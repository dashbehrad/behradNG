package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"errors"
	"strings"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	NonceSize = 12
	TagSize   = 16

	CipherChaCha20Poly1305 = "chacha20-poly1305"
	CipherAES128GCM        = "aes-128-gcm"
	CipherAES256GCM        = "aes-256-gcm"
)

// Cipher wraps an AEAD cipher for stream encryption.
type Cipher struct {
	aead   cipher.AEAD
	method string
}

// NewCipher creates an AEAD cipher instance based on method and key.
func NewCipher(method string, key []byte) (*Cipher, error) {
	norm := strings.ToLower(strings.TrimSpace(method))
	switch norm {
	case CipherChaCha20Poly1305, "chacha20":
		if len(key) < chacha20poly1305.KeySize {
			return nil, errors.New("key too short for ChaCha20-Poly1305")
		}
		aead, err := chacha20poly1305.New(key[:chacha20poly1305.KeySize])
		if err != nil {
			return nil, err
		}
		return &Cipher{aead: aead, method: CipherChaCha20Poly1305}, nil

	case CipherAES128GCM, "aes-128":
		if len(key) < 16 {
			return nil, errors.New("key too short for AES-128-GCM")
		}
		block, err := aes.NewCipher(key[:16])
		if err != nil {
			return nil, err
		}
		aead, err := cipher.NewGCM(block)
		if err != nil {
			return nil, err
		}
		return &Cipher{aead: aead, method: CipherAES128GCM}, nil

	case CipherAES256GCM, "aes-256":
		if len(key) < 32 {
			return nil, errors.New("key too short for AES-256-GCM")
		}
		block, err := aes.NewCipher(key[:32])
		if err != nil {
			return nil, err
		}
		aead, err := cipher.NewGCM(block)
		if err != nil {
			return nil, err
		}
		return &Cipher{aead: aead, method: CipherAES256GCM}, nil

	default:
		// Default to ChaCha20-Poly1305
		if len(key) < chacha20poly1305.KeySize {
			return nil, errors.New("key too short for default cipher")
		}
		aead, err := chacha20poly1305.New(key[:chacha20poly1305.KeySize])
		if err != nil {
			return nil, err
		}
		return &Cipher{aead: aead, method: CipherChaCha20Poly1305}, nil
	}
}

// NonceSize returns standard nonce size (12 bytes).
func (c *Cipher) NonceSize() int {
	return NonceSize
}

// Overhead returns standard AEAD tag overhead (16 bytes).
func (c *Cipher) Overhead() int {
	return TagSize
}

// Encrypt encrypts plaintext using nonce and optional additional data.
func (c *Cipher) Encrypt(nonce, plaintext, aad []byte) []byte {
	return c.aead.Seal(nil, nonce, plaintext, aad)
}

// Decrypt decrypts ciphertext using nonce and optional additional data.
func (c *Cipher) Decrypt(nonce, ciphertext, aad []byte) ([]byte, error) {
	return c.aead.Open(nil, nonce, ciphertext, aad)
}
