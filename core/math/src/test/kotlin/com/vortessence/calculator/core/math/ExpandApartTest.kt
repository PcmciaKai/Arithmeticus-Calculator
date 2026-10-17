package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.PartialFractions
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.ConstantNode
import com.vortessence.calculator.core.math.type.ExponentNode
import com.vortessence.calculator.core.math.type.MathOp
import com.vortessence.calculator.core.math.type.MathParser
import com.vortessence.calculator.core.math.type.NumberNode
import com.vortessence.calculator.core.math.type.OperatorNode
import com.vortessence.calculator.core.math.type.ParenthesesNode
import com.vortessence.calculator.core.math.type.FunctionNode
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.SeparatorNode
import com.vortessence.calculator.core.math.type.VariableNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The commands expand(e) and apart(e): the result, the issues, and the steps. */
class ExpandApartTest {

    private fun calc(input: MathString) = runBlocking { MathEngine.calculate(input, MathContext()) }

    private fun shown(text: String): String {
        val result = calc(TestInput.parse(text))
        assertTrue("$text: ${result.issues}", result.isSuccess)
        return result.result.toString()
    }

    private fun issues(text: String) = calc(TestInput.parse(text)).issues

    private fun rules(input: MathString): List<StepRule>? =
        runBlocking { MathEngine.steps(input, MathContext()) }?.steps?.mapNotNull { it.rule }

    /** apart(body, variable) */
    private fun apartIn(body: String, variable: String) =
        MathString(listOf(FunctionNode("apart", MathString(TestInput.parse(body).nodes + SeparatorNode + VariableNode(variable)))))

    @Test
    fun expandMultipliesOutProductsAndPowers() {
        assertEquals("(x)^(2) + 2x + 1", shown("expand((x+1)^2)"))
        assertEquals("(x)^(3) + 2(x)^(2) − x − 2", shown("expand((x+1)(x-1)(x+2))"))
        assertEquals("(x)^(3) + 3(x)^(2)y + 3x(y)^(2) + (y)^(3)", shown("expand((x+y)^3)"))
        assertEquals("2x + 6", shown("expand(2(x+3))"))
        // Numbers are their own expansion
        assertEquals("5", shown("expand(5)"))
        // Part of a calculation it is the same function
        assertEquals("2(x)^(2) + 4x + 2", shown("2*expand((x+1)^2)"))
    }

    @Test
    fun apartSplitsIntoPartialFractions() {
        assertEquals("(1)/(2(x − 1)) − (1)/(2(x + 1))", shown("apart(1/(x^2-1))"))
        // The variable can be given
        assertEquals(shown("apart(1/(x^2-1))"), calc(apartIn("1/(x^2-1)", "x")).result.toString())
    }

    @Test
    fun apartHandlesTheKindsOfFactors() {
        // Two linear factors, a repeated one, a polynomial part, a quadratic without real roots
        assertEquals("(4)/(3(x − 1)) − (1)/(3(x + 2))", shown("apart((x+3)/((x-1)(x+2)))"))
        // A/(x − 1) + B/(x − 1)² + C/(x + 1)
        assertEquals(" − (1)/(4(x − 1)) + (1)/(2((x − 1))^(2)) + (1)/(4(x + 1))", shown("apart(1/((x-1)^2(x+1)))"))
        assertEquals("x + (1)/(2(x − 1)) + (1)/(2(x + 1))", shown("apart(x^3/(x^2-1))"))
        assertEquals("x + 1 + (2)/(x − 1)", shown("apart((x^2+1)/(x-1))"))
        assertEquals("(1)/(x) − (x)/((x)^(2) + 1)", shown("apart(1/(x(x^2+1)))"))
        // A sum of fractions, a denominator that cannot be split further, a polynomial
        assertEquals("(1)/(x − 1) + (1)/(x + 1)", shown("apart(1/(x-1)+1/(x+1))"))
        assertEquals("(1)/((x)^(2) + 1)", shown("apart(1/(x^2+1))"))
        assertEquals("(x)^(2) + 1", shown("apart(x^2+1)"))
    }

    @Test
    fun apartRefusesWhatItCannotSplit() {
        // x³ + x + 1 has no rational root and is not a quadratic; a second letter would be a coefficient
        assertEquals(listOf(MathIssue.CANT_DECOMPOSE), issues("apart(1/(x^3+x+1))"))
        assertEquals(listOf(MathIssue.CANT_DECOMPOSE), issues("apart(1/(x*y))"))
        assertEquals(listOf(MathIssue.CANT_DECOMPOSE), issues("apart(sin(x))"))
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), issues("apart()"))
    }

    @Test
    fun theStepsNameTheirRules() {
        assertEquals(listOf(StepRule.PARTIAL_FRACTIONS), rules(TestInput.parse("apart(1/(x^2-1))")))
        assertEquals(listOf(StepRule.PARTIAL_FRACTIONS), rules(TestInput.parse("apart(1/(x(x^2+1)))")))
        assertEquals(listOf(StepRule.EXPAND_POWER), rules(TestInput.parse("expand((x+1)^2)")))
        assertTrue(StepRule.EXPAND in rules(TestInput.parse("expand((x+1)(x-1)(x+2))")).orEmpty())
        // Nothing to show where the argument is already in that form
        assertNull(rules(TestInput.parse("apart(1/(x^2+1))")))
        assertNull(rules(TestInput.parse("expand(5)")))
        assertNull(rules(TestInput.parse("apart(1/(x^3+x+1))")))
    }

    @Test
    fun theSumIsTheSameFunction() {
        val x = Variable("x")
        val fractions = listOf(
            "1/(2x^2-3x+1)", "1/(2*x*(x^2+1))", "(3x+1)/(2*x*(x^2+x+1))", "x^4/((x-1)(x+2)(x-3))", "(x^3+2)/(x^2(x-1))",
            "1/(x^2-2)", "(x+1)/(x^2-2)", "-1/(3x^2-x)", "[1;2]/(x^2-1/4)", "(x^5+1)/(x^2+4)", "1/((x+1)^3)",
            "x/((x-1)^2*(x^2+1))", "1/(x-1)-1/(x-2)+3/(x^2+1)", "1/(x^3-1)", "(2x+3)/(x^3+4x)"
        )
        for (text in fractions) {
            val canonical = MathParser().parse(TestInput.parse(text)).canonicalize(MathContext())
            val sum = PartialFractions.apart(canonical, x, MathContext())
            assertNotNull(text, sum)
            for (at in listOf(0.37, 1.13, 2.71, -0.83, 5.2, -3.3)) {
                val expected = canonical.evaluateNumerically(mapOf(x to at))
                assertEquals(text, expected, sum!!.evaluateNumerically(mapOf(x to at)), 1e-9 * maxOf(1.0, Math.abs(expected)))
            }
        }
    }

    @Test
    fun rationalRootsAndFractionalNumeratorsAreWrittenWithWholeNumbers() {
        assertEquals("(1)/(x − 1) − (2)/(2x − 1)", shown("apart(1/(2x^2-3x+1))"))
        assertEquals("(1)/(2x − 1) − (1)/(2x + 1)", shown("apart([1;2]/(x^2-1/4))"))
        assertEquals("(1)/(2x) + ( − x + 2)/(2((x)^(2) + x + 1))", shown("apart((3x+1)/(2*x*(x^2+x+1)))"))
    }

    @Test
    fun theCommandsAreTheSameFunctionWhereverTheyAreUsed() {
        val x = Variable("x")
        // Graphs and tables evaluate the typed expression
        val typed = MathParser().parse(TestInput.parse("apart(1/(x^2-1))"))
        assertEquals(0.125, typed.evaluateNumerically(mapOf(x to 3.0)), 1e-12)
        assertEquals(9.0, MathParser().parse(TestInput.parse("expand((x+1)^2)")).evaluateNumerically(mapOf(x to 2.0)), 1e-12)
        assertEquals("2x + 2", shown("D{expand((x+1)^2)}"))
        assertEquals("x = 1, x =  − 3", shown("expand((x+1)^2)=4"))
        assertEquals("(7)/(3)", shown("I[0,1]{expand((x+1)^2)}"))
        assertEquals("(1)/(x + 1)", shown("apart(1/(x^2-1))*(x-1)"))
        // A definition stores the expanded form
        val define = MathString(listOf(VariableNode("a"), OperatorNode(MathOp.DEFINE)) + TestInput.parse("expand((x+1)^2)").nodes)
        assertEquals("a = (x)^(2) + 2x + 1", calc(define).result.toString())
        // With complex numbers on, i is a number
        val complex = MathString(listOf(FunctionNode("expand", MathString(listOf(
            ParenthesesNode(MathString(listOf(VariableNode("x"), OperatorNode(MathOp.PLUS), ConstantNode("i")))),
            ExponentNode(null, MathString(listOf(NumberNode("2")))))))))
        val result = runBlocking { MathEngine.calculate(complex, MathContext(complexNumbers = true)) }
        assertTrue(result.issues.toString(), result.isSuccess)
        assertEquals("(x)^(2) − 1 + 2ix", result.result.toString())
    }
}
