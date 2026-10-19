package com.vortessence.arithmeticus.ui.math

import com.vortessence.arithmeticus.ui.graph.formatGraphNumber
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DecimalMarkTest {
    @After
    fun reset() {
        DecimalMark.comma = false
    }

    @Test
    fun theDecimalPointIsTheDefault() {
        assertEquals("1.5", DecimalMark.display("1.5"))
        assertEquals(", ", DecimalMark.argumentSeparator)
    }

    @Test
    fun theDecimalCommaOnlyChangesTheDisplay() {
        DecimalMark.comma = true
        assertEquals("1,5", DecimalMark.display("1.5"))
        assertEquals("-0,25", DecimalMark.display("-0.25"))
        assertEquals("12", DecimalMark.display("12"))
        // a comma next to decimal commas would be ambiguous
        assertEquals("; ", DecimalMark.argumentSeparator)
        assertEquals("2,5", formatGraphNumber(2.5))
        assertEquals("1,5×10⁻⁵", formatGraphNumber(0.000015))
    }
}
