package com.omlight.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real-body fixtures: E-M10 public capture (0l.de) + our own E-M5 II
 * dump. Guards the AST parser and dialect detection against the exact
 * bytes shipping cameras send.
 */
class CapabilityFixtureTest {

    @Before
    fun useKxml() {
        OpcParse.newParser = { org.kxml2.io.KXmlParser() }
    }

    private fun load(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().readText()

    @Test
    fun em10_parses() {
        val doc = OpcParse.capabilityDoc(load("em10_commandlist.xml"))
        assertEquals("2.20", doc.version)
        assertTrue(doc.support.contains("remote"))
        assertTrue(doc.supports("switch_cammode"))
        assertTrue(doc.supports("exec_shutter"))
        assertTrue(doc.supportsArg("switch_cammode", "mode", "shutter"))
        assertTrue(doc.supportsArg("exec_takemotion", "com", "starttake"))
        assertFalse(doc.supportsArg("exec_takemotion", "com", "newstarttake"))
        assertTrue(doc.supportsArg("exec_takemisc", "com", "ctrlzoom"))
        // Nested: lvqty only under mode=rec.
        val lv = doc.nestedValues("switch_cammode", "mode", "rec", "lvqty")
        assertTrue(lv.contains("0640x0480"))
        // No push events on E-M10 either.
        assertFalse(doc.supports("start_pushevent"))
    }

    @Test
    fun em5_parses() {
        val doc = OpcParse.capabilityDoc(load("em5_commandlist.xml"))
        assertEquals("4.00", doc.version)
        assertTrue(doc.supportsArg("exec_takemotion", "com", "starttake"))
        assertTrue(doc.supportsArg("exec_takemisc", "com", "ctrlzoom"))
        assertTrue(doc.supportsArg("exec_shutter", "com", "1stpush"))
        val lv = doc.nestedValues("switch_cammode", "mode", "rec", "lvqty")
        assertEquals(5, lv.size)
        assertFalse(doc.supports("start_pushevent"))
        assertFalse(doc.supports("exec_erase"))
    }

    @Test
    fun em5_props_known() {
        val doc = OpcParse.capabilityDoc(load("em5_commandlist.xml"))
        val descs = doc.nestedValues("get_camprop", "com", "desc", "propname")
        assertTrue(descs.contains("expcomp"))
        assertTrue(descs.contains("desclist"))
        val sets = doc.nestedValues("set_camprop", "com", "set", "propname")
        assertTrue(sets.contains("expcomp"))
    }
}
