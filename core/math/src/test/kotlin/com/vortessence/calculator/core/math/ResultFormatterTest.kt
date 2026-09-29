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

class DecimalPlacesTest {
    private fun n(s: String) = NumberNode(s)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun calc(input: MathString, places: Int? = null, system: com.vortessence.calculator.core.math.context.UnitSystem =
        com.vortessence.calculator.core.math.context.UnitSystem.METRIC) =
        runBlocking { MathEngine.calculate(input, MathContext(decimalPlaces = places, unitSystem = system)) }

    @Test
    fun fixedDecimalPlaces() {
        val third = calc(ms(FractionNode(ms(n("1")), ms(n("3")))), places = 2)
        assertEquals("0.33", ResultFormatter.format(third, DECIMAL).toString())
        assertEquals("3.33×10^( − 1)", ResultFormatter.format(third, SCIENTIFIC).toString())
        // Trailing zeros are kept: that is the point of a fixed number of places
        assertEquals("3.50", ResultFormatter.format(calc(ms(FractionNode(ms(n("7")), ms(n("2")))), places = 2), DECIMAL).toString())
        assertEquals("5.000", ResultFormatter.format(calc(ms(n("5")), places = 3), DECIMAL).toString())
        // Exact results keep their fractions, but decimals in quantities are rounded
        assertEquals("(1)/(3)", ResultFormatter.format(third, EXACT).toString())
        assertEquals("0.91m", calc(ms(n("3"), UnitNode("ft")), places = 2).result.toString())
    }

    @Test
    fun valuesThatWouldRoundToZeroUseScientificNotation() {
        assertEquals("1.23×10^( − 4)", ResultFormatter.format(calc(ms(n("0.0001234")), places = 2), DECIMAL).toString())
        // 9.999 rounds up to 10.00 in the mantissa, so the exponent moves up
        assertEquals("1.00×10^(1)", ResultFormatter.format(calc(ms(n("9.999")), places = 2), SCIENTIFIC).toString())
    }

    @Test
    fun answersAreNeverRounded() {
        // Displayed with 2 places, reusable exactly
        val feet = calc(ms(n("3"), UnitNode("ft")), places = 2)
        assertEquals("0.9144m", feet.answer.toString())
        // A non-terminating quantity is shown as a decimal but reused as a fraction
        val metresInFeet = calc(ms(n("5300"), UnitNode("m")), system = com.vortessence.calculator.core.math.context.UnitSystem.IMPERIAL)
        assertEquals("17388.45144ft", metresInFeet.result.toString())
        assertEquals("(6625000)/(381)ft", metresInFeet.answer.toString())
        // Reusing the answer gives the same value, not a rounded one
        val reused = calc(metresInFeet.answer!!, system = com.vortessence.calculator.core.math.context.UnitSystem.IMPERIAL)
        assertEquals(metresInFeet.numericValue, reused.numericValue, 0.0)
    }
}
