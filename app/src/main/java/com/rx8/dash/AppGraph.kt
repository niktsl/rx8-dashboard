package com.rx8.dash

import android.content.Context
import com.rx8.dash.core.diag.Diagnostics
import com.rx8.dash.data.MaintenanceRepository
import com.rx8.dash.data.Rx8Database
import com.rx8.dash.data.SettingsRepository
import com.rx8.dash.data.TripRepository
import com.rx8.dash.media.MediaPanelController
import com.rx8.dash.service.TelemetryHub

/** Manual dependency graph, created once by [App]. */
class AppGraph(context: Context) {
    private val appContext: Context = context.applicationContext

    val settings: SettingsRepository = SettingsRepository(appContext)
    val db: Rx8Database = Rx8Database.build(appContext)
    val tripRepository: TripRepository = TripRepository(db)
    val maintenanceRepository: MaintenanceRepository = MaintenanceRepository(db)
    val telemetryHub: TelemetryHub = TelemetryHub(appContext, settings, tripRepository)
    val mediaController: MediaPanelController = MediaPanelController(appContext)

    /** Non-null only while a live adapter session is connected. */
    fun diagnostics(): Diagnostics? = telemetryHub.sessionAccess?.let { Diagnostics(it) }
}
