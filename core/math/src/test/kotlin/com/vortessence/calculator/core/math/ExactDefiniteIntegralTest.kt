package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExactDefiniteIntegralTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private val pi = ConstantNode("π")
    private val e = ConstantNode("e")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg exponent: MathNode) = ExponentNode(null, ms(*exponent))
    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))
    private fun frac(num: MathString, den: MathString) = FractionNode(num, den)
    private fun int(a: MathString, b: MathString, vararg body: MathNode) = ms(IntegralNode(a, b, ms(*body)))
    private val zero = ms(n("0"))
    private val one = ms(n("1"))

    private fun calc(input: MathString, mode: CalculationMode = CalculationMode.EXACT): CalculationResult = runBlocking {
        MathEngine.calculate(input, MathContext(mode = mode))
    }

    private fun assertResult(expected: String, res: CalculationResult) {
        assertTrue("Expected success but got ${res.issues}", res.isSuccess)
        assertEquals(expected, res.result.toString())
    }

    @Test
    fun exactResultsFromTheAntiderivative() {
        assertResult("(1)/(3)", calc(int(zero, one, x, pow(n("2")))))
        assertResult("2", calc(int(zero, ms(pi), f("sin", x))))
        assertResult("(π)/(4)", calc(int(zero, one, frac(ms(n("1")), ms(n("1"), op(MathOp.PLUS), x, pow(n("2")))))))
        assertResult("ln(2)", calc(int(one, ms(n("2")), frac(ms(n("1")), ms(x)))))
        assertResult(" − ln(2)", calc(int(ms(op(MathOp.MINUS), n("2")), ms(op(MathOp.MINUS), n("1")), frac(ms(n("1")), ms(x)))))
        assertResult("e − 1", calc(int(zero, one, e, pow(x))))
        assertResult("1", calc(int(zero, one, x, e, pow(x))))
        assertResult("(π)/(2)", calc(int(zero, ms(pi), f("sin", x), pow(n("2")))))
        // Simpson's rule is least accurate here (√x is steep at 0), the exact value still wins
        assertResult("(16)/(3)", calc(int(zero, ms(n("4")), RadicalNode(null, ms(x)))))
    }

    @Test
    fun numericFallback() {
        // No antiderivative: numeric as before
        val gaussian = calc(int(zero, one, e, pow(x, pow(n("2")))))
        assertTrue(gaussian.isSuccess)
        assertEquals(1.4626517459071816, gaussian.numericValue, 1e-9)
        // Integrals across a pole diverge (F(b) − F(a) = −3/2 would be wrong for 1/x²)
        val minusOne = ms(op(MathOp.MINUS), n("1"))
        assertEquals(listOf(MathIssue.UNDEFINED), calc(int(minusOne, ms(n("2")), frac(ms(n("1")), ms(x, pow(n("2")))))).issues)
        assertEquals(listOf(MathIssue.UNDEFINED), calc(int(minusOne, ms(n("2")), frac(ms(n("1")), ms(x)))).issues)
        assertEquals(listOf(MathIssue.UNDEFINED), calc(int(zero, ms(n("3")), f("tan", x))).issues)
        // ...but an oscillating integrand is no pole
        assertEquals(1.0 / 50 - kotlin.math.cos(150.0) / 50, calc(int(zero, ms(n("3")), f("sin", n("50"), x))).numericValue, 1e-12)
        val sinc = calc(int(one, ms(n("10")), frac(ms(f("sin", x)), ms(x))))
        assertEquals(0.7122645238516915, sinc.numericValue, 1e-9)
        // Numeric mode stays numeric
        assertResult("0.3333333333", calc(int(zero, one, x, pow(n("2"))), CalculationMode.NUMERIC))
    }
}
