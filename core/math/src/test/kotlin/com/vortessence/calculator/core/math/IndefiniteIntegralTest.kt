package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.Antiderivative
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IndefiniteIntegralTest {
    private fun n(s: String) = NumberNode(s)
    private val x = VariableNode("x")
    private val e = ConstantNode("e")
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(vararg exponent: MathNode) = ExponentNode(null, ms(*exponent))
    private fun f(name: String, vararg arg: MathNode) = FunctionNode(name, ms(*arg))
    private fun frac(num: MathString, den: MathString) = FractionNode(num, den)
    private fun i(vararg integrand: MathNode) = ms(AntiderivativeNode(ms(*integrand)))

    private fun calc(input: MathString, angle: AngleUnit = AngleUnit.RADIANS): CalculationResult = runBlocking {
        MathEngine.calculate(input, MathContext(angleUnit = angle))
    }

    private fun assertResult(expected: String, res: CalculationResult) {
        assertTrue("Expected success but got ${res.issues}", res.isSuccess)
        assertEquals(expected, res.result.toString())
    }

    private fun assertNotFound(res: CalculationResult) = assertEquals(listOf(MathIssue.CANT_INTEGRATE), res.issues)

    @Test
    fun substitutionOfAPowerThatDoesNotAppearAsItIs() {
        // u = x², where x⁴ = (x²)²
        assertResult("(atan((x)^(2)))/(2) + C", calc(i(frac(ms(x), ms(n("1"), op(MathOp.PLUS), x, pow(n("4")))))))
        assertResult("(asin((x)^(2)))/(2) + C",
            calc(i(frac(ms(x), ms(RadicalNode(null, ms(n("1"), op(MathOp.MINUS), x, pow(n("4")))))))))
        // u = x⁴ rather than x²
        assertResult("(atan((x)^(4)))/(4) + C", calc(i(frac(ms(x, pow(n("3"))), ms(n("1"), op(MathOp.PLUS), x, pow(n("8")))))))
    }

    @Test
    fun polynomialsAndPowers() {
        assertResult("((x)^(3))/(3) + C", calc(i(x, pow(n("2")))))
        assertResult("(x)^(3) + (x)^(2) + x + C", calc(i(n("3"), x, pow(n("2")), op(MathOp.PLUS), n("2"), x, op(MathOp.PLUS), n("1"))))
        assertResult("5x + C", calc(i(n("5"))))
        assertResult("(2(x)^((3)/(2)))/(3) + C", calc(i(RadicalNode(null, ms(x)))))
        assertResult("(((2x + 1))^(4))/(8) + C", calc(i(ParenthesesNode(ms(n("2"), x, op(MathOp.PLUS), n("1"))), pow(n("3")))))
        // Multiplied out
        assertResult("((x)^(4))/(4) + (2(x)^(3))/(3) + ((x)^(2))/(2) + C",
            calc(i(x, ParenthesesNode(ms(x, op(MathOp.PLUS), n("1"))), pow(n("2")))))
        // Other variables are constants
        assertResult("((x)^(2)y)/(2) + C", calc(i(VariableNode("y"), x)))
    }

    @Test
    fun exponentialsAndLogarithms() {
        assertResult("ln(abs(x)) + C", calc(i(frac(ms(n("1")), ms(x)))))
        assertResult("(e)^(x) + C", calc(i(e, pow(x))))
        assertResult("((e)^(2x))/(2) + C", calc(i(e, pow(n("2"), x))))
        assertResult("((2)^(x))/(ln(2)) + C", calc(i(n("2"), pow(x))))
        assertResult("xln(x) − x + C", calc(i(f("ln", x))))
    }

    @Test
    fun trigonometricFunctions() {
        assertResult(" − cos(x) + C", calc(i(f("sin", x))))
        assertResult("(sin(3x))/(3) + C", calc(i(f("cos", n("3"), x))))
        assertResult(" − ln(abs(cos(x))) + C", calc(i(f("tan", x))))
        assertResult("(x)/(2) − (sin(x)cos(x))/(2) + C", calc(i(f("sin", x), pow(n("2")))))
        assertResult("atan(x) + C", calc(i(frac(ms(n("1")), ms(n("1"), op(MathOp.PLUS), x, pow(n("2")))))))
        assertResult("asin(x) + C", calc(i(frac(ms(n("1")), ms(RadicalNode(null, ms(n("1"), op(MathOp.MINUS), x, pow(n("2")))))))))
        // In degrees x is an angle in degrees
        assertResult(" − (180cos((πx)/(180)))/(π) + C", calc(i(f("sin", x)), AngleUnit.DEGREES))
        // atan/asin results are angles in radians, which would be misread in degree mode
        assertNotFound(calc(i(frac(ms(n("1")), ms(n("1"), op(MathOp.PLUS), x, pow(n("2"))))), AngleUnit.DEGREES))
    }

    @Test
    fun substitution() {
        assertResult("sin((x)^(2)) + C", calc(i(n("2"), x, f("cos", x, pow(n("2"))))))
        assertResult("((e)^((x)^(2)))/(2) + C", calc(i(x, e, pow(x, pow(n("2"))))))
        assertResult("((sin(x))^(2))/(2) + C", calc(i(f("sin", x), f("cos", x))))
        assertResult("((ln(x))^(2))/(2) + C", calc(i(frac(ms(f("ln", x)), ms(x)))))
        assertResult("(ln(abs((x)^(2) + 1)))/(2) + C", calc(i(frac(ms(x), ms(x, pow(n("2")), op(MathOp.PLUS), n("1"))))))
    }

    @Test
    fun integrationByParts() {
        assertResult("(e)^(x)(x − 1) + C", calc(i(x, e, pow(x))))
        assertResult("cos(x)(2 − (x)^(2)) + 2xsin(x) + C", calc(i(x, pow(n("2")), f("sin", x))))
        assertResult("((x)^(2)ln(x))/(2) − ((x)^(2))/(4) + C", calc(i(x, f("ln", x))))
        assertResult("xatan(x) − (ln(abs(1 + (x)^(2))))/(2) + C", calc(i(f("atan", x))))
    }

    @Test
    fun noClosedForm() {
        assertNotFound(calc(i(e, pow(x, pow(n("2"))))))
        assertNotFound(calc(i(frac(ms(f("sin", x)), ms(x)))))
        // A denominator that does not split into linear factors (completing the square is not done)
        assertNotFound(calc(i(frac(ms(n("1")), ms(x, pow(n("2")), op(MathOp.PLUS), x, op(MathOp.PLUS), n("1"))))))
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(i()).issues)
    }

    @Test
    fun partialFractions() {
        // ½ln|x − 1| − ½ln|x + 1|
        assertResult(" − (ln(abs(x + 1)))/(2) + (ln(abs(x − 1)))/(2) + C", calc(i(frac(ms(n("1")), ms(x, pow(n("2")), op(MathOp.MINUS), n("1"))))))
        // With a polynomial part: x²/2 + ½ln|x + 1| + ½ln|x − 1|
        assertResult("((x)^(2))/(2) + (ln(abs(x + 1)))/(2) + (ln(abs(x − 1)))/(2) + C", calc(i(frac(ms(x, pow(n("3"))), ms(x, pow(n("2")), op(MathOp.MINUS), n("1"))))))
    }

    @Test
    fun integrationConstant() {
        // Differentiating removes the constant again
        assertResult("xsin(x)", calc(ms(DerivativeNode(ms(AntiderivativeNode(ms(x, f("sin", x))))))))
        // The answer (for Ans) has no constant
        assertEquals("((x)^(3))/(3)", calc(i(x, pow(n("2")))).answer.toString().replace(" ", ""))
    }

    @Test
    fun numericEvaluationForPlotting() {
        val antiderivative = Antiderivative(MathParser().parse(ms(n("3"), x, pow(n("2")))), Variable("x"))
        assertEquals(8.0, antiderivative.evaluateNumerically(2.0, Variable("x")), 1e-12)
    }

    @Test
    fun absoluteValueRoundTrip() {
        // ln|x| is written as ln(abs(x)), and abs parses back: ln|−e| = 1
        val written = calc(i(frac(ms(n("1")), ms(x)))).answer!!
        assertEquals(FunctionNode("ln", ms(FunctionNode("abs", ms(x)))), written.nodes.single())
        assertEquals(1.0, calc(ms(f("ln", FunctionNode("abs", ms(op(MathOp.MINUS), e))))).numericValue, 1e-12)
    }
}
