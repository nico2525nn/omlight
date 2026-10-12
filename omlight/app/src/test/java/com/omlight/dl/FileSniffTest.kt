package com.omlight.dl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSniffTest {

    @Test
    fun jpegMatches() {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertTrue(FileSniff.matches("P001.JPG", head))
        assertFalse(FileSniff.matches("P001.ORF", head))
    }

    @Test
    fun orfLittleEndianMatches() {
        val head = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)
        assertTrue(FileSniff.matches("P001.ORF", head))
    }

    @Test
    fun errorPageRejected() {
        val html = "<html><body>404</body></html>".toByteArray()
        assertTrue(FileSniff.looksLikeError(html))
        assertFalse(FileSniff.matches("P001.JPG", html))
        val xml = "<?xml version=\"1.0\"?>".toByteArray()
        assertTrue(FileSniff.looksLikeError(xml))
    }

    @Test
    fun emptyRejected() {
        assertTrue(FileSniff.looksLikeError(ByteArray(0)))
        assertFalse(FileSniff.matches("P001.JPG", ByteArray(0)))
    }

    @Test
    fun movFtypMatches() {
        val head = byteArrayOf(0, 0, 0, 0x14, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        assertTrue(FileSniff.matches("P001.MOV", head))
    }
}
