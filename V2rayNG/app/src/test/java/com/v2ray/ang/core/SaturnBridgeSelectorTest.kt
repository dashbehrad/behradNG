package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
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

    @Test fun turboTestsTheWholePoolAndRanksOnlyReachableResults(): Unit = runBlocking {
        val checked = mutableListOf<String>()
        val picked = SaturnBridgeSelector.select((1..30).map { "bridge-$it" }, "turbo") { line, timeout ->
            assertEquals(1500, timeout); checked.add(line)
            if (line == "bridge-1") null else 100L - line.substringAfter('-').toLong()
        }
        assertEquals(30, checked.size)
        assertEquals((30 downTo 2).map { "bridge-$it" }, picked)
    }
    @Test fun deepTestsMoreCandidatesButNeverInventsAWorkingBridge() = runBlocking {
        var checked = 0
        val picked = SaturnBridgeSelector.select((1..80).map { "bridge-$it" }, "deep") { _, timeout ->
            assertEquals(5000, timeout); checked++; null
        }
        assertEquals(64, checked)
        assertEquals(emptyList<String>(), picked)
        assertEquals(emptyList<String>(), SaturnBridgeSelector.select(emptyList(), "turbo") { _, _ -> error("not called") })
    }
    @Test fun cancellationPropagatesInsteadOfChoosingAFallback() {
        assertThrows(CancellationException::class.java) {
            runBlocking { SaturnBridgeSelector.select(listOf("bridge"), "turbo") { _, _ -> throw CancellationException("stop") } }
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
