package com.aaronhrndz.ultraremapper

import com.aaronhrndz.ultraremapper.R

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.wear.remote.interactions.RemoteActivityHelper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

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
    private const val FORGEJO_TAILSCALE_BASE = "https://forgejo.tailc4b32.ts.net"
    private const val FORGEJO_LAN_RELEASES_API = "$FORGEJO_LAN_BASE/api/v1/repos/Aaronhrndz/ultra-remapper/releases?limit=5"
    private const val CODEBERG_MANIFEST_URL = "https://codeberg.org/Aaronhrndz/ultra-remapper/raw/branch/main/update.json"
    private const val CODEBERG_RELEASES_API = "https://codeberg.org/api/v1/repos/Aaronhrndz/ultra-remapper/releases?limit=5"
    private val GITHUB_REPOS = listOf("Aaronhrndz/ultra-remapper", "aaronhrnndz/ultra-remapper")
    
    private const val PREFS_NAME = "update_checker"
    private const val KEY_LAST_CHECK = "last_check_time"
    private const val KEY_NOTIFIED_VER = "last_notified_version"
    private const val KEY_BETA_CHANNEL = "beta_channel_enabled"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

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

    fun check(context: Context, onResult: (ReleaseUpdate?) -> Unit) {
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
        val cleanCurrent = currentVersion.removePrefix("v")
        val userAgent = "UltraRemapper/$currentVersion"

        executor.execute {
            val isBeta = isBetaChannel(context)
            var best: ReleaseUpdate? = null

            // 1. Private Forgejo OTA Sources FIRST (LAN, then Tailscale/remote, matching Antigravity-on-wear-os)
            for ((apiUrl, originBase) in listOf(
                FORGEJO_LAN_RELEASES_API to FORGEJO_LAN_BASE,
                "$FORGEJO_TAILSCALE_BASE/api/v1/repos/Aaronhrndz/ultra-remapper/releases?limit=5" to FORGEJO_TAILSCALE_BASE
            )) {
                var forgejoBody = fetchUrlText(
                    apiUrl,
                    userAgent,
                    "application/json",
                    connectTimeoutMs = 3000,
                    readTimeoutMs = 3000
                )
                if (forgejoBody == null && originBase == FORGEJO_LAN_BASE) {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val wifiNet = cm?.allNetworks?.firstOrNull { net ->
                        cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    }
                    if (wifiNet != null) {
                        forgejoBody = fetchUrlText(
                            apiUrl,
                            userAgent,
                            "application/json",
                            connectTimeoutMs = 3000,
                            readTimeoutMs = 3000,
                            network = wifiNet
                        )
                    }
                }
                forgejoBody?.let { body ->
                    try {
                        val arr = org.json.JSONArray(body)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            if (obj.optBoolean("draft", false)) continue
                            val parsed = parseRelease(obj, "Aaronhrndz/ultra-remapper", originBase) ?: continue
                            best = pickHigherVersion(best, parsed)
                        }
                    } catch (_: Exception) {}
                }
                if (!best?.apkDownloadUrl.isNullOrEmpty()) {
                    val forgejoUpdate = best?.takeIf { isNewerVersion(it.versionName, cleanCurrent) }
                    mainHandler.post { onResult(forgejoUpdate) }
                    return@execute
                }
            }

            // 2. Codeberg Master Manifest (update.json)
            fetchUrlText(CODEBERG_MANIFEST_URL, userAgent)?.let { body ->
                try {
                    val json = JSONObject(body)
                    val ver = json.optString("version", "").removePrefix("v").trim()
                    val isPre = json.optBoolean("isPrerelease", false)
                    if (ver.isNotEmpty() && (isBeta || !isPre)) {
                        val candidate = ReleaseUpdate(
                            versionName = ver,
                            htmlUrl = json.optString("htmlUrl", "https://codeberg.org/Aaronhrndz/ultra-remapper/releases/latest"),
                            apkDownloadUrl = json.optString("watchApkUrl", "").takeIf { it.isNotEmpty() },
                            releaseNotes = json.optString("notes", "").trim(),
                            isPrerelease = isPre
                        )
                        best = pickHigherVersion(best, candidate)
                    }
                } catch (_: Exception) {}
            }

            // 2 & 3. GitHub Primary (Aaronhrndz) & Secondary (aaronhrnndz)
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

            // 4. Codeberg Releases API
            fetchUrlText(CODEBERG_RELEASES_API, userAgent, "application/json")?.let { body ->
                try {
                    val arr = org.json.JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (obj.optBoolean("draft", false)) continue
                        val parsed = parseRelease(obj, "Aaronhrndz/ultra-remapper") ?: continue
                        if (!isBeta && parsed.isPrerelease) continue
                        best = pickHigherVersion(best, parsed)
                    }
                } catch (_: Exception) {}
            }

            val finalUpdate = best?.takeIf { isNewerVersion(it.versionName, cleanCurrent) }
            mainHandler.post { onResult(finalUpdate) }
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
                null
            }
        } catch (_: Exception) {
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
            val connectivityManager = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            var activeNetwork: Network? = null
            var networkCallback: ConnectivityManager.NetworkCallback? = null
            val networkWaitLatch = java.util.concurrent.CountDownLatch(1)

            if (connectivityManager != null) {
                try {
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()

                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            activeNetwork = network
                            networkWaitLatch.countDown()
                        }
                        override fun onUnavailable() {
                            networkWaitLatch.countDown()
                        }
                    }
                    networkCallback = callback
                    connectivityManager.requestNetwork(request, callback, 5000)
                    networkWaitLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Throwable) {
                    // Fallback to default network route if Wi-Fi request fails
                }
            }

            try {
                mainHandler.post { onProgress(0f, appCtx.getString(R.string.update_downloading)) }
                val attempts = buildDownloadAttempts(apkUrl, activeNetwork)
                var lastError: Exception? = null
                var succeeded = false
                for ((candidateUrl, candidateNetwork, candidateConnectTimeout) in attempts) {
                    try {
                        val conn = openDownloadConnection(candidateUrl, candidateNetwork, candidateConnectTimeout)
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
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(appCtx, appCtx.getString(R.string.toast_download_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                    onProgress(0f, "")
                }
            } finally {
                if (networkCallback != null && connectivityManager != null) {
                    try {
                        connectivityManager.unregisterNetworkCallback(networkCallback)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun buildDownloadAttempts(apkUrl: String, activeNetwork: Network?): List<Triple<String, Network?, Int>> {
        val isForgejo = apkUrl.startsWith(FORGEJO_TAILSCALE_BASE) || apkUrl.startsWith(FORGEJO_LAN_BASE)
        if (!isForgejo) {
            return buildList {
                if (activeNetwork != null) add(Triple(apkUrl, activeNetwork, 15000))
                add(Triple(apkUrl, null, 15000))
            }
        }
        val lanUrl = if (apkUrl.startsWith(FORGEJO_TAILSCALE_BASE)) {
            apkUrl.replaceFirst(FORGEJO_TAILSCALE_BASE, FORGEJO_LAN_BASE)
        } else apkUrl
        return buildList {
            if (activeNetwork != null) add(Triple(lanUrl, activeNetwork, 8000))
            add(Triple(lanUrl, null, 8000))
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

    private fun installFromConnection(
        context: Context,
        conn: HttpURLConnection,
        version: String,
        onProgress: (progressFraction: Float, statusText: String) -> Unit
    ) {
        val cacheDir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clean up any old, leftover or partial APKs from previous update attempts to preserve flash storage
        cacheDir.listFiles()?.forEach { file ->
            try { file.delete() } catch (_: Exception) {}
        }
        val apkFile = File(cacheDir, "ultra-remapper-v$version.apk")

        val totalLength = conn.contentLength.toLong()
        var downloaded = 0L

        conn.inputStream.use { input ->
            apkFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var lastProgressUpdate = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 100L) {
                        lastProgressUpdate = now
                        val fraction = if (totalLength > 0) downloaded.toFloat() / totalLength.toFloat() else 0f
                        val percent = if (totalLength > 0) (fraction * 100).toInt() else 0
                        val status = if (totalLength > 0) "$percent%" else "${downloaded / 1024} KB"
                        mainHandler.post { onProgress(fraction, status) }
                    }
                }
            }
        }

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
