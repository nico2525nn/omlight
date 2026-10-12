package com.omlight.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQrTest {

    @Test
    fun parsesStandardPayload() {
        val c = WifiQr.parse("WIFI:T:WPA;S:OLYMPUS-E-M5-II;P:secret123;;")
        assertEquals("OLYMPUS-E-M5-II", c!!.ssid)
        assertEquals("secret123", c.pass)
        assertFalse(c.hidden)
    }

    @Test
    fun unescapesSpecials() {
        val c = WifiQr.parse("""WIFI:S:AB\;CD;P:X\:Y;;""")
        assertEquals("AB;CD", c!!.ssid)
        assertEquals("X:Y", c.pass)
    }

    @Test
    fun rejectsNonWifi() {
        assertNull(WifiQr.parse("https://example.com"))
        assertNull(WifiQr.parse(""))
        assertNull(WifiQr.parse("WIFI:T:WPA;;"))
    }

    @Test
    fun detectsCameraSsids() {
        assertTrue(WifiQr.looksLikeCamera("OLYMPUS-ABCD"))
        assertTrue(WifiQr.looksLikeCamera("OM-1-1234"))
        assertTrue(WifiQr.looksLikeCamera("E-M5MarkII"))
        assertFalse(WifiQr.looksLikeCamera("HomeWiFi"))
    }
}
