package com.v2ray.ang.core

import android.content.Context
import android.os.Process
import androidx.annotation.RequiresApi
import com.v2ray.ang.dto.entities.SaturnSettings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
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
    private var obfs4Process: java.lang.Process? = null
    private var obfs4OutputJob: Job? = null
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
        val proxy = SaturnDohProxy(settings, owner, reservedPorts, guid)
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
            discoverBridgesWithTimeout { proxy.discoverBridges() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Saturn: mode=transport guid=$guid bridge refresh failed; using bundled pool",
                e)
            emptyList()
        } else emptyList()
        currentCoroutineContext().ensureActive()
        val bridges = SaturnBridgeSelector.resolveCandidates(SaturnBridgeSelector.candidates(settings, bundled, refreshed), proxy::resolve)
        // Let Tor authenticate every candidate. TCP probe latency cannot establish obfs4
        // usability or bandwidth, and pre-filtering can discard the only working bridge.
        check(bridges.isNotEmpty()) { "No Saturn bridge could be resolved" }
        proxy.start(bridges)
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=BRIDGES operation=bridge pool ready candidates=${bridges.size}")
        stage = Stage.NATIVE
        val obfs4Proxy = startObfs4Transport(obfs4, directory, proxy.port, guid, owner)
        val port = (1..16).asSequence().map {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.first { it in 1024..65535 && it !in reservedPorts && it != proxy.port }
        val torrc = File(directory, "torrc")
        torrc.writeText(configuration(settings, directory, geoip, obfs4Proxy, port, bridges, Process.myPid()))
        currentCoroutineContext().ensureActive()
        val command = listOf(launcher.absolutePath, tor.absolutePath, "-f", torrc.absolutePath)
        val builder = ProcessBuilder(command).directory(directory).redirectErrorStream(true)
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
            check(awaitBootstrap(180_000,
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

    private suspend fun startObfs4Transport(binary: File, directory: File, proxyPort: Int, guid: String,
        owner: CoroutineScope): String {
        val stateDirectory = File(directory, "pt_state")
        check(stateDirectory.isDirectory || stateDirectory.mkdirs())
        val process = ProcessBuilder(binary.absolutePath).directory(directory).redirectErrorStream(true).apply {
            environment()["TOR_PT_MANAGED_TRANSPORT_VER"] = "1"
            environment()["TOR_PT_CLIENT_TRANSPORTS"] = "obfs4"
            environment()["TOR_PT_STATE_LOCATION"] = stateDirectory.absolutePath + File.separator
            environment()["TOR_PT_EXIT_ON_STDIN_CLOSE"] = "1"
            environment()["TOR_PT_PROXY"] = "socks5://127.0.0.1:$proxyPort"
        }.start()
        obfs4Process = process
        val methodAddress = CompletableDeferred<String>()
        obfs4OutputJob = owner.launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        when {
                            line.startsWith("CMETHOD ") -> parseObfs4Cmethod(line)?.let(methodAddress::complete)
                            line == "CMETHODS DONE" -> if (!methodAddress.isCompleted) {
                                methodAddress.completeExceptionally(IllegalStateException("Lyrebird did not register obfs4"))
                            }
                            line.startsWith("CMETHOD-ERROR obfs4 ") || line.startsWith("ENV-ERROR ") ||
                                line.startsWith("VERSION-ERROR ") -> if (!methodAddress.isCompleted) {
                                methodAddress.completeExceptionally(IllegalStateException("Lyrebird managed transport setup failed"))
                            }
                        }
                    }
                }
                if (!methodAddress.isCompleted) {
                    methodAddress.completeExceptionally(IllegalStateException("Lyrebird exited before registering obfs4"))
                }
            } catch (e: Exception) {
                methodAddress.completeExceptionally(e)
            }
        }
        return try {
            withTimeout(10_000) { methodAddress.await() }.also {
                check(process.isAlive) { "Lyrebird exited during managed transport setup" }
                LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=NATIVE operation=managed obfs4 transport ready")
            }
        } catch (e: Exception) {
            stopObfs4Transport()
            throw e
        }
    }

    private suspend fun stopObfs4Transport() = withContext(Dispatchers.IO) {
        val process = obfs4Process
        try {
            if (process != null) {
                runCatching { process.outputStream.close() }
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
                check(process.waitFor(1, TimeUnit.SECONDS)) { "Lyrebird process did not exit" }
            }
        } finally {
            obfs4Process = null
            obfs4OutputJob?.cancelAndJoin()
            obfs4OutputJob = null
        }
    }

    fun isAlive(): Boolean = bootstrapped && child?.isAlive == true && obfs4Process?.isAlive == true

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
            try {
                stopObfs4Transport()
            } finally {
                try {
                    doh?.stop()
                } finally {
                    doh = null
                    outputJob?.cancelAndJoin()
                    outputJob = null
                }
            }
        }
    }

    companion object {
        internal const val BRIDGE_REFRESH_TIMEOUT_MILLIS = 10_000L

        internal suspend fun discoverBridgesWithTimeout(discover: suspend () -> List<String>): List<String> =
            withTimeoutOrNull(BRIDGE_REFRESH_TIMEOUT_MILLIS) { discover() }.orEmpty()

        internal fun parseObfs4Cmethod(line: String): String? {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size != 4 || fields[0] != "CMETHOD" || fields[1] != "obfs4" || fields[2] != "socks5") return null
            val endpoint = fields[3]
            val host = endpoint.substringBeforeLast(':', "")
            val port = endpoint.substringAfterLast(':').toIntOrNull() ?: return null
            return endpoint.takeIf { host in setOf("127.0.0.1", "localhost") && port in 1024..65535 }
        }

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

        internal fun configuration(settings: SaturnSettings, directory: File, geoip: File, obfs4Proxy: String,
            port: Int, bridges: List<String>, ownerPid: Int): String {
            require(settings.isValid() && port in 1024..65535 && parseObfs4Cmethod("CMETHOD obfs4 socks5 $obfs4Proxy") != null && bridges.isNotEmpty())
            require(bridges.all(SaturnSettings::validBridge))
            require(ownerPid > 0)
            fun quoted(file: File): String = "\"" + file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            return buildString {
                appendLine("DataDirectory ${quoted(directory)}")
                appendLine("GeoIPFile ${quoted(geoip)}")
                appendLine("GeoIPv6File ${quoted(File(geoip.parentFile, "geoip6"))}")
                appendLine("SocksPort 127.0.0.1:$port")
                appendLine("ClientOnly 1")
                appendLine("UseBridges 1")
                // Tor rejects Socks5Proxy alongside ClientTransportPlugin. Lyrebird owns the upstream
                // SOCKS proxy via TOR_PT_PROXY; Tor connects to Lyrebird's managed local SOCKS endpoint.
                appendLine("ClientTransportPlugin obfs4 socks5 $obfs4Proxy")
                appendLine("RunAsDaemon 0")
                appendLine("SafeLogging 1")
                // Start Tor's consensus fetch immediately; Tor otherwise waits before contacting authorities.
                appendLine("ClientBootstrapConsensusAuthorityDownloadInitialDelay 0")
                appendLine("Log notice stdout")
                appendLine("__OwningControllerProcess $ownerPid")
                if (settings.country != "auto") appendLine("ExitNodes {${settings.country}}")
                bridges.forEach { appendLine("Bridge $it") }
            }
        }
    }
}
