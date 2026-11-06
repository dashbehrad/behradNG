package handshake_test

import (
	"testing"

	"saturn-core/pkg/crypto"
	"saturn-core/pkg/handshake"
)

func TestReplayCache(t *testing.T) {
	rc := handshake.NewReplayCache()

	token1 := make([]byte, 32)
	copy(token1, "token-number-one-unique-string!!")

	token2 := make([]byte, 32)
	copy(token2, "token-number-two-unique-string!!")

	if !rc.CheckAndAdd(token1, 5) {
		t.Fatalf("First insertion of token1 should succeed")
	}

	if rc.CheckAndAdd(token1, 5) {
		t.Fatalf("Replay of token1 should be blocked by cache")
	}

	if !rc.CheckAndAdd(token2, 5) {
		t.Fatalf("First insertion of token2 should succeed")
	}

	if rc.CheckAndAdd(token2, 5) {
		t.Fatalf("Replay of token2 should be blocked by cache")
	}

	// Short token should be rejected
	if rc.CheckAndAdd([]byte("too-short"), 5) {
		t.Fatalf("Short token should return false")
	}
}

func TestCryptoAuthToken(t *testing.T) {
	authKey := make([]byte, 32)
	copy(authKey, "saturn-auth-key-32-bytes-test!!")

	clientRandom := make([]byte, 32)
	copy(clientRandom, "random-client-hello-bytes-32byte")

	token := crypto.ComputeAuthToken(authKey, clientRandom)
	if len(token) != 32 {
		t.Fatalf("Expected 32-byte auth token, got %d", len(token))
	}

	if !crypto.VerifyAuthToken(token[:], token[:]) {
		t.Fatalf("Self verification failed")
	}

	badToken := make([]byte, 32)
	copy(badToken, token[:])
	badToken[0] ^= 0xFF
	if crypto.VerifyAuthToken(token[:], badToken) {
		t.Fatalf("Tampered token should not verify")
	}
}
