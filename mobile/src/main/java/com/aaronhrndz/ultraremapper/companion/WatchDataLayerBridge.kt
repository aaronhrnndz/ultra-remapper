package com.aaronhrndz.ultraremapper.companion

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

data class WatchAppEntry(
    val pkg: String,
    val label: String,
    val iconB64: String? = null
)

data class WatchButtonMode(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val mappings: Map<String, String>
)

data class WatchOverlayConfig(
    val style: String = "breathing",
    val thicknessPct: Int = 13,
    val durationMs: Int = 1000,
    val speedPct: Int = 100,
    val intensityPct: Int = 90,
    val showPill: Boolean = true,
    val pillPosition: String = "top",
    val pillSizePct: Int = 100,
    val pillAlphaPct: Int = 100
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("style", style)
        put("thicknessPct", thicknessPct)
        put("durationMs", durationMs)
        put("speedPct", speedPct)
        put("intensityPct", intensityPct)
        put("showPill", showPill)
        put("pillPosition", pillPosition)
        put("pillSizePct", pillSizePct)
        put("pillAlphaPct", pillAlphaPct)
    }

    companion object {
        fun fromJson(obj: JSONObject?): WatchOverlayConfig {
            if (obj == null) return WatchOverlayConfig()
            return WatchOverlayConfig(
                style = obj.optString("style", "breathing"),
                thicknessPct = obj.optInt("thicknessPct", 13).coerceIn(6, 26),
                durationMs = obj.optInt("durationMs", 1000).coerceIn(400, 2500),
                speedPct = obj.optInt("speedPct", 100).coerceIn(30, 200),
                intensityPct = obj.optInt("intensityPct", 90).coerceIn(40, 100),
                showPill = obj.optBoolean("showPill", true),
                pillPosition = obj.optString("pillPosition", "top"),
                pillSizePct = obj.optInt("pillSizePct", 100).coerceIn(50, 300),
                pillAlphaPct = obj.optInt("pillAlphaPct", 100).coerceIn(20, 100)
            )
        }
    }
}

data class WatchSyncedState(
    val versionName: String,
    val masterEnabled: Boolean,
    val hapticEnabled: Boolean,
    val betaChannelEnabled: Boolean = false,
    val mappings: Map<String, String>,
    val modes: List<WatchButtonMode> = emptyList(),
    val activeModeId: String = "mode_default",
    val overlayConfig: WatchOverlayConfig = WatchOverlayConfig(),
    val permissions: Map<String, Boolean>,
    val installedApps: List<WatchAppEntry>,
    val wirelessAdbEnabled: Boolean = false,
    val capturedPin: String? = null,
    val capturedHostIp: String? = null,
    val capturedPairingPort: Int? = null,
    val capturedConnectPort: Int? = null
)

/**
 * Manages Bluetooth / Wearable DataLayer synchronization between Phone Companion and Watch.
 * Works continuously over regular Bluetooth without needing Wi-Fi or ADB active.
 */
class WatchDataLayerBridge(
    private val context: Context,
    private val onNodeConnected: (String) -> Unit = {},
    private val onStateReceived: (WatchSyncedState, String?) -> Unit
) : DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    @Volatile
    private var isStarted = false
    @Volatile
    private var lastRequestTs = 0L

    fun start(includeApps: Boolean = true) {
        if (!isStarted) {
            isStarted = true
            dataClient.addListener(this)
            messageClient.addListener(this)
        }
        fetchCurrentDataItem()
        requestSyncFromWatch(includeApps = includeApps)
    }

    fun stop() {
        isStarted = false
        try {
            dataClient.removeListener(this)
            messageClient.removeListener(this)
        } catch (_: Exception) {}
    }

    fun requestSyncFromWatch(includeApps: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!includeApps && now - lastRequestTs < 1200L) return
        lastRequestTs = now
        val payloadBytes = (if (includeApps) "apps" else "").toByteArray(Charsets.UTF_8)

        fun fallbackDataItem() {
            try {
                val req = PutDataMapRequest.create(PATH_REQUEST_SYNC).apply {
                    dataMap.putBoolean("includeApps", includeApps)
                    dataMap.putLong("ts", System.currentTimeMillis())
                }.asPutDataRequest()
                dataClient.putDataItem(req)
            } catch (_: Exception) {}
        }

        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val watchNode = nodes.firstOrNull()
            if (watchNode != null) {
                onNodeConnected(watchNode.displayName)
                messageClient.sendMessage(watchNode.id, PATH_REQUEST_SYNC, payloadBytes)
                    .addOnFailureListener { fallbackDataItem() }
            } else {
                fallbackDataItem()
            }
        }.addOnFailureListener {
            fallbackDataItem()
        }
        fetchCurrentDataItem()
    }

    fun sendCommandToWatch(json: JSONObject, onSent: ((Boolean) -> Unit)? = null) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)

        fun fallbackDataItem() {
            try {
                val req = PutDataMapRequest.create(PATH_CMD).apply {
                    dataMap.putString("cmd_json", json.toString())
                    dataMap.putLong("ts", System.currentTimeMillis())
                }.asPutDataRequest()
                dataClient.putDataItem(req)
            } catch (_: Exception) {}
        }

        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val watchNode = nodes.firstOrNull()
            if (watchNode != null) {
                onNodeConnected(watchNode.displayName)
                messageClient.sendMessage(watchNode.id, PATH_CMD, bytes)
                    .addOnSuccessListener { onSent?.invoke(true) }
                    .addOnFailureListener {
                        fallbackDataItem()
                        onSent?.invoke(false)
                    }
            } else {
                fallbackDataItem()
                onSent?.invoke(false)
            }
        }.addOnFailureListener {
            fallbackDataItem()
            onSent?.invoke(false)
        }
    }

    private fun fetchCurrentDataItem() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val nodeName = nodes.firstOrNull()?.displayName
            if (!nodeName.isNullOrBlank()) {
                onNodeConnected(nodeName)
            }
            val uri = Uri.Builder().scheme("wear").path(PATH_STATE).build()
            dataClient.getDataItems(uri).addOnSuccessListener { buffer ->
                try {
                    for (item in buffer) {
                        if (item.uri.path == PATH_STATE) {
                            val dataMap = DataMapItem.fromDataItem(item).dataMap
                            val jsonStr = dataMap.getString("state_json")
                            if (!jsonStr.isNullOrBlank()) {
                                parseStateJson(jsonStr)?.let { parsed ->
                                    onStateReceived(parsed, nodeName)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WatchDataLayerBridge", "Error reading DataItems: ${e.message}")
                } finally {
                    buffer.release()
                }
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                val path = event.dataItem.uri.path
                if (path == PATH_STATE) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val jsonStr = dataMap.getString("state_json")
                    if (!jsonStr.isNullOrBlank()) {
                        parseStateJson(jsonStr)?.let { parsed ->
                            nodeClient.connectedNodes.addOnSuccessListener { nodes ->
                                onStateReceived(parsed, nodes.firstOrNull()?.displayName)
                            }
                        }
                    }
                } else if (path == PhoneActionListenerService.PATH_PHONE_ACTION) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val action = dataMap.getString("action")
                    val ts = dataMap.getLong("ts", 0L)
                    if (!action.isNullOrBlank()) {
                        PhoneActionListenerService.executePhoneAction(context.applicationContext, action, ts)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == PATH_STATE) {
            val jsonStr = String(event.data, Charsets.UTF_8)
            parseStateJson(jsonStr)?.let { parsed ->
                nodeClient.connectedNodes.addOnSuccessListener { nodes ->
                    onStateReceived(parsed, nodes.firstOrNull()?.displayName)
                }
            }
        } else if (event.path == PhoneActionListenerService.PATH_PHONE_ACTION) {
            val payload = String(event.data, Charsets.UTF_8).trim()
            PhoneActionListenerService.executePhoneAction(context.applicationContext, payload)
        }
    }

    companion object {
        const val PATH_STATE = "/ultra_remapper/state"
        const val PATH_REQUEST_SYNC = "/ultra_remapper/request_sync"
        const val PATH_CMD = "/ultra_remapper/cmd"

        fun parseStateJson(jsonStr: String): WatchSyncedState? {
            return try {
                val root = JSONObject(jsonStr)
                val versionName = root.optString("versionName", "")
                val masterEnabled = root.optBoolean("masterEnabled", true)
                val hapticEnabled = root.optBoolean("hapticEnabled", true)
                val betaChannelEnabled = root.optBoolean("betaChannelEnabled", false)

                val mappings = mutableMapOf<String, String>()
                root.optJSONObject("mappings")?.let { mObj ->
                    val keys = mObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        mappings[k] = mObj.optString(k, "none")
                    }
                }

                val modes = mutableListOf<WatchButtonMode>()
                root.optJSONArray("modes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val name = item.optString("name", "Mode ${i + 1}")
                        val color = item.optInt("color", 0xFFFF5500.toInt())
                        val mmap = mutableMapOf<String, String>()
                        item.optJSONObject("mappings")?.let { mo ->
                            val ks = mo.keys()
                            while (ks.hasNext()) {
                                val k = ks.next()
                                val v = mo.optString(k)
                                if (v.isNotBlank() && v != "none") mmap[k] = v
                            }
                        }
                        modes.add(WatchButtonMode(id = id, name = name, colorArgb = color, mappings = mmap))
                    }
                }
                if (modes.isEmpty()) {
                    modes.add(
                        WatchButtonMode(
                            id = "mode_default",
                            name = "Mode 1",
                            colorArgb = 0xFFFF5500.toInt(),
                            mappings = mappings.filterValues { it != "none" && it.isNotBlank() }
                        )
                    )
                }
                val activeModeId = root.optString("activeModeId", modes.first().id)
                    .takeIf { id -> modes.any { it.id == id } } ?: modes.first().id
                val overlayConfig = WatchOverlayConfig.fromJson(root.optJSONObject("overlayConfig"))

                val perms = mutableMapOf<String, Boolean>()
                root.optJSONObject("permissions")?.let { pObj ->
                    val keys = pObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        perms[k] = pObj.optBoolean(k, false)
                    }
                }

                val apps = mutableListOf<WatchAppEntry>()
                root.optJSONArray("installedApps")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val pkg = item.optString("pkg")
                        val label = item.optString("label", pkg)
                        val iconB64 = item.optString("iconB64", "").takeIf { it.isNotBlank() }
                        if (pkg.isNotBlank()) {
                            apps.add(WatchAppEntry(pkg, label, iconB64))
                        }
                    }
                }

                val wirelessAdbEnabled = root.optBoolean("wirelessAdbEnabled", false)
                val capturedPin = root.optString("capturedPin", "").takeIf { it.isNotBlank() }
                val capturedHostIp = root.optString("capturedHostIp", "").takeIf { it.isNotBlank() }
                val capturedPairingPort = root.optInt("capturedPairingPort", -1).takeIf { it > 0 }
                val capturedConnectPort = root.optInt("capturedConnectPort", -1).takeIf { it > 0 }

                WatchSyncedState(
                    versionName = versionName,
                    masterEnabled = masterEnabled,
                    hapticEnabled = hapticEnabled,
                    betaChannelEnabled = betaChannelEnabled,
                    mappings = mappings,
                    modes = modes,
                    activeModeId = activeModeId,
                    overlayConfig = overlayConfig,
                    permissions = perms,
                    installedApps = apps,
                    wirelessAdbEnabled = wirelessAdbEnabled,
                    capturedPin = capturedPin,
                    capturedHostIp = capturedHostIp,
                    capturedPairingPort = capturedPairingPort,
                    capturedConnectPort = capturedConnectPort
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
