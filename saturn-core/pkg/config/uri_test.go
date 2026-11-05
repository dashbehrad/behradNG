package config_test

import (
	"testing"

	"saturn-core/pkg/config"
	"saturn-core/pkg/crypto"
)

func TestURIParseAndBuild(t *testing.T) {
	kp, err := crypto.GenerateKeypair()
	if err != nil {
		t.Fatal(err)
	}

	pubKeyStr := crypto.EncodeKeyBase64(kp.PublicKey[:])
	rawURI := "saturn://12345678-1234-1234-1234-123456789abc@198.51.100.1:443?sni=dl.google.com&pbk=" + pubKeyStr + "&sid=a1b2c3d4&fp=chrome&frag=1-5#Saturn-Direct"

	profile, err := config.ParseURI(rawURI)
	if err != nil {
		t.Fatalf("ParseURI failed: %v", err)
	}

	if profile.UUID != "12345678-1234-1234-1234-123456789abc" {
		t.Errorf("UUID mismatch: got %s", profile.UUID)
	}
	if profile.Server != "198.51.100.1" || profile.Port != 443 {
		t.Errorf("Server/port mismatch: got %s:%d", profile.Server, profile.Port)
	}
	if profile.SNI != "dl.google.com" {
		t.Errorf("SNI mismatch: got %s", profile.SNI)
	}
	if profile.PublicKey != pubKeyStr {
		t.Errorf("Public key mismatch: got %s", profile.PublicKey)
	}
	if profile.ShortID != "a1b2c3d4" {
		t.Errorf("ShortID mismatch: got %s", profile.ShortID)
	}
	if profile.Fingerprint != "chrome" {
		t.Errorf("Fingerprint mismatch: got %s", profile.Fingerprint)
	}
	if profile.Fragmentation != "1-5" {
		t.Errorf("Fragmentation mismatch: got %s", profile.Fragmentation)
	}
	if profile.Remarks != "Saturn-Direct" {
		t.Errorf("Remarks mismatch: got %s", profile.Remarks)
	}

	rebuilt := profile.BuildURI()
	reProfile, err := config.ParseURI(rebuilt)
	if err != nil {
		t.Fatalf("Failed to parse rebuilt URI: %v", err)
	}

	if reProfile.Server != profile.Server || reProfile.Port != profile.Port || reProfile.UUID != profile.UUID {
		t.Errorf("Rebuilt profile mismatch")
	}
}
