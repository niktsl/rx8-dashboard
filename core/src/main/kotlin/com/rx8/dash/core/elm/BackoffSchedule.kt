package com.rx8.dash.core.elm

/**
 * Reconnect delays. Attempt 0 is the initial connect delay (lets the head unit's Bluetooth settle);
 * attempts 1..n walk [stepsMs] and then stay on the last step forever.
 */
class BackoffSchedule(
    private val initialDelayMs: Long = 3000,
    private val stepsMs: List<Long> = listOf(2000, 5000, 10000, 30000, 60000),
) {
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0 || stepsMs.isEmpty()) return initialDelayMs
        val index = minOf(attempt - 1, stepsMs.size - 1)
        return stepsMs[index]
    }
}
