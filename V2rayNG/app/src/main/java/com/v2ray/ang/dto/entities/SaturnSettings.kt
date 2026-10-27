package com.v2ray.ang.dto.entities

import java.net.URI

/** Saturn owns Aether (Cloudflare Tunnel) and URnetwork settings. */
data class SaturnSettings(
    val aetherProtocol: String = "masque",
    val aetherCarrier: String = "h2",
    val urAccountId: String? = null,
    val urAccountToken: String? = null,
    val urTermsAgreed: Boolean = false,
    val urMode: String = "web",
    val urCarrier: String = "h2",
    val urLocation: String = "auto",
    // Retain legacy fields for backwards compatibility with previously saved Saturn profiles
    val dohUrl: String = "https://cloudflare-dns.com/dns-query",
    val bootstrapIps: String = "1.1.1.1, 1.0.0.1",
    val bridges: String = "",
    val scanMode: String = "balanced",
    val country: String = "auto",
    val bridgeSource: String? = null,
    val carrier: String? = "h2",
) {
    fun hasUrAccount(): Boolean = !urAccountId.isNullOrBlank() && !urAccountToken.isNullOrBlank()

    fun normalizedAetherProtocol(): String = when (aetherProtocol.lowercase().trim()) {
        "wg", "wireguard" -> "wg"
        else -> "masque"
    }

    fun normalizedAetherCarrier(): String = when (aetherCarrier.lowercase().trim()) {
        "h3", "http3", "http/3" -> "h3"
        else -> "h2"
    }

    fun normalizedUrMode(): String = when (urMode.lowercase().trim()) {
        "streaming" -> "streaming"
        else -> "web"
    }

    fun normalizedUrCarrier(): String = when (urCarrier.lowercase().trim()) {
        "h3", "http3", "http/3" -> "h3"
        else -> "h2"
    }

    fun usesAutomaticBridges(): Boolean = bridgeSource == "auto" || (bridgeSource == null && bridges.isBlank())

    fun usesDirectConnection(): Boolean = bridgeSource == "direct" || (!usesAutomaticBridges() && bridges.trim().equals("DIRECT", ignoreCase = true))

    fun normalizedCarrier(): String = when (carrier?.lowercase()?.trim()) {
        "h3", "http3", "http/3" -> "h3"
        else -> normalizedAetherCarrier()
    }

    fun bridgeLines(): List<String> = bridges.lineSequence().map { it.trim().removePrefix("Bridge ") }
        .filter { it.isNotEmpty() }.distinct().toList()

    fun isValid(): Boolean = runCatching {
        if (dohUrl.isNotBlank()) {
            val uri = URI(dohUrl)
            require(dohUrl.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrEmpty())
            require(uri.userInfo == null && uri.fragment == null && uri.port in listOf(-1, 443))
        }
        val ips = bootstrapIps.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(ips.size <= 8 && ips.all(::isIpLiteral))
        require(scanMode in setOf("turbo", "deep", "balanced"))
        require(country == "auto" || country.matches(Regex("[a-z]{2}")))
        require(bridges.length <= 32768)
        require(bridgeSource == null || bridgeSource in setOf("auto", "custom", "direct"))
        require(carrier == null || carrier.lowercase().trim() in setOf("h2", "h3", "http2", "http3"))
        require(aetherProtocol.lowercase().trim() in setOf("wg", "wireguard", "masque"))
        require(aetherCarrier.lowercase().trim() in setOf("h2", "h3", "http2", "http3"))
        require(urMode.lowercase().trim() in setOf("web", "streaming"))
        require(urCarrier.lowercase().trim() in setOf("h2", "h3", "http2", "http3"))
        require(urLocation.isNotBlank() && urLocation.length <= 32)
        if (hasUrAccount()) {
            require(urTermsAgreed)
            require(urAccountId!!.length in 1..128)
            require(urAccountToken!!.length in 1..4096)
        }
        val lines = bridgeLines()
        if (!usesAutomaticBridges() && !usesDirectConnection()) require(lines.size in 1..64 && lines.all(::validBridge))
        true
    }.getOrDefault(false)

    companion object {
        fun isIpLiteral(value: String): Boolean {
            if (value.contains(':')) return value.length <= 45 && value.count { it == ':' } >= 2 &&
                value.all { it in "0123456789abcdefABCDEF:." } &&
                runCatching { java.net.InetAddress.getByName(value) is java.net.Inet6Address }.getOrDefault(false)
            val parts = value.split('.')
            return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 &&
                it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 }
        }

        fun validBridge(line: String): Boolean {
            if (line.length > 1024 || line.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
            val fields = line.split(Regex("\\s+"))
            if (fields.size != 5 || fields[0] != "obfs4") return false
            val address = fields[1].substringBeforeLast(':').removeSurrounding("[", "]")
            val port = fields[1].substringAfterLast(':').toIntOrNull() ?: return false
            val validHost = isIpLiteral(address) || (address.length in 1..253 &&
                address.split('.').all { it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) })
            return validHost && port in 1..65535 && fields[2].matches(Regex("[A-Fa-f0-9]{40}")) &&
                fields[3].matches(Regex("cert=[A-Za-z0-9+/]{70}={0,2}")) &&
                fields[4] in setOf("iat-mode=0", "iat-mode=1", "iat-mode=2")
        }
    }
}
