package com.v2ray.ang.handler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class SaturnConnectionProbeTest {
    @Test
    fun blockedPingEndpointsDoNotPreventIndependentIpDetection(): Unit = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val failures = mutableListOf<String>()
        val result = SpeedtestManager.runSaturnProbes(listOf("primary", "fallback"), "ip",
            request = { url, readBody ->
                calls += url to readBody
                if (url != "ip") throw IOException("Timed out")
                """{"ip":"192.0.2.1","country_code":"DE"}"""
            }, reportFailure = { operation, _ -> failures += operation })
        assertEquals(-1L, result.delayMillis)
        assertEquals("192.0.2.1", result.ipAddress)
        assertEquals("DE", result.country)
        assertEquals(listOf("primary" to false, "fallback" to false, "ip" to true), calls)
        assertEquals(listOf("delay-0", "delay-1"), failures)
    }

    @Test
    fun workingFallbackAndUnavailableIpServiceKeepTheSuccessfulLatency(): Unit = runBlocking {
        val result = SpeedtestManager.runSaturnProbes(listOf("primary", "fallback"), "ip",
            request = { url, _ -> if (url != "fallback") throw IOException("Unavailable") else "" },
            reportFailure = { _, _ -> })
        assertTrue(result.delayMillis >= 0)
        assertNull(result.ipAddress)
    }

    @Test
    fun successfulPrimarySkipsFallbackAndParsesAlternateIpFormat(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val result = SpeedtestManager.runSaturnProbes(listOf("primary", "fallback"), "ip",
            request = { url, _ ->
                calls += url
                if (url == "ip") """{"query":"192.0.2.2","countryCode":"NL"}""" else ""
            }, reportFailure = { _, _ -> error("Unexpected failure") })
        assertTrue(result.delayMillis >= 0)
        assertEquals(listOf("primary", "ip"), calls)
        assertEquals("192.0.2.2", result.ipAddress)
        assertEquals("NL", result.country)
    }

    @Test
    fun invalidUrlsAndEmptyIpResultsDoNotReportAnEndpoint(): Unit = runBlocking {
        val failures = mutableListOf<String>()
        val result = SpeedtestManager.runSaturnProbes(listOf("invalid", "invalid"), "ip",
            request = { url, _ -> if (url != "ip") throw IllegalArgumentException("Bad URL") else "{}" },
            reportFailure = { operation, _ -> failures += operation })
        assertEquals(-1L, result.delayMillis)
        assertNull(result.ipAddress)
        assertEquals(listOf("delay-0", "ip-info"), failures)
    }

    @Test
    fun ownerCancellationStopsBeforeFallbackOrIpRequests(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                SpeedtestManager.runSaturnProbes(listOf("primary", "fallback"), "ip",
                    request = { url, _ -> calls += url; throw CancellationException("stop") },
                    reportFailure = { _, _ -> error("Cancellation is not a probe failure") })
            }
        }
        assertEquals(listOf("primary"), calls)
    }
}
