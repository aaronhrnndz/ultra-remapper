package com.aaronhrndz.ultraremapper

import com.aaronhrndz.ultraremapper.R

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
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
    val isPrerelease: Boolean = false,
    val viaPhoneProxy: Boolean = false
)

object UpdateChecker {
    private val executor = Executors.newCachedThreadPool()
    private val streamExecutor = Executors.newSingleThreadExecutor()
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

    private val checkProxyLock = Any()
    @Volatile private var pendingCheckLatch: CountDownLatch? = null
    @Volatile private var pendingCheckPayload: String? = null

    @Volatile private var proxyChannelOpenLatch: CountDownLatch? = null
    @Volatile private var proxyTransferDoneLatch: CountDownLatch? = null
    @Volatile private var proxyTransferError: String? = null
    @Volatile private var proxyTransferredFile: File? = null
    @Volatile private var activeTransferProgressCb: ((Float, String) -> Unit)? = null

    fun onPhoneOtaCheckResponse(payload: String) {
        pendingCheckPayload = payload
        pendingCheckLatch?.countDown()
    }

    fun onPhoneOtaApkError(errorMsg: String) {
        proxyTransferError = errorMsg.ifBlank { "Error Bluetooth en móvil" }
        proxyChannelOpenLatch?.countDown()
        proxyTransferDoneLatch?.countDown()
    }

    fun onPhoneOtaApkChannelOpened(context: Context, channel: ChannelClient.Channel) {
        val appCtx = context.applicationContext
        proxyChannelOpenLatch?.countDown()
        val suffix = channel.path.removePrefix(CompanionSyncService.PATH_OTA_APK_STREAM_PREFIX)
        val slashIdx = suffix.lastIndexOf('/')
        val version = if (slashIdx > 0) suffix.substring(0, slashIdx) else "latest"
        val expectedSize = if (slashIdx > 0) suffix.substring(slashIdx + 1).toLongOrNull() ?: -1L else -1L

        streamExecutor.execute {
            val channelClient = Wearable.getChannelClient(appCtx)
            try {
                val apkFile = Tasks.await(channelClient.getInputStream(channel), 15, TimeUnit.SECONDS).use { input ->
                    copyStreamToApkFile(appCtx, input, version, expectedSize, activeTransferProgressCb)
                }
                if (apkFile.length() < 100_000L || (expectedSize > 0L && apkFile.length() < expectedSize)) {
                    proxyTransferError = "APK incompleto (${apkFile.length()}/$expectedSize)"
                    try { apkFile.delete() } catch (_: Exception) {}
                } else {
                    proxyTransferredFile = apkFile
                }
            } catch (e: Exception) {
                proxyTransferError = e.localizedMessage ?: "Error recibiendo APK"
            } finally {
                try { Tasks.await(channelClient.close(channel), 5, TimeUnit.SECONDS) } catch (_: Exception) {}
                proxyTransferDoneLatch?.countDown()
            }
        }
    }

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
            val appCtx = context.applicationContext
            val isBeta = isBetaChannel(appCtx)
            var best: ReleaseUpdate? = null
            val wifiNet = findActiveWifiNetwork(appCtx)

            // 1. Direct Forgejo LAN ONLY when watch has active Wi-Fi
            if (wifiNet != null) {
                val apiUrl = "$FORGEJO_LAN_BASE/api/v1/repos/$FORGEJO_REPO/releases?limit=5"
                fetchUrlText(
                    apiUrl,
                    userAgent,
                    "application/json",
                    connectTimeoutMs = 2000,
                    readTimeoutMs = 3000,
                    network = wifiNet
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
                if (!best?.apkDownloadUrl.isNullOrEmpty()) {
                    val forgejoUpdate = best?.takeIf { isNewerVersion(it.versionName, cleanCurrent) }
                    mainHandler.post { onResult(forgejoUpdate) }
                    return@execute
                }
            }

            // 2. Phone Companion Bluetooth OTA Proxy (handles Forgejo LAN & Internet via phone without Wi-Fi/Tailscale on watch)
            requestPhoneOtaCheck(appCtx, isBeta)?.let { proxyUpdate ->
                if (!proxyUpdate.apkDownloadUrl.isNullOrEmpty()) {
                    val checkedUpdate = proxyUpdate.takeIf { isNewerVersion(it.versionName, cleanCurrent) }
                    mainHandler.post { onResult(checkedUpdate) }
                    return@execute
                }
            }

            // 3. Public Internet Fallback: Codeberg Manifest, GitHub, Codeberg Releases
            fetchUrlText(CODEBERG_MANIFEST_URL, userAgent)?.let { body ->
                parseManifestUpdate(body, isBeta)?.let { best = pickHigherVersion(best, it) }
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

    private fun findActiveWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun requestPhoneOtaCheck(context: Context, isBeta: Boolean): ReleaseUpdate? = synchronized(checkProxyLock) {
        return try {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 3, TimeUnit.SECONDS)
            if (nodes.isNullOrEmpty()) return@synchronized null

            val latch = CountDownLatch(1)
            pendingCheckPayload = null
            pendingCheckLatch = latch

            val reqBytes = JSONObject().apply { put("isBeta", isBeta) }.toString().toByteArray(Charsets.UTF_8)
            val msgClient = Wearable.getMessageClient(context)
            var sentAny = false
            for (node in nodes) {
                try {
                    Tasks.await(msgClient.sendMessage(node.id, CompanionSyncService.PATH_OTA_CHECK_REQ, reqBytes), 4, TimeUnit.SECONDS)
                    sentAny = true
                } catch (_: Exception) {}
            }
            if (!sentAny) {
                pendingCheckLatch = null
                return@synchronized null
            }

            latch.await(6, TimeUnit.SECONDS)
            val rawPayload = pendingCheckPayload
            pendingCheckLatch = null
            if (rawPayload.isNullOrBlank()) return@synchronized null

            val obj = JSONObject(rawPayload)
            if (!obj.optBoolean("found", false)) return@synchronized null
            val ver = obj.optString("versionName", "").trim()
            val apkUrl = obj.optString("apkDownloadUrl", "").takeIf { it.isNotEmpty() }
            if (ver.isEmpty() || apkUrl == null) return@synchronized null
            ReleaseUpdate(
                versionName = ver,
                htmlUrl = obj.optString("htmlUrl", FORGEJO_LAN_BASE),
                apkDownloadUrl = apkUrl,
                releaseNotes = obj.optString("releaseNotes", ""),
                isPrerelease = obj.optBoolean("isPrerelease", false),
                viaPhoneProxy = true
            )
        } catch (_: Exception) {
            pendingCheckLatch = null
            null
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
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ultraremapper:ota_download")?.apply {
                setReferenceCounted(false)
                try { acquire(5 * 60 * 1000L) } catch (_: Throwable) {}
            }
            try {
                mainHandler.post { onProgress(0f, appCtx.getString(R.string.update_downloading)) }
                val wifiNet = findActiveWifiNetwork(appCtx)
                val usePhoneProxy = update.viaPhoneProxy ||
                    (wifiNet == null && apkUrl.startsWith(FORGEJO_LAN_BASE))

                if (usePhoneProxy) {
                    downloadViaPhoneProxy(appCtx, update, apkUrl, onProgress)
                } else {
                    val networksToTry = buildList {
                        if (wifiNet != null) add(wifiNet)
                        add(null)
                    }
                    var lastError: Exception? = null
                    var succeeded = false
                    for (net in networksToTry) {
                        try {
                            val conn = openDownloadConnection(apkUrl, net, 12000)
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

    private fun downloadViaPhoneProxy(
        appCtx: Context,
        update: ReleaseUpdate,
        apkUrl: String,
        onProgress: (Float, String) -> Unit
    ) {
        val nodes = Tasks.await(Wearable.getNodeClient(appCtx).connectedNodes, 4, TimeUnit.SECONDS)
        if (nodes.isNullOrEmpty()) {
            throw java.io.IOException("Móvil desconectado")
        }

        val openLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        proxyTransferError = null
        proxyTransferredFile = null
        activeTransferProgressCb = onProgress
        proxyChannelOpenLatch = openLatch
        proxyTransferDoneLatch = doneLatch

        try {
            val reqBytes = JSONObject().apply {
                put("apkUrl", apkUrl)
                put("versionName", update.versionName)
            }.toString().toByteArray(Charsets.UTF_8)

            val msgClient = Wearable.getMessageClient(appCtx)
            var sent = false
            for (node in nodes) {
                try {
                    Tasks.await(msgClient.sendMessage(node.id, CompanionSyncService.PATH_OTA_APK_REQ, reqBytes), 5, TimeUnit.SECONDS)
                    sent = true
                } catch (_: Exception) {}
            }
            if (!sent) throw java.io.IOException("No se pudo contactar con móvil")

            if (!openLatch.await(25, TimeUnit.SECONDS)) {
                throw java.io.IOException("Tiempo agotado esperando APK del móvil")
            }
            proxyTransferError?.let { throw java.io.IOException(it) }

            if (!doneLatch.await(5, TimeUnit.MINUTES)) {
                throw java.io.IOException("Transferencia Bluetooth agotada")
            }
            proxyTransferError?.let { throw java.io.IOException(it) }

            val apkFile = proxyTransferredFile ?: throw java.io.IOException("APK no recibida")
            launchApkInstaller(appCtx, apkFile, onProgress)
        } finally {
            activeTransferProgressCb = null
            proxyChannelOpenLatch = null
            proxyTransferDoneLatch = null
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
