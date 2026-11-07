package dpi

import (
	"bytes"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"math/big"
	"net"
	"sort"
	"strings"
	"time"
)

var PolyOOBPool = []byte{
	0x21, 0x23, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2d, 0x2e, 0x33, 0x3b, 0x3d, 0x3f,
	0x40, 0x42, 0x47, 0x4b, 0x4e, 0x51, 0x57, 0x5a, 0x5e, 0x61, 0x6d, 0x71, 0x78, 0x7a, 0x7e,
}

func PolyOOBByte() byte {
	n, _ := rand.Int(rand.Reader, big.NewInt(int64(len(PolyOOBPool))))
	return PolyOOBPool[n.Int64()]
}

func PolyRecordVersion(baseVer uint16) uint16 {
	if baseVer == 0x0304 {
		choices := []uint16{0x0301, 0x0303, 0x0304}
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(len(choices))))
		return choices[n.Int64()]
	}
	choices := []uint16{0x0301, 0x0303}
	n, _ := rand.Int(rand.Reader, big.NewInt(int64(len(choices))))
	return choices[n.Int64()]
}

// ClientHelloOffsets contains parsed byte positions of critical TLS handshake structures.
type ClientHelloOffsets struct {
	IsValidCH       bool
	RecordLen       int
	CipherSuitesPos int
	CipherSuitesLen int
	ExtTotalLenPos  int
	SNIExtPos       int
	SNIListLenPos   int
	SNINameLenPos   int
	SNINamePos      int
	SNINameLen      int
	SNIFirstDotPos  int
	SNILastDotPos   int
	ALPNExtPos      int
	SupVerExtPos    int
	KeyShareExtPos  int
}

// InspectClientHello parses raw TLS ClientHello bytes and extracts key extension offsets.
func InspectClientHello(record []byte) ClientHelloOffsets {
	recLen := len(record)
	midGuess := recLen / 3
	if midGuess < 14 {
		midGuess = 14
	} else if midGuess > 180 {
		midGuess = 180
	}

	extLenPos := midGuess - 12
	if extLenPos < 48 {
		extLenPos = 48
	} else if extLenPos > 120 {
		extLenPos = 120
	}

	info := ClientHelloOffsets{
		IsValidCH:       false,
		RecordLen:       recLen,
		CipherSuitesPos: 43,
		CipherSuitesLen: 32,
		ExtTotalLenPos:  extLenPos,
		SNIExtPos:       midGuess,
		SNIListLenPos:   midGuess + 4,
		SNINameLenPos:   midGuess + 7,
		SNINamePos:      midGuess + 9,
		SNINameLen:      20,
		SNIFirstDotPos:  midGuess + 15,
		SNILastDotPos:   midGuess + 21,
		ALPNExtPos:      -1,
		SupVerExtPos:    -1,
		KeyShareExtPos:  -1,
	}

	if recLen < 48 || record[0] != 0x16 || record[5] != 0x01 {
		return info
	}

	pos := 43
	if pos >= recLen {
		return info
	}
	sidLen := int(record[pos])
	pos += 1 + sidLen
	if pos+2 > recLen {
		return info
	}

	info.CipherSuitesPos = pos
	ciphLen := int(binary.BigEndian.Uint16(record[pos : pos+2]))
	info.CipherSuitesLen = ciphLen
	pos += 2 + ciphLen
	if pos+1 > recLen {
		return info
	}

	compLen := int(record[pos])
	pos += 1 + compLen
	if pos+2 > recLen {
		return info
	}

	info.ExtTotalLenPos = pos
	extTotalLen := int(binary.BigEndian.Uint16(record[pos : pos+2]))
	pos += 2
	end := pos + extTotalLen
	if end > recLen {
		end = recLen
	}
	info.IsValidCH = true

	for pos+4 <= end {
		etype := binary.BigEndian.Uint16(record[pos : pos+2])
		elen := int(binary.BigEndian.Uint16(record[pos+2 : pos+4]))
		extBodyEnd := pos + 4 + elen
		if extBodyEnd > end {
			break
		}

		if etype == 0x0000 && pos+9 <= extBodyEnd {
			nameLen := int(binary.BigEndian.Uint16(record[pos+7 : pos+9]))
			nameStart := pos + 9
			nameEnd := nameStart + nameLen
			if nameEnd > extBodyEnd {
				nameEnd = extBodyEnd
			}

			info.SNIExtPos = pos
			info.SNIListLenPos = pos + 4
			info.SNINameLenPos = pos + 7
			info.SNINamePos = nameStart
			info.SNINameLen = nameEnd - nameStart
			if info.SNINameLen < 1 {
				info.SNINameLen = 1
			}

			hostBytes := record[nameStart:nameEnd]
			firstDot := bytes.IndexByte(hostBytes, '.')
			lastDot := bytes.LastIndexByte(hostBytes, '.')
			if firstDot > 0 {
				info.SNIFirstDotPos = nameStart + firstDot
			} else {
				info.SNIFirstDotPos = nameStart + info.SNINameLen/2
			}
			if lastDot > 0 {
				info.SNILastDotPos = nameStart + lastDot
			} else {
				info.SNILastDotPos = info.SNIFirstDotPos
			}
		} else if etype == 0x0010 {
			info.ALPNExtPos = pos
		} else if etype == 0x002B {
			info.SupVerExtPos = pos
		} else if etype == 0x0033 {
			info.KeyShareExtPos = pos
		}

		pos = extBodyEnd
	}

	return info
}

// RewriteOuterVersion alters the TLS record layer version (bytes 1..2).
func RewriteOuterVersion(record []byte, version uint16) []byte {
	if len(record) < 5 || record[0] != 0x16 {
		return record
	}
	out := make([]byte, len(record))
	copy(out, record)
	out[1] = byte(version >> 8)
	out[2] = byte(version)
	return out
}

// BuildPreambleBytes generates dummy TLS record preambles for middlebox desynchronization.
func BuildPreambleBytes(preambleMode string) []byte {
	switch preambleMode {
	case "zero0304":
		verChoices := [][]byte{{0x03, 0x01}, {0x03, 0x03}, {0x03, 0x04}}
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(len(verChoices))))
		v := verChoices[n.Int64()]
		return []byte{0x16, v[0], v[1], 0x00, 0x00}
	case "2xzero":
		return []byte{0x16, 0x03, 0x01, 0x00, 0x00, 0x16, 0x03, 0x04, 0x00, 0x00}
	case "4xzero":
		return []byte{
			0x16, 0x03, 0x01, 0x00, 0x00,
			0x16, 0x03, 0x02, 0x00, 0x00,
			0x16, 0x03, 0x03, 0x00, 0x00,
			0x16, 0x03, 0x04, 0x00, 0x00,
		}
	default:
		return nil
	}
}

// SplitRecordAtomic splits a single TLS record into two distinct TLS records at cutOffset.
func SplitRecordAtomic(record []byte, cutOffset int, version uint16) []byte {
	if len(record) < 10 || cutOffset <= 5 || cutOffset >= len(record) {
		return RewriteOuterVersion(record, version)
	}
	hs := record[5:]
	cutRel := cutOffset - 5
	part1 := hs[:cutRel]
	part2 := hs[cutRel:]

	rec1 := make([]byte, 5+len(part1))
	rec1[0] = 0x16
	rec1[1] = 0x03
	rec1[2] = 0x01
	binary.BigEndian.PutUint16(rec1[3:5], uint16(len(part1)))
	copy(rec1[5:], part1)

	rec2 := make([]byte, 5+len(part2))
	rec2[0] = 0x16
	rec2[1] = byte(version >> 8)
	rec2[2] = byte(version)
	binary.BigEndian.PutUint16(rec2[3:5], uint16(len(part2)))
	copy(rec2[5:], part2)

	return append(rec1, rec2...)
}

// ReframeHandshakeChunks frames arbitrary handshake body slices into multiple TLS records.
func ReframeHandshakeChunks(hsBody []byte, chunkSizes []int, version uint16, altVersions []uint16) [][]byte {
	var frames [][]byte
	cursor := 0
	total := len(hsBody)
	idx := 0

	for _, sz := range chunkSizes {
		if cursor >= total {
			break
		}
		take := sz
		if take < 1 {
			take = 1
		}
		if cursor+take > total {
			take = total - cursor
		}
		seg := hsBody[cursor : cursor+take]
		recVer := version
		if len(altVersions) > 0 {
			recVer = altVersions[idx%len(altVersions)]
		}

		rec := make([]byte, 5+len(seg))
		rec[0] = 0x16
		rec[1] = byte(recVer >> 8)
		rec[2] = byte(recVer)
		binary.BigEndian.PutUint16(rec[3:5], uint16(len(seg)))
		copy(rec[5:], seg)

		frames = append(frames, rec)
		cursor += take
		idx++
	}

	if cursor < total {
		rem := hsBody[cursor:]
		recVer := version
		if len(altVersions) > 0 {
			recVer = altVersions[idx%len(altVersions)]
		}

		rec := make([]byte, 5+len(rem))
		rec[0] = 0x16
		rec[1] = byte(recVer >> 8)
		rec[2] = byte(recVer)
		binary.BigEndian.PutUint16(rec[3:5], uint16(len(rem)))
		copy(rec[5:], rem)

		frames = append(frames, rec)
	}

	return frames
}

// SafeSendUrgent sends data using TCP Urgent Pointer (MSG_OOB) to desynchronize stateful middleboxes.
func SafeSendUrgent(conn net.Conn, chunk []byte, decoyByte byte) (int, error) {
	if len(chunk) == 0 {
		return 0, nil
	}
	if tcpConn, ok := conn.(*net.TCPConn); ok {
		if decoyByte == 0 {
			decoyByte = PolyOOBByte()
		}

		combined := make([]byte, len(chunk)+1)
		copy(combined, chunk)
		combined[len(chunk)] = decoyByte

		sent, err := sendRawOOB(tcpConn, combined)
		if err == nil {
			if sent < len(chunk) {
				_, _ = tcpConn.Write(chunk[sent:])
			}
			return len(chunk), nil
		}
	}

	// Fallback or non-TCP connection (e.g. in-memory test pipe): write pure payload without injecting in-band decoy byte
	return conn.Write(chunk)
}

// TransmitUplinkShieldFrame applies OOB injection and MSS clamping on application data.
func TransmitUplinkShieldFrame(conn net.Conn, payload []byte, injectOOB bool, mssClamp int) (int, error) {
	if len(payload) == 0 {
		return 0, nil
	}
	totalSent := 0
	view := payload

	if injectOOB && len(view) > 20 && view[0] == 0x17 && view[1] == 0x03 {
		n, _ := SafeSendUrgent(conn, view[:3], 0)
		totalSent += n
		view = view[3:]
	}

	if mssClamp > 0 && len(view) > mssClamp {
		jitMSS := mssClamp + randRange(-24, 24)
		if jitMSS < 512 {
			jitMSS = 512
		}
		cursor := 0
		for cursor < len(view) {
			chunkSize := jitMSS
			if cursor+chunkSize > len(view) {
				chunkSize = len(view) - cursor
			}
			piece := view[cursor : cursor+chunkSize]
			n, err := conn.Write(piece)
			if err != nil {
				return totalSent + n, err
			}
			totalSent += n
			cursor += chunkSize
		}
	} else if len(view) > 0 {
		n, err := conn.Write(view)
		totalSent += n
		return totalSent, err
	}

	return totalSent, nil
}

// TransmitFragmentedBuffer writes buffer in small micro-slices with microsecond delays.
func TransmitFragmentedBuffer(conn net.Conn, payload []byte, minLen, maxLen, minDelayMs, maxDelayMs int) (int, error) {
	if len(payload) == 0 {
		return 0, nil
	}
	cursor := 0
	totalLen := len(payload)
	lo := minLen
	if lo < 1 {
		lo = 1
	}
	hi := maxLen
	if hi < lo {
		hi = lo
	}

	for cursor < totalLen {
		step := randRange(lo, hi)
		if cursor+step > totalLen {
			step = totalLen - cursor
		}
		chunk := payload[cursor : cursor+step]
		if _, err := conn.Write(chunk); err != nil {
			return cursor, err
		}
		cursor += len(chunk)

		if cursor < totalLen && maxDelayMs > 0 {
			delayMs := randRange(minDelayMs, maxDelayMs)
			if delayMs > 0 {
				time.Sleep(time.Duration(delayMs) * time.Millisecond)
			}
		}
	}
	return totalLen, nil
}

// TransmitTailSegments writes segments with forward cuts, jitter, and optional secondary urgent cut.
func TransmitTailSegments(conn net.Conn, tailBuf []byte, forwardCuts []int, jitterBytes, secondUrgentDelta int) (int, error) {
	if len(tailBuf) == 0 {
		return 0, nil
	}

	tailLen := len(tailBuf)
	if secondUrgentDelta > 0 {
		jitter := 0
		if jitterBytes > 0 {
			jitter = randRange(-jitterBytes, jitterBytes)
		}
		targetDelta := secondUrgentDelta + jitter
		if targetDelta < 24 {
			targetDelta = 24
		}
		cutPos := targetDelta
		if cutPos >= tailLen {
			cutPos = (tailLen * 2) / 3
		}
		if cutPos > 0 && cutPos < tailLen {
			sentU, err := SafeSendUrgent(conn, tailBuf[:cutPos], 0)
			if err != nil {
				return sentU, err
			}
			time.Sleep(1 * time.Millisecond)
			n, err2 := conn.Write(tailBuf[cutPos:])
			return sentU + n, err2
		}
	}

	if len(forwardCuts) == 0 {
		return conn.Write(tailBuf)
	}

	resolvedCuts := make([]int, 0, len(forwardCuts))
	for _, baseDelta := range forwardCuts {
		jitter := 0
		if baseDelta > 60 && jitterBytes > 0 {
			jitter = randRange(-jitterBytes, jitterBytes)
		}
		candidate := baseDelta + jitter
		if candidate < 20 {
			candidate = 20
		}
		if candidate > 0 && candidate < tailLen {
			resolvedCuts = append(resolvedCuts, candidate)
		} else if baseDelta > 0 && baseDelta < tailLen {
			resolvedCuts = append(resolvedCuts, baseDelta)
		} else if tailLen > 48 {
			resolvedCuts = append(resolvedCuts, (tailLen*7)/10)
		}
	}

	pointsMap := map[int]bool{0: true, tailLen: true}
	for _, pt := range resolvedCuts {
		pointsMap[pt] = true
	}
	points := make([]int, 0, len(pointsMap))
	for pt := range pointsMap {
		points = append(points, pt)
	}
	sort.Ints(points)

	sentBytes := 0
	for i := 0; i < len(points)-1; i++ {
		segment := tailBuf[points[i]:points[i+1]]
		if len(segment) > 0 {
			n, err := conn.Write(segment)
			sentBytes += n
			if err != nil {
				return sentBytes, err
			}
			if len(points) > 2 && i < len(points)-2 {
				time.Sleep(2 * time.Millisecond)
			}
		}
	}
	return sentBytes, nil
}

// TransmitSniDotFibonacci implements 7-record Fibonacci segmentation around SNI dot.
func TransmitSniDotFibonacci(conn net.Conn, hsBody []byte, offsets ClientHelloOffsets, jitterBytes int) (int, string, error) {
	sniStartRel := offsets.SNINamePos - 5
	if sniStartRel < 6 {
		sniStartRel = 6
	}
	dotRel := offsets.SNIFirstDotPos - 5
	if dotRel < sniStartRel+1 {
		dotRel = sniStartRel + 1
	}

	fibSteps := []int{1, 2, 3, 5, 8}
	dotAnchor := dotRel - 3
	if dotAnchor < 4 {
		dotAnchor = 4
	}

	var chunkPlan []int
	if dotAnchor > sniStartRel+2 {
		firstCut := sniStartRel + 2
		bridgeCut := dotAnchor - firstCut
		chunkPlan = append(chunkPlan, firstCut, bridgeCut)
	} else {
		chunkPlan = append(chunkPlan, dotAnchor)
	}
	chunkPlan = append(chunkPlan, fibSteps...)

	altVersions := []uint16{0x0301, 0x0303, 0x0303, 0x0301, 0x0303, 0x0303, 0x0301, 0x0303}
	frames := ReframeHandshakeChunks(hsBody, chunkPlan, 0x0303, altVersions)

	dotFrameIdx := 3
	if dotAnchor > sniStartRel+2 {
		dotFrameIdx = 4
	}
	if dotFrameIdx >= len(frames) {
		dotFrameIdx = len(frames) - 1
	}

	var flightA, flightB []byte
	for i, f := range frames {
		if i < dotFrameIdx {
			flightA = append(flightA, f...)
		} else {
			flightB = append(flightB, f...)
		}
	}

	if _, err := conn.Write(flightA); err != nil {
		return len(flightA), "", err
	}

	jit := 0
	if jitterBytes > 0 {
		jit = randRange(-jitterBytes, jitterBytes)
	}
	tailStride := 340 + jit
	if tailStride < 64 {
		tailStride = 64
	}

	if len(flightB) > tailStride+24 {
		if _, err := conn.Write(flightB[:tailStride]); err != nil {
			return len(flightA), "", err
		}
		time.Sleep(1500 * time.Microsecond)
		if _, err := conn.Write(flightB[tailStride:]); err != nil {
			return len(flightA) + tailStride, "", err
		}
	} else if len(flightB) > 0 {
		if _, err := conn.Write(flightB); err != nil {
			return len(flightA), "", err
		}
	}

	total := len(flightA) + len(flightB)
	return total, fmt.Sprintf("Coalesced-Sync(%dF) | %dB", len(frames), total), nil
}

// TransmitHybridVector implements composite vectors (Phantom Seam OOB, SNI Microcell Pincer, Chameleon Interleave).
func TransmitHybridVector(conn net.Conn, hsBody []byte, offsets ClientHelloOffsets, vector *BypassVector, customStride, jitterBytes int) (int, string, error) {
	bodyLen := len(hsBody)
	sniExtRel := offsets.SNIExtPos - 5
	if sniExtRel < 6 {
		sniExtRel = 6
	}
	sniNameRel := offsets.SNINamePos - 5
	if sniNameRel < 10 {
		sniNameRel = 10
	}
	sniLen := offsets.SNINameLen
	if sniLen < 4 {
		sniLen = 4
	}

	// 1. Phantom Seam OOB (VEC-21 - Core Omega)
	if vector.HybridMode == "phantom_seam_oob" {
		c1 := sniExtRel - 2
		if sniExtRel > 44 {
			c1 = sniExtRel - 4
		}
		if c1 < 8 {
			c1 = 8
		}
		if c1 > bodyLen-16 {
			c1 = bodyLen - 16
		}

		part1 := hsBody[:c1]
		part2 := hsBody[c1:]

		rec1 := make([]byte, 5+len(part1))
		rec1[0] = 0x16
		rec1[1] = 0x03
		rec1[2] = 0x01
		binary.BigEndian.PutUint16(rec1[3:5], uint16(len(part1)))
		copy(rec1[5:], part1)

		rec2 := make([]byte, 5+len(part2))
		rec2[0] = 0x16
		rec2[1] = 0x03
		rec2[2] = 0x04
		binary.BigEndian.PutUint16(rec2[3:5], uint16(len(part2)))
		copy(rec2[5:], part2)

		combined := append(rec1, rec2...)
		targetHost := sniNameRel + 10 + sniLen/3
		if targetHost >= len(combined)-4 {
			targetHost = len(combined) - 4
		}

		sentHead, err := SafeSendUrgent(conn, combined[:targetHost], 0)
		if err != nil {
			return sentHead, "", err
		}

		strideVal := customStride
		if strideVal <= 0 {
			strideVal = 400
		}
		sentTail, err2 := TransmitTailSegments(conn, combined[targetHost:], []int{strideVal}, jitterBytes, 0)
		total := sentHead + sentTail
		return total, fmt.Sprintf("Core-Omega | %dB", total), err2
	}

	// 2. SNI Microcell Pincer (VEC-22)
	if vector.HybridMode == "sni_microcell_pincer" {
		cellStart := sniNameRel + sniLen/4
		if cellStart < 8 {
			cellStart = 8
		}
		if cellStart > bodyLen-16 {
			cellStart = bodyLen - 16
		}

		cellLen := sniLen / 2
		if cellLen < 2 {
			cellLen = 2
		} else if cellLen > 5 {
			cellLen = 5
		}
		if cellStart+cellLen > bodyLen-6 {
			cellLen = 2
		}
		cellEnd := cellStart + cellLen

		p1 := hsBody[:cellStart]
		p2 := hsBody[cellStart:cellEnd]
		p3 := hsBody[cellEnd:]

		rec1 := make([]byte, 5+len(p1))
		rec1[0] = 0x16; rec1[1] = 0x03; rec1[2] = 0x04
		binary.BigEndian.PutUint16(rec1[3:5], uint16(len(p1)))
		copy(rec1[5:], p1)

		rec2 := make([]byte, 5+len(p2))
		rec2[0] = 0x16; rec2[1] = 0x03; rec2[2] = 0x01
		binary.BigEndian.PutUint16(rec2[3:5], uint16(len(p2)))
		copy(rec2[5:], p2)

		rec3 := make([]byte, 5+len(p3))
		rec3[0] = 0x16; rec3[1] = 0x03; rec3[2] = 0x04
		binary.BigEndian.PutUint16(rec3[3:5], uint16(len(p3)))
		copy(rec3[5:], p3)

		oob1Cut := offsets.SNIExtPos + 1
		if oob1Cut < 10 {
			oob1Cut = 10
		}
		if oob1Cut >= len(rec1)-2 {
			oob1Cut = len(rec1) - 2
		}

		sent1, err := SafeSendUrgent(conn, rec1[:oob1Cut], 0)
		if err != nil {
			return sent1, "", err
		}

		remStream := append(append(rec1[oob1Cut:], rec2...), rec3...)
		jit := 0
		if jitterBytes > 0 {
			jit = randRange(-jitterBytes, jitterBytes)
		}
		pincerStride := 360 + jit
		if pincerStride < 48 {
			pincerStride = 48
		}

		if len(remStream) > pincerStride+24 {
			sent2, err2 := SafeSendUrgent(conn, remStream[:pincerStride], 0)
			if err2 != nil {
				return sent1 + sent2, "", err2
			}
			time.Sleep(1500 * time.Microsecond)
			n, err3 := conn.Write(remStream[pincerStride:])
			total := sent1 + sent2 + n
			return total, fmt.Sprintf("Core-Window | %dB", total), err3
		}

		n, err2 := conn.Write(remStream)
		total := sent1 + n
		return total, fmt.Sprintf("Core-Window | %dB", total), err2
	}

	// 3. Chameleon Interleave (VEC-23)
	k1 := sniExtRel / 2
	if sniExtRel > 45 {
		k1 = sniExtRel - 2
		if k1 > 41 {
			k1 = 41
		}
	}
	if k1 < 8 {
		k1 = 8
	}

	k2 := k1 + 4
	if sniNameRel+2 > k2 {
		k2 = sniNameRel + 2
	}
	if k2 > bodyLen-20 {
		k2 = bodyLen - 20
	}

	span := sniLen / 3
	if span < 3 {
		span = 3
	} else if span > 6 {
		span = 6
	}
	k3 := k2 + span
	if k3 > bodyLen-14 {
		k3 = bodyLen - 14
	}

	k4 := k3 + span
	if k4 > bodyLen-8 {
		k4 = bodyLen - 8
	}

	sizes := []int{k1, k2 - k1, k3 - k2, k4 - k3}
	altVers := []uint16{0x0301, 0x0303, 0x0301, 0x0303, 0x0303}
	frames := ReframeHandshakeChunks(hsBody, sizes, 0x0303, altVers)

	var burst1, burst2 []byte
	for i, f := range frames {
		if i < 3 {
			burst1 = append(burst1, f...)
		} else {
			burst2 = append(burst2, f...)
		}
	}

	if _, err := conn.Write(burst1); err != nil {
		return len(burst1), "", err
	}

	jit := 0
	if jitterBytes > 0 {
		jit = randRange(-jitterBytes, jitterBytes)
	}
	stride380 := 380 + jit
	if stride380 < 40 {
		stride380 = 40
	}

	if len(burst2) > stride380+20 {
		if _, err := conn.Write(burst2[:stride380]); err != nil {
			return len(burst1), "", err
		}
		time.Sleep(1500 * time.Microsecond)
		if _, err := conn.Write(burst2[stride380:]); err != nil {
			return len(burst1) + stride380, "", err
		}
	} else if len(burst2) > 0 {
		if _, err := conn.Write(burst2); err != nil {
			return len(burst1), "", err
		}
	}

	total := len(burst1) + len(burst2)
	return total, fmt.Sprintf("Record-Sync | %dB", total), nil
}

// TransmitPlainHTTP desynchronizes HTTP plaintext headers (Host header slicing and OOB injection).
func TransmitPlainHTTP(conn net.Conn, httpPayload []byte, vector *BypassVector, customStride, jitterBytes int) (int, string, error) {
	if vector.Family == "DIRECT" || len(httpPayload) < 16 {
		n, err := conn.Write(httpPayload)
		return n, fmt.Sprintf("HTTP-Direct | %dB", n), err
	}

	if vector.Family == "FRAGMENT" && vector.FragSliceMax > 0 {
		n, err := TransmitFragmentedBuffer(conn, httpPayload, vector.FragSliceMin, vector.FragSliceMax, vector.FragDelayMinMs, vector.FragDelayMaxMs)
		return n, fmt.Sprintf("HTTP-Frag(%d-%dB)", vector.FragSliceMin, vector.FragSliceMax), err
	}

	lower := strings.ToLower(string(httpPayload))
	hostIdx := strings.Index(lower, "\r\nhost:")
	if hostIdx != -1 && hostIdx+10 < len(httpPayload) {
		valStart := hostIdx + 7
		for valStart < len(httpPayload) && (httpPayload[valStart] == ' ' || httpPayload[valStart] == '\t') {
			valStart++
		}
		cut := valStart + 3
		if cut > len(httpPayload)-2 {
			cut = len(httpPayload) - 2
		}

		usesOOB := vector.UrgentMode != "none" || vector.HybridMode == "phantom_seam_oob" || vector.HybridMode == "sni_microcell_pincer"
		if cut > 0 && usesOOB {
			sentU, err := SafeSendUrgent(conn, httpPayload[:cut], 0)
			if err != nil {
				return sentU, "", err
			}
			stride := customStride
			if stride <= 0 {
				stride = 400
			}
			rem, err2 := TransmitTailSegments(conn, httpPayload[cut:], []int{stride}, jitterBytes, 0)
			total := sentU + rem
			return total, fmt.Sprintf("HTTP-HostDesync | %dB", total), err2
		}

		if cut > 0 {
			if _, err := conn.Write(httpPayload[:cut]); err != nil {
				return 0, "", err
			}
			time.Sleep(1500 * time.Microsecond)
			n, err2 := conn.Write(httpPayload[cut:])
			return cut + n, fmt.Sprintf("HTTP-HostSplit | %dB", cut+n), err2
		}
	}

	n, err := conn.Write(httpPayload)
	return n, fmt.Sprintf("HTTP-Direct | %dB", n), err
}

// TransmitClientHello transmits a TLS ClientHello according to the specified BypassVector.
func TransmitClientHello(conn net.Conn, chRecord []byte, vector *BypassVector, customStride, jitterBytes int) (int, string, error) {
	if vector == nil || vector.Family == "DIRECT" || len(chRecord) < 16 {
		n, err := conn.Write(chRecord)
		return n, fmt.Sprintf("TLS-Direct | %dB", n), err
	}

	ver := PolyRecordVersion(vector.OuterVersion)
	offsets := InspectClientHello(chRecord)
	hsBody := chRecord[5:]

	if vector.HybridMode != "" && vector.HybridMode != "none" && len(hsBody) >= 48 {
		return TransmitHybridVector(conn, hsBody, offsets, vector, customStride, jitterBytes)
	}

	if vector.RecordEnvelope == "fibonacci_7rec" {
		return TransmitSniDotFibonacci(conn, hsBody, offsets, jitterBytes)
	}

	if vector.RecordEnvelope == "sni_dot_3rec" {
		sniStartRel := offsets.SNINamePos - 5
		if sniStartRel < 2 {
			sniStartRel = 2
		}
		dotRel := offsets.SNIFirstDotPos - 5
		if dotRel < sniStartRel+2 {
			dotRel = sniStartRel + 2
		}
		c1 := dotRel
		if c1 < 4 {
			c1 = 4
		}
		if c1 > len(hsBody)-8 {
			c1 = len(hsBody) - 8
		}
		midSpan := 3
		remSpan := len(hsBody) - c1 - midSpan
		if remSpan < 1 {
			remSpan = 1
		}
		frames := ReframeHandshakeChunks(hsBody, []int{c1, midSpan, remSpan}, 0x0303, []uint16{0x0301, 0x0303, 0x0303})
		var packed []byte
		for _, f := range frames {
			packed = append(packed, f...)
		}
		n, err := conn.Write(packed)
		return n, fmt.Sprintf("Aligned-Relay | %dB", n), err
	}

	if vector.RecordEnvelope == "sni_mid_2rec" {
		sniStartRel := offsets.SNINamePos - 5
		if sniStartRel < 2 {
			sniStartRel = 2
		}
		cutRel := sniStartRel + offsets.SNINameLen/2
		if cutRel < 4 {
			cutRel = 4
		}
		if cutRel > len(hsBody)-4 {
			cutRel = len(hsBody) - 4
		}
		frames := ReframeHandshakeChunks(hsBody, []int{cutRel}, 0x0303, []uint16{0x0301, 0x0303})
		var packed []byte
		for _, f := range frames {
			packed = append(packed, f...)
		}
		n, err := conn.Write(packed)
		return n, fmt.Sprintf("Stream-Balance | %dB", n), err
	}

	mutCH := RewriteOuterVersion(chRecord, ver)
	prefix := BuildPreambleBytes(vector.Preamble)

	if vector.UrgentMode == "cipher_pivot" && len(mutCH) > 96 {
		fullBuf := append(prefix, mutCH...)
		pivotCut := len(prefix) + offsets.CipherSuitesPos + 1
		if pivotCut < len(prefix)+44 {
			pivotCut = len(prefix) + 44
		}
		if pivotCut > len(fullBuf)-48 {
			pivotCut = len(fullBuf) - 48
		}

		anchor := vector.TailAnchorBytes
		if anchor <= 0 {
			anchor = 36
		}
		anchorJit := anchor + randRange(-8, 8)
		if anchorJit < 24 {
			anchorJit = 24
		}
		tailStart := len(fullBuf) - anchorJit
		if tailStart < pivotCut+8 {
			tailStart = pivotCut + 8
		}

		sent1, err := SafeSendUrgent(conn, fullBuf[:pivotCut], 0)
		if err != nil {
			return sent1, "", err
		}
		if _, err := conn.Write(fullBuf[pivotCut:tailStart]); err != nil {
			return sent1, "", err
		}
		_, err = conn.Write(fullBuf[tailStart:])
		total := sent1 + (len(fullBuf) - pivotCut)
		return total, fmt.Sprintf("Core-Delta | %dB", total), err
	}

	if vector.Family == "FRAGMENT" && vector.FragSliceMax > 0 {
		fullFragBuf := append(prefix, mutCH...)
		n, err := TransmitFragmentedBuffer(conn, fullFragBuf, vector.FragSliceMin, vector.FragSliceMax, vector.FragDelayMinMs, vector.FragDelayMaxMs)
		return n, fmt.Sprintf("Frag(%d-%dB) | %dB", vector.FragSliceMin, vector.FragSliceMax, n), err
	}

	cuts := append([]int(nil), vector.ForwardCuts...)
	if customStride > 0 && len(cuts) == 1 && cuts[0] == 400 {
		cuts = []int{customStride}
	}
	secUrg := vector.SecondUrgentDelta
	if customStride > 0 && secUrg > 0 {
		secUrg = customStride
	}

	if vector.SplitMode == "1pkt_midsni" {
		cut := offsets.SNINamePos + offsets.SNINameLen/2
		if cut < offsets.SNINamePos+3 {
			cut = offsets.SNINamePos + 3
		}
		splitPayload := append(prefix, SplitRecordAtomic(chRecord, cut, ver)...)
		n, err := TransmitTailSegments(conn, splitPayload, cuts, jitterBytes, secUrg)
		return n, fmt.Sprintf("AtomicSplit | %dB", n), err
	}

	fullBuf := append(prefix, mutCH...)
	shift := len(prefix)

	if vector.UrgentMode == "sni_tag" {
		cut := shift + offsets.SNIExtPos + 1
		if cut > len(fullBuf)-2 {
			cut = len(fullBuf) - 2
		}
		if cut > 0 {
			sU, err := SafeSendUrgent(conn, fullBuf[:cut], 0)
			if err != nil {
				return sU, "", err
			}
			rem, err2 := TransmitTailSegments(conn, fullBuf[cut:], cuts, jitterBytes, secUrg)
			total := sU + rem
			return total, fmt.Sprintf("Core-Alpha | %dB", total), err2
		}
	}

	if vector.UrgentMode == "mid_host" {
		cut := shift + offsets.SNINamePos + offsets.SNINameLen/2
		if cut < shift+offsets.SNINamePos+3 {
			cut = shift + offsets.SNINamePos + 3
		}
		if cut > len(fullBuf)-2 {
			cut = len(fullBuf) - 2
		}
		if cut > 0 {
			sU, err := SafeSendUrgent(conn, fullBuf[:cut], 0)
			if err != nil {
				return sU, "", err
			}
			rem, err2 := TransmitTailSegments(conn, fullBuf[cut:], cuts, jitterBytes, secUrg)
			total := sU + rem
			return total, fmt.Sprintf("Core-Sigma | %dB", total), err2
		}
	}

	if vector.UrgentMode == "sni_len" {
		cut := shift + offsets.SNIListLenPos + 1
		if cut > len(fullBuf)-2 {
			cut = len(fullBuf) - 2
		}
		if cut > 0 {
			sU, err := SafeSendUrgent(conn, fullBuf[:cut], 0)
			if err != nil {
				return sU, "", err
			}
			rem, err2 := TransmitTailSegments(conn, fullBuf[cut:], cuts, jitterBytes, secUrg)
			total := sU + rem
			return total, fmt.Sprintf("Core-Len | %dB", total), err2
		}
	}

	if vector.UrgentMode == "name_len" {
		cut := shift + offsets.SNINameLenPos + 1
		if cut > len(fullBuf)-2 {
			cut = len(fullBuf) - 2
		}
		if cut > 0 {
			sU, err := SafeSendUrgent(conn, fullBuf[:cut], 0)
			if err != nil {
				return sU, "", err
			}
			rem, err2 := TransmitTailSegments(conn, fullBuf[cut:], cuts, jitterBytes, secUrg)
			total := sU + rem
			return total, fmt.Sprintf("Core-NameLen | %dB", total), err2
		}
	}

	if vector.UrgentMode == "dual" {
		c1 := shift + offsets.SNIExtPos + 1
		c2 := shift + offsets.SNINamePos + 4
		if c2 > len(fullBuf)-2 {
			c2 = len(fullBuf) - 2
		}
		if c1 > 0 && c1 < c2 {
			u1, err := SafeSendUrgent(conn, fullBuf[:c1], 0)
			if err != nil {
				return u1, "", err
			}
			u2, err := SafeSendUrgent(conn, fullBuf[c1:c2], 0)
			if err != nil {
				return u1 + u2, "", err
			}
			rem, err := TransmitTailSegments(conn, fullBuf[c2:], cuts, jitterBytes, secUrg)
			total := u1 + u2 + rem
			return total, fmt.Sprintf("Core-Dual | %dB", total), err
		}
	}

	if vector.UrgentMode == "triple" {
		c0 := shift + offsets.ExtTotalLenPos + 1
		c1 := shift + offsets.SNIExtPos + 1
		c2 := shift + offsets.SNINamePos + 4
		if c2 > len(fullBuf)-2 {
			c2 = len(fullBuf) - 2
		}
		if c0 > 0 && c0 < c1 && c1 < c2 {
			u0, err := SafeSendUrgent(conn, fullBuf[:c0], 0)
			if err != nil {
				return u0, "", err
			}
			u1, err := SafeSendUrgent(conn, fullBuf[c0:c1], 0)
			if err != nil {
				return u0 + u1, "", err
			}
			u2, err := SafeSendUrgent(conn, fullBuf[c1:c2], 0)
			if err != nil {
				return u0 + u1 + u2, "", err
			}
			rem, err := TransmitTailSegments(conn, fullBuf[c2:], cuts, jitterBytes, secUrg)
			total := u0 + u1 + u2 + rem
			return total, fmt.Sprintf("Core-Tri | %dB", total), err
		}
	}

	sent, err := TransmitTailSegments(conn, fullBuf, cuts, jitterBytes, secUrg)
	return sent, fmt.Sprintf("Coalesced | %dB", sent), err
}

func randRange(min, max int) int {
	if max <= min {
		return min
	}
	delta := max - min + 1
	n, _ := rand.Int(rand.Reader, big.NewInt(int64(delta)))
	return min + int(n.Int64())
}
