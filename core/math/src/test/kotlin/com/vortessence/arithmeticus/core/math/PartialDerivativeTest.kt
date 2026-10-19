package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.type.*
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
        for (name in listOf("x", "y", "a", "X", "A", "M", "θ", "λ")) assertTrue(name, isValidVariableName(name))
        for (name in listOf("", "xy", "1", " ", "+", "∞")) {
            assertEquals(name, VariableNameProblem.NOT_A_LETTER, variableNameProblem(name))
        }
        for (name in listOf("e", "i", "π")) assertEquals(name, VariableNameProblem.CONSTANT, variableNameProblem(name))
        // T in an exponent is the transpose
        assertEquals(VariableNameProblem.TRANSPOSE, variableNameProblem("T"))
        assertFalse(isValidVariableName("T"))
    }

    @Test
    fun otherVariablesAreOfferedWhenTheVariableIsNotInTheBody() {
        // d/dx(y²) is 0: y is probably meant
        assertEquals(listOf("y"), MathEngine.otherVariables(derivative(y, pow(n("2")))))
        assertEquals(listOf("y", "z"), MathEngine.otherVariables(derivative(y, VariableNode("z"))))
        assertEquals(listOf("y"), MathEngine.otherVariables(AntiderivativeNode(ms(y, pow(n("2"))), "x")))
        assertEquals(listOf("y"), MathEngine.otherVariables(IntegralNode(ms(n("0")), ms(n("1")), ms(y), "x")))
    }

    @Test
    fun noOtherVariablesWhenThereIsNothingToSuggest() {
        // The variable is in the body: a partial derivative, or just fine
        assertEquals(emptyList<String>(), MathEngine.otherVariables(derivative(x, pow(n("2")), y)))
        assertEquals(emptyList<String>(), MathEngine.otherVariables(derivative(x, pow(n("2")))))
        // A constant or an empty body
        assertEquals(emptyList<String>(), MathEngine.otherVariables(derivative(n("5"))))
        assertEquals(emptyList<String>(), MathEngine.otherVariables(derivative()))
        // Constants and the transpose are no candidates
        assertEquals(emptyList<String>(), MathEngine.otherVariables(derivative(ConstantNode("e"))))
        assertEquals(emptyList<String>(), MathEngine.otherVariables(derivative(VariableNode("T"))))
        // Not an integral or a derivative
        assertEquals(emptyList<String>(), MathEngine.otherVariables(y))
        // A defined variable is a number by then
        assertEquals(emptyList<String>(), MathEngine.otherVariables(derivative(VariableNode("a")), mapOf("a" to ms(n("5")))))
        assertEquals(listOf("y"), MathEngine.otherVariables(derivative(VariableNode("a")), mapOf("a" to ms(y))))
    }

    @Test
    fun savedLinesKeepCapitalAndGreekVariablesAndChosenVariables() {
        val line = ms(
            VariableNode("A"), VariableNode("θ"),
            DerivativeNode(ms(x, y), null, "y"), AntiderivativeNode(ms(x), "z"),
            IntegralNode(ms(n("0")), ms(n("1")), ms(x), "t")
        )
        assertEquals(line, MathStringCodec.decode(MathStringCodec.encode(line)))
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
