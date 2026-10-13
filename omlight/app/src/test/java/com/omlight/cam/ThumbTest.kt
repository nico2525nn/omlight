package com.omlight.cam

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbTest {

    @Test
    fun degreesFor_exifValues() {
        assertEquals(0, Thumb.degreesFor("1"))
        assertEquals(180, Thumb.degreesFor("3"))
        assertEquals(90, Thumb.degreesFor("6"))
        assertEquals(270, Thumb.degreesFor("8"))
    }

    @Test
    fun degreesFor_missingOrUnknownIsUpright() {
        assertEquals(0, Thumb.degreesFor(null))
        assertEquals(0, Thumb.degreesFor(""))
        assertEquals(90, Thumb.degreesFor(" 6 "))
        assertEquals(0, Thumb.degreesFor("bogus"))
    }
}
