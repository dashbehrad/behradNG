package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class SaturnDohFragmentationTest {
    @Test fun clientHelloSpansValidRecordsWithoutChangingTheHandshake() {
        val hostname = "cloudflare-dns.com"
        val hello = clientHello(hostname)
        val output = ByteArrayOutputStream()
        FragmentingOutputStream(output).apply { write(hello); flush() }
        val records = payloads(output.toByteArray())
        assertTrue(records.size > 1)
        assertEquals(hello.drop(5), records.flatMap { it.toList() })
        assertTrue(records.none { String(it, Charsets.US_ASCII).contains(hostname) })
    }

    @Test fun partialWritesAndFlushesDoNotReleaseAnUnfragmentedHello() {
        val hello = clientHello("dns.google")
        val output = ByteArrayOutputStream()
        val stream = FragmentingOutputStream(output)
        for (index in 0 until hello.lastIndex) {
            stream.write(hello[index].toInt() and 0xff)
            stream.flush()
            assertEquals(0, output.size())
        }
        stream.write(hello.last().toInt() and 0xff)
        stream.flush()
        assertEquals(hello.drop(5), payloads(output.toByteArray()).flatMap { it.toList() })
    }

    @Test fun legacyRecordVersionAndLargeClientHelloArePreserved() {
        val hello = clientHello("dns.google", 1024).apply { this[2] = 1 }
        val output = ByteArrayOutputStream()
        FragmentingOutputStream(output).apply { write(hello); flush() }
        val records = payloads(output.toByteArray(), 1)
        assertTrue(records.any { it.size > 255 })
        assertEquals(hello.drop(5), records.flatMap { it.toList() })
    }

    @Test fun laterRecordsAndSubsequentWritesAreForwardedUnchanged() {
        val hello = clientHello("dns.google")
        val following = byteArrayOf(20, 3, 3, 0, 1, 1)
        val later = byteArrayOf(23, 3, 3, 0, 2, 42, 43)
        val output = ByteArrayOutputStream()
        val stream = FragmentingOutputStream(output)
        stream.write(hello + following)
        val afterHello = output.size() - following.size
        assertEquals(following.toList(), output.toByteArray().drop(afterHello))
        assertEquals(hello.drop(5), payloads(output.toByteArray().copyOf(afterHello)).flatMap { it.toList() })
        stream.write(later)
        stream.flush()
        assertEquals(later.toList(), output.toByteArray().takeLast(later.size))
    }

    @Test fun absentAndMalformedSniKeepTheHandshakeIntact() {
        val malformed = clientHello("dns.google")
        val hostnameStart = String(malformed, Charsets.ISO_8859_1).indexOf("dns.google")
        malformed[hostnameStart - 2] = 0xff.toByte()
        malformed[hostnameStart - 1] = 0xff.toByte()
        for (hello in listOf(clientHello(null), malformed)) {
            val output = ByteArrayOutputStream()
            FragmentingOutputStream(output).apply { write(hello); flush() }
            val records = payloads(output.toByteArray())
            assertTrue(records.size > 1)
            assertEquals(hello.drop(5), records.flatMap { it.toList() })
        }
    }

    @Test fun otherTrafficAndInvalidTlsHeadersArePassedThroughUnchanged() {
        val samples = listOf("plain data".toByteArray(), byteArrayOf(23, 3, 3, 0, 1, 42),
            byteArrayOf(22, 3, 3, 0, 4, 2, 0, 0, 0), byteArrayOf(22, 3, 3, -1, -1, 1))
        for (sample in samples) {
            val output = ByteArrayOutputStream()
            FragmentingOutputStream(output).apply { write(sample); flush(); close() }
            assertEquals(sample.toList(), output.toByteArray().toList())
        }
    }

    @Test fun closingAnIncompleteTlsRecordDoesNotSendAPartialHandshake() {
        val output = ByteArrayOutputStream()
        FragmentingOutputStream(output).apply { write(clientHello().copyOf(12)); flush(); close() }
        assertEquals(0, output.size())
    }

    @Test fun invalidRangesDoNotConsumeTheFirstHandshake() {
        val output = ByteArrayOutputStream()
        val stream = FragmentingOutputStream(output)
        assertThrows(IndexOutOfBoundsException::class.java) { stream.write(byteArrayOf(1), -1, 1) }
        assertThrows(IndexOutOfBoundsException::class.java) { stream.write(byteArrayOf(1), 0, 2) }
        val hello = clientHello()
        stream.write(hello)
        assertEquals(hello.drop(5), payloads(output.toByteArray()).flatMap { it.toList() })
    }

    private fun clientHello(hostname: String? = "dns.google", paddingSize: Int = 32): ByteArray {
        fun size(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
        val extensions = ByteArrayOutputStream()
        extensions.write(byteArrayOf(0, 43, 0, 3, 2, 3, 4))
        if (hostname != null) {
            val name = hostname.toByteArray(Charsets.US_ASCII)
            val names = byteArrayOf(0) + size(name.size) + name
            val sni = size(names.size) + names
            extensions.write(byteArrayOf(0, 0) + size(sni.size) + sni)
        }
        extensions.write(byteArrayOf(0, 21) + size(paddingSize) + ByteArray(paddingSize))
        val body = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(3, 1, 2, 3,
            0, 2, 0x13, 1, 1, 0) + size(extensions.size()) + extensions.toByteArray()
        val payload = byteArrayOf(1, (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        return byteArrayOf(22, 3, 3) + size(payload.size) + payload
    }

    private fun payloads(wire: ByteArray, versionMinor: Byte = 3): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < wire.size) {
            assertTrue(offset + 5 <= wire.size)
            assertEquals(listOf<Byte>(22, 3, versionMinor), wire.slice(offset until offset + 3))
            val length = ((wire[offset + 3].toInt() and 0xff) shl 8) or (wire[offset + 4].toInt() and 0xff)
            assertTrue(length > 0 && offset + 5 + length <= wire.size)
            result.add(wire.copyOfRange(offset + 5, offset + 5 + length))
            offset += 5 + length
        }
        assertTrue(result.isNotEmpty())
        return result
    }
}
