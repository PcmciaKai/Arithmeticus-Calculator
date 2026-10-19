package com.vortessence.arithmeticus.ui.graph

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphNumberFormatTest {
    @Test
    fun formatsWithSignificantDigits() {
        assertEquals("1.41421", formatGraphNumber(1.41421356))
        assertEquals("-2", formatGraphNumber(-2.0000000001))
        assertEquals("0", formatGraphNumber(3e-12))
        assertEquals("1.5×10⁸", formatGraphNumber(1.5e8))
        assertEquals("2.5×10⁻⁵", formatGraphNumber(2.5e-5))
        assertEquals("undefined", formatGraphNumber(Double.NaN))
    }
}
