package com.rx8.dash.ui.vm

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import com.rx8.dash.App
import com.rx8.dash.AppGraph
import com.rx8.dash.core.maint.MaintenanceItem
import com.rx8.dash.data.FillUpEntity
import com.rx8.dash.data.TripEntity
import kotlin.coroutines.cancellation.CancellationException

/** The Application from the ViewModel creation extras. */
internal fun CreationExtras.app(): App = checkNotNull(this[APPLICATION_KEY]) { "No Application in CreationExtras" } as App

internal fun CreationExtras.graph(): AppGraph = app().graph

/** Runs [block], turning failures (except cancellation) into a user-facing message. */
internal suspend fun <T> runCatchingUi(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

internal fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/**
 * Best odometer estimate without an odometer PID: the latest fill-up's odometer plus distance logged by trips that
 * started after it, or the highest odometer recorded on a maintenance item, whichever is larger. Null when nothing is
 * known yet (the user then types it in).
 */
internal fun estimateOdometerKm(
    trips: List<TripEntity>,
    fills: List<FillUpEntity>,
    items: List<MaintenanceItem>,
): Double? {
    var best: Double? = null
    val lastFill = fills.maxByOrNull { it.timestampMs }
    if (lastFill != null && lastFill.odometerKm.isFinite() && lastFill.odometerKm > 0.0) {
        var km = lastFill.odometerKm
        for (t in trips) {
            if (t.startMs > lastFill.timestampMs && t.distanceKm.isFinite() && t.distanceKm > 0.0) km += t.distanceKm
        }
        best = km
    }
    for (i in items) {
        val k = i.lastKm ?: continue
        if (k.isFinite() && (best == null || k > best)) best = k
    }
    return best
}
