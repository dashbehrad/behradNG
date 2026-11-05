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
	"time"

	"saturn-core/pkg/crypto"
)

const (
	SaturnExtensionID = 0x5a70 // 'Zp' / Saturn Auth Extension ID
	HandshakeTimeout  = 10 * time.Second
	MaxClockSkewSec   = 180    // Maximum clock skew tolerance: 3 minutes
)

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
	ClientRandom []byte
}

// HandleServerHandshake performs stealth authentication or transparent active probe fallback.
func HandleServerHandshake(clientConn net.Conn, cfg *ServerHandshakeConfig) (*HandshakeResult, net.Conn, error) {
	_ = clientConn.SetReadDeadline(time.Now().Add(HandshakeTimeout))

	// Buffer up to 4KB of initial client bytes (standard ClientHello is 300 - 2000 bytes)
	buf := make([]byte, 4096)
	n, err := clientConn.Read(buf)
	if err != nil {
		return nil, nil, err
	}
	initialBytes := buf[:n]

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

	// Locate client ephemeral public key and token
	clientPubKey, token := extractSaturnCredentials(data, sessionID)
	if len(clientPubKey) != crypto.KeySize {
		return nil, errors.New("client ephemeral public key not found")
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

	// Derive AEAD session key and base IV
	aeadKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyAEAD, 32)
	if err != nil {
		return nil, err
	}
	baseIV, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyIV, crypto.NonceSize)
	if err != nil {
		return nil, err
	}

	return &HandshakeResult{
		SharedSecret: sharedSecret,
		AEADKey:      aeadKey,
		BaseIV:       baseIV,
		ClientRandom: clientRandom,
	}, nil
}

// extractSaturnCredentials extracts client X25519 public key and auth token from ClientHello.
func extractSaturnCredentials(data, sessionID []byte) ([]byte, []byte) {
	// First search for custom Saturn Extension (0x5a70)
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
			if extType == SaturnExtensionID && extDataLen >= 64 {
				// Contains ClientPubKey (32 bytes) + Token (32 bytes)
				pub := data[curr : curr+32]
				tok := data[curr+32 : curr+64]
				return pub, tok
			}
			// Extension 0x0033: TLS 1.3 KeyShare
			if extType == 0x0033 && extDataLen >= 36 {
				// Search for X25519 group (0x001d)
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
							// Token is in SessionID
							if len(sessionID) == 32 {
								return pubKey, sessionID
							}
						}
						idx += kLen
					}
				}
			}
			curr += extDataLen
		}
	}

	// Fallback: If SessionID carries the token, and key share was found
	if len(sessionID) == 32 {
		return nil, sessionID
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
		0x02, // ServerHello
		0x00, 0x00, 0x46, // length 70
		0x03, 0x03, // Version TLS 1.2 legacy
	}
	shPayload = append(shPayload, serverRandom[:]...)
	shPayload = append(shPayload, 0x00) // SessionID length 0
	shPayload = append(shPayload, 0x13, 0x01) // TLS_AES_128_GCM_SHA256
	shPayload = append(shPayload, 0x00) // Compression None

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

	// Write initial client bytes to target destination
	if len(initialBytes) > 0 {
		if _, err := destConn.Write(initialBytes); err != nil {
			return
		}
	}

	// Bidirectional zero-copy proxy
	errc := make(chan error, 2)
	go func() {
		_, err := io.Copy(destConn, clientConn)
		errc <- err
	}()
	go func() {
		_, err := io.Copy(clientConn, destConn)
		errc <- err
	}()
	<-errc
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
