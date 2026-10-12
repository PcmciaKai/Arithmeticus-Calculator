package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.steps.Transform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Bugs found in the review of the step-by-step solutions. */
class ReviewFixesTest {
    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").replace("( −", "(−").trim()
    private fun calc(text: String, context: MathContext = MathContext()) = runBlocking { MathEngine.calculate(TestInput.parse(text), context) }
    private fun steps(text: String) = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    @Test
    fun lHopitalTwiceAndThreeTimes() {
        // After the first l'Hôpital the quotient is simplified into a sum over one denominator
        val twice = steps("L[x->0]{[e^x - 1 - x;x^2]}")!!
        assertEquals(2, twice.steps.count { it.rule == StepRule.LHOPITAL })
        assertEquals("(1)/(2)", twice.result.toString())
        assertEquals(3, steps("L[x->0]{[x - sin(x);x^3]}")!!.steps.count { it.rule == StepRule.LHOPITAL })
        assertEquals("1", steps("L[x->inf]{x(e^{[1;x]} - 1)}")!!.result.toString())
    }

    @Test
    fun anEvenRootOfANegativeNumberTimesALetter() {
        // √(−4y) = 2√(−y), not the root of −4
        assertEquals("2sqrt(−y)", clean(calc("sqrt(-4y)").result.toString()))
        assertEquals("x = sqrt(−y), x = −sqrt(−y)", clean(calc("x^2 + y = 0").result.toString()))
    }

    @Test
    fun equationsInDegreesAreSearchedOverSeveralPeriods() {
        val degrees = MathContext(angleUnit = AngleUnit.DEGREES)
        val result = clean(calc("cos(2x) = cos(x)", degrees).result.toString())
        // 2x = ±x + 360k: x = 360k and x = 120k, which is x = 120k
        assertEquals("x = 120k, k ∈ ℤ", result)
        // A root where the curve only touches 0 is found exactly: sin x = 1 at 90° (with 210° and 330° it is 90° + 120°k)
        assertEquals("x = 90 + 120k, k ∈ ℤ", clean(calc("2sin(x)^2 - sin(x) - 1 = 0", degrees).result.toString()))
    }

    @Test
    fun degreesStayDegreesInResultsAndSteps() {
        val degrees = MathContext(angleUnit = AngleUnit.DEGREES)
        // sin(πx/180) is written sin(x), also in what is reused as the next input
        val sine = calc("sin(x) + cos(2x)", degrees)
        assertEquals("sin(x) + cos(2x)", clean(sine.result.toString()))
        assertEquals("sin(x) + cos(2x)", clean(sine.answer.toString()))
        assertEquals("(πcos(x))/(180)", clean(calc("D{sin(x)}", degrees).result.toString()))
        // The steps of an equation with a double angle, in degrees
        val solution = runBlocking { MathEngine.steps(TestInput.parse("cos(2x) = cos(x)"), degrees) }!!
        assertEquals("x = 120k, k ∈ ℤ", clean(solution.result.toString()))
        assertTrue(solution.steps.any { it.rule == StepRule.DOUBLE_ANGLE })
        val mixed = runBlocking { MathEngine.steps(TestInput.parse("sin(2x) = cos(x)"), degrees) }!!
        assertEquals("x = 90 + 360k, x = 30 + 120k, k ∈ ℤ", clean(mixed.result.toString()))
    }

    @Test
    fun theSquaresOfSineAndCosineAddUpToOne() {
        assertEquals("1", clean(calc("sin(x)^2 + cos(x)^2").result.toString()))
        assertEquals("3", clean(calc("3sin(x)^2 + 3cos(x)^2").result.toString()))
        assertEquals("x", clean(calc("x sin(x)^2 + x cos(x)^2").result.toString()))
        assertEquals("5", clean(calc("sin(x)^2 + cos(x)^2 + 4").result.toString()))
        // Other angles, and a difference, stay as they are
        assertEquals("(sin(x))^(2) + (cos(2x))^(2)", clean(calc("sin(x)^2 + cos(2x)^2").result.toString()))
        assertEquals("(sin(x))^(2) −(cos(x))^(2)", clean(calc("sin(x)^2 - cos(x)^2").result.toString()).replace("− (", "−("))
        val solution = steps("sin(x)^2 + cos(x)^2 + 4")!!
        assertEquals(StepRule.PYTHAGOREAN_IDENTITY, solution.steps[1].rule)
    }

    @Test
    fun moreTrigonometricIntegrals() {
        assertEquals("tan(x) −x + C", clean(calc("I{tan(x)^2}").result.toString()))
        assertEquals("−cos(x) + ((cos(x))^(3))/(3) + C", clean(calc("I{sin(x)^3}").result.toString()))
        assertEquals("sin(x) −((sin(x))^(3))/(3) + C", clean(calc("I{cos(x)^3}").result.toString()))
        assertEquals("(xsqrt(1 −(x)^(2)))/(2) + (asin(x))/(2) + C", clean(calc("I{sqrt(1 - x^2)}").result.toString()))
        assertEquals("(π)/(4)", clean(calc("I[0,1]{sqrt(1 - x^2)}").result.toString()))
        // The steps: one factor kept, the rest with the other function, then a substitution
        val odd = steps("I{sin(x)^3}")!!
        assertEquals(StepRule.TRIG_ODD_POWER, odd.steps[1].rule)
        assertTrue(odd.steps.any { it.rule == StepRule.SUBSTITUTION })
        assertEquals("−cos(x) + ((cos(x))^(3))/(3) + C", clean(odd.result.toString()))
    }

    @Test
    fun limitsFromOneSide() {
        assertEquals("∞", clean(calc("L[x->0^+]{[1;x]}").result.toString()))
        assertEquals("−∞", clean(calc("L[x->0^-]{[1;x]}").result.toString()))
        // From both sides it does not exist
        assertFalse(calc("L[x->0]{[1;x]}").isSuccess)
        assertEquals("∞", clean(calc("L[x->0]{[1;x^2]}").result.toString()))
        assertEquals("−∞", clean(calc("L[x->0^+]{ln(x)}").result.toString()))
        assertEquals("1", clean(calc("L[x->0^+]{[abs(x);x]}").result.toString()))
        assertEquals("−1", clean(calc("L[x->0^-]{[abs(x);x]}").result.toString()))
        // The steps
        val pole = steps("L[x->2^+]{[1;x - 2]}")!!
        assertEquals(StepRule.LIMIT_POLE, pole.steps[1].rule)
        assertEquals("∞", clean(pole.result.toString()))
        assertEquals("−∞", clean(steps("L[x->1^-]{[1;x - 1]}")!!.result.toString()))
        assertEquals(StepRule.LIMIT_LOGARITHM_ZERO, steps("L[x->0^+]{ln(x)}")!!.steps[1].rule)
        // The first line of the steps keeps the sign: x → 0⁺
        assertTrue(pole.steps[0].expression.toString(), pole.steps[0].expression.toString().contains("^( + )"))
    }

    @Test
    fun theFormOneToTheInfinity() {
        // (1 + 1/x)ˣ as x → ∞: e^(lim x·ln(1 + 1/x)) = e^1
        val steps = steps("L[x->inf]{(1 + [1;x])^x}")!!
        assertEquals(StepRule.LIMIT_EXPONENTIAL_FORM, steps.steps[1].rule)
        assertEquals(StepRule.LIMIT_EXPONENTIAL_RESULT, steps.steps.last().rule)
        assertEquals("e", clean(steps.result.toString()))
        assertEquals("e", clean(steps("L[x->0]{(1 + x)^{[1;x]}}")!!.result.toString()))
    }

    @Test
    fun improperIntegralsAreALimitOfTheAntiderivative() {
        // ∫₀^∞ e^(−x) dx = lim (−e^(−t) + 1) = 1
        val exponential = steps("I[0,inf]{e^{-x}}")!!
        assertEquals(StepRule.IMPROPER_INTEGRAL, exponential.steps.first { it.rule == StepRule.IMPROPER_INTEGRAL }.rule)
        assertEquals("1", clean(exponential.result.toString()))
        assertEquals("(1)/(2)", clean(steps("I[1,inf]{[1;x^3]}")!!.result.toString()))
        assertEquals("1", clean(steps("I[-inf,0]{e^x}")!!.result.toString()))
        assertEquals("(π)/(2)", clean(steps("I[0,inf]{[1;x^2 + 1]}")!!.result.toString()))
        assertEquals("1", clean(steps("I[0,inf]{x e^{-x}}")!!.result.toString()))
        // 1/x does not converge: no result, no steps
        assertNull(steps("I[1,inf]{[1;x]}"))
    }

    private fun transform(text: String, kind: Transform) =
        runBlocking { MathEngine.steps(TestInput.parse(text), MathContext(), transform = kind) }

    @Test
    fun expressionsWrittenInAnotherForm() {
        // Multiplied out: the calculator leaves (x + 1)² as it is
        val expanded = transform("(x + 1)^2", Transform.EXPAND)!!
        assertEquals("(x)^(2) + 2x + 1", clean(expanded.result.toString()))
        assertEquals(StepRule.EXPAND_POWER, expanded.steps[1].rule)
        // …but not what it gives anyway
        assertNull(transform("(x + 2)(x - 3)", Transform.EXPAND))
        assertNull(transform("x^2 + 1", Transform.EXPAND))
        // Factored, with the zeros as notes
        val factored = transform("x^2 - 5x + 6", Transform.FACTOR)!!
        assertEquals("(x −2)(x −3)", clean(factored.result.toString()).replace("−2", "−2"))
        assertEquals(StepRule.FACTOR_POLYNOMIAL, factored.steps[1].rule)
        assertEquals(2, factored.steps[1].notes.size)
        assertEquals("(x + 2)(2x −1)", clean(transform("2x^2 + 3x - 2", Transform.FACTOR)!!.result.toString()))
        assertEquals("2x(x + 2)", clean(transform("2x^2 + 4x", Transform.FACTOR)!!.result.toString()))
        assertNull(transform("x^2 + x + 1", Transform.FACTOR))
        // As one fraction
        val combined = transform("[1;x - 1] - [1;x + 1]", Transform.COMBINE)!!
        assertEquals("(2)/((x −1)(x + 1))", clean(combined.result.toString()))
        assertEquals(listOf(StepRule.EXTEND_FRACTIONS, StepRule.COMBINE_FRACTIONS), combined.steps.drop(1).take(2).map { it.rule })
        assertEquals("(2x + 1)/(x(x + 1))", clean(transform("[1;x] + [1;x + 1]", Transform.COMBINE)!!.result.toString()))
        // Which forms are offered
        assertEquals(listOf(Transform.EXPAND), runBlocking { MathEngine.transforms(TestInput.parse("(x + 1)^2")) })
        assertEquals(listOf(Transform.FACTOR), runBlocking { MathEngine.transforms(TestInput.parse("x^2 - 4")) })
        assertEquals(listOf(Transform.COMBINE), runBlocking { MathEngine.transforms(TestInput.parse("[1;x] + [1;x + 1]")) })
        assertEquals(emptyList<Transform>(), runBlocking { MathEngine.transforms(TestInput.parse("x^2 - 4 = 0")) })
        assertEquals(emptyList<Transform>(), runBlocking { MathEngine.transforms(TestInput.parse("2 + 3")) })
    }
}
