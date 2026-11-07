package config

import (
	"encoding/json"
	"os"
)

// ServerConfig defines the JSON structure for /etc/saturn/config.json.
type ServerConfig struct {
	Listen       string `json:"listen"`        // e.g. "0.0.0.0:443"
	PrivateKey   string `json:"private_key"`   // X25519 Private Key (base64 or hex)
	PublicKey    string `json:"public_key"`    // X25519 Public Key (base64 or hex)
	UUID         string `json:"uuid"`          // Client auth UUID
	ShortID      string `json:"short_id"`      // 8-character hex
	FallbackDest string `json:"fallback_dest"` // e.g. "dl.google.com:443"
	Cipher       string `json:"cipher"`        // "chacha20-poly1305" or "aes-128-gcm"
}

// ClientConfig defines the JSON structure for client execution.
type ClientConfig struct {
	LocalListen string `json:"local_listen"` // e.g. "127.0.0.1:10808"
	Profile     SaturnProfile `json:"profile"`
	Connections int    `json:"connections"`  // Pool size (1-4)
}

// LoadServerConfig reads and parses server JSON configuration.
func LoadServerConfig(path string) (*ServerConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg ServerConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	if cfg.Listen == "" {
		cfg.Listen = "0.0.0.0:443"
	}
	if cfg.FallbackDest == "" {
		cfg.FallbackDest = "dl.google.com:443"
	}
	if cfg.Cipher == "" {
		cfg.Cipher = "chacha20-poly1305"
	}
	return &cfg, nil
}

// SaveServerConfig writes server configuration to disk.
func SaveServerConfig(path string, cfg *ServerConfig) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0600)
}

// LoadClientConfig reads and parses client JSON configuration.
func LoadClientConfig(path string) (*ClientConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg ClientConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	if cfg.LocalListen == "" {
		cfg.LocalListen = "127.0.0.1:10808"
	}
	if cfg.Connections <= 0 {
		cfg.Connections = 2
	}
	return &cfg, nil
}

// SaveClientConfig writes client configuration to disk.
func SaveClientConfig(path string, cfg *ClientConfig) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0644)
}
