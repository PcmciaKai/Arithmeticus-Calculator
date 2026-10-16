package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Numbers written with 0x, 0b and 0o, the bitwise operators and the hex, octal and binary result forms. */
class NumberBaseTest {

    private fun number(text: String): List<MathNode> = MathText.fromPlainText(text)!!.nodes

    private fun op(op: MathOp) = OperatorNode(op)

    private fun line(vararg parts: Any): MathString = MathString(parts.flatMap { if (it is String) number(it) else listOf(it as MathNode) })

    private fun calc(input: MathString) = runBlocking { MathEngine.calculate(input, MathContext()) }

    private fun value(input: MathString): String {
        val res = calc(input)
        assertTrue("${res.issues}", res.isSuccess)
        return plain(res.result.toString())
    }

    /** The minus sign of a result is written " − ": compared here as a plain "-". */
    private fun plain(text: String) = text.replace(" − ", "-").replace("−", "-")

    private fun shown(input: MathString, form: ResultForm): String = plain(ResultFormatter.format(calc(input), form).toString())

    @Test
    fun prefixedNumbersHaveTheirValue() {
        assertEquals("255", value(line("0xFF")))
        assertEquals("255", value(line("0xff")))
        assertEquals("10", value(line("0b1010")))
        assertEquals("15", value(line("0o17")))
        assertEquals("0", value(line("0x0")))
        assertEquals("18446744073709551615", value(line("0xFFFFFFFFFFFFFFFF")))
    }

    @Test
    fun prefixedNumbersTakePartInArithmetic() {
        assertEquals("266", value(line("0xFF", op(MathOp.PLUS), "11")))
        assertEquals("510", value(line("0xFF", op(MathOp.MULTIPLY), "2")))
        assertEquals("-255", value(line("-0xFF")))
        assertEquals("16", value(line("0b1000", op(MathOp.PLUS), "0o10")))
    }

    @Test
    fun digitsThatDoNotBelongToTheBaseAreASyntaxError() {
        val twelve = MathString(listOf(NumberNode("0b"), NumberNode("1"), NumberNode("2")))
        assertTrue(MathIssue.SYNTAX_ERROR in calc(twelve).issues)
        val octal = MathString(listOf(NumberNode("0o"), NumberNode("8")))
        assertTrue(MathIssue.SYNTAX_ERROR in calc(octal).issues)
        val letters = MathString(listOf(NumberNode("0x"), NumberNode("G")))
        assertTrue(MathIssue.SYNTAX_ERROR in calc(letters).issues)
        // A prefix alone is not a number yet
        assertTrue(MathIssue.SYNTAX_ERROR in calc(MathString(listOf(NumberNode("0x")))).issues)
    }

    @Test
    fun pastedTextWithAPrefixBecomesANumber() {
        assertEquals("0xFF", MathText.fromPlainText("0xff")!!.toString())
        assertEquals("0b1010", MathText.fromPlainText("0B1010")!!.toString())
        assertEquals("0xDEADBEEF", MathText.fromPlainText("0xDEAD_BEEF")!!.toString())
        assertEquals("−0x10", MathText.fromPlainText("-0x10")!!.nodes.joinToString("") { if (it is OperatorNode) "−" else it.toString() })
        assertNull(MathText.fromPlainText("0b12"))
        assertNull(MathText.fromPlainText("0x"))
        // Plain decimals are as before
        assertEquals("12", MathText.fromPlainText("12")!!.toString())
    }

    @Test
    fun bitwiseOperatorsWork() {
        assertEquals("8", value(line("12", op(MathOp.AND), "10")))
        assertEquals("14", value(line("12", op(MathOp.OR), "10")))
        assertEquals("6", value(line("12", op(MathOp.XOR), "10")))
        assertEquals("16", value(line("1", op(MathOp.SHIFT_LEFT), "4")))
        assertEquals("4", value(line("16", op(MathOp.SHIFT_RIGHT), "2")))
        assertEquals("15", value(line("0xFF", op(MathOp.AND), "0b1111")))
    }

    @Test
    fun negativeNumbersBehaveAsInTwosComplement() {
        assertEquals("-6", value(MathString(listOf(FunctionNode("not", MathString(listOf(NumberNode("5"))))))))
        // The usual way to ask for 8 bits: a mask
        assertEquals("240", value(MathString(listOf(FunctionNode("not", MathString(listOf(NumberNode("1"), NumberNode("5")))), op(MathOp.AND), NumberNode("2"), NumberNode("5"), NumberNode("5")))))
        assertEquals("-8", value(line("-1", op(MathOp.SHIFT_LEFT), "3")))
        assertEquals("-1", value(line("-1", op(MathOp.SHIFT_RIGHT), "3")))
        assertEquals("4", value(line("-12", op(MathOp.AND), "7")))
    }

    @Test
    fun bitwiseOperatorsBindLikeInC() {
        // + before shifts, shifts before AND, AND before XOR before OR; all before =
        assertEquals("48", value(line("1", op(MathOp.PLUS), "2", op(MathOp.SHIFT_LEFT), "4")))
        // 1 OR ((2 AND 4) XOR 0) = 1 OR (0 XOR 0) = 1
        assertEquals("1", value(line("1", op(MathOp.OR), "2", op(MathOp.AND), "4", op(MathOp.XOR), "0")))
        // AND first, then XOR, then OR: 1 OR 2 XOR 3 AND 1 = 1 OR (2 XOR (3 AND 1)) = 1 OR (2 XOR 1) = 1 OR 3 = 3
        assertEquals("3", value(line("1", op(MathOp.OR), "2", op(MathOp.XOR), "3", op(MathOp.AND), "1")))
        assertEquals("7", value(line("1", op(MathOp.OR), "2", op(MathOp.OR), "4")))
        // 2 OR 4 AND 6 = 2 OR (4 AND 6) = 6
        assertEquals("6", value(line("2", op(MathOp.OR), "4", op(MathOp.AND), "6")))
        // = is the weakest: (12 AND 10) = 8 is true, not 12 AND (10 = 8)
        assertEquals("true", value(line("12", op(MathOp.AND), "10", op(MathOp.EQUALS), "8")).lowercase())
    }

    @Test
    fun onlyWholeNumbersHaveBits() {
        assertTrue(MathIssue.WHOLE_NUMBERS_ONLY in calc(line("2.5", op(MathOp.AND), "1")).issues)
        assertTrue(MathIssue.WHOLE_NUMBERS_ONLY in calc(line("1", op(MathOp.SHIFT_LEFT), "0.5")).issues)
        // Huge shifts are refused, not calculated
        assertTrue(MathIssue.VALUES_TOO_LARGE in calc(line("1", op(MathOp.SHIFT_LEFT), "1000000")).issues)
    }

    @Test
    fun wholeResultsCanBeShownInAnotherBase() {
        val input = line("255")
        assertEquals("0xFF", shown(input, ResultForm.HEX))
        assertEquals("0o377", shown(input, ResultForm.OCT))
        assertEquals("0b11111111", shown(input, ResultForm.BIN))
        assertEquals("-0x10", shown(line("-16"), ResultForm.HEX))
        assertEquals("0x0", shown(line("0"), ResultForm.HEX))
        // The shown text reads back as the same number
        assertEquals("255", value(ResultFormatter.format(calc(input), ResultForm.HEX)))
    }

    @Test
    fun otherResultsOnlyHaveTheirOtherForms() {
        for (text in listOf("2.5", "1")) {
            val forms = ResultFormatter.availableForms(calc(line(text)))
            assertEquals(text, text == "1", ResultForm.HEX in forms)
        }
        // A fraction, a root and a quantity are not whole numbers
        assertFalse(ResultForm.HEX in ResultFormatter.availableForms(calc(MathString(listOf(FractionNode(MathString(listOf(NumberNode("1"))), MathString(listOf(NumberNode("3")))))))))
        assertFalse(ResultForm.BIN in ResultFormatter.availableForms(calc(MathString(listOf(RadicalNode(MathString(listOf(NumberNode("2")))))))))
    }

    @Test
    fun sessionsKeepPrefixedNumbers() {
        val input = line("0xFF", op(MathOp.AND), "0b1111")
        assertEquals(input.toString(), MathStringCodec.decode(MathStringCodec.encode(input))!!.toString())
    }

    @Test
    fun aResultReusedAsInputKeepsItsPrefixInOneNode() {
        val result = calc(line("255"))
        val hex = ResultFormatter.format(result, ResultForm.HEX)
        val typed = MathText.splitNumbers(hex)
        assertEquals(listOf("0x", "F", "F"), typed.nodes.map { (it as NumberNode).text })
        assertEquals("255", value(typed))
        // A decimal result is split into its digits as before
        assertEquals(listOf("2", "5", "5"), MathText.splitNumbers(MathString(listOf(NumberNode("255")))).nodes.map { (it as NumberNode).text })
    }

    @Test
    fun shiftingRightByAHugeNumberLeavesNothing() {
        assertEquals("0", value(line("5", op(MathOp.SHIFT_RIGHT), "100000")))
        assertEquals("-1", value(line("-5", op(MathOp.SHIFT_RIGHT), "100000")))
        assertEquals("0", value(line("0", op(MathOp.SHIFT_LEFT), "100000")))
        assertTrue(MathIssue.VALUES_TOO_LARGE in calc(line("5", op(MathOp.SHIFT_LEFT), "100000")).issues)
    }
}
