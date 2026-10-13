package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.SpecialMath
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** gamma, lngamma, erf, erfc, beta and x! for numbers that are not whole. */
class SpecialFunctionTest {

    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun n(s: String) = NumberNode(s)
    private fun minus() = OperatorNode(MathOp.MINUS)
    private fun f(name: String, vararg argument: MathNode) = FunctionNode(name, ms(*argument))
    private fun frac(a: String, b: String) = FractionNode(ms(n(a)), ms(n(b)))

    private fun calc(vararg nodes: MathNode, mode: CalculationMode = CalculationMode.EXACT): CalculationResult =
        runBlocking { MathEngine.calculate(ms(*nodes), MathContext(mode = mode)) }

    private fun exact(vararg nodes: MathNode): String {
        val res = calc(*nodes)
        assertTrue("${res.issues}", res.isSuccess)
        return res.result.toString()
    }

    private fun value(vararg nodes: MathNode, mode: CalculationMode = CalculationMode.EXACT): Double {
        val res = calc(*nodes, mode = mode)
        assertTrue("${res.issues}", res.isSuccess)
        return res.numericValue
    }

    private fun assertClose(expected: Double, actual: Double, relative: Double = 1e-13) =
        assertEquals(expected, actual, kotlin.math.abs(expected) * relative)

    @Test
    fun gammaInFloatingPoint() {
        assertClose(1.7724538509055159, SpecialMath.gamma(0.5))
        assertClose(0.886226925452758, SpecialMath.gamma(1.5))
        assertClose(52.34277778455352, SpecialMath.gamma(5.5))
        assertClose(2.3632718012073544, SpecialMath.gamma(-1.5))
        assertClose(-3.544907701811032, SpecialMath.gamma(-0.5))
        assertClose(9.513507698668732, SpecialMath.gamma(0.1))
        assertClose(99.43258511915059, SpecialMath.gamma(0.01))
        assertClose(4.170651783796603, SpecialMath.gamma(3.7))
        assertClose(5.56209241456e305, SpecialMath.gamma(170.5), 1e-11)
        assertEquals(24.0, SpecialMath.gamma(5.0), 0.0)
        assertClose(1.21645100408832e17, SpecialMath.gamma(20.0), 0.0)
        assertClose(7.257415615307998e306, SpecialMath.gamma(171.0))
        assertTrue(SpecialMath.gamma(172.0).isInfinite())
        assertTrue(SpecialMath.gamma(0.0).isNaN())
        assertTrue(SpecialMath.gamma(-3.0).isNaN())
    }

    @Test
    fun lnGammaInFloatingPoint() {
        assertClose(0.5723649429247004, SpecialMath.lnGamma(0.5))
        assertClose(359.1342053695754, SpecialMath.lnGamma(100.0))
        assertClose(2.2527126517342055, SpecialMath.lnGamma(0.1))
        assertClose(5905.220423209181, SpecialMath.lnGamma(1000.0))
        assertClose(4.599479878042022, SpecialMath.lnGamma(0.01))
        assertClose(0.9870985778947339, SpecialMath.lnGamma(3.3))
        assertClose(12815504.569147611, SpecialMath.lnGamma(1e6))
        assertTrue(SpecialMath.lnGamma(0.0).isNaN())
    }

    @Test
    fun errorFunctionInFloatingPoint() {
        assertClose(0.1124629160182849, SpecialMath.erf(0.1))
        assertClose(0.0011283787909692365, SpecialMath.erf(0.001))
        assertClose(0.5204998778130465, SpecialMath.erf(0.5))
        assertClose(0.8427007929497149, SpecialMath.erf(1.0))
        assertClose(0.9927904292352575, SpecialMath.erf(1.9))
        assertClose(0.9953222650189527, SpecialMath.erf(2.0))
        assertClose(0.999593047982555, SpecialMath.erf(2.5))
        assertClose(0.9999779095030014, SpecialMath.erf(3.0))
        assertClose(-0.8427007929497149, SpecialMath.erf(-1.0))
        assertEquals(1.0, SpecialMath.erf(30.0), 0.0)
    }

    @Test
    fun complementaryErrorFunctionKeepsItsRelativeAccuracy() {
        assertClose(0.4795001221869535, SpecialMath.erfc(0.5))
        assertClose(0.0072095707647425325, SpecialMath.erfc(1.9), 1e-12)
        assertClose(0.004677734981047266, SpecialMath.erfc(2.0), 1e-12)
        assertClose(0.0004069520174449589, SpecialMath.erfc(2.5), 1e-12)
        assertClose(2.209049699858544e-05, SpecialMath.erfc(3.0))
        assertClose(1.537459794428035e-12, SpecialMath.erfc(5.0))
        assertClose(2.088487583762545e-45, SpecialMath.erfc(10.0))
        assertClose(5.395865611607901e-176, SpecialMath.erfc(20.0))
        assertClose(1.8427007929497148, SpecialMath.erfc(-1.0))
        assertEquals(0.0, SpecialMath.erfc(40.0), 0.0)
    }

    @Test
    fun gammaOfWholeNumbersIsAFactorial() {
        assertEquals("120", exact(f("gamma", n("6"))))
        assertEquals("1", exact(f("gamma", n("1"))))
        assertEquals("1", exact(f("gamma", n("2"))))
        assertEquals(listOf(MathIssue.UNDEFINED), calc(f("gamma", n("0"))).issues)
        assertEquals(listOf(MathIssue.UNDEFINED), calc(f("gamma", minus(), n("2"))).issues)
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), calc(f("gamma", n("5000"))).issues)
    }

    @Test
    fun gammaOfHalfIntegersIsARationalMultipleOfRootPi() {
        val sqrtPi = Math.sqrt(Math.PI)
        assertEquals("sqrt(π)", exact(f("gamma", frac("1", "2"))))
        assertClose(sqrtPi / 2, value(f("gamma", frac("3", "2"))))
        assertClose(3.0 / 4 * sqrtPi, value(f("gamma", n("2.5"))))
        assertClose(-2 * sqrtPi, value(f("gamma", minus(), frac("1", "2"))))
        assertClose(4.0 / 3 * sqrtPi, value(f("gamma", minus(), n("1.5"))))
        // The result is exact, not a decimal
        val res = calc(f("gamma", n("2.5")))
        assertTrue(res.result.toString(), res.result.toString().contains("π"))
    }

    @Test
    fun otherArgumentsStaySymbolicAndAreCalculatedNumerically() {
        val res = calc(f("gamma", n("0.3")))
        assertTrue("${res.issues}", res.isSuccess)
        assertClose(2.991568987687590, res.numericValue, 1e-12)
        assertTrue(res.result.toString(), res.result.toString().contains("Γ"))
        assertClose(2.991568987687590, value(f("gamma", n("0.3")), mode = CalculationMode.NUMERIC), 1e-12)
        assertClose(Math.sqrt(Math.PI), value(f("gamma", n("0.5")), mode = CalculationMode.NUMERIC))
        assertEquals(24.0, value(f("gamma", n("5")), mode = CalculationMode.NUMERIC), 0.0)
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), calc(f("gamma", n("200.5")), mode = CalculationMode.NUMERIC).issues)
    }

    @Test
    fun lnGamma() {
        assertEquals("0", exact(f("lngamma", n("1"))))
        assertEquals("0", exact(f("lngamma", n("2"))))
        assertClose(359.1342053695754, value(f("lngamma", n("100"))))
        assertClose(0.5723649429247004, value(f("lngamma", n("0.5"))))
        assertEquals(listOf(MathIssue.UNDEFINED), calc(f("lngamma", n("0"))).issues)
        assertEquals(listOf(MathIssue.UNDEFINED), calc(f("lngamma", minus(), n("1.5"))).issues)
    }

    @Test
    fun errorFunction() {
        assertEquals("0", exact(f("erf", n("0"))))
        assertEquals("1", exact(f("erfc", n("0"))))
        assertClose(0.8427007929497149, value(f("erf", n("1"))))
        assertClose(-0.8427007929497149, value(f("erf", minus(), n("1"))))
        assertClose(0.8427007929497149, value(f("erf", n("1")), mode = CalculationMode.NUMERIC))
        assertClose(0.0004069520174449589, value(f("erfc", n("2.5"))), 1e-12)
        // Complex numbers have no error function here
        val complex = runBlocking { MathEngine.calculate(ms(f("erf", ConstantNode("i"))), MathContext(complexNumbers = true)) }
        assertEquals(listOf(MathIssue.UNDEFINED), complex.issues)
    }

    @Test
    fun factorialOfNumbersThatAreNotWhole() {
        assertEquals("120", exact(n("5"), OperatorNode(MathOp.FACTORIAL)))
        assertClose(Math.sqrt(Math.PI) / 2, value(n("0.5"), OperatorNode(MathOp.FACTORIAL)))
        assertClose(Math.sqrt(Math.PI), value(ParenthesesNode(ms(minus(), n("0.5"))), OperatorNode(MathOp.FACTORIAL)))
        assertClose(Math.sqrt(Math.PI) / 2, value(ParenthesesNode(ms(frac("1", "2"))), OperatorNode(MathOp.FACTORIAL)))
        // 2.5! = Γ(3.5)
        assertClose(3.323350970447843, value(n("2.5"), OperatorNode(MathOp.FACTORIAL)), 1e-12)
        // The negative whole numbers have no factorial
        assertEquals(listOf(MathIssue.UNDEFINED), calc(ParenthesesNode(ms(minus(), n("2"))), OperatorNode(MathOp.FACTORIAL)).issues)
    }

    @Test
    fun betaFunction() {
        assertEquals("(1)/(12)", exact(f("beta", n("2"), SeparatorNode, n("3"))))
        assertEquals("π", exact(f("beta", frac("1", "2"), SeparatorNode, frac("1", "2"))).replace(" ", ""))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), calc(f("beta", n("2"))).issues)
    }

    @Test
    fun errorFunctionIsDifferentiated() {
        val x = VariableNode("x")
        val res = calc(DerivativeNode(ms(f("erf", x))))
        assertTrue("${res.issues}", res.isSuccess)
        // 2/√π · e^(−x²) at x = 1
        val atOne = calc(DerivativeNode(ms(f("erf", x)), ms(n("1"))))
        assertTrue("${atOne.issues}", atOne.isSuccess)
        assertClose(2 / Math.sqrt(Math.PI) * Math.exp(-1.0), atOne.numericValue)
        val chain = calc(DerivativeNode(ms(f("erf", n("2"), x)), ms(n("0"))))
        assertTrue("${chain.issues}", chain.isSuccess)
        assertClose(4 / Math.sqrt(Math.PI), chain.numericValue)
        val complement = calc(DerivativeNode(ms(f("erfc", x)), ms(n("0"))))
        assertClose(-2 / Math.sqrt(Math.PI), complement.numericValue)
    }

    @Test
    fun theGaussianIsIntegratedToTheErrorFunction() {
        val x = VariableNode("x")
        // e^(−x²)
        val gauss = ms(ConstantNode("e"), ExponentNode(null, ms(minus(), x, ExponentNode(null, ms(n("2"))))))
        val plain = calc(AntiderivativeNode(gauss))
        assertTrue("${plain.issues}", plain.isSuccess)
        assertTrue(plain.result.toString(), plain.result.toString().contains("erf"))
        // The definite integral from 0 to 1 is √π/2 · erf(1)
        val definite = calc(IntegralNode(ms(n("0")), ms(n("1")), gauss))
        assertTrue("${definite.issues}", definite.isSuccess)
        assertClose(0.746824132812427, definite.numericValue, 1e-9)
    }

    @Test
    fun digammaInFloatingPoint() {
        assertClose(-0.5772156649015329, SpecialMath.digamma(1.0))
        assertClose(0.42278433509846713, SpecialMath.digamma(2.0))
        assertClose(2.2517525890667214, SpecialMath.digamma(10.0))
        assertClose(4.600161852738087, SpecialMath.digamma(100.0))
        assertClose(-1.9635100260214235, SpecialMath.digamma(0.5))
        assertClose(0.703156640645243, SpecialMath.digamma(2.5))
        assertClose(0.03648997397857645, SpecialMath.digamma(-0.5), 1e-12)
        assertClose(1.1031566406452438, SpecialMath.digamma(-2.5), 1e-12)
        assertClose(1.1671535394164323, SpecialMath.digamma(3.7), 1e-9)
        assertClose(-10.423754940411077, SpecialMath.digamma(0.1), 1e-12)
        assertTrue(SpecialMath.digamma(0.0).isNaN())
        assertTrue(SpecialMath.digamma(-4.0).isNaN())
    }

    @Test
    fun gammaAndLnGammaAreDifferentiatedWithTheDigamma() {
        val x = VariableNode("x")
        // Γ'(2) = Γ(2)·ψ(2)
        val gamma = calc(DerivativeNode(ms(f("gamma", x)), ms(n("2"))))
        assertTrue("${gamma.issues}", gamma.isSuccess)
        assertClose(0.42278433509846713, gamma.numericValue)
        val lnGamma = calc(DerivativeNode(ms(f("lngamma", x)), ms(n("1"))))
        assertClose(-0.5772156649015329, lnGamma.numericValue)
        // With the chain rule: d/dx Γ(2x) = 2·Γ(2x)·ψ(2x) at x = 1
        val chain = calc(DerivativeNode(ms(f("gamma", n("2"), x)), ms(n("1"))))
        assertClose(2 * 0.42278433509846713, chain.numericValue)
        assertEquals(listOf(MathIssue.CANT_DIFFERENTIATE), calc(DerivativeNode(ms(f("digamma", x)))).issues)
        assertClose(0.42278433509846713, value(f("digamma", n("2"))))
        assertEquals(listOf(MathIssue.UNDEFINED), calc(f("digamma", n("0"))).issues)
    }

    @Test
    fun gammaAndDigammaAreWrittenAsGreekLettersAndReadBothWays() {
        // Typed as gamma( and digamma(, results and steps write Γ( and ψ(
        val typed = calc(f("gamma", n("0.3")))
        assertTrue(typed.result.toString(), typed.result.toString().startsWith("Γ"))
        val digamma = calc(f("digamma", n("0.3")))
        assertTrue(digamma.result.toString(), digamma.result.toString().startsWith("ψ"))
        // And what is written can be read back (Ans, the history), as can the Γ key's function
        assertClose(2.991568987687590, value(f("Γ", n("0.3"))), 1e-12)
        assertClose(value(f("digamma", n("2"))), value(f("ψ", n("2"))))
        assertEquals(exact(f("gamma", n("6"))), exact(f("Γ", n("6"))))
        // The derivative reads Γ(x)·ψ(x)
        val derivative = calc(DerivativeNode(ms(f("gamma", VariableNode("x")))))
        val text = derivative.result.toString()
        assertTrue(text, text.contains("Γ") && text.contains("ψ"))
        // lngamma is written lnΓ and read back; erf keeps its name
        assertTrue(calc(f("lngamma", n("0.3"))).result.toString().startsWith("lnΓ"))
        assertClose(value(f("lngamma", n("0.3"))), value(f("lnΓ", n("0.3"))))
        assertTrue(calc(f("erf", n("0.3"))).result.toString().startsWith("erf"))
    }
}
