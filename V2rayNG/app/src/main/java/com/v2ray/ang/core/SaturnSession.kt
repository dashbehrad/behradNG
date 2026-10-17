package com.v2ray.ang.core

import android.content.Context
import android.os.Process
import androidx.annotation.RequiresApi
import com.v2ray.ang.dto.entities.SaturnSettings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    enum class Stage { NATIVE, DNS, BRIDGES, BOOTSTRAP, READY }
    @Volatile var stage: Stage = Stage.NATIVE
        private set
    @Volatile var nativeExitCode: Int? = null
        private set
    private var child: java.lang.Process? = null
    private var outputJob: Job? = null
    private var doh: SaturnDohProxy? = null
    @Volatile private var bootstrapped = false
    @Volatile private var bootstrapProgress = -1

    suspend fun start(context: Context, guid: String, settings: SaturnSettings, reservedPorts: Set<Int>,
        owner: CoroutineScope): Int = withContext(Dispatchers.IO) {
        check(child == null && doh == null)
        stage = Stage.NATIVE
        nativeExitCode = null
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
        stage = Stage.DNS
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=DNS operation=initialize DoH resolver")
        currentCoroutineContext().ensureActive()
        // Do not gate IP-literal bridges on an unrelated DoH lookup; DoH is used only for hostnames.
        stage = Stage.BRIDGES
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=BRIDGES operation=discover bridge candidates")
        val bundled = if (settings.usesAutomaticBridges()) context.assets.open("saturn/builtin-bridges.json")
            .bufferedReader().use { SaturnBridgeSelector.parseBuiltins(it.readText()) } else emptyList()
        val refreshed = if (settings.usesAutomaticBridges()) try {
            proxy.discoverBridges()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Saturn: mode=transport guid=$guid bridge refresh failed; using bundled pool",
                e)
            emptyList()
        } else emptyList()
        currentCoroutineContext().ensureActive()
        val lines = SaturnBridgeSelector.resolveCandidates(SaturnBridgeSelector.candidates(settings, bundled, refreshed), proxy::resolve)
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
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=BRIDGES operation=bridge scan complete candidates=${lines.size} reachable=${selected.size}")
        check(selected.isNotEmpty()) { "No Saturn bridge is reachable" }
        stage = Stage.NATIVE
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
        bootstrapProgress = -1
        val process = builder.start()
        child = process
        stage = Stage.BOOTSTRAP
        process.outputStream.close()
        outputJob = owner.launch(Dispatchers.IO) {
            // Native output can contain bridge addresses. Retain and log only numeric progress.
            try { process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(512)
                val line = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        if (buffer[index] == '\n') {
                            val progress = parseBootstrapProgress(line.toString())
                            if (progress != null) {
                                bootstrapped = progress == 100
                                if (bootstrapProgress < 0 || progress == 100 || progress / 10 > bootstrapProgress / 10) {
                                    LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=BOOTSTRAP progress=$progress%")
                                }
                                bootstrapProgress = progress
                            }
                            line.setLength(0)
                        } else if (line.length < 2048) line.append(buffer[index])
                    }
                }
            } } catch (_: java.io.IOException) {
                // Teardown closes the process pipe; native contents are never persisted.
            }
        }
        try {
            check(awaitBootstrap(if (settings.scanMode == "deep") 300_000 else 180_000,
                { bootstrapped }, { process.isAlive })) { "Saturn bootstrap timed out" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=BOOTSTRAP operation=Tor bootstrap failed progress=$bootstrapProgress", e)
            throw e
        } finally {
            if (!process.isAlive) nativeExitCode = process.exitValue()
        }
        check(isAlive())
        stage = Stage.READY
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
        internal fun parseBootstrapProgress(line: String): Int? =
            Regex("(?:^|\\s)Bootstrapped\\s+(\\d{1,3})%")
                .find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

        /** An owned bootstrap timeout is failure; cancellation by the service still propagates. */
        internal suspend fun awaitBootstrap(timeoutMillis: Long, ready: () -> Boolean, alive: () -> Boolean): Boolean =
            withTimeoutOrNull(timeoutMillis) {
                while (!ready()) {
                    check(alive()) { "Saturn Tor bootstrap failed" }
                    delay(200)
                }
                true
            } == true

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

