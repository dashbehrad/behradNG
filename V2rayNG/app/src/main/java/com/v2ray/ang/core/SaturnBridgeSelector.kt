package com.v2ray.ang.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Probe only the user's supplied bridges; TCP reachability is not an obfs4 handshake. */
internal object SaturnBridgeSelector {
    suspend fun select(lines: List<String>, mode: String, probe: suspend (String, Int) -> Long?): List<String> = coroutineScope {
        require(mode in setOf("turbo", "deep"))
        val candidates = lines.distinct().take(if (mode == "turbo") 12 else 64)
        val permits = Semaphore(if (mode == "turbo") 3 else 4)
        candidates.map { bridge -> async {
            permits.withPermit { probe(bridge, if (mode == "turbo") 1500 else 5000)?.let { bridge to it } }
        } }.awaitAll().filterNotNull().sortedBy { it.second }
            .take(if (mode == "turbo") 3 else 8).map { it.first }
    }
}
