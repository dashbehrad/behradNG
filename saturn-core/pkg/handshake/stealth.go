package handshake

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"strings"
	"sync"
	"time"

	"saturn-core/pkg/crypto"
)

const (
	HandshakeTimeout = 10 * time.Second
	MaxClockSkewSec  = 300 // Bounded ±300s (5-minute) anti-replay timestamp window for real mobile networks
)

// ReplayCache provides a thread-safe, memory-bounded TTL cache to prevent replay attacks.
type ReplayCache struct {
	mu      sync.Mutex
	entries map[[32]byte]int64
}

func NewReplayCache() *ReplayCache {
	rc := &ReplayCache{
		entries: make(map[[32]byte]int64),
	}
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		for range ticker.C {
			rc.cleanup()
		}
	}()
	return rc
}

func (rc *ReplayCache) CheckAndAdd(token []byte, ttlSeconds int64) bool {
	if len(token) != 32 {
		return false
	}
	var k [32]byte
	copy(k[:], token)

	rc.mu.Lock()
	defer rc.mu.Unlock()

	now := time.Now().Unix()
	if exp, exists := rc.entries[k]; exists && exp > now {
		return false // Replay detected!
	}

	rc.entries[k] = now + ttlSeconds
	return true
}

func (rc *ReplayCache) cleanup() {
	rc.mu.Lock()
	defer rc.mu.Unlock()

	now := time.Now().Unix()
	for k, exp := range rc.entries {
		if exp <= now {
			delete(rc.entries, k)
		}
	}
}

var globalReplayCache = NewReplayCache()

// ServerHandshakeConfig holds server credentials for stealth validation.
type ServerHandshakeConfig struct {
	PrivateKey   []byte
	PublicKey    []byte
	UUID         string
	ShortID      []byte // 4 to 8 bytes
	FallbackDest string // e.g. "dl.google.com:443"
	Cipher       string
}

// HandshakeResult represents successful authentication output.
type HandshakeResult struct {
	SharedSecret []byte
	AEADKey      []byte
	BaseIV       []byte
	ClientKey    []byte
	ClientIV     []byte
	ServerKey    []byte
	ServerIV     []byte
	ClientRandom []byte
}

// readInitialClientRecord reads the initial TLS record, assembling fragmented records or preambles if necessary.
func readInitialClientRecord(r io.Reader) ([]byte, error) {
	header := make([]byte, 5)

	// Loop to skip any 0-length TLS records (preambles like 16 03 04 00 00 or 16 03 01 00 00)
	for {
		if _, err := io.ReadFull(r, header); err != nil {
			return nil, err
		}
		// Check if this is a TLS record (0x16 0x03)
		if header[0] == 0x16 && header[1] == 0x03 {
			recordLen := int(binary.BigEndian.Uint16(header[3:5]))
			if recordLen == 0 {
				// Empty preamble record (e.g. zero0304, 2xzero, 4xzero), read next record
				continue
			}
			if recordLen > 0 && recordLen <= 16384 {
				firstPayload := make([]byte, recordLen)
				if _, err := io.ReadFull(r, firstPayload); err != nil {
					return nil, err
				}

				// Check if this payload starts a ClientHello (type 0x01)
				if len(firstPayload) >= 4 && firstPayload[0] == 0x01 {
					totalHandshakeLen := int(firstPayload[1])<<16 | int(firstPayload[2])<<8 | int(firstPayload[3])
					totalExpected := 4 + totalHandshakeLen

					if len(firstPayload) >= totalExpected {
						// Single complete ClientHello record
						fullRecord := make([]byte, 5+len(firstPayload))
						copy(fullRecord, header)
						copy(fullRecord[5:], firstPayload)
						return fullRecord, nil
					}

					// Multi-record fragmented ClientHello (e.g. Alan DPI vectors)
					assembled := make([]byte, 0, totalExpected)
					assembled = append(assembled, firstPayload...)

					for len(assembled) < totalExpected {
						subHeader := make([]byte, 5)
						if _, err := io.ReadFull(r, subHeader); err != nil {
							break
						}
						if subHeader[0] != 0x16 {
							break
						}
						subLen := int(binary.BigEndian.Uint16(subHeader[3:5]))
						if subLen == 0 {
							continue
						}
						subPayload := make([]byte, subLen)
						if _, err := io.ReadFull(r, subPayload); err != nil {
							break
						}
						assembled = append(assembled, subPayload...)
					}

					fullRecord := make([]byte, 5+len(assembled))
					fullRecord[0] = 0x16
					fullRecord[1] = 0x03
					fullRecord[2] = 0x01
					binary.BigEndian.PutUint16(fullRecord[3:5], uint16(len(assembled)))
					copy(fullRecord[5:], assembled)
					return fullRecord, nil
				}

				fullRecord := make([]byte, 5+recordLen)
				copy(fullRecord, header)
				copy(fullRecord[5:], firstPayload)
				return fullRecord, nil
			}
		}
		break
	}

	// Non-TLS or abnormal record: read any immediate buffered data (probe fallback)
	buf := make([]byte, 4096)
	copy(buf, header)
	if conn, ok := r.(net.Conn); ok {
		_ = conn.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
		n, _ := conn.Read(buf[5:])
		_ = conn.SetReadDeadline(time.Time{})
		return buf[:5+n], nil
	}
	return header, nil
}

// HandleServerHandshake performs stealth authentication or transparent active probe fallback.
func HandleServerHandshake(clientConn net.Conn, cfg *ServerHandshakeConfig) (*HandshakeResult, net.Conn, error) {
	_ = clientConn.SetReadDeadline(time.Now().Add(HandshakeTimeout))

	// Reliably assemble initial client record (handling TLS record fragmentation)
	initialBytes, err := readInitialClientRecord(clientConn)
	if err != nil {
		return nil, nil, err
	}

	// Attempt to authenticate client
	result, authErr := verifyClientHello(initialBytes, cfg)
	if authErr != nil {
		// AUTH FAILED: Active Probe / DPI Scanner detected!
		// Transparently fallback and splice connection directly to FallbackDest
		fallbackDest := cfg.FallbackDest
		if fallbackDest == "" {
			fallbackDest = "dl.google.com:443"
		}
		if !strings.Contains(fallbackDest, ":") {
			fallbackDest += ":443"
		}

		go spliceToFallback(clientConn, fallbackDest, initialBytes)
		return nil, nil, fmt.Errorf("active probe redirected to %s: %w", fallbackDest, authErr)
	}

	// AUTH SUCCEEDED: Send stealth ServerHello response
	_ = clientConn.SetReadDeadline(time.Time{})
	serverHello, err := buildStealthServerHello(result.SharedSecret, result.ClientRandom)
	if err != nil {
		return nil, nil, err
	}
	if _, err := clientConn.Write(serverHello); err != nil {
		return nil, nil, err
	}

	return result, clientConn, nil
}

// verifyClientHello validates TLS 1.3 ClientHello fields against Saturn credentials.
func verifyClientHello(data []byte, cfg *ServerHandshakeConfig) (*HandshakeResult, error) {
	if len(data) < 43 {
		return nil, errors.New("data too short for TLS ClientHello")
	}

	// Verify TLS Record Layer: Type 0x16 (Handshake), Version 0x0301 or 0x0303
	if data[0] != 0x16 || (data[1] != 0x03) {
		return nil, errors.New("not a TLS record")
	}

	// Handshake type 0x01 (ClientHello)
	if data[5] != 0x01 {
		return nil, errors.New("not a ClientHello")
	}

	// ClientRandom: bytes 11..43 (32 bytes)
	clientRandom := data[11:43]

	// Extract timestamp (bytes 0..7) and ShortID (bytes 8..15) from ClientRandom
	clientTs := binary.BigEndian.Uint64(clientRandom[0:8])
	now := uint64(time.Now().Unix())
	diff := int64(clientTs) - int64(now)
	if math.Abs(float64(diff)) > MaxClockSkewSec {
		return nil, fmt.Errorf("timestamp skew too large: diff=%ds", diff)
	}

	// ShortID verification (8 bytes)
	if len(cfg.ShortID) > 0 {
		checkLen := len(cfg.ShortID)
		if checkLen > 8 {
			checkLen = 8
		}
		if subtle.ConstantTimeCompare(clientRandom[8:8+checkLen], cfg.ShortID[:checkLen]) != 1 {
			return nil, errors.New("shortID mismatch")
		}
	}

	// Extract SessionID: byte 43 is SessionID length
	sessionIDLen := int(data[43])
	if len(data) < 44+sessionIDLen {
		return nil, errors.New("truncated SessionID")
	}
	sessionID := data[44 : 44+sessionIDLen]
	if len(sessionID) != 32 {
		return nil, errors.New("invalid session ID length for auth token")
	}

	// Locate client ephemeral public key and token from standard KeyShare (0x0033) and SessionID
	clientPubKey, token := extractSaturnCredentials(data, sessionID)
	if len(clientPubKey) != crypto.KeySize {
		return nil, errors.New("client ephemeral public key not found in KeyShare")
	}

	// Anti-Replay: Check token against bounded memory cache
	if !globalReplayCache.CheckAndAdd(token, 120) {
		return nil, errors.New("replay attack detected: token already seen")
	}

	// Compute shared secret via X25519
	sharedSecret, err := crypto.ComputeSharedSecret(cfg.PrivateKey, clientPubKey)
	if err != nil {
		return nil, fmt.Errorf("ecdh computation failed: %w", err)
	}

	// Derive authentication key
	salt := []byte(crypto.TokenSalt)
	if len(cfg.PublicKey) == crypto.KeySize {
		salt = cfg.PublicKey
	}
	authKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyAuth, 32)
	if err != nil {
		return nil, err
	}

	// Derive expected token = HMAC-SHA256(authKey, timestamp || shortID || clientRandom[16:32])
	expectedToken := crypto.ComputeAuthToken(authKey, clientRandom)
	if !crypto.VerifyAuthToken(token, expectedToken[:]) {
		// Also check with UUID salt if configured
		if cfg.UUID != "" {
			uuidKey, _ := crypto.DeriveKey(sharedSecret, []byte(cfg.UUID), crypto.InfoKeyAuth, 32)
			uuidToken := crypto.ComputeAuthToken(uuidKey, clientRandom)
			if !crypto.VerifyAuthToken(token, uuidToken[:]) {
				return nil, errors.New("invalid authentication token")
			}
		} else {
			return nil, errors.New("invalid authentication token")
		}
	}

	// Derive AEAD session keys and directional base IVs
	aeadKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyAEAD, 32)
	if err != nil {
		return nil, err
	}
	baseIV, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyIV, crypto.NonceSize)
	if err != nil {
		return nil, err
	}
	clientKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoClientKey, 32)
	if err != nil {
		return nil, err
	}
	clientIV, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoClientIV, crypto.NonceSize)
	if err != nil {
		return nil, err
	}
	serverKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoServerKey, 32)
	if err != nil {
		return nil, err
	}
	serverIV, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoServerIV, crypto.NonceSize)
	if err != nil {
		return nil, err
	}

	return &HandshakeResult{
		SharedSecret: sharedSecret,
		AEADKey:      aeadKey,
		BaseIV:       baseIV,
		ClientKey:    clientKey,
		ClientIV:     clientIV,
		ServerKey:    serverKey,
		ServerIV:     serverIV,
		ClientRandom: clientRandom,
	}, nil
}

// extractSaturnCredentials extracts client X25519 public key from KeyShare (0x0033) and auth token from SessionID.
func extractSaturnCredentials(data, sessionID []byte) ([]byte, []byte) {
	extOffset := findExtensionsOffset(data)
	if extOffset > 0 && extOffset+2 <= len(data) {
		extLen := int(binary.BigEndian.Uint16(data[extOffset : extOffset+2]))
		extEnd := extOffset + 2 + extLen
		if extEnd > len(data) {
			extEnd = len(data)
		}
		curr := extOffset + 2
		for curr+4 <= extEnd {
			extType := binary.BigEndian.Uint16(data[curr : curr+2])
			extDataLen := int(binary.BigEndian.Uint16(data[curr+2 : curr+4]))
			curr += 4
			if curr+extDataLen > extEnd {
				break
			}
			// Extension 0x0033: TLS 1.3 KeyShare
			if extType == 0x0033 && extDataLen >= 36 {
				ksData := data[curr : curr+extDataLen]
				if len(ksData) >= 4 {
					ksListLen := int(binary.BigEndian.Uint16(ksData[:2]))
					idx := 2
					for idx+4 <= len(ksData) && idx < 2+ksListLen {
						group := binary.BigEndian.Uint16(ksData[idx : idx+2])
						kLen := int(binary.BigEndian.Uint16(ksData[idx+2 : idx+4]))
						idx += 4
						if group == 0x001d && kLen == 32 && idx+32 <= len(ksData) {
							pubKey := ksData[idx : idx+32]
							return pubKey, sessionID
						}
						idx += kLen
					}
				}
			}
			curr += extDataLen
		}
	}

	return nil, nil
}

// findExtensionsOffset finds the start of extensions in a TLS ClientHello.
func findExtensionsOffset(data []byte) int {
	if len(data) < 44 {
		return -1
	}
	sessLen := int(data[43])
	idx := 44 + sessLen
	if idx+2 > len(data) {
		return -1
	}
	// Cipher suites
	csLen := int(binary.BigEndian.Uint16(data[idx : idx+2]))
	idx += 2 + csLen
	if idx+1 > len(data) {
		return -1
	}
	// Compression methods
	compLen := int(data[idx])
	idx += 1 + compLen
	if idx+2 > len(data) {
		return -1
	}
	return idx
}

// buildStealthServerHello crafts a genuine-looking TLS 1.3 ServerHello response.
func buildStealthServerHello(sharedSecret, clientRandom []byte) ([]byte, error) {
	// Generate server random
	var serverRandom [32]byte
	_, _ = io.ReadFull(rand.Reader, serverRandom[:])

	// Compute confirmation tag in server random bytes 16..32
	h := hmac.New(sha256.New, sharedSecret)
	h.Write([]byte("saturn-server-confirm"))
	h.Write(clientRandom)
	copy(serverRandom[16:], h.Sum(nil)[:16])

	// TLS 1.3 ServerHello packet
	// Record layer: 0x16 0x03 0x03, Length (2B)
	// Handshake: 0x02, Length (3B), Version: 0x0303, Random (32B), SessionID (0B), CipherSuite (2B: 0x1301 TLS_AES_128_GCM_SHA256), Compression (1B: 0x00)
	// Extensions: Supported Versions (0x002b -> 0x0304 TLS 1.3)
	shPayload := []byte{
		0x02,             // ServerHello
		0x00, 0x00, 0x46, // length 70
		0x03, 0x03, // Version TLS 1.2 legacy
	}
	shPayload = append(shPayload, serverRandom[:]...)
	shPayload = append(shPayload, 0x00)       // SessionID length 0
	shPayload = append(shPayload, 0x13, 0x01) // TLS_AES_128_GCM_SHA256
	shPayload = append(shPayload, 0x00)       // Compression None

	// Extensions (SupportedVersions TLS 1.3)
	extensions := []byte{
		0x00, 0x06, // extensions length 6
		0x00, 0x2b, // Supported versions
		0x00, 0x02, // length 2
		0x03, 0x04, // TLS 1.3
	}
	shPayload = append(shPayload, extensions...)

	// Wrap in TLS Record
	record := make([]byte, 5+len(shPayload))
	record[0] = 0x16 // Handshake
	record[1] = 0x03
	record[2] = 0x03
	binary.BigEndian.PutUint16(record[3:5], uint16(len(shPayload)))
	copy(record[5:], shPayload)

	// ChangeCipherSpec Record (RFC 8446 standard middlebox compatibility)
	ccs := []byte{0x14, 0x03, 0x03, 0x00, 0x01, 0x01}
	return append(record, ccs...), nil
}

// spliceToFallback seamlessly proxies the client connection to the real fallback target.
func spliceToFallback(clientConn net.Conn, fallbackDest string, initialBytes []byte) {
	defer clientConn.Close()

	destConn, err := net.DialTimeout("tcp", fallbackDest, 5*time.Second)
	if err != nil {
		return
	}
	defer destConn.Close()

	// Forward initial client ClientHello bytes to target destination
	if len(initialBytes) > 0 {
		_ = destConn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		if _, err := destConn.Write(initialBytes); err != nil {
			return
		}
		_ = destConn.SetWriteDeadline(time.Time{})
	}

	// Bidirectional proxy with rolling 30s idle timeout and TCP half-close
	pipe := func(dst, src net.Conn, done chan<- struct{}) {
		defer func() {
			if tc, ok := dst.(*net.TCPConn); ok {
				_ = tc.CloseWrite()
			}
			done <- struct{}{}
		}()
		buf := make([]byte, 32*1024)
		for {
			_ = src.SetReadDeadline(time.Now().Add(30 * time.Second))
			n, rerr := src.Read(buf)
			if n > 0 {
				_ = dst.SetWriteDeadline(time.Now().Add(30 * time.Second))
				if _, werr := dst.Write(buf[:n]); werr != nil {
					break
				}
			}
			if rerr != nil {
				break
			}
		}
	}

	done := make(chan struct{}, 2)
	go pipe(destConn, clientConn, done)
	go pipe(clientConn, destConn, done)
	<-done
}

// ParseShortID parses hex or ascii ShortID into byte slice.
func ParseShortID(sid string) []byte {
	cleaned := strings.TrimSpace(sid)
	if len(cleaned)%2 == 0 {
		b, err := hex.DecodeString(cleaned)
		if err == nil {
			return b
		}
	}
	return []byte(cleaned)
}
