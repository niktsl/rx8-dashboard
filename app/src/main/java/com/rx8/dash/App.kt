package com.rx8.dash

import android.app.Application

/** Application: owns the manual-DI [AppGraph]. */
class App : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Notification channels are created lazily by Notifications.ensureChannels where needed.
    }
}
