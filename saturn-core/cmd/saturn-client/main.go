package main

import (
	"bufio"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/xtaci/smux"
	"saturn-core/pkg/config"
	"saturn-core/pkg/crypto"
	"saturn-core/pkg/handshake"
	"saturn-core/pkg/mux"
)

func main() {
	uriFlag := flag.String("u", "", "saturn:// URI configuration")
	listenFlag := flag.String("l", "127.0.0.1:10808", "Local inbound listen address (SOCKS5/HTTP)")
	serverFlag := flag.String("server", "", "Server address or IP")
	portFlag := flag.Int("port", 443, "Server port")
	uuidFlag := flag.String("uuid", "", "Client UUID")
	sniFlag := flag.String("sni", "dl.google.com", "Camouflage SNI")
	pbkFlag := flag.String("pbk", "", "Server X25519 Public Key")
	sidFlag := flag.String("sid", "", "ShortID")
	fpFlag := flag.String("fp", "chrome", "Fingerprint (chrome/firefox)")
	fragFlag := flag.String("frag", "1-5", "Fragmentation range (e.g. 1-5)")
	connsFlag := flag.Int("conns", 2, "Number of persistent connections (1-4)")
	flag.Parse()

	var profile *config.SaturnProfile
	var err error

	if *uriFlag != "" {
		profile, err = config.ParseURI(*uriFlag)
		if err != nil {
			log.Fatalf("Invalid Saturn URI: %v", err)
		}
	} else {
		if *serverFlag == "" || *pbkFlag == "" {
			log.Fatalf("Error: either -u <saturn://URI> or (-server and -pbk) are required")
		}
		profile = &config.SaturnProfile{
			Server:        *serverFlag,
			Port:          *portFlag,
			UUID:          *uuidFlag,
			SNI:           *sniFlag,
			PublicKey:     *pbkFlag,
			ShortID:       *sidFlag,
			Fingerprint:   *fpFlag,
			Fragmentation: *fragFlag,
			Remarks:       "Saturn-Direct",
		}
	}

	serverPubKey, err := crypto.ParseKey(profile.PublicKey)
	if err != nil {
		log.Fatalf("Invalid server public key: %v", err)
	}

	shortID := handshake.ParseShortID(profile.ShortID)

	handshakeCfg := &handshake.ClientHandshakeConfig{
		ServerAddress: profile.Server,
		ServerPort:    profile.Port,
		SNI:           profile.SNI,
		ServerPubKey:  serverPubKey,
		ShortID:       shortID,
		UUID:          profile.UUID,
		Fingerprint:   profile.Fingerprint,
		Fragmentation: profile.Fragmentation,
		Cipher:        "chacha20-poly1305",
	}

	poolSize := *connsFlag
	if poolSize < 1 {
		poolSize = 1
	} else if poolSize > 4 {
		poolSize = 4
	}

	pool := NewClientPool(profile.Server, profile.Port, handshakeCfg, poolSize)

	// Pre-warm primary tunnel session before opening inbound listener so initial ping and tests have 0ms dial latency
	log.Printf("[Saturn Client] Pre-warming primary tunnel connection to %s:%d...", profile.Server, profile.Port)
	if err := pool.Warmup(8 * time.Second); err != nil {
		log.Printf("[Saturn Client] Warning: initial warmup failed (%v), pool will dial on demand", err)
	} else {
		log.Printf("[Saturn Client] Primary tunnel session is active and ready")
	}
	go pool.Run()

	listener, err := net.Listen("tcp", *listenFlag)
	if err != nil {
		log.Fatalf("Failed to bind local inbound %s: %v", *listenFlag, err)
	}
	defer listener.Close()

	log.Printf("[Saturn Client] Local SOCKS5/HTTP inbound active on %s -> Remote %s:%d (SNI: %s, FP: %s, Frag: %s)",
		*listenFlag, profile.Server, profile.Port, profile.SNI, profile.Fingerprint, profile.Fragmentation)

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigChan
		log.Println("[Saturn Client] Shutting down...")
		_ = listener.Close()
		pool.Close()
		os.Exit(0)
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		go handleInboundConnection(conn, pool)
	}
}

// ClientPool maintains persistent smux connections to the Saturn server.
type ClientPool struct {
	server     string
	port       int
	cfg        *handshake.ClientHandshakeConfig
	poolSize   int
	sessions   []*smux.Session
	lock       sync.Mutex
	roundRobin int
	isClosed   bool
}

func NewClientPool(server string, port int, cfg *handshake.ClientHandshakeConfig, poolSize int) *ClientPool {
	return &ClientPool{
		server:   server,
		port:     port,
		cfg:      cfg,
		poolSize: poolSize,
	}
}

// Warmup synchronously establishes the primary tunnel session with a bounded timeout.
func (cp *ClientPool) Warmup(timeout time.Duration) error {
	type dialResult struct {
		sess *smux.Session
		err  error
	}
	ch := make(chan dialResult, 1)
	go func() {
		sess, err := cp.dialSession()
		ch <- dialResult{sess: sess, err: err}
	}()

	select {
	case res := <-ch:
		if res.err != nil {
			return res.err
		}
		cp.lock.Lock()
		cp.sessions = append(cp.sessions, res.sess)
		cp.lock.Unlock()
		return nil
	case <-time.After(timeout):
		return fmt.Errorf("warmup timed out after %v", timeout)
	}
}

func (cp *ClientPool) Run() {
	for !cp.isClosed {
		cp.maintainPool()
		time.Sleep(2 * time.Second)
	}
}

func (cp *ClientPool) maintainPool() {
	// 1. Clean dead sessions quickly under short lock
	cp.lock.Lock()
	alive := cp.sessions[:0]
	for _, s := range cp.sessions {
		if !s.IsClosed() {
			alive = append(alive, s)
		}
	}
	cp.sessions = alive
	currentCount := len(cp.sessions)
	cp.lock.Unlock()

	// 2. If more sessions needed, dial OUTSIDE the lock to never freeze inbounds!
	needed := cp.poolSize - currentCount
	for i := 0; i < needed; i++ {
		if cp.isClosed {
			break
		}
		sess, err := cp.dialSession()
		if err != nil {
			log.Printf("[Saturn Client] Background tunnel connection to %s:%d failed: %v", cp.server, cp.port, err)
			break
		}
		cp.lock.Lock()
		if !cp.isClosed {
			cp.sessions = append(cp.sessions, sess)
			log.Printf("[Saturn Client] Established tunnel connection (%d/%d active)", len(cp.sessions), cp.poolSize)
		} else {
			_ = sess.Close()
		}
		cp.lock.Unlock()
	}
}

func (cp *ClientPool) dialSession() (*smux.Session, error) {
	target := fmt.Sprintf("%s:%d", cp.server, cp.port)
	rawConn, err := net.DialTimeout("tcp", target, 10*time.Second)
	if err != nil {
		return nil, err
	}

	// Optimize socket for mobile networks (disable Nagle delay, keepalive for cellular NAT)
	if tc, ok := rawConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(15 * time.Second)
	}

	result, authedConn, err := handshake.ClientHandshake(rawConn, cp.cfg)
	if err != nil {
		_ = rawConn.Close()
		return nil, err
	}

	clientSendCipher, err := crypto.NewCipher(cp.cfg.Cipher, result.ClientKey)
	if err != nil {
		_ = authedConn.Close()
		return nil, err
	}
	clientRecvCipher, err := crypto.NewCipher(cp.cfg.Cipher, result.ServerKey)
	if err != nil {
		_ = authedConn.Close()
		return nil, err
	}

	framedConn := mux.NewDirectionalFramedConn(authedConn, clientSendCipher, clientRecvCipher, result.ClientIV, result.ServerIV)
	sess, err := smux.Client(framedConn, mux.DefaultSmuxConfig())
	if err != nil {
		_ = framedConn.Close()
		return nil, err
	}
	return sess, nil
}

func (cp *ClientPool) OpenStream() (*smux.Stream, error) {
	// Try existing healthy sessions from the pool
	for attempt := 0; attempt < 3; attempt++ {
		sess := cp.pickSession()
		if sess != nil {
			stream, err := sess.OpenStream()
			if err == nil {
				return stream, nil
			}
			// Failed session: close it and trigger background pool refresh
			_ = sess.Close()
			go cp.maintainPool()
		} else {
			break
		}
	}

	// Pool has no active sessions: dial on-demand outside lock
	sess, err := cp.dialSession()
	if err != nil {
		return nil, fmt.Errorf("tunnel unavailable: %w", err)
	}

	cp.lock.Lock()
	if !cp.isClosed {
		cp.sessions = append(cp.sessions, sess)
	}
	cp.lock.Unlock()

	return sess.OpenStream()
}

func (cp *ClientPool) pickSession() *smux.Session {
	cp.lock.Lock()
	defer cp.lock.Unlock()

	alive := cp.sessions[:0]
	for _, s := range cp.sessions {
		if !s.IsClosed() {
			alive = append(alive, s)
		}
	}
	cp.sessions = alive

	if len(cp.sessions) == 0 {
		return nil
	}

	cp.roundRobin = (cp.roundRobin + 1) % len(cp.sessions)
	return cp.sessions[cp.roundRobin]
}

func (cp *ClientPool) Close() {
	cp.lock.Lock()
	defer cp.lock.Unlock()
	cp.isClosed = true
	for _, s := range cp.sessions {
		_ = s.Close()
	}
	cp.sessions = nil
}

// handleInboundConnection detects SOCKS5 or HTTP and dispatches accordingly.
func handleInboundConnection(conn net.Conn, pool *ClientPool) {
	defer conn.Close()

	br := bufio.NewReader(conn)
	firstByte, err := br.Peek(1)
	if err != nil {
		return
	}

	if firstByte[0] == 0x05 {
		// SOCKS5 protocol
		handleSocks5(conn, br, pool)
	} else {
		// HTTP / HTTPS CONNECT
		handleHTTP(conn, br, pool)
	}
}

func handleSocks5(conn net.Conn, br *bufio.Reader, pool *ClientPool) {
	// Version identifier / method selection
	header := make([]byte, 2)
	if _, err := io.ReadFull(br, header); err != nil {
		return
	}
	numMethods := int(header[1])
	methods := make([]byte, numMethods)
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}

	// Reply: 0x05 (SOCKS5), 0x00 (NO AUTHENTICATION REQUIRED)
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// Request: VER (1B), CMD (1B), RSV (1B), ATYP (1B)
	reqHead := make([]byte, 4)
	if _, err := io.ReadFull(br, reqHead); err != nil {
		return
	}
	cmd := reqHead[1]
	atyp := reqHead[3]

	if cmd != 0x01 { // Only CONNECT is supported
		_, _ = conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Command not supported
		return
	}

	var targetHost string
	switch atyp {
	case 0x01: // IPv4
		var ipBuf [4]byte
		if _, err := io.ReadFull(br, ipBuf[:]); err != nil {
			return
		}
		targetHost = net.IP(ipBuf[:]).String()
	case 0x03: // Domain
		lenByte, err := br.ReadByte()
		if err != nil {
			return
		}
		domainBuf := make([]byte, int(lenByte))
		if _, err := io.ReadFull(br, domainBuf); err != nil {
			return
		}
		targetHost = string(domainBuf)
	case 0x04: // IPv6
		var ipBuf [16]byte
		if _, err := io.ReadFull(br, ipBuf[:]); err != nil {
			return
		}
		targetHost = fmt.Sprintf("[%s]", net.IP(ipBuf[:]).String())
	default:
		_, _ = conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(br, portBuf[:]); err != nil {
		return
	}
	targetPort := strconv.Itoa(int(binary.BigEndian.Uint16(portBuf[:])))
	targetAddr := net.JoinHostPort(targetHost, targetPort)

	stream, err := pool.OpenStream()
	if err != nil {
		_, _ = conn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer stream.Close()

	if err := mux.WriteAddressHeader(stream, targetAddr); err != nil {
		_, _ = conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// SOCKS5 success response: 0x05 0x00 0x00 0x01 0 0 0 0 0 0
	if _, err := conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	// Relay connection
	relayBufferedConn(conn, br, stream)
}

func handleHTTP(conn net.Conn, br *bufio.Reader, pool *ClientPool) {
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}

	target := req.Host
	if !strings.Contains(target, ":") {
		if req.Method == "CONNECT" {
			target += ":443"
		} else {
			target += ":80"
		}
	}

	stream, err := pool.OpenStream()
	if err != nil {
		_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	defer stream.Close()

	if err := mux.WriteAddressHeader(stream, target); err != nil {
		_, _ = conn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}

	if req.Method == "CONNECT" {
		if _, err := conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
			return
		}
		relayBufferedConn(conn, br, stream)
	} else {
		// Forward non-CONNECT HTTP request
		if err := req.Write(stream); err != nil {
			return
		}
		relayBufferedConn(conn, br, stream)
	}
}

func relayBufferedConn(conn net.Conn, br *bufio.Reader, stream *smux.Stream) {
	var once sync.Once
	closeBoth := func() {
		_ = conn.Close()
		_ = stream.Close()
	}

	done := make(chan struct{})

	// Inbound -> Smux Stream (Upload)
	go func() {
		buf := make([]byte, 32*1024)
		_, err := io.CopyBuffer(stream, br, buf)
		if err != nil {
			once.Do(closeBoth)
			return
		}
		_ = stream.Close()
	}()

	// Smux Stream -> Inbound (Download / Response)
	go func() {
		defer close(done)
		buf := make([]byte, 32*1024)
		_, _ = io.CopyBuffer(conn, stream, buf)
		once.Do(closeBoth)
	}()

	<-done
	once.Do(closeBoth)
}
