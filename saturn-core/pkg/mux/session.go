package mux

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/smux"
	"saturn-core/pkg/crypto"
	"saturn-core/pkg/framing"
)

// FramedConn wraps a net.Conn with Saturn binary AEAD packet framing.
type FramedConn struct {
	net.Conn
	pm        *framing.PacketManager
	readBuf   bytes.Buffer
	readMutex sync.Mutex
	writeMutex sync.Mutex
}

// NewFramedConn creates a new Saturn framed connection.
func NewFramedConn(conn net.Conn, cipher *crypto.Cipher, baseIV []byte) *FramedConn {
	return &FramedConn{
		Conn: conn,
		pm:   framing.NewPacketManager(cipher, baseIV),
	}
}

// NewDirectionalFramedConn creates a new Saturn framed connection with separate directional ciphers and IVs.
func NewDirectionalFramedConn(conn net.Conn, sendCipher, recvCipher *crypto.Cipher, sendIV, recvIV []byte) *FramedConn {
	return &FramedConn{
		Conn: conn,
		pm:   framing.NewDirectionalPacketManager(sendCipher, recvCipher, sendIV, recvIV),
	}
}

// Read implements io.Reader with buffered packet extraction.
func (fc *FramedConn) Read(p []byte) (int, error) {
	fc.readMutex.Lock()
	defer fc.readMutex.Unlock()

	for fc.readBuf.Len() == 0 {
		_, _, payload, err := fc.pm.ReadPacket(fc.Conn)
		if err != nil {
			return 0, err
		}
		fc.readBuf.Write(payload)
	}

	return fc.readBuf.Read(p)
}

// Write implements io.Writer by packaging data into Saturn framed packets.
func (fc *FramedConn) Write(p []byte) (int, error) {
	fc.writeMutex.Lock()
	defer fc.writeMutex.Unlock()

	sent := 0
	for sent < len(p) {
		chunkSize := len(p) - sent
		if chunkSize > framing.MaxPayloadChunk {
			chunkSize = framing.MaxPayloadChunk
		}
		err := fc.pm.WritePacket(fc.Conn, framing.PacketTypeData, 0, p[sent:sent+chunkSize])
		if err != nil {
			return sent, err
		}
		sent += chunkSize
	}
	return sent, nil
}

// UplinkRotationThreshold defines the byte volume limit before mobile flow rotation (1.5 MB).
const UplinkRotationThreshold int64 = 1572864

// TrackedSession wraps an smux session with atomic uplink byte accounting for flow rotation.
type TrackedSession struct {
	Session   *smux.Session
	bytesSent atomic.Int64
}

// NewTrackedSession creates a new TrackedSession.
func NewTrackedSession(sess *smux.Session) *TrackedSession {
	return &TrackedSession{
		Session: sess,
	}
}

// RecordUplinkBytes atomically records bytes written through the session.
func (ts *TrackedSession) RecordUplinkBytes(n int64) {
	if n > 0 {
		ts.bytesSent.Add(n)
	}
}

// GetUplinkBytes returns the total uplink bytes sent through this session.
func (ts *TrackedSession) GetUplinkBytes() int64 {
	return ts.bytesSent.Load()
}

// ShouldRotate checks if the session uplink traffic exceeded the 1.5MB threshold.
func (ts *TrackedSession) ShouldRotate() bool {
	return ts.bytesSent.Load() >= UplinkRotationThreshold
}

// Multiplexer manages smux v2 sessions over a pool of persistent connections with uplink rotation.
type Multiplexer struct {
	sessions []*TrackedSession
	lock     sync.RWMutex
	idx      int
}

// NewMultiplexer creates a multiplexer.
func NewMultiplexer() *Multiplexer {
	return &Multiplexer{
		sessions: make([]*TrackedSession, 0, 4),
	}
}

// AddSession adds an active smux session to the multiplexer pool.
func (m *Multiplexer) AddSession(sess *smux.Session) *TrackedSession {
	m.lock.Lock()
	defer m.lock.Unlock()
	ts := NewTrackedSession(sess)
	m.sessions = append(m.sessions, ts)
	return ts
}

// AddTrackedSession adds an existing TrackedSession to the multiplexer pool.
func (m *Multiplexer) AddTrackedSession(ts *TrackedSession) {
	m.lock.Lock()
	defer m.lock.Unlock()
	m.sessions = append(m.sessions, ts)
}

// GetTrackedSessions returns a copy of the tracked sessions.
func (m *Multiplexer) GetTrackedSessions() []*TrackedSession {
	m.lock.RLock()
	defer m.lock.RUnlock()
	res := make([]*TrackedSession, len(m.sessions))
	copy(res, m.sessions)
	return res
}

// OpenStream opens a new multiplexed stream across the session pool, prioritizing unthrottled sessions.
func (m *Multiplexer) OpenStream() (*smux.Stream, error) {
	m.lock.Lock()
	defer m.lock.Unlock()

	// Clean dead sessions
	alive := m.sessions[:0]
	for _, s := range m.sessions {
		if !s.Session.IsClosed() {
			alive = append(alive, s)
		}
	}
	m.sessions = alive

	if len(m.sessions) == 0 {
		return nil, errors.New("no active sessions available in pool")
	}

	// 1. Prioritize sessions that have not exceeded the 1.5MB uplink rotation threshold
	for i := 0; i < len(m.sessions); i++ {
		targetIdx := (m.idx + i) % len(m.sessions)
		if !m.sessions[targetIdx].ShouldRotate() {
			m.idx = (targetIdx + 1) % len(m.sessions)
			return m.sessions[targetIdx].Session.OpenStream()
		}
	}

	// 2. All sessions exceeded threshold: fallback to round-robin
	m.idx = (m.idx + 1) % len(m.sessions)
	return m.sessions[m.idx].Session.OpenStream()
}

// Close closes all sessions in the pool.
func (m *Multiplexer) Close() {
	m.lock.Lock()
	defer m.lock.Unlock()
	for _, s := range m.sessions {
		_ = s.Session.Close()
	}
	m.sessions = nil
}

// DefaultSmuxConfig returns tuned smux v2 configuration for low latency, Irancell mobile stability, and BBR.
func DefaultSmuxConfig() *smux.Config {
	cfg := smux.DefaultConfig()
	cfg.Version = 2
	cfg.KeepAliveInterval = 8 * time.Second
	cfg.KeepAliveTimeout = 20 * time.Second
	cfg.MaxFrameSize = 1024                // Strict MSS alignment (<= 1024 bytes per frame)
	cfg.MaxReceiveBuffer = 8 * 1024 * 1024 // 8MB buffer for mobile uplinks
	cfg.MaxStreamBuffer = 2 * 1024 * 1024  // 2MB per stream
	return cfg
}

// RelayStreams proxies bidirectional traffic between two network streams with EOF handling.
func RelayStreams(stream1, stream2 io.ReadWriteCloser) {
	var once sync.Once
	closeBoth := func() {
		_ = stream1.Close()
		_ = stream2.Close()
	}

	done := make(chan struct{})

	// stream1 (smux stream) -> stream2 (remote target TCP)
	go func() {
		buf := make([]byte, 32*1024)
		_, err := io.CopyBuffer(stream2, stream1, buf)
		if err != nil {
			once.Do(closeBoth)
			return
		}
		if tc, ok := stream2.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
	}()

	// stream2 (remote target TCP) -> stream1 (smux stream)
	go func() {
		defer close(done)
		buf := make([]byte, 32*1024)
		_, _ = io.CopyBuffer(stream1, stream2, buf)
		once.Do(closeBoth)
	}()

	<-done
	once.Do(closeBoth)
}

// WriteAddressHeader writes target address [addrType, len, host..., port (2B)] to stream.
func WriteAddressHeader(w io.Writer, target string) error {
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return err
	}
	port, err := net.LookupPort("tcp", portStr)
	if err != nil {
		return err
	}

	ip := net.ParseIP(host)
	if ip4 := ip.To4(); ip4 != nil {
		// IPv4: type 0x01 + 4 bytes + 2 bytes port
		buf := make([]byte, 1+4+2)
		buf[0] = 0x01
		copy(buf[1:5], ip4)
		binary.BigEndian.PutUint16(buf[5:7], uint16(port))
		_, err = w.Write(buf)
		return err
	} else if ip6 := ip.To16(); ip6 != nil {
		// IPv6: type 0x04 + 16 bytes + 2 bytes port
		buf := make([]byte, 1+16+2)
		buf[0] = 0x04
		copy(buf[1:17], ip6)
		binary.BigEndian.PutUint16(buf[17:19], uint16(port))
		_, err = w.Write(buf)
		return err
	}

	// Domain: type 0x03 + 1 byte len + domain bytes + 2 bytes port
	hostBytes := []byte(host)
	if len(hostBytes) > 255 {
		return errors.New("domain name too long")
	}
	buf := make([]byte, 1+1+len(hostBytes)+2)
	buf[0] = 0x03
	buf[1] = byte(len(hostBytes))
	copy(buf[2:2+len(hostBytes)], hostBytes)
	binary.BigEndian.PutUint16(buf[2+len(hostBytes):], uint16(port))
	_, err = w.Write(buf)
	return err
}

// ReadAddressHeader reads target address from stream.
func ReadAddressHeader(r io.Reader) (string, error) {
	var typeBuf [1]byte
	if _, err := io.ReadFull(r, typeBuf[:]); err != nil {
		return "", err
	}

	switch typeBuf[0] {
	case 0x01: // IPv4
		var buf [6]byte
		if _, err := io.ReadFull(r, buf[:]); err != nil {
			return "", err
		}
		ip := net.IP(buf[0:4])
		port := binary.BigEndian.Uint16(buf[4:6])
		return fmt.Sprintf("%s:%d", ip.String(), port), nil

	case 0x04: // IPv6
		var buf [18]byte
		if _, err := io.ReadFull(r, buf[:]); err != nil {
			return "", err
		}
		ip := net.IP(buf[0:16])
		port := binary.BigEndian.Uint16(buf[16:18])
		return fmt.Sprintf("[%s]:%d", ip.String(), port), nil

	case 0x03: // Domain
		var lenBuf [1]byte
		if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
			return "", err
		}
		domainBuf := make([]byte, int(lenBuf[0])+2)
		if _, err := io.ReadFull(r, domainBuf); err != nil {
			return "", err
		}
		domain := string(domainBuf[:lenBuf[0]])
		port := binary.BigEndian.Uint16(domainBuf[lenBuf[0]:])
		return fmt.Sprintf("%s:%d", domain, port), nil

	default:
		return "", fmt.Errorf("unsupported address type 0x%02x", typeBuf[0])
	}
}
