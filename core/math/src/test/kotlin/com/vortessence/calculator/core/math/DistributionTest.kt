package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.numbers.SpecialMath
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import kotlin.math.pow
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

    @Test
    fun studentTInFloatingPoint() {
        // Closed forms for whole degrees of freedom
        assertClose(0.9607818798761502, SpecialMath.studentCdf(1.96, 10.0))
        assertClose(0.027245049671188126, SpecialMath.studentCdf(-2.5, 5.0))
        assertClose(0.6742760175759246, SpecialMath.studentCdf(0.5, 3.0))
        assertClose(0.8975836176504333, SpecialMath.studentCdf(3.0, 1.0))
        assertClose(0.21132486540518713, SpecialMath.studentCdf(-1.0, 2.0))
        assertClose(0.999718998188642, SpecialMath.studentCdf(10.0, 4.0))
        // (the closed form for odd degrees of freedom loses digits here; this is the tail integrated numerically)
        assertClose(1.1067408404700081e-05, SpecialMath.studentCdf(-8.0, 9.0), 1e-12)
        assertClose(0.5395058772960201, SpecialMath.studentCdf(0.1, 31.0))
        // Numerical integration for the others
        assertClose(0.8760817734568515, SpecialMath.studentCdf(1.5, 2.5))
        assertClose(0.2525278109369254, SpecialMath.studentCdf(-0.7, 7.5))
        assertClose(0.957442340488167, SpecialMath.studentCdf(2.2, 4.5))
        // Many degrees of freedom: the t distribution is nearly the normal one
        assertClose(SpecialMath.inverseNormal(0.975), SpecialMath.studentInverse(0.975, 1e9), 1e-7)
        assertClose(0.3183098861837906, SpecialMath.studentPdf(0.0, 1.0))
        assertClose(0.1591549430918953, SpecialMath.studentPdf(1.0, 1.0))
        assertClose(0.124517344646355, SpecialMath.studentPdf(1.5, 5.0))
        assertClose(0.3400943856052354, SpecialMath.studentPdf(0.3, 2.5))
        assertClose(0.06114576632121823, SpecialMath.studentPdf(-2.0, 10.0))
    }

    @Test
    fun studentQuantilesInFloatingPoint() {
        assertClose(2.228138851986274, SpecialMath.studentInverse(0.975, 10.0), 1e-11)
        assertClose(12.706204736174694, SpecialMath.studentInverse(0.975, 1.0), 1e-11)
        assertClose(4.302652729749459, SpecialMath.studentInverse(0.975, 2.0), 1e-11)
        assertClose(63.65674116287124, SpecialMath.studentInverse(0.995, 1.0), 1e-11)
        assertClose(4.540702858568109, SpecialMath.studentInverse(0.99, 3.0), 1e-11)
        assertClose(2.5705818356363146, SpecialMath.studentInverse(0.975, 5.0), 1e-11)
        assertClose(2.0422724563012338, SpecialMath.studentInverse(0.975, 30.0), 1e-11)
        assertClose(1.5332062740589438, SpecialMath.studentInverse(0.9, 4.0), 1e-11)
        assertClose(0.2631668613520226, SpecialMath.studentInverse(0.6, 7.0), 1e-11)
        assertClose(3.929633264626472, SpecialMath.studentInverse(0.999, 12.0), 1e-11)
        assertClose(-2.228138851986274, SpecialMath.studentInverse(0.025, 10.0), 1e-11)
        assertEquals(0.0, SpecialMath.studentInverse(0.5, 7.0), 0.0)
        assertTrue(SpecialMath.studentInverse(0.0, 7.0).isNaN())
        assertTrue(SpecialMath.studentInverse(0.5, 0.0).isNaN())
        // The quantile undoes the probability, also far out in the tail and for few degrees of freedom
        for ((p, df) in listOf(1e-10 to 3.0, 1e-6 to 0.5, 0.3 to 100.0, 0.999 to 1.5)) {
            assertClose(p, SpecialMath.studentCdf(SpecialMath.studentInverse(p, df), df), 1e-9)
        }
    }

    @Test
    fun chiSquareInFloatingPoint() {
        assertClose(0.9499564787512949, SpecialMath.chiSquareCdf(3.84, 1.0))
        assertClose(0.8282028557032669, SpecialMath.chiSquareCdf(5.0, 3.0))
        assertClose(0.9498909385885376, SpecialMath.chiSquareCdf(18.3, 10.0))
        assertClose(0.6321205588285577, SpecialMath.chiSquareCdf(2.0, 2.0))
        assertClose(0.999083171138544, SpecialMath.chiSquareCdf(100.0, 60.0))
        assertClose(0.0078767067673704, SpecialMath.chiSquareCdf(0.5, 5.0))
        assertClose(0.775710995165596, SpecialMath.chiSquareCdf(30.0, 25.0))
        assertClose(0.7307442128278664, SpecialMath.chiSquareCdf(7.0, 5.5))
        assertClose(0.3672305482097608, SpecialMath.chiSquareCdf(3.0, 4.5))
        assertEquals(0.0, SpecialMath.chiSquareCdf(0.0, 4.0), 0.0)
        assertClose(0.18393972058572114, SpecialMath.chiSquarePdf(2.0, 2.0))
        assertClose(0.24197072451914325, SpecialMath.chiSquarePdf(1.0, 1.0))
        assertClose(0.1673476201113224, SpecialMath.chiSquarePdf(3.0, 4.0))
        assertClose(0.07322491280963248, SpecialMath.chiSquarePdf(5.0, 3.0))
        assertClose(0.02139972988062509, SpecialMath.chiSquarePdf(0.5, 5.5))
        assertEquals(0.0, SpecialMath.chiSquarePdf(-1.0, 3.0), 0.0)
    }

    @Test
    fun chiSquareQuantilesInFloatingPoint() {
        assertClose(3.8414588206941227, SpecialMath.chiSquareInverse(0.95, 1.0), 1e-11)
        assertClose(5.991464547107977, SpecialMath.chiSquareInverse(0.95, 2.0), 1e-11)
        assertClose(18.307038053275143, SpecialMath.chiSquareInverse(0.95, 10.0), 1e-11)
        assertClose(12.832501994030014, SpecialMath.chiSquareInverse(0.975, 5.0), 1e-11)
        assertClose(15.086272469388984, SpecialMath.chiSquareInverse(0.99, 5.0), 1e-11)
        assertClose(3.9402991361190605, SpecialMath.chiSquareInverse(0.05, 10.0), 1e-11)
        assertClose(2.3659738843753377, SpecialMath.chiSquareInverse(0.5, 3.0), 1e-11)
        assertClose(0.09080403553897612, SpecialMath.chiSquareInverse(0.001, 4.0), 1e-11)
        assertClose(45.31474661812568, SpecialMath.chiSquareInverse(0.999, 20.0), 1e-11)
        assertClose(63.167121005726315, SpecialMath.chiSquareInverse(0.9, 50.0), 1e-11)
        assertTrue(SpecialMath.chiSquareInverse(1.0, 3.0).isNaN())
        for ((p, df) in listOf(1e-12 to 1.0, 1e-6 to 0.3, 0.999999 to 200.0)) {
            assertClose(p, SpecialMath.chiSquareCdf(SpecialMath.chiSquareInverse(p, df), df), 1e-8)
        }
    }

    @Test
    fun studentTFunctions() {
        assertClose(2.228138851986274, value("tinv", "0.975", "10"), 1e-11)
        assertClose(0.9607818798761502, value("tcdf", "1.96", "10"))
        assertClose(0.9607818798761502, value("tcdf", "1.96", "10", mode = CalculationMode.NUMERIC))
        assertEquals("1/2", exact("tcdf", "0", "7"))
        assertEquals("0", exact("tinv", "0.5", "7"))
        assertClose(0.3183098861837906, value("tpdf", "0", "1"))
        // The density is exact where the gamma function is: 1/π, and 1/(2π) at 1
        assertEquals("1/π", exact("tpdf", "0", "1"))
        assertClose(0.1591549430918953, value("tpdf", "1", "1"))
        assertClose(0.124517344646355, value("tpdf", "1.5", "5"))
        assertClose(0.124517344646355, value("tpdf", "1.5", "5", mode = CalculationMode.NUMERIC))
        // Many degrees of freedom are decimals
        assertClose(0.39894128303877335, value("tpdf", "0", "100000"), 1e-9)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("tpdf", "1", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("tcdf", "1", "-3"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("tinv", "1", "5"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("tinv", "0", "5"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("tcdf", "1"))
    }

    @Test
    fun chiSquareFunctions() {
        assertClose(18.307038053275143, value("chisqinv", "0.95", "10"), 1e-11)
        assertClose(0.9499564787512949, value("chisqcdf", "3.84", "1"))
        assertClose(0.7307442128278664, value("chisqcdf", "7", "5.5"))
        // Even degrees of freedom are exact: 1 − e^(−1)·(1 + 1) for x = 2, k = 4 is 1 − 2/e
        val even = calc(call("chisqcdf", "2", "4"))
        assertTrue("${even.issues}", even.isSuccess)
        assertTrue(even.result.toString(), even.result.toString().contains("e"))
        assertClose(1 - 2 / Math.E, even.numericValue)
        assertClose(0.6321205588285577, value("chisqcdf", "2", "2"))
        assertClose(0.6321205588285577, value("chisqcdf", "2", "2", mode = CalculationMode.NUMERIC))
        assertEquals("0", exact("chisqcdf", "-1", "4"))
        // The density: e^(−1)/2 at x = 2 for 2 degrees of freedom
        val pdf = calc(call("chisqpdf", "2", "2"))
        assertTrue("${pdf.issues}", pdf.isSuccess)
        assertTrue(pdf.result.toString(), pdf.result.toString().contains("e"))
        assertClose(0.18393972058572114, pdf.numericValue)
        assertClose(0.24197072451914325, value("chisqpdf", "1", "1"))
        assertClose(0.1673476201113224, value("chisqpdf", "3", "4"))
        assertClose(0.02139972988062509, value("chisqpdf", "0.5", "5.5"))
        assertEquals("0", exact("chisqpdf", "-1", "3"))
        assertEquals("0", exact("chisqpdf", "0", "3"))
        assertEquals("1/2", exact("chisqpdf", "0", "2"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("chisqpdf", "0", "1"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("chisqcdf", "1", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("chisqinv", "1", "3"))
    }

    @Test
    fun theProbabilitiesOfTAndChiSquareAreDifferentiatedToTheirDensities() {
        val x = VariableNode("x")
        val t = calc(ms(DerivativeNode(ms(FunctionNode("tcdf", ms(x, SeparatorNode, NumberNode("5")))), ms(NumberNode("1")))))
        assertTrue("${t.issues}", t.isSuccess)
        assertClose(SpecialMath.studentPdf(1.0, 5.0), t.numericValue)
        val chi = calc(ms(DerivativeNode(ms(FunctionNode("chisqcdf", ms(x, SeparatorNode, NumberNode("3")))), ms(NumberNode("2")))))
        assertTrue("${chi.issues}", chi.isSuccess)
        assertClose(SpecialMath.chiSquarePdf(2.0, 3.0), chi.numericValue)
        // The density of t itself is a formula
        val density = calc(ms(DerivativeNode(ms(FunctionNode("tpdf", ms(x, SeparatorNode, NumberNode("3")))), ms(NumberNode("1")))))
        assertTrue("${density.issues}", density.isSuccess)
        val h = 1e-6
        assertEquals((SpecialMath.studentPdf(1.0 + h, 3.0) - SpecialMath.studentPdf(1.0 - h, 3.0)) / (2 * h), density.numericValue, 1e-8)
    }

    @Test
    fun hugeDegreesOfFreedomKeepTheirDigits() {
        // Student t: the Cornish-Fisher expansion of the quantile is exact to ν⁻⁴ there
        val z = SpecialMath.inverseNormal(0.975)
        val nu = 1e6
        val expansion = z + (z * z * z + z) / (4 * nu) + (5 * z.pow(5) + 16 * z.pow(3) + 3 * z) / (96 * nu * nu) +
            (3 * z.pow(7) + 19 * z.pow(5) + 17 * z.pow(3) - 15 * z) / (384 * nu * nu * nu)
        assertClose(expansion, SpecialMath.studentInverse(0.975, nu), 1e-12)
        assertClose(z + (z * z * z + z) / (4 * 1e9), SpecialMath.studentInverse(0.975, 1e9), 1e-12)
        // The density at 0 of t is φ(0)·(1 − 1/(4ν) + 1/(32ν²) …)
        assertClose(0.3989422804014327 * (1 - 1 / (4 * 1e8) + 1 / (32 * 1e16)), SpecialMath.studentPdf(0.0, 1e8), 1e-13)
        // Chi-square at its mean: ½ plus the skewness term of the Edgeworth expansion, with the next one in 1/k
        val k = 1e6
        assertEquals(0.5 + 0.3989422804014327 * Math.sqrt(8 / k) / 6, SpecialMath.chiSquareCdf(k, k), 1e-6)
        // No jump where the formulas for large arguments take over (a = df/2 = 30)
        for (df in listOf(0.0, 1.0, 2.5)) {
            val below = 60.0 - 1e-9 + df * 0
            assertClose(SpecialMath.studentCdf(0.8, below), SpecialMath.studentCdf(0.8, below + 2e-9), 1e-8)
            assertClose(SpecialMath.studentPdf(0.8, below), SpecialMath.studentPdf(0.8, below + 2e-9), 1e-8)
            assertClose(SpecialMath.chiSquareCdf(55.0 + df, below), SpecialMath.chiSquareCdf(55.0 + df, below + 2e-9), 1e-8)
        }
    }

    @Test
    fun quantilesUndoTheProbabilitiesEverywhere() {
        val degrees = listOf(0.1, 0.5, 1.0, 1.5, 2.0, 3.0, 7.0, 10.0, 29.9, 30.0, 60.0, 100.0, 1000.0, 1e5, 1e7)
        val probabilities = listOf(1e-12, 1e-8, 1e-4, 0.01, 0.1, 0.3, 0.5, 0.7, 0.9, 0.99, 0.9999, 1 - 1e-8, 1 - 1e-12)
        val started = System.nanoTime()
        for (df in degrees) {
            for (p in probabilities) {
                val t = SpecialMath.studentInverse(p, df)
                assertTrue("tinv($p, $df) = $t", t.isFinite())
                val back = SpecialMath.studentCdf(t, df)
                // Close to 1 the probability itself has few digits: compare the tail that is the smaller one
                assertEquals("tcdf(tinv($p, $df))", minOf(p, 1 - p), minOf(back, 1 - back), minOf(p, 1 - p) * 1e-6 + 1e-15)
                // Even a chi-square variable with this many degrees of freedom
                val x = SpecialMath.chiSquareInverse(p, df)
                assertTrue("chisqinv($p, $df) = $x", x.isFinite() && x > 0.0)
                val chi = SpecialMath.chiSquareCdf(x, df)
                assertEquals("chisqcdf(chisqinv($p, $df))", minOf(p, 1 - p), minOf(chi, 1 - chi), minOf(p, 1 - p) * 1e-6 + 1e-15)
            }
        }
        // Not a calculation that takes a noticeable time
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun probabilitiesStayBetween0And1AndGrow() {
        for (df in listOf(0.1, 0.5, 1.0, 2.0, 5.5, 20.0, 59.9, 60.1, 200.0, 5000.0, 1e6)) {
            var previousT = 0.0
            var previousChi = 0.0
            for (step in 0..400) {
                val x = -50.0 + step * 0.25
                val t = SpecialMath.studentCdf(x, df)
                assertTrue("tcdf($x, $df) = $t", t in 0.0..1.0)
                assertTrue("tcdf($x, $df) = $t is below $previousT", t >= previousT - 1e-14)
                previousT = t
                val chi = SpecialMath.chiSquareCdf(x + 50.0, df)
                assertTrue("chisqcdf(${x + 50.0}, $df) = $chi", chi in 0.0..1.0)
                assertTrue("chisqcdf(${x + 50.0}, $df) = $chi is below $previousChi", chi >= previousChi - 1e-14)
                previousChi = chi
            }
        }
    }

    @Test
    fun largeCountsAreDecimalsNotWrongExactResults() {
        // The exact fractions would be hundreds of digits long: a decimal is shown, and it is the right one
        assertClose(0.03890837750398265, value("binompdf", "500", "0.3", "150"), 1e-12)
        assertClose(0.06896457740497401, value("binompdf", "150", "0.3333", "50"), 1e-12)
        assertClose(0.04349407047516401, value("binompdf", "400", "0.3", "120"), 1e-12)
        assertClose(0.023026546149187352, value("poissonpdf", "300", "300"), 1e-11)
        assertClose(0.02070242183006165, value("poissonpdf", "250.5", "260"), 1e-11)
        assertClose(1.0012290136006881e-29, value("chisqpdf", "100.5", "299"), 1e-11)
        for ((name, args) in listOf("binompdf" to listOf("500", "0.3", "150"), "poissonpdf" to listOf("300", "300"), "chisqpdf" to listOf("100.5", "299"))) {
            val text = calc(call(name, *args.toTypedArray())).result.toString()
            assertTrue("$name: $text", text.length < 40 && !text.contains("/"))
        }
        // A short exact result stays exact
        assertEquals("63/256", exact("binompdf", "10", "0.5", "5"))
        assertEquals("319/512", exact("binomcdf", "10", "0.5", "5"))
    }

    @Test
    fun hugeBinomialAndPoissonProbabilitiesKeepTheirDigits() {
        assertClose(0.0007978843613317501, SpecialMath.binomialPmf(1e6, 0.5, 500000.0), 1e-12)
        assertClose(7.978845608026659e-07, SpecialMath.binomialPmf(1e12, 0.5, 5e11), 1e-11)
        assertClose(2.5231325220201594e-08, SpecialMath.binomialPmf(1e15, 0.5, 5e14), 1e-9)
        assertClose(0.0008705631546366808, SpecialMath.binomialPmf(1e6, 0.3, 300000.0), 1e-11)
        assertClose(0.00039914186800791017, SpecialMath.binomialPmf(1e9, 0.001, 1e6), 1e-11)
        assertClose(0.17945748213541912, SpecialMath.binomialPmf(30.0, 0.2, 6.0), 1e-13)
        assertClose(0.17619705200195312, SpecialMath.binomialPmf(20.0, 0.5, 10.0), 1e-13)
        assertClose(0.1848648188248634, SpecialMath.binomialPmf(100.0, 0.99, 98.0), 1e-12)
        assertClose(0.012614611348721499, SpecialMath.poissonPmf(1000.0, 1000.0), 1e-12)
        assertClose(0.00039894224715624404, SpecialMath.poissonPmf(1e6, 1e6), 1e-11)
        assertClose(3.9894228040139945e-07, SpecialMath.poissonPmf(1e12, 1e12), 1e-11)
        assertClose(0.012636055410679864, SpecialMath.poissonPmf(0.5, 3.0), 1e-13)
        assertClose(1.4019028953083583e-25, SpecialMath.poissonPmf(1e4, 9000.0), 1e-10)
        assertClose(0.020104872145676234, SpecialMath.poissonPmf(50.0, 60.0), 1e-13)
        assertClose(0.006737946999085467, SpecialMath.poissonPmf(5.0, 0.0), 1e-14)
    }

    @Test
    fun hugeFractionsConvertToDecimalsCorrectly() {
        fun power(base: Long, exponent: Int) = (1..exponent).fold(BigInt.ONE) { acc, _ -> acc * base }
        // Both numerator and denominator are beyond a double: the quotient of the two doubles would be NaN
        assertClose(1.0, Rational.of(power(10, 400) + BigInt.ONE, power(10, 400)).toDouble(), 1e-15)
        // 3^1000 is infinite as a double, 2^1000 is not: the quotient (1.5)^1000 used to come out as infinity
        assertClose(1.2338405969061735e+176, Rational.of(power(3, 1000), power(2, 1000)).toDouble(), 1e-12)
        // And the other way round: 2^1000/3^1000 used to be 0
        assertClose(8.104774656527566e-177, Rational.of(power(2, 1000), power(3, 1000)).toDouble(), 1e-12)
        // Where the quotient is not a double at all
        assertEquals(Double.POSITIVE_INFINITY, Rational.of(power(10, 400), BigInt.ONE).toDouble(), 0.0)
        assertEquals(0.0, Rational.of(BigInt.ONE, power(10, 400)).toDouble(), 0.0)
        // Short fractions are as before
        assertEquals(1.0 / 3.0, Rational.of(1L, 3L).toDouble(), 0.0)
    }

    @Test
    fun betaOfLargeArguments() {
        // B(200, 200) = Γ(200)²/Γ(400) is about 9.7×10⁻¹²²: exact mode used to say 0, numeric mode "too large"
        assertClose(9.713217247611181e-122, value("beta", "200", "200"), 1e-10)
        assertClose(9.713217247611181e-122, value("beta", "200", "200", mode = CalculationMode.NUMERIC), 1e-10)
        // B(a, b) is about 1/a − γ for small a: just under 10⁴ for a = 0.0001
        assertClose(9987.917251638047, value("beta", "0.0001", "100000"), 1e-9)
        assertEquals("1/12", exact("beta", "2", "3"))
        assertClose(1.0 / 12, value("beta", "2", "3", mode = CalculationMode.NUMERIC), 1e-13)
        assertClose(Math.PI, value("beta", "0.5", "0.5", mode = CalculationMode.NUMERIC), 1e-13)
        // Negative arguments, where ln Γ is not defined: B(−1/2, 2) = Γ(−1/2)·Γ(2)/Γ(3/2) = −4
        assertClose(-4.0, value("beta", "-0.5", "2", mode = CalculationMode.NUMERIC), 1e-12)
        assertClose(-4.0, value("beta", "-0.5", "2"), 1e-12)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("beta", "0", "2"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("beta", "-2", "5"))
    }

    @Test
    fun theLogarithmOfTheBetaFunctionKeepsItsDigitsForLargeArguments() {
        assertClose(-138629444.05681732, SpecialMath.lnBeta(1e8, 1e8), 1e-13)
        assertClose(-70226546.06878522, SpecialMath.lnBeta(1e8, 3e7), 1e-13)
        // B(10⁶, 2) = 1/(10⁶·(10⁶ + 1))
        assertClose(-Math.log(1e6) - Math.log(1e6 + 1), SpecialMath.lnBeta(1e6, 2.0), 1e-13)
        assertClose(-891.5975491259757, SpecialMath.lnBeta(50.0, 1e9), 1e-12)
        assertClose(-530.9482011382281, SpecialMath.lnBeta(300.0, 500.0), 1e-12)
        assertClose(-16.602059876016597, SpecialMath.lnBeta(12.0, 12.0), 1e-12)
        assertClose(Math.log(1.0 / 12), SpecialMath.lnBeta(2.0, 3.0), 1e-14)
        assertTrue(SpecialMath.lnBeta(0.0, 2.0).isNaN())
    }

    @Test
    fun theExactResultOfTheLargestCountsIsQuick() {
        // Up to 500 trials the exact fractions are tried first: even for many digits in p that is a matter of milliseconds
        val started = System.nanoTime()
        for ((name, args) in listOf(
            "binomcdf" to listOf("500", "0.123456789", "250"), "binompdf" to listOf("500", "0.123456789", "250"),
            "poissoncdf" to listOf("499.5", "499"), "chisqcdf" to listOf("300.5", "600"), "chisqpdf" to listOf("100.5", "299")
        )) {
            val res = calc(call(name, *args.toTypedArray()))
            assertTrue("$name: ${res.issues}", res.isSuccess)
        }
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 3_000_000_000L)
    }
}
