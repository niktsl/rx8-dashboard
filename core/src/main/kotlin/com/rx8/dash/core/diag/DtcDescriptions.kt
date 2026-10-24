package com.rx8.dash.core.diag

/**
 * DTC description table: generic SAE P0xxx/P2xxx codes plus notes for the RX-8 (13B-MSP Renesis).
 * On the RX-8, "cylinder 1/2" misfire codes refer to the front/rear rotor. Codes the RX-8 does not use (for example
 * variable cam timing P0010–P0014) are deliberately left out. Unknown powertrain codes fall back to a generic
 * category description. Manufacturer-specific codes (P1xxx) are described only where the meaning is well established.
 */
object DtcDescriptions {

    fun describe(code: String): String? {
        val c = code.trim().uppercase()
        TABLE[c]?.let { return it }
        return genericCategory(c)
    }

    private fun genericCategory(c: String): String? {
        if (c.length != 5 || c[0] != 'P') return null
        val group = c.substring(1, 3)
        val category = when (group) {
            "00", "01", "02" -> "Fuel and air metering"
            "03" -> "Ignition system or misfire"
            "04" -> "Auxiliary emission controls"
            "05" -> "Vehicle speed, idle control or auxiliary inputs"
            "06" -> "Computer or output circuit"
            "07", "08", "09" -> "Transmission"
            "10", "11", "12", "13", "14", "15", "16", "17", "18", "19" -> "Manufacturer-specific"
            "20", "21", "22", "23", "24", "25", "26", "27", "28", "29" -> "Generic powertrain (fuel, air, emissions)"
            else -> return null
        }
        return "$category (no detailed description available)"
    }

    private val TABLE: Map<String, String> = mapOf(
        // Fuel and air metering
        "P0030" to "O2 sensor heater control circuit (bank 1 sensor 1)",
        "P0031" to "O2 sensor heater control circuit low (bank 1 sensor 1)",
        "P0032" to "O2 sensor heater control circuit high (bank 1 sensor 1)",
        "P0036" to "O2 sensor heater control circuit (bank 1 sensor 2)",
        "P0037" to "O2 sensor heater control circuit low (bank 1 sensor 2)",
        "P0038" to "O2 sensor heater control circuit high (bank 1 sensor 2)",
        "P0100" to "Mass air flow (MAF) circuit malfunction",
        "P0101" to "Mass air flow (MAF) circuit range/performance",
        "P0102" to "Mass air flow (MAF) circuit low input",
        "P0103" to "Mass air flow (MAF) circuit high input",
        "P0106" to "Manifold absolute pressure / barometric pressure circuit range/performance",
        "P0107" to "Manifold absolute pressure / barometric pressure circuit low input",
        "P0108" to "Manifold absolute pressure / barometric pressure circuit high input",
        "P0110" to "Intake air temperature (IAT) circuit malfunction",
        "P0111" to "Intake air temperature (IAT) circuit range/performance",
        "P0112" to "Intake air temperature (IAT) circuit low input",
        "P0113" to "Intake air temperature (IAT) circuit high input",
        "P0115" to "Engine coolant temperature (ECT) circuit malfunction",
        "P0116" to "Engine coolant temperature (ECT) circuit range/performance",
        "P0117" to "Engine coolant temperature (ECT) circuit low input",
        "P0118" to "Engine coolant temperature (ECT) circuit high input",
        "P0120" to "Throttle position sensor A circuit malfunction",
        "P0121" to "Throttle position sensor A circuit range/performance",
        "P0122" to "Throttle position sensor A circuit low input",
        "P0123" to "Throttle position sensor A circuit high input",
        "P0125" to "Insufficient coolant temperature for closed-loop fuel control",
        "P0126" to "Insufficient coolant temperature for stable operation",
        "P0128" to "Coolant thermostat (coolant temperature below thermostat regulating temperature)",
        "P0130" to "O2 sensor circuit malfunction (bank 1 sensor 1)",
        "P0131" to "O2 sensor circuit low voltage (bank 1 sensor 1)",
        "P0132" to "O2 sensor circuit high voltage (bank 1 sensor 1)",
        "P0133" to "O2 sensor circuit slow response (bank 1 sensor 1)",
        "P0134" to "O2 sensor circuit no activity detected (bank 1 sensor 1)",
        "P0135" to "O2 sensor heater circuit malfunction (bank 1 sensor 1)",
        "P0136" to "O2 sensor circuit malfunction (bank 1 sensor 2)",
        "P0137" to "O2 sensor circuit low voltage (bank 1 sensor 2)",
        "P0138" to "O2 sensor circuit high voltage (bank 1 sensor 2)",
        "P0139" to "O2 sensor circuit slow response (bank 1 sensor 2)",
        "P0140" to "O2 sensor circuit no activity detected (bank 1 sensor 2)",
        "P0141" to "O2 sensor heater circuit malfunction (bank 1 sensor 2)",
        "P0170" to "Fuel trim malfunction (bank 1)",
        "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)",
        "P0180" to "Fuel temperature sensor A circuit malfunction",
        "P0181" to "Fuel temperature sensor A circuit range/performance",
        "P0182" to "Fuel temperature sensor A circuit low input",
        "P0183" to "Fuel temperature sensor A circuit high input",
        "P0190" to "Fuel rail pressure sensor circuit malfunction",
        "P0191" to "Fuel rail pressure sensor circuit range/performance",
        "P0192" to "Fuel rail pressure sensor circuit low input",
        "P0193" to "Fuel rail pressure sensor circuit high input",
        "P0201" to "Injector circuit malfunction, injector 1",
        "P0202" to "Injector circuit malfunction, injector 2",
        "P0203" to "Injector circuit malfunction, injector 3",
        "P0204" to "Injector circuit malfunction, injector 4",
        "P0205" to "Injector circuit malfunction, injector 5",
        "P0206" to "Injector circuit malfunction, injector 6",
        "P0217" to "Engine over-temperature condition",
        "P0219" to "Engine overspeed condition",
        "P0220" to "Throttle position sensor B circuit malfunction",
        "P0221" to "Throttle position sensor B circuit range/performance",
        "P0222" to "Throttle position sensor B circuit low input",
        "P0223" to "Throttle position sensor B circuit high input",
        "P0230" to "Fuel pump primary circuit malfunction",
        "P0231" to "Fuel pump secondary circuit low",
        "P0232" to "Fuel pump secondary circuit high",

        // Ignition and misfire (RX-8: cylinder 1 = front rotor, cylinder 2 = rear rotor)
        "P0300" to "Random or multiple misfire detected (RX-8: both rotors / random; check coils, plugs, wires, flooding)",
        "P0301" to "Misfire detected, cylinder 1 (RX-8: front rotor)",
        "P0302" to "Misfire detected, cylinder 2 (RX-8: rear rotor)",
        "P0325" to "Knock sensor 1 circuit malfunction",
        "P0326" to "Knock sensor 1 circuit range/performance",
        "P0327" to "Knock sensor 1 circuit low input",
        "P0328" to "Knock sensor 1 circuit high input",
        "P0335" to "Crankshaft position sensor A circuit malfunction (RX-8: eccentric shaft position sensor)",
        "P0336" to "Crankshaft position sensor A circuit range/performance (RX-8: eccentric shaft position sensor)",
        "P0340" to "Camshaft position sensor A circuit malfunction",
        "P0341" to "Camshaft position sensor A circuit range/performance",
        "P0351" to "Ignition coil A primary/secondary circuit malfunction",
        "P0352" to "Ignition coil B primary/secondary circuit malfunction",
        "P0353" to "Ignition coil C primary/secondary circuit malfunction",
        "P0354" to "Ignition coil D primary/secondary circuit malfunction",

        // Auxiliary emission controls
        "P0410" to "Secondary air injection system malfunction",
        "P0411" to "Secondary air injection system incorrect flow detected",
        "P0412" to "Secondary air injection system switching valve A circuit malfunction",
        "P0413" to "Secondary air injection system switching valve A circuit open",
        "P0414" to "Secondary air injection system switching valve A circuit shorted",
        "P0418" to "Secondary air injection system relay A circuit malfunction",
        "P0420" to "Catalyst system efficiency below threshold (bank 1)",
        "P0421" to "Warm-up catalyst efficiency below threshold (bank 1)",
        "P0440" to "Evaporative emission (EVAP) control system malfunction",
        "P0441" to "Evaporative emission (EVAP) control system incorrect purge flow",
        "P0442" to "Evaporative emission (EVAP) control system leak detected (small leak)",
        "P0443" to "Evaporative emission (EVAP) control system purge control valve circuit malfunction",
        "P0446" to "Evaporative emission (EVAP) control system vent control circuit malfunction",
        "P0451" to "Evaporative emission (EVAP) control system pressure sensor range/performance",
        "P0452" to "Evaporative emission (EVAP) control system pressure sensor low input",
        "P0453" to "Evaporative emission (EVAP) control system pressure sensor high input",
        "P0455" to "Evaporative emission (EVAP) control system leak detected (large leak / no flow; check fuel cap)",
        "P0456" to "Evaporative emission (EVAP) control system leak detected (very small leak)",
        "P0460" to "Fuel level sensor circuit malfunction",
        "P0461" to "Fuel level sensor circuit range/performance",
        "P0462" to "Fuel level sensor circuit low input",
        "P0463" to "Fuel level sensor circuit high input",
        "P0480" to "Cooling fan 1 control circuit malfunction",
        "P0481" to "Cooling fan 2 control circuit malfunction",
        "P0482" to "Cooling fan 3 control circuit malfunction",

        // Speed, idle control, auxiliary inputs
        "P0500" to "Vehicle speed sensor malfunction",
        "P0501" to "Vehicle speed sensor range/performance",
        "P0504" to "Brake switch A/B correlation",
        "P0505" to "Idle air control system malfunction",
        "P0506" to "Idle control system rpm lower than expected",
        "P0507" to "Idle control system rpm higher than expected",
        "P0511" to "Idle air control circuit malfunction",
        "P0532" to "A/C refrigerant pressure sensor circuit low input",
        "P0533" to "A/C refrigerant pressure sensor circuit high input",
        "P0550" to "Power steering pressure sensor circuit malfunction",
        "P0560" to "System voltage malfunction",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0571" to "Brake switch A circuit malfunction",

        // Computer and output circuits
        "P0600" to "Serial communication link malfunction",
        "P0601" to "Internal control module memory checksum error",
        "P0602" to "Control module programming error",
        "P0603" to "Internal control module keep-alive memory (KAM) error",
        "P0604" to "Internal control module RAM error",
        "P0605" to "Internal control module ROM error",
        "P0606" to "Control module processor fault",
        "P0610" to "Control module vehicle options error",
        "P0638" to "Throttle actuator control range/performance (bank 1)",
        "P0645" to "A/C clutch relay control circuit malfunction",

        // Transmission
        "P0700" to "Transmission control system malfunction (check TCM codes)",
        "P0703" to "Brake switch B circuit malfunction",
        "P0704" to "Clutch switch input circuit malfunction",
        "P0850" to "Park/neutral switch input circuit malfunction",

        // Generic P2xxx
        "P2004" to "Intake manifold runner control stuck open (bank 1) (RX-8: intake runner control, APV/SSV/VDI system)",
        "P2006" to "Intake manifold runner control stuck closed (bank 1) (RX-8: intake runner control, APV/SSV/VDI system)",
        "P2008" to "Intake manifold runner control circuit/open (bank 1) (RX-8: intake runner control, APV/SSV/VDI system)",
        "P2009" to "Intake manifold runner control circuit low (bank 1)",
        "P2010" to "Intake manifold runner control circuit high (bank 1)",
        "P2070" to "Intake manifold tuning valve stuck open",
        "P2071" to "Intake manifold tuning valve stuck closed",
        "P2100" to "Throttle actuator control motor circuit open",
        "P2101" to "Throttle actuator control motor circuit range/performance",
        "P2102" to "Throttle actuator control motor circuit low",
        "P2103" to "Throttle actuator control motor circuit high",
        "P2107" to "Throttle actuator control module processor fault",
        "P2108" to "Throttle actuator control module performance",
        "P2109" to "Throttle/pedal position sensor A minimum stop performance",
        "P2110" to "Throttle actuator control system forced limited rpm",
        "P2111" to "Throttle actuator control system stuck open",
        "P2112" to "Throttle actuator control system stuck closed",
        "P2119" to "Throttle actuator control throttle body range/performance",
        "P2122" to "Accelerator pedal position sensor D circuit low input",
        "P2123" to "Accelerator pedal position sensor D circuit high input",
        "P2127" to "Accelerator pedal position sensor E circuit low input",
        "P2128" to "Accelerator pedal position sensor E circuit high input",
        "P2135" to "Throttle position sensor A/B voltage correlation",
        "P2138" to "Accelerator pedal position sensor D/E voltage correlation",
        "P2177" to "System too lean off idle (bank 1)",
        "P2178" to "System too rich off idle (bank 1)",
        "P2187" to "System too lean at idle (bank 1)",
        "P2188" to "System too rich at idle (bank 1)",
        "P2195" to "O2 sensor signal stuck lean (bank 1 sensor 1)",
        "P2196" to "O2 sensor signal stuck rich (bank 1 sensor 1)",
        "P2228" to "Barometric pressure sensor circuit low input",
        "P2229" to "Barometric pressure sensor circuit high input",
        "P2502" to "Charging system voltage malfunction",
        "P2503" to "Charging system voltage low",
        "P2504" to "Charging system voltage high",

        // Mazda manufacturer-specific
        "P1170" to "Mazda: front (upstream) O2 sensor signal stuck / not switching (manufacturer-specific; confirm with service data)",
    )
}
