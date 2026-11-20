package com.aaronhrndz.ultraremapper.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pure event-driven WearableListenerService on the Phone.
 * Zero idle battery usage: Android OS wakes this service only when the user presses
 * a button mapped to a Phone Action on the watch, or when the watch requests an OTA proxy check/transfer.
 */
class PhoneActionListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        super.onMessageReceived(event)
        when (event.path) {
            PATH_PHONE_ACTION -> {
                val payload = String(event.data, Charsets.UTF_8).trim()
                executePhoneAction(applicationContext, payload)
            }
            PATH_OTA_CHECK_REQ -> {
                val payload = String(event.data, Charsets.UTF_8).trim()
                handleOtaCheckRequest(applicationContext, event.sourceNodeId, payload)
            }
            PATH_OTA_APK_REQ -> {
                val payload = String(event.data, Charsets.UTF_8).trim()
                handleOtaApkRequest(applicationContext, event.sourceNodeId, payload)
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        try {
            for (event in dataEvents) {
                if (event.dataItem.uri.path == PATH_PHONE_ACTION) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val action = dataMap.getString("action")
                    val ts = dataMap.getLong("ts", 0L)
                    if (!action.isNullOrBlank()) {
                        executePhoneAction(applicationContext, action, ts)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val PATH_PHONE_ACTION = "/ultra_remapper/phone_action"
        const val PATH_OTA_CHECK_REQ = "/ultra_remapper/ota_check_req"
        const val PATH_OTA_CHECK_RES = "/ultra_remapper/ota_check_res"
        const val PATH_OTA_APK_REQ = "/ultra_remapper/ota_apk_req"
        const val PATH_OTA_APK_ERR = "/ultra_remapper/ota_apk_err"
        const val PATH_OTA_APK_STREAM_PREFIX = "/ultra_remapper/ota_apk_stream/"
        private const val ACTION_STOP_FIND_PHONE = "com.aaronhrndz.ultraremapper.STOP_FIND_PHONE"
        private const val FIND_NOTIF_CHANNEL_ID = "find_my_phone_channel"
        private const val FIND_NOTIF_ID = 9401

        private val otaExecutor = Executors.newSingleThreadExecutor()
        @Volatile
        private var otaWakeLock: PowerManager.WakeLock? = null

        private fun acquireOtaWakeLock(context: Context, timeoutMs: Long) {
            try {
                val pm = context.getSystemService(PowerManager::class.java) ?: return
                val wl = otaWakeLock ?: pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "UltraRemapper:OtaProxyWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    otaWakeLock = this
                }
                wl.acquire(timeoutMs)
            } catch (_: Exception) {}
        }

        private fun releaseOtaWakeLock() {
            try {
                if (otaWakeLock?.isHeld == true) otaWakeLock?.release()
            } catch (_: Exception) {}
        }

        private fun handleOtaCheckRequest(context: Context, sourceNodeId: String, payloadStr: String) {
            val appCtx = context.applicationContext
            acquireOtaWakeLock(appCtx, 15_000L)
            val isBeta = try {
                JSONObject(payloadStr).optBoolean("isBeta", false)
            } catch (_: Exception) {
                UpdateChecker.isBetaChannel(appCtx)
            }
            otaExecutor.execute {
                try {
                    UpdateChecker.checkAll(appCtx, isBetaOverride = isBeta) { _, latestRel ->
                        otaExecutor.execute {
                            try {
                                val watchApk = latestRel?.watchApkUrl?.takeIf {
                                    it.isNotBlank() && !it.startsWith("asset://")
                                }
                                val resObj = JSONObject().apply {
                                    if (latestRel != null && watchApk != null) {
                                        put("found", true)
                                        put("versionName", latestRel.versionName)
                                        put("htmlUrl", latestRel.htmlUrl)
                                        put("apkDownloadUrl", watchApk)
                                        put("releaseNotes", latestRel.releaseNotes)
                                        put("isPrerelease", latestRel.isPrerelease)
                                    } else {
                                        put("found", false)
                                    }
                                }
                                Tasks.await(
                                    Wearable.getMessageClient(appCtx).sendMessage(
                                        sourceNodeId,
                                        PATH_OTA_CHECK_RES,
                                        resObj.toString().toByteArray(Charsets.UTF_8)
                                    ),
                                    10,
                                    TimeUnit.SECONDS
                                )
                            } catch (_: Exception) {
                            } finally {
                                releaseOtaWakeLock()
                            }
                        }
                    }
                } catch (_: Exception) {
                    releaseOtaWakeLock()
                }
            }
        }

        private fun handleOtaApkRequest(context: Context, sourceNodeId: String, payloadStr: String) {
            val appCtx = context.applicationContext
            acquireOtaWakeLock(appCtx, 180_000L)
            val obj = try { JSONObject(payloadStr) } catch (_: Exception) { null }
            val apkUrl = obj?.optString("apkUrl", "")?.trim().orEmpty()
            val versionName = obj?.optString("versionName", "latest")?.trim()
                ?.replace("/", "_")
                ?.ifBlank { "latest" } ?: "latest"

            fun sendApkError(msg: String) {
                otaExecutor.execute {
                    try {
                        Tasks.await(
                            Wearable.getMessageClient(appCtx).sendMessage(
                                sourceNodeId,
                                PATH_OTA_APK_ERR,
                                msg.toByteArray(Charsets.UTF_8)
                            ),
                            10,
                            TimeUnit.SECONDS
                        )
                    } catch (_: Exception) {
                    } finally {
                        releaseOtaWakeLock()
                    }
                }
            }

            if (apkUrl.isEmpty() || apkUrl.startsWith("asset://")) {
                sendApkError("URL OTA inválida")
                return
            }

            UpdateChecker.downloadApkToFile(
                context = appCtx,
                apkUrl = apkUrl,
                targetFileName = "watch_proxy_v$versionName.apk",
                onProgress = { _, _ -> }
            ) { apkFile ->
                if (apkFile == null || !apkFile.exists() || apkFile.length() < 100_000L) {
                    try { apkFile?.delete() } catch (_: Exception) {}
                    sendApkError("Error descargando APK en móvil")
                    return@downloadApkToFile
                }
                otaExecutor.execute {
                    val channelClient = Wearable.getChannelClient(appCtx)
                    var channel: ChannelClient.Channel? = null
                    try {
                        val streamPath = "$PATH_OTA_APK_STREAM_PREFIX$versionName/${apkFile.length()}"
                        channel = Tasks.await(channelClient.openChannel(sourceNodeId, streamPath), 20, TimeUnit.SECONDS)
                        Tasks.await(channelClient.getOutputStream(channel), 15, TimeUnit.SECONDS).use { out ->
                            apkFile.inputStream().use { input ->
                                val buf = ByteArray(32 * 1024)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                }
                                out.flush()
                            }
                        }
                    } catch (e: Exception) {
                        try {
                            Tasks.await(
                                Wearable.getMessageClient(appCtx).sendMessage(
                                    sourceNodeId,
                                    PATH_OTA_APK_ERR,
                                    (e.localizedMessage ?: "Error Bluetooth").toByteArray(Charsets.UTF_8)
                                ),
                                10,
                                TimeUnit.SECONDS
                            )
                        } catch (_: Exception) {}
                    } finally {
                        try { if (channel != null) Tasks.await(channelClient.close(channel), 5, TimeUnit.SECONDS) } catch (_: Exception) {}
                        try { apkFile.delete() } catch (_: Exception) {}
                        releaseOtaWakeLock()
                    }
                }
            }
        }

        private val mainHandler = Handler(Looper.getMainLooper())
        private val dedupLock = Any()
        private var lastHandledEventTs = 0L
        private var lastHandledWallTime = 0L
        private var lastHandledAction = ""

        @Volatile
        private var isTorchOn = false
        private var torchCallbackRegistered = false

        @Volatile
        private var activeMediaPlayer: MediaPlayer? = null
        @Volatile
        private var activeRingtone: Ringtone? = null
        @Volatile
        private var activeWakeLock: PowerManager.WakeLock? = null
        @Volatile
        private var previousAlarmVolume: Int = -1
        private var stopRingRunnable: Runnable? = null
        private var stopReceiverRegistered = false

        private val stopFindReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == ACTION_STOP_FIND_PHONE) {
                    stopFindMyPhone(context.applicationContext)
                    Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.toast_phone_ring_stopped),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        fun executePhoneAction(context: Context, rawAction: String, explicitTs: Long = 0L) {
            val appCtx = context.applicationContext
            var action = rawAction.trim()
            var eventTs = explicitTs
            if (action.startsWith("{")) {
                try {
                    val obj = JSONObject(action)
                    action = obj.optString("action", action)
                    if (eventTs == 0L) {
                        eventTs = obj.optLong("ts", 0L)
                    }
                } catch (_: Exception) {}
            }

            if (action.isBlank()) return

            // Deduplicate identical event deliveries across MessageClient, DataClient & listeners
            synchronized(dedupLock) {
                val now = System.currentTimeMillis()
                if (eventTs > 0L && eventTs == lastHandledEventTs) {
                    return
                }
                if (action == lastHandledAction && (now - lastHandledWallTime) < 650L) {
                    return
                }
                if (eventTs > 0L) {
                    lastHandledEventTs = eventTs
                }
                lastHandledAction = action
                lastHandledWallTime = now
            }

            // Acquire short wake lock so action completes reliably even with screen OFF
            acquireTempWakeLock(appCtx, if (action == "phone_find") 32000L else 5000L)

            mainHandler.post {
                when (action) {
                    "phone_find" -> toggleFindMyPhone(appCtx)
                    "phone_torch" -> togglePhoneTorch(appCtx)
                    "phone_camera" -> triggerPhoneCamera(appCtx)
                    "phone_ringer" -> cycleRingerMode(appCtx)
                    "phone_lock" -> lockPhoneScreen(appCtx)
                    "phone_dnd" -> togglePhoneDnd(appCtx)
                    "phone_ha_event" -> fireHomeAssistantEvent(appCtx)
                }
            }
        }

        private fun acquireTempWakeLock(context: Context, timeoutMs: Long) {
            try {
                val pm = context.getSystemService(PowerManager::class.java) ?: return
                val wl = activeWakeLock ?: pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "UltraRemapper:PhoneActionWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    activeWakeLock = this
                }
                wl.acquire(timeoutMs)
            } catch (_: Exception) {}
        }

        private fun toggleFindMyPhone(context: Context) {
            val audio = context.getSystemService(AudioManager::class.java)
            val vibrator = context.getSystemService(Vibrator::class.java)

            val isAlreadyPlaying = (activeMediaPlayer?.isPlaying == true) || (activeRingtone?.isPlaying == true)
            if (isAlreadyPlaying) {
                stopFindMyPhone(context)
                Toast.makeText(context, context.getString(R.string.toast_phone_ring_stopped), Toast.LENGTH_SHORT).show()
                return
            }

            try {
                if (!stopReceiverRegistered) {
                    stopReceiverRegistered = true
                    val filter = IntentFilter(ACTION_STOP_FIND_PHONE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(stopFindReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        context.registerReceiver(stopFindReceiver, filter)
                    }
                }

                if (audio != null) {
                    previousAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                }

                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                // Prefer user's actual phone ringtone (pleasant & recognizable) over ugly default alarm beep
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                var startedWithMediaPlayer = false
                if (uri != null) {
                    try {
                        val mp = MediaPlayer().apply {
                            setAudioAttributes(attrs)
                            setDataSource(context, uri)
                            isLooping = true
                            prepare()
                            start()
                        }
                        activeMediaPlayer = mp
                        startedWithMediaPlayer = true
                    } catch (_: Exception) {
                        startedWithMediaPlayer = false
                    }
                }

                if (!startedWithMediaPlayer && uri != null) {
                    val ringtone = RingtoneManager.getRingtone(context, uri)
                    ringtone.audioAttributes = attrs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.isLooping = true
                    }
                    ringtone.play()
                    activeRingtone = ringtone
                }

                // Continuous repeating vibration pattern (repeat index 0 = loop until stopped)
                val vibWaveform = VibrationEffect.createWaveform(
                    longArrayOf(0, 600, 350, 600, 350),
                    0
                )
                @Suppress("DEPRECATION")
                vibrator?.vibrate(vibWaveform, attrs)

                showFindPhoneNotification(context)
                Toast.makeText(context, context.getString(R.string.toast_phone_ringing), Toast.LENGTH_LONG).show()

                stopRingRunnable?.let { mainHandler.removeCallbacks(it) }
                val autoStop = Runnable { stopFindMyPhone(context) }
                stopRingRunnable = autoStop
                mainHandler.postDelayed(autoStop, 30000L)
            } catch (_: Exception) {}
        }

        private fun showFindPhoneNotification(context: Context) {
            try {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                val channel = NotificationChannel(
                    FIND_NOTIF_CHANNEL_ID,
                    context.getString(R.string.action_phone_find),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)

                val stopIntent = Intent(ACTION_STOP_FIND_PHONE).setPackage(context.packageName)
                val stopPending = PendingIntent.getBroadcast(
                    context,
                    0,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notif = NotificationCompat.Builder(context, FIND_NOTIF_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                    .setContentTitle(context.getString(R.string.action_phone_find))
                    .setContentText(context.getString(R.string.toast_phone_ringing))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setAutoCancel(true)
                    .setContentIntent(stopPending)
                    .addAction(
                        android.R.drawable.ic_media_pause,
                        context.getString(R.string.toast_phone_ring_stopped),
                        stopPending
                    )
                    .build()

                nm.notify(FIND_NOTIF_ID, notif)
            } catch (_: Exception) {}
        }

        fun stopFindMyPhone(context: Context) {
            stopRingRunnable?.let { mainHandler.removeCallbacks(it) }
            stopRingRunnable = null
            try {
                activeMediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            activeMediaPlayer = null

            try {
                activeRingtone?.stop()
            } catch (_: Exception) {}
            activeRingtone = null

            try {
                val vibrator = context.getSystemService(Vibrator::class.java)
                vibrator?.cancel()
            } catch (_: Exception) {}

            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.cancel(FIND_NOTIF_ID)
            } catch (_: Exception) {}

            if (previousAlarmVolume >= 0) {
                try {
                    val audio = context.getSystemService(AudioManager::class.java)
                    audio?.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
                } catch (_: Exception) {}
                previousAlarmVolume = -1
            }

            try {
                activeWakeLock?.let {
                    if (it.isHeld) it.release()
                }
            } catch (_: Exception) {}
        }

        private fun togglePhoneTorch(context: Context) {
            try {
                val camManager = context.getSystemService(CameraManager::class.java) ?: return
                if (!torchCallbackRegistered) {
                    torchCallbackRegistered = true
                    camManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                            isTorchOn = enabled
                        }
                    }, mainHandler)
                }
                val camId = camManager.cameraIdList.firstOrNull { id ->
                    val chars = camManager.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                } ?: camManager.cameraIdList.firstOrNull { id ->
                    val chars = camManager.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: camManager.cameraIdList.firstOrNull() ?: return

                val nextState = !isTorchOn
                camManager.setTorchMode(camId, nextState)
                isTorchOn = nextState
            } catch (_: Exception) {}
        }

        private fun triggerPhoneCamera(context: Context) {
            try {
                val audio = context.getSystemService(AudioManager::class.java)
                // Also dispatch hardware camera / volume shutter event if camera is already open
                audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAMERA))
                audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAMERA))

                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Intent.ACTION_MAIN).apply {
                        setPackage("com.sec.android.app.camera")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {}
            }
        }

        private fun cycleRingerMode(context: Context) {
            try {
                val audio = context.getSystemService(AudioManager::class.java) ?: return
                val nextMode = if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    AudioManager.RINGER_MODE_VIBRATE
                } else {
                    AudioManager.RINGER_MODE_NORMAL
                }
                audio.ringerMode = nextMode
                val msgRes = if (nextMode == AudioManager.RINGER_MODE_VIBRATE) {
                    R.string.toast_phone_vibrate
                } else {
                    R.string.toast_phone_sound
                }
                Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }

        private fun lockPhoneScreen(context: Context) {
            val locked = PhoneActionAccessibilityService.lockScreen()
            if (!locked) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, context.getString(R.string.toast_enable_phone_a11y), Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }

        private fun togglePhoneDnd(context: Context) {
            try {
                val notifManager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
                if (!notifManager.isNotificationPolicyAccessGranted) {
                    val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, context.getString(R.string.toast_grant_phone_dnd), Toast.LENGTH_LONG).show()
                    return
                }
                val currentFilter = notifManager.currentInterruptionFilter
                val newFilter = if (currentFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
                    android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                }
                notifManager.setInterruptionFilter(newFilter)
                val msg = if (newFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
                    context.getString(R.string.toast_phone_dnd_off)
                } else {
                    context.getString(R.string.toast_phone_dnd_on)
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }

        private fun fireHomeAssistantEvent(context: Context) {
            try {
                // Broadcasts an event to Home Assistant companion app
                // Received in HA via event_type: "android.intent_received" or "ultra_remapper_button"
                val intent = Intent("io.homeassistant.companion.android.ACTION_EVENT").apply {
                    setPackage("io.homeassistant.companion.android")
                    putExtra("action", "ultra_remapper_button_press")
                    putExtra("source", "watch_hardware_button")
                    putExtra("timestamp", System.currentTimeMillis())
                }
                context.sendBroadcast(intent)

                // Also send general Tasker/HA intent
                val taskerIntent = Intent("io.homeassistant.companion.android.TASKER").apply {
                    setPackage("io.homeassistant.companion.android")
                    putExtra("event", "ultra_remapper_pressed")
                    putExtra("source", "galaxy_watch")
                }
                context.sendBroadcast(taskerIntent)

                Toast.makeText(context, context.getString(R.string.toast_ha_event_fired), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.toast_ha_not_installed), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
