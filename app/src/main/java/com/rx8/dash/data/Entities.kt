package com.rx8.dash.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One drive. Created open (endMs = null) and filled from core TripSummary when finished.
 * [calibration] is the fuel calibration factor in effect while [fuelLitres] was integrated, so the uncalibrated
 * estimate (fuelLitres / calibration) can be recovered for fill-up calibration.
 */
@Entity(tableName = "trips", indices = [Index(value = ["startMs"])])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long? = null,
    val distanceKm: Double = 0.0,
    val fuelLitres: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val maxRpm: Double = 0.0,
    val maxCoolantC: Double? = null,
    val avgLPer100: Double? = null,
    val coldStart: Boolean = false,
    val calibration: Double = 1.0,
)

/**
 * One logged telemetry sample. Raw PID 03 ([fuelStatus]) and PID 44 ([cmdLambda]) are kept on every row so the
 * on-car run shows which fuel path the PCM actually needs. No foreign key: samples are deleted with their trip by
 * [TripRepository.deleteTrip].
 */
@Entity(tableName = "samples", indices = [Index(value = ["tripId", "timestamp"])])
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val tripId: Long,
    val rpm: Double? = null,
    val speed: Double? = null,
    val coolant: Double? = null,
    val iat: Double? = null,
    val throttle: Double? = null,
    val load: Double? = null,
    val maf: Double? = null,
    val stft: Double? = null,
    val ltft: Double? = null,
    val voltage: Double? = null,
    val fuelStatus: Int? = null,
    val cmdLambda: Double? = null,
    val fuelLph: Double? = null,
    val lowConfidence: Boolean = false,
)

/** Persisted maintenance item; maps 1:1 to core MaintenanceItem. */
@Entity(tableName = "maintenance")
data class MaintenanceEntity(
    @PrimaryKey val id: String,
    val title: String,
    val intervalKm: Int?,
    val intervalDays: Int?,
    val lastKm: Double?,
    val lastDateMs: Long?,
    val notes: String = "",
    val sortOrder: Int = 0,
)

/** A DTC seen during a scan. [kind] is "stored", "pending" or "permanent". */
@Entity(tableName = "dtc_history", indices = [Index(value = ["timestampMs"]), Index(value = ["code"])])
data class DtcHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val code: String,
    val description: String? = null,
    val kind: String = KIND_STORED,
    val cleared: Boolean = false,
) {
    companion object {
        const val KIND_STORED = "stored"
        const val KIND_PENDING = "pending"
        const val KIND_PERMANENT = "permanent"
    }
}

/** A fuel fill-up used to true up the fuel calibration factor. */
@Entity(tableName = "fill_ups", indices = [Index(value = ["timestampMs"])])
data class FillUpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val odometerKm: Double,
    val litres: Double,
    val fullTank: Boolean = true,
    val notes: String = "",
)

/** Engine start / stall log, used for short-cold-trip flood-risk tracking. */
@Entity(tableName = "start_log", indices = [Index(value = ["timestampMs"])])
data class StartLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val tripId: Long? = null,
    val startCoolantC: Double? = null,
    val maxCoolantC: Double? = null,
    val durationMin: Double? = null,
    val shortColdTrip: Boolean = false,
    val stalled: Boolean = false,
    val notes: String = "",
)
