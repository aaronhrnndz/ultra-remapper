package com.aaronhrndz.ultraremapper

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/**
 * Wear OS Tile to toggle Wireless Debugging (ADB over Wi-Fi) with one tap and display
 * the watch's local Wi-Fi IPv4 and resolved _adb-tls-connect._tcp port.
 */
class WirelessAdbTileService : TileService() {

    private val tileExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ID_TOGGLE_ADB = "toggle_adb"
        private const val ID_REFRESH_ADB = "refresh_adb"
        private const val ID_OPEN_SETTINGS = "open_adb_settings"

        fun requestTileUpdate(context: Context) {
            try {
                getUpdater(context.applicationContext).requestUpdate(WirelessAdbTileService::class.java)
            } catch (_: Throwable) {}
        }
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val future = SettableFuture.create<TileBuilders.Tile>()
        val appCtx = applicationContext
        val clickId = requestParams.currentState.lastClickableId

        tileExecutor.execute {
            try {
                val hasPerm = SystemRouteHelper.hasSecureSettingsPermission(appCtx)

                when (clickId) {
                    ID_TOGGLE_ADB -> {
                        if (hasPerm) {
                            val next = !UtilityActions.isWirelessAdbEnabled(appCtx)
                            UtilityActions.setWirelessAdbEnabled(appCtx, next)
                            HapticFeedbackHelper.playButtonHaptic(appCtx)
                        } else {
                            UtilityActions.openWirelessDebuggingScreen(appCtx, backToHome = true)
                        }
                    }
                    ID_OPEN_SETTINGS -> {
                        HapticFeedbackHelper.playUiTap(appCtx)
                        UtilityActions.openWirelessDebuggingScreen(appCtx, backToHome = true)
                    }
                    ID_REFRESH_ADB -> {
                        HapticFeedbackHelper.playUiTap(appCtx)
                        UtilityActions.lastResolvedAdbPort = null
                    }
                }

                val isAdbOn = UtilityActions.isWirelessAdbEnabled(appCtx)
                val ip = UtilityActions.getWatchWifiIpv4(appCtx)
                val port = if (isAdbOn) {
                    UtilityActions.lastResolvedAdbPort
                        ?: BottomButtonService.capturedConnectPort
                        ?: UtilityActions.resolveAdbConnectPortBlocking(appCtx, 1400L) {
                            requestTileUpdate(appCtx)
                        }
                } else {
                    null
                }

                val rootLayout = buildTileLayout(hasPerm, isAdbOn, ip, port)
                val timeline = TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(rootLayout)
                                    .build()
                            )
                            .build()
                    )
                    .build()

                val tile = TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    .setTileTimeline(timeline)
                    .build()

                future.set(tile)
            } catch (t: Throwable) {
                future.setException(t)
            }
        }
        return future
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    override fun onTileEnterEvent(requestParams: androidx.wear.tiles.EventBuilders.TileEnterEvent) {
        super.onTileEnterEvent(requestParams)
        if (UtilityActions.isWirelessAdbEnabled(applicationContext)) {
            UtilityActions.lastResolvedAdbPort = null
            requestTileUpdate(applicationContext)
        }
    }

    override fun onDestroy() {
        tileExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildTileLayout(
        hasPerm: Boolean,
        isAdbOn: Boolean,
        ip: String?,
        port: Int?
    ): LayoutElementBuilders.LayoutElement {
        val statusText = getString(
            if (isAdbOn) R.string.tile_adb_header_on else R.string.tile_adb_header_off
        )
        val statusColor = if (isAdbOn) 0xFF4CAF50.toInt() else 0xFF9EA7B8.toInt()

        val headerPill = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(0xFF181B22.toInt()))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(12f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(12f))
                            .setEnd(DimensionBuilders.dp(12f))
                            .setTop(DimensionBuilders.dp(4f))
                            .setBottom(DimensionBuilders.dp(4f))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(statusText)
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(11f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(ColorBuilders.argb(statusColor))
                            .build()
                    )
                    .build()
            )
            .build()

        val addressText = when {
            isAdbOn && ip == null -> getString(R.string.tile_no_wifi)
            isAdbOn && port != null -> "$ip:$port"
            isAdbOn -> ip ?: getString(R.string.tile_no_wifi)
            else -> ip ?: getString(R.string.tile_no_wifi)
        }
        val addressColor = if (isAdbOn) 0xFFFFFFFF.toInt() else 0xFF9EA7B8.toInt()

        val subText = when {
            isAdbOn && ip != null && port == null -> getString(R.string.tile_loading_port)
            isAdbOn -> getString(R.string.tile_tap_to_refresh)
            else -> getString(R.string.tile_adb_disabled_sub)
        }
        val subColor = if (isAdbOn && ip != null && port == null) {
            0xFFFFB74D.toInt()
        } else {
            0xFF9EA7B8.toInt()
        }

        val refreshClickable = ModifiersBuilders.Clickable.Builder()
            .setId(ID_REFRESH_ADB)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

        val addressCard = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(refreshClickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(0xFF1F232A.toInt()))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(16f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(14f))
                            .setEnd(DimensionBuilders.dp(14f))
                            .setTop(DimensionBuilders.dp(10f))
                            .setBottom(DimensionBuilders.dp(10f))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(addressText)
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(16f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(ColorBuilders.argb(addressColor))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(3f))
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(subText)
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(11f))
                            .setColor(ColorBuilders.argb(subColor))
                            .build()
                    )
                    .build()
            )
            .build()

        val btnId = if (hasPerm) ID_TOGGLE_ADB else ID_OPEN_SETTINGS
        val btnBgColor = when {
            !hasPerm -> 0xFFFF5500.toInt()
            isAdbOn -> 0xFFD32F2F.toInt()
            else -> 0xFFFF5500.toInt()
        }
        val btnText = when {
            !hasPerm -> getString(R.string.tile_open_dev_settings)
            isAdbOn -> getString(R.string.tile_btn_disable)
            else -> getString(R.string.tile_btn_enable)
        }

        val actionButton = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(btnId)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(btnBgColor))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(18f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(18f))
                            .setEnd(DimensionBuilders.dp(18f))
                            .setTop(DimensionBuilders.dp(9f))
                            .setBottom(DimensionBuilders.dp(9f))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(btnText)
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(13f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(ColorBuilders.argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(headerPill)
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(8f))
                    .build()
            )
            .addContent(addressCard)
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(DimensionBuilders.dp(10f))
                    .build()
            )
            .addContent(actionButton)
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(0xFF000000.toInt()))
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(14f))
                            .setEnd(DimensionBuilders.dp(14f))
                            .build()
                    )
                    .build()
            )
            .addContent(column)
            .build()
    }
}
