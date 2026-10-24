package com.rx8.dash.data

import com.rx8.dash.core.Metric
import com.rx8.dash.core.Rx8Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun gaugeSlotsRoundTrip() {
        val slots = listOf(Metric.RPM, Metric.COOLANT, Metric.FUEL_FLOW, Metric.TPMS_RR)
        val encoded = SettingsCodec.encodeGaugeSlots(slots)
        assertEquals("RPM,COOLANT,FUEL_FLOW,TPMS_RR", encoded)
        assertEquals(slots, SettingsCodec.decodeGaugeSlots(encoded))
    }

    @Test
    fun gaugeSlotsSkipUnknownAndFallBackWhenEmpty() {
        assertEquals(listOf(Metric.SPEED, Metric.VOLTAGE), SettingsCodec.decodeGaugeSlots(" SPEED , BOGUS,,VOLTAGE "))
        assertEquals(AppSettings.DEFAULT_GAUGE_SLOTS, SettingsCodec.decodeGaugeSlots(null))
        assertEquals(AppSettings.DEFAULT_GAUGE_SLOTS, SettingsCodec.decodeGaugeSlots(""))
        assertEquals(AppSettings.DEFAULT_GAUGE_SLOTS, SettingsCodec.decodeGaugeSlots("NOPE"))
    }

    @Test
    fun keyMapRoundTrip() {
        val map = mapOf(
            KnobKeyCodes.KEYCODE_DPAD_DOWN to KnobAction.NEXT,
            KnobKeyCodes.KEYCODE_ENTER to KnobAction.SELECT,
            87 to KnobAction.NONE,
        )
        val encoded = SettingsCodec.encodeKeyMap(map)
        assertEquals("20=NEXT;66=SELECT;87=NONE", encoded)
        assertEquals(map, SettingsCodec.decodeKeyMap(encoded))
        assertEquals(AppSettings.DEFAULT_KEY_MAP, SettingsCodec.decodeKeyMap(SettingsCodec.encodeKeyMap(AppSettings.DEFAULT_KEY_MAP)))
    }

    @Test
    fun keyMapToleratesMalformedPairs() {
        val decoded = SettingsCodec.decodeKeyMap("20=NEXT;x=SELECT;21=;=BACK;22=JUMP;;4=BACK")
        assertEquals(mapOf(20 to KnobAction.NEXT, 4 to KnobAction.BACK), decoded)
        assertEquals(AppSettings.DEFAULT_KEY_MAP, SettingsCodec.decodeKeyMap(null))
        assertTrue(SettingsCodec.decodeKeyMap("").isEmpty())
    }

    @Test
    fun defaultKeyMapMatchesSpec() {
        val s = AppSettings()
        assertEquals(KnobAction.NEXT, s.actionFor(20))   // DPAD_DOWN
        assertEquals(KnobAction.NEXT, s.actionFor(22))   // DPAD_RIGHT
        assertEquals(KnobAction.PREVIOUS, s.actionFor(19)) // DPAD_UP
        assertEquals(KnobAction.PREVIOUS, s.actionFor(21)) // DPAD_LEFT
        assertEquals(KnobAction.SELECT, s.actionFor(23))  // DPAD_CENTER
        assertEquals(KnobAction.SELECT, s.actionFor(66))  // ENTER
        assertEquals(KnobAction.BACK, s.actionFor(4))     // BACK
        assertEquals(KnobAction.NONE, s.actionFor(999))
    }

    @Test
    fun defaultsAndEnumDecoding() {
        val s = AppSettings()
        assertEquals(Rx8Series.S1_6PORT, s.series)
        assertEquals(9000, s.redlineRpm)
        assertEquals(740.0, s.fuelDensity, 0.0)
        assertEquals(1.0, s.fuelCalibration, 0.0)
        assertEquals(3000L, s.initialConnectDelayMs)
        assertEquals(Rx8Series.S2_AUTO, SettingsCodec.decodeSeries("S2_AUTO"))
        assertEquals(Rx8Series.S1_6PORT, SettingsCodec.decodeSeries("garbage"))
        assertEquals(DashboardPalette.NIGHT_AMBER, SettingsCodec.decodePalette("NIGHT_AMBER"))
        assertEquals(DashboardPalette.RENESIS_RED, SettingsCodec.decodePalette(null))
    }

    @Test
    fun fuelConfigClampsBadValues() {
        val cfg = AppSettings(fuelDensity = 0.0, fuelCalibration = Double.NaN).toFuelConfig()
        assertEquals(740.0, cfg.densityGPerL, 0.0)
        assertEquals(1.0, cfg.calibration, 0.0)
        assertEquals(AppSettings.MAX_CALIBRATION, AppSettings(fuelCalibration = 9.0).toFuelConfig().calibration, 0.0)
        assertEquals(false, AppSettings(series = Rx8Series.S1_4PORT).toFuelConfig().sixPort)
    }
}
