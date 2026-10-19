package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SimplificationTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg e: MathNode) = ExponentNode(null, ms(*e))
    private fun p(vararg inner: MathNode) = ParenthesesNode(ms(*inner))
    private val xPlus1 = p(x, op(MathOp.PLUS), n("1"))

    private fun calc(input: MathString): CalculationResult = runBlocking { MathEngine.calculate(input, MathContext()) }

    private fun assertResult(expected: String, res: CalculationResult) {
        assertTrue("Expected success but got ${res.issues}", res.isSuccess)
        assertEquals(expected, res.result.toString())
    }

    @Test
    fun identitiesWithPowersOfSums() {
        // Used to give a list of fake numeric solutions
        assertResult("true", calc(ms(xPlus1, pow(n("2")), op(MathOp.EQUALS), x, pow(n("2")), op(MathOp.PLUS), n("2"), x, op(MathOp.PLUS), n("1"))))
        assertResult("true", calc(ms(xPlus1, p(x, op(MathOp.MINUS), n("1")), op(MathOp.EQUALS), x, pow(n("2")), op(MathOp.MINUS), n("1"))))
    }

    @Test
    fun powersOfSumsAreSolvedExactly() {
        // (x+1)² = 4: x² + 2x − 3 = 0
        assertResult("x = 1, x =  − 3", calc(ms(xPlus1, pow(n("2")), op(MathOp.EQUALS), n("4"))))
        // Contradiction after multiplying out: (x+1)² = x² + 2x
        assertFalse(calc(ms(xPlus1, pow(n("2")), op(MathOp.EQUALS), x, pow(n("2")), op(MathOp.PLUS), n("2"), x)).isSuccess)
    }

    @Test
    fun sumsAreMultipliedOutOnlyWhenShorter() {
        assertResult("2x + 1", calc(ms(xPlus1, pow(n("2")), op(MathOp.MINUS), x, pow(n("2")))))
        assertResult("((x + 1))^(2) + 1", calc(ms(xPlus1, pow(n("2")), op(MathOp.PLUS), n("1"))))
        assertResult("((x + 1))^(8) + x", calc(ms(xPlus1, pow(n("8")), op(MathOp.PLUS), x)))
    }

    private val e = ConstantNode("e")
    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))
    private fun frac(num: MathString, den: MathString) = FractionNode(num, den)
    private val times = op(MathOp.MULTIPLY)

    // d/dx (x²·e^sin(x) + x/(3x − 1))
    private val derivative = DerivativeNode(ms(x, pow(n("2")), times, e, pow(f("sin", x)), op(MathOp.PLUS),
        frac(ms(x), ms(n("3"), x, op(MathOp.MINUS), n("1")))))
    // e^sin(x)·(2x + x²·cos(x)) − 1/(3x − 1)²
    private val simplified = arrayOf(e, pow(f("sin", x)), times, p(n("2"), x, op(MathOp.PLUS), x, pow(n("2")), f("cos", x)),
        op(MathOp.MINUS), frac(ms(n("1")), ms(p(n("3"), x, op(MathOp.MINUS), n("1")), pow(n("2")))))

    @Test
    fun symbolicResultsAreShownCompactly() {
        // Common factor and fractions over the same denominator combined
        assertResult("(e)^(sin(x))(2x + (x)^(2)cos(x)) − (1)/(((3x − 1))^(2))", calc(ms(derivative)))
        assertResult("(1)/(((x + 1))^(2))", calc(ms(DerivativeNode(ms(frac(ms(x), ms(x, op(MathOp.PLUS), n("1"))))))))
        // Numbers and powers of x are not factored out
        assertResult("(e)^(x)(1 + x)", calc(ms(DerivativeNode(ms(x, e, pow(x))))))
        // Nothing to gain: as before
        assertResult("2x + 1", calc(ms(xPlus1, pow(n("2")), op(MathOp.MINUS), x, pow(n("2")))))
    }

    @Test
    fun equalExpressionsAreAnIdentity() {
        assertResult("true", calc(ms(*simplified, op(MathOp.EQUALS), derivative)))
        assertResult("true", calc(ms(f("sin", x), pow(n("2")), op(MathOp.PLUS), f("cos", x), pow(n("2")), op(MathOp.EQUALS), n("1"))))
        // Different expressions: solved for where they are equal
        val different = calc(ms(*simplified, op(MathOp.EQUALS), DerivativeNode(ms(x, pow(n("3"))))))
        assertTrue(different.result.toString().startsWith("x = "))
        // A tiny coefficient is not zero
        assertResult("x = 0", calc(ms(n("1"), SciExponentNode(ms(op(MathOp.MINUS), n("20"))), x, op(MathOp.EQUALS), n("0"))))
    }

    @Test
    fun factorOrder() {
        assertResult("6x(((x)^(2) + 1))^(2)",
            calc(ms(DerivativeNode(ms(p(x, pow(n("2")), op(MathOp.PLUS), n("1")), pow(n("3")))))))
        assertResult("2xy", calc(ms(n("2"), VariableNode("y"), x)))
        assertResult("2πx", calc(ms(n("2"), x, ConstantNode("π"))))
    }
}
