package com.aaronhrndz.ultraremapper

import com.aaronhrndz.ultraremapper.R

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.aaronhrndz.ultraremapper.adb.LocalAdbClient
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ReleaseUpdate(
    val versionName: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val releaseNotes: String,
    val isPrerelease: Boolean = false
)

object UpdateChecker {
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val FORGEJO_LAN_BASE = "http://192.168.1.8:3000"
    private const val FORGEJO_REPO = "Aaronhrndz/ultra-remapper"
    private const val CODEBERG_MANIFEST_URL = "https://codeberg.org/Aaronhrndz/ultra-remapper/raw/branch/main/update.json"
    private const val CODEBERG_RELEASES_API = "https://codeberg.org/api/v1/repos/Aaronhrndz/ultra-remapper/releases?limit=5"
    private val GITHUB_REPOS = listOf("Aaronhrndz/ultra-remapper", "aaronhrnndz/ultra-remapper")
    
    private const val PREFS_NAME = "update_checker"
    private const val KEY_LAST_CHECK = "last_check_time"
    private const val KEY_NOTIFIED_VER = "last_notified_version"
    private const val KEY_BETA_CHANNEL = "beta_channel_enabled"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val AUTO_CHECK_COOLDOWN_MS = 15_000L

    private val checkStateLock = Any()
    private var isCheckInFlight = false
    private val inFlightCallbacks = mutableListOf<(ReleaseUpdate?) -> Unit>()
    private var lastCompletedCheckAtMs = 0L
    private var lastCompletedCheckUpdate: ReleaseUpdate? = null

    private val wifiStateLock = Any()
    private var wifiRefCount = 0
    private var savedWifiMediator = 0
    private var savedWifiOn = 1
    private var savedWifiEnabled = true
    private var modifiedWifiSettings = false

    fun isBetaChannel(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BETA_CHANNEL, false)
    }

    fun setBetaChannel(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BETA_CHANNEL, enabled)
            .apply()
    }

    fun scheduleDailyCheck(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val intent = Intent(context, UpdateReceiver::class.java).apply {
                action = UpdateReceiver.ACTION_CHECK_UPDATE
            }
            val pi = android.app.PendingIntent.getBroadcast(
                context,
                901,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + CHECK_INTERVAL_MS
            // Inexact inexact repeating or setAndAllowWhileIdle for battery friendliness
            am.setInexactRepeating(
                android.app.AlarmManager.RTC,
                triggerAt,
                CHECK_INTERVAL_MS,
                pi
            )
        } catch (_: Exception) {}
    }

    /**
     * Called in background (e.g. from AlarmManager or button presses).
     * Only queries network if at least 24 hours have passed since last check.
     */
    fun checkOpportunistic(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) return

        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        check(context) { update ->
            if (update != null) {
                val lastNotified = prefs.getString(KEY_NOTIFIED_VER, "")
                if (lastNotified != update.versionName) {
                    prefs.edit().putString(KEY_NOTIFIED_VER, update.versionName).apply()
                    UpdateReceiver.showUpdateNotification(context, update)
                }
            }
        }
    }

    fun check(context: Context, force: Boolean = false, onResult: (ReleaseUpdate?) -> Unit) {
        val appCtx = context.applicationContext
        val currentVersion = try {
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
        val cleanCurrent = currentVersion.removePrefix("v")
        val userAgent = "UltraRemapper/$currentVersion"

        synchronized(checkStateLock) {
            if (!force) {
                if (isCheckInFlight) {
                    inFlightCallbacks.add(onResult)
                    return
                }
                val now = System.currentTimeMillis()
                if (lastCompletedCheckAtMs > 0L && (now - lastCompletedCheckAtMs) < AUTO_CHECK_COOLDOWN_MS) {
                    val cached = lastCompletedCheckUpdate?.takeIf { isNewerVersion(it.versionName, cleanCurrent) }
                    mainHandler.post { onResult(cached) }
                    return
                }
            }
            isCheckInFlight = true
            inFlightCallbacks.add(onResult)
        }

        executor.execute {
            val finalUpdate: ReleaseUpdate? = try {
                val isBeta = isBetaChannel(appCtx)
                var best: ReleaseUpdate? = null

                // 1. Wake temporary Wi-Fi if not outdoors on standalone LTE -> check Forgejo LAN directly
                withTemporaryWifi(appCtx, timeoutMs = 12_000L) { wifiNet ->
                    val apiUrl = "$FORGEJO_LAN_BASE/api/v1/repos/$FORGEJO_REPO/releases?limit=5"
                    val networksToTry = buildList<Network?> {
                        if (wifiNet != null) add(wifiNet)
                        if (!hasActiveCellularNetwork(appCtx)) add(null)
                    }
                    for (net in networksToTry) {
                        fetchUrlText(
                            apiUrl,
                            userAgent,
                            "application/json",
                            connectTimeoutMs = 6000,
                            readTimeoutMs = 6000,
                            network = net
                        )?.let { body ->
                            try {
                                val arr = org.json.JSONArray(body)
                                for (i in 0 until arr.length()) {
                                    val obj = arr.optJSONObject(i) ?: continue
                                    if (obj.optBoolean("draft", false)) continue
                                    val parsed = parseRelease(obj, FORGEJO_REPO, FORGEJO_LAN_BASE) ?: continue
                                    if (!isBeta && parsed.isPrerelease) continue
                                    best = pickHigherVersion(best, parsed)
                                }
                            } catch (_: Exception) {}
                        }
                        if (!best?.apkDownloadUrl.isNullOrEmpty()) break
                    }
                }

                if (best?.apkDownloadUrl.isNullOrEmpty()) {
                    // 2. Public Internet Fallback: Codeberg Manifest, GitHub, Codeberg Releases
                    fetchUrlText(CODEBERG_MANIFEST_URL, userAgent)?.let { body ->
                        parseManifestUpdate(body, isBeta)?.let { best = pickHigherVersion(best, it) }
                    }

                    for (repo in GITHUB_REPOS) {
                        val apiUrl = if (isBeta) {
                            "https://api.github.com/repos/$repo/releases?per_page=5"
                        } else {
                            "https://api.github.com/repos/$repo/releases/latest"
                        }
                        fetchUrlText(apiUrl, userAgent, "application/vnd.github+json")?.let { body ->
                            try {
                                if (isBeta) {
                                    val arr = org.json.JSONArray(body)
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.optJSONObject(i) ?: continue
                                        if (obj.optBoolean("draft", false)) continue
                                        best = pickHigherVersion(best, parseRelease(obj, repo))
                                    }
                                } else {
                                    best = pickHigherVersion(best, parseRelease(JSONObject(body), repo))
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    fetchUrlText(CODEBERG_RELEASES_API, userAgent, "application/json")?.let { body ->
                        try {
                            val arr = org.json.JSONArray(body)
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                if (obj.optBoolean("draft", false)) continue
                                val parsed = parseRelease(obj, FORGEJO_REPO) ?: continue
                                if (!isBeta && parsed.isPrerelease) continue
                                best = pickHigherVersion(best, parsed)
                            }
                        } catch (_: Exception) {}
                    }
                }

                best?.takeIf { isNewerVersion(it.versionName, cleanCurrent) }
            } catch (_: Exception) {
                null
            }

            val callbacks = synchronized(checkStateLock) {
                lastCompletedCheckAtMs = System.currentTimeMillis()
                lastCompletedCheckUpdate = finalUpdate
                isCheckInFlight = false
                inFlightCallbacks.toList().also { inFlightCallbacks.clear() }
            }
            mainHandler.post {
                for (cb in callbacks) {
                    cb(finalUpdate)
                }
            }
        }
    }

    private fun parseManifestUpdate(body: String, isBeta: Boolean): ReleaseUpdate? {
        return try {
            val json = JSONObject(body)
            val ver = json.optString("version", "").removePrefix("v").trim()
            val isPre = json.optBoolean("isPrerelease", false)
            if (ver.isNotEmpty() && (isBeta || !isPre)) {
                ReleaseUpdate(
                    versionName = ver,
                    htmlUrl = json.optString("htmlUrl", "https://codeberg.org/Aaronhrndz/ultra-remapper/releases/latest"),
                    apkDownloadUrl = json.optString("watchApkUrl", "").takeIf { it.isNotEmpty() },
                    releaseNotes = json.optString("notes", "").trim(),
                    isPrerelease = isPre
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun findActiveWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun hasActiveCellularNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    @Suppress("DEPRECATION")
    private fun <T> withTemporaryWifi(
        context: Context,
        timeoutMs: Long,
        onWakingWifi: (() -> Unit)? = null,
        block: (Network?) -> T
    ): T {
        val appCtx = context.applicationContext
        findActiveWifiNetwork(appCtx)?.let { return block(it) }
        if (hasActiveCellularNetwork(appCtx)) {
            return block(null)
        }

        onWakingWifi?.invoke()
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wm = appCtx.getSystemService(WifiManager::class.java)

        synchronized(wifiStateLock) {
            if (wifiRefCount == 0) {
                savedWifiMediator = try {
                    Settings.Global.getInt(appCtx.contentResolver, "cw_disable_wifimediator", 0)
                } catch (_: Exception) {
                    0
                }
                savedWifiOn = try {
                    Settings.Global.getInt(appCtx.contentResolver, "wifi_on", 1)
                } catch (_: Exception) {
                    1
                }
                savedWifiEnabled = try {
                    wm?.isWifiEnabled != false
                } catch (_: Exception) {
                    true
                }
                modifiedWifiSettings = false
                if (SystemRouteHelper.hasSecureSettingsPermission(appCtx)) {
                    if (savedWifiMediator == 0) {
                        try {
                            Settings.Global.putInt(appCtx.contentResolver, "cw_disable_wifimediator", 1)
                            modifiedWifiSettings = true
                        } catch (_: Throwable) {}
                    }
                    if (savedWifiOn == 0 || !savedWifiEnabled) {
                        try {
                            Settings.Global.putInt(appCtx.contentResolver, "wifi_on", 1)
                            modifiedWifiSettings = true
                        } catch (_: Throwable) {}
                    }
                }
                if (savedWifiOn == 0 || !savedWifiEnabled) {
                    try { wm?.setWifiEnabled(true) } catch (_: Throwable) {}
                    try {
                        LocalAdbClient.activeInstance?.takeIf { it.isConnected }?.exec("svc wifi enable")
                    } catch (_: Throwable) {}
                }
            }
            wifiRefCount++
        }

        val wifiLock = try {
            wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ultraremapper:ota_wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {
            null
        }

        var acquiredNet: Network? = findActiveWifiNetwork(appCtx)
        var netCallback: ConnectivityManager.NetworkCallback? = null
        if (acquiredNet == null && cm != null) {
            try {
                val latch = CountDownLatch(1)
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        acquiredNet = network
                        latch.countDown()
                    }
                    override fun onUnavailable() {
                        latch.countDown()
                    }
                }
                netCallback = cb
                cm.requestNetwork(req, cb, timeoutMs.toInt())
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                if (acquiredNet == null) {
                    acquiredNet = findActiveWifiNetwork(appCtx)
                }
            } catch (_: Throwable) {}
        }

        try {
            return block(acquiredNet)
        } finally {
            if (netCallback != null && cm != null) {
                try { cm.unregisterNetworkCallback(netCallback) } catch (_: Throwable) {}
            }
            try { if (wifiLock?.isHeld == true) wifiLock.release() } catch (_: Throwable) {}
            synchronized(wifiStateLock) {
                wifiRefCount = (wifiRefCount - 1).coerceAtLeast(0)
                if (wifiRefCount == 0 && modifiedWifiSettings) {
                    modifiedWifiSettings = false
                    if (!UtilityActions.isWirelessAdbEnabled(appCtx)) {
                        if (savedWifiMediator == 0) {
                            try {
                                Settings.Global.putInt(appCtx.contentResolver, "cw_disable_wifimediator", savedWifiMediator)
                            } catch (_: Throwable) {}
                        }
                        if (savedWifiOn == 0 || !savedWifiEnabled) {
                            try {
                                Settings.Global.putInt(appCtx.contentResolver, "wifi_on", savedWifiOn)
                            } catch (_: Throwable) {}
                            try { wm?.setWifiEnabled(false) } catch (_: Throwable) {}
                            try {
                                LocalAdbClient.activeInstance?.takeIf { it.isConnected }?.exec("svc wifi disable")
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }
    }

    private fun fetchUrlText(
        urlStr: String,
        userAgent: String,
        accept: String? = null,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 3000,
        network: Network? = null
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            val rawConn = if (network != null) network.openConnection(url) else url.openConnection()
            conn = (rawConn as HttpURLConnection).apply {
                requestMethod = "GET"
                if (accept != null) setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", userAgent)
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w("UpdateChecker", "HTTP ${conn.responseCode} for $urlStr")
                null
            }
        } catch (e: Exception) {
            Log.w("UpdateChecker", "fetchUrlText failed: $urlStr net=$network -> ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun pickHigherVersion(current: ReleaseUpdate?, candidate: ReleaseUpdate?): ReleaseUpdate? {
        if (candidate == null) return current
        if (current == null || isNewerVersion(candidate.versionName, current.versionName)) return candidate
        return current
    }

    private fun parseRelease(json: JSONObject, fallbackRepo: String, overrideOrigin: String? = null): ReleaseUpdate? {
        val tag = json.optString("tag_name", "").removePrefix("v").trim()
        if (tag.isEmpty()) return null
        val rawHtmlUrl = json.optString("html_url", "https://github.com/$fallbackRepo/releases/latest")
        val htmlUrl = if (overrideOrigin != null) rawHtmlUrl.replace(Regex("^https?://[^/]+"), overrideOrigin) else rawHtmlUrl
        val notes = json.optString("body", "").trim()
        var apkUrl: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true) &&
                    !name.contains("companion", ignoreCase = true) &&
                    !name.contains("mobile", ignoreCase = true)
                ) {
                    val rawDl = asset.optString("browser_download_url", "").takeIf { it.isNotEmpty() }
                    apkUrl = if (overrideOrigin != null && rawDl != null) {
                        rawDl.replace(Regex("^https?://[^/]+"), overrideOrigin)
                    } else {
                        rawDl
                    }
                    break
                }
            }
        }
        val isPre = json.optBoolean("prerelease", false)
        return ReleaseUpdate(tag, htmlUrl, apkUrl, notes, isPre)
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote == local) return false
        val rBase = remote.substringBefore("-").trim()
        val lBase = local.substringBefore("-").trim()
        fun parseParts(v: String): List<Int> =
            Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()
        val rParts = parseParts(rBase)
        val lParts = parseParts(lBase)
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        val rPre = remote.contains("-")
        val lPre = local.contains("-")
        if (rPre && !lPre) return false
        if (!rPre && lPre) return true
        if (rPre && lPre) {
            return remote.substringAfter("-").compareTo(local.substringAfter("-")) > 0
        }
        return false
    }

    fun openReleaseOnPhone(context: Context, update: ReleaseUpdate) {
        try {
            val remoteHelper = RemoteActivityHelper(context, executor)
            val intent = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse(update.htmlUrl))
            remoteHelper.startRemoteActivity(intent)
            Toast.makeText(context, context.getString(R.string.update_opened_phone), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.toast_open_phone_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun downloadAndInstallOnWatch(
        context: Context,
        update: ReleaseUpdate,
        onProgress: (progressFraction: Float, statusText: String) -> Unit = { _, _ -> }
    ) {
        val appCtx = context.applicationContext
        val apkUrl = update.apkDownloadUrl
        if (apkUrl.isNullOrEmpty()) {
            openReleaseOnPhone(context, update)
            return
        }

        executor.execute {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultraremapper:ota_download")?.apply {
                setReferenceCounted(false)
                try { acquire(5 * 60 * 1000L) } catch (_: Throwable) {}
            }
            try {
                withTemporaryWifi(
                    appCtx,
                    timeoutMs = 20_000L,
                    onWakingWifi = { mainHandler.post { onProgress(0f, "Activando Wi-Fi...") } }
                ) { wifiNet ->
                    if (wifiNet == null && apkUrl.startsWith(FORGEJO_LAN_BASE)) {
                        throw java.io.IOException("Wi-Fi no conectado")
                    }
                    mainHandler.post { onProgress(0f, appCtx.getString(R.string.update_downloading)) }
                    val networksToTry = buildList {
                        if (wifiNet != null) add(wifiNet)
                        if (!apkUrl.startsWith(FORGEJO_LAN_BASE)) add(null)
                    }
                    var lastError: Exception? = null
                    var succeeded = false
                    for (net in networksToTry) {
                        try {
                            val timeoutMs = if (net != null) 12000 else 15000
                            val conn = openDownloadConnection(apkUrl, net, timeoutMs)
                            if (conn.responseCode == 200) {
                                installFromConnection(appCtx, conn, update.versionName, onProgress)
                                succeeded = true
                                break
                            } else {
                                val code = conn.responseCode
                                try { conn.disconnect() } catch (_: Exception) {}
                                lastError = java.io.IOException("HTTP $code")
                            }
                        } catch (e: Exception) {
                            lastError = e
                        }
                    }
                    if (!succeeded && lastError != null) {
                        throw lastError
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(appCtx, appCtx.getString(R.string.toast_download_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                    onProgress(0f, "")
                }
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun openDownloadConnection(urlStr: String, network: Network?, connectTimeoutMs: Int): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (true) {
            val url = URL(currentUrl)
            val rawConn = if (network != null) network.openConnection(url) else url.openConnection()
            val conn = (rawConn as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = connectTimeoutMs
                readTimeout = 30000
                setRequestProperty("User-Agent", "UltraRemapper-OTA")
            }
            if (conn.responseCode in 300..399 && redirects < 4) {
                val redirectUrl = conn.getHeaderField("Location")
                if (!redirectUrl.isNullOrEmpty()) {
                    try { conn.disconnect() } catch (_: Exception) {}
                    currentUrl = redirectUrl
                    redirects++
                    continue
                }
            }
            return conn
        }
    }

    private fun copyStreamToApkFile(
        context: Context,
        input: InputStream,
        version: String,
        totalLength: Long,
        onProgress: ((Float, String) -> Unit)?
    ): File {
        val cacheDir = File(context.cacheDir, "updates").apply { mkdirs() }
        cacheDir.listFiles()?.forEach { file ->
            try { file.delete() } catch (_: Exception) {}
        }
        val apkFile = File(cacheDir, "ultra-remapper-v$version.apk")
        var downloaded = 0L
        var lastProgressUpdate = 0L
        apkFile.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                downloaded += bytesRead
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 100L && onProgress != null) {
                    lastProgressUpdate = now
                    val fraction = if (totalLength > 0) downloaded.toFloat() / totalLength.toFloat() else 0f
                    val percent = if (totalLength > 0) (fraction * 100).toInt().coerceIn(0, 100) else 0
                    val status = if (totalLength > 0) "$percent%" else "${downloaded / 1024} KB"
                    mainHandler.post { onProgress(fraction, status) }
                }
            }
        }
        return apkFile
    }

    private fun installFromConnection(
        context: Context,
        conn: HttpURLConnection,
        version: String,
        onProgress: (progressFraction: Float, statusText: String) -> Unit
    ) {
        val totalLength = conn.contentLength.toLong()
        val apkFile = conn.inputStream.use { input ->
            copyStreamToApkFile(context, input, version, totalLength, onProgress)
        }
        launchApkInstaller(context, apkFile, onProgress)
    }

    private fun launchApkInstaller(
        context: Context,
        apkFile: File,
        onProgress: (progressFraction: Float, statusText: String) -> Unit
    ) {
        mainHandler.post {
            onProgress(1f, context.getString(R.string.update_launching))
            try {
                val apkUri: Uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.toast_install_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
            onProgress(0f, "")
        }
    }
}
