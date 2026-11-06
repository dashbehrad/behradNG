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
	HeaderSize                     = 24 // 6 32-bit words (Word 1: Type+StreamID, Word 2: Len+Pad, Word 3: Seq, Word 4-6: IV)

	PacketTypeData  byte = 0x01
	PacketTypePing  byte = 0x02
	PacketTypePong  byte = 0x03
	PacketTypeClose byte = 0x04
	PacketTypeUDP   byte = 0x05

	MaxPayloadSize           = 65535 - HeaderSize - TLSRecordHeaderSize - crypto.TagSize - MaxPaddingSize
	MaxPaddingSize           = 512
	MorphingPacketsThreshold = 12
)

var (
	ErrPayloadTooLarge = errors.New("payload exceeds maximum size")
	ErrInvalidPacket   = errors.New("invalid saturn packet")
	ErrDecryptFailed   = errors.New("packet decryption failed")
)

// Header models the 24-byte Saturn binary packet header.
type Header struct {
	PacketType byte
	StreamID   uint32 // 24-bit
	PayloadLen uint16
	PaddingLen uint16
	Seq        uint32
	Nonce      [crypto.NonceSize]byte
}

// EncodeHeader writes header fields into a 24-byte buffer.
func (h *Header) Encode(buf []byte) {
	_ = buf[23] // bounds check hint
	buf[0] = h.PacketType
	buf[1] = byte(h.StreamID >> 16)
	buf[2] = byte(h.StreamID >> 8)
	buf[3] = byte(h.StreamID)
	binary.BigEndian.PutUint16(buf[4:6], h.PayloadLen)
	binary.BigEndian.PutUint16(buf[6:8], h.PaddingLen)
	binary.BigEndian.PutUint32(buf[8:12], h.Seq)
	copy(buf[12:24], h.Nonce[:])
}

// DecodeHeader reads a 24-byte buffer into Header.
func DecodeHeader(buf []byte) Header {
	_ = buf[23]
	streamID := (uint32(buf[1]) << 16) | (uint32(buf[2]) << 8) | uint32(buf[3])
	var h Header
	h.PacketType = buf[0]
	h.StreamID = streamID
	h.PayloadLen = binary.BigEndian.Uint16(buf[4:6])
	h.PaddingLen = binary.BigEndian.Uint16(buf[6:8])
	h.Seq = binary.BigEndian.Uint32(buf[8:12])
	copy(h.Nonce[:], buf[12:24])
	return h
}

// PacketManager manages sequence numbers and dynamic padding for streams.
type PacketManager struct {
	seqCounter   atomic.Uint32
	packetsSent  atomic.Uint32
	cipher       *crypto.Cipher
	baseNonce    [crypto.NonceSize]byte
}

// NewPacketManager initializes a packet manager with a cipher and base IV.
func NewPacketManager(cipher *crypto.Cipher, baseIV []byte) *PacketManager {
	pm := &PacketManager{cipher: cipher}
	if len(baseIV) >= crypto.NonceSize {
		copy(pm.baseNonce[:], baseIV[:crypto.NonceSize])
	} else {
		_, _ = io.ReadFull(rand.Reader, pm.baseNonce[:])
	}
	return pm
}

// WritePacket encodes, encrypts, pads, and wraps a packet into a standard TLS 1.3 Application Data record.
func (pm *PacketManager) WritePacket(w io.Writer, packetType byte, streamID uint32, plaintext []byte) error {
	if len(plaintext) > MaxPayloadSize {
		return ErrPayloadTooLarge
	}

	seq := pm.seqCounter.Add(1)
	count := pm.packetsSent.Add(1)

	// Calculate dynamic obfuscation padding for the first 12 packets
	var padLen uint16
	if count <= MorphingPacketsThreshold {
		var r [2]byte
		_, _ = rand.Read(r[:])
		padLen = uint16(binary.BigEndian.Uint16(r[:]) % (MaxPaddingSize + 1))
	}

	// Generate IV / Nonce derived from baseNonce ^ seq
	var nonce [crypto.NonceSize]byte
	copy(nonce[:], pm.baseNonce[:])
	binary.BigEndian.PutUint32(nonce[crypto.NonceSize-4:], binary.BigEndian.Uint32(nonce[crypto.NonceSize-4:])^seq)

	var h Header
	h.PacketType = packetType
	h.StreamID = streamID & 0x00FFFFFF
	h.PayloadLen = uint16(len(plaintext))
	h.PaddingLen = padLen
	h.Seq = seq
	h.Nonce = nonce

	hdrBuf := make([]byte, HeaderSize)
	h.Encode(hdrBuf)

	// Authenticate the 12-byte header prefix as Additional Authenticated Data (AAD)
	aad := hdrBuf[:12]
	ciphertext := pm.cipher.Encrypt(nonce[:], plaintext, aad)

	// Calculate total TLS record length = HeaderSize + len(ciphertext) + padLen
	recLen := HeaderSize + len(ciphertext) + int(padLen)

	packetBuf := make([]byte, TLSRecordHeaderSize+recLen)
	// TLS 1.3 Application Data Record header: 0x17 0x03 0x03 [len_hi] [len_lo]
	packetBuf[0] = RecordTypeApplicationData
	packetBuf[1] = 0x03
	packetBuf[2] = 0x03
	binary.BigEndian.PutUint16(packetBuf[3:5], uint16(recLen))

	// Copy Saturn header
	copy(packetBuf[TLSRecordHeaderSize:], hdrBuf)
	// Copy ciphertext
	copy(packetBuf[TLSRecordHeaderSize+HeaderSize:], ciphertext)

	// Dynamic obfuscation padding
	if padLen > 0 {
		pad := packetBuf[TLSRecordHeaderSize+HeaderSize+len(ciphertext):]
		_, _ = rand.Read(pad)
	}

	_, err := w.Write(packetBuf)
	return err
}

// ReadPacket reads a complete TLS Application Data record, un-pads, decrypts, and returns plaintext.
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
	if recordLen < HeaderSize+pm.cipher.Overhead() {
		return 0, 0, nil, ErrInvalidPacket
	}

	// 2. Read Saturn 24-byte header
	hdrBuf := make([]byte, HeaderSize)
	if _, err := io.ReadFull(r, hdrBuf); err != nil {
		return 0, 0, nil, err
	}
	h := DecodeHeader(hdrBuf)

	cipherLen := int(h.PayloadLen) + pm.cipher.Overhead()
	if HeaderSize+cipherLen+int(h.PaddingLen) != recordLen {
		return 0, 0, nil, ErrInvalidPacket
	}

	// 3. Read ciphertext
	cipherBuf := make([]byte, cipherLen)
	if _, err := io.ReadFull(r, cipherBuf); err != nil {
		return 0, 0, nil, err
	}

	// 4. Read dynamic padding
	if h.PaddingLen > 0 {
		padBuf := make([]byte, h.PaddingLen)
		if _, err := io.ReadFull(r, padBuf); err != nil {
			return 0, 0, nil, err
		}
	}

	// 5. Decrypt and verify AAD
	aad := hdrBuf[:12]
	plaintext, err := pm.cipher.Decrypt(h.Nonce[:], cipherBuf, aad)
	if err != nil {
		return 0, 0, nil, ErrDecryptFailed
	}

	return h.PacketType, h.StreamID, plaintext, nil
}
