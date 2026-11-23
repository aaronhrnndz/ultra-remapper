package com.aaronhrndz.ultraremapper

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class WirelessAdbToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appCtx = context.applicationContext
        if (SystemRouteHelper.hasSecureSettingsPermission(appCtx)) {
            UtilityActions.lastResolvedAdbPort = null
            BottomButtonService.capturedConnectPort = null
            val next = !UtilityActions.isWirelessAdbEnabled(appCtx)
            UtilityActions.setWirelessAdbEnabled(appCtx, next)
            HapticFeedbackHelper.playButtonHaptic(appCtx)
            WirelessAdbComplicationService.requestUpdate(appCtx)
            WirelessAdbTileService.requestTileUpdate(appCtx)
        } else {
            HapticFeedbackHelper.playUiTap(appCtx)
            UtilityActions.openWirelessDebuggingScreen(appCtx, backToHome = true)
        }
    }
}

open class WirelessAdbComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val MAX_RETRIES = 4
        private val retryCount = java.util.concurrent.atomic.AtomicInteger(0)

        fun requestUpdate(context: Context, resetRetries: Boolean = true) {
            if (resetRetries) retryCount.set(0)
            val appCtx = context.applicationContext
            for (cls in listOf(
                WirelessAdbComplicationService::class.java,
                WirelessAdbLongComplicationService::class.java
            )) {
                try {
                    val requester = ComplicationDataSourceUpdateRequester.create(
                        appCtx,
                        ComponentName(appCtx, cls)
                    )
                    requester.requestUpdateAll()
                } catch (_: Throwable) {}
            }
        }

        private fun scheduleRetry(context: Context) {
            if (retryCount.getAndIncrement() >= MAX_RETRIES) return
            val appCtx = context.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (UtilityActions.isWirelessAdbEnabled(appCtx)) {
                    requestUpdate(appCtx, resetRetries = false)
                }
            }, 2000L)
        }
    }

    private fun buildTapPendingIntent(): PendingIntent {
        val intent = Intent(this, WirelessAdbToggleReceiver::class.java)
        return PendingIntent.getBroadcast(
            this,
            9011,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return buildData(type, isOn = true, ip = "192.168.1.72", port = 41523, tapAction = null)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val isOn = UtilityActions.isWirelessAdbEnabled(this)
        val ip = UtilityActions.getWatchWifiIpv4(this)
        val port = if (isOn) {
            UtilityActions.lastResolvedAdbPort
                ?: BottomButtonService.capturedConnectPort
                ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    UtilityActions.resolveAdbConnectPortBlocking(this@WirelessAdbComplicationService, 2000L)
                }
        } else {
            null
        }
        if (isOn && (ip == null || port == null)) {
            scheduleRetry(this)
        } else {
            retryCount.set(0)
        }
        return buildData(request.complicationType, isOn, ip, port, buildTapPendingIntent())
    }

    private fun buildData(
        type: ComplicationType,
        isOn: Boolean,
        ip: String?,
        port: Int?,
        tapAction: PendingIntent?
    ): ComplicationData? {
        val lastOctet = ip?.substringAfterLast('.')
        val compactAddr = when {
            !isOn -> "OFF"
            lastOctet != null && port != null -> "$lastOctet:$port"
            lastOctet != null -> lastOctet
            port != null -> ":$port"
            else -> "ADB"
        }
        val shortTitleStr = when {
            !isOn -> "ADB"
            lastOctet != null && port != null -> "ADB:$lastOctet"
            else -> null
        }
        val shortTextStr = when {
            !isOn -> "OFF"
            ip == null -> "NO WIFI"
            port != null -> "$port"
            else -> "ADB:$lastOctet"
        }
        val longTextStr = when {
            !isOn -> "ADB OFF"
            ip != null && port != null -> "$ip:$port"
            ip != null -> ip
            else -> "ADB (No WiFi)"
        }
        val desc = PlainComplicationText.Builder(compactAddr).build()
        val monoIcon = Icon.createWithResource(this, R.drawable.ic_adb_complication)
        val smallIcon = Icon.createWithResource(this, R.mipmap.ic_launcher)

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(shortTextStr).build(),
                contentDescription = desc
            ).apply {
                if (shortTitleStr != null) {
                    setTitle(PlainComplicationText.Builder(shortTitleStr).build())
                }
            }
                .setMonochromaticImage(MonochromaticImage.Builder(monoIcon).build())
                .setTapAction(tapAction)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(longTextStr).build(),
                contentDescription = PlainComplicationText.Builder(longTextStr).build()
            )
                .setMonochromaticImage(MonochromaticImage.Builder(monoIcon).build())
                .setTapAction(tapAction)
                .build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(smallIcon, SmallImageType.ICON).build(),
                contentDescription = desc
            )
                .setTapAction(tapAction)
                .build()

            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                monochromaticImage = MonochromaticImage.Builder(monoIcon).build(),
                contentDescription = desc
            )
                .setTapAction(tapAction)
                .build()

            else -> null
        }
    }
}

class WirelessAdbLongComplicationService : WirelessAdbComplicationService()
