package main

import (
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/google/uuid"
	"github.com/xtaci/smux"
	"saturn-core/pkg/config"
	"saturn-core/pkg/crypto"
	"saturn-core/pkg/handshake"
	"saturn-core/pkg/mux"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	command := os.Args[1]
	switch command {
	case "keygen":
		handleKeygen()
	case "run":
		handleRun(os.Args[2:])
	default:
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println("Usage: saturn-server <command> [options]")
	fmt.Println("Commands:")
	fmt.Println("  keygen                    Generate X25519 keys, UUID, and ShortID")
	fmt.Println("  run -c <config.json>      Start Saturn server daemon")
}

func handleKeygen() {
	kp, err := crypto.GenerateKeypair()
	if err != nil {
		log.Fatalf("Failed to generate X25519 keypair: %v", err)
	}

	clientUUID := uuid.New().String()

	var sidBytes [4]byte
	_, _ = rand.Read(sidBytes[:])
	shortID := hex.EncodeToString(sidBytes[:])

	privStr := crypto.EncodeKeyBase64(kp.PrivateKey[:])
	pubStr := crypto.EncodeKeyBase64(kp.PublicKey[:])

	fmt.Println("=== Saturn Keygen ===")
	fmt.Printf("Private Key: %s\n", privStr)
	fmt.Printf("Public Key:  %s\n", pubStr)
	fmt.Printf("UUID:        %s\n", clientUUID)
	fmt.Printf("ShortID:     %s\n", shortID)
}

func handleRun(args []string) {
	fs := flag.NewFlagSet("run", flag.ExitOnError)
	configPath := fs.String("c", "/etc/saturn/config.json", "Path to server JSON config")
	_ = fs.Parse(args)

	cfg, err := config.LoadServerConfig(*configPath)
	if err != nil {
		log.Fatalf("Failed to load server config: %v", err)
	}

	privKey, err := crypto.ParseKey(cfg.PrivateKey)
	if err != nil {
		log.Fatalf("Invalid private key: %v", err)
	}

	var pubKey []byte
	if cfg.PublicKey != "" {
		pubKey, err = crypto.ParseKey(cfg.PublicKey)
		if err != nil {
			log.Printf("Warning: public key parse failed: %v", err)
		}
	}

	shortID := handshake.ParseShortID(cfg.ShortID)

	listener, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		log.Fatalf("Failed to listen on %s: %v", cfg.Listen, err)
	}
	defer listener.Close()

	log.Printf("[Saturn Server] Listening on %s | Fallback: %s | Cipher: %s", cfg.Listen, cfg.FallbackDest, cfg.Cipher)

	handshakeCfg := &handshake.ServerHandshakeConfig{
		PrivateKey:   privKey,
		PublicKey:    pubKey,
		UUID:         cfg.UUID,
		ShortID:      shortID,
		FallbackDest: cfg.FallbackDest,
		Cipher:       cfg.Cipher,
	}

	// Graceful shutdown
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigChan
		log.Println("[Saturn Server] Shutting down...")
		_ = listener.Close()
		os.Exit(0)
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Temporary() {
				time.Sleep(10 * time.Millisecond)
				continue
			}
			return
		}

		go handleClientConnection(conn, handshakeCfg)
	}
}

func handleClientConnection(conn net.Conn, cfg *handshake.ServerHandshakeConfig) {
	// Enable TCP KeepAlive & NoDelay for mobile cellular stability
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(10 * time.Second)
		_ = tc.SetNoDelay(true)
	}

	result, authedConn, err := handshake.HandleServerHandshake(conn, cfg)
	if err != nil {
		// Logged or handled by active probe fallback
		return
	}

	// Handshake verified: establish Saturn stealth framing & multiplexer
	serverSendCipher, err := crypto.NewCipher(cfg.Cipher, result.ServerKey)
	if err != nil {
		log.Printf("Cipher error: %v", err)
		_ = authedConn.Close()
		return
	}
	serverRecvCipher, err := crypto.NewCipher(cfg.Cipher, result.ClientKey)
	if err != nil {
		log.Printf("Cipher error: %v", err)
		_ = authedConn.Close()
		return
	}

	framedConn := mux.NewDirectionalFramedConn(authedConn, serverSendCipher, serverRecvCipher, result.ServerIV, result.ClientIV)
	smuxSess, err := smux.Server(framedConn, mux.DefaultSmuxConfig())
	if err != nil {
		log.Printf("smux server error: %v", err)
		_ = framedConn.Close()
		return
	}
	defer smuxSess.Close()

	for {
		stream, err := smuxSess.AcceptStream()
		if err != nil {
			return
		}
		go handleStream(stream)
	}
}

func handleStream(stream *smux.Stream) {
	defer stream.Close()

	targetAddr, err := mux.ReadAddressHeader(stream)
	if err != nil {
		return
	}
	targetAddr = strings.TrimPrefix(targetAddr, "//")

	destConn, err := net.DialTimeout("tcp", targetAddr, 10*time.Second)
	if err != nil {
		log.Printf("[Saturn Server] Failed to dial target %s: %v", targetAddr, err)
		return
	}
	defer destConn.Close()
	if tc, ok := destConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	mux.RelayStreams(stream, destConn)
}
