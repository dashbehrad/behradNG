package com.v2ray.ang.dto.entities

import java.net.URI

/** Saturn owns only its Tor/DoH settings; native identity and runtime ports are never shared. */
data class SaturnSettings(
    val dohUrl: String = "https://doh.nineline.workers.dev/dns-query",
    val bootstrapIps: String = "1.1.1.1,1.0.0.1",
    val bridges: String = "",
    val scanMode: String = "turbo",
    val country: String = "auto",
    // Missing in 3.0.0 links: retain custom bridges; empty profiles use automatic discovery.
    val bridgeSource: String? = null,
) {
    fun usesAutomaticBridges(): Boolean = bridgeSource == "auto" || (bridgeSource == null && bridges.isBlank())

    fun bridgeLines(): List<String> = bridges.lineSequence().map { it.trim().removePrefix("Bridge ") }
        .filter { it.isNotEmpty() }.distinct().toList()

    fun isValid(): Boolean = runCatching {
        val uri = URI(dohUrl)
        require(dohUrl.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrEmpty())
        require(uri.userInfo == null && uri.fragment == null && uri.port in listOf(-1, 443))
        require(scanMode in setOf("turbo", "deep"))
        require(country == "auto" || country.matches(Regex("[a-z]{2}")))
        val ips = bootstrapIps.split(',').map { it.trim() }
        require(ips.size in 1..8 && ips.all(::isIpLiteral))
        require(bridges.length <= 32768)
        require(bridgeSource == null || bridgeSource in setOf("auto", "custom"))
        val lines = bridgeLines()
        if (!usesAutomaticBridges()) require(lines.size in 1..64 && lines.all(::validBridge))
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
