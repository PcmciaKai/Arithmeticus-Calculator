package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.type.*
import com.vortessence.calculator.core.math.type.ResultForm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ResultFormatterTest {
    private fun n(s: String) = NumberNode(s)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun frac(num: String, den: String) = FractionNode(ms(n(num)), ms(n(den)))
    private fun calc(input: MathString) = runBlocking { MathEngine.calculate(input, MathContext()) }
    private fun format(input: MathString, form: ResultForm) = ResultFormatter.format(calc(input), form).toString()

    @Test
    fun formsOfAFraction() {
        val sevenHalves = ms(frac("7", "2"))
        assertEquals(listOf(EXACT, DECIMAL, SCIENTIFIC, MIXED), ResultFormatter.availableForms(calc(sevenHalves)))
        assertEquals("(7)/(2)", format(sevenHalves, EXACT))
        assertEquals("3.5", format(sevenHalves, DECIMAL))
        assertEquals("3.5×10^(0)", format(sevenHalves, SCIENTIFIC))
        assertEquals("3(1)/(2)", format(sevenHalves, MIXED))
    }

    @Test
    fun decimalsHaveNoTrailingZerosAndKeepUnits() {
        assertEquals("0.3333333333", format(ms(frac("1", "3")), DECIMAL))
        assertEquals("0.5", format(ms(frac("1", "2")), DECIMAL))
        assertEquals("3.333333333×10^( − 1)", format(ms(frac("1", "3")), SCIENTIFIC))
        assertEquals(" − 3.333333333×10^(1)", format(ms(OperatorNode(MathOp.MINUS), n("33.33333333333")), SCIENTIFIC))
        assertEquals("1500m", format(ms(n("1.5"), UnitNode("km")), DECIMAL))
        assertEquals("1.5×10^(3)m", format(ms(n("1.5"), UnitNode("km")), SCIENTIFIC))
    }

    @Test
    fun formsThatDoNotApplyFallBackToExact() {
        // Mixed only for improper fractions, numeric forms not for equations
        assertEquals("(1)/(2)", format(ms(frac("1", "2")), MIXED))
        val equation = ms(VariableNode("x"), OperatorNode(MathOp.EQUALS), n("2"))
        assertEquals(listOf(EXACT), ResultFormatter.availableForms(calc(equation)))
        assertEquals("x = 2", format(equation, DECIMAL))
    }
}
