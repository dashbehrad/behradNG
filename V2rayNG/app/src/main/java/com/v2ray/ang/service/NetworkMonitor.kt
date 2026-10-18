package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Used from Android P generally and Android O for Saturn, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] is invoked on a background thread after the debounce window and may block.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: () -> Unit,
    private val logContext: String,
    private val watchLinkChanges: Boolean = false,
    private val debounceMillis: Long = 1000L,
) {
    private val callbackGate = Any()
    private val state = HandoverState<Network>()
    // This scope belongs to this registration, rather than the daemon's full lifetime.
    // unregister() cancels it before releasing the framework callback.
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("NetworkMonitor"))
    private var handoverJob: Job? = null
    private var registered = false

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(callbackGate) {
                if (!registered) return
                val revision = state.available(network)
                updateUnderlying(arrayOf(network))
                if (revision != null) scheduleHandover(revision)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(callbackGate) {
                if (registered && state.isCurrent(network)) updateUnderlying(arrayOf(network))
            }
        }

        override fun onLost(network: Network) {
            synchronized(callbackGate) {
                if (!registered || !state.lost(network)) return
                handoverJob?.cancel()
                handoverJob = null
                updateUnderlying(null)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (!watchLinkChanges) return
            synchronized(callbackGate) {
                if (!registered) return
                state.propertiesChanged(network, linkProperties.linkAddresses.map { it.toString() }.toSet(),
                    linkProperties.dnsServers.mapNotNull { it.hostAddress }.toSet())?.let(::scheduleHandover)
            }
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        synchronized(callbackGate) {
            if (registered || state.closed) return
            registered = true
            try {
                connectivity.requestNetwork(request, callback)
            } catch (e: Exception) {
                registered = false
                state.close()
                monitorScope.cancel()
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: $logContext phase=start operation=register failed", e)
            }
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        val wasRegistered = synchronized(callbackGate) {
            state.close()
            handoverJob?.cancel()
            handoverJob = null
            monitorScope.cancel()
            registered.also { registered = false }
        }
        if (!wasRegistered) return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: $logContext phase=stop operation=unregister failed", e)
        }
    }

    private fun updateUnderlying(networks: Array<Network>?) {
        try {
            onUnderlyingNetworksChanged(networks)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "NetworkMonitor: $logContext phase=handover operation=update underlying network failed", e)
        }
    }

    private fun scheduleHandover(revision: Long) {
        handoverJob?.cancel()
        handoverJob = monitorScope.launch {
            try {
                delay(debounceMillis)
                // The same gate closes registration and enters the callback. Once unregister()
                // returns, neither queued framework events nor a queued handover can enter.
                synchronized(callbackGate) {
                    if (registered && state.canHandover(revision)) onHandover()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: $logContext phase=handover operation=reconnect failed", e)
            }
        }
    }

    /** Deterministic observation state, owned and guarded by this monitor's callback gate. */
    internal class HandoverState<T> {
        var closed = false
            private set
        private var upstream: T? = null
        private var seenNetwork = false
        private var revision = 0L
        private var properties: Pair<Set<String>, Set<String>>? = null

        fun isCurrent(network: T): Boolean = !closed && upstream == network

        fun available(network: T): Long? {
            if (closed || upstream == network) return null
            val handover = seenNetwork
            upstream = network
            seenNetwork = true
            properties = null
            revision++
            return revision.takeIf { handover }
        }

        fun lost(network: T): Boolean {
            if (!isCurrent(network)) return false
            upstream = null
            properties = null
            revision++
            return true
        }

        fun propertiesChanged(network: T, addresses: Set<String>, dns: Set<String>): Long? {
            if (!isCurrent(network)) return null
            val previous = properties
            properties = addresses.toSet() to dns.toSet()
            if (previous == null || previous == properties) return null
            revision++
            return revision
        }

        fun canHandover(scheduledRevision: Long): Boolean =
            !closed && upstream != null && revision == scheduledRevision

        fun close() {
            closed = true
            upstream = null
            properties = null
            revision++
        }
    }
}
