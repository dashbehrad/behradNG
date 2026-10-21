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
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import okio.Buffer
import java.io.DataInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import java.io.OutputStream
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Daemon-session-owned, loopback-only SOCKS5 CONNECT bridge. DoH never carries TCP payloads. */
internal class SaturnDohProxy(private val settings: SaturnSettings, private val owner: CoroutineScope, reservedPorts: Set<Int> = emptySet(),
    private val profileGuid: String? = null, private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).proxy(java.net.Proxy.NO_PROXY).build()) {
    private val stopped = AtomicBoolean()
    private val resolvers = createResolvers(settings, client, stopped::get, ::reportDnsFailure)
    private val preferredResolver = AtomicInteger()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val refreshDispatchers = ConcurrentHashMap.newKeySet<Dispatcher>()
    private val active = AtomicInteger()
    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val hostLocks = ConcurrentHashMap<String, Any>()
    private val listener = (1..16).asSequence().map {
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    }.firstOrNull { socket ->
        if (socket.localPort in reservedPorts) { socket.close(); false } else true
    } ?: error("No internal Saturn DNS proxy port available")
    private var acceptJob: Job? = null
    private var allowed = emptySet<Pair<String, Int>>()
    private val directConnection = settings.usesDirectConnection()
    val port: Int get() = listener.localPort

    fun resolve(host: String): List<InetAddress> {
        if (SaturnSettings.isIpLiteral(host)) return listOf(InetAddress.getByName(host))
        val now = System.currentTimeMillis()
        val cached = dnsCache[host]
        if (cached != null && now < cached.first) {
            return cached.second
        }
        val lock = hostLocks.computeIfAbsent(host) { Any() }
        val result = synchronized(lock) {
            val secondCheck = dnsCache[host]
            if (secondCheck != null && System.currentTimeMillis() < secondCheck.first) {
                return@synchronized secondCheck.second
            }
            val resolved = lookupWithFallback(host, resolvers, preferredResolver, stopped::get, ::reportDnsFailure)
            if (resolved.isNotEmpty()) {
                dnsCache[host] = (System.currentTimeMillis() + 1_800_000L) to resolved
            }
            resolved
        }
        hostLocks.remove(host, lock)
        return result
    }

    private fun reportDnsFailure(index: Int, e: IOException) {
        val source = if (settings.usesDirectConnection()) "direct" else if (settings.usesAutomaticBridges()) "auto" else "custom"
        LogUtil.w(AppConfig.TAG, "Saturn: mode=transport component=DoH guid=$profileGuid source=$source operation=encrypted DNS request failed resolver=$index", e)
    }

    /** Refresh owns its HTTP and nested DoH calls; cancellation must leave other session requests alone. */
    suspend fun discoverBridges(): List<String> = suspendCancellableCoroutine { continuation ->
        val refreshStopped = AtomicBoolean()
        val dispatcher = Dispatcher()
        refreshDispatchers.add(dispatcher)
        fun finishRefresh() {
            if (refreshStopped.compareAndSet(false, true)) {
                dispatcher.cancelAll()
                dispatcher.executorService.shutdown()
                refreshDispatchers.remove(dispatcher)
            }
        }
        continuation.invokeOnCancellation { finishRefresh() }
        if (stopped.get() || !continuation.isActive) {
            finishRefresh()
            if (continuation.isActive) continuation.resumeWithException(IOException("Saturn bridge refresh stopped"))
            return@suspendCancellableCoroutine
        }
        val isRefreshStopped = { stopped.get() || refreshStopped.get() }
        val reportRefreshFailure: (Int, IOException) -> Unit = { index, failure ->
            if (!isRefreshStopped()) reportDnsFailure(index, failure)
        }
        val refreshDnsClient = client.newBuilder().dispatcher(dispatcher).build()
        val refreshResolvers = createResolvers(settings, refreshDnsClient, isRefreshStopped, reportRefreshFailure)
        val refreshPreferred = AtomicInteger(preferredResolver.get())
        val refreshClient = refreshDnsClient.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (SaturnSettings.isIpLiteral(hostname)) listOf(InetAddress.getByName(hostname))
                else lookupWithFallback(hostname, refreshResolvers, refreshPreferred, isRefreshStopped, reportRefreshFailure)
                    .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
        }).socketFactory(DoHFragmentingSocketFactory()).callTimeout(1500, TimeUnit.MILLISECONDS).build()
        val call = refreshClient.newCall(Request.Builder()
            .url("https://bridges.torproject.org/moat/circumvention/builtin").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                finishRefresh()
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
                    if (continuation.isActive) continuation.resume(bridges)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally { finishRefresh() }
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
                    if (active.incrementAndGet() > 512) { active.decrementAndGet(); socket.close(); continue }
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
            socket.apply {
                tcpNoDelay = true
                keepAlive = true
                receiveBufferSize = 524288
                sendBufferSize = 524288
                soTimeout = 15000
            }
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
            val addresses = resolve(host).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            require(addresses.isNotEmpty()) { "No addresses resolved for $host" }
            var connectedSocket: Socket? = null
            var lastFailure: IOException? = null
            for (address in addresses) {
                if (listener.isClosed) break
                val candidate = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    receiveBufferSize = 524288
                    sendBufferSize = 524288
                }
                sockets.add(candidate)
                try {
                    candidate.connect(InetSocketAddress(address, destPort), 4000)
                    connectedSocket = candidate
                    break
                } catch (e: IOException) {
                    lastFailure = e
                    sockets.remove(candidate)
                    runCatching { candidate.close() }
                } catch (e: Throwable) {
                    sockets.remove(candidate)
                    runCatching { candidate.close() }
                    throw e
                }
            }
            val remote = connectedSocket ?: throw (lastFailure ?: IOException("Connection failed to $host:$destPort"))
            try {
                remote.apply {
                    tcpNoDelay = true
                    keepAlive = true
                    receiveBufferSize = 524288
                    sendBufferSize = 524288
                    soTimeout = 0
                }
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
                socket.soTimeout = 0
                kotlinx.coroutines.coroutineScope {
                    val upload = launch(Dispatchers.IO) {
                        try { input.copyTo(remote.getOutputStream(), 131072) }
                        finally { remote.close(); socket.close() }
                    }
                    try { remote.getInputStream().copyTo(output, 131072) }
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
        refreshDispatchers.forEach { dispatcher ->
            dispatcher.cancelAll()
            dispatcher.executorService.shutdown()
        }
        refreshDispatchers.clear()
        client.dispatcher.cancelAll()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        acceptJob?.cancelAndJoin()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        internal val KNOWN_DOH_POOLS = mapOf(
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "cloudflare-dns.com" to listOf("162.159.36.1", "162.159.46.1", "1.1.1.1", "1.0.0.1"),
            "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6"),
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112"),
            "doh.opendns.com" to listOf("208.67.222.222", "208.67.220.220", "146.112.41.2"),
            "doh.cleanbrowsing.org" to listOf("185.228.168.9", "185.228.169.9"),
            "base.dns.mullvad.net" to listOf("194.242.2.2")
        )

        /**
         * Scans candidate IPs concurrently on port 443 with a short timeout, ranking responsive
         * IPs by lowest round-trip latency. Follows pydns-scanner / SlipNet DnsPoolScanner design.
         */
        internal fun scanBestIps(candidates: List<String>, port: Int = 443, timeoutMs: Int = 1500): List<String> {
            if (candidates.size <= 1) return candidates
            val results = ConcurrentHashMap<String, Long>()
            val threads = candidates.map { ip ->
                Thread({
                    try {
                        val t0 = System.currentTimeMillis()
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, port), timeoutMs)
                        }
                        results[ip] = System.currentTimeMillis() - t0
                    } catch (_: Exception) {}
                }, "doh-ip-scanner").apply { isDaemon = true; start() }
            }
            threads.forEach { runCatching { it.join(timeoutMs.toLong() + 200) } }
            val working = results.entries.sortedBy { it.value }.map { it.key }
            return if (working.isNotEmpty()) working + (candidates - working.toSet()) else candidates
        }

        /** Retry wire-format POST as GET. Only the built-in default may fail over to Cloudflare;
         * custom providers stay confined to the configured endpoint. Never use system DNS. */
        internal fun createResolvers(settings: SaturnSettings, client: OkHttpClient,
            isStopped: () -> Boolean = { false }, onFailure: (Int, IOException) -> Unit): List<Dns> {
            val userIps = settings.bootstrapIps.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val dohHost = try { settings.dohUrl.toHttpUrl().host } catch (_: Exception) { "" }
            val primaryCandidates = if (userIps.isNotEmpty() && dohHost in KNOWN_DOH_POOLS && !userIps.any { it in (KNOWN_DOH_POOLS[dohHost] ?: emptyList()) }) {
                (KNOWN_DOH_POOLS[dohHost] ?: emptyList()) + userIps
            } else if (userIps.isNotEmpty()) userIps
            else KNOWN_DOH_POOLS[dohHost] ?: (if (SaturnSettings.isIpLiteral(dohHost)) listOf(dohHost) else emptyList())
            val endpoints = mutableListOf(settings.dohUrl to primaryCandidates)
            val defaults = SaturnSettings()
            if (settings.dohUrl == defaults.dohUrl && (settings.bootstrapIps == defaults.bootstrapIps || settings.bootstrapIps.isBlank())) {
                endpoints += "https://cloudflare-dns.com/dns-query" to (KNOWN_DOH_POOLS["cloudflare-dns.com"] ?: listOf("162.159.36.1", "1.1.1.1"))
                endpoints += "https://dns.alidns.com/dns-query" to (KNOWN_DOH_POOLS["dns.alidns.com"] ?: listOf("223.5.5.5", "223.6.6.6"))
                endpoints += "https://dns.quad9.net/dns-query" to (if (userIps.isNotEmpty()) userIps else (KNOWN_DOH_POOLS["dns.quad9.net"] ?: listOf("9.9.9.9", "149.112.112.112")))
            }
            return endpoints.mapIndexed { index, (url, ips) ->
                val preferGet = AtomicBoolean()
                val preferFragmented = AtomicBoolean()
                val dnsClient = client.newBuilder().connectTimeout(1500, TimeUnit.MILLISECONDS)
                    .readTimeout(1500, TimeUnit.MILLISECONDS).callTimeout(2000, TimeUnit.MILLISECONDS).apply {
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
                fun resolverFor(transport: OkHttpClient): Dns {
                    val builder = DnsOverHttps.Builder().client(transport).url(url.toHttpUrl())
                        .resolvePrivateAddresses(true).post(true)
                    val hosts = ips.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                    if (hosts.isNotEmpty()) builder.bootstrapDnsHosts(hosts)
                    return builder.build()
                }
                val standard = resolverFor(dnsClient)
                val fragmented = resolverFor(dnsClient.newBuilder().socketFactory(DoHFragmentingSocketFactory()).build())
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        if (preferFragmented.get()) {
                            try {
                                fragmented.lookup(hostname)
                            } catch (_: IOException) {
                                lookupWithTlsFallback(hostname, standard, fragmented, isStopped) { failure ->
                                    onFailure(index, IOException("DoH HTTPS transport failed; retrying with fragmented TLS", failure))
                                }
                            }
                        } else {
                            lookupWithTlsFallback(hostname, standard, fragmented, isStopped) { failure ->
                                preferFragmented.set(true)
                                onFailure(index, IOException("DoH HTTPS transport failed; retrying with fragmented TLS", failure))
                            }
                        }
                }
            }
        }

        /** Retry only socket transport failures, retaining the same encrypted endpoint and TLS checks. */
        internal fun lookupWithTlsFallback(host: String, standard: Dns, fragmented: Dns,
            isStopped: () -> Boolean, onFailure: (IOException) -> Unit): List<InetAddress> {
            if (isStopped()) throw IOException("Saturn encrypted resolver stopped")
            try {
                return standard.lookup(host)
            } catch (failure: IOException) {
                if (isStopped() || Thread.currentThread().isInterrupted || !isTlsTransportFailure(failure)) throw failure
                onFailure(failure)
                if (isStopped() || Thread.currentThread().isInterrupted) throw failure
                try {
                    return fragmented.lookup(host)
                } catch (retryFailure: IOException) {
                    if (retryFailure !== failure) retryFailure.addSuppressed(failure)
                    throw retryFailure
                }
            }
        }

        private fun isTlsTransportFailure(failure: IOException): Boolean {
            val pending = java.util.ArrayDeque<Throwable>()
            val seen = mutableSetOf<Throwable>()
            var transportFailure = false
            pending.add(failure)
            while (pending.isNotEmpty()) {
                val error = pending.removeFirst()
                if (!seen.add(error)) continue
                if (error is javax.net.ssl.SSLPeerUnverifiedException || error is java.security.cert.CertificateException) return false
                if (error is java.net.SocketException || error is java.net.SocketTimeoutException) transportFailure = true
                error.cause?.let(pending::add)
                error.suppressed.forEach(pending::add)
            }
            return transportFailure
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
 * Splits the first ClientHello into valid TLS records, including across the SNI hostname.
 * Uses the record-splitting approach in SlipNet SniFragmentForwarder; handshake bytes,
 * certificate verification and HTTPS hostname verification remain unchanged.
 * Reference: anonvector/SlipNet@552ab507, SniFragmentForwarder.kt (sni_split).
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

    init { delegate.tcpNoDelay = true }

    override fun connect(endpoint: SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
    override fun getInputStream() = delegate.getInputStream()
    override fun getOutputStream() = output
    override fun setSoTimeout(timeout: Int) = delegate.setSoTimeout(timeout)
    override fun getSoTimeout() = delegate.soTimeout
    override fun setTcpNoDelay(on: Boolean) = delegate.setTcpNoDelay(on)
    override fun getTcpNoDelay() = delegate.tcpNoDelay
    override fun setKeepAlive(on: Boolean) = delegate.setKeepAlive(on)
    override fun close() = delegate.close()
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun isConnected() = delegate.isConnected
    override fun isBound() = delegate.isBound
    override fun isClosed() = delegate.isClosed
    override fun getInetAddress() = delegate.inetAddress
    override fun getPort() = delegate.port
    override fun getLocalPort() = delegate.localPort
    override fun getRemoteSocketAddress() = delegate.remoteSocketAddress
    override fun getLocalSocketAddress() = delegate.localSocketAddress
}

internal class FragmentingOutputStream(private val delegate: OutputStream) : OutputStream() {
    private var passthrough = false
    private val pending = ByteArrayOutputStream()

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return
        if (passthrough) {
            delegate.write(bytes, offset, length)
            return
        }
        pending.write(bytes, offset, length)
        val buffered = pending.toByteArray()
        val tlsHeader = buffered[0] == 0x16.toByte() && (buffered.size < 2 || buffered[1] == 3.toByte())
        if (tlsHeader && buffered.size < 5) return
        val recordLength = if (tlsHeader) unsignedShort(buffered, 3) else 0
        if (tlsHeader && recordLength in 1..16384 && buffered.size < 5 + recordLength) return
        pending.reset()
        passthrough = true
        if (tlsHeader && recordLength in 4..16384 && buffered[5] == 1.toByte()) {
            val payload = buffered.copyOfRange(5, 5 + recordLength)
            val middle = (sniSplitPoint(payload) ?: (payload.size / 2)).coerceIn(4, payload.size - 1)
            val ends = listOf(middle, payload.size).distinct().sorted()
            var start = 0
            for (end in ends) {
                val size = end - start
                delegate.write(byteArrayOf(buffered[0], buffered[1], buffered[2], (size shr 8).toByte(), size.toByte()))
                delegate.write(payload, start, size)
                delegate.flush()
                start = end
                if (end < payload.size) {
                    // A short gap plus TCP_NODELAY prevents adjacent record writes from coalescing.
                    try { Thread.sleep(5) } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("DoH ClientHello fragmentation interrupted").apply { initCause(e) }
                    }
                }
            }
            delegate.write(buffered, 5 + recordLength, buffered.size - 5 - recordLength)
        } else {
            delegate.write(buffered)
        }
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun sniSplitPoint(payload: ByteArray): Int? {
        if (payload.size < 39) return null
        val helloEnd = 4 + ((payload[1].toInt() and 0xff) shl 16) + unsignedShort(payload, 2)
        if (helloEnd > payload.size || helloEnd < 39) return null
        var offset = 38
        offset += 1 + (payload[offset].toInt() and 0xff)
        if (offset + 2 > helloEnd) return null
        val cipherLength = unsignedShort(payload, offset)
        offset += 2 + cipherLength
        if (offset >= helloEnd) return null
        offset += 1 + (payload[offset].toInt() and 0xff)
        if (offset + 2 > helloEnd) return null
        val extensionsEnd = offset + 2 + unsignedShort(payload, offset)
        offset += 2
        if (extensionsEnd > helloEnd) return null
        while (offset + 4 <= extensionsEnd) {
            val type = unsignedShort(payload, offset)
            val extensionEnd = offset + 4 + unsignedShort(payload, offset + 2)
            offset += 4
            if (extensionEnd > extensionsEnd) return null
            if (type == 0 && offset + 2 <= extensionEnd) {
                val namesEnd = offset + 2 + unsignedShort(payload, offset)
                offset += 2
                if (namesEnd > extensionEnd) return null
                while (offset + 3 <= namesEnd) {
                    val nameType = payload[offset].toInt() and 0xff
                    val nameLength = unsignedShort(payload, offset + 1)
                    offset += 3
                    if (offset + nameLength > namesEnd) return null
                    if (nameType == 0 && nameLength >= 2) return offset + nameLength / 2
                    offset += nameLength
                }
                return null
            }
            offset = extensionEnd
        }
        return null
    }

    // Keep incomplete TLS records buffered across flushes; a TLS write can arrive in pieces.
    override fun flush() = delegate.flush()

    override fun close() {
        try { flush() } finally { delegate.close() }
    }
}
