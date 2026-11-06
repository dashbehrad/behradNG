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
	Fragmentation string // "1-5" (minDelay-maxDelay ms)
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
	if len(rawClientHello) == 0 {
		return nil, nil, errors.New("raw ClientHello is empty")
	}

	// Wrap in standard TLS record: 0x16 0x03 0x01
	record := make([]byte, 5+len(rawClientHello))
	record[0] = 0x16
	record[1] = 0x03
	record[2] = 0x01
	binary.BigEndian.PutUint16(record[3:5], uint16(len(rawClientHello)))
	copy(record[5:], rawClientHello)

	// 7. Dynamic TLS Record Fragmentation across SNI
	minDelay, maxDelay := parseFragmentation(cfg.Fragmentation)
	if err := sendFragmentedPayload(tcpConn, record, sni, minDelay, maxDelay); err != nil {
		return nil, nil, fmt.Errorf("fragmentation write failed: %w", err)
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

	// 9. Derive AEAD keys for framing session
	aeadKey, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyAEAD, 32)
	if err != nil {
		return nil, nil, err
	}
	baseIV, err := crypto.DeriveKey(sharedSecret, salt, crypto.InfoKeyIV, crypto.NonceSize)
	if err != nil {
		return nil, nil, err
	}

	return &HandshakeResult{
		SharedSecret: sharedSecret,
		AEADKey:      aeadKey,
		BaseIV:       baseIV,
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
