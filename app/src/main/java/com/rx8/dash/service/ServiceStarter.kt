package com.rx8.dash.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.rx8.dash.App
import com.rx8.dash.bt.BluetoothDevices

/** Starts [ObdForegroundService] without ever crashing on background-start restrictions. */
object ServiceStarter {
    private const val TAG = "ServiceStarter"

    /**
     * Returns true when the foreground service start was accepted.
     *
     * - Without BLUETOOTH_CONNECT (API 31+) the connectedDevice service cannot go foreground, so it is not started;
     *   the hub is started in-process instead (demo mode works, live mode reports the missing permission).
     * - A background-start refusal posts a "tap to connect" notification that opens the dashboard; the Activity's
     *   onResume then calls this again from the foreground.
     */
    fun tryStart(context: Context): Boolean {
        val app = context.applicationContext
        if (!BluetoothDevices.hasConnectPermission(app)) {
            (app as? App)?.graph?.telemetryHub?.start()
            return false
        }
        val intent = Intent(app, ObdForegroundService::class.java)
        return try {
            ContextCompat.startForegroundService(app, intent)
            Notifications.cancelTapToConnect(app)
            true
        } catch (e: IllegalStateException) {
            // Covers ForegroundServiceStartNotAllowedException (a subclass, API 31+) and pre-31 background limits.
            val notAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && FgsCheck.isNotAllowed(e)
            Log.w(TAG, if (notAllowed) "Foreground service start not allowed" else "Service start failed", e)
            Notifications.ensureChannels(app)
            Notifications.postTapToConnect(app)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Service start refused", e)
            false
        }
    }

    /** Asks a running service to stop. */
    fun stop(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, ObdForegroundService::class.java))
    }

    /** Keeps the API 31 class reference out of code paths that run on older releases. */
    @RequiresApi(Build.VERSION_CODES.S)
    private object FgsCheck {
        fun isNotAllowed(e: Throwable): Boolean = e is ForegroundServiceStartNotAllowedException
    }
}
