package com.v2ray.ang.fmt

import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SaturnSettings
import com.v2ray.ang.enums.EConfigType
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/** Self-contained capability link: anyone possessing the ENTIRE URI can decrypt it. */
object SaturnFmt {
    private data class Payload(val version: Int = 1, val remarks: String = "", val settings: SaturnSettings = SaturnSettings())
    private val gson = Gson()
    private val aad = "saturn://v1".toByteArray(Charsets.UTF_8)

    fun toUri(profile: ProfileItem): String {
        require(profile.configType == EConfigType.SATURN)
        val settings = requireNotNull(profile.saturnSettings)
        require(settings.isValid() && profile.remarks.isNotBlank() && profile.remarks.length <= 256)
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
        require(uri.length <= 65536 && uri.startsWith("saturn://v1/"))
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
        ProfileItem.create(EConfigType.SATURN).copy(remarks = payload.remarks, server = "127.0.0.1",
            serverPort = AppConfig.PORT_SOCKS, network = payload.settings.normalizedCarrier(), saturnSettings = payload.settings)
    }.getOrNull()

    private fun key(secret: ByteArray) = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret), "AES")
}
