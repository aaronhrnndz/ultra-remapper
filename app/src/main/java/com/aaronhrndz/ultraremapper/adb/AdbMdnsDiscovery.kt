package com.aaronhrndz.ultraremapper.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Discovers local Wireless Debugging ADB and Pairing ports announced via mDNS on localhost.
 * Resolves ports in background to feed the wizard automatically.
 */
class AdbMdnsDiscovery(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var discoveredConnectPort: Int? = null
        private set

    @Volatile
    var discoveredPairingPort: Int? = null
        private set

    private var connectListener: NsdManager.DiscoveryListener? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null

    private fun getLocalWatchIps(): Set<String> {
        val ips = mutableSetOf("127.0.0.1", "::1")
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return ips
            for (nif in interfaces) {
                for (addr in nif.inetAddresses) {
                    val host = addr.hostAddress?.substringBefore("%")
                    if (!host.isNullOrBlank()) ips.add(host)
                }
            }
        } catch (_: Exception) {}
        return ips
    }

    private fun isLocalWatchService(info: NsdServiceInfo): Boolean {
        val localIps = getLocalWatchIps()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.any {
                it.isLoopbackAddress || localIps.contains(it.hostAddress?.substringBefore("%"))
            }
        } else {
            @Suppress("DEPRECATION")
            val host = info.host
            host != null && (host.isLoopbackAddress || localIps.contains(host.hostAddress?.substringBefore("%")))
        }
    }

    fun startDiscovery(wantPairing: Boolean, onPortFound: (isPairing: Boolean, port: Int) -> Unit) {
        val manager = nsdManager ?: return
        stopDiscovery()

        if (wantPairing) {
            // Discover ONLY ADB Pairing service: _adb-tls-pairing._tcp
            pairingListener = createListener { info ->
                discoveredPairingPort = info.port
                mainHandler.post { onPortFound(true, info.port) }
            }
            try {
                manager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingListener)
            } catch (_: Exception) {}
        } else {
            // Discover ONLY ADB Connect service: _adb-tls-connect._tcp
            connectListener = createListener { info ->
                discoveredConnectPort = info.port
                mainHandler.post { onPortFound(false, info.port) }
            }
            try {
                manager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener)
            } catch (_: Exception) {}
        }
    }

    private fun createListener(onResolved: (NsdServiceInfo) -> Unit): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveExecutor.execute {
                    try {
                        val latch = java.util.concurrent.CountDownLatch(1)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            // Android 14+ / Wear OS 5+ thread-safe resolve API
                            nsdManager?.registerServiceInfoCallback(
                                serviceInfo,
                                resolveExecutor,
                                object : NsdManager.ServiceInfoCallback {
                                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                        latch.countDown()
                                    }
                                    override fun onServiceUpdated(resolvedInfo: NsdServiceInfo) {
                                        if (isLocalWatchService(resolvedInfo)) {
                                            onResolved(resolvedInfo)
                                        }
                                        try { nsdManager.unregisterServiceInfoCallback(this) } catch (_: Exception) {}
                                        latch.countDown()
                                    }
                                    override fun onServiceLost() {
                                        latch.countDown()
                                    }
                                    override fun onServiceInfoCallbackUnregistered() {}
                                }
                            )
                            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                        } else {
                            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                    latch.countDown()
                                }
                                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                    if (isLocalWatchService(resolvedInfo)) {
                                        onResolved(resolvedInfo)
                                    }
                                    latch.countDown()
                                }
                            })
                            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    } catch (_: Exception) {}
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
    }

    fun stopDiscovery() {
        val manager = nsdManager ?: return
        try { connectListener?.let { manager.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { pairingListener?.let { manager.stopServiceDiscovery(it) } } catch (_: Exception) {}
        connectListener = null
        pairingListener = null
    }
}
