package com.rx8.dash.core.poll

import com.rx8.dash.core.elm.Elm327Session

/** Exclusive access to the live adapter session (diagnostics, extended scanner). */
interface SessionAccess {
    /**
     * Pauses polling, runs [block] with the session exclusively, then resumes polling.
     * @throws IllegalStateException when no adapter session is connected.
     */
    suspend fun <T> withSession(block: suspend (Elm327Session) -> T): T
}
