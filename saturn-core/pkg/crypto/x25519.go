package crypto

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"io"
	"strings"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

const (
	KeySize      = 32
	ShortIDSize  = 8
	TokenSalt    = "saturn-auth-salt-v1"
	InfoKeyAuth   = "saturn-auth-token-v1"
	InfoKeyAEAD   = "saturn-aead-key-v1"
	InfoKeyIV     = "saturn-aead-iv-v1"
	InfoClientKey = "saturn-client-key-v1"
	InfoClientIV  = "saturn-client-iv-v1"
	InfoServerKey = "saturn-server-key-v1"
	InfoServerIV  = "saturn-server-iv-v1"
)

// Keypair contains X25519 private and public keys.
type Keypair struct {
	PrivateKey [KeySize]byte
	PublicKey  [KeySize]byte
}

// GenerateKeypair creates a fresh X25519 keypair.
func GenerateKeypair() (*Keypair, error) {
	var priv [KeySize]byte
	if _, err := io.ReadFull(rand.Reader, priv[:]); err != nil {
		return nil, err
	}
	// Clamp private key scalar according to RFC 7748
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64

	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return nil, err
	}

	var kp Keypair
	copy(kp.PrivateKey[:], priv[:])
	copy(kp.PublicKey[:], pub)
	return &kp, nil
}

// ComputeSharedSecret computes ECDH shared secret = X25519(privKey, pubKey).
func ComputeSharedSecret(privKey, peerPubKey []byte) ([]byte, error) {
	if len(privKey) != KeySize || len(peerPubKey) != KeySize {
		return nil, errors.New("invalid key size for X25519")
	}
	shared, err := curve25519.X25519(privKey, peerPubKey)
	if err != nil {
		return nil, err
	}
	// Reject all-zero shared secrets
	var zero [KeySize]byte
	if subtle.ConstantTimeCompare(shared, zero[:]) == 1 {
		return nil, errors.New("weak shared secret detected")
	}
	return shared, nil
}

// DeriveKey uses HKDF-SHA256 to expand a secret into a specified number of bytes.
func DeriveKey(secret, salt []byte, info string, length int) ([]byte, error) {
	kdf := hkdf.New(sha256.New, secret, salt, []byte(info))
	out := make([]byte, length)
	if _, err := io.ReadFull(kdf, out); err != nil {
		return nil, err
	}
	return out, nil
}

// ComputeAuthToken calculates HMAC-SHA256(authKey, payload).
func ComputeAuthToken(authKey, payload []byte) [32]byte {
	h := hmac.New(sha256.New, authKey)
	h.Write(payload)
	var token [32]byte
	copy(token[:], h.Sum(nil))
	return token
}

// VerifyAuthToken checks if actualToken matches expectedToken in constant time.
func VerifyAuthToken(actual, expected []byte) bool {
	if len(actual) != len(expected) {
		return false
	}
	return subtle.ConstantTimeCompare(actual, expected) == 1
}

// ParseKey parses a 32-byte key from Base64 (std/URL-safe) or Hex.
func ParseKey(input string) ([]byte, error) {
	cleaned := strings.TrimSpace(input)
	if len(cleaned) == 64 {
		b, err := hex.DecodeString(cleaned)
		if err == nil && len(b) == KeySize {
			return b, nil
		}
	}
	// Try standard base64 then URL-safe base64
	b, err := base64.StdEncoding.DecodeString(cleaned)
	if err == nil && len(b) == KeySize {
		return b, nil
	}
	b, err = base64.RawStdEncoding.DecodeString(cleaned)
	if err == nil && len(b) == KeySize {
		return b, nil
	}
	b, err = base64.URLEncoding.DecodeString(cleaned)
	if err == nil && len(b) == KeySize {
		return b, nil
	}
	b, err = base64.RawURLEncoding.DecodeString(cleaned)
	if err == nil && len(b) == KeySize {
		return b, nil
	}
	return nil, errors.New("unable to parse 32-byte key from hex or base64")
}

// EncodeKeyBase64 encodes key as URL-safe base64 without padding.
func EncodeKeyBase64(key []byte) string {
	return base64.RawURLEncoding.EncodeToString(key)
}

// EncodeKeyHex encodes key as lowercase hex.
func EncodeKeyHex(key []byte) string {
	return hex.EncodeToString(key)
}
