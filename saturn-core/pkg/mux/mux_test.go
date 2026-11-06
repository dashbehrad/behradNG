package mux_test

import (
	"bytes"
	"io"
	"net"
	"testing"

	"github.com/xtaci/smux"
	"saturn-core/pkg/crypto"
	"saturn-core/pkg/mux"
)

func TestFramedConnAndSmuxRelay(t *testing.T) {
	cipherKey := make([]byte, 32)
	copy(cipherKey, "32-bytes-long-cipher-key-test!!!")
	baseIV := make([]byte, 12)
	copy(baseIV, "12-bytes-iv!")

	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()

	cip1, err := crypto.NewCipher("chacha20-poly1305", cipherKey)
	if err != nil {
		t.Fatal(err)
	}
	cip2, err := crypto.NewCipher("chacha20-poly1305", cipherKey)
	if err != nil {
		t.Fatal(err)
	}

	framedClient := mux.NewFramedConn(c1, cip1, baseIV)
	framedServer := mux.NewFramedConn(c2, cip2, baseIV)

	smuxCfg := mux.DefaultSmuxConfig()

	serverReady := make(chan *smux.Session, 1)
	go func() {
		sess, err := smux.Server(framedServer, smuxCfg)
		if err != nil {
			t.Errorf("smux Server failed: %v", err)
			return
		}
		serverReady <- sess
	}()

	clientSess, err := smux.Client(framedClient, smuxCfg)
	if err != nil {
		t.Fatalf("smux Client failed: %v", err)
	}
	defer clientSess.Close()

	serverSess := <-serverReady
	defer serverSess.Close()

	// Open a stream from client to server
	clientStream, err := clientSess.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream failed: %v", err)
	}
	defer clientStream.Close()

	serverStream, err := serverSess.AcceptStream()
	if err != nil {
		t.Fatalf("AcceptStream failed: %v", err)
	}
	defer serverStream.Close()

	// Write address header
	target := "example.com:443"
	if err := mux.WriteAddressHeader(clientStream, target); err != nil {
		t.Fatalf("WriteAddressHeader failed: %v", err)
	}

	readTarget, err := mux.ReadAddressHeader(serverStream)
	if err != nil {
		t.Fatalf("ReadAddressHeader failed: %v", err)
	}
	if readTarget != target {
		t.Fatalf("Target mismatch: got %s, want %s", readTarget, target)
	}

	// Echo test
	testData := []byte("Hello, Saturn Anti-DPI Multiplexer!")
	go func() {
		_, _ = clientStream.Write(testData)
	}()

	buf := make([]byte, len(testData))
	if _, err := io.ReadFull(serverStream, buf); err != nil {
		t.Fatalf("Read testData failed: %v", err)
	}
	if !bytes.Equal(buf, testData) {
		t.Fatalf("Payload mismatch")
	}
}
