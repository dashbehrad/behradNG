package crypto_test

import (
	"bytes"
	"testing"

	"saturn-core/pkg/crypto"
)

func TestX25519AndAEAD(t *testing.T) {
	// 1. Generate keypairs for Alice and Bob
	alice, err := crypto.GenerateKeypair()
	if err != nil {
		t.Fatalf("Alice keygen failed: %v", err)
	}
	bob, err := crypto.GenerateKeypair()
	if err != nil {
		t.Fatalf("Bob keygen failed: %v", err)
	}

	// 2. Compute shared secret
	sharedAlice, err := crypto.ComputeSharedSecret(alice.PrivateKey[:], bob.PublicKey[:])
	if err != nil {
		t.Fatalf("Alice ECDH failed: %v", err)
	}
	sharedBob, err := crypto.ComputeSharedSecret(bob.PrivateKey[:], alice.PublicKey[:])
	if err != nil {
		t.Fatalf("Bob ECDH failed: %v", err)
	}

	if !bytes.Equal(sharedAlice, sharedBob) {
		t.Fatalf("Shared secrets do not match")
	}

	// 3. Key derivation
	salt := []byte(crypto.TokenSalt)
	keyAlice, err := crypto.DeriveKey(sharedAlice, salt, crypto.InfoKeyAEAD, 32)
	if err != nil {
		t.Fatalf("DeriveKey failed: %v", err)
	}

	// 4. AEAD encryption & decryption
	cipher, err := crypto.NewCipher(crypto.CipherChaCha20Poly1305, keyAlice)
	if err != nil {
		t.Fatalf("NewCipher failed: %v", err)
	}

	nonce := make([]byte, crypto.NonceSize)
	copy(nonce, "123456789012")
	plaintext := []byte("Hello, Saturn Anti-DPI Protocol!")
	aad := []byte("Saturn-Header")

	ciphertext := cipher.Encrypt(nonce, plaintext, aad)
	decrypted, err := cipher.Decrypt(nonce, ciphertext, aad)
	if err != nil {
		t.Fatalf("Decrypt failed: %v", err)
	}

	if !bytes.Equal(decrypted, plaintext) {
		t.Fatalf("Decrypted does not match plaintext: got %s", string(decrypted))
	}
}

func TestKeyEncoding(t *testing.T) {
	kp, err := crypto.GenerateKeypair()
	if err != nil {
		t.Fatal(err)
	}

	encodedB64 := crypto.EncodeKeyBase64(kp.PublicKey[:])
	parsed, err := crypto.ParseKey(encodedB64)
	if err != nil {
		t.Fatalf("ParseKey b64 failed: %v", err)
	}
	if !bytes.Equal(parsed, kp.PublicKey[:]) {
		t.Fatalf("Parsed key does not match")
	}

	encodedHex := crypto.EncodeKeyHex(kp.PublicKey[:])
	parsedHex, err := crypto.ParseKey(encodedHex)
	if err != nil {
		t.Fatalf("ParseKey hex failed: %v", err)
	}
	if !bytes.Equal(parsedHex, kp.PublicKey[:]) {
		t.Fatalf("Parsed hex key does not match")
	}
}
