package com.omlight.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpcParseTest {

    @Before
    fun useKxml() {
        // android.util.Xml is a stub on the JVM; kxml2 implements the same API.
        OpcParse.newParser = { org.kxml2.io.KXmlParser() }
    }

    @Test
    fun imgList_parsesEntries() {
        // date 18324 = 2015-06-23, time 25692 = 12:34:24.
        val body = "VER_100\r\n" +
            "/DCIM/100OLYMP,P6230001.JPG,269117,32,18324,25692\r\n" +
            "/DCIM/100OLYMP,P6230002.ORF,15000000,32,18324,25692\r\n"
        val list = OpcParse.imgList(body)
        assertEquals(2, list.size)
        assertEquals("/DCIM/100OLYMP/P6230001.JPG", list[0].path)
        assertEquals(269117L, list[0].sizeBytes)
        assertFalse(list[0].isDirectory)
        assertTrue(list[1].isRaw)
        assertTrue(list[0].takenAtMillis > 0)
    }

    @Test
    fun imgList_skipsHiddenAndSystem() {
        val body = "VER_100\r\n" +
            "/DCIM/100OLYMP,.hidden,100,2,18324,25692\r\n" +
            "/DCIM/100OLYMP,sys,100,4,18324,25692\r\n" +
            "/DCIM/100OLYMP,vol,0,8,18324,25692\r\n" +
            "/DCIM,100OLYMP,0,16,18324,25692\r\n"
        val list = OpcParse.imgList(body)
        assertEquals(1, list.size)
        assertTrue(list[0].isDirectory)
        assertEquals("/DCIM/100OLYMP", list[0].path)
    }

    @Test
    fun imgList_movieDetection() {
        val body = "VER_100\r\n/DCIM/100OLYMP,P6230001.MOV,999,32,18324,25692\r\n"
        assertTrue(OpcParse.imgList(body)[0].isMovie)
    }

    @Test
    fun dcf_invalid_returnsZero() {
        assertEquals(0L, OpcParse.dcfToMillis(0, 0))
        assertEquals(0L, OpcParse.dcfToMillis(0xFFFF, 0xFFFF))
    }

    @Test
    fun xmlFlat_parsesResponse() {
        val m = OpcParse.xmlFlat(
            "<?xml version=\"1.0\"?>\n<response>\n<result>OK</result>\n</response>"
        )
        assertEquals("OK", m["result"])
    }

    @Test
    fun xmlFlat_parsesEventRoot() {
        // Push-event body uses <root> instead of <response>; same depth.
        val m = OpcParse.xmlFlat(
            "<?xml version=\"1.0\"?><root><result>ok</result>" +
                "<location>0320x0240</location><size>0080x0080</size></root>"
        )
        assertEquals("ok", m["result"])
        assertEquals("0320x0240", m["location"])
        assertEquals("0080x0080", m["size"])
    }

    @Test
    fun xmlFlat_toleratesGarbage() {
        assertTrue(OpcParse.xmlFlat("not xml at all").isEmpty())
        assertTrue(OpcParse.xmlFlat("").isEmpty())
    }

    @Test
    fun imgList_emptyDir() {
        assertTrue(OpcParse.imgList("VER_100\r\n").isEmpty())
        assertTrue(OpcParse.imgList("").isEmpty())
    }

    @Test
    fun camState_parsesCard() {
        val s = OpcParse.camState(
            "<response><cardstatus>ok</cardstatus>" +
                "<cardremainnum>42</cardremainnum>" +
                "<cardremainbyte>123456</cardremainbyte></response>"
        )
        assertTrue(s.cardMounted)
        assertEquals(42, s.remainShots)
        assertEquals(123456L, s.cardFreeBytes)
    }

    @Test
    fun camPropList_parsesDesclist() {
        val list = OpcParse.camPropList(
            "<response><desc><propname>EXP_COMP</propname>" +
                "<attribute>getset</attribute><value>0.0</value>" +
                "<enum>-1.0 0.0 +1.0</enum></desc></response>"
        )
        assertEquals(1, list.size)
        assertEquals("EXP_COMP", list[0].name)
        assertTrue(list[0].writable)
        assertEquals(listOf("-1.0", "0.0", "+1.0"), list[0].values)
    }

    @Test
    fun commandList_extractsModes() {
        val cmds = OpcParse.commandList(
            "<response><cgi name=\"switch_cammode.cgi\">" +
                "<http_method type=\"get\"><cmd name=\"mode\">" +
                "<param name=\"rec\"/><param name=\"play\"/>" +
                "</cmd></http_method></cgi></response>"
        )
        val info = CameraInfo(model = "x", commands = cmds)
        assertTrue(info.supports("switch_cammode"))
        assertTrue(info.supportsArg("switch_cammode", "mode", "rec"))
        assertFalse(info.supportsArg("switch_cammode", "mode", "shutter"))
    }
}
