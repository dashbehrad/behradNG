package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.SaturnSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Daemon-session-owned, loopback-only SOCKS5 CONNECT bridge. DoH never carries TCP payloads. */
internal class SaturnDohProxy(settings: SaturnSettings, private val owner: CoroutineScope, reservedPorts: Set<Int> = emptySet()) {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).proxy(java.net.Proxy.NO_PROXY).build()
    private val resolver = DnsOverHttps.Builder().client(client).url(settings.dohUrl.toHttpUrl())
        .bootstrapDnsHosts(settings.bootstrapIps.split(',').map { InetAddress.getByName(it.trim()) })
        .resolvePrivateAddresses(true).post(true).build()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val active = AtomicInteger()
    private val listener = (1..16).asSequence().map {
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    }.firstOrNull { socket ->
        if (socket.localPort in reservedPorts) { socket.close(); false } else true
    } ?: error("No internal Saturn DNS proxy port available")
    private var acceptJob: Job? = null
    private var allowed = emptySet<Pair<String, Int>>()
    val port: Int get() = listener.localPort

    fun resolve(host: String): List<InetAddress> = if (SaturnSettings.isIpLiteral(host))
        listOf(InetAddress.getByName(host)) else resolver.lookup(host)

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
            require(host to destPort in allowed)
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
        listener.close()
        client.dispatcher.cancelAll()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        acceptJob?.cancelAndJoin()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
