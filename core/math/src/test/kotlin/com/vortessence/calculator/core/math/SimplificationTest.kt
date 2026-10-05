package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.type.*
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

    @Test
    fun factorOrder() {
        assertResult("6x(((x)^(2) + 1))^(2)",
            calc(ms(DerivativeNode(ms(p(x, pow(n("2")), op(MathOp.PLUS), n("1")), pow(n("3")))))))
        assertResult("2xy", calc(ms(n("2"), VariableNode("y"), x)))
        assertResult("2πx", calc(ms(n("2"), x, ConstantNode("π"))))
    }
}
