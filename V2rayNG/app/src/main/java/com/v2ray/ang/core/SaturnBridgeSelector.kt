package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.SaturnSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Rank a bounded, trusted bridge pool; TCP reachability is not an obfs4 handshake. */
internal object SaturnBridgeSelector {
    fun parseBuiltins(json: String): List<String> {
        require(json.length <= 65536)
        val lines = JsonParser.parseString(json).asJsonObject.getAsJsonArray("obfs4")
        require(lines != null && lines.size() <= 64)
        return lines.mapNotNull { value ->
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) null
            else value.asString.trim().removePrefix("Bridge ").takeIf(SaturnSettings::validBridge)
        }.distinct()
    }

    fun candidates(settings: SaturnSettings, bundled: List<String>, refreshed: List<String>): List<String> =
        if (settings.usesAutomaticBridges()) (refreshed + bundled).filter(SaturnSettings::validBridge).distinct().take(64)
        else settings.bridgeLines()

    suspend fun select(lines: List<String>, mode: String, probe: suspend (String, Int) -> Long?): List<String> = coroutineScope {
        require(mode in setOf("turbo", "deep"))
        val candidates = lines.distinct().take(64)
        val permits = Semaphore(if (mode == "turbo") 3 else 4)
        candidates.map { bridge -> async {
            permits.withPermit { probe(bridge, if (mode == "turbo") 1500 else 5000)?.let { bridge to it } }
        } }.awaitAll().filterNotNull().sortedBy { it.second }
            .map { it.first }
    }
}
