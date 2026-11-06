package framing_test

import (
	"bytes"
	"testing"

	"saturn-core/pkg/crypto"
	"saturn-core/pkg/framing"
)

func TestPacketFraming(t *testing.T) {
	key := make([]byte, 32)
	copy(key, "saturn-32-byte-test-key-secret!!")
	c, err := crypto.NewCipher(crypto.CipherChaCha20Poly1305, key)
	if err != nil {
		t.Fatal(err)
	}

	baseIV := make([]byte, 12)
	copy(baseIV, "saturn-base-")
	pm := framing.NewPacketManager(c, baseIV)

	var buf bytes.Buffer
	payload := []byte("Direct VPN Payload with BBR optimization")
	streamID := uint32(0x123456)

	err = pm.WritePacket(&buf, framing.PacketTypeData, streamID, payload)
	if err != nil {
		t.Fatalf("WritePacket failed: %v", err)
	}

	rawBytes := buf.Bytes()
	if len(rawBytes) < framing.TLSRecordHeaderSize || rawBytes[0] != framing.RecordTypeApplicationData || rawBytes[1] != 0x03 || rawBytes[2] != 0x03 {
		t.Fatalf("Expected TLS 1.3 Application Data record header (0x17 0x03 0x03), got %x", rawBytes[:3])
	}

	pktType, readStreamID, readPayload, err := pm.ReadPacket(&buf)
	if err != nil {
		t.Fatalf("ReadPacket failed: %v", err)
	}

	if pktType != framing.PacketTypeData {
		t.Errorf("Expected PacketTypeData, got %d", pktType)
	}
	if readStreamID != (streamID & 0x00FFFFFF) {
		t.Errorf("Expected StreamID 0x%x, got 0x%x", streamID, readStreamID)
	}
	if !bytes.Equal(readPayload, payload) {
		t.Errorf("Expected %s, got %s", string(payload), string(readPayload))
	}
}
