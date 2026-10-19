package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.SaturnSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.decodeBase64

class SaturnDohProxyTest {
    @Test fun directIpRelayDoesNotRequireAReachableDohServer(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val echo = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val proxy = SaturnDohProxy(SaturnSettings(bridgeSource = "direct",
            dohUrl = "https://unreachable.invalid/dns-query", bootstrapIps = "192.0.2.1"), scope)
        val proxyPort = proxy.port
        val echoJob = scope.launch {
            echo.accept().use { peer -> peer.getOutputStream().write(peer.getInputStream().read()) }
        }
        try {
            proxy.start(emptyList())
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3000
                val output = client.getOutputStream()
                val input = DataInputStream(client.getInputStream())
                output.write(byteArrayOf(5, 1, 0)); output.flush()
                assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (echo.localPort shr 8).toByte(), echo.localPort.toByte()))
                output.flush()
                val reply = ByteArray(10); input.readFully(reply)
                assertEquals(0.toByte(), reply[1])
                output.write(42); output.flush()
                assertEquals(42, input.read())
            }
            withTimeout(3000) { echoJob.join() }
        } finally { proxy.stop(); echo.close(); scope.cancel() }
        assertThrows(IOException::class.java) { Socket("127.0.0.1", proxyPort).close() }
    }

    @Test fun forbiddenDohPostRetriesGetAndRemembersTheWorkingMethod() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = fakeDohClient { request ->
            requests.add(request)
            if (request.method == "POST") 403 else 200
        }
        try {
            val settings = SaturnSettings(dohUrl = "https://custom.example/dns-query", bootstrapIps = "192.0.2.1")
            val resolvers = SaturnDohProxy.createResolvers(settings, client) { _, _ -> }
            val preferred = AtomicInteger()
            assertEquals(1, resolvers.size)
            assertEquals("192.0.2.7", SaturnDohProxy.lookupWithFallback("bridge.example", resolvers,
                preferred, { false }) { _, _ -> }.single().hostAddress)
            assertTrue(requests.any { it.method == "POST" })
            assertTrue(requests.any { it.method == "GET" && it.url.queryParameter("dns") != null })
            assertTrue(requests.all { it.header("Accept") == "application/dns-message" })
            requests.clear()
            SaturnDohProxy.lookupWithFallback("other.example", resolvers, preferred, { false }) { _, _ -> }
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it.method == "GET" && it.url.host == "custom.example" })
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun blockedGoogleDefaultFallsBackToCloudflareAndReusesTheWorkingProvider() {
        val settings = SaturnSettings()
        val defaultHost = settings.dohUrl.toHttpUrl().host
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = fakeDohClient { request ->
            requests.add(request)
            if (request.url.host == defaultHost) 403 else 200
        }
        try {
            val resolvers = SaturnDohProxy.createResolvers(settings, client) { _, _ -> }
            val preferred = AtomicInteger()
            assertEquals(2, resolvers.size)
            assertEquals("192.0.2.7", SaturnDohProxy.lookupWithFallback("bridge.example", resolvers,
                preferred, { false }) { _, _ -> }.single().hostAddress)
            assertTrue(requests.any { it.url.host == defaultHost && it.method == "GET" })
            assertEquals(1, preferred.get())
            requests.clear()
            SaturnDohProxy.lookupWithFallback("other.example", resolvers, preferred, { false }) { _, _ -> }
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it.url.host == "cloudflare-dns.com" })
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun customDohFailureNeverContactsAnotherProviderAndPreservesTheCause() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = fakeDohClient { request -> requests.add(request); 403 }
        try {
            val settings = SaturnSettings(dohUrl = "https://custom.example/dns-query", bootstrapIps = "192.0.2.1")
            val resolvers = SaturnDohProxy.createResolvers(settings, client) { _, _ -> }
            val error = assertThrows(UnknownHostException::class.java) {
                SaturnDohProxy.lookupWithFallback("bridge.example", resolvers, AtomicInteger(), { false }) { _, _ -> }
            }
            assertEquals(1, error.suppressed.size)
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it.url.host == "custom.example" && it.url.isHttps })
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun stoppedSessionDoesNotTryAnotherResolver() {
        var stopped = false
        var attempts = 0
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                attempts++
                stopped = true
                throw UnknownHostException("Stopped")
            }
        }
        assertThrows(IOException::class.java) {
            SaturnDohProxy.lookupWithFallback("bridge.example", listOf(dns, dns), AtomicInteger(), { stopped }) { _, _ -> }
        }
        assertEquals(1, attempts)
    }

    @Test fun rateLimitedCustomProviderDoesNotRetryWithGet() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = fakeDohClient { request -> requests.add(request); 429 }
        try {
            val settings = SaturnSettings(dohUrl = "https://custom.example/dns-query", bootstrapIps = "192.0.2.1")
            val resolvers = SaturnDohProxy.createResolvers(settings, client) { _, _ -> }
            assertThrows(UnknownHostException::class.java) {
                SaturnDohProxy.lookupWithFallback("bridge.example", resolvers, AtomicInteger(), { false }) { _, _ -> }
            }
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it.method == "POST" })
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    /** Exercise real OkHttp DoH encoding/decoding without external DNS or network calls. */
    private fun fakeDohClient(status: (Request) -> Int): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val code = status(request)
            val body = if (code == 200) {
                val query = if (request.method == "GET") requireNotNull(request.url.queryParameter("dns")
                    ?.decodeBase64()).toByteArray() else Buffer().also { requireNotNull(request.body).writeTo(it) }.readByteArray()
                var questionEnd = 12
                while (query[questionEnd] != 0.toByte()) questionEnd += 1 + (query[questionEnd].toInt() and 255)
                val ipv4 = query[questionEnd + 1] == 0.toByte() && query[questionEnd + 2] == 1.toByte()
                val reply = query.copyOf(questionEnd + 5)
                reply[2] = 0x81.toByte(); reply[3] = 0x80.toByte()
                reply[6] = 0; reply[7] = if (ipv4) 1 else 0
                reply[8] = 0; reply[9] = 0; reply[10] = 0; reply[11] = 0
                if (ipv4) reply + byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4,
                    192.toByte(), 0, 2, 7) else reply
            } else byteArrayOf()
            Response.Builder().request(request).protocol(Protocol.HTTP_2).code(code).message("Test response")
                .body(body.toResponseBody("application/dns-message".toMediaType())).build()
        }.build()

    @Test fun localSocksConnectRelaysOnlyTheConfiguredBridgeAndStopClosesListeners(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val echo = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val bridge = "obfs4 127.0.0.1:${echo.localPort} " + "A".repeat(40) + " cert=" + "B".repeat(70) + " iat-mode=0"
        val proxy = SaturnDohProxy(SaturnSettings(bridges = bridge), scope)
        val proxyPort = proxy.port
        val echoJob = scope.launch {
            echo.accept().use { peer -> peer.getOutputStream().write(peer.getInputStream().read()) }
        }
        try {
            proxy.start(listOf(bridge))
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3000
                val output = client.getOutputStream()
                val input = DataInputStream(client.getInputStream())
                output.write(byteArrayOf(5, 1, 0)); output.flush()
                assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (echo.localPort shr 8).toByte(), echo.localPort.toByte()))
                output.flush()
                val reply = ByteArray(10); input.readFully(reply)
                assertEquals(0.toByte(), reply[1])
                output.write(42); output.flush()
                assertEquals(42, input.read())
            }
            withTimeout(3000) { echoJob.join() }
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3000
                val output = client.getOutputStream()
                val input = DataInputStream(client.getInputStream())
                output.write(byteArrayOf(5, 1, 0)); output.flush()
                input.readUnsignedByte(); input.readUnsignedByte()
                output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 1)); output.flush()
                assertEquals(-1, input.read())
            }
        } finally { proxy.stop(); echo.close(); scope.cancel() }
        assertThrows(java.io.IOException::class.java) { Socket("127.0.0.1", proxyPort).close() }
    }
}
