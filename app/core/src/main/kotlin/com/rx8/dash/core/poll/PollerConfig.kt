package com.rx8.dash.core.poll

data class PollerConfig(
    /** Poll the FAST tier every cycle; when false it is polled on alternate cycles. */
    val fastEveryCycle: Boolean = true,
    /** Minimum age before a MEDIUM PID is polled again (actual rate is bounded by the adapter). */
    val mediumIntervalMs: Long = 500,
    /** Interval of the SLOW batch (Mode 22 extended + TPMS). */
    val slowIntervalMs: Long = 8000,
    /** Poll Mazda Mode 22 engine DIDs (opt-in, unvalidated). */
    val extendedEnabled: Boolean = false,
    /** Poll the TPMS module on 751/759 in the SLOW batch (opt-in, unvalidated). */
    val tpmsEnabled: Boolean = false,
)
