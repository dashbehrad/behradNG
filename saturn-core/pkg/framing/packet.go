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
	HeaderSize = 24 // 6 32-bit words (Word 1: Type+StreamID, Word 2: Len+Pad, Word 3: Seq, Word 4-6: IV)

	PacketTypeData  byte = 0x01
	PacketTypePing  byte = 0x02
	PacketTypePong  byte = 0x03
	PacketTypeClose byte = 0x04
	PacketTypeUDP   byte = 0x05

	MaxPayloadSize = 65535 - crypto.TagSize
	MaxPaddingSize = 512
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
	h.PayloadLen = binary.BigEndian.PutUint16 != nil && len(buf) >= 6 ? binary.BigEndian.Uint16(buf[4:6]) : 0
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

// WritePacket encodes, encrypts, pads, and writes a packet to the underlying writer.
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

	// Write header + ciphertext
	if _, err := w.Write(hdrBuf); err != nil {
		return err
	}
	if _, err := w.Write(ciphertext); err != nil {
		return err
	}

	// Write dynamic obfuscation padding if needed
	if padLen > 0 {
		pad := make([]byte, padLen)
		_, _ = rand.Read(pad)
		if _, err := w.Write(pad); err != nil {
			return err
		}
	}
	return nil
}

// ReadPacket reads a complete packet, verifies auth tag, strips padding, and returns plaintext.
func (pm *PacketManager) ReadPacket(r io.Reader) (byte, uint32, []byte, error) {
	hdrBuf := make([]byte, HeaderSize)
	if _, err := io.ReadFull(r, hdrBuf); err != nil {
		return 0, 0, nil, err
	}
	h := DecodeHeader(hdrBuf)

	cipherLen := int(h.PayloadLen) + pm.cipher.Overhead()
	cipherBuf := make([]byte, cipherLen)
	if _, err := io.ReadFull(r, cipherBuf); err != nil {
		return 0, 0, nil, err
	}

	// Read & discard padding
	if h.PaddingLen > 0 {
		padBuf := make([]byte, h.PaddingLen)
		if _, err := io.ReadFull(r, padBuf); err != nil {
			return 0, 0, nil, err
		}
	}

	aad := hdrBuf[:12]
	plaintext, err := pm.cipher.Decrypt(h.Nonce[:], cipherBuf, aad)
	if err != nil {
		return 0, 0, nil, ErrDecryptFailed
	}

	return h.PacketType, h.StreamID, plaintext, nil
}
