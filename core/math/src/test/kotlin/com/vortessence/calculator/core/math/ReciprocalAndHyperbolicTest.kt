package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.ConstantNode
import com.vortessence.calculator.core.math.type.FunctionNode
import com.vortessence.calculator.core.math.type.MathNode
import com.vortessence.calculator.core.math.type.MathString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** sec, csc, cot, the hyperbolic functions and all their inverses. */
class ReciprocalAndHyperbolicTest {

    private fun calc(
        text: String,
        mode: CalculationMode = CalculationMode.EXACT,
        angle: AngleUnit = AngleUnit.RADIANS,
        complex: Boolean = false
    ): CalculationResult = runBlocking {
        MathEngine.calculate(TestInput.parse(text), MathContext(mode = mode, angleUnit = angle, complexNumbers = complex))
    }

    /** Results are written with spaces around a minus sign; only whether there is one matters here. */
    private fun normalized(text: String) = text.trim().replace(Regex("\\s+"), " ")

    private fun assertResult(expected: String, text: String, angle: AngleUnit = AngleUnit.RADIANS) {
        val res = calc(text, angle = angle)
        assertTrue("Expected success for $text but got ${res.issues}", res.isSuccess)
        assertEquals(text, normalized(expected), normalized(res.result.toString()))
    }

    /** The steps of [text] (an equation, a derivative …), failing the test if there are none. */
    private fun solution(text: String, angle: AngleUnit = AngleUnit.RADIANS): Solution =
        runBlocking { MathEngine.steps(TestInput.parse(text), MathContext(angleUnit = angle)) } ?: throw AssertionError("No steps for $text")

    private fun assertSolved(expected: String, text: String, angle: AngleUnit = AngleUnit.RADIANS) =
        assertEquals(text, normalized(expected), normalized(solution(text, angle).result.toString()))

    private fun assertNumeric(expected: Double, text: String, angle: AngleUnit = AngleUnit.RADIANS) {
        val res = calc(text, CalculationMode.NUMERIC, angle)
        assertTrue("Expected success for $text but got ${res.issues}", res.isSuccess)
        assertEquals(text, expected, res.numericValue, 1e-9)
    }

    private fun assertIssue(issue: MathIssue, text: String) {
        val res = calc(text)
        assertTrue("Expected $issue for $text but got ${res.issues}", res.issues.contains(issue))
    }

    @Test
    fun exactReciprocalValuesMatchTheNumericOnesForAllMultiplesOfPiOver12() {
        for (k in 0 until 24) {
            val x = k * PI / 12
            val argument = "$k*pi/12"
            val sine = sin(x)
            val cosine = cos(x)
            if (abs(sine) > 1e-9) assertEquals("csc($k)", 1 / sine, calc("csc($argument)").numericValue, 1e-9)
            else assertIssue(MathIssue.DIVISION_BY_ZERO, "csc($argument)")
            if (abs(cosine) > 1e-9) assertEquals("sec($k)", 1 / cosine, calc("sec($argument)").numericValue, 1e-9)
            else assertIssue(MathIssue.DIVISION_BY_ZERO, "sec($argument)")
            if (abs(sine) > 1e-9) assertEquals("cot($k)", cosine / sine, calc("cot($argument)").numericValue, 1e-9)
            else assertIssue(MathIssue.DIVISION_BY_ZERO, "cot($argument)")
        }
    }

    @Test
    fun exactSpecialAngles() {
        assertResult("2", "sec(pi/3)")
        assertResult("2", "csc(pi/6)")
        assertResult("1", "cot(pi/4)")
        assertResult("0", "cot(pi/2)")
        assertResult("sqrt(6) + sqrt(2)", "csc(pi/12)")
        assertResult("2", "sec(60)", AngleUnit.DEGREES)
        assertResult("1", "cot(45)", AngleUnit.DEGREES)
        assertResult("sec(1)", "sec(1)")
    }

    @Test
    fun reciprocalPolesAndInverseDomains() {
        assertIssue(MathIssue.DIVISION_BY_ZERO, "sec(pi/2)")
        assertIssue(MathIssue.DIVISION_BY_ZERO, "csc(0)")
        assertIssue(MathIssue.DIVISION_BY_ZERO, "cot(pi)")
        assertIssue(MathIssue.TRIGONOMETRIC_DOMAIN, "asec(1/2)")
        assertIssue(MathIssue.TRIGONOMETRIC_DOMAIN, "acsc(0)")
        assertIssue(MathIssue.TRIGONOMETRIC_DOMAIN, "asin(2)")
    }

    @Test
    fun numericReciprocalsAndTheirInverses() {
        assertNumeric(1 / cos(1.0), "sec(1)")
        assertNumeric(1 / sin(1.0), "csc(1)")
        assertNumeric(1 / tan(1.0), "cot(1)")
        assertNumeric(Math.toDegrees(kotlin.math.acos(1 / 3.0)), "asec(3)", AngleUnit.DEGREES)
        assertNumeric(kotlin.math.asin(1 / 3.0), "acsc(3)")
        assertNumeric(kotlin.math.atan(0.5), "acot(2)")
    }

    @Test
    fun inverseReciprocalsAreExactAtSpecialValues() {
        assertResult("(π)/(3)", "asec(2)")
        assertResult("(π)/(6)", "acsc(2)")
        assertResult("(π)/(4)", "acot(1)")
        // acot has its principal values in (0, π)
        assertResult("(π)/(2)", "acot(0)")
        assertResult("(3π)/(4)", "acot(-1)")
        assertResult("60", "asec(2)", AngleUnit.DEGREES)
    }

    @Test
    fun reciprocalsOfAnArctangent() {
        assertResult("sqrt(1 + (x)^(2))", "sec(atan(x))")
        assertResult("(1)/(x)", "cot(atan(x))")
    }

    @Test
    fun hyperbolicValues() {
        assertResult("0", "sinh(0)")
        assertResult("1", "cosh(0)")
        assertResult("1", "sech(0)")
        assertResult("(3)/(4)", "sinh(ln(2))")
        assertResult("(5)/(4)", "cosh(ln(2))")
        assertResult("(3)/(5)", "tanh(ln(2))")
        assertResult("(4)/(3)", "csch(ln(2))")
        assertResult("sinh(1)", "sinh(1)")
        assertNumeric(kotlin.math.sinh(1.0), "sinh(1)")
        assertNumeric(1 / kotlin.math.cosh(1.0), "sech(1)")
        assertNumeric(1 / kotlin.math.tanh(1.0), "coth(1)")
        assertNumeric(kotlin.math.sinh(1.0), "sinh(1)", AngleUnit.DEGREES)
        assertIssue(MathIssue.DIVISION_BY_ZERO, "csch(0)")
        assertIssue(MathIssue.DIVISION_BY_ZERO, "coth(0)")
    }

    @Test
    fun inverseHyperbolicValuesAndDomains() {
        assertNumeric(kotlin.math.asinh(1.0), "asinh(1)")
        assertNumeric(kotlin.math.acosh(2.0), "acosh(2)")
        assertNumeric(kotlin.math.atanh(0.5), "atanh(0.5)")
        assertNumeric(kotlin.math.acosh(2.0), "asech(0.5)")
        assertNumeric(kotlin.math.asinh(0.5), "acsch(2)")
        assertNumeric(kotlin.math.atanh(0.5), "acoth(2)")
        assertResult("ln(2)", "asinh(3/4)")
        assertResult("ln(3)", "acosh(5/3)")
        assertResult("0", "acosh(1)")
        assertResult("0", "asech(1)")
        assertResult("asinh(1)", "arcsinh(1)")
        assertIssue(MathIssue.TRIGONOMETRIC_DOMAIN, "acosh(0)")
        assertIssue(MathIssue.TRIGONOMETRIC_DOMAIN, "atanh(1)")
        assertIssue(MathIssue.TRIGONOMETRIC_DOMAIN, "acoth(1/2)")
        assertIssue(MathIssue.TRIGONOMETRIC_DOMAIN, "asech(2)")
        assertIssue(MathIssue.TRIGONOMETRIC_DOMAIN, "acsch(0)")
        assertTrue(calc("sinh(1000)", CalculationMode.NUMERIC).issues.contains(MathIssue.VALUES_TOO_LARGE))
    }

    @Test
    fun hyperbolicFunctionsOfComplexNumbers() {
        fun complexCalc(name: String, vararg argument: MathNode) = runBlocking {
            MathEngine.calculate(MathString(listOf(FunctionNode(name, MathString(argument.toList())))), MathContext(complexNumbers = true))
        }
        val i = ConstantNode("i")
        // sinh(i) = i·sin(1)
        val sinhI = complexCalc("sinh", i)
        assertTrue(sinhI.issues.toString(), sinhI.isSuccess)
        assertEquals("0.8414709848i", normalized(sinhI.result.toString()).replace(" ", ""))
        // cosh(iπ) = cos(π)
        val coshIPi = complexCalc("cosh", i, ConstantNode("π"))
        assertTrue(coshIPi.issues.toString(), coshIPi.isSuccess)
        assertEquals("-1", normalized(coshIPi.result.toString()).replace(" ", "").replace("−", "-"))
        assertTrue(complexCalc("asinh", i).issues.contains(MathIssue.UNDEFINED))
    }

    @Test
    fun derivatives() {
        assertResult("sec(x)tan(x)", "D{sec(x)}")
        assertResult("− csc(x)cot(x)", "D{csc(x)}")
        assertResult("− (csc(x))^(2)", "D{cot(x)}")
        assertResult("2sec(2x)tan(2x)", "D{sec(2*x)}")
        assertResult("(1)/(abs(x)sqrt((x)^(2) − 1))", "D{asec(x)}")
        assertResult("− (1)/(1 + (x)^(2))", "D{acot(x)}")
        assertResult("cosh(x)", "D{sinh(x)}")
        assertResult("sinh(x)", "D{cosh(x)}")
        assertResult("(sech(x))^(2)", "D{tanh(x)}")
        assertResult("− sech(x)tanh(x)", "D{sech(x)}")
        assertResult("− csch(x)coth(x)", "D{csch(x)}")
        assertResult("− (csch(x))^(2)", "D{coth(x)}")
        assertResult("(1)/(sqrt((x)^(2) + 1))", "D{asinh(x)}")
        assertResult("(1)/(sqrt((x)^(2) − 1))", "D{acosh(x)}")
        assertResult("(1)/(1 − (x)^(2))", "D{atanh(x)}")
        assertResult("2xcosh((x)^(2))", "D{sinh(x^2)}")
        // In degrees the factor π/180 (or 180/π for an inverse) comes in, as for sin
        assertResult("(πsec(x)tan(x))/(180)", "D{sec(x)}", AngleUnit.DEGREES)
        assertResult("− (180)/(π(1 + (x)^(2)))", "D{acot(x)}", AngleUnit.DEGREES)
    }

    @Test
    fun derivativeStepsNameTheRules() {
        assertEquals(listOf(StepRule.SECANT), solution("D{sec(x)}").steps.mapNotNull { it.rule })
        val hyperbolic = solution("D{sinh(x^2)}")
        assertTrue(hyperbolic.steps.any { it.rule == StepRule.HYPERBOLIC_SINE })
        assertTrue(hyperbolic.steps.any { it.rule == StepRule.CHAIN_RULE })
        assertTrue(solution("D{acsch(x)}").steps.any { it.rule == StepRule.INVERSE_HYPERBOLIC_COSECANT })
    }

    @Test
    fun antiderivatives() {
        assertResult("tan(x) + C", "I{sec(x)^2}")
        assertResult("ln(abs(sec(x) + tan(x))) + C", "I{sec(x)}")
        assertResult("− ln(abs(csc(x) + cot(x))) + C", "I{csc(x)}")
        assertResult("ln(abs(sin(x))) + C", "I{cot(x)}")
        assertResult("− cot(x) + C", "I{csc(x)^2}")
        assertResult("− cot(x) − x + C", "I{cot(x)^2}")
        assertResult("sec(x) + C", "I{sec(x)*tan(x)}")
        assertResult("− csc(x) + C", "I{csc(x)*cot(x)}")
        assertResult("cosh(x) + C", "I{sinh(x)}")
        assertResult("(sinh(2x))/(2) + C", "I{cosh(2*x)}")
        assertResult("ln(cosh(x)) + C", "I{tanh(x)}")
        assertResult("atan(sinh(x)) + C", "I{sech(x)}")
        assertResult("tanh(x) + C", "I{sech(x)^2}")
        assertResult("− sech(x) + C", "I{sech(x)*tanh(x)}")
        assertResult("x − tanh(x) + C", "I{tanh(x)^2}")
        assertResult("(sinh(x)cosh(x))/(2) + (x)/(2) + C", "I{cosh(x)^2}")
        assertResult("xcosh(x) − sinh(x) + C", "I{x*sinh(x)}")
        assertResult("xasinh(x) − sqrt((x)^(2) + 1) + C", "I{asinh(x)}")
    }

    @Test
    fun equationsWithReciprocalFunctionsAreSolvedThroughSineCosineAndTangent() {
        assertSolved("x = (π)/(3) + 2πk, x = − (π)/(3) + 2πk, k ∈ ℤ", "sec(x)=2")
        assertSolved("x = (π)/(6) + 2πk, x = (5π)/(6) + 2πk, k ∈ ℤ", "csc(x)=2")
        assertSolved("x = (π)/(4) + πk, k ∈ ℤ", "cot(x)=1")
        assertSolved("x = (π)/(2) + πk, k ∈ ℤ", "cot(x)=0")
        assertSolved("x = 60 + 360k, x = − 60 + 360k, k ∈ ℤ", "sec(x)=2", AngleUnit.DEGREES)
        assertTrue(solution("sec(x)=2").steps.any { it.rule == StepRule.RECIPROCAL_EQUATION })
        assertSolved("no real solution", "sec(x)=1/2")
        assertSolved("no real solution", "csc(x)=0")
    }

    @Test
    fun hyperbolicEquationsUseTheInverseFunctions() {
        assertSolved("x = asinh(2)", "sinh(x)=2")
        assertSolved("x = (ln(2))/(2)", "sinh(2*x)=3/4")
        assertSolved("x = 0", "cosh(x)=1")
        assertSolved("x = − ln(2), x = ln(2)", "cosh(x)=5/4")
        assertTrue(solution("cosh(x)=5/4").steps.any { it.rule == StepRule.HYPERBOLIC_SOLUTION })
        assertSolved("no real solution", "cosh(x)=1/2")
        assertSolved("no real solution", "tanh(x)=2")
    }

    @Test
    fun limits() {
        assertResult("1", "L[x->0]{[sinh(x);x]}")
        assertResult("− (1)/(2)", "L[x->0]{[1-cosh(x);x^2]}")
        assertResult("1", "L[x->inf]{tanh(x)}")
        assertResult("1", "L[x->inf]{[sinh(x);cosh(x)]}")
        assertResult("0", "L[x->inf]{acot(x)}")
    }
}
