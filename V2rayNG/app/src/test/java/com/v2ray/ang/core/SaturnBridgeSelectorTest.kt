package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SaturnBridgeSelectorTest {
    @Test fun turboBoundsCandidatesAndRanksOnlyReachableResults() = runBlocking {
        val checked = mutableListOf<String>()
        val picked = SaturnBridgeSelector.select((1..30).map { "bridge-$it" }, "turbo") { line, timeout ->
            assertEquals(1500, timeout); checked.add(line)
            if (line == "bridge-1") null else 100L - line.substringAfter('-').toLong()
        }
        assertEquals(12, checked.size)
        assertEquals(listOf("bridge-12", "bridge-11", "bridge-10"), picked)
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
}
