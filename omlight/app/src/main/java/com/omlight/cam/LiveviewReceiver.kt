package com.omlight.cam

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * Minimal RTP/JPEG live-view receiver (spec §4).
 *
 * Frame format: each UDP datagram is one RTP packet; the JPEG sub-frame
 * is the datagram payload after the 12-byte RTP header (+ extension on the
 * first packet of a frame, X=1). The M (marker) bit ends the JPEG.
 * Concatenate payloads with M=0..1 and you get one complete JPEG
 * (FFD8..FFD9). No reordering: drop the frame on sequence gap.
 *
 * Camera settings ride in the extension header of the first packet; we
 * skip it (length word counts 32-bit words after the 4-byte header).
 */
class LiveviewReceiver(port: Int = 0) : AutoCloseable {

    private val sock = DatagramSocket(port).apply { soTimeout = 3_000 }
    /** Actual bound port — pass to `startliveview`. */
    val port: Int get() = sock.localPort

    private val buf = ByteArray(65535)
    private val frame = java.io.ByteArrayOutputStream(256 * 1024)
    private var expectSeq = -1

    /**
     * Block for the next complete JPEG frame. Returns null on timeout
     * (caller decides whether to keep waiting).
     */
    @Throws(IOException::class)
    fun nextFrame(): ByteArray? {
        frame.reset()
        expectSeq = -1
        while (true) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (_: SocketTimeoutException) {
                return if (frame.size() == 0) null else null // drop partial
            }
            val len = pkt.length
            if (len < 12) continue
            val b = pkt.data
            if ((b[0].toInt() shr 6) and 0x03 != 2) continue // V=2
            val seq = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            val marker = (b[1].toInt() and 0x80) != 0
            var off = 12 + 4 * (b[0].toInt() and 0x0F) // CC CSRC words
            if ((b[0].toInt() and 0x10) != 0) { // X=1: extension header
                if (len < off + 4) continue
                val words = ((b[off + 2].toInt() and 0xFF) shl 8) or
                    (b[off + 3].toInt() and 0xFF)
                off += 4 + words * 4
            }
            if (off >= len) continue
            // Fresh frame starts with FFD8; resync on it.
            val isStart = len - off >= 2 && b[off] == 0xFF.toByte() && b[off + 1] == 0xD8.toByte()
            if (isStart && frame.size() > 0) {
                // Previous frame never got its marker: restart from here.
                frame.reset()
            }
            if (expectSeq >= 0 && seq != expectSeq) {
                // Gap: drop partial frame, resync at next FFD8.
                frame.reset()
                if (!isStart) {
                    expectSeq = -1
                    continue
                }
            }
            expectSeq = (seq + 1) and 0xFFFF
            frame.write(b, off, len - off)
            if (marker) {
                val jpeg = frame.toByteArray()
                frame.reset()
                expectSeq = -1
                // Sanity: must end with FFD9.
                if (jpeg.size >= 2 && jpeg[jpeg.size - 2] == 0xFF.toByte() &&
                    jpeg[jpeg.size - 1] == 0xD9.toByte()
                ) {
                    return jpeg
                }
                // Corrupt: keep waiting for the next frame.
            }
            if (frame.size() > MAX_FRAME) frame.reset() // runaway guard
        }
    }

    override fun close() {
        runCatching { sock.close() }
    }

    companion object {
        const val MAX_FRAME = 4 * 1024 * 1024
    }
}
