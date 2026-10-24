package com.rx8.dash.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.rx8.dash.core.ElmIo
import com.rx8.dash.core.ElmIoException
import com.rx8.dash.core.ElmTimeoutException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * ELM327 over Bluetooth Classic SPP (RFCOMM).
 *
 * [connect] must be called (it switches to [Dispatchers.IO] itself) before [write]/[readUntilPrompt].
 * Discovery is cancelled first, then a secure socket is tried, then an insecure one (many clones only pair insecurely).
 * Reads poll [InputStream.available] so a silent adapter can time out instead of blocking forever.
 */
class RfcommElmIo(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter? = null,
) : ElmIo {

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    /** Opens the RFCOMM link. Throws [ElmIoException] when both secure and insecure sockets fail. */
    @SuppressLint("MissingPermission") // callers check BLUETOOTH_CONNECT (see BluetoothDevices.hasConnectPermission)
    suspend fun connect() {
        withContext(Dispatchers.IO) {
            cancelDiscoveryQuietly()
            val secureError: Exception = try {
                open(secure = true)
                return@withContext
            } catch (e: IOException) {
                e
            } catch (e: SecurityException) {
                throw ElmIoException("Bluetooth permission denied", e)
            }
            cancelDiscoveryQuietly()
            try {
                open(secure = false)
            } catch (e: IOException) {
                throw ElmIoException(
                    "Could not connect to adapter (secure: ${secureError.message}; insecure: ${e.message})",
                    e,
                )
            } catch (e: SecurityException) {
                throw ElmIoException("Bluetooth permission denied", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(secure: Boolean) {
        val s: BluetoothSocket = if (secure) {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } else {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        }
        try {
            connectCancellable(s)
            input = s.inputStream
            output = s.outputStream
            socket = s
        } catch (e: Throwable) {
            closeQuietly(s)
            throw e
        }
    }

    /** BluetoothSocket.connect() blocks; closing the socket on cancellation unblocks it. */
    @SuppressLint("MissingPermission")
    private suspend fun connectCancellable(s: BluetoothSocket) {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { closeQuietly(s) }
            try {
                s.connect()
                cont.resume(Unit)
            } catch (e: IOException) {
                cont.resumeWithException(e)
            } catch (e: SecurityException) {
                cont.resumeWithException(e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscoveryQuietly() {
        try {
            adapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            // BLUETOOTH_SCAN not granted on API 31+: discovery cannot be running from us anyway.
        } catch (e: RuntimeException) {
            // Some head-unit stacks throw here; connecting may still work.
        }
    }

    override suspend fun write(command: String) {
        val out = output ?: throw ElmIoException("Not connected")
        val inp = input
        withContext(Dispatchers.IO) {
            try {
                // Drop late bytes from a previous timed-out request so they are not read as this reply.
                if (inp != null) drain(inp)
                out.write((command + "\r").toByteArray(Charsets.US_ASCII))
                out.flush()
            } catch (e: IOException) {
                throw ElmIoException("Write failed: ${e.message}", e)
            }
        }
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val inp = input ?: throw ElmIoException("Not connected")
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            val buf = ByteArray(BUFFER_SIZE)
            val deadline = System.currentTimeMillis() + timeoutMs
            var done = false
            while (!done) {
                val available = try {
                    inp.available()
                } catch (e: IOException) {
                    throw ElmIoException("Read failed: ${e.message}", e)
                }
                if (available > 0) {
                    val n = try {
                        inp.read(buf, 0, minOf(available, buf.size))
                    } catch (e: IOException) {
                        throw ElmIoException("Read failed: ${e.message}", e)
                    }
                    if (n < 0) throw ElmIoException("Adapter closed the connection")
                    var i = 0
                    while (i < n && !done) {
                        val c = (buf[i].toInt() and 0xFF).toChar()
                        if (c == PROMPT) {
                            done = true // bytes after the prompt are dropped by the next write's drain
                        } else if (c != '\u0000') {
                            sb.append(c) // some clones pad with NULs
                        }
                        i++
                    }
                    if (sb.length > MAX_RESPONSE_CHARS) throw ElmIoException("Response too long without prompt")
                } else {
                    if (System.currentTimeMillis() >= deadline) {
                        throw ElmTimeoutException("No '>' prompt within $timeoutMs ms")
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
            sb.toString()
        }
    }

    override fun close() {
        val s = socket
        socket = null
        input = null
        output = null
        if (s != null) closeQuietly(s)
    }

    private fun drain(inp: InputStream) {
        val buf = ByteArray(BUFFER_SIZE)
        var guard = 0
        while (guard++ < MAX_DRAIN_READS) {
            val n = inp.available()
            if (n <= 0) return
            if (inp.read(buf, 0, minOf(n, buf.size)) < 0) return
        }
    }

    private fun closeQuietly(s: BluetoothSocket) {
        try {
            s.close()
        } catch (e: IOException) {
            // Already closed.
        }
    }

    companion object {
        /** Standard Serial Port Profile UUID used by ELM327 adapters. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val PROMPT = '>'
        private const val BUFFER_SIZE = 256
        private const val POLL_INTERVAL_MS = 5L
        private const val MAX_RESPONSE_CHARS = 16 * 1024
        private const val MAX_DRAIN_READS = 64
    }
}
