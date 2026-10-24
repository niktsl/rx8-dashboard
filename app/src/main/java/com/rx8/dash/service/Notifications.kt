package com.rx8.dash.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rx8.dash.MainActivity
import com.rx8.dash.R

/** Notification channels and builders shared by the service and [ServiceStarter]. */
object Notifications {
    const val CHANNEL_STATUS = "obd_status"
    const val CHANNEL_CONNECT = "obd_connect"

    const val ID_SERVICE = 1001
    const val ID_TAP_TO_CONNECT = 1002

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val status = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_status_desc)
            setShowBadge(false)
        }
        val connect = NotificationChannel(
            CHANNEL_CONNECT,
            context.getString(R.string.channel_connect_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_connect_desc)
        }
        nm.createNotificationChannel(status)
        nm.createNotificationChannel(connect)
    }

    /** Opens (or brings to front) the dashboard. */
    fun openAppIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun serviceNotification(context: Context, text: String): Notification {
        val stopIntent = Intent(context, ObdForegroundService::class.java).setAction(ObdForegroundService.ACTION_STOP)
        val stopPending = PendingIntent.getService(
            context,
            REQ_STOP,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_rx8)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context, REQ_OPEN))
            .addAction(0, context.getString(R.string.action_disconnect), stopPending)
            .build()
    }

    fun postTapToConnect(context: Context) {
        val n = NotificationCompat.Builder(context, CHANNEL_CONNECT)
            .setSmallIcon(R.drawable.ic_stat_rx8)
            .setContentTitle(context.getString(R.string.tap_to_connect_title))
            .setContentText(context.getString(R.string.tap_to_connect_text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(context, REQ_TAP))
            .build()
        notifySafely(context, ID_TAP_TO_CONNECT, n)
    }

    fun cancelTapToConnect(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_TAP_TO_CONNECT)
    }

    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private const val REQ_OPEN = 1
    private const val REQ_TAP = 2
    private const val REQ_STOP = 3
}
