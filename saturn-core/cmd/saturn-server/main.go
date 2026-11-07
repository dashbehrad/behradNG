package main

import (
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
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
	if len(os.Args) > 1 {
		cmd := os.Args[1]
		if cmd == "keygen" || cmd == "-keygen" || cmd == "--keygen" {
			handleKeygen()
			return
		}
		if cmd == "-h" || cmd == "--help" || cmd == "help" {
			printUsage()
			return
		}
		if cmd == "run" {
			handleRun(os.Args[2:])
			return
		}
		if strings.HasPrefix(cmd, "-") {
			handleRun(os.Args[1:])
			return
		}
	}

	// Default with 0 args: run directly with automatic configuration generation
	handleRun(nil)
}

func printUsage() {
	fmt.Println("🪐 Saturn Stealth Tunnel Server")
	fmt.Println("Usage: saturn-server [command] [options]")
	fmt.Println("\nCommands:")
	fmt.Println("  (no args)                 Start Saturn server (auto-generates config if missing)")
	fmt.Println("  keygen                    Generate fresh X25519 keys, UUID, and ShortID")
	fmt.Println("  run -c <config.json>      Start Saturn server daemon with explicit config")
	fmt.Println("\nOptions:")
	fmt.Println("  -c <path>                 Path to server JSON config")
	fmt.Println("  -l <address>              Override listening address (e.g. 0.0.0.0:443)")
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

func getDefaultConfigPath() string {
	// 1. If ./config.json exists in working directory
	if _, err := os.Stat("config.json"); err == nil {
		return "config.json"
	}
	// 2. If /etc/saturn/config.json exists
	if _, err := os.Stat("/etc/saturn/config.json"); err == nil {
		return "/etc/saturn/config.json"
	}
	// 3. For Windows or non-root Unix
	if runtime.GOOS == "windows" || os.Geteuid() != 0 {
		return "config.json"
	}
	// 4. Default for Linux root
	return "/etc/saturn/config.json"
}

func detectPublicIP() string {
	client := http.Client{Timeout: 3 * time.Second}
	endpoints := []string{
		"https://api.ipify.org",
		"https://icanhazip.com",
		"https://ifconfig.me/ip",
	}
	for _, ep := range endpoints {
		resp, err := client.Get(ep)
		if err == nil {
			body, err := io.ReadAll(resp.Body)
			resp.Body.Close()
			if err == nil {
				ip := strings.TrimSpace(string(body))
				if net.ParseIP(ip) != nil {
					return ip
				}
			}
		}
	}

	// Fallback to local network interface
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err == nil {
		defer conn.Close()
		localAddr := conn.LocalAddr().(*net.UDPAddr)
		if !localAddr.IP.IsLoopback() {
			return localAddr.IP.String()
		}
	}
	return "YOUR_SERVER_IP"
}

func autoGenerateServerConfig(configPath string) (*config.ServerConfig, string, error) {
	kp, err := crypto.GenerateKeypair()
	if err != nil {
		return nil, "", fmt.Errorf("failed to generate keypair: %w", err)
	}

	clientUUID := uuid.New().String()
	var sidBytes [4]byte
	_, _ = rand.Read(sidBytes[:])
	shortID := hex.EncodeToString(sidBytes[:])

	privStr := crypto.EncodeKeyBase64(kp.PrivateKey[:])
	pubStr := crypto.EncodeKeyBase64(kp.PublicKey[:])

	cfg := &config.ServerConfig{
		Listen:       "0.0.0.0:443",
		PrivateKey:   privStr,
		PublicKey:    pubStr,
		UUID:         clientUUID,
		ShortID:      shortID,
		FallbackDest: "dl.google.com:443",
		Cipher:       "chacha20-poly1305",
	}

	dir := filepath.Dir(configPath)
	if dir != "" && dir != "." {
		_ = os.MkdirAll(dir, 0755)
	}

	if err := config.SaveServerConfig(configPath, cfg); err != nil {
		// If system path write failed, fallback to local current directory
		configPath = "config.json"
		if err2 := config.SaveServerConfig(configPath, cfg); err2 != nil {
			return nil, "", fmt.Errorf("failed to save config to %s: %w", configPath, err2)
		}
	}

	serverIP := detectPublicIP()
	uri := fmt.Sprintf("saturn://%s@%s:443?pbk=%s&sid=%s&sni=dl.google.com&alpn=h2&fp=chrome&cipher=chacha20-poly1305#Saturn-%s",
		clientUUID, serverIP, pubStr, shortID, serverIP)

	return cfg, uri, nil
}

func handleRun(args []string) {
	defaultPath := getDefaultConfigPath()
	fs := flag.NewFlagSet("saturn-server", flag.ExitOnError)
	configPath := fs.String("c", defaultPath, "Path to server JSON config")
	listenOverride := fs.String("l", "", "Override listening address (e.g. 0.0.0.0:443)")
	if len(args) > 0 {
		_ = fs.Parse(args)
	}

	var cfg *config.ServerConfig
	var uri string
	var err error

	if _, statErr := os.Stat(*configPath); os.IsNotExist(statErr) {
		log.Printf("[Saturn Server] Configuration file '%s' not found.", *configPath)
		log.Println("[Saturn Server] Generating a fresh high-security configuration automatically...")
		cfg, uri, err = autoGenerateServerConfig(*configPath)
		if err != nil {
			log.Fatalf("Failed to auto-generate server config: %v", err)
		}

		fmt.Println("\n==================================================================")
		fmt.Println("       🪐 SATURN SERVER - CONFIGURATION GENERATED AUTOMATICALLY    ")
		fmt.Println("==================================================================")
		fmt.Printf("Config File: %s\n", *configPath)
		fmt.Printf("Public Key:  %s\n", cfg.PublicKey)
		fmt.Printf("Client UUID: %s\n", cfg.UUID)
		fmt.Printf("Short ID:    %s\n", cfg.ShortID)
		fmt.Printf("Listen Port: %s\n", cfg.Listen)
		fmt.Println("------------------------------------------------------------------")
		fmt.Println("Client Connection URI (Copy to behradNG or Saturn Client):")
		fmt.Printf("%s\n", uri)
		fmt.Println("==================================================================")
		fmt.Println()
	} else {
		cfg, err = config.LoadServerConfig(*configPath)
		if err != nil {
			log.Fatalf("Failed to load server config %s: %v", *configPath, err)
		}
	}

	if *listenOverride != "" {
		cfg.Listen = *listenOverride
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

	log.Printf("[Saturn Server] Running on %s | Fallback: %s | Cipher: %s", cfg.Listen, cfg.FallbackDest, cfg.Cipher)

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
