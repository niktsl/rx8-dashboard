package com.rx8.dash.core.elm

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffScheduleTest {
    @Test
    fun defaultSequence() {
        val b = BackoffSchedule()
        val seq = (0..8).map { b.delayForAttempt(it) }
        assertEquals(listOf(3000L, 2000L, 5000L, 10000L, 30000L, 60000L, 60000L, 60000L, 60000L), seq)
    }

    @Test
    fun customInitialAndNegativeAttempt() {
        val b = BackoffSchedule(initialDelayMs = 500, stepsMs = listOf(100, 200))
        assertEquals(500L, b.delayForAttempt(-1))
        assertEquals(500L, b.delayForAttempt(0))
        assertEquals(100L, b.delayForAttempt(1))
        assertEquals(200L, b.delayForAttempt(2))
        assertEquals(200L, b.delayForAttempt(99))
    }
}
