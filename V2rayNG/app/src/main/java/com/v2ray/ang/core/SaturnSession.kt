package com.v2ray.ang.core

import android.content.Context
import android.os.Process
import androidx.annotation.RequiresApi
import com.v2ray.ang.dto.entities.SaturnSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** saturn-core: one daemon-owned DoH proxy, Tor process and managed obfs4 transport. */
@RequiresApi(26)
class SaturnSession {
    private var child: java.lang.Process? = null
    private var outputJob: Job? = null
    private var doh: SaturnDohProxy? = null
    @Volatile private var bootstrapped = false

    suspend fun start(context: Context, guid: String, settings: SaturnSettings, reservedPorts: Set<Int>,
        owner: CoroutineScope): Int = withContext(Dispatchers.IO) {
        check(child == null && doh == null)
        require(guid.matches(Regex("[A-Za-z0-9_-]{1,128}")) && settings.isValid())
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val launcher = File(nativeDir, "libsaturn-core.so")
        val tor = File(nativeDir, "libsaturn-tor.so")
        val obfs4 = File(nativeDir, "libsaturn-obfs4.so")
        check(listOf(launcher, tor, obfs4).all { it.isFile }) { "Saturn native inputs unavailable" }
        val directory = File(context.noBackupFilesDir, "saturn/$guid")
        check(directory.isDirectory || directory.mkdirs())
        val geoip = File(directory, "geoip")
        for (name in listOf("geoip", "geoip6")) {
            val target = File(directory, name)
            if (!target.exists()) {
                val pending = File(directory, "$name.pending")
                try {
                    context.assets.open("saturn/$name").use { input -> pending.outputStream().use { input.copyTo(it) } }
                    check(pending.renameTo(target))
                } finally { pending.delete() }
            }
        }
        val proxy = SaturnDohProxy(settings, owner, reservedPorts)
        doh = proxy
        currentCoroutineContext().ensureActive()
        // Validate the HTTPS resolver before probing bridges. There is no system-DNS fallback.
        proxy.resolve("torproject.org")
        currentCoroutineContext().ensureActive()
        val lines = settings.bridgeLines().take(if (settings.scanMode == "turbo") 12 else 64).flatMap { bridge ->
            val parts = bridge.split(Regex("\\s+"))
            val host = parts[1].substringBeforeLast(':').removeSurrounding("[", "]")
            val port = parts[1].substringAfterLast(':')
            try {
                currentCoroutineContext().ensureActive()
                proxy.resolve(host).take(2).map { address ->
                    val ip = address.hostAddress!!.substringBefore('%')
                    listOf(parts[0], (if (ip.contains(':')) "[$ip]" else ip) + ":$port")
                        .plus(parts.drop(2)).joinToString(" ")
                }
            } catch (e: java.net.UnknownHostException) { emptyList() }
        }
        proxy.start(lines)
        val selected = SaturnBridgeSelector.select(lines, settings.scanMode) { line, timeout ->
            withContext(Dispatchers.IO) {
                try {
                    val endpoint = line.split(' ')[1]
                    val started = System.nanoTime()
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(endpoint.substringBeforeLast(':').removeSurrounding("[", "]"),
                            endpoint.substringAfterLast(':').toInt()), timeout)
                    }
                    (System.nanoTime() - started) / 1_000_000
                } catch (_: java.io.IOException) { null }
            }
        }
        check(selected.isNotEmpty()) { "No supplied Saturn bridge is reachable" }
        val port = (1..16).asSequence().map {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.first { it in 1024..65535 && it !in reservedPorts && it != proxy.port }
        val torrc = File(directory, "torrc")
        torrc.writeText(configuration(settings, directory, geoip, obfs4, port, proxy.port, selected, Process.myPid()))
        currentCoroutineContext().ensureActive()
        val command = listOf(launcher.absolutePath, tor.absolutePath, "-f", torrc.absolutePath)
        val builder = ProcessBuilder(command).directory(directory).redirectErrorStream(true)
        builder.environment()["TOR_PT_PROXY"] = "socks5://127.0.0.1:${proxy.port}"
        bootstrapped = false
        val process = builder.start()
        child = process
        process.outputStream.close()
        outputJob = owner.launch(Dispatchers.IO) {
            // Native output can contain bridge addresses. Retain only bootstrap success.
            try { process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(512)
                val line = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        if (buffer[index] == '\n') {
                            if (line.contains("Bootstrapped 100%")) bootstrapped = true
                            line.setLength(0)
                        } else if (line.length < 2048) line.append(buffer[index])
                    }
                }
            } } catch (_: java.io.IOException) {
                // Teardown closes the process pipe; native contents are never persisted.
            }
        }
        withTimeout(if (settings.scanMode == "deep") 300_000 else 180_000) {
            while (!bootstrapped) {
                check(process.isAlive) { "Saturn Tor bootstrap failed" }
                delay(200)
            }
        }
        check(isAlive())
        port
    }

    fun isAlive(): Boolean = bootstrapped && child?.isAlive == true

    suspend fun stop() = withContext(Dispatchers.IO) {
        bootstrapped = false
        try {
            child?.let { process ->
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
                check(process.waitFor(1, TimeUnit.SECONDS)) { "Saturn native process did not exit" }
            }
            child = null
        } finally {
            doh?.stop()
            doh = null
            outputJob?.cancelAndJoin()
            outputJob = null
        }
    }

    companion object {
        internal fun configuration(settings: SaturnSettings, directory: File, geoip: File, obfs4: File,
            port: Int, dohPort: Int, bridges: List<String>, ownerPid: Int): String {
            require(settings.isValid() && port in 1024..65535 && dohPort in 1024..65535 && bridges.isNotEmpty())
            require(bridges.all(SaturnSettings::validBridge))
            require(ownerPid > 0)
            fun quoted(file: File): String = "\"" + file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            return buildString {
                appendLine("DataDirectory ${quoted(directory)}")
                appendLine("GeoIPFile ${quoted(geoip)}")
                appendLine("GeoIPv6File ${quoted(File(geoip.parentFile, "geoip6"))}")
                appendLine("SocksPort 127.0.0.1:$port")
                appendLine("Socks5Proxy 127.0.0.1:$dohPort")
                appendLine("ClientOnly 1")
                appendLine("UseBridges 1")
                appendLine("ClientTransportPlugin obfs4 exec ${quoted(obfs4)}")
                appendLine("RunAsDaemon 0")
                appendLine("SafeLogging 1")
                appendLine("Log notice stdout")
                appendLine("__OwningControllerProcess $ownerPid")
                if (settings.country != "auto") appendLine("ExitNodes {${settings.country}}")
                bridges.forEach { appendLine("Bridge $it") }
            }
        }
    }
}
