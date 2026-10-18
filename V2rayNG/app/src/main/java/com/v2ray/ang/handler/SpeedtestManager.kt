package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.dto.ConnectionTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
    }

    /** Tor diagnostics use the running local proxy, with cancellable requests and enough time for DNS/circuit setup. */
    suspend fun measureSaturnConnection(guid: String, mode: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val port = SettingsManager.getHttpPort()
        check(port in 1..65535)
        val username = SettingsManager.getSocksUsername()
        val password = SettingsManager.getSocksPassword()
        val credentials = if (!username.isNullOrBlank() && !password.isNullOrBlank()) Credentials.basic(username, password) else null
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, port)))
            .proxyAuthenticator { _, response ->
                if (credentials == null || response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", credentials).build()
            }
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).build()
        try {
            val ipUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
                .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL
            runSaturnProbes(listOf(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(true)), ipUrl,
                request = { url, readBody -> fetchSaturnUrl(client, url, readBody) },
                reportFailure = { operation, failure ->
                    // URLs may be customized and contain secrets; log only the operation and failure type.
                    LogUtil.w(AppConfig.TAG, "Saturn: mode=$mode guid=$guid phase=connection-test operation=$operation failed",
                        IOException(failure.javaClass.simpleName))
                })
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    internal suspend fun runSaturnProbes(delayUrls: List<String>, ipUrl: String,
        request: suspend (String, Boolean) -> String,
        reportFailure: (String, Exception) -> Unit): ConnectionTestResult {
        var elapsed = -1L
        for ((index, url) in delayUrls.distinct().withIndex()) {
            currentCoroutineContext().ensureActive()
            val started = System.nanoTime()
            try {
                request(url, false)
                elapsed = (System.nanoTime() - started) / 1_000_000
                break
            } catch (e: IOException) {
                reportFailure("delay-$index", e)
            } catch (e: IllegalArgumentException) {
                reportFailure("delay-$index", e)
            }
        }
        // A blocked ping endpoint does not prove all Tor exits or IP-information services are unreachable.
        currentCoroutineContext().ensureActive()
        val endpoint = try {
            parseRemoteIPInfo(request(ipUrl, true))?.takeIf { !it.ipAddress.isNullOrBlank() }
                ?: throw IOException("IP information unavailable")
        } catch (e: IOException) {
            reportFailure("ip-info", e)
            null
        } catch (e: IllegalArgumentException) {
            reportFailure("ip-info", e)
            null
        }
        return ConnectionTestResult(delayMillis = elapsed, country = endpoint?.country, ipAddress = endpoint?.ipAddress)
    }

    private suspend fun fetchSaturnUrl(client: OkHttpClient, url: String, readBody: Boolean): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val content = response.use {
                            if (!it.isSuccessful) throw IOException("HTTP status ${it.code}")
                            if (!readBody) "" else it.body.byteStream().use { input ->
                                val bytes = ByteArray(4097)
                                var size = 0
                                while (size < bytes.size) {
                                    val count = input.read(bytes, size, bytes.size - size)
                                    if (count < 0) break
                                    size += count
                                }
                                if (size > 4096) throw IOException("IP response exceeded limit")
                                String(bytes, 0, size, Charsets.UTF_8)
                            }
                        }
                        if (continuation.isActive) continuation.resume(content)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    fun getRemoteIPInfo(): RemoteEndpointInfo? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        return parseRemoteIPInfo(content)
    }

    private fun parseRemoteIPInfo(content: String): RemoteEndpointInfo? {
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return RemoteEndpointInfo(
            country = country,
            ipAddress = ip,
        )
    }
}
