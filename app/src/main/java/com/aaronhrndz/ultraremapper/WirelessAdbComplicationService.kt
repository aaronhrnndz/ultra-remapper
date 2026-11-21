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

class WirelessAdbComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        fun requestUpdate(context: Context) {
            try {
                val appCtx = context.applicationContext
                val requester = ComplicationDataSourceUpdateRequester.create(
                    appCtx,
                    ComponentName(appCtx, WirelessAdbComplicationService::class.java)
                )
                requester.requestUpdateAll()
            } catch (_: Throwable) {}
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
                ?: UtilityActions.resolveAdbConnectPortBlocking(this, 1000L)
        } else {
            null
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
        val shortTitleStr = if (isOn && lastOctet != null && port != null) lastOctet else "ADB"
        val shortTextStr = if (isOn && lastOctet != null && port != null) "$port" else compactAddr
        val desc = PlainComplicationText.Builder(compactAddr).build()
        val monoIcon = Icon.createWithResource(this, R.drawable.ic_adb_complication)
        val smallIcon = Icon.createWithResource(this, R.mipmap.ic_launcher)

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(shortTextStr).build(),
                contentDescription = desc
            )
                .setTitle(PlainComplicationText.Builder(shortTitleStr).build())
                .setMonochromaticImage(MonochromaticImage.Builder(monoIcon).build())
                .setTapAction(tapAction)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(compactAddr).build(),
                contentDescription = desc
            )
                .setTitle(PlainComplicationText.Builder("ADB").build())
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
