package com.rx8.dash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Single process-wide DataStore instance (the delegate must be top-level). */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App settings persisted in DataStore Preferences. Lists/maps are stored via [SettingsCodec]. */
class SettingsRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs -> fromPreferences(prefs) }
        .distinctUntilChanged()

    /** Current value, for one-shot reads off the main thread. */
    suspend fun current(): AppSettings = settings.first()

    /** Atomically reads, transforms and writes the settings. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val before = fromPreferences(prefs)
            var next = transform(before)
            // Changing series while the redline still sits at the old series default moves it to the new default.
            if (next.series != before.series && next.redlineRpm == before.series.redlineRpm) {
                next = next.copy(redlineRpm = next.series.redlineRpm)
            }
            writePreferences(prefs, next)
        }
    }

    private object Keys {
        val DEVICE_ADDRESS = stringPreferencesKey("device_address")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val EXTENDED_PIDS = booleanPreferencesKey("extended_pids")
        val TPMS = booleanPreferencesKey("tpms")
        val SERIES = stringPreferencesKey("series")
        val REDLINE_RPM = intPreferencesKey("redline_rpm")
        val FUEL_DENSITY = doublePreferencesKey("fuel_density")
        val FUEL_CALIBRATION = doublePreferencesKey("fuel_calibration")
        val PALETTE = stringPreferencesKey("palette")
        val DASH_LAYOUT = stringPreferencesKey("dash_layout")
        val GAUGE_SLOTS = stringPreferencesKey("gauge_slots")
        val INITIAL_CONNECT_DELAY_MS = longPreferencesKey("initial_connect_delay_ms")
        val KEY_MAP = stringPreferencesKey("key_map")
    }

    private fun fromPreferences(p: Preferences): AppSettings {
        val defaults = AppSettings()
        val series = SettingsCodec.decodeSeries(p[Keys.SERIES])
        return AppSettings(
            deviceAddress = p[Keys.DEVICE_ADDRESS]?.takeIf { it.isNotBlank() },
            demoMode = p[Keys.DEMO_MODE] ?: defaults.demoMode,
            extendedPids = p[Keys.EXTENDED_PIDS] ?: defaults.extendedPids,
            tpms = p[Keys.TPMS] ?: defaults.tpms,
            series = series,
            // Without an explicit override the redline follows the selected series.
            redlineRpm = p[Keys.REDLINE_RPM] ?: series.redlineRpm,
            fuelDensity = p[Keys.FUEL_DENSITY] ?: defaults.fuelDensity,
            fuelCalibration = p[Keys.FUEL_CALIBRATION] ?: defaults.fuelCalibration,
            palette = SettingsCodec.decodePalette(p[Keys.PALETTE]),
            dashLayout = SettingsCodec.decodeLayout(p[Keys.DASH_LAYOUT]),
            gaugeSlots = SettingsCodec.decodeGaugeSlots(p[Keys.GAUGE_SLOTS]),
            initialConnectDelayMs = p[Keys.INITIAL_CONNECT_DELAY_MS] ?: defaults.initialConnectDelayMs,
            keyMap = SettingsCodec.decodeKeyMap(p[Keys.KEY_MAP]),
        )
    }

    private fun writePreferences(p: MutablePreferences, s: AppSettings) {
        val address = s.deviceAddress
        if (address.isNullOrBlank()) p.remove(Keys.DEVICE_ADDRESS) else p[Keys.DEVICE_ADDRESS] = address
        p[Keys.DEMO_MODE] = s.demoMode
        p[Keys.EXTENDED_PIDS] = s.extendedPids
        p[Keys.TPMS] = s.tpms
        p[Keys.SERIES] = s.series.name
        p[Keys.REDLINE_RPM] = s.redlineRpm
        p[Keys.FUEL_DENSITY] = s.fuelDensity
        p[Keys.FUEL_CALIBRATION] = s.fuelCalibration
        p[Keys.PALETTE] = s.palette.name
        p[Keys.DASH_LAYOUT] = s.dashLayout.name
        p[Keys.GAUGE_SLOTS] = SettingsCodec.encodeGaugeSlots(s.gaugeSlots)
        p[Keys.INITIAL_CONNECT_DELAY_MS] = s.initialConnectDelayMs
        p[Keys.KEY_MAP] = SettingsCodec.encodeKeyMap(s.keyMap)
    }
}
