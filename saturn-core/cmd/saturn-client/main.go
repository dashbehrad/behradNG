package main

import (
	"bufio"
	"encoding/binary"
	"errors"
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
	configFlag := flag.String("c", "", "Path to client JSON config file")
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
	dpiVectorFlag := flag.String("dpi-vector", "auto", "Alan DPI evasion vector (auto/VEC-21/VEC-22/VEC-01/etc.)")
	connsFlag := flag.Int("conns", 2, "Number of persistent connections (1-4)")
	flag.Parse()

	var profile *config.SaturnProfile
	var err error

	if *configFlag != "" {
		clientCfg, err := config.LoadClientConfig(*configFlag)
		if err != nil {
			log.Fatalf("Failed to load client config %s: %v", *configFlag, err)
		}
		profile = &clientCfg.Profile
		if *listenFlag == "127.0.0.1:10808" && clientCfg.LocalListen != "" {
			*listenFlag = clientCfg.LocalListen
		}
		if *connsFlag == 2 && clientCfg.Connections > 0 {
			*connsFlag = clientCfg.Connections
		}
	} else if *uriFlag != "" {
		profile, err = config.ParseURI(*uriFlag)
		if err != nil {
			log.Fatalf("Invalid Saturn URI: %v", err)
		}
	} else if *serverFlag != "" && *pbkFlag != "" {
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
	} else {
		// Auto-detect client.json or config.json
		candidatePaths := []string{"client.json", "config.json"}
		for _, cp := range candidatePaths {
			if _, statErr := os.Stat(cp); statErr == nil {
				clientCfg, err := config.LoadClientConfig(cp)
				if err == nil && clientCfg.Profile.Server != "" && clientCfg.Profile.Server != "YOUR_SERVER_IP" {
					log.Printf("[Saturn Client] Automatically loaded configuration from %s", cp)
					profile = &clientCfg.Profile
					if *listenFlag == "127.0.0.1:10808" && clientCfg.LocalListen != "" {
						*listenFlag = clientCfg.LocalListen
					}
					if *connsFlag == 2 && clientCfg.Connections > 0 {
						*connsFlag = clientCfg.Connections
					}
					break
				}
			}
		}

		if profile == nil {
			templatePath := "client.json"
			if _, statErr := os.Stat(templatePath); os.IsNotExist(statErr) {
				templateCfg := &config.ClientConfig{
					LocalListen: "127.0.0.1:10808",
					Connections: 2,
					Profile: config.SaturnProfile{
						Server:        "YOUR_SERVER_IP",
						Port:          443,
						UUID:          "d3b07384-d113-4660-8438-d6eb406e2329",
						PublicKey:     "YOUR_SERVER_PUBLIC_KEY_BASE64",
						ShortID:       "a1b2c3d4",
						SNI:           "dl.google.com",
						ALPN:          "h2",
						Fingerprint:   "chrome",
						Cipher:        "chacha20-poly1305",
						DpiVector:     "VEC-10",
						Remarks:       "Saturn-Profile",
					},
				}
				_ = config.SaveClientConfig(templatePath, templateCfg)
				fmt.Printf("[*] Template client configuration generated at: %s\n\n", templatePath)
			}

			fmt.Println("🪐 Saturn Client - Ready to Connect")
			fmt.Println("------------------------------------------------------------------")
			fmt.Println("Option 1: Connect via URI:")
			fmt.Println("  saturn-client -u \"saturn://UUID@SERVER_IP:443?pbk=...&sid=...\"")
			fmt.Println("\nOption 2: Connect via configuration file:")
			fmt.Println("  Edit client.json with your server details and run:")
			fmt.Println("  saturn-client -c client.json")
			fmt.Println("\nOption 3: Connect via direct flags:")
			fmt.Println("  saturn-client -server 1.2.3.4 -port 443 -uuid ... -pbk ... -sid ...")
			fmt.Println("------------------------------------------------------------------")
			os.Exit(0)
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
		DpiVector:     *dpiVectorFlag,
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
	sessions   []*mux.TrackedSession
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
		cp.sessions = append(cp.sessions, mux.NewTrackedSession(res.sess))
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
	// 1. Clean dead or rotated sessions quickly under short lock
	cp.lock.Lock()
	alive := cp.sessions[:0]
	for _, s := range cp.sessions {
		if !s.Session.IsClosed() {
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
			cp.sessions = append(cp.sessions, mux.NewTrackedSession(sess))
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
		_ = tc.SetKeepAlivePeriod(10 * time.Second)
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

func (cp *ClientPool) OpenStream() (*smux.Stream, *mux.TrackedSession, error) {
	// Try existing healthy sessions from the pool with strict 3-second timeout per attempt
	for attempt := 0; attempt < 3; attempt++ {
		ts := cp.pickSession()
		if ts != nil {
			stream, err := openStreamWithTimeout(ts.Session, 3*time.Second)
			if err == nil {
				return stream, ts, nil
			}
			// Failed/timed out session: evict immediately from pool, close in background, and trigger background pool refresh
			log.Printf("[Saturn Client] Session stream open failed (%v), evicting and failing over", err)
			cp.evictSession(ts)
			go cp.maintainPool()
		} else {
			break
		}
	}

	// Pool has no active sessions: dial on-demand outside lock
	sess, err := cp.dialSession()
	if err != nil {
		return nil, nil, fmt.Errorf("tunnel unavailable: %w", err)
	}

	ts := mux.NewTrackedSession(sess)
	cp.lock.Lock()
	if !cp.isClosed {
		cp.sessions = append(cp.sessions, ts)
	}
	cp.lock.Unlock()

	stream, err := openStreamWithTimeout(sess, 4*time.Second)
	if err != nil {
		return nil, nil, err
	}
	return stream, ts, nil
}

func (cp *ClientPool) evictSession(ts *mux.TrackedSession) {
	cp.lock.Lock()
	alive := cp.sessions[:0]
	for _, s := range cp.sessions {
		if s != ts && !s.Session.IsClosed() {
			alive = append(alive, s)
		}
	}
	cp.sessions = alive
	cp.lock.Unlock()
	go func() { _ = ts.Session.Close() }()
}

func openStreamWithTimeout(sess *smux.Session, timeout time.Duration) (*smux.Stream, error) {
	type result struct {
		stream *smux.Stream
		err    error
	}
	ch := make(chan result, 1)
	go func() {
		s, err := sess.OpenStream()
		ch <- result{stream: s, err: err}
	}()

	select {
	case res := <-ch:
		return res.stream, res.err
	case <-time.After(timeout):
		return nil, errors.New("open stream timed out")
	}
}

func (cp *ClientPool) pickSession() *mux.TrackedSession {
	cp.lock.Lock()
	defer cp.lock.Unlock()

	alive := cp.sessions[:0]
	for _, s := range cp.sessions {
		if !s.Session.IsClosed() {
			alive = append(alive, s)
		}
	}
	cp.sessions = alive

	if len(cp.sessions) == 0 {
		return nil
	}

	// Prefer session that has not reached UplinkRotationThreshold (1.5 MB)
	for i := 0; i < len(cp.sessions); i++ {
		idx := (cp.roundRobin + i) % len(cp.sessions)
		if !cp.sessions[idx].ShouldRotate() {
			cp.roundRobin = (idx + 1) % len(cp.sessions)
			return cp.sessions[idx]
		}
	}

	cp.roundRobin = (cp.roundRobin + 1) % len(cp.sessions)
	return cp.sessions[cp.roundRobin]
}

func (cp *ClientPool) Close() {
	cp.lock.Lock()
	defer cp.lock.Unlock()
	cp.isClosed = true
	for _, s := range cp.sessions {
		_ = s.Session.Close()
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
	targetHost = strings.TrimPrefix(targetHost, "//")
	targetAddr := net.JoinHostPort(targetHost, targetPort)

	stream, tracked, err := pool.OpenStream()
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
	relayBufferedConn(conn, br, stream, tracked)
}

func handleHTTP(conn net.Conn, br *bufio.Reader, pool *ClientPool) {
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}

	target := strings.TrimPrefix(req.Host, "//")
	if !strings.Contains(target, ":") {
		if req.Method == "CONNECT" {
			target += ":443"
		} else {
			target += ":80"
		}
	}

	stream, tracked, err := pool.OpenStream()
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
		relayBufferedConn(conn, br, stream, tracked)
	} else {
		// Forward non-CONNECT HTTP request
		if err := req.Write(stream); err != nil {
			return
		}
		relayBufferedConn(conn, br, stream, tracked)
	}
}

func relayBufferedConn(conn net.Conn, br *bufio.Reader, stream *smux.Stream, tracked *mux.TrackedSession) {
	var once sync.Once
	closeBoth := func() {
		_ = conn.Close()
		_ = stream.Close()
	}

	done := make(chan struct{})

	// Inbound -> Smux Stream (Upload)
	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := br.Read(buf)
			if n > 0 {
				if _, werr := stream.Write(buf[:n]); werr != nil {
					once.Do(closeBoth)
					return
				}
				if tracked != nil {
					tracked.RecordUplinkBytes(int64(n))
				}
			}
			if err != nil {
				if err != io.EOF {
					once.Do(closeBoth)
					return
				}
				break
			}
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
