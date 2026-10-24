package com.rx8.dash.data

import com.rx8.dash.core.Metric
import com.rx8.dash.core.Rx8Series
import com.rx8.dash.core.fuel.FuelConfig
import com.rx8.dash.core.health.HealthConfig
import com.rx8.dash.core.poll.PollerConfig

/** What a knob/key press does. Keycodes map to these through [AppSettings.keyMap]. */
enum class KnobAction { NEXT, PREVIOUS, SELECT, BACK, NONE }

/** Dashboard colour schemes. */
enum class DashboardPalette(val label: String) {
    RENESIS_RED("Renesis red"),
    NIGHT_AMBER("Night amber"),
    CLASSIC_WHITE("Classic white"),
}

/**
 * Android keycode values (android.view.KeyEvent) duplicated as plain constants so the settings codec stays pure
 * Kotlin and can be unit-tested on the JVM without Android stubs.
 */
object KnobKeyCodes {
    const val KEYCODE_BACK: Int = 4
    const val KEYCODE_DPAD_UP: Int = 19
    const val KEYCODE_DPAD_DOWN: Int = 20
    const val KEYCODE_DPAD_LEFT: Int = 21
    const val KEYCODE_DPAD_RIGHT: Int = 22
    const val KEYCODE_DPAD_CENTER: Int = 23
    const val KEYCODE_ENTER: Int = 66
}

data class AppSettings(
    // Connection
    val deviceAddress: String? = null,
    val demoMode: Boolean = false,
    val extendedPids: Boolean = false,
    val tpms: Boolean = false,
    // Car
    val series: Rx8Series = Rx8Series.S1_6PORT,
    val redlineRpm: Int = Rx8Series.S1_6PORT.redlineRpm,
    // Fuel
    val fuelDensity: Double = FuelConfig.DEFAULT_DENSITY_G_PER_L,
    val fuelCalibration: Double = 1.0,
    // Display
    val palette: DashboardPalette = DashboardPalette.RENESIS_RED,
    val gaugeSlots: List<Metric> = DEFAULT_GAUGE_SLOTS,
    // Connection timing
    val initialConnectDelayMs: Long = 3000L,
    // Knob
    val keyMap: Map<Int, KnobAction> = DEFAULT_KEY_MAP,
) {
    /** Fuel model config derived from these settings. Density and calibration are clamped to sane ranges. */
    fun toFuelConfig(): FuelConfig = FuelConfig(
        densityGPerL = sanitize(fuelDensity, FuelConfig.DEFAULT_DENSITY_G_PER_L, MIN_DENSITY, MAX_DENSITY),
        calibration = sanitize(fuelCalibration, 1.0, MIN_CALIBRATION, MAX_CALIBRATION),
        sixPort = series.sixPort,
    )

    fun toHealthConfig(): HealthConfig = HealthConfig(redlineRpm = redlineRpm)

    fun toPollerConfig(): PollerConfig = PollerConfig(extendedEnabled = extendedPids, tpmsEnabled = tpms)

    /** Action for [keyCode], or [KnobAction.NONE] when unmapped. */
    fun actionFor(keyCode: Int): KnobAction = keyMap[keyCode] ?: KnobAction.NONE

    companion object {
        const val MIN_DENSITY: Double = 600.0
        const val MAX_DENSITY: Double = 900.0
        const val MIN_CALIBRATION: Double = 0.5
        const val MAX_CALIBRATION: Double = 2.0

        val DEFAULT_GAUGE_SLOTS: List<Metric> = listOf(
            Metric.SPEED,
            Metric.COOLANT,
            Metric.INTAKE_AIR,
            Metric.VOLTAGE,
            Metric.FUEL_FLOW,
            Metric.ECONOMY,
        )

        val DEFAULT_KEY_MAP: Map<Int, KnobAction> = mapOf(
            KnobKeyCodes.KEYCODE_DPAD_DOWN to KnobAction.NEXT,
            KnobKeyCodes.KEYCODE_DPAD_RIGHT to KnobAction.NEXT,
            KnobKeyCodes.KEYCODE_DPAD_UP to KnobAction.PREVIOUS,
            KnobKeyCodes.KEYCODE_DPAD_LEFT to KnobAction.PREVIOUS,
            KnobKeyCodes.KEYCODE_DPAD_CENTER to KnobAction.SELECT,
            KnobKeyCodes.KEYCODE_ENTER to KnobAction.SELECT,
            KnobKeyCodes.KEYCODE_BACK to KnobAction.BACK,
        )

        private fun sanitize(v: Double, default: Double, min: Double, max: Double): Double =
            if (v.isFinite() && v > 0.0) v.coerceIn(min, max) else default
    }
}

/**
 * Pure string codecs for the list/map settings stored in DataStore Preferences.
 *
 * - Gauge slots: metric enum names joined by ',' (e.g. "SPEED,COOLANT"). Unknown names are skipped.
 * - Key map: "keycode=ACTION" pairs joined by ';' (e.g. "20=NEXT;22=NEXT"). Malformed pairs are skipped.
 */
object SettingsCodec {
    private const val LIST_SEP = ','
    private const val PAIR_SEP = ';'
    private const val KV_SEP = '='

    fun encodeGaugeSlots(slots: List<Metric>): String = slots.joinToString(LIST_SEP.toString()) { it.name }

    /** Absent or effectively empty input yields the defaults, so the dashboard never ends up with no gauges. */
    fun decodeGaugeSlots(raw: String?): List<Metric> {
        if (raw == null) return AppSettings.DEFAULT_GAUGE_SLOTS
        val parsed = raw.split(LIST_SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { name -> enumOrNull<Metric>(name) }
        return parsed.ifEmpty { AppSettings.DEFAULT_GAUGE_SLOTS }
    }

    fun encodeKeyMap(map: Map<Int, KnobAction>): String =
        map.entries.sortedBy { it.key }.joinToString(PAIR_SEP.toString()) { "${it.key}$KV_SEP${it.value.name}" }

    /** Absent input yields the default map; a present but empty string is an intentionally empty map. */
    fun decodeKeyMap(raw: String?): Map<Int, KnobAction> {
        if (raw == null) return AppSettings.DEFAULT_KEY_MAP
        val out = LinkedHashMap<Int, KnobAction>()
        for (pair in raw.split(PAIR_SEP)) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) continue
            val idx = trimmed.indexOf(KV_SEP)
            if (idx <= 0 || idx == trimmed.length - 1) continue
            val code = trimmed.substring(0, idx).trim().toIntOrNull() ?: continue
            val action = enumOrNull<KnobAction>(trimmed.substring(idx + 1).trim()) ?: continue
            out[code] = action
        }
        return out
    }

    fun decodeSeries(raw: String?): Rx8Series = raw?.let { enumOrNull<Rx8Series>(it) } ?: Rx8Series.S1_6PORT

    fun decodePalette(raw: String?): DashboardPalette =
        raw?.let { enumOrNull<DashboardPalette>(it) } ?: DashboardPalette.RENESIS_RED

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
        enumValues<E>().firstOrNull { it.name == name }
}
