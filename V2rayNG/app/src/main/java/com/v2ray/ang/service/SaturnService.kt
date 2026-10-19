package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Network
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.SaturnSession
import com.v2ray.ang.core.AetherLifecycle
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.SoftReference
import java.io.File

/** Saturn owns its child process; Xray remains the daemon's tunnel/routing authority. */
class SaturnService : VpnService(), ServiceControl {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("SaturnService"))
    private val lifecycle = AetherLifecycle()
    private var setupJob: Job? = null
    private var watchdog: Job? = null
    private var cleanupJob: Job? = null
    private var session: SaturnSession? = null
    private var vpnOwner: CoreVpnService.OwnedTransportVpn? = null
    @Volatile
    private var ownsCore = false
    private var guid = ""
    private var mode = ""
    @Volatile
    private var startupSettings: com.v2ray.ang.dto.entities.SaturnSettings? = null
    @Volatile
    private var internalProxyPort: Int? = null
    private var receiverRegistered = false
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Once Xray owns the command receiver, it delegates Saturn commands back here.
            if (ownsCore) return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    val progress = session?.currentProgress ?: -1
                    val progressStr = if (progress in 0..100) "$progress%" else ""
                    if (!lifecycle.isStopping()) MessageHelper.sendMsg2UI(this@SaturnService,
                        if (isConnecting()) AppConfig.MSG_STATE_CONNECTING else AppConfig.MSG_STATE_NOT_RUNNING,
                        if (isConnecting()) progressStr else "")
                }
                AppConfig.MSG_STATE_STOP -> stopService()
                AppConfig.MSG_STATE_RESTART -> {
                    restartOwnedService()
                    if (isOrderedBroadcast) resultCode = android.app.Activity.RESULT_OK
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.startForeground(this, NotificationChannelType.SATURN,
            getString(R.string.saturn_title), getString(R.string.saturn_starting))
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, commandReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE), Utils.receiverFlags())
            receiverRegistered = true
        }
        if (!lifecycle.canStart(setupJob?.isActive == true, ownsCore)) return START_NOT_STICKY
        setupJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            MessageHelper.sendMsg2UI(this@SaturnService, AppConfig.MSG_STATE_CONNECTING, "")
            var failure = R.string.saturn_start_error
            try {
                // API 26 adds the process APIs used to guarantee bounded child-process cleanup.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    failure = R.string.saturn_unavailable
                    error("Unsupported Android version")
                }
                check(!CoreServiceManager.isRunning()) { "Another core is already running" }
                guid = MmkvManager.getSelectServer().orEmpty()
                mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE).orEmpty()
                if (SettingsManager.isRootMode()) {
                    failure = R.string.saturn_root_unsupported
                    error("Saturn root mode is unsupported")
                }
                if (!File(applicationInfo.nativeLibraryDir, "libsaturn-core.so").isFile ||
                    !File(applicationInfo.nativeLibraryDir, "libsaturn-tor.so").isFile ||
                    !File(applicationInfo.nativeLibraryDir, "libsaturn-obfs4.so").isFile) {
                    failure = R.string.saturn_unavailable
                    error("Saturn core is not packaged for this ABI")
                }
                val profile = MmkvManager.decodeServerConfig(guid) ?: error("Invalid selected profile")
                check(profile.configType == EConfigType.SATURN)
                val settings = profile.saturnSettings ?: error("Saturn settings unavailable")
                failure = R.string.saturn_invalid_settings
                check(settings.isValid())
                CoreServiceManager.serviceControl = SoftReference(this@SaturnService)
                session = SaturnSession()
                failure = R.string.saturn_start_error
                val progressCallback: (Int) -> Unit = { pct ->
                    val progressStr = "$pct%"
                    NotificationHelper.startForeground(this@SaturnService, NotificationChannelType.SATURN,
                        getString(R.string.saturn_title), "${getString(R.string.connection_connecting)} ($progressStr)")
                    MessageHelper.sendMsg2UI(this@SaturnService, AppConfig.MSG_STATE_CONNECTING, progressStr)
                }
                internalProxyPort = session!!.start(this@SaturnService, guid, settings,
                    setOf(SettingsManager.getSocksPort(), SettingsManager.getHttpPort()), serviceScope, progressCallback)
                startupSettings = settings
                if (SettingsManager.isVpnMode()) {
                    failure = R.string.saturn_permission_error
                    check(prepare(this@SaturnService) == null) { "VPN permission missing" }
                    failure = R.string.saturn_vpn_error
                    vpnOwner = CoreVpnService.OwnedTransportVpn(this@SaturnService) { Builder() }
                    vpnOwner!!.establish(profile.remarks)
                }
                ownsCore = true
                failure = R.string.saturn_routing_error
                check(CoreServiceManager.startCoreLoop(vpnOwner?.descriptor, notifyStarted = false))
                failure = R.string.saturn_tunnel_error
                vpnOwner?.startTunnel()
                currentCoroutineContext().ensureActive()
                MessageHelper.sendMsg2UI(this@SaturnService, AppConfig.MSG_STATE_START_SUCCESS, "")
                watchdog = serviceScope.launch {
                    while (isActive && !lifecycle.isStopping()) {
                        delay(1_000)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && session?.isAlive() != true) {
                            LogUtil.e(AppConfig.TAG, "Saturn: mode=$mode guid=$guid child process exited", IllegalStateException("Core exited"))
                            MessageHelper.sendMsg2UI(this@SaturnService, AppConfig.MSG_STATE_CONNECTING, "")
                            restartOwnedService()
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val stage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) session?.stage else null
                val exitCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) session?.nativeExitCode else null
                LogUtil.e(AppConfig.TAG, "Saturn: mode=$mode guid=$guid startup stage=$stage exit=$exitCode failed",
                    e)
                val message = if (failure != R.string.saturn_start_error) getString(failure) else when (stage) {
                    SaturnSession.Stage.NATIVE -> getString(R.string.saturn_native_error)
                    SaturnSession.Stage.DNS -> getString(R.string.saturn_dns_error)
                    SaturnSession.Stage.BRIDGES -> getString(R.string.saturn_bridges_error)
                    SaturnSession.Stage.BOOTSTRAP -> if (exitCode != null) getString(R.string.saturn_core_exit, exitCode)
                        else getString(R.string.saturn_bootstrap_error)
                    SaturnSession.Stage.EXIT -> getString(R.string.saturn_exit_error)
                    else -> getString(R.string.saturn_start_error)
                }
                MessageHelper.sendMsg2UI(this@SaturnService, AppConfig.MSG_STATE_START_FAILURE, message)
                stopService()
            }
        }
        setupJob?.start()
        // Recreate resources on the Android-owned null-intent/SERVICE_INTERFACE start paths.
        return START_NOT_STICKY
    }

    override fun isConnecting(): Boolean = lifecycle.isPreparing(setupJob?.isActive == true, ownsCore)

    override fun stopService() {
        if (!lifecycle.beginStop()) return
        cleanupJob = serviceScope.launch {
            setupJob?.cancelAndJoin()
            val released = withContext(NonCancellable) { cleanup() }
            if (released) MessageHelper.sendMsg2UI(this@SaturnService, AppConfig.MSG_STATE_STOP_SUCCESS, "")
            stopSelf()
        }
    }

    fun hasReadyProfile(profileGuid: String, settings: com.v2ray.ang.dto.entities.SaturnSettings?): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !lifecycle.isStopping() && profileGuid == guid &&
            (settings ?: com.v2ray.ang.dto.entities.SaturnSettings()) == startupSettings && session?.isAlive() == true

    override fun saturnProxyPort(guid: String): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && guid == this.guid)
            lifecycle.readyPort(guid, this.guid, session?.isAlive() == true, internalProxyPort) else null

    override fun restartOwnedService(): Boolean {
        if (!lifecycle.beginRestart()) return true
        cleanupJob = serviceScope.launch {
            setupJob?.cancelAndJoin()
            val released = withContext(NonCancellable) { cleanup() }
            if (!released) {
                lifecycle.beginStop()
                stopSelf()
                return@launch
            }
            if (isActive) {
                try {
                    val restarted = lifecycle.completeRestart { LauncherManager.startService(this@SaturnService) }
                    if (!restarted || MmkvManager.decodeServerConfig(MmkvManager.getSelectServer().orEmpty())?.configType != EConfigType.SATURN) stopSelf()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Saturn: mode=$mode guid=$guid restart dispatch failed", IllegalStateException(e.javaClass.simpleName))
                    stopSelf()
                }
            }
        }
        return true
    }

    private suspend fun cleanup() = withContext(Dispatchers.IO) {
        var released = true
        suspend fun release(operation: String, action: suspend () -> Unit): Boolean {
            try {
                action()
                return true
            } catch (e: Exception) {
                released = false
                LogUtil.e(AppConfig.TAG, "Saturn: mode=$mode guid=$guid cleanup $operation failed", e)
                return false
            }
        }
        watchdog?.cancel()
        watchdog = null
        release("HEV") { vpnOwner?.stopTunnel() }
        if (ownsCore && CoreServiceManager.serviceControl?.get() === this@SaturnService) {
            if (release("Xray") { check(CoreServiceManager.stopCoreLoop(awaitNativeStop = true)) }) ownsCore = false
        } else ownsCore = false
        if (release("VPN interface") { vpnOwner?.close() }) vpnOwner = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (release("child process") { session?.stop() }) session = null
        }
        startupSettings = null
        internalProxyPort = null
        released
    }

    override fun onRevoke() { stopService() }

    override fun onDestroy() {
        lifecycle.beginStop()
        setupJob?.cancel()
        // Await cleanup for at most 5 seconds: closing a listener before root/VPN teardown
        // could send traffic to a dead proxy. Native/file work runs on Dispatchers.IO.
        try {
            runBlocking { withTimeout(5_000) { withContext(Dispatchers.IO) { setupJob?.join(); cleanupJob?.join(); cleanup() } } }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Saturn: mode=$mode guid=$guid destroy cleanup failed", e)
        } finally {
            serviceScope.cancel()
            if (receiverRegistered) {
                unregisterReceiver(commandReceiver)
                receiverRegistered = false
            }
            super.onDestroy()
        }
    }

    override fun getService(): Service = this
    override fun startService() = Unit
    override fun vpnProtect(socket: Int): Boolean = if (SettingsManager.isVpnMode()) protect(socket) else true
    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean =
        if (SettingsManager.isVpnMode()) super<VpnService>.setUnderlyingNetworks(networks) else false
    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }
}
