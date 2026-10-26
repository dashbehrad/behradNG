package com.v2ray.ang.core

import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object UrnetworkManager {
    const val TERMS_URL = "https://ur.io/terms"
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
        LocationItem("auto", "Automatic (Best)", "ext-auto.ur.io", 443),
        LocationItem("us", "United States", "ext-us.ur.io", 443),
        LocationItem("de", "Germany", "ext-de.ur.io", 443),
        LocationItem("gb", "United Kingdom", "ext-gb.ur.io", 443),
        LocationItem("nl", "Netherlands", "ext-nl.ur.io", 443),
        LocationItem("fr", "France", "ext-fr.ur.io", 443),
        LocationItem("ca", "Canada", "ext-ca.ur.io", 443),
        LocationItem("jp", "Japan", "ext-jp.ur.io", 443),
        LocationItem("sg", "Singapore", "ext-sg.ur.io", 443),
        LocationItem("ch", "Switzerland", "ext-ch.ur.io", 443),
        LocationItem("se", "Sweden", "ext-se.ur.io", 443),
        LocationItem("fi", "Finland", "ext-fi.ur.io", 443),
        LocationItem("pl", "Poland", "ext-pl.ur.io", 443),
        LocationItem("au", "Australia", "ext-au.ur.io", 443),
        LocationItem("br", "Brazil", "ext-br.ur.io", 443),
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
        return if (loc != null) loc.host to loc.port else "ext-auto.ur.io" to 443
    }

    suspend fun refreshLocations(): List<LocationItem> = withContext(Dispatchers.IO) {
        DEFAULT_LOCATIONS
    }
}
