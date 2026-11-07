package dpi

import (
	"bytes"
	"net"
	"testing"
)

// mockConn simulates a network connection recording written data.
type mockConn struct {
	net.Conn
	buf bytes.Buffer
}

func (m *mockConn) Write(b []byte) (int, error) {
	return m.buf.Write(b)
}

func (m *mockConn) Close() error {
	return nil
}

// buildSampleClientHello builds a realistic synthetic TLS 1.3 ClientHello record.
func buildSampleClientHello(serverName string) []byte {
	// Handshake body
	var body bytes.Buffer
	// Handshake Type: ClientHello (0x01)
	body.WriteByte(0x01)
	// Placeholder for 3-byte length
	body.Write([]byte{0x00, 0x00, 0x00})

	// Client Version: TLS 1.2 (0x0303)
	body.Write([]byte{0x03, 0x03})
	// Random (32 bytes)
	body.Write(make([]byte, 32))
	// Session ID length + Session ID (32 bytes)
	body.WriteByte(32)
	body.Write(make([]byte, 32))

	// Cipher Suites (e.g. 2 suites = 4 bytes)
	body.Write([]byte{0x00, 0x04, 0x13, 0x01, 0x13, 0x02})
	// Compression Methods (1 byte = 0x00)
	body.Write([]byte{0x01, 0x00})

	// Extensions
	var exts bytes.Buffer
	// SNI Extension (0x0000)
	sniBytes := []byte(serverName)
	var sniExt bytes.Buffer
	sniExt.Write([]byte{0x00, byte(len(sniBytes) + 3)}) // list length
	sniExt.WriteByte(0x00)                               // host_name type
	sniExt.Write([]byte{0x00, byte(len(sniBytes))})     // name length
	sniExt.Write(sniBytes)

	exts.Write([]byte{0x00, 0x00}) // SNI type
	exts.Write([]byte{0x00, byte(sniExt.Len())})
	exts.Write(sniExt.Bytes())

	// Supported Versions Extension (0x002B)
	exts.Write([]byte{0x00, 0x2B, 0x00, 0x03, 0x02, 0x03, 0x04})

	// Key Share Extension (0x0033)
	exts.Write([]byte{0x00, 0x33, 0x00, 0x24, 0x00, 0x22, 0x00, 0x1D, 0x00, 0x20})
	exts.Write(make([]byte, 32)) // 32-byte key share

	// Write extension length
	body.Write([]byte{byte(exts.Len() >> 8), byte(exts.Len())})
	body.Write(exts.Bytes())

	rawBody := body.Bytes()
	// Fill length (excluding 4-byte header)
	bodyLen := len(rawBody) - 4
	rawBody[1] = byte(bodyLen >> 16)
	rawBody[2] = byte(bodyLen >> 8)
	rawBody[3] = byte(bodyLen)

	// Wrap in TLS Record Header: 0x16 0x03 0x01 [length]
	record := make([]byte, 5+len(rawBody))
	record[0] = 0x16
	record[1] = 0x03
	record[2] = 0x01
	record[3] = byte(len(rawBody) >> 8)
	record[4] = byte(len(rawBody))
	copy(record[5:], rawBody)

	return record
}

func TestInspectClientHello(t *testing.T) {
	sni := "dl.google.com"
	rec := buildSampleClientHello(sni)
	offsets := InspectClientHello(rec)

	if !offsets.IsValidCH {
		t.Fatalf("expected valid ClientHello, got invalid")
	}
	if offsets.SNIExtPos <= 0 {
		t.Errorf("expected positive SNIExtPos, got %d", offsets.SNIExtPos)
	}
	if offsets.SNINameLen != len(sni) {
		t.Errorf("expected SNINameLen=%d, got %d", len(sni), offsets.SNINameLen)
	}
	parsedSNI := string(rec[offsets.SNINamePos : offsets.SNINamePos+offsets.SNINameLen])
	if parsedSNI != sni {
		t.Errorf("expected SNI '%s', got '%s'", sni, parsedSNI)
	}
	if offsets.SNIFirstDotPos <= offsets.SNINamePos {
		t.Errorf("expected first dot after name pos, got %d vs %d", offsets.SNIFirstDotPos, offsets.SNINamePos)
	}
}

func TestRewriteOuterVersion(t *testing.T) {
	rec := buildSampleClientHello("test.example.com")
	rewritten := RewriteOuterVersion(rec, 0x0304)
	if rewritten[1] != 0x03 || rewritten[2] != 0x04 {
		t.Errorf("expected record version 0x0304, got 0x%02x%02x", rewritten[1], rewritten[2])
	}
}

func TestBuildPreambleBytes(t *testing.T) {
	p1 := BuildPreambleBytes("2xzero")
	if len(p1) != 10 {
		t.Errorf("expected 10 bytes for 2xzero, got %d", len(p1))
	}
	p2 := BuildPreambleBytes("4xzero")
	if len(p2) != 20 {
		t.Errorf("expected 20 bytes for 4xzero, got %d", len(p2))
	}
	p3 := BuildPreambleBytes("zero0304")
	if len(p3) != 5 {
		t.Errorf("expected 5 bytes for zero0304, got %d", len(p3))
	}
}

func TestSplitRecordAtomic(t *testing.T) {
	rec := buildSampleClientHello("test.example.com")
	cut := 50
	split := SplitRecordAtomic(rec, cut, 0x0304)
	if len(split) != len(rec)+5 {
		t.Errorf("expected split length %d, got %d", len(rec)+5, len(split))
	}
	if split[0] != 0x16 || split[1] != 0x03 || split[2] != 0x01 {
		t.Errorf("record 1 header invalid")
	}
}

func TestTransmitAll24Vectors(t *testing.T) {
	rec := buildSampleClientHello("dl.google.com")

	for _, vecID := range AutopilotPriorityIDs {
		vec := GetVector(vecID)
		if vec == nil {
			t.Fatalf("vector %s not registered", vecID)
		}

		conn := &mockConn{}
		sent, label, err := TransmitClientHello(conn, rec, vec, 400, 10)
		if err != nil {
			t.Errorf("vector %s (%s) failed: %v", vec.VectorID, vec.CodeName, err)
		}
		if sent <= 0 {
			t.Errorf("vector %s (%s) transmitted 0 bytes", vec.VectorID, vec.CodeName)
		}
		if conn.buf.Len() <= 0 {
			t.Errorf("vector %s (%s) recorded 0 buffer bytes", vec.VectorID, vec.CodeName)
		}
		t.Logf("Vector %s (%s): %s -> %d bytes sent", vec.VectorID, vec.CodeName, label, sent)
	}
}

func TestTransmitPlainHTTP(t *testing.T) {
	payload := []byte("GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: curl/7.88.1\r\n\r\n")
	vec := GetVector("VEC-01")
	conn := &mockConn{}
	sent, label, err := TransmitPlainHTTP(conn, payload, vec, 400, 10)
	if err != nil {
		t.Fatalf("TransmitPlainHTTP failed: %v", err)
	}
	if sent <= 0 || conn.buf.Len() <= 0 {
		t.Fatalf("expected data sent, got sent=%d len=%d", sent, conn.buf.Len())
	}
	t.Logf("HTTP Desync: %s -> %d bytes", label, sent)
}

