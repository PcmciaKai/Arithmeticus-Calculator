package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.expressions.Derivative
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DerivativeTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg exponent: MathNode) = ExponentNode(null, ms(*exponent))
    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))
    private fun d(vararg body: MathNode, at: MathString? = null) = ms(DerivativeNode(ms(*body), at))

    private fun calc(
        input: MathString,
        angle: AngleUnit = AngleUnit.RADIANS,
        variables: Map<String, MathString> = emptyMap()
    ): CalculationResult = runBlocking {
        MathEngine.calculate(input, MathContext(angleUnit = angle, variables = variables))
    }

    private fun assertResult(expected: String, res: CalculationResult) {
        assertTrue("Expected success but got ${res.issues}", res.isSuccess)
        assertEquals(expected, res.result.toString())
    }

    @Test
    fun polynomials() {
        assertResult("3(x)^(2)", calc(d(x, pow(n("3")))))
        assertResult("6x + 2", calc(d(n("3"), x, pow(n("2")), op(MathOp.PLUS), n("2"), x, op(MathOp.PLUS), n("1"))))
        assertResult("2x + 2", calc(d(ParenthesesNode(ms(x, op(MathOp.PLUS), n("1"))), pow(n("2")))))
        assertResult("0", calc(d(n("5"))))
    }

    @Test
    fun productQuotientAndChainRule() {
        assertResult("sin(x) + xcos(x)", calc(d(x, f("sin", x))))
        assertResult("(cos(x))/(x) − (sin(x))/((x)^(2))", calc(d(FractionNode(ms(f("sin", x)), ms(x)))))
        assertResult(" − (1)/((x)^(2))", calc(d(FractionNode(ms(n("1")), ms(x)))))
        // The inner derivative comes first
        assertResult("2xcos((x)^(2))", calc(d(f("sin", x, pow(n("2"))))))
    }

    @Test
    fun rootsExponentialsAndLogarithms() {
        assertResult("(1)/(2sqrt(x))", calc(d(RadicalNode(null, ms(x)))))
        assertResult("2(e)^(2x)", calc(d(ConstantNode("e"), pow(n("2"), x))))
        assertResult("(1)/(x)", calc(d(f("ln", x))))
        assertResult("(1)/(xln(10))", calc(d(f("log", x))))
        assertResult("(x)^(x)(ln(x) + 1)", calc(d(x, pow(x))))
    }

    @Test
    fun trigonometricFunctions() {
        assertResult("(1)/((cos(x))^(2))", calc(d(f("tan", x))))
        assertResult("(1)/(1 + (x)^(2))", calc(d(f("atan", x))))
        assertResult("(1)/(sqrt(1 − (x)^(2)))", calc(d(f("asin", x))))
        // In degrees, x is an angle in degrees: d/dx sin(x°) = π/180·cos(x°)
        assertResult("(πcos(x))/(180)", calc(d(f("sin", x)), AngleUnit.DEGREES))
        assertEquals(0.0, calc(d(f("sin", x), at = ms(n("90"))), AngleUnit.DEGREES).numericValue, 1e-12)
    }

    @Test
    fun valueAtAPoint() {
        assertResult("6", calc(d(x, pow(n("2")), at = ms(n("3")))))
        assertResult("1", calc(d(f("sin", x), at = ms(n("0")))))
        // Not differentiable there: 1/(2√0)
        assertFalse(calc(d(RadicalNode(null, ms(x)), at = ms(n("0")))).isSuccess)
    }

    @Test
    fun nestedDerivativesIntegralsAndEquations() {
        assertResult("6x", calc(d(DerivativeNode(ms(x, pow(n("3")))))))
        // Fundamental theorem of calculus
        assertResult("(x)^(2)", calc(d(IntegralNode(ms(n("0")), ms(x), ms(x, pow(n("2")))))))
        // Where is the slope of x² equal to 4?
        assertResult("x = 2", calc(ms(DerivativeNode(ms(x, pow(n("2")))), op(MathOp.EQUALS), n("4"))))
    }

    @Test
    fun otherVariablesAreConstants() {
        assertResult("2xy", calc(d(VariableNode("y"), x, pow(n("2")))))
    }

    @Test
    fun definedVariables() {
        // a := x³ is substituted before differentiating
        assertResult("3(x)^(2)", calc(d(VariableNode("a")), variables = mapOf("a" to ms(x, pow(n("3"))))))
        // x is bound by d/dx: a defined x does not turn the derivative into a constant
        assertResult("2x", calc(d(x, pow(n("2"))), variables = mapOf("x" to ms(n("3")))))
        // ...but it is used for the point
        assertResult("6", calc(d(x, pow(n("2")), at = ms(VariableNode("a"))), variables = mapOf("a" to ms(n("3")))))
    }

    @Test
    fun errors() {
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(d()).issues)
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(d(x, at = ms())).issues)
        val matrix = MatrixNode(1, 1, listOf(ms(x)))
        assertEquals(listOf(MathIssue.MATRIX_UNSUPPORTED), calc(d(matrix)).issues)
    }

    @Test
    fun numericEvaluationForPlotting() {
        val derivative = Derivative(MathParser().parse(ms(x, pow(n("3")))), Variable("x"))
        assertEquals(12.0, derivative.evaluateNumerically(2.0, Variable("x")), 1e-12)
    }

    @Test
    fun writtenResultParsesBack() {
        val derivative = Derivative(MathParser().parse(ms(x, pow(n("2")))), Variable("x"), MathParser().parse(ms(n("3"))))
        val written = MathWriter(lossless = true).write(derivative)
        assertTrue(written.nodes.single() is DerivativeNode)
        assertResult("6", calc(written))
    }
}
