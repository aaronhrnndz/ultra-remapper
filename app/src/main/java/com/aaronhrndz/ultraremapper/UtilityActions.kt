package com.aaronhrndz.ultraremapper

import com.aaronhrndz.ultraremapper.R

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.widget.Toast
import com.aaronhrndz.ultraremapper.adb.AdbMdnsDiscovery
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

object UtilityActions {

    private const val PREF_PARKING = "parking_prefs"
    private const val KEY_HAS_PARKING = "has_parking"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_PREV_TIMEOUT = "prev_screen_timeout"

    @Volatile
    private var adbEasyModeActive = false
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var wifiHighPerfLock: WifiManager.WifiLock? = null
    private val adbExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    var lastResolvedAdbPort: Int? = null

    @Volatile
    private var waterEjectRunning = false

    fun isAdbEasyModeEnabled(): Boolean = adbEasyModeActive

    // ------------------------------------------------------------------------
    // 1. PARKING SAVE & NAVIGATE TO CAR (GOOGLE MAPS WALKING)
    // ------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun saveParking(context: Context) {
        val appCtx = context.applicationContext
        val lm = appCtx.getSystemService(LocationManager::class.java)
        if (lm == null) {
            Toast.makeText(appCtx, appCtx.getString(R.string.toast_parking_no_gps), Toast.LENGTH_SHORT).show()
            return
        }

        // Try best recent location first (under 2 minutes old)
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var bestLocation: Location? = null
        val now = System.currentTimeMillis()
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p)
                if (loc != null && (now - loc.time) < 120_000L) {
                    if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                        bestLocation = loc
                    }
                }
            } catch (_: SecurityException) {
                Toast.makeText(appCtx, appCtx.getString(R.string.toast_parking_perm_needed), Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {
            }
        }

        if (bestLocation != null) {
            persistParking(appCtx, bestLocation.latitude, bestLocation.longitude)
            return
        }

        // Otherwise request fresh location fix
        Toast.makeText(appCtx, appCtx.getString(R.string.toast_parking_acquiring), Toast.LENGTH_SHORT).show()
        try {
            val provider = lm.getBestProvider(Criteria().apply { accuracy = Criteria.ACCURACY_FINE }, true)
                ?: LocationManager.GPS_PROVIDER
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    persistParking(appCtx, location.latitude, location.longitude)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            // Fallback after 8 seconds if indoors: use any available lastKnownLocation
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    lm.removeUpdates(listener)
                } catch (_: Exception) {}
            }, 8000L)
        } catch (_: SecurityException) {
            Toast.makeText(appCtx, appCtx.getString(R.string.toast_parking_perm_needed), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(appCtx, appCtx.getString(R.string.toast_parking_no_gps), Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistParking(context: Context, lat: Double, lon: Double) {
        context.getSharedPreferences(PREF_PARKING, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_PARKING, true)
            .putString(KEY_LAT, lat.toString())
            .putString(KEY_LON, lon.toString())
            .apply()
        vibrateDouble(context)
        Toast.makeText(context, context.getString(R.string.toast_parking_saved), Toast.LENGTH_SHORT).show()
    }

    fun navigateToParking(context: Context, backToHome: Boolean) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREF_PARKING, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_PARKING, false)) {
            Toast.makeText(appCtx, appCtx.getString(R.string.toast_parking_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val lat = prefs.getString(KEY_LAT, null) ?: return
        val lon = prefs.getString(KEY_LON, null) ?: return

        // Launch Google Maps Wear OS directly in walking navigation mode
        val navUri = Uri.parse("google.navigation:q=$lat,$lon&mode=w")
        val mapsIntent = Intent(Intent.ACTION_VIEW, navUri)
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { if (backToHome) addFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME) }
        try {
            appCtx.startActivity(mapsIntent)
            return
        } catch (_: Exception) {
        }

        // Fallback to geo: URI (opens OruxMaps / Google Maps / any installed map app)
        val geoUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Parking)")
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appCtx.startActivity(geoIntent)
        } catch (_: Exception) {
            Toast.makeText(appCtx, appCtx.getString(R.string.toast_maps_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------------
    // 2. ADB SIDELOAD / KEEP-AWAKE EASY MODE TOGGLE
    // ------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    fun toggleAdbEasyMode(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREF_PARKING, Context.MODE_PRIVATE)
        val pm = appCtx.getSystemService(PowerManager::class.java)
        val wm = appCtx.applicationContext.getSystemService(WifiManager::class.java)

        val isPersistedActive = prefs.getBoolean("keep_awake_active", false)
        if (!isPersistedActive) {
            prefs.edit().putBoolean("keep_awake_active", true).apply()
            adbEasyModeActive = true
            // 1. Acquire Screen Bright WakeLock so watch stays unlocked (no PIN re-prompt) & awake for 30 min
            try {
                screenWakeLock?.let { if (it.isHeld) it.release() }
                screenWakeLock = pm?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "UltraRemapper:AdbEasyWakeLock"
                )?.apply {
                    acquire(30 * 60 * 1000L)
                }
            } catch (_: Exception) {
            }

            // 2. Acquire High-Perf WifiLock so wireless ADB never drops
            try {
                wifiHighPerfLock?.let { if (it.isHeld) it.release() }
                wifiHighPerfLock = wm?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "UltraRemapper:AdbWifiLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
            }

            // 3. If WRITE_SETTINGS / WRITE_SECURE_SETTINGS granted, extend timeout & enable adb_wifi_enabled
            try {
                val curTimeout = Settings.System.getInt(appCtx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 15000)
                if (curTimeout < 1_800_000) {
                    prefs.edit().putInt(KEY_PREV_TIMEOUT, curTimeout).apply()
                }
                Settings.System.putInt(appCtx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 1_800_000)
            } catch (_: Exception) {
            }
            try {
                Settings.Global.putInt(appCtx.contentResolver, "adb_wifi_enabled", 1)
                Settings.Global.putInt(appCtx.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 7)
                Settings.Global.putInt(appCtx.contentResolver, "cw_disable_wifimediator", 1)
            } catch (_: Exception) {
            }

            vibrateDouble(appCtx)
            Toast.makeText(appCtx, appCtx.getString(R.string.toast_adb_mode_on), Toast.LENGTH_SHORT).show()
        } else {
            prefs.edit().putBoolean("keep_awake_active", false).apply()
            adbEasyModeActive = false
            try {
                screenWakeLock?.let { if (it.isHeld) it.release() }
                screenWakeLock = null
            } catch (_: Exception) {
            }
            try {
                wifiHighPerfLock?.let { if (it.isHeld) it.release() }
                wifiHighPerfLock = null
            } catch (_: Exception) {
            }
            try {
                val prev = prefs.getInt(KEY_PREV_TIMEOUT, 15000)
                Settings.System.putInt(appCtx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, prev)
            } catch (_: Exception) {
            }
            try {
                Settings.Global.putInt(appCtx.contentResolver, "cw_disable_wifimediator", 0)
            } catch (_: Exception) {
            }

            vibrateSingle(appCtx)
            Toast.makeText(appCtx, appCtx.getString(R.string.toast_adb_mode_off), Toast.LENGTH_SHORT).show()
        }
    }

    fun isWirelessAdbEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    fun getWatchWifiIpv4(context: Context): String? {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                @Suppress("DEPRECATION")
                val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
                for (net in networks.distinct()) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val lp = cm.getLinkProperties(net) ?: continue
                    for (linkAddr in lp.linkAddresses) {
                        val addr = linkAddr.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank() && host != "127.0.0.1" && host != "0.0.0.0" && !host.startsWith("192.168.167.")) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name?.lowercase() ?: ""
                if (!name.startsWith("wlan")) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "127.0.0.1" && host != "0.0.0.0" && !host.startsWith("192.168.167.")) {
                            return host
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun setWirelessAdbEnabled(context: Context, enabled: Boolean): Boolean {
        if (!SystemRouteHelper.hasSecureSettingsPermission(context)) return false
        val appCtx = context.applicationContext
        val wm = appCtx.getSystemService(WifiManager::class.java)
        try {
            if (enabled) {
                try {
                    if (wifiHighPerfLock?.isHeld != true) {
                        wifiHighPerfLock = wm?.createWifiLock(
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            "UltraRemapper:AdbWifiLock"
                        )?.apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                    }
                } catch (_: Exception) {}
                Settings.Global.putInt(appCtx.contentResolver, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putInt(appCtx.contentResolver, "adb_wifi_enabled", 1)
                Settings.Global.putInt(appCtx.contentResolver, "cw_disable_wifimediator", 1)
            } else {
                Settings.Global.putInt(appCtx.contentResolver, "adb_wifi_enabled", 0)
                lastResolvedAdbPort = null
                BottomButtonService.capturedConnectPort = null
                if (!adbEasyModeActive) {
                    try {
                        wifiHighPerfLock?.let { if (it.isHeld) it.release() }
                        wifiHighPerfLock = null
                    } catch (_: Exception) {}
                    try {
                        Settings.Global.putInt(appCtx.contentResolver, "cw_disable_wifimediator", 0)
                    } catch (_: Exception) {}
                }
            }
            WirelessAdbTileService.requestTileUpdate(appCtx)
            WirelessAdbComplicationService.requestUpdate(appCtx)
            CompanionSyncService.syncStateToDataLayer(appCtx)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun resolveAdbConnectPortBlocking(
        context: Context,
        timeoutMs: Long = 1500L,
        onLateResolved: ((Int) -> Unit)? = null
    ): Int? {
        val appCtx = context.applicationContext
        val localIp = getWatchWifiIpv4(appCtx)
        val port = queryMdnsSrvPortRaw("_adb-tls-connect._tcp.local", timeoutMs.toInt().coerceIn(400, 2500), localIp)
        if (port != null && port in 1024..65535) {
            lastResolvedAdbPort = port
            BottomButtonService.capturedConnectPort = port
            CompanionSyncService.syncStateToDataLayer(appCtx)
        }
        return lastResolvedAdbPort ?: BottomButtonService.capturedConnectPort
    }

    /**
     * Pure-UDP mDNS PTR query to 224.0.0.251:5353 for _adb-tls-connect._tcp.local.
     * Avoids Android NsdManager.discoverServices() which triggers the system NsdPickerActivity popup on Wear OS.
     */
    private fun queryMdnsSrvPortRaw(serviceType: String, timeoutMs: Int, localIp: String?): Int? {
        var socket: java.net.MulticastSocket? = null
        return try {
            val queryBytes = buildMdnsPtrQuery(serviceType)
            val group = java.net.InetAddress.getByName("224.0.0.251")
            val localhost = java.net.InetAddress.getByName("127.0.0.1")
            socket = java.net.MulticastSocket(5353).apply {
                reuseAddress = true
                soTimeout = timeoutMs
                try { joinGroup(group) } catch (_: Exception) {}
            }
            socket.send(java.net.DatagramPacket(queryBytes, queryBytes.size, group, 5353))
            try {
                socket.send(java.net.DatagramPacket(queryBytes, queryBytes.size, localhost, 5353))
            } catch (_: Exception) {}

            val buf = ByteArray(2048)
            val marker = "_adb-tls-connect".toByteArray(Charsets.US_ASCII)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val pkt = java.net.DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                val senderIp = pkt.address?.hostAddress
                if (senderIp != "127.0.0.1" && (localIp == null || senderIp != localIp)) continue
                if (!containsBytes(pkt.data, pkt.length, marker)) continue
                val port = parseMdnsSrvPort(pkt.data, pkt.length)
                if (port != null && port in 1024..65535) {
                    return port
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun containsBytes(data: ByteArray, len: Int, needle: ByteArray): Boolean {
        if (needle.isEmpty() || len < needle.size) return false
        outer@ for (i in 0..(len - needle.size)) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun buildMdnsPtrQuery(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Header: ID=0, Flags=0, QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(0) // root
        // QTYPE = PTR (12), QCLASS = IN (1)
        out.write(byteArrayOf(0, 12, 0, 1))
        return out.toByteArray()
    }

    private fun skipDnsName(data: ByteArray, len: Int, startPos: Int): Int {
        var pos = startPos
        while (pos < len) {
            val b = data[pos].toInt() and 0xFF
            if (b == 0) return pos + 1
            if ((b and 0xC0) == 0xC0) return pos + 2
            pos += 1 + b
        }
        return len
    }

    private fun parseMdnsSrvPort(data: ByteArray, len: Int): Int? {
        if (len < 12) return null
        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val nsCount = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
        val arCount = ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)

        var pos = 12
        for (i in 0 until qdCount) {
            pos = skipDnsName(data, len, pos) + 4
            if (pos > len) return null
        }
        val totalRr = anCount + nsCount + arCount
        for (i in 0 until totalRr) {
            pos = skipDnsName(data, len, pos)
            if (pos + 10 > len) return null
            val rType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val rdLength = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLength > len) return null
            // SRV record = 33; RDATA: priority(2) + weight(2) + port(2) + target
            if (rType == 33 && rdLength >= 6) {
                val port = ((data[pos + 4].toInt() and 0xFF) shl 8) or (data[pos + 5].toInt() and 0xFF)
                if (port in 1024..65535) return port
            }
            pos += rdLength
        }
        return null
    }

    fun openWirelessDebuggingScreen(context: Context, backToHome: Boolean = false) {
        val appCtx = context.applicationContext

        // 1. Trigger auto-toggle via Accessibility with solid delay
        BottomButtonService.instance?.autoToggleWirelessAdb()

        // 2. Open Developer Options / Wireless Debugging screen directly
        val devIntents = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent().setClassName(
                "com.google.android.apps.wearable.settings",
                "com.samsung.android.clockwork.settings.developer.StDevOptionsActivity"
            ),
            Intent("com.google.android.clockwork.settings.DEVELOPER_OPTIONS")
        )

        var launched = false
        val launchCtx: Context = BottomButtonService.instance ?: appCtx
        for (intent in devIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (backToHome) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                launchCtx.startActivity(intent)
                launched = true
                break
            } catch (_: Exception) {}
        }

        if (launched) {
            vibrateDouble(appCtx)
        } else {
            Toast.makeText(appCtx, appCtx.getString(R.string.toast_dev_options_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    fun openWirelessDebugging(context: Context, backToHome: Boolean = false) {
        val appCtx = context.applicationContext
        if (SystemRouteHelper.hasSecureSettingsPermission(appCtx)) {
            val targetOn = !isWirelessAdbEnabled(appCtx)
            if (setWirelessAdbEnabled(appCtx, targetOn)) {
                if (targetOn) {
                    vibrateDouble(appCtx)
                    adbExecutor.execute {
                        try { Thread.sleep(450L) } catch (_: Exception) {}
                        val ip = getWatchWifiIpv4(appCtx)
                        val port = resolveAdbConnectPortBlocking(appCtx, 1800L)
                        WirelessAdbTileService.requestTileUpdate(appCtx)
                        WirelessAdbComplicationService.requestUpdate(appCtx)
                        val mainHandler = Handler(Looper.getMainLooper())
                        mainHandler.post {
                            val msg = when {
                                ip != null && port != null -> appCtx.getString(R.string.toast_wireless_adb_on_addr, "$ip:$port")
                                ip != null -> appCtx.getString(R.string.toast_wireless_adb_on_addr, ip)
                                else -> appCtx.getString(R.string.toast_wireless_adb_on)
                            }
                            Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                            if (ip != null && port != null) {
                                mainHandler.postDelayed({
                                    Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                                }, 3200L)
                            }
                        }
                    }
                } else {
                    vibrateSingle(appCtx)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appCtx, appCtx.getString(R.string.toast_wireless_adb_off), Toast.LENGTH_LONG).show()
                    }
                }
                return
            }
        }
        openWirelessDebuggingScreen(context, backToHome)
    }

    // ------------------------------------------------------------------------
    // 4. LTE & MOBILE DATA TOGGLE
    // ------------------------------------------------------------------------

    fun toggleLteData(context: Context) {
        val appCtx = context.applicationContext
        val currentCell = try {
            Settings.Global.getInt(appCtx.contentResolver, "cell_on", 0)
        } catch (_: Exception) {
            0
        }
        val targetOn = currentCell == 0
        try {
            Settings.Global.putInt(appCtx.contentResolver, "cell_on", if (targetOn) 1 else 0)
            Settings.Global.putInt(appCtx.contentResolver, "mobile_data", if (targetOn) 1 else 0)
            if (targetOn) {
                vibrateDouble(appCtx)
                Toast.makeText(appCtx, appCtx.getString(R.string.toast_lte_on), Toast.LENGTH_SHORT).show()
            } else {
                vibrateSingle(appCtx)
                Toast.makeText(appCtx, appCtx.getString(R.string.toast_lte_off), Toast.LENGTH_SHORT).show()
            }
        } catch (_: SecurityException) {
            Toast.makeText(appCtx, appCtx.getString(R.string.toast_write_secure_needed), Toast.LENGTH_LONG).show()
            try {
                appCtx.startActivity(
                    Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------------
    // 4. WATER EJECT SPEAKER (165 Hz Sine Wave + Vibrate + Water Lock)
    // ------------------------------------------------------------------------

    fun ejectWater(context: Context) {
        val appCtx = context.applicationContext
        if (waterEjectRunning) return
        waterEjectRunning = true

        Toast.makeText(appCtx, appCtx.getString(R.string.toast_water_eject), Toast.LENGTH_SHORT).show()

        Thread {
            val audio = appCtx.getSystemService(AudioManager::class.java)
            val prevAlarm = audio?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 10
            val prevMusic = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10
            val prevRing = audio?.getStreamVolume(AudioManager.STREAM_RING) ?: 10
            val maxAlarm = audio?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 15
            val maxMusic = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            val maxRing = audio?.getStreamMaxVolume(AudioManager.STREAM_RING) ?: 15

            try {
                // Maximum hardware gain for water expulsion
                audio?.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                audio?.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                audio?.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)

                val sampleRate = 44100
                val totalSec = 10.0
                val totalSamples = (sampleRate * totalSec).toInt()
                val samples = ShortArray(totalSamples)

                // 10 distinct ejection cycles: 0.8s acoustic expulsion sweep + 0.2s pause
                // Sweep from 145 Hz to 240 Hz with smooth attack/decay envelope to prevent clipping distortion
                var phase = 0.0
                val cycleDuration = 1.0 // 1.0s total per cycle (0.8s tone, 0.2s gap)
                val toneDuration = 0.80

                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / sampleRate
                    val cyclePos = t % cycleDuration

                    if (cyclePos < toneDuration) {
                        val progress = cyclePos / toneDuration
                        // Acoustic sweep: starts deep at 145 Hz, sweeps up to 240 Hz
                        val currentFreq = 145.0 + (95.0 * progress)
                        val freqStep = 2.0 * PI * currentFreq / sampleRate
                        phase += freqStep

                        // Soft envelope: 15ms fade-in, 30ms fade-out (eliminates harsh clicking/buzz)
                        val envelope = when {
                            cyclePos < 0.015 -> cyclePos / 0.015
                            cyclePos > (toneDuration - 0.030) -> (toneDuration - cyclePos) / 0.030
                            else -> 1.0
                        }

                        // Fundamental resonant tone + subtle 2nd harmonic (adds diaphragm physical thrust)
                        val wave = sin(phase) + 0.22 * sin(phase * 2.0)
                        val normalized = (wave / 1.22 * envelope).coerceIn(-1.0, 1.0)
                        samples[i] = (normalized * 32767.0).toInt().toShort()
                    } else {
                        samples[i] = 0
                    }
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.setVolume(1.0f)
                track.write(samples, 0, samples.size)
                track.play()

                // Synchronized haptic motor pulses: 10 bursts matching the 1.0s cycles
                val vib = appCtx.getSystemService(Vibrator::class.java)
                val timing = LongArray(21) // 0 wait, 10 pairs of (800ms vibrate, 200ms sleep)
                timing[0] = 0
                for (c in 0 until 10) {
                    timing[1 + c * 2] = 800
                    timing[2 + c * 2] = 200
                }
                vib?.vibrate(VibrationEffect.createWaveform(timing, -1))

                Thread.sleep((totalSec * 1000).toLong())
                track.stop()
                track.release()
            } catch (_: Exception) {
            } finally {
                try {
                    audio?.setStreamVolume(AudioManager.STREAM_ALARM, prevAlarm, 0)
                    audio?.setStreamVolume(AudioManager.STREAM_MUSIC, prevMusic, 0)
                    audio?.setStreamVolume(AudioManager.STREAM_RING, prevRing, 0)
                } catch (_: Exception) {
                }
                waterEjectRunning = false
            }
        }.start()
    }

    private fun vibrateSingle(context: Context) {
        try {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun vibrateDouble(context: Context) {
        try {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 50, 60), -1))
        } catch (_: Exception) {
        }
    }

    fun hasHardwareActionButton(context: Context): Boolean = true
}

