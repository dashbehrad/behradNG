package config

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"

	"saturn-core/pkg/crypto"
)

// SaturnProfile represents a parsed saturn:// configuration profile.
type SaturnProfile struct {
	UUID          string `json:"uuid"`
	Server        string `json:"server"`
	Port          int    `json:"port"`
	SNI           string `json:"sni"`
	PublicKey     string `json:"public_key"`
	ShortID       string `json:"short_id"`
	Fingerprint   string `json:"fingerprint"`
	Fragmentation string `json:"fragmentation"`
	Remarks       string `json:"remarks"`
}

// ParseURI parses a saturn:// URI into a SaturnProfile.
func ParseURI(rawURI string) (*SaturnProfile, error) {
	trimmed := strings.TrimSpace(rawURI)
	if !strings.HasPrefix(trimmed, "saturn://") {
		return nil, errors.New("invalid scheme, must start with saturn://")
	}

	u, err := url.Parse(trimmed)
	if err != nil {
		return nil, fmt.Errorf("failed to parse URI: %w", err)
	}

	profile := &SaturnProfile{
		Remarks: u.Fragment,
	}
	if profile.Remarks == "" {
		profile.Remarks = "Saturn-Direct"
	}

	// Extract UUID from UserInfo
	if u.User != nil {
		profile.UUID = u.User.Username()
	}

	// Extract Server and Port
	host, portStr, err := net.SplitHostPort(u.Host)
	if err != nil {
		// Host might not contain port
		host = u.Host
		profile.Port = 443
	} else {
		p, err := strconv.Atoi(portStr)
		if err != nil || p <= 0 || p > 65535 {
			return nil, errors.New("invalid port number")
		}
		profile.Port = p
	}
	profile.Server = host

	// Extract Query Parameters
	q := u.Query()
	profile.SNI = q.Get("sni")
	if profile.SNI == "" {
		profile.SNI = "dl.google.com"
	}
	profile.PublicKey = q.Get("pbk")
	profile.ShortID = q.Get("sid")
	profile.Fingerprint = q.Get("fp")
	if profile.Fingerprint == "" {
		profile.Fingerprint = "chrome"
	}
	profile.Fragmentation = q.Get("frag")
	if profile.Fragmentation == "" {
		profile.Fragmentation = "1-5"
	}

	// Validate public key
	if profile.PublicKey != "" {
		_, err := crypto.ParseKey(profile.PublicKey)
		if err != nil {
			return nil, fmt.Errorf("invalid public key: %w", err)
		}
	}

	return profile, nil
}

// BuildURI formats a SaturnProfile into a canonical saturn:// URI string.
func (p *SaturnProfile) BuildURI() string {
	sni := p.SNI
	if sni == "" {
		sni = "dl.google.com"
	}
	fp := p.Fingerprint
	if fp == "" {
		fp = "chrome"
	}
	frag := p.Fragmentation
	if frag == "" {
		frag = "1-5"
	}
	remarks := p.Remarks
	if remarks == "" {
		remarks = "Saturn-Direct"
	}
	port := p.Port
	if port <= 0 {
		port = 443
	}

	q := url.Values{}
	q.Set("sni", sni)
	if p.PublicKey != "" {
		q.Set("pbk", p.PublicKey)
	}
	if p.ShortID != "" {
		q.Set("sid", p.ShortID)
	}
	q.Set("fp", fp)
	q.Set("frag", frag)

	var hostPart string
	if strings.Contains(p.Server, ":") {
		hostPart = fmt.Sprintf("[%s]:%d", p.Server, port)
	} else {
		hostPart = fmt.Sprintf("%s:%d", p.Server, port)
	}

	userPart := ""
	if p.UUID != "" {
		userPart = p.UUID + "@"
	}

	return fmt.Sprintf("saturn://%s%s?%s#%s", userPart, hostPart, q.Encode(), url.QueryEscape(remarks))
}
