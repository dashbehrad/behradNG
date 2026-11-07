package handshake_test

import (
	"bytes"
	"net"
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

func TestClientServerHandshake(t *testing.T) {
	serverKP, err := crypto.GenerateKeypair()
	if err != nil {
		t.Fatal(err)
	}

	shortID := []byte("abcd")
	uuid := "550e8400-e29b-41d4-a716-446655440000"

	serverCfg := &handshake.ServerHandshakeConfig{
		PrivateKey:   serverKP.PrivateKey[:],
		PublicKey:    serverKP.PublicKey[:],
		UUID:         uuid,
		ShortID:      shortID,
		FallbackDest: "127.0.0.1:9999",
		Cipher:       "chacha20-poly1305",
	}

	clientCfg := &handshake.ClientHandshakeConfig{
		ServerAddress: "127.0.0.1",
		ServerPort:    443,
		SNI:           "dl.google.com",
		ServerPubKey:  serverKP.PublicKey[:],
		ShortID:       shortID,
		UUID:          uuid,
		Fingerprint:   "chrome",
		Fragmentation: "0-0",
		Cipher:        "chacha20-poly1305",
	}

	clientConn, serverConn := net.Pipe()
	defer clientConn.Close()
	defer serverConn.Close()

	serverErrChan := make(chan error, 1)
	var serverResult *handshake.HandshakeResult
	go func() {
		res, _, err := handshake.HandleServerHandshake(serverConn, serverCfg)
		serverResult = res
		serverErrChan <- err
	}()

	clientResult, _, clientErr := handshake.ClientHandshake(clientConn, clientCfg)
	serverErr := <-serverErrChan

	t.Logf("serverErr: %v", serverErr)
	t.Logf("clientErr: %v", clientErr)

	if serverErr != nil {
		t.Fatalf("ServerHandshake failed: %v", serverErr)
	}
	if clientErr != nil {
		t.Fatalf("ClientHandshake failed: %v", clientErr)
	}

	if !bytes.Equal(clientResult.ClientKey, serverResult.ClientKey) {
		t.Fatalf("ClientKey mismatch between client and server")
	}
	if !bytes.Equal(clientResult.ServerKey, serverResult.ServerKey) {
		t.Fatalf("ServerKey mismatch between client and server")
	}
	if !bytes.Equal(clientResult.ClientIV, serverResult.ClientIV) {
		t.Fatalf("ClientIV mismatch between client and server")
	}
	if !bytes.Equal(clientResult.ServerIV, serverResult.ServerIV) {
		t.Fatalf("ServerIV mismatch between client and server")
	}
}

func TestHandshakeWithVariousAlanDpiVectors(t *testing.T) {
	testVectors := []string{
		"VEC-21", // Phantom Seam OOB
		"VEC-22", // SNI Microcell Pincer
		"VEC-01", // Adaptive Core Alpha (sni_tag)
		"VEC-20", // Fibonacci 7-record segmentation
		"VEC-17", // Aligned Record Relay (sni_dot_3rec)
		"VEC-04", // Preamble Fusion + Stride
		"VEC-09", // Atomic Mid-SNI Record Split
		"VEC-00", // Direct passthrough
	}

	for _, vecID := range testVectors {
		t.Run(vecID, func(t *testing.T) {
			serverKP, err := crypto.GenerateKeypair()
			if err != nil {
				t.Fatal(err)
			}

			shortID := []byte("alan")
			uuid := "11111111-2222-3333-4444-555555555555"

			serverCfg := &handshake.ServerHandshakeConfig{
				PrivateKey:   serverKP.PrivateKey[:],
				PublicKey:    serverKP.PublicKey[:],
				UUID:         uuid,
				ShortID:      shortID,
				FallbackDest: "127.0.0.1:9999",
				Cipher:       "chacha20-poly1305",
			}

			clientCfg := &handshake.ClientHandshakeConfig{
				ServerAddress: "127.0.0.1",
				ServerPort:    443,
				SNI:           "dl.google.com",
				ServerPubKey:  serverKP.PublicKey[:],
				ShortID:       shortID,
				UUID:          uuid,
				Fingerprint:   "chrome",
				DpiVector:     vecID,
				Cipher:        "chacha20-poly1305",
			}

			clientConn, serverConn := net.Pipe()
			defer clientConn.Close()
			defer serverConn.Close()

			serverErrChan := make(chan error, 1)
			var serverResult *handshake.HandshakeResult
			go func() {
				res, _, err := handshake.HandleServerHandshake(serverConn, serverCfg)
				serverResult = res
				serverErrChan <- err
			}()

			clientResult, _, clientErr := handshake.ClientHandshake(clientConn, clientCfg)
			serverErr := <-serverErrChan

			if serverErr != nil {
				t.Fatalf("Vector %s ServerHandshake failed: %v", vecID, serverErr)
			}
			if clientErr != nil {
				t.Fatalf("Vector %s ClientHandshake failed: %v", vecID, clientErr)
			}
			if !bytes.Equal(clientResult.ClientKey, serverResult.ClientKey) {
				t.Fatalf("Vector %s ClientKey mismatch", vecID)
			}
		})
	}
}
