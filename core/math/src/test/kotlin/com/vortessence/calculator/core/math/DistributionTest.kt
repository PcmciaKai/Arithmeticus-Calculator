package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.SpecialMath
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The normal, binomial and Poisson distributions. */
class DistributionTest {

    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())

    /** The input "name(a, b, ...)", each argument typed like "-2.5" or "x". */
    private fun call(name: String, vararg arguments: String): MathString {
        val argument = mutableListOf<MathNode>()
        arguments.forEachIndexed { i, text ->
            if (i > 0) argument.add(SeparatorNode)
            argument.addAll(MathText.fromPlainText(text)?.nodes ?: listOf(VariableNode(text)))
        }
        return ms(FunctionNode(name, MathString(argument)))
    }

    private fun calc(input: MathString, mode: CalculationMode = CalculationMode.EXACT) =
        runBlocking { MathEngine.calculate(input, MathContext(mode = mode)) }

    private fun value(name: String, vararg arguments: String, mode: CalculationMode = CalculationMode.EXACT): Double {
        val res = calc(call(name, *arguments), mode)
        assertTrue("$name: ${res.issues}", res.isSuccess)
        return res.numericValue
    }

    private fun exact(name: String, vararg arguments: String): String {
        val res = calc(call(name, *arguments))
        assertTrue("$name: ${res.issues}", res.isSuccess)
        return res.result.toString().replace("(", "").replace(")", "")
    }

    private fun issues(name: String, vararg arguments: String) = calc(call(name, *arguments)).issues

    private fun assertClose(expected: Double, actual: Double, relative: Double = 1e-12) =
        assertEquals(expected, actual, kotlin.math.abs(expected) * relative)

    @Test
    fun theNormalQuantileInFloatingPoint() {
        assertClose(1.9599639845400536, SpecialMath.inverseNormal(0.975))
        assertClose(-1.9599639845400538, SpecialMath.inverseNormal(0.025))
        assertClose(1.2815515655446008, SpecialMath.inverseNormal(0.9))
        assertClose(-6.361340902404056, SpecialMath.inverseNormal(1e-10))
        assertClose(3.090232306167813, SpecialMath.inverseNormal(0.999), 1e-11)
        assertClose(-3.090232306167813, SpecialMath.inverseNormal(0.001))
        assertClose(-37.0470962993612, SpecialMath.inverseNormal(1e-300))
        assertClose(4.753424308817089, SpecialMath.inverseNormal(0.999999), 1e-9)
        assertClose(-0.5244005127080407, SpecialMath.inverseNormal(0.3))
        assertEquals(0.0, SpecialMath.inverseNormal(0.5), 0.0)
        assertTrue(SpecialMath.inverseNormal(0.0).isNaN())
        assertTrue(SpecialMath.inverseNormal(1.0).isNaN())
    }

    @Test
    fun normalDistribution() {
        assertEquals("1/2", exact("normalcdf", "0"))
        assertClose(0.9750021048517795, value("normalcdf", "1.96"))
        assertClose(0.15865525393145707, value("normalcdf", "-1"))
        // Far in the tail the value keeps its digits
        assertClose(6.22096057427182e-16, value("normalcdf", "-8"), 1e-9)
        assertClose(0.9986501019683699, value("normalcdf", "3"))
        assertClose(0.9986501019683699, value("normalcdf", "130", "100", "10"))
        assertClose(0.3989422804014327, value("normalpdf", "0"))
        assertClose(0.17603266338214976, value("normalpdf", "1", "0", "2"))
        assertClose(0.9750021048517795, value("normalcdf", "1.96", "0", "1", mode = CalculationMode.NUMERIC))
        assertClose(1.9599639845400536, value("normalinv", "0.975"))
        assertClose(129.3994597681008, value("normalinv", "0.975", "100", "15"))
        assertEquals("0", exact("normalinv", "0.5"))
        assertClose(100.0, value("normalinv", "0.5", "100", "15"), 1e-12)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("normalinv", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("normalinv", "1.5"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("normalcdf", "1", "0", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("normalpdf", "1", "0", "-2"))
        // μ without σ is ambiguous
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("normalcdf", "1", "0"))
    }

    @Test
    fun theNormalDistributionIsDifferentiated() {
        val x = VariableNode("x")
        // Φ' = φ
        val cdf = calc(ms(DerivativeNode(ms(FunctionNode("normalcdf", ms(x))), ms(NumberNode("0")))))
        assertTrue("${cdf.issues}", cdf.isSuccess)
        assertClose(0.3989422804014327, cdf.numericValue)
        // φ'(1) = −φ(1)
        val pdf = calc(ms(DerivativeNode(ms(FunctionNode("normalpdf", ms(x))), ms(NumberNode("1")))))
        assertClose(-0.24197072451914337, pdf.numericValue)
        // The quantile: 1/φ(Φ⁻¹(p)) at p = 1/2
        val inverse = calc(ms(DerivativeNode(ms(FunctionNode("normalinv", ms(x))), ms(NumberNode("0.5")))))
        assertTrue("${inverse.issues}", inverse.isSuccess)
        assertClose(Math.sqrt(2 * Math.PI), inverse.numericValue)
    }

    @Test
    fun binomialProbabilitiesAreExact() {
        assertEquals("63/256", exact("binompdf", "10", "0.5", "5"))
        assertEquals("128/625", exact("binompdf", "5", "0.2", "2"))
        assertEquals("319/512", exact("binomcdf", "10", "0.5", "5"))
        // 2·(1/4)·(3/4)
        assertEquals("3/8", exact("binompdf", "2", "0.25", "1"))
        assertClose(0.8866685371230206, value("binomcdf", "20", "0.3", "8"))
        // Nothing to happen, or everything
        assertEquals("0", exact("binompdf", "5", "0.5", "6"))
        assertEquals("0", exact("binompdf", "5", "0.5", "-1"))
        assertEquals("1", exact("binomcdf", "5", "0.5", "5"))
        assertEquals("0", exact("binomcdf", "5", "0.5", "-1"))
        // The edges of p
        assertEquals("1", exact("binompdf", "5", "0", "0"))
        assertEquals("0", exact("binompdf", "5", "0", "2"))
        assertEquals("1", exact("binompdf", "5", "1", "5"))
        assertEquals("1", exact("binomcdf", "5", "1", "5"))
        assertEquals("0", exact("binomcdf", "5", "1", "4"))
    }

    @Test
    fun binomialProbabilitiesOfManyTrialsAreDecimals() {
        assertClose(0.01783901114585432, value("binompdf", "2000", "0.5", "1000"), 1e-10)
        assertClose(0.0027529546485038922, value("binompdf", "100000", "0.3", "30000"), 1e-8)
        val big = calc(call("binompdf", "2000", "0.5", "1000"))
        assertFalse(big.result.toString().contains("/"))
        assertClose(0.8866685371230206, value("binomcdf", "20", "0.3", "8", mode = CalculationMode.NUMERIC))
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), issues("binomcdf", "1000000", "0.5", "300000"))
    }

    @Test
    fun binomialArgumentsAreChecked() {
        assertEquals(listOf(MathIssue.UNDEFINED), issues("binompdf", "5", "1.5", "2"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("binompdf", "5", "-0.1", "2"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("binompdf", "-5", "0.5", "2"))
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), issues("binompdf", "2.5", "0.5", "2"))
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), issues("binompdf", "5", "0.5", "1.5"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("binompdf", "5", "0.5"))
    }

    @Test
    fun poissonProbabilities() {
        // e^(−2)·2³/3! = 4/3·e^(−2)
        val pdf = calc(call("poissonpdf", "2", "3"))
        assertTrue("${pdf.issues}", pdf.isSuccess)
        assertTrue(pdf.result.toString(), pdf.result.toString().contains("e"))
        assertClose(0.1804470443154836, pdf.numericValue)
        // e^(−2)·(1 + 2 + 2 + 4/3) = 19/3·e^(−2)
        assertClose(0.857123460498547, value("poissoncdf", "2", "3"))
        assertClose(0.857123460498547, value("poissoncdf", "2", "3", mode = CalculationMode.NUMERIC))
        assertClose(0.17138511932176867, value("poissoncdf", "100", "90"), 1e-10)
        assertClose(0.012614611348719664, value("poissonpdf", "1000", "1000"), 1e-9)
        assertEquals("0", exact("poissonpdf", "2", "-1"))
        assertEquals("1", exact("poissonpdf", "0", "0"))
        assertEquals("0", exact("poissonpdf", "0", "3"))
        assertEquals("1", exact("poissoncdf", "0", "3"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("poissonpdf", "-1", "3"))
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), issues("poissonpdf", "2", "1.5"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("poissoncdf", "2"))
    }

    @Test
    fun probabilitiesStayOpenWithVariables() {
        // The values are not known yet; the binomial and Poisson ones wait, the normal ones are written out
        assertTrue(calc(call("binompdf", "10", "p", "3")).isSuccess)
        assertTrue(calc(call("normalcdf", "x")).isSuccess)
        assertTrue(calc(call("poissonpdf", "l", "2")).isSuccess)
    }
}
