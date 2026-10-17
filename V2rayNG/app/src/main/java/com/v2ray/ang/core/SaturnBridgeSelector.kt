package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.SaturnSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress

/** Resolve a bounded, trusted bridge pool; Tor owns transport handshakes and selection. */
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

    suspend fun resolveCandidates(lines: List<String>, resolve: suspend (String) -> List<InetAddress>): List<String> =
        coroutineScope {
            val permits = Semaphore(12)
            lines.distinct().take(64).map { bridge -> async {
                permits.withPermit {
                    val parts = bridge.split(Regex("\\s+"))
                    val endpoint = parts[1]
                    val host = endpoint.substringBeforeLast(':').removeSurrounding("[", "]")
                    val port = endpoint.substringAfterLast(':')
                    val addresses = try {
                        if (SaturnSettings.isIpLiteral(host)) listOf(InetAddress.getByName(host)) else resolve(host).take(2)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    addresses.map { address ->
                        val ip = address.hostAddress!!.substringBefore('%')
                        listOf(parts[0], (if (ip.contains(':')) "[$ip]" else ip) + ":$port")
                            .plus(parts.drop(2)).joinToString(" ")
                    }
                }
            } }.awaitAll().flatten()
        }

}

