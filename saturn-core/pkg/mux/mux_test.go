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

func TestMultiplexerFailover(t *testing.T) {
	cipherKey := make([]byte, 32)
	copy(cipherKey, "32-bytes-long-cipher-key-test!!!")
	baseIV := make([]byte, 12)
	copy(baseIV, "12-bytes-iv!")

	makePair := func() (*smux.Session, *smux.Session) {
		c1, c2 := net.Pipe()
		cip1, _ := crypto.NewCipher("chacha20-poly1305", cipherKey)
		cip2, _ := crypto.NewCipher("chacha20-poly1305", cipherKey)
		fc1 := mux.NewFramedConn(c1, cip1, baseIV)
		fc2 := mux.NewFramedConn(c2, cip2, baseIV)
		smuxCfg := mux.DefaultSmuxConfig()

		srvChan := make(chan *smux.Session, 1)
		go func() {
			s, _ := smux.Server(fc2, smuxCfg)
			srvChan <- s
		}()
		cli, err := smux.Client(fc1, smuxCfg)
		if err != nil {
			t.Fatal(err)
		}
		srv := <-srvChan
		return cli, srv
	}

	cli1, srv1 := makePair()
	defer cli1.Close()
	defer srv1.Close()

	cli2, srv2 := makePair()
	defer cli2.Close()
	defer srv2.Close()

	m := mux.NewMultiplexer()
	m.AddSession(cli1)
	m.AddSession(cli2)

	// Accept streams on servers in background
	go func() {
		for {
			s, err := srv1.AcceptStream()
			if err != nil {
				return
			}
			s.Close()
		}
	}()
	go func() {
		for {
			s, err := srv2.AcceptStream()
			if err != nil {
				return
			}
			s.Close()
		}
	}()

	// First stream succeeds
	st1, err := m.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream failed: %v", err)
	}
	st1.Close()

	// Kill first session
	_ = cli1.Close()

	// Second stream should seamlessly failover to cli2
	st2, err := m.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream failover failed: %v", err)
	}
	st2.Close()

	// Kill second session
	_ = cli2.Close()

	// Now all sessions are dead, OpenStream must return error
	_, err = m.OpenStream()
	if err == nil {
		t.Fatalf("Expected error when all sessions dead, got nil")
	}
}

func TestUplinkRotationThreshold(t *testing.T) {
	cipherKey := make([]byte, 32)
	copy(cipherKey, "32-bytes-long-cipher-key-test!!!")
	baseIV := make([]byte, 12)
	copy(baseIV, "12-bytes-iv!")

	makePair := func() (*smux.Session, *smux.Session) {
		c1, c2 := net.Pipe()
		cip1, _ := crypto.NewCipher("chacha20-poly1305", cipherKey)
		cip2, _ := crypto.NewCipher("chacha20-poly1305", cipherKey)
		fc1 := mux.NewFramedConn(c1, cip1, baseIV)
		fc2 := mux.NewFramedConn(c2, cip2, baseIV)
		smuxCfg := mux.DefaultSmuxConfig()

		srvChan := make(chan *smux.Session, 1)
		go func() {
			s, _ := smux.Server(fc2, smuxCfg)
			srvChan <- s
		}()
		cli, err := smux.Client(fc1, smuxCfg)
		if err != nil {
			t.Fatal(err)
		}
		srv := <-srvChan
		return cli, srv
	}

	cli1, srv1 := makePair()
	defer cli1.Close()
	defer srv1.Close()

	cli2, srv2 := makePair()
	defer cli2.Close()
	defer srv2.Close()

	go func() {
		for {
			s, err := srv1.AcceptStream()
			if err != nil {
				return
			}
			s.Close()
		}
	}()
	go func() {
		for {
			s, err := srv2.AcceptStream()
			if err != nil {
				return
			}
			s.Close()
		}
	}()

	m := mux.NewMultiplexer()
	ts1 := m.AddSession(cli1)
	ts2 := m.AddSession(cli2)

	if ts1.ShouldRotate() {
		t.Fatalf("ts1 should not rotate before bytes recorded")
	}

	// Record bytes exceeding 1.5MB threshold on session 1
	ts1.RecordUplinkBytes(mux.UplinkRotationThreshold + 1024)
	if !ts1.ShouldRotate() {
		t.Fatalf("ts1 should rotate after exceeding UplinkRotationThreshold")
	}
	if ts2.ShouldRotate() {
		t.Fatalf("ts2 should not rotate")
	}

	// OpenStream should now prioritize session 2
	st, err := m.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream failed: %v", err)
	}
	_ = st.Close()
}
