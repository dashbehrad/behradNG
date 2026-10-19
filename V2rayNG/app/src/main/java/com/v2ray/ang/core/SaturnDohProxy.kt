package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.SaturnSettings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import okio.Buffer
import java.io.DataInputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import java.io.OutputStream
import java.util.Random
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Daemon-session-owned, loopback-only SOCKS5 CONNECT bridge. DoH never carries TCP payloads. */
internal class SaturnDohProxy(settings: SaturnSettings, private val owner: CoroutineScope, reservedPorts: Set<Int> = emptySet(),
    private val profileGuid: String? = null) {
    private val client = OkHttpClient.Builder().socketFactory(DoHFragmentingSocketFactory())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).proxy(java.net.Proxy.NO_PROXY).build()
    private val resolvers = createResolvers(settings, client, ::reportDnsFailure)
    private val preferredResolver = AtomicInteger()
    private val stopped = AtomicBoolean()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val active = AtomicInteger()
    private val listener = (1..16).asSequence().map {
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    }.firstOrNull { socket ->
        if (socket.localPort in reservedPorts) { socket.close(); false } else true
    } ?: error("No internal Saturn DNS proxy port available")
    private var acceptJob: Job? = null
    private var allowed = emptySet<Pair<String, Int>>()
    private val directConnection = settings.usesDirectConnection()
    val port: Int get() = listener.localPort

    fun resolve(host: String): List<InetAddress> = if (SaturnSettings.isIpLiteral(host))
        listOf(InetAddress.getByName(host)) else lookupWithFallback(host, resolvers, preferredResolver, stopped::get, ::reportDnsFailure)

    private fun reportDnsFailure(index: Int, e: IOException) {
        LogUtil.w(AppConfig.TAG, "Saturn: mode=transport component=DoH guid=$profileGuid operation=encrypted DNS request failed resolver=$index", e)
    }

    /** Session-owned refresh using the configured DoH resolver; cancellation closes the HTTP call. */
    suspend fun discoverBridges(): List<String> = suspendCancellableCoroutine { continuation ->
        val refreshClient = client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
        }).callTimeout(20, TimeUnit.SECONDS).build()
        val call = refreshClient.newCall(Request.Builder()
            .url("https://bridges.torproject.org/moat/circumvention/builtin").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bridges = response.use {
                        check(it.isSuccessful) { "Saturn bridge distributor unavailable" }
                        it.body.byteStream().use { input ->
                            val bytes = ByteArray(65537)
                            var size = 0
                            while (size < bytes.size) {
                                val count = input.read(bytes, size, bytes.size - size)
                                if (count < 0) break
                                size += count
                            }
                            require(size <= 65536) { "Saturn bridge response too large" }
                            SaturnBridgeSelector.parseBuiltins(String(bytes, 0, size, Charsets.UTF_8))
                        }
                    }
                    continuation.resume(bridges)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    fun start(bridges: List<String>) {
        allowed = bridges.map { line ->
            val endpoint = line.split(' ')[1]
            endpoint.substringBeforeLast(':').removeSurrounding("[", "]") to endpoint.substringAfterLast(':').toInt()
        }.toSet()
        acceptJob = owner.launch(Dispatchers.IO) {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    if (active.incrementAndGet() > 32) { active.decrementAndGet(); socket.close(); continue }
                    sockets.add(socket)
                    if (listener.isClosed) { active.decrementAndGet(); sockets.remove(socket); socket.close(); continue }
                    launch { try { connect(socket) } finally { active.decrementAndGet(); sockets.remove(socket); socket.close() } }
                }
            } catch (e: java.net.SocketException) {
                if (!listener.isClosed) throw e
            }
        }
    }

    private suspend fun connect(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            require(input.readUnsignedByte() == 5)
            val methods = ByteArray(input.readUnsignedByte().also { require(it in 1..16) })
            input.readFully(methods)
            if (0.toByte() !in methods) { output.write(byteArrayOf(5, -1)); return }
            output.write(byteArrayOf(5, 0)); output.flush()
            require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
            val host = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress!!
                4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress!!
                3 -> String(ByteArray(input.readUnsignedByte().also { require(it > 0) }).also(input::readFully), Charsets.US_ASCII)
                else -> error("Unsupported address")
            }
            val destPort = input.readUnsignedShort()
            require(destPort in 1..65535 && (directConnection || host to destPort in allowed))
            val remote = Socket()
            sockets.add(remote)
            try {
                check(!listener.isClosed)
                remote.connect(InetSocketAddress(resolve(host).first(), destPort), 10000)
                remote.tcpNoDelay = true
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
                socket.soTimeout = 0
                socket.tcpNoDelay = true
                kotlinx.coroutines.coroutineScope {
                    val upload = launch(Dispatchers.IO) {
                        try { input.copyTo(remote.getOutputStream(), 32768) }
                        finally { remote.close(); socket.close() }
                    }
                    try { remote.getInputStream().copyTo(output, 32768) }
                    finally { remote.close(); socket.close(); upload.cancelAndJoin() }
                }
            } finally { sockets.remove(remote); remote.close() }
        } catch (_: java.io.IOException) {
            // A peer closing or refusing a bridge is an expected candidate failure.
        } catch (_: IllegalArgumentException) {
            // Reject malformed local SOCKS requests without logging destination/bridge secrets.
        } catch (_: IllegalStateException) {
            // Unsupported local requests are not valid Tor bridge connections.
        }
    }

    suspend fun stop() {
        stopped.set(true)
        listener.close()
        client.dispatcher.cancelAll()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        acceptJob?.cancelAndJoin()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        /** Retry wire-format POST as GET. Only the built-in default may fail over to Cloudflare;
         * custom providers stay confined to the configured endpoint. Never use system DNS. */
        internal fun createResolvers(settings: SaturnSettings, client: OkHttpClient,
            onFailure: (Int, IOException) -> Unit): List<Dns> {
            val endpoints = mutableListOf(settings.dohUrl to settings.bootstrapIps.split(',').map { it.trim() })
            val defaults = SaturnSettings()
            if (settings.dohUrl == defaults.dohUrl && settings.bootstrapIps == defaults.bootstrapIps) {
                endpoints += "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")
            }
            return endpoints.mapIndexed { index, (url, ips) ->
                val preferGet = AtomicBoolean()
                val dnsClient = client.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).apply {
                        interceptors().add(0, Interceptor { chain ->
                            fun getRequest(request: Request): Request {
                                val buffer = Buffer()
                                requireNotNull(request.body).writeTo(buffer)
                                val dns = buffer.readByteString().base64Url().trimEnd('=')
                                return request.newBuilder().url(request.url.newBuilder()
                                    .setQueryParameter("dns", dns).build()).get().removeHeader("Content-Type").build()
                            }
                            val post = chain.request()
                            val request = if (preferGet.get()) getRequest(post) else post
                            val response = chain.proceed(request)
                            if (request.method == "POST" && response.code in setOf(403, 405, 415)) {
                                onFailure(index, IOException("DoH HTTP ${response.code}; retrying with GET"))
                                response.close()
                                chain.proceed(getRequest(post)).also { if (it.isSuccessful) preferGet.set(true) }
                            } else response
                        })
                    }.build()
                DnsOverHttps.Builder().client(dnsClient).url(url.toHttpUrl())
                    .bootstrapDnsHosts(ips.map(InetAddress::getByName))
                    .resolvePrivateAddresses(true).post(true).build()
            }
        }

        /** Remember the working encrypted resolver for this session to avoid repeated failed round trips. */
        internal fun lookupWithFallback(host: String, resolvers: List<Dns>, preferred: AtomicInteger,
            isStopped: () -> Boolean, onFailure: (Int, IOException) -> Unit): List<InetAddress> {
            val first = preferred.get()
            val order = resolvers.indices.sortedBy { if (it == first) 0 else 1 }
            val failures = mutableListOf<IOException>()
            for (index in order) {
                if (isStopped()) throw IOException("Saturn encrypted resolver stopped")
                try {
                    val addresses = resolvers[index].lookup(host)
                    if (addresses.isEmpty()) throw UnknownHostException("Empty encrypted DNS response")
                    preferred.set(index)
                    return addresses
                } catch (e: IOException) {
                    failures += e
                    if (!isStopped()) onFailure(index, e)
                }
            }
            throw UnknownHostException("Saturn encrypted DNS lookup failed").apply {
                failures.forEach { addSuppressed(it) }
            }
        }
    }
}

/**
 * Fragments the first TLS record sent by a DoH connection. This is the DoH transport
 * workaround, separate from Xray outbound finalmask fragmentation: it splits the TLS
 * ClientHello before the DoH HTTP request reaches the resolver.
 */
internal class DoHFragmentingSocketFactory : SocketFactory() {
    private val delegate = SocketFactory.getDefault()

    override fun createSocket(): Socket = FragmentingSocket(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = FragmentingSocket(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        FragmentingSocket(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: java.net.InetAddress, port: Int): Socket = FragmentingSocket(delegate.createSocket(host, port))
    override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        FragmentingSocket(delegate.createSocket(host, port, localHost, localPort))
}

private class FragmentingSocket(private val delegate: Socket) : Socket() {
    private val output by lazy { FragmentingOutputStream(delegate.getOutputStream()) }

    override fun connect(endpoint: SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
    override fun getInputStream() = delegate.getInputStream()
    override fun getOutputStream() = output
    override fun setSoTimeout(timeout: Int) = delegate.setSoTimeout(timeout)
    override fun getSoTimeout() = delegate.soTimeout
    override fun setTcpNoDelay(on: Boolean) = delegate.setTcpNoDelay(on)
    override fun setKeepAlive(on: Boolean) = delegate.setKeepAlive(on)
    override fun close() = delegate.close()
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun isConnected() = delegate.isConnected
    override fun isClosed() = delegate.isClosed
    override fun getInetAddress() = delegate.inetAddress
    override fun getRemoteSocketAddress() = delegate.remoteSocketAddress
}

private class FragmentingOutputStream(private val delegate: OutputStream) : OutputStream() {
    private var fragmented = false
    private val random = Random()
    private val pending = ByteArrayOutputStream()

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (fragmented) {
            delegate.write(bytes, offset, length)
            return
        }
        pending.write(bytes, offset, length)
        if (pending.size() >= 96) flushPending(true)
    }

    override fun flush() {
        flushPending(false)
        delegate.flush()
    }

    private fun flushPending(forceFragment: Boolean) {
        if (pending.size() == 0) return
        val bytes = pending.toByteArray()
        pending.reset()
        if (forceFragment && bytes[0].toInt() and 0xff == 0x16 && bytes.size > 1) {
            val firstLength = 40 + random.nextInt(51)
            val split = minOf(firstLength, bytes.size - 1)
            delegate.write(bytes, 0, split)
            delegate.flush()
            delegate.write(bytes, split, bytes.size - split)
            fragmented = true
        } else {
            delegate.write(bytes)
            if (forceFragment) fragmented = true
        }
    }

    override fun close() = delegate.close()
}
