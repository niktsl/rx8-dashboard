package com.rx8.dash.media

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification listener whose only job is to give the app access to MediaSessionManager.getActiveSessions().
 * Publishes whether the system currently has it bound via [connected].
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _connected.value = false
        // The system unbinds listeners after crashes / package updates; ask to be bound again.
        try {
            NotificationListenerService.requestRebind(component(this))
        } catch (e: RuntimeException) {
            Log.w(TAG, "requestRebind failed", e)
        }
    }

    companion object {
        private const val TAG = "MediaListener"

        private val _connected = MutableStateFlow(false)

        /** True while the system has this listener bound. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, MediaListenerService::class.java)
    }
}
