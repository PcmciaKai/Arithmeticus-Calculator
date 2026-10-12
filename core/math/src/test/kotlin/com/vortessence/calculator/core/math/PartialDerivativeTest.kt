package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PartialDerivativeTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private val y = VariableNode("y")
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg exponent: MathNode) = ExponentNode(null, ms(*exponent))
    private fun derivative(vararg body: MathNode, variable: String = "x", at: MathString? = null) =
        DerivativeNode(ms(*body), at, variable)

    private fun calc(input: MathString, variables: Map<String, MathString> = emptyMap()): CalculationResult = runBlocking {
        MathEngine.calculate(input, MathContext(variables = variables))
    }

    private fun assertResult(expected: String, res: CalculationResult) {
        assertTrue("Expected success but got ${res.issues}", res.isSuccess)
        assertEquals(expected, res.result.toString())
    }

    @Test
    fun anotherVariableMakesItPartial() {
        assertTrue(MathEngine.isPartialDerivative(derivative(x, pow(n("2")), y)))
        assertTrue(MathEngine.isPartialDerivative(derivative(x, pow(n("2")), y, variable = "y")))
        // At a point it is still partial
        assertTrue(MathEngine.isPartialDerivative(derivative(x, y, at = ms(n("1")))))
    }

    @Test
    fun oneVariableIsOrdinary() {
        assertFalse(MathEngine.isPartialDerivative(derivative(x, pow(n("3")))))
        assertFalse(MathEngine.isPartialDerivative(derivative(y, pow(n("3")), variable = "y")))
        // A body without the variable is a constant, but not a partial derivative of anything else
        assertFalse(MathEngine.isPartialDerivative(derivative(n("5"))))
        // Constants are not variables
        assertFalse(MathEngine.isPartialDerivative(derivative(ConstantNode("e"), pow(x))))
    }

    @Test
    fun incompleteBodyIsNotPartial() {
        assertFalse(MathEngine.isPartialDerivative(derivative()))
        assertFalse(MathEngine.isPartialDerivative(derivative(x, OperatorNode(MathOp.PLUS))))
    }

    @Test
    fun definedVariablesAreSubstituted() {
        // a := 5: a is a number by the time it is differentiated
        val definitions = mapOf("a" to ms(n("5")))
        assertFalse(MathEngine.isPartialDerivative(derivative(VariableNode("a"), x), definitions))
        // a := y: still depends on y
        assertTrue(MathEngine.isPartialDerivative(derivative(VariableNode("a"), x), mapOf("a" to ms(y))))
        // A defined x is bound by d/dx, it does not replace the variable
        assertFalse(MathEngine.isPartialDerivative(derivative(x, pow(n("2"))), mapOf("x" to ms(n("3")))))
        // A definition of another variable does not matter if it is not used
        assertFalse(MathEngine.isPartialDerivative(derivative(x, pow(n("2"))), mapOf("a" to ms(y))))
    }

    @Test
    fun boundVariablesInsideAreNotFree() {
        // d/dx of the integral of t from 0 to x: t is bound by the integral
        val integral = IntegralNode(ms(n("0")), ms(x), ms(VariableNode("t")), "t")
        assertFalse(MathEngine.isPartialDerivative(derivative(integral)))
    }

    @Test
    fun differentiatingByAnotherVariable() {
        // ∂/∂y (x²y) = x²
        assertResult("(x)^(2)", calc(ms(derivative(x, pow(n("2")), y, variable = "y"))))
        // ∂/∂x (x²y) = 2xy
        assertResult("2xy", calc(ms(derivative(x, pow(n("2")), y))))
        // At a point: ∂/∂x (x²y) at x = 3 is 6y
        assertResult("6y", calc(ms(derivative(x, pow(n("2")), y, at = ms(n("3"))))))
    }

    @Test
    fun integratingByAnotherVariable() {
        // ∫ x·y dy = x·y²/2
        assertResult("(x(y)^(2))/(2) + C", calc(ms(AntiderivativeNode(ms(x, y), "y"))))
    }

    @Test
    fun variableNames() {
        assertTrue(isValidVariableName("x"))
        assertTrue(isValidVariableName("y"))
        assertTrue(isValidVariableName("a"))
        assertFalse(isValidVariableName(""))
        assertFalse(isValidVariableName("xy"))
        assertFalse(isValidVariableName("X"))
        assertFalse(isValidVariableName("1"))
        assertFalse(isValidVariableName("e"))
        assertFalse(isValidVariableName("i"))
    }

    @Test
    fun stepsUseTheChosenVariable() {
        fun steps(input: MathString) = runBlocking { MathEngine.steps(input, MathContext()) }!!

        val derivative = steps(ms(derivative(x, pow(n("2")), y, variable = "y")))
        assertEquals("(x)^(2)", derivative.result.toString())
        assertEquals("{d/dy((x)^(2)y)}", derivative.steps.first().expression.toString())

        // The other variable is a constant factor
        val other = steps(ms(derivative(x, pow(n("2")), y)))
        assertEquals("2xy", other.result.toString())
        assertEquals("[y{d/dx((x)^(2))}]", other.steps[1].expression.toString())

        val integral = steps(ms(AntiderivativeNode(ms(x, y), "y")))
        assertEquals("(x(y)^(2))/(2) + C", integral.result.toString())
        assertEquals("{int (xy) dy}", integral.steps.first().expression.toString())
    }
}
