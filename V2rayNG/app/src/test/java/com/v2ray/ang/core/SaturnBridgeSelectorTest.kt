package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import com.google.gson.Gson
import com.v2ray.ang.dto.entities.SaturnSettings

class SaturnBridgeSelectorTest {
    @Test fun unavailableDohSkipsHostnameBridgeButKeepsIpLiteralBridge(): Unit = runBlocking {
        val ipBridge = "obfs4 192.0.2.10:443 " + "A".repeat(40) + " cert=" + "B".repeat(70) + " iat-mode=0"
        val dnsBridge = "obfs4 bridge.example:443 " + "C".repeat(40) + " cert=" + "D".repeat(70) + " iat-mode=0"
        val resolvedHosts = mutableListOf<String>()

        val result = SaturnBridgeSelector.resolveCandidates(listOf(ipBridge, dnsBridge)) { host ->
            resolvedHosts += host
            throw javax.net.ssl.SSLException("DoH TLS unavailable")
        }

        assertEquals(listOf("bridge.example"), resolvedHosts)
        assertEquals(1, result.size)
        assertTrue(result.single().startsWith("obfs4 192.0.2.10:443 "))
    }

    @Test fun bestSpeedPolicyTestsPoolConcurrentlyAndRanksReachableResults(): Unit = runBlocking {
        val checked = mutableListOf<String>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val picked = SaturnBridgeSelector.selectBestSpeed((1..30).map { "bridge-$it" }) { line, timeout ->
            assertEquals(1800, timeout); synchronized(checked) { checked.add(line) }
            val nowActive = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, nowActive) }
            delay(10)
            active.decrementAndGet()
            if (line == "bridge-1") null else 100L - line.substringAfter('-').toLong()
        }
        assertEquals(30, checked.size)
        assertTrue(maxActive.get() in 2..12)
        assertEquals((30 downTo 28).map { "bridge-$it" }, picked)
    }
    @Test fun bestSpeedPolicyBoundsCandidatesAndNeverInventsAWorkingBridge() = runBlocking {
        val checked = AtomicInteger()
        val picked = SaturnBridgeSelector.selectBestSpeed((1..80).map { "bridge-$it" }) { _, timeout ->
            assertEquals(1800, timeout); checked.incrementAndGet(); null
        }
        assertEquals(64, checked.get())
        assertEquals(emptyList<String>(), picked)
        assertEquals(emptyList<String>(), SaturnBridgeSelector.selectBestSpeed(emptyList()) { _, _ -> error("not called") })
    }
    @Test fun cancellationPropagatesInsteadOfChoosingAFallback() {
        assertThrows(CancellationException::class.java) {
            runBlocking { SaturnBridgeSelector.selectBestSpeed(listOf("bridge")) { _, _ -> throw CancellationException("stop") } }
        }
    }

    @Test fun automaticPoolValidatesDeduplicatesAndRetainsBundledFallback() {
        fun bridge(ip: String) = "obfs4 $ip:443 " + "A".repeat(40) + " cert=" + "B".repeat(70) + " iat-mode=0"
        val bundled = bridge("192.0.2.1")
        val fresh = bridge("192.0.2.2")
        val json = Gson().toJson(mapOf("obfs4" to listOf(fresh, fresh, "invalid")))
        assertEquals(listOf(fresh), SaturnBridgeSelector.parseBuiltins(json))
        assertEquals(listOf(fresh, bundled), SaturnBridgeSelector.candidates(SaturnSettings(), listOf(bundled), listOf(fresh)))
        assertEquals(listOf(bundled), SaturnBridgeSelector.candidates(SaturnSettings(), listOf(bundled), emptyList()))
        assertEquals(emptyList<String>(), SaturnBridgeSelector.parseBuiltins("{\"obfs4\":[]}"))
        assertThrows(IllegalArgumentException::class.java) { SaturnBridgeSelector.parseBuiltins("x".repeat(65537)) }
        assertThrows(IllegalArgumentException::class.java) {
            SaturnBridgeSelector.parseBuiltins(Gson().toJson(mapOf("obfs4" to List(65) { fresh })))
        }
        val custom = SaturnSettings(bridges = bundled)
        assertEquals(listOf(bundled), SaturnBridgeSelector.candidates(custom, emptyList(), listOf(fresh)))
    }
}

