package com.rx8.dash.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the OBD service after boot. BOOT_COMPLETED is exempt from background foreground-service limits. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT_POWERON -> ServiceStarter.tryStart(context)
        }
    }

    companion object {
        /** Sent instead of (or as well as) BOOT_COMPLETED by many head units' fast-boot / ACC-on resume. */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
