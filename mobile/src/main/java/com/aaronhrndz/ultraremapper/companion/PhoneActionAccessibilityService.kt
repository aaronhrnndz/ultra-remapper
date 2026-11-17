package com.aaronhrndz.ultraremapper.companion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

/**
 * Lightweight AccessibilityService for Phone Companion to perform system-level actions
 * (such as GLOBAL_ACTION_LOCK_SCREEN) and keep DataLayer phone action listeners alive 24/7
 * even when the phone screen is off and Samsung battery optimization restricts background services.
 */
class PhoneActionAccessibilityService : AccessibilityService(),
    MessageClient.OnMessageReceivedListener,
    DataClient.OnDataChangedListener {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            Wearable.getMessageClient(applicationContext).addListener(this)
            Wearable.getDataClient(applicationContext).addListener(this)
        } catch (_: Exception) {}
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == PhoneActionListenerService.PATH_PHONE_ACTION) {
            val payload = String(event.data, Charsets.UTF_8).trim()
            PhoneActionListenerService.executePhoneAction(applicationContext, payload)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.dataItem.uri.path == PhoneActionListenerService.PATH_PHONE_ACTION) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val action = dataMap.getString("action")
                    val ts = dataMap.getLong("ts", 0L)
                    if (!action.isNullOrBlank()) {
                        PhoneActionListenerService.executePhoneAction(applicationContext, action, ts)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        try {
            Wearable.getMessageClient(applicationContext).removeListener(this)
            Wearable.getDataClient(applicationContext).removeListener(this)
        } catch (_: Exception) {}
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            Wearable.getMessageClient(applicationContext).removeListener(this)
            Wearable.getDataClient(applicationContext).removeListener(this)
        } catch (_: Exception) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        var instance: PhoneActionAccessibilityService? = null
            private set

        fun lockScreen(): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
