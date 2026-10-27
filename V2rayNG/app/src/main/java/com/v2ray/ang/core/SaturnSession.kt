package com.v2ray.ang.core

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.AetherSettings
import com.v2ray.ang.dto.entities.SaturnSettings
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** saturn-core: Aether Cloudflare Tunnel chained to URnetwork decentralized proxy network. */
@RequiresApi(26)
class SaturnSession {
    enum class Stage { AETHER, URNETWORK, EXIT, READY, NATIVE, CLOUDFLARED, BRIDGES, BOOTSTRAP }

    @Volatile
    var stage: Stage = Stage.AETHER
        private set

    @Volatile
    var nativeExitCode: Int? = null
        private set

    private var cfSession: AetherSession? = null
    private var urProcess: Process? = null
    private var proxyServer: ServerSocket? = null
    private var proxyJob: Job? = null
    @Volatile
    private var isRunning = false
    @Volatile
    private var bootstrapProgress = -1
    private var cloudflaredPort: Int = 0
    private var urnetworkPort: Int = 0

    val currentProgress: Int get() = bootstrapProgress

    suspend fun start(
        context: Context,
        guid: String,
        settings: SaturnSettings,
        reservedPorts: Set<Int>,
        owner: CoroutineScope,
        onProgress: ((Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        check(cfSession == null && proxyServer == null)
        stage = Stage.AETHER
        nativeExitCode = null
        require(guid.matches(Regex("[A-Za-z0-9_-]{1,128}")) && settings.isValid())

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val aetherCore = File(nativeDir, "libaether-core.so")
        val aetherLauncher = File(nativeDir, "libaether-launcher.so")
        check(aetherCore.isFile && aetherLauncher.isFile) { "Aether native inputs unavailable" }

        // Hop 1: Connect to Aether (Cloudflare Tunnel)
        stage = Stage.AETHER
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=AETHER operation=connect Aether Cloudflare Tunnel protocol=${settings.normalizedAetherProtocol()} carrier=${settings.normalizedAetherCarrier()}")
        bootstrapProgress = 15
        onProgress?.invoke(15)
        currentCoroutineContext().ensureActive()

        val cf = AetherSession()
        cfSession = cf
        val dnsIps = settings.bootstrapIps.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(",").ifBlank { "1.1.1.1,1.0.0.1" }
        val cfSettings = AetherSettings(
            protocol = settings.normalizedAetherProtocol(),
            carrier = settings.normalizedAetherCarrier(),
            scanMode = "balanced",
            ipVersion = "v4",
            noise = "firewall",
            dns = dnsIps,
            fragment = true,
            fragmentSize = "16-32",
            fragmentDelay = "2-10"
        )
        val cfPort = cf.start(context, "${guid}_cf", cfSettings, reservedPorts, owner, "Saturn")
        cloudflaredPort = cfPort
        check(awaitTunnelReady(cfPort, alive = { cf.isAlive() })) { "Aether Cloudflare Tunnel did not route traffic in time" }
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=AETHER operation=Aether Cloudflare Tunnel ready port=$cfPort")
        bootstrapProgress = 50
        onProgress?.invoke(50)
        currentCoroutineContext().ensureActive()

        // Hop 2: Connect URnetwork native daemon chained over Aether
        stage = Stage.URNETWORK
        val urCore = UrnetworkManager.getNativeCore(context)
        val urPort = (1..16).asSequence().map {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.first { it in 1024..65535 && it !in reservedPorts && it != cloudflaredPort }
        urnetworkPort = urPort

        var actualUpstreamPort = cfPort
        if (urCore != null && urCore.isFile) {
            val aetherLauncher = File(context.applicationInfo.nativeLibraryDir, "libaether-launcher.so")
            val accountFile = UrnetworkManager.getAccountFile(context)
            UrnetworkManager.ensureAccount(context, upstreamProxyPort = cfPort)
            val stateDir = UrnetworkManager.getStateDir(context)
            val tmpDir = File(context.cacheDir, "urnetwork-tmp").apply { mkdirs() }

            val urArgs = mutableListOf(
                urCore.absolutePath,
                "-state", stateDir.absolutePath,
                "-account", accountFile.absolutePath,
                "-socks", "127.0.0.1:$urPort",
                "-memory", "128",
                "-desc", "behradNG · ${Build.MODEL}",
                "-dns", dnsIps
            )
            val clientCountry = Locale.getDefault().country.lowercase().takeIf { it.isNotBlank() } ?: "ir"
            urArgs.add("-country")
            urArgs.add(clientCountry)

            val loc = settings.urLocation.trim().lowercase()
            if (loc.isNotEmpty() && loc != "auto") {
                val locJson = JSONObject().apply {
                    put("country_code", loc.uppercase())
                    put("location_type", "country")
                }.toString()
                urArgs.add("-location")
                urArgs.add(locJson)
            }

            LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=URNETWORK operation=chain URnetwork mode=${settings.normalizedUrMode()} carrier=${settings.normalizedUrCarrier()} location=${settings.urLocation} socksPort=$urPort upstream=127.0.0.1:$cfPort")

            val command = if (aetherLauncher.isFile) {
                listOf(aetherLauncher.absolutePath) + urArgs
            } else {
                urArgs
            }
            val pb = ProcessBuilder(command)
            pb.directory(stateDir)
            val env = pb.environment()
            env["ALL_PROXY"] = "socks5://127.0.0.1:$cfPort"
            env["all_proxy"] = "socks5://127.0.0.1:$cfPort"
            env["HTTP_PROXY"] = "socks5://127.0.0.1:$cfPort"
            env["http_proxy"] = "socks5://127.0.0.1:$cfPort"
            env["HTTPS_PROXY"] = "socks5://127.0.0.1:$cfPort"
            env["https_proxy"] = "socks5://127.0.0.1:$cfPort"
            env["TMPDIR"] = tmpDir.absolutePath
            env["HOME"] = tmpDir.absolutePath
            pb.redirectErrorStream(true)

            val proc = pb.start()
            urProcess = proc

            val readyDeferred = CompletableDeferred<Boolean>()
            owner.launch(Dispatchers.IO + CoroutineName("UrnetworkLogPumper")) {
                val capturedErrors = StringBuilder()
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            LogUtil.d(AppConfig.TAG, "URnetwork: $line")
                            if (line.contains("listening on") || line.contains("listening")) {
                                if (!readyDeferred.isCompleted) readyDeferred.complete(true)
                            }
                            if (line.contains("error", ignoreCase = true) || line.contains("refused", ignoreCase = true)) {
                                capturedErrors.append(line).append("\n")
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (!readyDeferred.isCompleted) readyDeferred.complete(false)
                    if (!proc.isAlive) {
                        val exit = runCatching { proc.exitValue() }.getOrNull()
                        LogUtil.e(AppConfig.TAG, "URnetwork daemon exited with code $exit: $capturedErrors")
                    }
                }
            }

            val readySuccess = withTimeoutOrNull(25_000L) {
                readyDeferred.await() || pollTunnelReady(urPort, 10_000L, alive = { proc.isAlive })
            } ?: false

            if (readySuccess && (proc.isAlive || testSocketListening(urPort))) {
                LogUtil.i(AppConfig.TAG, "Saturn: URnetwork daemon active on 127.0.0.1:$urPort chained to Aether 127.0.0.1:$cfPort")
                actualUpstreamPort = urPort
            } else {
                LogUtil.w(AppConfig.TAG, "Saturn: URnetwork daemon not ready in time, falling back to Aether transport")
            }
        } else {
            LogUtil.w(AppConfig.TAG, "Saturn: liburnetwork.so not available for current ABI, running on direct Aether transport")
        }

        bootstrapProgress = 70
        onProgress?.invoke(70)
        currentCoroutineContext().ensureActive()

        // Hop 3 (Client entry): Saturn proxy server listening on saturnPort
        val saturnPort = (1..16).asSequence().map {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        }.first { it in 1024..65535 && it !in reservedPorts && it != cloudflaredPort && it != urPort }

        val server = ServerSocket(saturnPort, 50, InetAddress.getByName("127.0.0.1"))
        proxyServer = server
        isRunning = true

        proxyJob = owner.launch(Dispatchers.IO + CoroutineName("SaturnProxyServer")) {
            while (isActive && !server.isClosed) {
                try {
                    val clientSocket = server.accept()
                    launch(Dispatchers.IO + CoroutineName("SaturnProxyConnection")) {
                        handleChainedConnection(clientSocket, actualUpstreamPort, settings)
                    }
                } catch (_: IOException) {
                    break
                }
            }
        }

        // Verify exit stream
        stage = Stage.EXIT
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=EXIT operation=verify exit stream location=${settings.urLocation}")
        bootstrapProgress = 85
        onProgress?.invoke(85)
        currentCoroutineContext().ensureActive()

        check(awaitExit(30_000) {
            check(isAlive()) { "Saturn session exited prematurely during stream verification" }
            try {
                probeExit(saturnPort, "cp.cloudflare.com")
            } catch (e: IOException) {
                try {
                    probeExit(saturnPort, "www.google.com")
                } catch (_: IOException) {
                    throw e
                }
            }
        }) { "Saturn could not open an exit stream through proxy chain" }

        stage = Stage.READY
        bootstrapProgress = 100
        onProgress?.invoke(100)
        LogUtil.i(AppConfig.TAG, "Saturn: mode=transport guid=$guid startup stage=READY operation=proxy chain active on port $saturnPort exitVia=${if (actualUpstreamPort == urPort) "URnetwork" else "Aether"}")
        saturnPort
    }

    private suspend fun handleChainedConnection(
        clientSocket: Socket,
        upstreamPort: Int,
        settings: SaturnSettings
    ) = withContext(Dispatchers.IO) {
        var upstreamSocket: Socket? = null
        try {
            clientSocket.soTimeout = 15_000
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // 1. Client greeting
            val ver = clientIn.read()
            if (ver != 5) return@withContext
            val nmethods = clientIn.read()
            if (nmethods <= 0) return@withContext
            val methods = ByteArray(nmethods)
            clientIn.readExact(methods)
            clientOut.write(byteArrayOf(5, 0)) // No auth
            clientOut.flush()

            // 2. Client request
            val reqVer = clientIn.read()
            val cmd = clientIn.read()
            val rsv = clientIn.read()
            val atyp = clientIn.read()
            if (reqVer != 5 || cmd != 1 || rsv != 0) return@withContext

            val targetAddrBytes: ByteArray = when (atyp) {
                1 -> ByteArray(4 + 2).also { clientIn.readExact(it) } // IPv4 + port
                3 -> {
                    val len = clientIn.read()
                    if (len <= 0) return@withContext
                    val domainBytes = ByteArray(len).also { clientIn.readExact(it) }
                    val portBytes = ByteArray(2).also { clientIn.readExact(it) }
                    byteArrayOf(len.toByte()) + domainBytes + portBytes
                }
                4 -> ByteArray(16 + 2).also { clientIn.readExact(it) } // IPv6 + port
                else -> return@withContext
            }

            val requestPayload = byteArrayOf(5, 1, 0, atyp.toByte()) + targetAddrBytes

            // 3. Connect to upstream transport (URnetwork daemon or Aether tunnel)
            val upstream = Socket("127.0.0.1", upstreamPort)
            upstreamSocket = upstream
            upstream.soTimeout = 15_000
            val upIn = upstream.getInputStream()
            val upOut = upstream.getOutputStream()

            // Upstream greeting
            upOut.write(byteArrayOf(5, 1, 0))
            upOut.flush()
            val upVer = upIn.read()
            val upMethod = upIn.read()
            if (upVer != 5 || upMethod != 0) return@withContext

            // Upstream CONNECT: Forward target destination through the tunnel
            upOut.write(requestPayload)
            upOut.flush()

            // Read upstream reply
            val repVer = upIn.read()
            val repCode = upIn.read()
            val repRsv = upIn.read()
            val repAtyp = upIn.read()
            if (repVer != 5) return@withContext

            val bndBytes: ByteArray = when (repAtyp) {
                1 -> ByteArray(4 + 2).also { upIn.readExact(it) }
                3 -> {
                    val len = upIn.read()
                    val domainBytes = ByteArray(len).also { upIn.readExact(it) }
                    val portBytes = ByteArray(2).also { upIn.readExact(it) }
                    byteArrayOf(len.toByte()) + domainBytes + portBytes
                }
                4 -> ByteArray(16 + 2).also { upIn.readExact(it) }
                else -> return@withContext
            }

            // Forward reply to client
            clientOut.write(byteArrayOf(5, repCode.toByte(), 0, repAtyp.toByte()) + bndBytes)
            clientOut.flush()
            if (repCode != 0) return@withContext

            // 4. Bidirectional streaming
            clientSocket.soTimeout = 0
            upstream.soTimeout = 0
            coroutineScope {
                val jobA = launch(Dispatchers.IO) {
                    try {
                        clientIn.copyTo(upOut)
                        upOut.flush()
                    } catch (_: IOException) {}
                }
                val jobB = launch(Dispatchers.IO) {
                    try {
                        upIn.copyTo(clientOut)
                        clientOut.flush()
                    } catch (_: IOException) {}
                }
                jobA.join()
                jobB.join()
            }
        } catch (_: IOException) {
        } finally {
            runCatching { clientSocket.close() }
            runCatching { upstreamSocket?.close() }
        }
    }

    fun isAlive(): Boolean = isRunning &&
        (proxyServer?.isClosed == false) &&
        (cfSession?.isAlive() == true) &&
        (urProcess?.isAlive != false)

    suspend fun stop() = withContext(Dispatchers.IO) {
        isRunning = false
        bootstrapProgress = -1
        try {
            runCatching { proxyServer?.close() }
            proxyJob?.cancelAndJoin()
            proxyJob = null
            proxyServer = null
        } finally {
            try {
                urProcess?.let { proc ->
                    runCatching {
                        if (proc.isAlive) {
                            proc.destroy()
                            delay(100)
                            if (proc.isAlive) proc.destroyForcibly()
                        }
                    }
                }
                urProcess = null
            } finally {
                cfSession?.stop()
                cfSession = null
            }
        }
    }

    companion object {
        internal const val BRIDGE_REFRESH_TIMEOUT_MILLIS = 1_000L

        private fun InputStream.readExact(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
            var read = 0
            while (read < length) {
                val count = this.read(buffer, offset + read, length - read)
                if (count < 0) throw EOFException("Unexpected EOF while reading socket")
                read += count
            }
        }

        internal suspend fun discoverBridgesWithTimeout(discover: suspend () -> List<String>): List<String> =
            withTimeoutOrNull(BRIDGE_REFRESH_TIMEOUT_MILLIS) {
                discover()
            } ?: emptyList()

        internal fun transportProcessesAlive(
            direct: Boolean,
            torAlive: Boolean,
            obfs4Alive: Boolean,
            cloudflaredAlive: Boolean = true
        ): Boolean = torAlive && cloudflaredAlive && (direct || obfs4Alive)

        /** Probe a public endpoint through the running proxy. */
        internal suspend fun probeExit(port: Int, host: String = "cp.cloudflare.com"): Unit = coroutineScope {
            val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            suspendCancellableCoroutine { continuation ->
                val probe = launch(Dispatchers.IO + CoroutineName("SaturnExitProbe")) {
                    try {
                        socket.use {
                            it.connect(InetSocketAddress.createUnresolved(host, 80), 10_000)
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

        internal suspend fun awaitExit(timeoutMillis: Long, probe: suspend () -> Unit): Boolean =
            withTimeoutOrNull(timeoutMillis) {
                var connected = false
                while (!connected) {
                    try {
                        probe()
                        connected = true
                    } catch (_: IOException) {
                        delay(500)
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

        internal suspend fun awaitBootstrap(timeoutMillis: Long, ready: () -> Boolean, alive: () -> Boolean): Boolean =
            withTimeoutOrNull(timeoutMillis) {
                while (!ready()) {
                    check(alive()) { "Saturn bootstrap failed" }
                    delay(100)
                }
                true
            } == true

        internal suspend fun testTunnelSocks5(
            proxyPort: Int,
            ipBytes: ByteArray = byteArrayOf(1, 1, 1, 1),
            targetPort: Int = 53
        ): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.soTimeout = 2500
                    socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 2500)
                    val output = socket.getOutputStream()
                    val input = socket.getInputStream()
                    output.write(byteArrayOf(5, 1, 0))
                    output.flush()
                    if (input.read() != 5 || input.read() != 0) return@runCatching false
                    output.write(byteArrayOf(5, 1, 0, 1, ipBytes[0], ipBytes[1], ipBytes[2], ipBytes[3],
                        (targetPort shr 8).toByte(), (targetPort and 0xFF).toByte()))
                    output.flush()
                    val ver = input.read()
                    val rep = input.read()
                    ver == 5 && rep == 0
                }
            }.getOrDefault(false)
        }

        internal suspend fun awaitTunnelReady(
            cfPort: Int,
            timeoutMillis: Long = 45_000L,
            alive: () -> Boolean = { true }
        ): Boolean = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                check(alive()) { "Cloudflare Tunnel process exited prematurely" }
                if (testTunnelSocks5(cfPort, byteArrayOf(1, 1, 1, 1), 53) ||
                    testTunnelSocks5(cfPort, byteArrayOf(1, 1, 1, 1), 443) ||
                    testTunnelSocks5(cfPort, byteArrayOf(1, 0, 0, 1), 53)) {
                    return@withTimeoutOrNull true
                }
                delay(500)
            }
            false
        } == true

        internal fun testSocketListening(port: Int): Boolean {
            return runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500)
                    true
                }
            }.getOrDefault(false)
        }

        internal suspend fun pollTunnelReady(
            port: Int,
            timeoutMillis: Long = 10_000L,
            alive: () -> Boolean = { true }
        ): Boolean = withTimeoutOrNull(timeoutMillis) {
            while (alive()) {
                if (testSocketListening(port)) return@withTimeoutOrNull true
                delay(250)
            }
            testSocketListening(port)
        } == true

        internal fun configuration(
            settings: SaturnSettings,
            directory: File,
            geoip: File,
            obfs4Proxy: String,
            port: Int,
            bridges: List<String>,
            ownerPid: Int,
            cloudflaredProxyPort: Int? = null,
            isDirect: Boolean = settings.usesDirectConnection()
        ): String {
            require(settings.isValid() && port in 1024..65535)
            val direct = isDirect || (settings.usesAutomaticBridges() && bridges.isEmpty())
            if (direct) {
                require(bridges.isEmpty() && cloudflaredProxyPort != null && cloudflaredProxyPort in 1024..65535 && cloudflaredProxyPort != port)
            } else {
                require(parseObfs4Cmethod("CMETHOD obfs4 socks5 $obfs4Proxy") != null && bridges.isNotEmpty())
            }
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
                appendLine("DownloadExtraInfo 0")
                appendLine("ClientUseIPv4 1")
                appendLine("ClientUseIPv6 0")
                appendLine("ClientPreferIPv6ORPort 0")
                appendLine("ClientPreferIPv6DirPort 0")
                appendLine("UseBridges ${if (direct) 0 else 1}")
                if (direct) appendLine("Socks5Proxy 127.0.0.1:$cloudflaredProxyPort")
                if (!direct) appendLine("ClientTransportPlugin obfs4 socks5 $obfs4Proxy")
                appendLine("RunAsDaemon 0")
                appendLine("SafeLogging 0")
                appendLine("ClientBootstrapConsensusAuthorityDownloadInitialDelay 0")
                appendLine("ClientBootstrapConsensusFallbackDownloadSchedule 0, 1, 2, 4, 8")
                appendLine("Log notice stdout")
                appendLine("__OwningControllerProcess $ownerPid")
                if (settings.country != "auto") appendLine("ExitNodes {${settings.country}}")
                bridges.forEach { appendLine("Bridge $it") }
            }
        }
    }
}
