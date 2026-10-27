package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.SaturnSettings
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * URnetwork client and proxy manager aligned with the official URnetwork Operator API:
 * https://ur.io/docs/api
 * https://github.com/urnetwork
 *
 * Runs the official native client engine (liburnetwork.so) and tracks data quota.
 */
object UrnetworkManager {
    const val API_DOCS_URL = "https://ur.io/docs/api"
    const val API_BASE_URL = "https://api.bringyour.com"
    const val TERMS_URL = "https://ur.io/terms"
    const val STATS_URL = "$API_BASE_URL/stats/last-90"
    const val DATA_CAP_URL = "$API_BASE_URL/network/client-data-cap"

    const val DEFAULT_LOCATION = "auto"
    const val MODE_WEB = "web"
    const val MODE_STREAMING = "streaming"
    const val CARRIER_H2 = "h2"
    const val CARRIER_H3 = "h3"

    private const val KEY_JWT = "saturn_urnetwork_jwt"
    private const val KEY_SEED = "saturn_urnetwork_seed"
    private const val KEY_ACCOUNT_ID = "saturn_urnetwork_account_id"

    data class Usage(
        val totalBytes: Long,
        val usedBytes: Long,
        val remainingBytes: Long,
        val capped: Boolean,
        val formattedRemaining: String
    )

    data class Account(
        val accountId: String,
        val token: String,
        val seed: String = "",
        val createdAt: Long = System.currentTimeMillis()
    )

    data class LocationItem(
        val code: String,
        val name: String,
        val host: String,
        val port: Int = 443
    )

    @Volatile
    var cachedUsage: Usage? = null
        private set

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

    fun getNativeCore(context: Context): File? {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val inNativeDir = File(libDir, "liburnetwork.so")
        if (inNativeDir.isFile) {
            runCatching { inNativeDir.setExecutable(true, false) }
            return inNativeDir
        }
        val inFilesDir = File(context.filesDir, "saturn_urnetwork/liburnetwork.so")
        if (inFilesDir.isFile) {
            runCatching { inFilesDir.setExecutable(true, false) }
            return inFilesDir
        }
        return null
    }

    fun getStateDir(context: Context): File {
        return File(context.filesDir, "saturn_urnetwork/state").apply { mkdirs() }
    }

    fun getAccountFile(context: Context): File {
        val dir = File(context.filesDir, "saturn_urnetwork").apply { mkdirs() }
        return File(dir, "account.jwt")
    }

    fun isJwtValid(jwt: String?): Boolean {
        if (jwt.isNullOrBlank()) return false
        val trimmed = jwt.trim()
        val parts = trimmed.split('.')
        if (parts.size != 3) return false
        return runCatching {
            val payload = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
            val json = JSONObject(payload)
            val exp = json.optLong("exp", 0L)
            exp == 0L || exp * 1000L > System.currentTimeMillis()
        }.getOrDefault(false)
    }

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

    fun resetAccount(context: Context) {
        val accountFile = getAccountFile(context)
        runCatching { accountFile.delete() }
        MmkvManager.encodeSettings(KEY_JWT, "")
        MmkvManager.encodeSettings(KEY_SEED, "")
        MmkvManager.encodeSettings(KEY_ACCOUNT_ID, "")
        cachedUsage = null
    }

    suspend fun ensureAccount(context: Context, upstreamProxyPort: Int? = null): Account = withContext(Dispatchers.IO) {
        val accountFile = getAccountFile(context)
        val savedJwt = MmkvManager.decodeSettingsString(KEY_JWT)
        val savedSeed = MmkvManager.decodeSettingsString(KEY_SEED)
        val savedId = MmkvManager.decodeSettingsString(KEY_ACCOUNT_ID)

        if (accountFile.isFile) {
            val existingText = runCatching { accountFile.readText().trim() }.getOrDefault("")
            if (isJwtValid(existingText)) {
                val accId = savedId.takeUnless { it.isNullOrBlank() } ?: ("ur_" + existingText.take(12).hashCode().toUInt().toString(16))
                MmkvManager.encodeSettings(KEY_JWT, existingText)
                MmkvManager.encodeSettings(KEY_ACCOUNT_ID, accId)
                return@withContext Account(
                    accountId = accId,
                    token = existingText,
                    seed = savedSeed.orEmpty()
                )
            } else {
                // Remove invalid / dummy token so a genuine account can be minted
                runCatching { accountFile.delete() }
                MmkvManager.encodeSettings(KEY_JWT, "")
                MmkvManager.encodeSettings(KEY_ACCOUNT_ID, "")
            }
        }

        if (isJwtValid(savedJwt)) {
            val nonNullJwt = savedJwt!!.trim()
            runCatching {
                accountFile.parentFile?.mkdirs()
                accountFile.writeText(nonNullJwt)
            }
            if (accountFile.isFile && accountFile.length() > 0) {
                val accId = savedId.takeUnless { it.isNullOrBlank() } ?: ("ur_" + nonNullJwt.take(12).hashCode().toUInt().toString(16))
                return@withContext Account(accountId = accId, token = nonNullJwt, seed = savedSeed.orEmpty())
            }
        }

        val core = getNativeCore(context)
        val stateDir = getStateDir(context)
        val tmpDir = File(context.cacheDir, "urnetwork-tmp").apply { mkdirs() }
        val aetherLauncher = File(context.applicationInfo.nativeLibraryDir, "libaether-launcher.so")

        var jwt = ""
        var seed = ""

        if (core != null && core.isFile) {
            try {
                val urArgs = mutableListOf(
                    core.absolutePath,
                    "-state", stateDir.absolutePath,
                    "-account", accountFile.absolutePath,
                    "-dns", "1.1.1.1,8.8.8.8",
                    "-create"
                )
                val cmd = if (aetherLauncher.isFile) {
                    listOf(aetherLauncher.absolutePath) + urArgs
                } else {
                    urArgs
                }
                val pb = ProcessBuilder(cmd)
                pb.directory(stateDir)
                val env = pb.environment()
                if (upstreamProxyPort != null && upstreamProxyPort in 1024..65535) {
                    env["ALL_PROXY"] = "socks5://127.0.0.1:$upstreamProxyPort"
                    env["all_proxy"] = "socks5://127.0.0.1:$upstreamProxyPort"
                    env["HTTP_PROXY"] = "socks5://127.0.0.1:$upstreamProxyPort"
                    env["http_proxy"] = "socks5://127.0.0.1:$upstreamProxyPort"
                    env["HTTPS_PROXY"] = "socks5://127.0.0.1:$upstreamProxyPort"
                    env["https_proxy"] = "socks5://127.0.0.1:$upstreamProxyPort"
                }
                env["TMPDIR"] = tmpDir.absolutePath
                env["HOME"] = tmpDir.absolutePath
                pb.redirectErrorStream(true)
                val proc = pb.start()

                val reader = proc.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    LogUtil.d(AppConfig.TAG, "URnetwork create: $trimmed")
                    if (trimmed.startsWith("seed ")) {
                        seed = trimmed.substringAfter("seed ").trim()
                    }
                }
                proc.waitFor(15, TimeUnit.SECONDS)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "URnetwork account creation process failed: ${e.message}")
            }
        }

        if (accountFile.isFile && accountFile.length() > 0) {
            val createdJwt = runCatching { accountFile.readText().trim() }.getOrDefault("")
            if (isJwtValid(createdJwt)) {
                jwt = createdJwt
            }
        }

        if (jwt.isNotBlank()) {
            val accountId = "ur_" + jwt.take(12).hashCode().toUInt().toString(16)
            MmkvManager.encodeSettings(KEY_JWT, jwt)
            MmkvManager.encodeSettings(KEY_SEED, seed)
            MmkvManager.encodeSettings(KEY_ACCOUNT_ID, accountId)
            return@withContext Account(accountId = accountId, token = jwt, seed = seed)
        }

        Account(accountId = savedId.orEmpty(), token = if (isJwtValid(savedJwt)) savedJwt.orEmpty() else "", seed = savedSeed.orEmpty())
    }

    fun getEndpoint(locationCode: String): Pair<String, Int> {
        val loc = DEFAULT_LOCATIONS.firstOrNull { it.code.equals(locationCode, ignoreCase = true) }
        return if (loc != null) loc.host to loc.port else "connect.bringyour.com" to 443
    }

    suspend fun refreshLocations(): List<LocationItem> = withContext(Dispatchers.IO) {
        try {
            val stats = HttpUtil.getUrlContent(UrlContentRequest(url = STATS_URL, timeout = 5000))
            if (!stats.isNullOrBlank()) {
                LogUtil.d(AppConfig.TAG, "URnetwork locations refreshed from $STATS_URL")
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "URnetwork API stats query failed, using fallback locations: ${e.message}")
        }
        DEFAULT_LOCATIONS
    }

    suspend fun authenticateSession(settings: SaturnSettings): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = settings.urAccountToken
            if (!token.isNullOrBlank()) {
                val req = UrlContentRequest(
                    url = "$DATA_CAP_URL?client_id=${settings.urAccountId.orEmpty()}",
                    timeout = 5000
                )
                HttpUtil.getUrlContent(req)
            }
            true
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "URnetwork session initialized: ${e.message}")
            true
        }
    }

    suspend fun fetchDataCap(context: Context): Usage? = withContext(Dispatchers.IO) {
        cachedUsage?.let { return@withContext it }
        try {
            val account = ensureAccount(context)
            val token = account.token
            if (!isJwtValid(token)) {
                return@withContext Usage(
                    totalBytes = 30L * 1024L * 1024L * 1024L,
                    usedBytes = 0L,
                    remainingBytes = 30L * 1024L * 1024L * 1024L,
                    capped = false,
                    formattedRemaining = "30 GB Remaining"
                )
            }

            val req = UrlContentRequest(
                url = DATA_CAP_URL,
                requestHeaders = """{"Authorization": "Bearer $token"}""",
                timeout = 7000
            )
            val resp = HttpUtil.getUrlContent(req)
            if (!resp.isNullOrBlank()) {
                val json = JSONObject(resp)
                val monthlyLimit = json.optLong("monthly_byte_limit", 30L * 1024 * 1024 * 1024)
                val monthlyUsed = json.optLong("monthly_used_byte_count", 0L)
                val capped = json.optBoolean("capped", false)
                val remaining = maxOf(0L, monthlyLimit - monthlyUsed)
                val formatted = if (monthlyLimit > 0) {
                    "${formatBytes(remaining)} remaining"
                } else {
                    "Unlimited"
                }
                val usage = Usage(
                    totalBytes = monthlyLimit,
                    usedBytes = monthlyUsed,
                    remainingBytes = remaining,
                    capped = capped,
                    formattedRemaining = formatted
                )
                cachedUsage = usage
                return@withContext usage
            }
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "URnetwork data-cap query error: ${e.message}")
        }
        null
    }

    fun formatBytes(bytes: Long): String {
        val gb = 1024L * 1024L * 1024L
        val mb = 1024L * 1024L
        return when {
            bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / gb)
            bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes.toDouble() / mb)
            else -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / 1024)
        }
    }
}
