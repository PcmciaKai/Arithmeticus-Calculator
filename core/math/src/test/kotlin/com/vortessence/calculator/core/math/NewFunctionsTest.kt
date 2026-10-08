package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Percent, mod, complex numbers, Σ, ∏, limits and ∞. */
class NewFunctionsTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private val k = VariableNode("k")
    private val i = ConstantNode("i")
    private val pi = ConstantNode("π")
    private val e = ConstantNode("e")
    private val inf = ConstantNode("∞")
    private fun op(o: MathOp) = OperatorNode(o)
    private val plus = op(MathOp.PLUS)
    private val minus = op(MathOp.MINUS)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg exponent: MathNode) = ExponentNode(null, ms(*exponent))
    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))
    private fun frac(num: MathString, den: MathString) = FractionNode(num, den)
    private fun sqrt(vararg radicand: MathNode) = RadicalNode(null, ms(*radicand))
    private fun paren(vararg inner: MathNode) = ParenthesesNode(ms(*inner))
    private fun sum(from: MathString, to: MathString, vararg body: MathNode) =
        BigOperatorNode(BigOperator.SUM, ms(k, op(MathOp.EQUALS), *from.nodes.toTypedArray()), to, ms(*body))
    private fun prod(from: MathString, to: MathString, vararg body: MathNode) =
        BigOperatorNode(BigOperator.PRODUCT, ms(k, op(MathOp.EQUALS), *from.nodes.toTypedArray()), to, ms(*body))
    private fun lim(to: MathString, vararg body: MathNode) =
        LimitNode(ms(x, op(MathOp.CONVERT), *to.nodes.toTypedArray()), ms(*body))

    private fun calc(
        vararg nodes: MathNode,
        complex: Boolean = true,
        mode: CalculationMode = CalculationMode.EXACT
    ): CalculationResult = runBlocking {
        MathEngine.calculate(ms(*nodes), MathContext(mode = mode, complexNumbers = complex))
    }

    private fun assertResult(expected: String, res: CalculationResult) {
        assertTrue("Expected $expected but got ${res.issues}", res.isSuccess)
        assertEquals(expected, res.result.toString())
    }

    private fun assertValue(expected: Double, res: CalculationResult, tolerance: Double = 1e-9) {
        assertTrue("Expected $expected but got ${res.issues}", res.isSuccess)
        assertEquals(expected, res.numericValue, tolerance)
    }

    @Test
    fun percent() {
        assertResult("(1)/(4)", calc(n("25"), op(MathOp.PERCENT)))
        assertResult("50", calc(n("200"), op(MathOp.MULTIPLY), n("25"), op(MathOp.PERCENT)))
        assertResult("6", calc(n("20"), op(MathOp.PERCENT), n("30")))
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(n("2"), plus, op(MathOp.PERCENT)).issues)
    }

    @Test
    fun modulo() {
        val mod = op(MathOp.MODULO)
        assertResult("1", calc(n("7"), mod, n("3")))
        assertResult("2", calc(minus, n("7"), mod, n("3")))
        assertResult("(3)/(2)", calc(n("7.5"), mod, n("2")))
        assertResult("π − 3", calc(pi, mod, n("3")))
        // Binds like ×: 2 + 7 mod 3 = 3
        assertResult("3", calc(n("2"), plus, n("7"), mod, n("3")))
        assertEquals(listOf(MathIssue.DIVISION_BY_ZERO), calc(n("7"), mod, n("0")).issues)
        // Written back so that it parses the same
        assertResult("x mod 3", calc(x, mod, n("3")))
        assertResult("x mod (2x)", calc(x, mod, paren(n("2"), x)))
    }

    @Test
    fun imaginaryUnit() {
        assertResult(" − 1", calc(i, pow(n("2"))))
        assertResult(" − i", calc(i, pow(n("3"))))
        assertResult("1", calc(i, pow(n("100"))))
        assertResult("2i", calc(paren(n("1"), plus, i), pow(n("2"))))
        assertResult("2", calc(paren(n("1"), plus, i), paren(n("1"), minus, i)))
        assertResult(" − 2 + 2i", calc(n("2"), i, paren(n("1"), plus, i)))
        assertResult("(1)/(2) − (i)/(2)", calc(frac(ms(n("1")), ms(n("1"), plus, i))))
        assertResult("5i", calc(n("2"), i, plus, n("3"), i))
    }

    @Test
    fun rootsAndPowersOfNegativeNumbers() {
        assertResult("2i", calc(sqrt(minus, n("4"))))
        assertResult("2isqrt(2)", calc(sqrt(minus, n("8"))))
        // Odd roots stay real
        assertResult(" − 2", calc(RadicalNode(ms(n("3")), ms(minus, n("8")))))
        // Euler's identity
        assertResult(" − 1", calc(e, pow(i, pi)))
        val eToIPiHalf = calc(e, pow(frac(ms(i, pi), ms(n("2")))))
        assertResult("i", eToIPiHalf)
    }

    @Test
    fun complexFunctions() {
        assertResult("πi", calc(f("ln", minus, n("1"))))
        assertResult("ln(2) + πi", calc(f("ln", minus, n("2"))))
        assertResult("5", calc(f("abs", n("3"), plus, n("4"), i)))
        val sinI = calc(f("sin", i))
        assertTrue(sinI.isSuccess)
        assertEquals(0.0, sinI.numericValue, 1e-12)
        assertEquals(kotlin.math.sinh(1.0), sinI.numericImaginary, 1e-12)
    }

    @Test
    fun complexResultForms() {
        val res = calc(frac(ms(n("1")), ms(n("1"), plus, i)))
        assertEquals(0.5, res.numericValue, 1e-15)
        assertEquals(-0.5, res.numericImaginary, 1e-15)
        assertEquals(listOf(ResultForm.EXACT, ResultForm.DECIMAL, ResultForm.SCIENTIFIC), ResultFormatter.availableForms(res))
        assertEquals("0.5 − 0.5i", ResultFormatter.format(res, ResultForm.DECIMAL).toString())
        assertEquals(" − i", ResultFormatter.format(calc(i, pow(n("3"))), ResultForm.DECIMAL).toString())
    }

    @Test
    fun quadraticEquationsHaveComplexRoots() {
        val res = calc(x, pow(n("2")), plus, n("1"), op(MathOp.EQUALS), n("0"))
        assertResult("x = i, x =  − i", res)
        assertEquals(listOf(MathIssue.NO_REAL_SOLUTION), calc(x, pow(n("2")), plus, n("1"), op(MathOp.EQUALS), n("0"), complex = false).issues)
    }

    @Test
    fun complexNumbersCanBeSwitchedOff() {
        assertEquals(listOf(MathIssue.COMPLEX_RESULT), calc(i, pow(n("2")), complex = false).issues)
        assertEquals(listOf(MathIssue.NEGATIVE_ROOT), calc(sqrt(minus, n("4")), complex = false).issues)
    }

    @Test
    fun finiteSumsAndProducts() {
        assertResult("5050", calc(sum(ms(n("1")), ms(n("100")), k)))
        assertResult("(49)/(36)", calc(sum(ms(n("1")), ms(n("3")), frac(ms(n("1")), ms(k, pow(n("2")))))))
        assertResult("6x", calc(sum(ms(n("1")), ms(n("3")), k, x)))
        assertResult("120", calc(prod(ms(n("1")), ms(n("5")), k)))
        // Empty range
        assertResult("0", calc(sum(ms(n("5")), ms(n("1")), k)))
        assertResult("1", calc(prod(ms(n("5")), ms(n("1")), k)))
        // Constant body
        assertResult("30", calc(sum(ms(n("1")), ms(n("10")), n("3"))))
        // Long sums are added in floating point
        assertValue(500000500000.0, calc(sum(ms(n("1")), ms(n("1000000")), k)), 1e-3)
        assertEquals(listOf(MathIssue.INVALID_BOUNDS), calc(sum(ms(n("1.5")), ms(n("3")), k)).issues)
    }

    @Test
    fun infiniteSeries() {
        // Σ 1/k² = π²/6
        assertResult("((π)^(2))/(6)", calc(sum(ms(n("1")), ms(inf), frac(ms(n("1")), ms(k, pow(n("2")))))))
        // Σ 1/2^k = 1
        assertResult("1", calc(sum(ms(n("1")), ms(inf), frac(ms(n("1")), ms(n("2"), pow(k))))))
        // Σ 1/k! = e
        assertResult("e", calc(sum(ms(n("0")), ms(inf), frac(ms(n("1")), ms(k, op(MathOp.FACTORIAL))))))
        // Σ (−1)^(k+1)/k = ln 2
        assertResult("ln(2)", calc(sum(ms(n("1")), ms(inf), frac(ms(paren(minus, n("1")), pow(k, plus, n("1"))), ms(k)))))
        // The harmonic series diverges
        assertResult("∞", calc(sum(ms(n("1")), ms(inf), frac(ms(n("1")), ms(k)))))
        // Σ (−1)^k has no value
        assertEquals(listOf(MathIssue.DIVERGES), calc(sum(ms(n("1")), ms(inf), paren(minus, n("1")), pow(k))).issues)
        // ∏ (1 − 1/(4k²)) = 2/π (Wallis)
        val wallis = calc(prod(ms(n("1")), ms(inf), n("1"), minus, frac(ms(n("1")), ms(n("4"), k, pow(n("2"))))))
        assertValue(2.0 / Math.PI, wallis, 1e-8)
    }

    @Test
    fun limits() {
        // Direct substitution
        assertResult("4", calc(lim(ms(n("2")), x, pow(n("2")))))
        // l'Hôpital
        assertResult("1", calc(lim(ms(n("0")), frac(ms(f("sin", x)), ms(x)))))
        assertResult("(1)/(2)", calc(lim(ms(n("0")), frac(ms(n("1"), minus, f("cos", x)), ms(x, pow(n("2")))))))
        assertResult("2", calc(lim(ms(n("1")), frac(ms(x, pow(n("2")), minus, n("1")), ms(x, minus, n("1"))))))
        // At infinity
        assertResult("e", calc(lim(ms(inf), paren(n("1"), plus, frac(ms(n("1")), ms(x))), pow(x))))
        assertResult("2", calc(lim(ms(inf), frac(ms(n("2"), x, plus, n("1")), ms(x, plus, n("3"))))))
        assertResult("0", calc(lim(ms(inf), frac(ms(f("sin", x)), ms(x)))))
        assertResult("∞", calc(lim(ms(inf), e, pow(x))))
        assertResult("0", calc(lim(ms(minus, inf), e, pow(x))))
        // Both sides tend to +∞
        assertResult("∞", calc(lim(ms(n("0")), frac(ms(n("1")), ms(x, pow(n("2")))))))
        // Different from the left and from the right
        assertEquals(listOf(MathIssue.UNDEFINED), calc(lim(ms(n("0")), frac(ms(n("1")), ms(x)))).issues)
        assertEquals(listOf(MathIssue.UNDEFINED), calc(lim(ms(n("0")), f("sin", frac(ms(n("1")), ms(x))))).issues)
        // ln x → −∞ from the only side where it is defined
        assertResult(" − ∞", calc(lim(ms(n("0")), f("ln", x))))
    }

    @Test
    fun limitWithOtherVariables() {
        val h = VariableNode("h")
        val body = frac(ms(paren(x, plus, h), pow(n("2")), minus, x, pow(n("2"))), ms(h))
        val res = calc(LimitNode(ms(h, op(MathOp.CONVERT), n("0")), ms(body)))
        assertResult("2x", res)
    }

    @Test
    fun improperIntegrals() {
        val zero = ms(n("0"))
        assertResult("1", calc(IntegralNode(zero, ms(inf), ms(e, pow(minus, x)))))
        assertResult("(π)/(2)", calc(IntegralNode(zero, ms(inf), ms(frac(ms(n("1")), ms(n("1"), plus, x, pow(n("2"))))))))
        assertResult("π", calc(IntegralNode(ms(minus, inf), ms(inf), ms(frac(ms(n("1")), ms(n("1"), plus, x, pow(n("2"))))))))
        assertEquals(listOf(MathIssue.UNDEFINED), calc(IntegralNode(ms(n("1")), ms(inf), ms(frac(ms(n("1")), ms(x))))).issues)
    }

    @Test
    fun infinityAlone() {
        assertResult("0", calc(frac(ms(n("1")), ms(inf))))
        assertResult("∞", calc(inf))
    }

    @Test
    fun factorialStaysSymbolicWithVariables() {
        assertResult("120", calc(n("5"), op(MathOp.FACTORIAL)))
        assertEquals(listOf(MathIssue.UNDEFINED), calc(n("2.5"), op(MathOp.FACTORIAL)).issues)
        assertResult("6", calc(paren(n("1"), plus, n("2")), op(MathOp.FACTORIAL)))
    }

    private fun mixed(whole: String, num: String, den: String) = MixedFractionNode(ms(n(whole)), ms(n(num)), ms(n(den)))

    @Test
    fun mixedNumbers() {
        assertResult("(7)/(2)", calc(mixed("3", "1", "2")))
        // A minus negates the whole mixed number
        assertResult(" − (7)/(2)", calc(minus, mixed("3", "1", "2")))
        assertResult("(5)/(4)", calc(mixed("1", "1", "2"), minus, frac(ms(n("1")), ms(n("4")))))
        // Unlike a number before a fraction, which is a product
        assertResult("(3)/(2)", calc(n("3"), frac(ms(n("1")), ms(n("2")))))
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(mixed("2.5", "1", "2")).issues)
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(MixedFractionNode(ms(n("2")), ms(x), ms(n("2")))).issues)
        assertEquals(listOf(MathIssue.DIVISION_BY_ZERO), calc(mixed("2", "1", "0")).issues)
    }

    @Test
    fun mixedResultFormReadsBackAsTheSameValue() {
        for (input in listOf(ms(frac(ms(n("7")), ms(n("2")))), ms(minus, frac(ms(n("7")), ms(n("2")))))) {
            val res = calc(*input.nodes.toTypedArray())
            val shown = ResultFormatter.format(res, ResultForm.MIXED)
            assertTrue(shown.nodes.any { it is MixedFractionNode })
            assertEquals(res.result.toString(), calc(*shown.nodes.toTypedArray()).result.toString())
        }
    }

    @Test
    fun exactSquareRootsOfComplexNumbers() {
        assertResult("(sqrt(6))/(2) + (isqrt(6))/(2)", calc(sqrt(n("3"), i)))
        assertResult("1 − i", calc(sqrt(minus, n("2"), i)))
        assertResult("2 + i", calc(sqrt(n("3"), plus, n("4"), i)))
        // z^(3/2) = (√z)³
        assertResult(" − 2 + 2i", calc(paren(n("2"), i), pow(frac(ms(n("3")), ms(n("2"))))))
    }

    @Test
    fun definitionWithComplexRootIsExactBothWays() {
        val a = VariableNode("a")
        val definition = calc(a, op(MathOp.DEFINE), minus, n("4"), plus, n("4"), op(MathOp.MULTIPLY), sqrt(n("3"), i))
        assertResult("a =  − 4 + 2sqrt(6) + 2isqrt(6)", definition)
        val reused = runBlocking {
            MathEngine.calculate(ms(a), MathContext(complexNumbers = true, variables = mapOf("a" to definition.answer!!)))
        }
        assertResult(" − 4 + 2sqrt(6) + 2isqrt(6)", reused)
    }

    @Test
    fun reusedDecimalResultsStayDecimals() {
        // A numeric integral has no exact value; reusing its digits must not make up a fraction
        val integral = calc(IntegralNode(ms(n("0")), ms(n("1")), ms(e, pow(x, pow(n("2"))))))
        assertTrue(integral.answer!!.nodes.any { it is NumberNode && it.approximate })
        val reused = runBlocking { MathEngine.calculate(integral.answer!!, MathContext()) }
        assertResult("1.462651746", reused)
        // Typed decimals are still exact
        assertResult("(1)/(4)", calc(n("0.25")))
    }
}
