package handshake

import (
	"bytes"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"time"

	utls "github.com/refraction-networking/utls"
	"saturn-core/pkg/crypto"
	"saturn-core/pkg/dpi"
)

// ClientHandshakeConfig contains configuration for client-side stealth handshake.
type ClientHandshakeConfig struct {
	ServerAddress string
	ServerPort    int
	SNI           string
	ServerPubKey  []byte
	ShortID       []byte
	UUID          string
	Fingerprint   string // "chrome", "firefox", "auto"
	Fragmentation string // "1-5" (minDelay-maxDelay ms) or Vector ID e.g. "VEC-21"
	DpiVector     string // Alan DPI vector ID: "auto", "VEC-21", "VEC-22", "VEC-01", etc.
	Cipher        string
}

// ClientHandshake performs utls fingerprint spoofing, dynamic record fragmentation, and stealth auth.
func ClientHandshake(tcpConn net.Conn, cfg *ClientHandshakeConfig) (*HandshakeResult, net.Conn, error) {
	// Ensure TCP_NODELAY is active for accurate packet fragmentation
	if tc, ok := tcpConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	// 1. Generate client ephemeral X25519 keypair
	ephemKP, err := crypto.GenerateKeypair()
	if err != nil {
		return nil, nil, fmt.Errorf("ephemeral keygen failed: %w", err)
	}

	// 2. Compute shared secret with server public key
	sharedSecret, err := crypto.ComputeSharedSecret(ephemKP.PrivateKey[:], cfg.ServerPubKey)
	if err != nil {
		return nil, nil, fmt.Errorf("ecdh shared secret failed: %w", err)
	}

	// 3. Build ClientRandom with timestamp & ShortID
	var clientRandom [32]byte
	now := uint64(time.Now().Unix())
	binary.BigEndian.PutUint64(clientRandom[0:8], now)
	if len(cfg.ShortID) > 0 {
		copy(clientRandom[8:16], cfg.ShortID)
	}
	_, _ = io.ReadFull(rand.Reader, clientRandom[16:32])

	// 4. Derive auth key and compute token
	salt := []byte(crypto.TokenSalt)
	if len(cfg.ServerPubKey) == crypto.KeySize {
		salt = cfg.ServerPubKey
	}
	authKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyAuth, 32)
	if err != nil {
		return nil, nil, err
	}
	token := crypto.ComputeAuthToken(authKey, clientRandom[:])

	// 5. Select utls fingerprint
	var helloID utls.ClientHelloID
	switch strings.ToLower(strings.TrimSpace(cfg.Fingerprint)) {
	case "firefox", "ff":
		helloID = utls.HelloFirefox_Auto
	case "chrome":
		helloID = utls.HelloChrome_Auto
	default:
		helloID = utls.HelloChrome_Auto
	}

	sni := cfg.SNI
	if sni == "" {
		sni = "dl.google.com"
	}

	// 6. Build ClientHello using uTLS (100% compliant with Chrome JA3/JA4 fingerprint)
	uConn := utls.UClient(tcpConn, &utls.Config{
		ServerName:         sni,
		InsecureSkipVerify: true,
	}, helloID)

	// Inject ClientRandom and SessionID (legacy_session_id RFC 8446 Appendix D.4 compatibility)
	uConn.HandshakeState.Hello.Random = clientRandom[:]
	uConn.HandshakeState.Hello.SessionId = token[:]

	// Embed client ephemeral public key strictly inside standard KeyShareExtension (0x0033) for group X25519 (0x001d).
	// No custom TLS extension is used, preserving identical JA3/JA4 TLS handshake fingerprint.
	foundKeyShare := false
	for _, ext := range uConn.Extensions {
		if ks, ok := ext.(*utls.KeyShareExtension); ok {
			for i := range ks.KeyShares {
				if ks.KeyShares[i].Group == utls.X25519 {
					ks.KeyShares[i].Data = ephemKP.PublicKey[:]
					foundKeyShare = true
				}
			}
			if !foundKeyShare {
				ks.KeyShares = append(ks.KeyShares, utls.KeyShare{
					Group: utls.X25519,
					Data:  ephemKP.PublicKey[:],
				})
				foundKeyShare = true
			}
		}
	}
	if !foundKeyShare {
		uConn.Extensions = append(uConn.Extensions, &utls.KeyShareExtension{
			KeyShares: []utls.KeyShare{
				{
					Group: utls.X25519,
					Data:  ephemKP.PublicKey[:],
				},
			},
		})
	}

	// Build raw ClientHello bytes
	if err := uConn.BuildHandshakeState(); err != nil {
		return nil, nil, fmt.Errorf("failed to build handshake state: %w", err)
	}
	rawClientHello := uConn.HandshakeState.Hello.Raw
	if len(rawClientHello) < 71 {
		return nil, nil, errors.New("raw ClientHello is too short")
	}

	// Wrap in standard TLS record: 0x16 0x03 0x01
	record := make([]byte, 5+len(rawClientHello))
	record[0] = 0x16
	record[1] = 0x03
	record[2] = 0x01
	binary.BigEndian.PutUint16(record[3:5], uint16(len(rawClientHello)))
	copy(record[5:], rawClientHello)

	// Embed Saturn credentials into standard TLS 1.3 ClientHello fields:
	// 1. ClientRandom (bytes 11..43): timestamp & ShortID
	// 2. SessionID (bytes 44..76): HMAC-SHA256 Auth Token (RFC 8446 Appendix D.4 legacy_session_id)
	// 3. KeyShare Extension 0x0033: X25519 group 0x001d ephemeral public key (32 bytes)
	// This preserves 100% uTLS fingerprint and JA3/JA4 hash consistency with genuine Chrome.
	copy(record[11:43], clientRandom[:])
	copy(record[44:76], token[:])
	if !injectClientKeyShare(record, ephemKP.PublicKey[:]) {
		return nil, nil, errors.New("failed to inject client ephemeral public key into KeyShare")
	}

	// 7. Alan DPI Evasion & Stealth Dispatch
	targetVector := cfg.DpiVector
	if targetVector == "" || targetVector == "auto" {
		if strings.HasPrefix(cfg.Fragmentation, "VEC-") {
			targetVector = cfg.Fragmentation
		} else {
			targetVector = "VEC-21" // Adaptive Core Omega default autopilot
		}
	}
	vec := dpi.GetVector(targetVector)
	if vec == nil {
		vec = dpi.GetVector("VEC-21")
	}

	if _, _, err := dpi.TransmitClientHello(tcpConn, record, vec, 400, 20); err != nil {
		return nil, nil, fmt.Errorf("DPI bypass transmit (%s) failed: %w", vec.VectorID, err)
	}

	// 8. Read server response (ServerHello + ChangeCipherSpec)
	_ = tcpConn.SetReadDeadline(time.Now().Add(HandshakeTimeout))
	respBuf := make([]byte, 1024)
	n, err := tcpConn.Read(respBuf)
	if err != nil {
		return nil, nil, fmt.Errorf("failed reading server handshake response: %w", err)
	}
	_ = tcpConn.SetReadDeadline(time.Time{})

	if n < 5 || respBuf[0] != 0x16 {
		return nil, nil, errors.New("invalid server stealth response")
	}

	// 9. Derive AEAD keys and directional base IVs for framing session
	aeadKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyAEAD, 32)
	if err != nil {
		return nil, nil, err
	}
	baseIV, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyIV, crypto.NonceSize)
	if err != nil {
		return nil, nil, err
	}
	clientKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoClientKey, 32)
	if err != nil {
		return nil, nil, err
	}
	clientIV, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoClientIV, crypto.NonceSize)
	if err != nil {
		return nil, nil, err
	}
	serverKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoServerKey, 32)
	if err != nil {
		return nil, nil, err
	}
	serverIV, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoServerIV, crypto.NonceSize)
	if err != nil {
		return nil, nil, err
	}

	return &HandshakeResult{
		SharedSecret: sharedSecret,
		AEADKey:      aeadKey,
		BaseIV:       baseIV,
		ClientKey:    clientKey,
		ClientIV:     clientIV,
		ServerKey:    serverKey,
		ServerIV:     serverIV,
		ClientRandom: clientRandom[:],
	}, tcpConn, nil
}

func cryptoRandN(n int64) int64 {
	if n <= 0 {
		return 0
	}
	var b [8]byte
	_, _ = rand.Read(b[:])
	val := binary.BigEndian.Uint64(b[:])
	return int64(val % uint64(n))
}

// sendFragmentedPayload splits the initial ClientHello at randomized byte offsets inside SNI with micro-delays.
func sendFragmentedPayload(conn net.Conn, data []byte, sni string, minDelay, maxDelay time.Duration) error {
	sniBytes := []byte(sni)
	sniIdx := bytes.Index(data, sniBytes)

	// Determine split points
	var splits []int
	if sniIdx > 0 && len(sniBytes) > 4 {
		// Cut 1: before or inside SNI extension
		cut1 := sniIdx + 1 + int(cryptoRandN(int64(len(sniBytes)/2)))
		splits = append(splits, cut1)
		// Cut 2: end of SNI or shortly after
		cut2 := sniIdx + len(sniBytes)
		if cut2 < len(data)-16 {
			splits = append(splits, cut2)
		}
	} else {
		// Fallback random 3-way split
		mid := len(data) / 2
		splits = append(splits, mid/2, mid)
	}

	prev := 0
	for _, split := range splits {
		if split > prev && split < len(data) {
			chunk := data[prev:split]
			if _, err := conn.Write(chunk); err != nil {
				return err
			}
			// Apply micro-delay
			delay := minDelay
			if maxDelay > minDelay {
				delta := maxDelay - minDelay
				delay = minDelay + time.Duration(cryptoRandN(int64(delta)))
			}
			if delay > 0 {
				time.Sleep(delay)
			}
			prev = split
		}
	}

	// Write remaining bytes
	if prev < len(data) {
		if _, err := conn.Write(data[prev:]); err != nil {
			return err
		}
	}
	return nil
}

// parseFragmentation parses "1-5" into (1ms, 5ms).
func parseFragmentation(frag string) (time.Duration, time.Duration) {
	cleaned := strings.TrimSpace(frag)
	if cleaned == "" {
		return 1 * time.Millisecond, 5 * time.Millisecond
	}
	parts := strings.Split(cleaned, "-")
	if len(parts) == 2 {
		minMs, err1 := strconv.Atoi(strings.TrimSpace(parts[0]))
		maxMs, err2 := strconv.Atoi(strings.TrimSpace(parts[1]))
		if err1 == nil && err2 == nil && minMs >= 0 && maxMs >= minMs {
			return time.Duration(minMs) * time.Millisecond, time.Duration(maxMs) * time.Millisecond
		}
	}
	return 2 * time.Millisecond, 5 * time.Millisecond
}

// injectClientKeyShare replaces the X25519 public key inside TLS 1.3 KeyShare extension (0x0033).
func injectClientKeyShare(data, pubKey []byte) bool {
	extOffset := findExtensionsOffset(data)
	if extOffset <= 0 || extOffset+2 > len(data) {
		return false
	}
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
						copy(ksData[idx:idx+32], pubKey)
						return true
					}
					idx += kLen
				}
			}
		}
		curr += extDataLen
	}
	return false
}
