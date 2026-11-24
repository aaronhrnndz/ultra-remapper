package com.aaronhrndz.ultraremapper.companion

import android.content.Context

/**
 * Shared definitions of watch gestures and actions so the Mobile Companion
 * can display and configure the exact same options as the watch app in any language.
 */
object WatchActionCatalog {

    val topGestures = listOf(
        "top_double" to R.string.gesture_double,
        "top_long" to R.string.gesture_long
    )

    val actionGestures = listOf(
        "single" to R.string.gesture_single,
        "double" to R.string.gesture_double,
        "long" to R.string.gesture_long
    )

    val bottomGestures = listOf(
        "bottom_single" to R.string.gesture_single,
        "bottom_double_tap" to R.string.gesture_double,
        "bottom_triple_tap" to R.string.gesture_triple,
        "bottom_long" to R.string.gesture_long
    )

    val gestureList = listOf(
        "toctoc" to R.string.gesture_toctoc
    )

    val allGestures = topGestures + actionGestures + bottomGestures + gestureList

    val shealthActions = listOf(
        "shealth_workout" to R.string.action_shealth_workout,
        "shealth_route" to R.string.action_shealth_route
    )

    val samsungActions = listOf(
        "recents" to R.string.action_recents,
        "stopwatch" to R.string.action_stopwatch,
        "water_lock" to R.string.action_water_lock,
        "assistant" to R.string.action_assistant
    )

    val devActions = listOf(
        "wireless_debugging" to R.string.action_wireless_debugging,
        "adb_mode" to R.string.action_adb_mode
    )

    val utilityActions = listOf(
        "flashlight" to R.string.action_flashlight,
        "red_flashlight" to R.string.action_red_flashlight,
        "dnd_toggle" to R.string.action_dnd_toggle,
        "save_parking" to R.string.action_save_parking,
        "nav_parking" to R.string.action_nav_parking,
        "water_eject" to R.string.action_water_eject,
        "lte_toggle" to R.string.action_lte_toggle
    )

    val homeAssistantActions = listOf(
        "ha_assist" to R.string.action_ha_assist
    )

    val mediaActions = listOf(
        "play_pause" to R.string.action_play_pause,
        "next" to R.string.action_next,
        "previous" to R.string.action_previous,
        "vol_up" to R.string.action_vol_up,
        "vol_down" to R.string.action_vol_down,
        "open_media_player" to R.string.action_open_media_player
    )

    val phoneActions = listOf(
        "phone_find" to R.string.action_phone_find,
        "phone_torch" to R.string.action_phone_torch,
        "phone_camera" to R.string.action_phone_camera,
        "phone_ringer" to R.string.action_phone_ringer,
        "phone_lock" to R.string.action_phone_lock,
        "phone_dnd" to R.string.action_phone_dnd,
        "phone_ha_event" to R.string.action_phone_ha_event
    )

    val modeActions = listOf(
        "mode_next" to R.string.action_mode_next,
        "mode_prev" to R.string.action_mode_prev
    )

    private val allStaticActions = (modeActions + shealthActions + samsungActions + devActions + utilityActions + phoneActions + homeAssistantActions + mediaActions).toMap()

    fun getActionLabel(context: Context, action: String, installedApps: List<WatchAppEntry>): String {
        if (action == "none" || action.isBlank()) return context.getString(R.string.action_none)
        if (action.startsWith("app:")) {
            val pkg = action.removePrefix("app:")
            return installedApps.firstOrNull { it.pkg == pkg }?.label ?: pkg
        }
        val resId = allStaticActions[action]
        return if (resId != null) context.getString(resId) else action
    }

    fun getGestureTitle(context: Context, gesture: String): String {
        val resId = allGestures.firstOrNull { it.first == gesture }?.second
        return if (resId != null) context.getString(resId) else gesture
    }
}
