package com.aaronhrndz.ultraremapper

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ButtonMode(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val mappings: Map<String, String>
)

data class ModeOverlayConfig(
    val style: String = STYLE_BREATHING,
    val thicknessPct: Int = 13,
    val durationMs: Int = 1000,
    val intensityPct: Int = 90,
    val showPill: Boolean = true,
    val pillPosition: String = POS_TOP,
    val pillSizePct: Int = 100
) {
    companion object {
        const val STYLE_BREATHING = "breathing"
        const val STYLE_ORBITAL = "orbital"
        const val STYLE_DUALWAVE = "dualwave"
        const val STYLE_TWINARC = "twinarc"

        const val POS_TOP = "top"
        const val POS_CENTER = "center"
        const val POS_BOTTOM = "bottom"

        val ALL_STYLES = listOf(STYLE_BREATHING, STYLE_ORBITAL, STYLE_DUALWAVE, STYLE_TWINARC)
        val ALL_POSITIONS = listOf(POS_TOP, POS_CENTER, POS_BOTTOM)

        fun fromJson(obj: JSONObject?): ModeOverlayConfig {
            if (obj == null) return ModeOverlayConfig()
            val style = obj.optString("style", STYLE_BREATHING).takeIf { it in ALL_STYLES } ?: STYLE_BREATHING
            val pos = obj.optString("pillPosition", POS_TOP).takeIf { it in ALL_POSITIONS } ?: POS_TOP
            return ModeOverlayConfig(
                style = style,
                thicknessPct = obj.optInt("thicknessPct", 13).coerceIn(6, 26),
                durationMs = obj.optInt("durationMs", 1000).coerceIn(400, 2000),
                intensityPct = obj.optInt("intensityPct", 90).coerceIn(40, 100),
                showPill = obj.optBoolean("showPill", true),
                pillPosition = pos,
                pillSizePct = obj.optInt("pillSizePct", 100).coerceIn(70, 150)
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("style", style)
        put("thicknessPct", thicknessPct)
        put("durationMs", durationMs)
        put("intensityPct", intensityPct)
        put("showPill", showPill)
        put("pillPosition", pillPosition)
        put("pillSizePct", pillSizePct)
    }
}

data class ModesState(
    val modes: List<ButtonMode>,
    val activeModeId: String,
    val overlayConfig: ModeOverlayConfig
) {
    val activeMode: ButtonMode
        get() = modes.firstOrNull { it.id == activeModeId } ?: modes.first()
}

data class ReservedSwitchInfo(
    val gesture: String,
    val action: String,
    val ownerModeId: String,
    val ownerModeName: String
)

/**
 * Dynamic N-modes store with automatic v2.x -> v3.0.0 migration,
 * globally reserved mode-switch gesture protection, and overlay animation config.
 */
object ModeStore {
    private const val PREFS_NAME = "mapping"
    private const val KEY_MODES_JSON = "v3_modes_json"
    private const val KEY_ACTIVE_MODE_ID = "v3_active_mode_id"
    private const val KEY_OVERLAY_CONFIG = "v3_overlay_config_json"

    private const val DEFAULT_COLOR = 0xFFFF5500.toInt()
    private val PALETTE = intArrayOf(
        0xFFFF5500.toInt(),
        0xFF00E676.toInt(),
        0xFF00B0FF.toInt(),
        0xFFD500F9.toInt(),
        0xFFFF1744.toInt(),
        0xFFFFD600.toInt(),
        0xFF00E5FF.toInt()
    )

    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isModeSwitchAction(action: String): Boolean =
        action == "mode_next" || action == "mode_prev"

    fun getState(context: Context): ModesState = synchronized(lock) {
        val p = prefs(context)
        val raw = p.getString(KEY_MODES_JSON, null)
        val modes = if (raw.isNullOrBlank()) {
            migrateFromV2Locked(context, p)
        } else {
            parseModesArray(raw).ifEmpty { migrateFromV2Locked(context, p) }
        }
        val savedActive = p.getString(KEY_ACTIVE_MODE_ID, null)
        val activeId = modes.firstOrNull { it.id == savedActive }?.id ?: modes.first().id
        val overlayRaw = p.getString(KEY_OVERLAY_CONFIG, null)
        val overlayCfg = try {
            if (overlayRaw.isNullOrBlank()) ModeOverlayConfig()
            else ModeOverlayConfig.fromJson(JSONObject(overlayRaw))
        } catch (_: Exception) {
            ModeOverlayConfig()
        }
        ModesState(modes = modes, activeModeId = activeId, overlayConfig = overlayCfg)
    }

    private fun migrateFromV2Locked(context: Context, p: android.content.SharedPreferences): List<ButtonMode> {
        val migrated = mutableMapOf<String, String>()
        for ((gesture, _) in Mapping.gestures) {
            val act = p.getString(gesture, null)
            if (!act.isNullOrBlank() && act != "none") {
                migrated[gesture] = act
            }
        }
        val defaultName = try {
            context.getString(R.string.mode_default_name)
        } catch (_: Exception) {
            "Mode 1"
        }
        val firstMode = ButtonMode(
            id = "mode_default",
            name = defaultName,
            colorArgb = DEFAULT_COLOR,
            mappings = migrated
        )
        val list = listOf(firstMode)
        p.edit()
            .putString(KEY_MODES_JSON, serializeModes(list).toString())
            .putString(KEY_ACTIVE_MODE_ID, firstMode.id)
            .apply()
        return list
    }

    private fun saveModesLocked(context: Context, modes: List<ButtonMode>, activeId: String) {
        val validActive = modes.firstOrNull { it.id == activeId }?.id ?: modes.first().id
        prefs(context).edit()
            .putString(KEY_MODES_JSON, serializeModes(modes).toString())
            .putString(KEY_ACTIVE_MODE_ID, validActive)
            .apply()
        notifyChanged(context)
    }

    private fun notifyChanged(context: Context) {
        val appCtx = context.applicationContext
        try {
            appCtx.sendBroadcast(Intent(CompanionSyncService.ACTION_STATE_UPDATED).setPackage(appCtx.packageName))
        } catch (_: Exception) {}
        CompanionSyncService.syncStateToDataLayer(appCtx)
    }

    fun getOverlayConfig(context: Context): ModeOverlayConfig = getState(context).overlayConfig

    fun setOverlayConfig(context: Context, config: ModeOverlayConfig) {
        synchronized(lock) {
            prefs(context).edit()
                .putString(KEY_OVERLAY_CONFIG, config.toJson().toString())
                .apply()
        }
        notifyChanged(context)
    }

    /**
     * Returns all globally reserved mode-switch gestures (any gesture assigned to mode_next/mode_prev
     * in any mode is reserved globally across all modes so the user can never get trapped).
     */
    fun getReservedSwitchGestures(context: Context): Map<String, ReservedSwitchInfo> {
        val state = getState(context)
        val result = linkedMapOf<String, ReservedSwitchInfo>()
        for (mode in state.modes) {
            for ((gesture, action) in mode.mappings) {
                if (isModeSwitchAction(action) && !result.containsKey(gesture)) {
                    result[gesture] = ReservedSwitchInfo(
                        gesture = gesture,
                        action = action,
                        ownerModeId = mode.id,
                        ownerModeName = mode.name
                    )
                }
            }
        }
        return result
    }

    fun isGestureReservedForOtherMode(context: Context, modeId: String, gesture: String): ReservedSwitchInfo? {
        val info = getReservedSwitchGestures(context)[gesture] ?: return null
        return if (info.ownerModeId != modeId) info else null
    }

    /** Effective action for a gesture in a specific mode (respects global reserved switch gestures). */
    fun getActionForMode(context: Context, modeId: String, gesture: String): String {
        val reserved = getReservedSwitchGestures(context)[gesture]
        if (reserved != null) return reserved.action
        val mode = getState(context).modes.firstOrNull { it.id == modeId } ?: return "none"
        return mode.mappings[gesture] ?: "none"
    }

    /** Effective action for a gesture in the currently active mode. */
    fun getActiveAction(context: Context, gesture: String): String {
        val state = getState(context)
        return getActionForMode(context, state.activeModeId, gesture)
    }

    fun setMappingForMode(context: Context, modeId: String, gesture: String, action: String) {
        synchronized(lock) {
            val state = getState(context)
            // Block overwriting a gesture reserved by another mode
            val reserved = getReservedSwitchGestures(context)[gesture]
            if (reserved != null && reserved.ownerModeId != modeId) {
                return
            }
            val updated = state.modes.map { m ->
                if (m.id != modeId) {
                    // If this mode just claimed a global mode-switch gesture, clear conflicting action in other modes
                    if (isModeSwitchAction(action) && m.mappings.containsKey(gesture)) {
                        m.copy(mappings = m.mappings - gesture)
                    } else {
                        m
                    }
                } else {
                    val nextMap = m.mappings.toMutableMap()
                    if (action == "none" || action.isBlank()) {
                        nextMap.remove(gesture)
                    } else {
                        nextMap[gesture] = action
                    }
                    m.copy(mappings = nextMap)
                }
            }
            saveModesLocked(context, updated, state.activeModeId)
        }
    }

    fun createMode(context: Context, name: String? = null, colorArgb: Int? = null): ButtonMode {
        val created: ButtonMode
        synchronized(lock) {
            val state = getState(context)
            val nextIdx = state.modes.size + 1
            val finalName = name?.trim()?.takeIf { it.isNotEmpty() } ?: try {
                context.getString(R.string.mode_numbered_name, nextIdx)
            } catch (_: Exception) {
                "Mode $nextIdx"
            }
            val finalColor = colorArgb ?: PALETTE[(nextIdx - 1) % PALETTE.size]
            created = ButtonMode(
                id = UUID.randomUUID().toString().substring(0, 8),
                name = finalName,
                colorArgb = finalColor,
                mappings = emptyMap()
            )
            saveModesLocked(context, state.modes + created, state.activeModeId)
        }
        return created
    }

    fun updateMode(context: Context, modeId: String, name: String? = null, colorArgb: Int? = null) {
        synchronized(lock) {
            val state = getState(context)
            val updated = state.modes.map { m ->
                if (m.id != modeId) m else m.copy(
                    name = name?.trim()?.takeIf { it.isNotEmpty() } ?: m.name,
                    colorArgb = colorArgb ?: m.colorArgb
                )
            }
            saveModesLocked(context, updated, state.activeModeId)
        }
    }

    fun deleteMode(context: Context, modeId: String): Boolean {
        synchronized(lock) {
            val state = getState(context)
            if (state.modes.size <= 1) return false
            val idx = state.modes.indexOfFirst { it.id == modeId }
            if (idx < 0) return false
            val removed = state.modes[idx]
            val remaining = state.modes.filterNot { it.id == modeId }.toMutableList()
            // If deleted mode owned any global mode-switch gesture, transfer ownership to the first remaining mode
            val ownedSwitches = removed.mappings.filterValues { isModeSwitchAction(it) }
            if (ownedSwitches.isNotEmpty() && remaining.isNotEmpty()) {
                val first = remaining[0]
                remaining[0] = first.copy(mappings = first.mappings + ownedSwitches)
            }
            val nextActive = if (state.activeModeId == modeId) {
                remaining[(idx - 1).coerceAtLeast(0)].id
            } else {
                state.activeModeId
            }
            saveModesLocked(context, remaining, nextActive)
            return true
        }
    }

    fun setActiveMode(context: Context, modeId: String, showOverlay: Boolean = false) {
        val target: ButtonMode
        synchronized(lock) {
            val state = getState(context)
            target = state.modes.firstOrNull { it.id == modeId } ?: return
            saveModesLocked(context, state.modes, target.id)
        }
        if (showOverlay) {
            ModeOverlayManager.show(context, target, getOverlayConfig(context))
        }
    }

    fun cycleMode(context: Context, delta: Int, showOverlay: Boolean = true): ButtonMode {
        val nextMode: ButtonMode
        val cfg: ModeOverlayConfig
        synchronized(lock) {
            val state = getState(context)
            val size = state.modes.size
            val curIdx = state.modes.indexOfFirst { it.id == state.activeModeId }.coerceAtLeast(0)
            val nextIdx = ((curIdx + delta) % size + size) % size
            nextMode = state.modes[nextIdx]
            cfg = state.overlayConfig
            saveModesLocked(context, state.modes, nextMode.id)
        }
        if (showOverlay) {
            ModeOverlayManager.show(context, nextMode, cfg)
        }
        return nextMode
    }

    fun serializeModes(modes: List<ButtonMode>): JSONArray {
        val arr = JSONArray()
        for (m in modes) {
            val mapObj = JSONObject()
            for ((g, a) in m.mappings) {
                mapObj.put(g, a)
            }
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("color", m.colorArgb)
                put("mappings", mapObj)
            })
        }
        return arr
    }

    fun parseModesArray(raw: String): List<ButtonMode> {
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ButtonMode>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = obj.optString("name", "Mode ${i + 1}")
                val color = obj.optInt("color", DEFAULT_COLOR)
                val mapObj = obj.optJSONObject("mappings")
                val map = mutableMapOf<String, String>()
                if (mapObj != null) {
                    val keys = mapObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = mapObj.optString(k)
                        if (v.isNotBlank() && v != "none") map[k] = v
                    }
                }
                list.add(ButtonMode(id = id, name = name, colorArgb = color, mappings = map))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
