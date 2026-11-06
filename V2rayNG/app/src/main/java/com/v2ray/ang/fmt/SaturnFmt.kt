package com.v2ray.ang.fmt

import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SaturnSettings
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/** Formatter for Saturn direct anti-DPI VPN URIs and legacy capability links. */
object SaturnFmt {
    private data class Payload(val version: Int = 1, val remarks: String = "", val settings: SaturnSettings = SaturnSettings())
    private val gson = Gson()
    private val aad = "saturn://v1".toByteArray(Charsets.UTF_8)

    fun toUri(profile: ProfileItem): String {
        require(profile.configType == EConfigType.SATURN)
        val settings = requireNotNull(profile.saturnSettings)
        require(settings.isValid() && profile.remarks.isNotBlank() && profile.remarks.length <= 256)

        if (settings.isDirectSaturn()) {
            val host = Utils.getIpv6Address(if (settings.server.isNotBlank()) settings.server else profile.server.orEmpty())
            val port = settings.serverPort
            val uuid = settings.uuid
            val dicQuery = HashMap<String, String>().apply {
                put("sni", settings.sni)
                if (settings.publicKey.isNotBlank()) put("pbk", settings.publicKey)
                if (settings.shortId.isNotBlank()) put("sid", settings.shortId)
                put("fp", settings.fingerprint)
                put("frag", settings.fragmentation)
            }
            val query = "?" + dicQuery.toList().joinToString(
                separator = "&",
                transform = { it.first + "=" + Utils.encodeURIComponent(it.second) }
            )
            return "${Utils.encodeURIComponent(uuid)}@${host}:${port}${query}#${Utils.encodeURIComponent(profile.remarks)}"
        }

        val random = SecureRandom()
        val secret = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(gson.toJson(Payload(remarks = profile.remarks, settings = settings)).toByteArray(Charsets.UTF_8))
        return "v1/" + Base64.UrlSafe.encode(iv + encrypted) + "#" + Base64.UrlSafe.encode(secret)
    }

    fun parse(uri: String): ProfileItem? = runCatching {
        require(uri.length <= 65536 && uri.startsWith("saturn://"))

        // Legacy encrypted capability link: saturn://v1/...#... or rejected newer/wrong version
        val remainder = uri.removePrefix("saturn://")
        if (remainder.startsWith("v") && remainder.substringBefore('#').substringBefore('?').contains('/')) {
            require(uri.startsWith("saturn://v1/"))
            val pieces = uri.removePrefix("saturn://v1/").split('#')
            require(pieces.size == 2)
            val bytes = Base64.UrlSafe.decode(pieces[0])
            val secret = Base64.UrlSafe.decode(pieces[1])
            require(secret.size == 32 && bytes.size in 29..49152)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            val payload = gson.fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), Payload::class.java)
            require(payload.version == 1 && payload.remarks.isNotBlank() && payload.remarks.length <= 256 && payload.settings.isValid())
            return ProfileItem.create(EConfigType.SATURN).copy(
                remarks = payload.remarks,
                server = "127.0.0.1",
                serverPort = AppConfig.PORT_SOCKS,
                network = payload.settings.normalizedCarrier(),
                saturnSettings = payload.settings
            )
        }

        // Direct Saturn URI: saturn://<UUID>@<SERVER_IP>:<PORT>?sni=<SNI>&pbk=<PUBKEY>&sid=<SHORTID>&fp=chrome&frag=1-5#Remarks
        val parsedUri = URI(uri)
        require(parsedUri.scheme.equals("saturn", ignoreCase = true))

        val uuid = parsedUri.userInfo.orEmpty()
        val host = parsedUri.host.orEmpty()
        val port = if (parsedUri.port > 0) parsedUri.port else 443
        require(uuid.isNotBlank() && host.isNotBlank() && port in 1..65535)

        val queryMap = mutableMapOf<String, String>()
        parsedUri.rawQuery?.split('&')?.forEach { param ->
            val parts = param.split('=', limit = 2)
            if (parts.size == 2) {
                queryMap[parts[0].lowercase()] = Utils.decodeURIComponent(parts[1])
            }
        }

        val sni = queryMap["sni"] ?: "dl.google.com"
        val pbk = queryMap["pbk"] ?: ""
        val sid = queryMap["sid"] ?: ""
        val fp = queryMap["fp"] ?: "chrome"
        val frag = queryMap["frag"] ?: "1-5"
        val remarks = parsedUri.fragment?.let { Utils.decodeURIComponent(it) }?.ifBlank { "Saturn-Direct" } ?: "Saturn-Direct"

        require(pbk.isNotBlank())

        val settings = SaturnSettings(
            server = host,
            serverPort = port,
            uuid = uuid,
            sni = sni,
            publicKey = pbk,
            shortId = sid,
            fingerprint = fp,
            fragmentation = frag
        )
        require(settings.isDirectSaturn() && settings.isValid())

        ProfileItem.create(EConfigType.SATURN).copy(
            remarks = remarks,
            server = host,
            serverPort = port.toString(),
            network = "direct",
            saturnSettings = settings
        )
    }.getOrNull()

    private fun key(secret: ByteArray) = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret), "AES")
}
