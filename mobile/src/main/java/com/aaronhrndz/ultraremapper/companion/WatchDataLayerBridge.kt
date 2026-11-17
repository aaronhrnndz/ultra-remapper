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
    val label: String
)

data class WatchSyncedState(
    val versionName: String,
    val masterEnabled: Boolean,
    val hapticEnabled: Boolean,
    val mappings: Map<String, String>,
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

    fun start() {
        dataClient.addListener(this)
        messageClient.addListener(this)
        fetchCurrentDataItem()
        requestSyncFromWatch()
    }

    fun stop() {
        try {
            dataClient.removeListener(this)
            messageClient.removeListener(this)
        } catch (_: Exception) {}
    }

    fun requestSyncFromWatch() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val watchNode = nodes.firstOrNull()
            if (watchNode != null) {
                onNodeConnected(watchNode.displayName)
                messageClient.sendMessage(watchNode.id, PATH_REQUEST_SYNC, ByteArray(0))
            }
        }
        try {
            val req = PutDataMapRequest.create(PATH_REQUEST_SYNC).apply {
                dataMap.putLong("ts", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(req)
        } catch (_: Exception) {}
        fetchCurrentDataItem()
    }

    fun sendCommandToWatch(json: JSONObject, onSent: ((Boolean) -> Unit)? = null) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val watchNode = nodes.firstOrNull()
            if (watchNode != null) {
                onNodeConnected(watchNode.displayName)
                messageClient.sendMessage(watchNode.id, PATH_CMD, bytes)
                    .addOnSuccessListener { onSent?.invoke(true) }
                    .addOnFailureListener { onSent?.invoke(false) }
            } else {
                onSent?.invoke(false)
            }
        }.addOnFailureListener {
            onSent?.invoke(false)
        }

        // Also write to DataItem as backup in case appId differs
        try {
            val req = PutDataMapRequest.create(PATH_CMD).apply {
                dataMap.putString("cmd_json", json.toString())
                dataMap.putLong("ts", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(req)
        } catch (_: Exception) {}
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

                val mappings = mutableMapOf<String, String>()
                root.optJSONObject("mappings")?.let { mObj ->
                    val keys = mObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        mappings[k] = mObj.optString(k, "none")
                    }
                }

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
                        if (pkg.isNotBlank()) {
                            apps.add(WatchAppEntry(pkg, label))
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
                    mappings = mappings,
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
