package com.rx8.dash.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports/imports the whole Room database as one JSON document, and single trips as CSV, through SAF Uris.
 *
 * JSON is streamed with android.util.JsonWriter/JsonReader so large sample tables never sit in memory at once.
 * Layout: { "format": "rx8dash-backup", "version": 1, "exportedAtMs": ..., "trips": [...], "samples": [...],
 * "maintenance": [...], "dtcHistory": [...], "fillUps": [...], "startLog": [...] }.
 * Settings (DataStore) are not part of the backup.
 */
class BackupManager(context: Context, private val db: Rx8Database) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    data class ImportResult(
        val trips: Int,
        val samples: Int,
        val maintenance: Int,
        val dtcHistory: Int,
        val fillUps: Int,
        val startLog: Int,
    )

    // ---- SAF entry points ----

    suspend fun exportJson(uri: Uri): Unit = withContext(Dispatchers.IO) {
        openOutput(uri).use { out -> exportJson(out) }
    }

    /** Replaces all Room data with the backup at [uri]. All-or-nothing: on any error nothing is changed. */
    suspend fun importJson(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
        input.use { importJson(it) }
    }

    /** Writes one trip's samples as CSV; returns the number of data rows. */
    suspend fun exportTripCsv(tripId: Long, uri: Uri): Int = withContext(Dispatchers.IO) {
        openOutput(uri).use { out -> exportTripCsv(tripId, out) }
    }

    // ---- Stream variants (caller owns and closes the stream) ----

    suspend fun exportJson(out: OutputStream): Unit = withContext(Dispatchers.IO) {
        val w = JsonWriter(BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)))
        w.setIndent("")
        w.beginObject()
        w.name(K_FORMAT).value(FORMAT)
        w.name(K_VERSION).value(VERSION.toLong())
        w.name(K_EXPORTED_AT).value(System.currentTimeMillis())

        w.name(T_TRIPS).beginArray()
        for (t in db.tripDao().getAll()) writeTrip(w, t)
        w.endArray()

        w.name(T_SAMPLES).beginArray()
        var after = 0L
        while (true) {
            val page = db.sampleDao().page(after, PAGE_SIZE)
            if (page.isEmpty()) break
            for (s in page) writeSample(w, s)
            after = page.last().id
        }
        w.endArray()

        w.name(T_MAINTENANCE).beginArray()
        for (m in db.maintenanceDao().getAll()) writeMaintenance(w, m)
        w.endArray()

        w.name(T_DTC).beginArray()
        for (d in db.dtcHistoryDao().getAll()) writeDtc(w, d)
        w.endArray()

        w.name(T_FILLUPS).beginArray()
        for (f in db.fillUpDao().getAll()) writeFillUp(w, f)
        w.endArray()

        w.name(T_STARTLOG).beginArray()
        for (e in db.startLogDao().getAll()) writeStartLog(w, e)
        w.endArray()

        w.endObject()
        w.flush()
    }

    suspend fun importJson(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val r = JsonReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8)))
        db.withTransaction {
            db.sampleDao().clear()
            db.tripDao().clear()
            db.maintenanceDao().clear()
            db.dtcHistoryDao().clear()
            db.fillUpDao().clear()
            db.startLogDao().clear()

            var formatOk = false
            var trips = 0
            var samples = 0
            var maintenance = 0
            var dtc = 0
            var fills = 0
            var starts = 0

            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    K_FORMAT -> {
                        val f = if (r.peek() == JsonToken.STRING) r.nextString() else { r.skipValue(); "" }
                        if (f != FORMAT) throw IOException("Not an RX-8 Dash backup")
                        formatOk = true
                    }
                    K_VERSION -> {
                        val v = (readValue(r) as? String)?.toDoubleOrNull()?.toInt() ?: 0
                        if (v < 1 || v > VERSION) throw IOException("Unsupported backup version $v")
                    }
                    T_TRIPS -> trips = readArray<TripEntity>(r, { readTrip(it) }) { db.tripDao().insertAll(it) }
                    T_SAMPLES -> samples = readArray<SampleEntity>(r, { readSample(it) }) { db.sampleDao().insertAll(it) }
                    T_MAINTENANCE -> maintenance = readArray<MaintenanceEntity>(r, { readMaintenance(it) }) { db.maintenanceDao().insertAll(it) }
                    T_DTC -> dtc = readArray<DtcHistoryEntity>(r, { readDtc(it) }) { db.dtcHistoryDao().insertAll(it) }
                    T_FILLUPS -> fills = readArray<FillUpEntity>(r, { readFillUp(it) }) { db.fillUpDao().insertAll(it) }
                    T_STARTLOG -> starts = readArray<StartLogEntity>(r, { readStartLog(it) }) { db.startLogDao().insertAll(it) }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!formatOk) throw IOException("Not an RX-8 Dash backup (missing format)")
            ImportResult(trips, samples, maintenance, dtc, fills, starts)
        }
    }

    suspend fun exportTripCsv(tripId: Long, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val w: Writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
        w.write(CSV_HEADER)
        w.write("\n")
        var rows = 0
        var after = 0L
        while (true) {
            val page = db.sampleDao().pageForTrip(tripId, after, PAGE_SIZE)
            if (page.isEmpty()) break
            for (s in page) {
                w.write(csvRow(s))
                w.write("\n")
                rows++
            }
            after = page.last().id
        }
        w.flush()
        rows
    }

    // ---- helpers ----

    private fun openOutput(uri: Uri): OutputStream {
        // "wt" truncates existing content; some providers only accept "w".
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            resolver.openOutputStream(uri, "w")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        }
        return stream ?: throw IOException("Cannot open $uri for writing")
    }

    private suspend fun <T> readArray(
        r: JsonReader,
        parse: (Map<String, Any?>) -> T,
        insert: suspend (List<T>) -> Unit,
    ): Int {
        if (r.peek() == JsonToken.NULL) {
            r.nextNull()
            return 0
        }
        var count = 0
        val batch = ArrayList<T>(PAGE_SIZE)
        r.beginArray()
        while (r.hasNext()) {
            val obj = readValue(r)
            if (obj is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                batch.add(parse(obj as Map<String, Any?>))
                count++
                if (batch.size >= PAGE_SIZE) {
                    insert(ArrayList(batch))
                    batch.clear()
                }
            }
        }
        r.endArray()
        if (batch.isNotEmpty()) insert(ArrayList(batch))
        return count
    }

    /** Generic reader: objects become Map, arrays List, numbers and strings String, booleans Boolean. */
    private fun readValue(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val m = LinkedHashMap<String, Any?>()
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                m[name] = readValue(r)
            }
            r.endObject()
            m
        }
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<Any?>()
            r.beginArray()
            while (r.hasNext()) l.add(readValue(r))
            r.endArray()
            l
        }
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> {
            r.nextNull()
            null
        }
        else -> {
            r.skipValue()
            null
        }
    }

    private fun writeTrip(w: JsonWriter, t: TripEntity) {
        w.beginObject()
        w.name("id").value(t.id)
        w.name("startMs").value(t.startMs)
        nLong(w, "endMs", t.endMs)
        nDouble(w, "distanceKm", t.distanceKm)
        nDouble(w, "fuelLitres", t.fuelLitres)
        nDouble(w, "avgSpeedKmh", t.avgSpeedKmh)
        nDouble(w, "maxSpeedKmh", t.maxSpeedKmh)
        nDouble(w, "maxRpm", t.maxRpm)
        nDouble(w, "maxCoolantC", t.maxCoolantC)
        nDouble(w, "avgLPer100", t.avgLPer100)
        w.name("coldStart").value(t.coldStart)
        nDouble(w, "calibration", t.calibration)
        w.endObject()
    }

    private fun readTrip(m: Map<String, Any?>) = TripEntity(
        id = m.long("id") ?: 0L,
        startMs = m.long("startMs") ?: 0L,
        endMs = m.long("endMs"),
        distanceKm = m.double("distanceKm") ?: 0.0,
        fuelLitres = m.double("fuelLitres") ?: 0.0,
        avgSpeedKmh = m.double("avgSpeedKmh") ?: 0.0,
        maxSpeedKmh = m.double("maxSpeedKmh") ?: 0.0,
        maxRpm = m.double("maxRpm") ?: 0.0,
        maxCoolantC = m.double("maxCoolantC"),
        avgLPer100 = m.double("avgLPer100"),
        coldStart = m.bool("coldStart") ?: false,
        calibration = m.double("calibration") ?: 1.0,
    )

    private fun writeSample(w: JsonWriter, s: SampleEntity) {
        w.beginObject()
        w.name("id").value(s.id)
        w.name("timestamp").value(s.timestamp)
        w.name("tripId").value(s.tripId)
        nDouble(w, "rpm", s.rpm)
        nDouble(w, "speed", s.speed)
        nDouble(w, "coolant", s.coolant)
        nDouble(w, "iat", s.iat)
        nDouble(w, "throttle", s.throttle)
        nDouble(w, "load", s.load)
        nDouble(w, "maf", s.maf)
        nDouble(w, "stft", s.stft)
        nDouble(w, "ltft", s.ltft)
        nDouble(w, "voltage", s.voltage)
        nLong(w, "fuelStatus", s.fuelStatus?.toLong())
        nDouble(w, "cmdLambda", s.cmdLambda)
        nDouble(w, "fuelLph", s.fuelLph)
        w.name("lowConfidence").value(s.lowConfidence)
        w.endObject()
    }

    private fun readSample(m: Map<String, Any?>) = SampleEntity(
        id = m.long("id") ?: 0L,
        timestamp = m.long("timestamp") ?: 0L,
        tripId = m.long("tripId") ?: 0L,
        rpm = m.double("rpm"),
        speed = m.double("speed"),
        coolant = m.double("coolant"),
        iat = m.double("iat"),
        throttle = m.double("throttle"),
        load = m.double("load"),
        maf = m.double("maf"),
        stft = m.double("stft"),
        ltft = m.double("ltft"),
        voltage = m.double("voltage"),
        fuelStatus = m.long("fuelStatus")?.toInt(),
        cmdLambda = m.double("cmdLambda"),
        fuelLph = m.double("fuelLph"),
        lowConfidence = m.bool("lowConfidence") ?: false,
    )

    private fun writeMaintenance(w: JsonWriter, e: MaintenanceEntity) {
        w.beginObject()
        w.name("id").value(e.id)
        w.name("title").value(e.title)
        nLong(w, "intervalKm", e.intervalKm?.toLong())
        nLong(w, "intervalDays", e.intervalDays?.toLong())
        nDouble(w, "lastKm", e.lastKm)
        nLong(w, "lastDateMs", e.lastDateMs)
        w.name("notes").value(e.notes)
        w.name("sortOrder").value(e.sortOrder.toLong())
        w.endObject()
    }

    private fun readMaintenance(m: Map<String, Any?>): MaintenanceEntity {
        val id = m.str("id")
        if (id.isNullOrEmpty()) throw IOException("Maintenance entry without id")
        return MaintenanceEntity(
            id = id,
            title = m.str("title") ?: id,
            intervalKm = m.long("intervalKm")?.toInt(),
            intervalDays = m.long("intervalDays")?.toInt(),
            lastKm = m.double("lastKm"),
            lastDateMs = m.long("lastDateMs"),
            notes = m.str("notes") ?: "",
            sortOrder = m.long("sortOrder")?.toInt() ?: 0,
        )
    }

    private fun writeDtc(w: JsonWriter, d: DtcHistoryEntity) {
        w.beginObject()
        w.name("id").value(d.id)
        w.name("timestampMs").value(d.timestampMs)
        w.name("code").value(d.code)
        nString(w, "description", d.description)
        w.name("kind").value(d.kind)
        w.name("cleared").value(d.cleared)
        w.endObject()
    }

    private fun readDtc(m: Map<String, Any?>) = DtcHistoryEntity(
        id = m.long("id") ?: 0L,
        timestampMs = m.long("timestampMs") ?: 0L,
        code = m.str("code") ?: "",
        description = m.str("description"),
        kind = m.str("kind") ?: DtcHistoryEntity.KIND_STORED,
        cleared = m.bool("cleared") ?: false,
    )

    private fun writeFillUp(w: JsonWriter, f: FillUpEntity) {
        w.beginObject()
        w.name("id").value(f.id)
        w.name("timestampMs").value(f.timestampMs)
        nDouble(w, "odometerKm", f.odometerKm)
        nDouble(w, "litres", f.litres)
        w.name("fullTank").value(f.fullTank)
        w.name("notes").value(f.notes)
        w.endObject()
    }

    private fun readFillUp(m: Map<String, Any?>) = FillUpEntity(
        id = m.long("id") ?: 0L,
        timestampMs = m.long("timestampMs") ?: 0L,
        odometerKm = m.double("odometerKm") ?: 0.0,
        litres = m.double("litres") ?: 0.0,
        fullTank = m.bool("fullTank") ?: true,
        notes = m.str("notes") ?: "",
    )

    private fun writeStartLog(w: JsonWriter, e: StartLogEntity) {
        w.beginObject()
        w.name("id").value(e.id)
        w.name("timestampMs").value(e.timestampMs)
        nLong(w, "tripId", e.tripId)
        nDouble(w, "startCoolantC", e.startCoolantC)
        nDouble(w, "maxCoolantC", e.maxCoolantC)
        nDouble(w, "durationMin", e.durationMin)
        w.name("shortColdTrip").value(e.shortColdTrip)
        w.name("stalled").value(e.stalled)
        w.name("notes").value(e.notes)
        w.endObject()
    }

    private fun readStartLog(m: Map<String, Any?>) = StartLogEntity(
        id = m.long("id") ?: 0L,
        timestampMs = m.long("timestampMs") ?: 0L,
        tripId = m.long("tripId"),
        startCoolantC = m.double("startCoolantC"),
        maxCoolantC = m.double("maxCoolantC"),
        durationMin = m.double("durationMin"),
        shortColdTrip = m.bool("shortColdTrip") ?: false,
        stalled = m.bool("stalled") ?: false,
        notes = m.str("notes") ?: "",
    )

    companion object {
        const val FORMAT: String = "rx8dash-backup"
        const val VERSION: Int = 1
        private const val PAGE_SIZE: Int = 500

        private const val K_FORMAT = "format"
        private const val K_VERSION = "version"
        private const val K_EXPORTED_AT = "exportedAtMs"
        private const val T_TRIPS = "trips"
        private const val T_SAMPLES = "samples"
        private const val T_MAINTENANCE = "maintenance"
        private const val T_DTC = "dtcHistory"
        private const val T_FILLUPS = "fillUps"
        private const val T_STARTLOG = "startLog"

        const val CSV_HEADER: String =
            "timestamp,tripId,rpm,speed,coolant,iat,throttle,load,maf,stft,ltft,voltage,fuelStatus,cmdLambda,fuelLph,lowConfidence"

        /** Locale-independent CSV row; nulls and non-finite values are empty cells. */
        fun csvRow(s: SampleEntity): String {
            val sb = StringBuilder(128)
            sb.append(s.timestamp).append(',')
            sb.append(s.tripId).append(',')
            appendCell(sb, s.rpm); sb.append(',')
            appendCell(sb, s.speed); sb.append(',')
            appendCell(sb, s.coolant); sb.append(',')
            appendCell(sb, s.iat); sb.append(',')
            appendCell(sb, s.throttle); sb.append(',')
            appendCell(sb, s.load); sb.append(',')
            appendCell(sb, s.maf); sb.append(',')
            appendCell(sb, s.stft); sb.append(',')
            appendCell(sb, s.ltft); sb.append(',')
            appendCell(sb, s.voltage); sb.append(',')
            if (s.fuelStatus != null) sb.append(s.fuelStatus)
            sb.append(',')
            appendCell(sb, s.cmdLambda); sb.append(',')
            appendCell(sb, s.fuelLph); sb.append(',')
            sb.append(if (s.lowConfidence) '1' else '0')
            return sb.toString()
        }

        private fun appendCell(sb: StringBuilder, v: Double?) {
            if (v != null && v.isFinite()) sb.append(v.toString())
        }

        private fun nDouble(w: JsonWriter, name: String, v: Double?) {
            w.name(name)
            if (v != null && v.isFinite()) w.value(v) else w.nullValue()
        }

        private fun nLong(w: JsonWriter, name: String, v: Long?) {
            w.name(name)
            if (v != null) w.value(v) else w.nullValue()
        }

        private fun nString(w: JsonWriter, name: String, v: String?) {
            w.name(name)
            if (v != null) w.value(v) else w.nullValue()
        }

        private fun Map<String, Any?>.str(key: String): String? = this[key] as? String

        private fun Map<String, Any?>.double(key: String): Double? =
            (this[key] as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() }

        private fun Map<String, Any?>.long(key: String): Long? {
            val raw = this[key] as? String ?: return null
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        }

        private fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
            is Boolean -> v
            is String -> when (v.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }
    }
}
