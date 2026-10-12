package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.type.FunctionNames
import org.junit.Assert.*
import org.junit.Test

class FunctionNamesTest {
    @Test
    fun theLongestNameAtTheEndWins() {
        assertEquals("sin", FunctionNames.endingWord("sin"))
        assertEquals("asin", FunctionNames.endingWord("asin"))
        assertEquals("asinh", FunctionNames.endingWord("asinh"))
        assertEquals("arcsin", FunctionNames.endingWord("arcsin"))
        // Letters before the name stay variables
        assertEquals("sin", FunctionNames.endingWord("xsin"))
        assertEquals("mean", FunctionNames.endingWord("mean"))
    }

    @Test
    fun caseDoesNotMatterAndTheNameIsGivenAsWritten() {
        assertEquals("sin", FunctionNames.endingWord("SIN"))
        assertEquals("nCr", FunctionNames.endingWord("ncr"))
        assertEquals("nPr", FunctionNames.endingWord("nPR"))
        assertEquals("sqrt", FunctionNames.endingWord("Sqrt"))
    }

    @Test
    fun otherWordsAreNoFunctions() {
        assertNull(FunctionNames.endingWord(""))
        assertNull(FunctionNames.endingWord("x"))
        assertNull(FunctionNames.endingWord("sinx"))
        assertNull(FunctionNames.endingWord("foo"))
    }

    @Test
    fun aNameStartMayStillBecomeAFunction() {
        assertTrue(FunctionNames.endsWithNameStart("si"))
        assertTrue(FunctionNames.endsWithNameStart("xsi"))
        assertTrue(FunctionNames.endsWithNameStart("m"))
        assertTrue(FunctionNames.endsWithNameStart("me"))
        // A trailing n could start nCr; but nothing that cannot start a name
        assertTrue(FunctionNames.endsWithNameStart("ln"))
        assertFalse(FunctionNames.endsWithNameStart("xy"))
        assertFalse(FunctionNames.endsWithNameStart("xyz"))
        assertFalse(FunctionNames.endsWithNameStart(""))
    }
}
