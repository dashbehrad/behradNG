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

func TestLargeUploadChunking(t *testing.T) {
	key := make([]byte, 32)
	copy(key, "saturn-32-byte-test-key-secret!!")
	c, err := crypto.NewCipher(crypto.CipherChaCha20Poly1305, key)
	if err != nil {
		t.Fatal(err)
	}

	baseIV := make([]byte, 12)
	copy(baseIV, "saturn-base-")
	pm := framing.NewPacketManager(c, baseIV)

	// 32 KB upload payload
	largePayload := make([]byte, 32768)
	for i := range largePayload {
		largePayload[i] = byte(i % 251)
	}

	var buf bytes.Buffer
	err = pm.WritePacket(&buf, framing.PacketTypeData, 1, largePayload)
	if err != nil {
		t.Fatalf("WritePacket with large payload failed: %v", err)
	}

	// Read all chunks back and verify each chunk is <= MaxPayloadChunk
	var assembled bytes.Buffer
	chunkCount := 0
	for buf.Len() > 0 {
		pktType, _, chunk, err := pm.ReadPacket(&buf)
		if err != nil {
			t.Fatalf("ReadPacket failed at chunk %d: %v", chunkCount, err)
		}
		if pktType != framing.PacketTypeData {
			t.Errorf("Expected PacketTypeData, got %d", pktType)
		}
		if len(chunk) > framing.MaxPayloadChunk {
			t.Errorf("Chunk %d length %d exceeds MaxPayloadChunk %d", chunkCount, len(chunk), framing.MaxPayloadChunk)
		}
		assembled.Write(chunk)
		chunkCount++
	}

	if chunkCount != (32768+framing.MaxPayloadChunk-1)/framing.MaxPayloadChunk {
		t.Errorf("Expected %d chunks, got %d", (32768+framing.MaxPayloadChunk-1)/framing.MaxPayloadChunk, chunkCount)
	}

	if !bytes.Equal(assembled.Bytes(), largePayload) {
		t.Errorf("Reassembled payload does not match original 32KB payload")
	}
}
