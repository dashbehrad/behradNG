package com.v2ray.ang.core

import okhttp3.Dns
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class SaturnDohTransportTest {
    private val addresses = listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7)))

    @Test fun successfulStandardHttpsDoesNotTryFragmentation() {
        val standard = resolver { host -> assertEquals("bridge.example", host); addresses }
        val fallback = resolver { error("Fragmented transport must not run") }
        assertEquals(addresses, SaturnDohProxy.lookupWithTlsFallback("bridge.example", standard, fallback, { false }) {
            error("Successful standard transport must not report failure")
        })
    }

    @Test fun wrappedSocketTimeoutAndSuppressedResetRetryTheSameHostname() {
        val timeout = UnknownHostException("DoH lookup failed").apply { initCause(SocketTimeoutException("Read timed out")) }
        val reset = UnknownHostException("DoH lookup failed").apply { addSuppressed(SocketException("Connection reset")) }
        for (failure in listOf(timeout, reset)) {
            var retries = 0
            var reports = 0
            val standard = resolver { throw failure }
            val fallback = resolver { host -> assertEquals("bridge.example", host); retries++; addresses }
            assertEquals(addresses, SaturnDohProxy.lookupWithTlsFallback("bridge.example", standard, fallback, { false }) {
                assertTrue(it === failure); reports++
            })
            assertEquals(1, retries)
            assertEquals(1, reports)
        }
    }

    @Test fun httpDnsAndCertificateFailuresDoNotTriggerAnotherTlsAttempt() {
        val certificateFailure = SSLHandshakeException("Certificate rejected").apply {
            initCause(CertificateException("Untrusted certificate"))
            addSuppressed(SocketException("Connection reset"))
        }
        for (failure in listOf(IOException("DoH HTTP 429"), UnknownHostException("NXDOMAIN"),
            SSLPeerUnverifiedException("Hostname mismatch"), certificateFailure)) {
            val standard = resolver { throw failure }
            val fallback = resolver { error("Must not retry non-transport errors") }
            val error = assertThrows(IOException::class.java) {
                SaturnDohProxy.lookupWithTlsFallback("bridge.example", standard, fallback, { false }) {
                    error("Must not report a TLS retry")
                }
            }
            assertTrue(error === failure)
        }
    }

    @Test fun stoppedSessionsNeverStartOrRetryDnsRequests() {
        val unused = resolver { error("Stopped resolver must not run") }
        assertThrows(IOException::class.java) {
            SaturnDohProxy.lookupWithTlsFallback("bridge.example", unused, unused, { true }) { error("No retry") }
        }
        var stopped = false
        val standard = resolver { stopped = true; throw SocketException("Socket closed") }
        assertThrows(SocketException::class.java) {
            SaturnDohProxy.lookupWithTlsFallback("bridge.example", standard, unused, { stopped }) { error("No retry") }
        }
        stopped = false
        val failed = resolver { throw SocketException("Connection reset") }
        assertThrows(SocketException::class.java) {
            SaturnDohProxy.lookupWithTlsFallback("bridge.example", failed, unused, { stopped }) { stopped = true }
        }
    }

    @Test fun failedFallbackPreservesBothTransportErrors() {
        val first = SocketException("Connection reset")
        val second = SocketTimeoutException("Read timed out")
        val error = assertThrows(SocketTimeoutException::class.java) {
            SaturnDohProxy.lookupWithTlsFallback("bridge.example", resolver { throw first }, resolver { throw second }, { false }) { }
        }
        assertTrue(error === second)
        assertTrue(error.suppressed.single() === first)
    }

    @Test fun interruptedLookupsDoNotStartAnotherTransport() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(SocketException::class.java) {
                SaturnDohProxy.lookupWithTlsFallback("bridge.example", resolver { throw SocketException("Socket closed") },
                    resolver { error("Must not retry an interrupted lookup") }, { false }) { error("No retry") }
            }
        } finally { Thread.interrupted() }
    }

    @Test fun cyclicFailureCausesDoNotBlockTransportFallback() {
        val failure = UnknownHostException("DoH lookup failed")
        val reset = SocketException("Connection reset")
        failure.addSuppressed(reset)
        reset.addSuppressed(failure)
        assertEquals(addresses, SaturnDohProxy.lookupWithTlsFallback("bridge.example", resolver { throw failure },
            resolver { addresses }, { false }) { })
    }

    private fun resolver(lookup: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = lookup.invoke(hostname)
    }
}
