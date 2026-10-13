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
}
