package com.omlight.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoInfoTest {

    @Test
    fun cgi_pretty_and_suffix() {
        val info = PhotoInfoParse.fromCgi(
            mapOf(
                "shutspeedvalue" to "250",
                "expcomp" to "0.0",
                "wbvalue" to "auto",
                "weirdkey" to "x",
            )
        )
        val m = info.rows.toMap()
        assertEquals("250", m["シャッター"])
        assertEquals("0.0EV", m["露出補正"])
        assertEquals("auto", m["ホワイトバランス"])
        assertEquals("x", m["weirdkey"])
    }

    @Test
    fun merge_prefers_cgi() {
        val cgi = PhotoInfoParse.fromCgi(mapOf("expcomp" to "1.0"))
        val exif = PhotoInfo(listOf("露出補正" to "0EV", "ISO" to "200"))
        val merged = PhotoInfoParse.merge(cgi, exif).rows.toMap()
        assertEquals("1.0EV", merged["露出補正"])
        assertEquals("200", merged["ISO"])
    }

    @Test
    fun merge_empty_sides() {
        val exif = PhotoInfo(listOf("ISO" to "200"))
        assertTrue(PhotoInfoParse.merge(PhotoInfo(), exif).rows.isNotEmpty())
        assertTrue(PhotoInfoParse.merge(exif, PhotoInfo()).rows.isNotEmpty())
        assertTrue(PhotoInfoParse.merge(PhotoInfo(), PhotoInfo()).isEmpty)
    }

    @Test
    fun tiff_rejects_non_tiff() {
        assertTrue(PhotoInfoParse.fromTiff(ByteArray(0)).isEmpty)
        assertTrue(PhotoInfoParse.fromTiff("<?xml version".toByteArray()).isEmpty)
        assertTrue(PhotoInfoParse.fromTiff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)).isEmpty)
    }

    @Test
    fun tiff_reads_orf_head() {
        // Synthetic little-endian ORF head: "IIRO" + IFD0 with Model +
        // ExifIFD pointer, ExifIFD with F-number 28/10 + ISO 200.
        val buf = ByteArray(256)
        fun p16(off: Int, v: Int) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun p32(off: Int, v: Int) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v shr 8) and 0xFF).toByte()
            buf[off + 2] = ((v shr 16) and 0xFF).toByte()
            buf[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        "IIRO".toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
        p32(4, 8) // IFD0 at 8
        p16(8, 2) // 2 entries
        // Model (0x0110, ascii, "E-M10\0" at 100)
        p16(10, 0x0110)
        p16(12, 2)
        p32(14, 6)
        p32(18, 100)
        // ExifIFD pointer (0x8769, long, offset 120)
        p16(22, 0x8769)
        p16(24, 4)
        p32(26, 1)
        p32(30, 120)
        "E-M10\u0000".toByteArray(Charsets.US_ASCII).copyInto(buf, 100)
        // ExifIFD at 120: FNumber + ISO
        p16(120, 2)
        p16(122, 0x829D) // FNumber rational at 150
        p16(124, 5)
        p32(126, 1)
        p32(130, 150)
        p16(134, 0x8827) // ISO short inline
        p16(136, 3)
        p32(138, 1)
        p16(142, 200)
        p32(150, 28) // 28/10 = F2.8
        p32(154, 10)
        val m = PhotoInfoParse.fromTiff(buf).rows.toMap()
        assertEquals("E-M10", m["カメラ"])
        assertEquals("F2.8", m["絞り"])
        assertEquals("200", m["ISO"])
    }

    @Test
    fun tiff_truncated_is_safe() {
        // Header claims IFD past the buffer: no crash, partial rows.
        val buf = ByteArray(16)
        buf[0] = 'I'.code.toByte()
        buf[1] = 'I'.code.toByte()
        buf[2] = 42
        buf[4] = 100 // IFD0 beyond end
        assertTrue(PhotoInfoParse.fromTiff(buf).isEmpty)
    }
}
