package com.omlight.cam

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Camera event notification receiver (spec §5).
 *
 * Sequence: app calls `start_pushevent.cgi?port=P` -> camera opens TCP port
 * P and listens -> app connects to P -> camera pushes event frames.
 * Frame: appID(2B) + event(2B) + length(2B) + XML body, big-endian.
 *
 * Runs on its own scope; [events] is a hot flow the UI/session subscribes
 * to. Reconnects are the session's job (call [stop]/[start] around them).
 */
class EventReceiver(private val host: String) {

    data class Event(
        val appId: Int,
        val eventId: Int,
        /** Parsed XML children, e.g. result/location/size for AF result. */
        val info: Map<String, String> = emptyMap(),
    ) {
        companion object {
            // Camera event list (spec §5).
            const val BATTERY = 5
            const val AF_RESULT = 101
            const val READY = 102
            const val CAPTURE_STARTED = 103
            const val CAPTURE_FINISHED = 106
            const val CAPTURE_DONE = 107
            const val RECView = 108
            const val MOVIE_STOPPED = 110
            const val PROGRESS = 111
            const val IMG_TRANSFER_REQ = 117
            const val LENS_MOUNT = 120
            const val LENS_DRIVE_STOP = 122
            const val CARD_MOUNT = 132
            const val TEMP = 133
            const val PROTECT_REMOVED = 134
            const val MOVIE_STARTED = 135
            const val MODE_CHANGED = 201
            const val PROP_CHANGED = 206
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile var running = false
        private set

    /**
     * Connect to the camera's event port and stream until [stop] or error.
     * Retries the TCP connect a few times: the camera opens the port
     * asynchronously after ACKing start_pushevent.
     */
    fun start(port: Int) {
        if (job != null) return
        running = true
        job = scope.launch {
            var attempt = 0
            var sock: Socket? = null
            try {
                // Connect with backoff (camera opens the port async).
                while (isActive && sock == null && attempt < 10) {
                    try {
                        sock = Socket()
                        sock.tcpNoDelay = true
                        sock.soTimeout = 0 // blocking reads; closed by stop()
                        sock.connect(InetSocketAddress(host, port), 2_000)
                    } catch (_: IOException) {
                        runCatching { sock?.close() }
                        sock = null
                        attempt++
                        delay(300L * attempt)
                    }
                }
                val s = sock ?: return@launch
                s.use {
                    val input = it.getInputStream()
                    val hdr = ByteArray(6)
                    while (isActive) {
                        readFully(input, hdr, 6)
                        val appId = u16(hdr, 0)
                        val eventId = u16(hdr, 2)
                        val len = u16(hdr, 4)
                        val body = if (len > 0) {
                            if (len > MAX_BODY) throw IOException("event body too large: $len")
                            val b = ByteArray(len)
                            readFully(input, b, len)
                            // XML: <root><result>ok</result>...</root>
                            OpcParse.xmlFlat(String(b, Charsets.UTF_8))
                        } else {
                            emptyMap()
                        }
                        _events.tryEmit(Event(appId, eventId, body))
                    }
                }
            } catch (_: EOFException) {
                // Camera closed: normal on stop_pushevent / mode change.
            } catch (_: IOException) {
                // Link lost; session decides whether to restart.
            } finally {
                runCatching { sock?.close() }
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
    }

    fun close() {
        stop()
        scope.cancel()
    }

    companion object {
        const val MAX_BODY = 64 * 1024

        private fun u16(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        @Throws(IOException::class)
        private fun readFully(input: java.io.InputStream, b: ByteArray, n: Int) {
            var off = 0
            while (off < n) {
                val r = input.read(b, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
        }
    }
}
