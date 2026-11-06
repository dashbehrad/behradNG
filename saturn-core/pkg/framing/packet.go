package framing

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"io"
	"sync/atomic"

	"saturn-core/pkg/crypto"
)

const (
	RecordTypeApplicationData byte = 0x17
	TLSRecordHeaderSize            = 5
	InnerHeaderSize                = 6 // Type (1B) + StreamID (3B) + PayloadLen (2B)

	PacketTypeData  byte = 0x01
	PacketTypePing  byte = 0x02
	PacketTypePong  byte = 0x03
	PacketTypeClose byte = 0x04
	PacketTypeUDP   byte = 0x05

	MaxTLSPlaintext          = 16384 // RFC 8446 standard maximum TLS plaintext (16KB)
	MaxPayloadChunk          = 1024  // Base MSS compliance (<= 1024 bytes per frame chunk)
	MaxPayloadSize           = MaxPayloadChunk
	MaxPaddingSize           = 256
	MorphingPacketsThreshold = 12
)

var (
	ErrPayloadTooLarge = errors.New("payload exceeds maximum size")
	ErrInvalidPacket   = errors.New("invalid saturn packet")
	ErrDecryptFailed   = errors.New("packet decryption failed")
)

// deriveNonce derives per-record 12-byte nonce by XORing the static IV with sequence counter (RFC 8446 Section 5.3).
func deriveNonce(baseIV []byte, seq uint64) [crypto.NonceSize]byte {
	var nonce [crypto.NonceSize]byte
	if len(baseIV) >= crypto.NonceSize {
		copy(nonce[:], baseIV[:crypto.NonceSize])
	}
	var seqBytes [8]byte
	binary.BigEndian.PutUint64(seqBytes[:], seq)
	for i := 0; i < 8; i++ {
		nonce[crypto.NonceSize-8+i] ^= seqBytes[i]
	}
	return nonce
}

// PacketManager manages sequence numbers, dynamic obfuscation padding, and TLS 1.3 AEAD record encryption.
type PacketManager struct {
	writeSeq    atomic.Uint64
	readSeq     atomic.Uint64
	packetsSent atomic.Uint32

	sendCipher *crypto.Cipher
	recvCipher *crypto.Cipher
	sendIV     [crypto.NonceSize]byte
	recvIV     [crypto.NonceSize]byte
}

// NewPacketManager initializes a symmetric packet manager with a shared cipher and base IV.
func NewPacketManager(cipher *crypto.Cipher, baseIV []byte) *PacketManager {
	return NewDirectionalPacketManager(cipher, cipher, baseIV, baseIV)
}

// NewDirectionalPacketManager initializes a packet manager with separate directional ciphers and IVs.
func NewDirectionalPacketManager(sendCipher, recvCipher *crypto.Cipher, sendIV, recvIV []byte) *PacketManager {
	pm := &PacketManager{
		sendCipher: sendCipher,
		recvCipher: recvCipher,
	}
	if len(sendIV) >= crypto.NonceSize {
		copy(pm.sendIV[:], sendIV[:crypto.NonceSize])
	} else {
		_, _ = io.ReadFull(rand.Reader, pm.sendIV[:])
	}
	if len(recvIV) >= crypto.NonceSize {
		copy(pm.recvIV[:], recvIV[:crypto.NonceSize])
	} else {
		_, _ = io.ReadFull(rand.Reader, pm.recvIV[:])
	}
	return pm
}

// WritePacket encodes, dynamic-pads, and wraps payload into 100% RFC 8446 TLS 1.3 Application Data records.
// Large payloads exceeding MaxPayloadChunk (1220 bytes) are split into individual chunks so no IP fragmentation occurs.
// All metadata (packetType, streamID, payloadLen, padding) is fully encrypted inside the AEAD record.
func (pm *PacketManager) WritePacket(w io.Writer, packetType byte, streamID uint32, plaintext []byte) error {
	if len(plaintext) == 0 {
		return pm.writeSingleRecord(w, packetType, streamID, nil)
	}

	for len(plaintext) > 0 {
		chunkSize := len(plaintext)
		if chunkSize > MaxPayloadChunk {
			chunkSize = MaxPayloadChunk
		}
		if err := pm.writeSingleRecord(w, packetType, streamID, plaintext[:chunkSize]); err != nil {
			return err
		}
		plaintext = plaintext[chunkSize:]
	}
	return nil
}

// writeSingleRecord handles encryption and smart padding for a single frame chunk of at most MaxPayloadChunk.
func (pm *PacketManager) writeSingleRecord(w io.Writer, packetType byte, streamID uint32, plaintext []byte) error {
	if len(plaintext) > MaxPayloadChunk {
		return ErrPayloadTooLarge
	}

	seq := pm.writeSeq.Add(1)
	count := pm.packetsSent.Add(1)

	// Conditional Smart Padding: During the first 12 packets, ONLY apply random padding
	// if len(payload) < 400. If a packet is already >= 400 bytes (upload data chunk),
	// padLen is 0 so uplink throughput is never bloated or fragmented.
	var padLen uint16
	if count <= MorphingPacketsThreshold && len(plaintext) < 400 {
		var r [2]byte
		_, _ = rand.Read(r[:])
		padLen = 16 + (binary.BigEndian.Uint16(r[:]) % (MaxPaddingSize - 16 + 1))
	}

	innerLen := InnerHeaderSize + len(plaintext) + int(padLen)
	plainBuf := make([]byte, innerLen)

	// Encrypted inner envelope: [Type 1B][StreamID 3B][PayloadLen 2B][Data][Padding]
	plainBuf[0] = packetType
	plainBuf[1] = byte(streamID >> 16)
	plainBuf[2] = byte(streamID >> 8)
	plainBuf[3] = byte(streamID)
	binary.BigEndian.PutUint16(plainBuf[4:6], uint16(len(plaintext)))
	copy(plainBuf[6:6+len(plaintext)], plaintext)

	if padLen > 0 {
		_, _ = io.ReadFull(rand.Reader, plainBuf[6+len(plaintext):])
	}

	nonce := deriveNonce(pm.sendIV[:], seq)

	// TLS 1.3 Application Data Record header: 0x17 0x03 0x03 [len_hi] [len_lo]
	var tlsHeader [TLSRecordHeaderSize]byte
	tlsHeader[0] = RecordTypeApplicationData
	tlsHeader[1] = 0x03
	tlsHeader[2] = 0x03
	recLen := len(plainBuf) + pm.sendCipher.Overhead()
	binary.BigEndian.PutUint16(tlsHeader[3:5], uint16(recLen))

	// Additional Authenticated Data is the 5-byte TLS record header with record length
	ciphertext := pm.sendCipher.Encrypt(nonce[:], plainBuf, tlsHeader[:])

	out := make([]byte, TLSRecordHeaderSize+len(ciphertext))
	copy(out[:TLSRecordHeaderSize], tlsHeader[:])
	copy(out[TLSRecordHeaderSize:], ciphertext)

	_, err := w.Write(out)
	return err
}

// ReadPacket reads a complete TLS Application Data record, decrypts, verifies integrity, un-pads, and returns data.
func (pm *PacketManager) ReadPacket(r io.Reader) (byte, uint32, []byte, error) {
	// 1. Read 5-byte TLS record header
	tlsHeader := make([]byte, TLSRecordHeaderSize)
	if _, err := io.ReadFull(r, tlsHeader); err != nil {
		return 0, 0, nil, err
	}

	// Verify TLS Application Data header: 0x17 0x03 0x03
	if tlsHeader[0] != RecordTypeApplicationData || tlsHeader[1] != 0x03 || tlsHeader[2] != 0x03 {
		return 0, 0, nil, ErrInvalidPacket
	}

	recordLen := int(binary.BigEndian.Uint16(tlsHeader[3:5]))
	if recordLen < InnerHeaderSize+pm.recvCipher.Overhead() || recordLen > MaxTLSPlaintext+pm.recvCipher.Overhead()+MaxPaddingSize {
		return 0, 0, nil, ErrInvalidPacket
	}

	// 2. Read entire encrypted record
	cipherBuf := make([]byte, recordLen)
	if _, err := io.ReadFull(r, cipherBuf); err != nil {
		return 0, 0, nil, err
	}

	// 3. Derive per-record nonce matching sender sequence
	seq := pm.readSeq.Add(1)
	nonce := deriveNonce(pm.recvIV[:], seq)

	// 4. Decrypt AEAD record and verify AAD
	plaintext, err := pm.recvCipher.Decrypt(nonce[:], cipherBuf, tlsHeader)
	if err != nil {
		return 0, 0, nil, ErrDecryptFailed
	}

	if len(plaintext) < InnerHeaderSize {
		return 0, 0, nil, ErrInvalidPacket
	}

	packetType := plaintext[0]
	streamID := (uint32(plaintext[1]) << 16) | (uint32(plaintext[2]) << 8) | uint32(plaintext[3])
	payloadLen := int(binary.BigEndian.Uint16(plaintext[4:6]))

	if InnerHeaderSize+payloadLen > len(plaintext) {
		return 0, 0, nil, ErrInvalidPacket
	}

	payload := plaintext[InnerHeaderSize : InnerHeaderSize+payloadLen]
	return packetType, streamID, payload, nil
}
