package com.aaronhrndz.ultraremapper.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Discovers Wireless Debugging ADB Connect (_adb-tls-connect._tcp) and
 * Pairing (_adb-tls-pairing._tcp) services on the local Wi-Fi network via mDNS.
 * Uses thread-safe serialized resolution and Android 14+ ServiceInfoCallback.
 */
class AdbClient(private val context: Context) {

    private val TAG = "UltraAdbMdns"

    data class DiscoveredEndpoint(
        val name: String,
        val host: String,
        val port: Int,
        val isPairing: Boolean
    )

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectListener: NsdManager.DiscoveryListener? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null

    private fun getLocalDeviceIps(): Set<String> {
        val ips = mutableSetOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return ips
            for (nif in interfaces) {
                for (addr in nif.inetAddresses) {
                    val host = addr.hostAddress
                    if (!host.isNullOrBlank()) ips.add(host)
                }
            }
        } catch (_: Exception) {}
        return ips
    }

    fun startDiscovery(wantPairing: Boolean, onFound: (DiscoveredEndpoint) -> Unit) {
        stopDiscovery()
        val manager = nsdManager ?: return

        if (wantPairing) {
            pairingListener = createListener(isPairing = true, onFound = onFound)
            try {
                manager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingListener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting pairing discovery: ${e.message}")
            }
        } else {
            connectListener = createListener(isPairing = false, onFound = onFound)
            try {
                manager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting connect discovery: ${e.message}")
            }
        }
    }

    private fun createListener(
        isPairing: Boolean,
        onFound: (DiscoveredEndpoint) -> Unit
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                resolveExecutor.execute {
                    try {
                        val localIps = getLocalDeviceIps()
                        @Suppress("DEPRECATION")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val preAddrs = service.hostAddresses
                            if (preAddrs.isNotEmpty() && preAddrs.all { it.isLoopbackAddress || localIps.contains(it.hostAddress) }) {
                                return@execute
                            }
                        } else {
                            val preHost = service.host
                            if (preHost != null && (preHost.isLoopbackAddress || localIps.contains(preHost.hostAddress))) {
                                return@execute
                            }
                        }

                        val latch = java.util.concurrent.CountDownLatch(1)
                        @Suppress("DEPRECATION")
                        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                latch.countDown()
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val resolvedIps = getLocalDeviceIps()
                                val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    val ipv4 = serviceInfo.hostAddresses.firstOrNull {
                                        !it.isLoopbackAddress && it is Inet4Address && !resolvedIps.contains(it.hostAddress)
                                    } ?: serviceInfo.hostAddresses.firstOrNull {
                                        !it.isLoopbackAddress && !(it.hostAddress?.contains(":") ?: true) && !resolvedIps.contains(it.hostAddress)
                                    }
                                    ipv4?.hostAddress
                                } else {
                                    @Suppress("DEPRECATION")
                                    val inetAddr = serviceInfo.host
                                    if (inetAddr != null && !inetAddr.isLoopbackAddress && !resolvedIps.contains(inetAddr.hostAddress) &&
                                        (inetAddr is Inet4Address || !(inetAddr.hostAddress?.contains(":") ?: true))
                                    ) {
                                        inetAddr.hostAddress
                                    } else {
                                        null
                                    }
                                }
                                if (!host.isNullOrBlank() && !host.startsWith("192.168.167.")) {
                                    val port = serviceInfo.port
                                    val name = serviceInfo.serviceName ?: "Galaxy Watch"
                                    mainHandler.post {
                                        onFound(DiscoveredEndpoint(name, host, port, isPairing))
                                    }
                                }
                                latch.countDown()
                            }
                        })
                        latch.await(2, TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun stopDiscovery() {
        val manager = nsdManager ?: return
        connectListener?.let {
            try { manager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        pairingListener?.let {
            try { manager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        connectListener = null
        pairingListener = null
    }
}
