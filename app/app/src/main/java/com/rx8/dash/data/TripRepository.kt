package com.rx8.dash.data

import androidx.room.withTransaction
import com.rx8.dash.core.Metric
import com.rx8.dash.core.TelemetrySnapshot
import com.rx8.dash.core.fuel.FillUp
import com.rx8.dash.core.fuel.FuelCalibration
import com.rx8.dash.core.trip.TripSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Trips, samples, fill-ups, start log and fill-up-based fuel calibration. */
class TripRepository(private val db: Rx8Database) {

    private val tripDao: TripDao = db.tripDao()
    private val sampleDao: SampleDao = db.sampleDao()
    private val fillUpDao: FillUpDao = db.fillUpDao()
    private val startLogDao: StartLogDao = db.startLogDao()

    private val bufferLock = Mutex()
    private val buffer = ArrayList<SampleEntity>(SAMPLE_BATCH_SIZE)

    // ---- Trips ----

    val trips: Flow<List<TripEntity>> = tripDao.observeAll()

    fun trip(tripId: Long): Flow<TripEntity?> = tripDao.observeById(tripId)

    /** Opens a trip and returns its id. [calibration] is the fuel calibration factor active for this trip. */
    suspend fun startTrip(startMs: Long, calibration: Double): Long =
        tripDao.insert(TripEntity(startMs = startMs, calibration = sanitizeCalibration(calibration)))

    /** Flushes buffered samples and writes the final summary onto the trip. */
    suspend fun finishTrip(tripId: Long, summary: TripSummary) {
        flushSamples()
        val existing = tripDao.getById(tripId) ?: return
        tripDao.update(
            existing.copy(
                startMs = summary.startMs,
                endMs = summary.endMs,
                distanceKm = finiteOr(summary.distanceKm, 0.0),
                fuelLitres = finiteOr(summary.fuelLitres, 0.0),
                avgSpeedKmh = finiteOr(summary.avgSpeedKmh, 0.0),
                maxSpeedKmh = finiteOr(summary.maxSpeedKmh, 0.0),
                maxRpm = finiteOr(summary.maxRpm, 0.0),
                maxCoolantC = finiteOrNull(summary.maxCoolantC),
                avgLPer100 = finiteOrNull(summary.avgLPer100),
                coldStart = summary.coldStart,
            ),
        )
    }

    /** Deletes a trip and its samples in one transaction. */
    suspend fun deleteTrip(tripId: Long) {
        db.withTransaction {
            sampleDao.deleteForTrip(tripId)
            tripDao.deleteById(tripId)
        }
    }

    // ---- Samples ----

    fun samplesForTrip(tripId: Long): Flow<List<SampleEntity>> = sampleDao.observeForTrip(tripId)

    suspend fun samplesForTripOnce(tripId: Long): List<SampleEntity> = sampleDao.getForTrip(tripId)

    /** Inserts a batch of samples directly. */
    suspend fun insertSamples(samples: List<SampleEntity>) {
        if (samples.isNotEmpty()) sampleDao.insertAll(samples)
    }

    /** Buffers one sample and writes the buffer in batches of [SAMPLE_BATCH_SIZE]. */
    suspend fun recordSample(tripId: Long, snapshot: TelemetrySnapshot) {
        val toWrite: List<SampleEntity>? = bufferLock.withLock {
            buffer.add(sampleFrom(tripId, snapshot))
            if (buffer.size >= SAMPLE_BATCH_SIZE) {
                val copy = ArrayList(buffer)
                buffer.clear()
                copy
            } else {
                null
            }
        }
        if (toWrite != null) sampleDao.insertAll(toWrite)
    }

    /** Writes any buffered samples now. */
    suspend fun flushSamples() {
        val toWrite: List<SampleEntity> = bufferLock.withLock {
            val copy = ArrayList(buffer)
            buffer.clear()
            copy
        }
        if (toWrite.isNotEmpty()) sampleDao.insertAll(toWrite)
    }

    // ---- Fill-ups and calibration ----

    val fillUps: Flow<List<FillUpEntity>> = fillUpDao.observeAll()

    suspend fun addFillUp(fill: FillUpEntity): Long = fillUpDao.insert(fill)

    suspend fun deleteFillUp(id: Long) = fillUpDao.deleteById(id)

    /**
     * Recomputes the global fuel calibration factor from fill-ups (see core FuelCalibration).
     *
     * The app estimate for fill i is the sum of uncalibrated trip litres (fuelLitres / calibration in effect) for
     * trips that started after fill i-1 and up to fill i. Fills and trips are aligned by timestamp; core orders the
     * segments by odometer.
     */
    suspend fun computeCalibration(): Double {
        val fills = fillUpDao.getAll() // ascending by timestamp
        if (fills.size < 2) return FuelCalibration.DEFAULT_FACTOR
        val trips = tripDao.getAll()
        val estimates = ArrayList<Double>(fills.size)
        estimates.add(0.0)
        for (i in 1 until fills.size) {
            val from = fills[i - 1].timestampMs
            val to = fills[i].timestampMs
            var litres = 0.0
            for (t in trips) {
                if (t.startMs > from && t.startMs <= to) litres += uncalibratedLitres(t)
            }
            estimates.add(litres)
        }
        return FuelCalibration.fromFillUps(
            fills.map { FillUp(odometerKm = it.odometerKm, litres = it.litres, fullTank = it.fullTank) },
            estimates,
        )
    }

    /** Recomputes the calibration and stores it in settings. Returns the new factor. */
    suspend fun recomputeCalibration(settings: SettingsRepository): Double {
        val factor = computeCalibration()
        settings.update { it.copy(fuelCalibration = factor) }
        return factor
    }

    // ---- Start log ----

    val startLog: Flow<List<StartLogEntity>> = startLogDao.observeAll()

    suspend fun logStart(entry: StartLogEntity): Long = startLogDao.insert(entry)

    /** Number of consecutive short cold trips, most recent first (feeds core StartLog.floodRisk). */
    suspend fun recentShortColdStreak(lookback: Int = 10): Int {
        var n = 0
        for (e in startLogDao.getRecent(lookback)) {
            if (e.shortColdTrip) n++ else break
        }
        return n
    }

    companion object {
        const val SAMPLE_BATCH_SIZE: Int = 25

        /** Maps a snapshot to a sample row; non-finite values become null. */
        fun sampleFrom(tripId: Long, s: TelemetrySnapshot): SampleEntity = SampleEntity(
            timestamp = s.timestampMs,
            tripId = tripId,
            rpm = finiteOrNull(s[Metric.RPM]),
            speed = finiteOrNull(s[Metric.SPEED]),
            coolant = finiteOrNull(s[Metric.COOLANT]),
            iat = finiteOrNull(s[Metric.INTAKE_AIR]),
            throttle = finiteOrNull(s[Metric.THROTTLE]),
            load = finiteOrNull(s[Metric.LOAD]),
            maf = finiteOrNull(s[Metric.MAF]),
            stft = finiteOrNull(s[Metric.STFT]),
            ltft = finiteOrNull(s[Metric.LTFT]),
            voltage = finiteOrNull(s[Metric.VOLTAGE]),
            fuelStatus = finiteOrNull(s[Metric.FUEL_SYSTEM_STATUS])?.toInt(),
            cmdLambda = finiteOrNull(s[Metric.COMMANDED_LAMBDA]),
            fuelLph = finiteOrNull(s[Metric.FUEL_FLOW]),
            lowConfidence = Metric.FUEL_FLOW in s.lowConfidence || Metric.ECONOMY in s.lowConfidence,
        )

        internal fun uncalibratedLitres(t: TripEntity): Double {
            val cal = if (t.calibration.isFinite() && t.calibration > 0.0) t.calibration else 1.0
            val l = t.fuelLitres / cal
            return if (l.isFinite() && l > 0.0) l else 0.0
        }

        private fun sanitizeCalibration(v: Double): Double = if (v.isFinite() && v > 0.0) v else 1.0

        private fun finiteOrNull(v: Double?): Double? = if (v != null && v.isFinite()) v else null

        private fun finiteOr(v: Double, default: Double): Double = if (v.isFinite()) v else default
    }
}
