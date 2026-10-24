package com.rx8.dash.core.elm

import com.rx8.dash.core.Clock
import com.rx8.dash.core.ElmIo
import com.rx8.dash.core.ElmTimeoutException

class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

/** One OBD request as seen by the fake adapter, with the header and receive filter active at the time. */
data class SentRequest(val header: String, val filter: String, val command: String)

/**
 * Scriptable ELM327. AT commands are answered like a real chip (tracking ATSH/ATCRA); OBD requests go to
 * [responder] with the current header. Commands in [timeoutOnce] time out once.
 */
class FakeElmIo(
    var identity: String = "ELM327 v1.5",
    var sti: String = "?",
    var responder: (header: String, command: String) -> String = { _, _ -> "NO DATA" },
) : ElmIo {
    val written = ArrayList<String>()
    val requests = ArrayList<SentRequest>()
    val timeoutOnce = ArrayList<String>()
    var header = "7DF"
        private set
    var filter = ""
        private set
    private var pending: String? = null
    var closed = false
        private set

    override suspend fun write(command: String) {
        written.add(command)
        pending = command
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val cmd = (pending ?: throw ElmTimeoutException("nothing pending")).replace(" ", "").uppercase()
        pending = null
        if (timeoutOnce.remove(cmd)) throw ElmTimeoutException("timeout on $cmd")
        val reply = when {
            cmd.startsWith("ATSH") -> { header = cmd.removePrefix("ATSH"); "OK" }
            cmd.startsWith("ATCRA") -> { filter = cmd.removePrefix("ATCRA"); "OK" }
            cmd == "ATZ" -> { header = "7DF"; filter = ""; "\r\rELM327 v1.5" }
            cmd == "ATI" -> identity
            cmd == "STI" -> sti
            cmd.startsWith("AT") -> "OK"
            else -> {
                requests.add(SentRequest(header, filter, cmd))
                responder(header, cmd)
            }
        }
        return "$reply\r\r"
    }

    override fun close() {
        closed = true
    }

    companion object {
        /** A healthy RX-8 PCM answering Mode 01 on [engineHeader]; TPMS answers on 751. */
        fun rx8Responder(engineHeader: String = "7E0"): (String, String) -> String = { header, cmd ->
            if (header == "751") {
                when (cmd) {
                    "22C901" -> "62C90190"
                    "22C902" -> "62C90191"
                    "22C903" -> "62C9038E"
                    "22C904" -> "62C9048F"
                    else -> "7F2231"
                }
            } else if (cmd.startsWith("22") && header == "7E0") {
                "7F2231"
            } else if (header != engineHeader) {
                "NO DATA"
            } else {
                when (cmd) {
                    "0100" -> "4100BE1FA011"
                    "0120" -> "412000000001"
                    "0140" -> "414050000000"
                    "010C" -> "410C1AF8"
                    "010D" -> "410D32"
                    "0111" -> "411140"
                    "0105" -> "41057B"
                    "010F" -> "410F44"
                    "0104" -> "410455"
                    "0106" -> "410680"
                    "0107" -> "410782"
                    "0110" -> "41100190"
                    "0142" -> "414236B0"
                    "0103" -> "41030200"
                    "0144" -> "41448000"
                    "010E" -> "410E90"
                    "010C0D" -> "410C1AF80D32"
                    else -> "NO DATA"
                }
            }
        }
    }
}
