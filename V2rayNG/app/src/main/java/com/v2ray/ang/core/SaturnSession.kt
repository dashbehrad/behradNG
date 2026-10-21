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
import kotlinx.coroutines.CoroutineName
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** saturn-core: one daemon-owned DoH proxy and Tor process, with optional managed obfs4 transport. */
@RequiresApi(26)
class SaturnSession {
    enum class Stage { NATIVE, DNS, BRIDGES, BOOTSTRAP, EXIT, READY }
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
    @Volatile private var exitReady = false
    @Volatile private var bootstrapProgress = -1
    private var directConnection = false

    val currentProgress: Int get() = bootstrapProgress

    suspend fun start(context: Context, guid: String, settings: SaturnSettings, reservedPorts: Set<Int>,
        owner: CoroutineScope, onProgress: ((Int) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        check(child == null && doh == null)
        stage = Stage.NATIVE
        exitReady = false
        nativeExitCode = null
        require(guid.matches(Regex("[A-Za-z0-9_-]{1,128}")) && settings.isValid())
        directConnection = settings.usesDirectConnection()
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val launcher = File(nativeDir, "libsaturn-core.so")
        val tor = File(nativeDir, "libsaturn-tor.so")
        val obfs4 = File(nativeDir, "libsaturn-obfs4.so")
        check((if (directConnection) listOf(launcher, tor) else listOf(launcher, tor, obfs4)).all { it.isFile }) { "Saturn native inputs unavailable" }
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
        var obfs4Proxy = ""
        val proxy = SaturnDohProxy(settings, owner, reservedPorts, guid)
        doh = proxy
        stage = Stage.DNS
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=DNS operation=initialize DoH resolver")
        onProgress?.invoke(5)
        currentCoroutineContext().ensureActive()
        if (directConnection) {
            // Like SlipNet, initialize the DoH-backed proxy before Tor without probing an
            // unrelated hostname. IP-literal Tor relays do not require a DNS lookup.
            proxy.start(emptyList())
        }
        val bridges = if (directConnection) emptyList() else {
            // Do not gate IP-literal bridges on an unrelated DoH lookup; DoH is used only for hostnames.
            stage = Stage.BRIDGES
            LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=BRIDGES operation=discover bridge candidates")
            onProgress?.invoke(10)
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
            obfs4Proxy = startObfs4Transport(obfs4, directory, proxy.port, guid, owner)
            bridges
        }
        val port = (1..16).asSequence().map {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.first { it in 1024..65535 && it !in reservedPorts && it != doh?.port }
        val torrc = File(directory, "torrc")
        stage = Stage.NATIVE
        torrc.writeText(configuration(settings, directory, geoip, obfs4Proxy, port, bridges, Process.myPid(), proxy.port))
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
                                onProgress?.invoke(progress)
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
        // A bridge and a directory circuit can bootstrap while ExitNodes has no usable exit.
        // Require a real SOCKS CONNECT through Tor before the service reports a working tunnel.
        stage = Stage.EXIT
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=EXIT operation=verify exit stream country=${settings.country}")
        onProgress?.invoke(95)
        check(awaitExit(60_000) {
            check(transportProcessesAlive(directConnection, process.isAlive, obfs4Process?.isAlive == true)) { "Saturn process exited during exit check" }
            try {
                probeExit(port)
            } catch (e: IOException) {
                try {
                    probeExit(port, "www.google.com")
                } catch (_: IOException) {
                    // SOCKS exceptions can include destination details; retain only the failure type.
                    LogUtil.w(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=EXIT operation=exit stream failed country=${settings.country}",
                        IOException(e.javaClass.simpleName))
                    throw e
                }
            }
        }) { "Saturn could not open an exit stream" }
        exitReady = true
        check(isAlive())
        stage = Stage.READY
        onProgress?.invoke(100)
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

    fun isAlive(): Boolean = bootstrapped && exitReady &&
        transportProcessesAlive(directConnection, child?.isAlive == true, obfs4Process?.isAlive == true)

    suspend fun stop() = withContext(Dispatchers.IO) {
        bootstrapped = false
        exitReady = false
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
        internal fun transportProcessesAlive(direct: Boolean, torAlive: Boolean, obfs4Alive: Boolean): Boolean =
            torAlive && (direct || obfs4Alive)

        internal const val BRIDGE_REFRESH_TIMEOUT_MILLIS = 3_000L

        internal suspend fun discoverBridgesWithTimeout(discover: suspend () -> List<String>): List<String> =
            withTimeoutOrNull(BRIDGE_REFRESH_TIMEOUT_MILLIS) { discover() }.orEmpty()

        /** Probe a public HTTPS port using remote DNS through Tor, never a direct connection. */
        internal suspend fun probeExit(port: Int, host: String = "www.torproject.org"): Unit = coroutineScope {
            val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            suspendCancellableCoroutine { continuation ->
                val probe = launch(Dispatchers.IO + CoroutineName("SaturnExitProbe")) {
                    try {
                        socket.use {
                            it.connect(InetSocketAddress.createUnresolved(host, 443), 15_000)
                        }
                        continuation.resume(Unit)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { socket.close() }
                    probe.cancel()
                }
            }
        }

        /** Retry transient exit failures; owner cancellation and dead processes must propagate. */
        internal suspend fun awaitExit(timeoutMillis: Long, probe: suspend () -> Unit): Boolean =
            withTimeoutOrNull(timeoutMillis) {
                var connected = false
                while (!connected) {
                    try {
                        probe()
                        connected = true
                    } catch (_: IOException) {
                        delay(1_000)
                    }
                }
                connected
            } == true

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
            port: Int, bridges: List<String>, ownerPid: Int, dohProxyPort: Int? = null): String {
            require(settings.isValid() && port in 1024..65535)
            if (settings.usesDirectConnection()) {
                require(bridges.isEmpty() && dohProxyPort != null && dohProxyPort in 1024..65535 && dohProxyPort != port)
            }
            else require(parseObfs4Cmethod("CMETHOD obfs4 socks5 $obfs4Proxy") != null && bridges.isNotEmpty())
            require(bridges.all(SaturnSettings::validBridge))
            require(ownerPid > 0)
            fun quoted(file: File): String = "\"" + file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            return buildString {
                appendLine("DataDirectory ${quoted(directory)}")
                appendLine("GeoIPFile ${quoted(geoip)}")
                appendLine("GeoIPv6File ${quoted(File(geoip.parentFile, "geoip6"))}")
                appendLine("SocksPort 127.0.0.1:$port")
                appendLine("ClientOnly 1")
                appendLine("AvoidDiskWrites 1")
                appendLine("DormantClientTimeout 2419200")
                appendLine("CircuitBuildTimeout 60")
                appendLine("LearnCircuitBuildTimeout 0")
                appendLine("KeepalivePeriod 20")
                appendLine("ConnectionPadding 1")
                appendLine("ReducedConnectionPadding 0")
                appendLine("PathsNeededToBuildCircuits 0.25")
                appendLine("ClientBootstrapConsensusMaxInProgressTries 3")
                appendLine("DownloadExtraInfo 0")
                appendLine("OptimisticData 1")
                appendLine("ClientUseIPv4 1")
                appendLine("ClientUseIPv6 0")
                appendLine("ClientPreferIPv6ORPort 0")
                appendLine("ClientPreferIPv6DirPort 0")
                appendLine("UseBridges ${if (settings.usesDirectConnection()) 0 else 1}")
                if (settings.usesDirectConnection()) appendLine("Socks5Proxy 127.0.0.1:$dohProxyPort")
                // Tor rejects Socks5Proxy alongside ClientTransportPlugin. Lyrebird owns the upstream
                // SOCKS proxy via TOR_PT_PROXY; Tor connects to Lyrebird's managed local SOCKS endpoint.
                if (!settings.usesDirectConnection()) appendLine("ClientTransportPlugin obfs4 socks5 $obfs4Proxy")
                appendLine("RunAsDaemon 0")
                appendLine("SafeLogging 0")
                // Start Tor's consensus fetch immediately; Tor otherwise waits before contacting authorities.
                appendLine("ClientBootstrapConsensusAuthorityDownloadInitialDelay 0")
                appendLine("ClientBootstrapConsensusFallbackDownloadSchedule 0, 1, 2, 4, 8")
                appendLine("ClientBootstrapConsensusAuthorityDownloadSchedule 0, 2, 4, 8, 16")
                appendLine("Log notice stdout")
                appendLine("__OwningControllerProcess $ownerPid")
                if (settings.country != "auto") appendLine("ExitNodes {${settings.country}}")
                bridges.forEach { appendLine("Bridge $it") }
            }
        }
    }
}
