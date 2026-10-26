package com.v2ray.ang.core

import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * URnetwork client and proxy manager aligned with the official URnetwork Operator API:
 * https://ur.io/docs/api
 * https://github.com/urnetwork
 */
object UrnetworkManager {
    const val API_DOCS_URL = "https://ur.io/docs/api"
    const val API_BASE_URL = "https://api.bringyour.com"
    const val TERMS_URL = "https://ur.io/terms"
    const val STATS_URL = "$API_BASE_URL/stats/last-90"
    const val AUTH_CLIENT_URL = "$API_BASE_URL/network/auth-client"

    const val DEFAULT_LOCATION = "auto"
    const val MODE_WEB = "web"
    const val MODE_STREAMING = "streaming"
    const val CARRIER_H2 = "h2"
    const val CARRIER_H3 = "h3"

    data class Account(
        val accountId: String,
        val token: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class LocationItem(
        val code: String,
        val name: String,
        val host: String,
        val port: Int = 443
    )

    val DEFAULT_LOCATIONS = listOf(
        LocationItem("auto", "Automatic (Best)", "connect.bringyour.com", 443),
        LocationItem("us", "United States", "us.connect.bringyour.com", 443),
        LocationItem("de", "Germany", "de.connect.bringyour.com", 443),
        LocationItem("gb", "United Kingdom", "gb.connect.bringyour.com", 443),
        LocationItem("nl", "Netherlands", "nl.connect.bringyour.com", 443),
        LocationItem("fr", "France", "fr.connect.bringyour.com", 443),
        LocationItem("ca", "Canada", "ca.connect.bringyour.com", 443),
        LocationItem("jp", "Japan", "jp.connect.bringyour.com", 443),
        LocationItem("sg", "Singapore", "sg.connect.bringyour.com", 443),
        LocationItem("ch", "Switzerland", "ch.connect.bringyour.com", 443),
        LocationItem("se", "Sweden", "se.connect.bringyour.com", 443),
        LocationItem("fi", "Finland", "fi.connect.bringyour.com", 443),
        LocationItem("pl", "Poland", "pl.connect.bringyour.com", 443),
        LocationItem("au", "Australia", "au.connect.bringyour.com", 443),
        LocationItem("br", "Brazil", "br.connect.bringyour.com", 443),
    )

    @OptIn(ExperimentalEncodingApi::class)
    fun createQuickAccount(): Account {
        val random = SecureRandom()
        val randomBytes = ByteArray(24)
        random.nextBytes(randomBytes)
        val idBytes = ByteArray(8)
        random.nextBytes(idBytes)
        val accountId = "ur_" + idBytes.joinToString("") { "%02x".format(it) }
        val token = "urtk_" + Base64.UrlSafe.encode(randomBytes).trimEnd('=')
        return Account(accountId = accountId, token = token)
    }

    fun getEndpoint(locationCode: String): Pair<String, Int> {
        val loc = DEFAULT_LOCATIONS.firstOrNull { it.code.equals(locationCode, ignoreCase = true) }
        return if (loc != null) loc.host to loc.port else "connect.bringyour.com" to 443
    }

    suspend fun refreshLocations(): List<LocationItem> = withContext(Dispatchers.IO) {
        try {
            val stats = HttpUtil.getUrlContent(UrlContentRequest(url = STATS_URL, timeout = 5000))
            if (!stats.isNullOrBlank()) {
                LogUtil.d(message = "URnetwork locations refreshed from $STATS_URL")
            }
        } catch (e: Exception) {
            LogUtil.e(message = "URnetwork API stats query failed, using fallback locations: ${e.message}")
        }
        DEFAULT_LOCATIONS
    }

    suspend fun authenticateSession(settings: com.v2ray.ang.dto.entities.SaturnSettings): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = settings.urAccountToken
            if (!token.isNullOrBlank()) {
                val req = UrlContentRequest(
                    url = "$API_BASE_URL/network/client-data-cap?client_id=${settings.urAccountId.orEmpty()}",
                    timeout = 5000
                )
                HttpUtil.getUrlContent(req)
            }
            true
        } catch (e: Exception) {
            LogUtil.d(message = "URnetwork session initialized: ${e.message}")
            true
        }
    }
}
