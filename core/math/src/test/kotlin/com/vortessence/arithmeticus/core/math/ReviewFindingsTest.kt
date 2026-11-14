package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.type.LineBreakNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** What a review of the whole app found wrong, each with its example. */
class ReviewFindingsTest {
    private fun calc(text: String, context: MathContext = MathContext()) = runBlocking { MathEngine.calculate(TestInput.parse(text), context) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    private fun result(text: String, context: MathContext = MathContext()): String {
        val r = calc(text, context)
        assertTrue("$text: ${r.issues}", r.isSuccess)
        val parts = mutableListOf(StringBuilder())
        for (node in r.result.nodes) if (node === LineBreakNode) parts.add(StringBuilder()) else parts.last().append(MathStringText.of(node))
        return parts.joinToString(" | ") { clean(it.toString()) }
    }

    private fun issue(text: String, context: MathContext = MathContext()): MathIssue? = calc(text, context).issues.firstOrNull()

    @Test
    fun rootsFarFromZeroOrVeryCloseToItAreFound() {
        // "No real solution" was said for each: the search only looked between −1000 and 1000
        assertEquals("x = 100000000000000000000", result("x^3=10^{60}"))
        assertEquals("x = −100000000000000000000", result("x^3=-10^{60}"))
        assertEquals("x = 100000000000000000000", result("sqrt(x)=10^{10}"))
        assertEquals("x = 100000000000000000000", result("1/x=10^{-20}"))
        // A root of 10⁻²⁰ was rounded to the whole number 0, where 1/x is not defined
        assertEquals("x = (1)/(100000000000000000000)", result("1/x=10^{20}"))
        assertEquals("x = −(1)/(100000), x = (1)/(100000)", result("1/x^2=10^{10}"))
        assertTrue(calc("ln(x)=-50").isSuccess)
        assertTrue(calc("ln(x)=100").isSuccess)
    }

    @Test
    fun aProductIsZeroWhereAFactorIs() {
        assertEquals("x = 1", result("(x-1)^{10}=0"))
        // −1000 was missing, and 10 and 11 (only the 10 roots nearest to 0 were listed)
        assertEquals("x = −1000, x = (1)/(1000), x = 1000", result("(x-1000)(x+1000)(x-0.001)=0"))
        assertEquals((0..11).joinToString(", ") { "x = $it" }, result("x(x-1)(x-2)(x-3)(x-4)(x-5)(x-6)(x-7)(x-8)(x-9)(x-10)(x-11)=0"))
        assertEquals("x = 3", result("(x^2+1)(x-3)=0"))
    }

    @Test
    fun anAbsoluteValueEqualToANumberIsSolvedExactly() {
        // x = 0 was said: the search cannot tell ±10⁻³⁰ from 0
        assertEquals("x = −(1)/(1000000000000000000000000000000), x = (1)/(1000000000000000000000000000000)", result("abs(x)=10^{-30}"))
        assertEquals("x = −1, x = 3", result("abs(x-1)=2"))
        assertEquals(MathIssue.NO_REAL_SOLUTION, issue("abs(x)=-1"))
    }

    @Test
    fun anEquationThatHoldsOnAStretchSaysWhere() {
        assertEquals("x ≥ 0", result("abs(x)=x"))
        assertEquals("x ≤ 0", result("abs(x)=-x"))
        assertEquals("−1 ≤ x ≤ 1", result("abs(x-1)+abs(x+1)=2"))
        assertEquals("x > 0", result("abs(x)/x=1"))
        // Not where both sides are merely too small to tell apart
        assertEquals(MathIssue.NO_REAL_SOLUTION, issue("e^x=0"))
        assertTrue(calc("e^x=10^{-100}").isSuccess)
    }

    @Test
    fun noSolutionIsOnlySaidWhereItIsKnown() {
        // Proven by the steps or by the kind of equation
        for (text in listOf("e^x=0", "sin(x)=2", "sqrt(x)=-2", "1/x=0", "x^4+x^3+x^2+x+1=0")) assertEquals(text, MathIssue.NO_REAL_SOLUTION, issue(text))
        // Only that the search found none
        for (text in listOf("e^x=x", "x^x=0.5")) assertEquals(text, MathIssue.NO_SOLUTION_FOUND, issue(text))
    }

    private fun assertSeries(expected: Double, text: String) {
        val r = calc(text)
        assertTrue("$text: ${r.issues}", r.isSuccess)
        assertEquals(text, expected, r.numericValue, 1e-10 * maxOf(1.0, kotlin.math.abs(expected)))
    }

    @Test
    fun seriesWhoseTermsChangeSignConverge() {
        // "Does not converge" was said for all of them
        assertSeries((Math.PI - 1) / 2, "#S[n=1,inf]{sin(n)/n}")
        assertSeries(-kotlin.math.ln(2 * kotlin.math.sin(0.5)), "#S[n=1,inf]{cos(n)/n}")
        assertSeries(Math.PI * Math.PI / 6 - Math.PI / 2 + 0.25, "#S[n=1,inf]{cos(n)/n^2}")
        assertSeries(1.0439821028490965, "#S[n=1,inf]{sin(n)/sqrt(n)}")
        assertSeries(-0.6048986434216305, "#S[n=1,inf]{(-1)^n/sqrt(n)}")
        assertSeries(-0.9242998972229388, "#S[n=1,inf]{(-1)^n/ln(n+1)}")
        // Terms that do not get smaller still do not converge
        assertEquals(MathIssue.DIVERGES, issue("#S[n=1,inf]{(-1)^n}"))
        assertEquals(MathIssue.DIVERGES, issue("#S[n=1,inf]{sin(n)}"))
    }

    @Test
    fun powerLawSeriesAreExactToTheLastDigit() {
        // Σ 1/n^1.01 was ∞, Σ 1/n^1.5 was 3e-9 off
        assertSeries(100.57794333849874, "#S[n=1,inf]{1/n^{1.01}}")
        assertSeries(2.6123753486854883, "#S[n=1,inf]{1/n^{1.5}}")
        assertSeries(1.4835228173095523, "#S[n=1,inf]{sin(1/n^2)}")
        assertEquals("(1)/(2)", result("#P[n=2,inf]{1-1/n^2}"))
        assertSeries(kotlin.math.sinh(Math.PI) / Math.PI, "#P[n=1,inf]{1+1/n^2}")
        // Divergent ones stay so
        for (text in listOf("#S[n=1,inf]{1/n}", "#S[n=1,inf]{1/sqrt(n)}", "#S[n=1,inf]{sin(1/n)}", "#P[n=1,inf]{1+1/n}")) assertEquals(text, "∞", result(text))
    }

    @Test
    fun aSeriesThatCannotBeDecidedIsNotCalledDivergent() {
        // Σ 1/(n·ln n + 1) diverges, too slowly for the numbers to tell, and without an antiderivative for the integral test (and
        // falling too slowly for its rest to be worked out by numbers): it is not called divergent, nor given a value
        val text = "#S[n=2,inf]{1/(n*ln(n)+1)}"
        assertEquals(text, MathIssue.SERIES_UNDECIDED, issue(text))
    }

    @Test
    fun aLogarithmOutsideItsDomainIsAnError() {
        // ln(0) and ln(−1) were shown as results, as if they had a value (log(0) was an error already)
        for (text in listOf("ln(0)", "ln(-1)", "ln(0)+1", "ln(sqrt(2)-2)", "ln(ln(0.5))", "log(0)")) {
            assertEquals(text, MathIssue.LOGARITHMIC_DOMAIN, issue(text))
        }
        assertEquals(MathIssue.NEGATIVE_ROOT, issue("sqrt(ln(0.5))"))
        assertEquals(MathIssue.NEGATIVE_ROOT, issue("sqrt(e-3)"))
        // With complex numbers ln(−1) has its value; ln(0) still has none
        assertEquals("πi", result("ln(-1)", MathContext(complexNumbers = true)))
        assertEquals(MathIssue.LOGARITHMIC_DOMAIN, issue("ln(0)", MathContext(complexNumbers = true)))
        assertEquals("∞", result("ln(inf)"))
    }

    @Test
    fun aPowerOfALogarithmIsItsNumber() {
        // e^ln(5) stayed as it was, and e^ln(5) − 5 was −8.9e−16
        assertEquals("5", result("e^{ln(5)}"))
        assertEquals("0", result("e^{ln(5)}-5"))
        assertEquals("5", result("10^{log(5)}"))
        assertEquals("9", result("e^{2ln(3)}"))
        assertEquals(MathIssue.DIVISION_BY_ZERO, issue("1/(e^{ln(2)}-2)"))
    }

    @Test
    fun infinityTimesZeroHasNoValue() {
        for (text in listOf("inf*0", "0*inf", "inf/inf", "inf-inf")) assertEquals(text, MathIssue.UNDEFINED, issue(text))
        assertEquals("0", result("1/inf"))
        assertEquals("∞", result("2*inf"))
    }

    @Test
    fun aTinyNumberIsNoZero() {
        // 10⁻⁶⁰⁰ is 0.0 as a double, and was taken for 0
        val tiny = calc("10^{-300}/10^{300}")
        assertTrue(tiny.isSuccess)
        assertFalse(tiny.expression.isZero)
        // and its decimal form was 0
        for (form in listOf(com.vortessence.arithmeticus.core.math.type.ResultForm.DECIMAL, com.vortessence.arithmeticus.core.math.type.ResultForm.SCIENTIFIC)) {
            assertEquals("1E-600", com.vortessence.arithmeticus.core.math.type.MathText.toPlainText(com.vortessence.arithmeticus.core.math.type.ResultFormatter.format(tiny, form)))
        }
        assertEquals("true", result("10^{-600}>0"))
        assertEquals("false", result("-10^{-600}>0"))
        assertTrue(result("x*10^{-600}=1").startsWith("x = 1000000"))
    }

    @Test
    fun aLimitOfZeroTimesInfinityIsWorkedOut() {
        // 0·ln(0) used to give 0 by substituting, now that ln(0) is an error l'Hôpital does it, also for ∞/∞
        for (text in listOf("L[x->0]{x*ln(x)}", "L[x->0]{sqrt(x)*ln(x)}", "L[x->0]{ln(x)/(1/x)}", "L[x->1]{(x-1)*ln(x-1)}", "L[x->0]{sin(x)*ln(x)}")) {
            assertEquals(text, "0", result(text))
            assertNotNull(text, runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) })
        }
        assertEquals(MathIssue.UNDEFINED, issue("L[x->0]{x*(1/x^2)}"))
    }

    @Test
    fun aLimitAtAJumpDoesNotExist() {
        // floor(0) = 0 was taken for the limit
        for (text in listOf("L[x->0]{floor(x)}", "L[x->1]{floor(x)}", "L[x->0]{sign(x)}", "L[x->0]{ceil(x)}", "L[x->0.5]{round(x)}", "L[x->0]{floor(x)+x^2}")) {
            assertEquals(text, MathIssue.UNDEFINED, issue(text))
        }
        // Away from a jump, or where the jump is cancelled, there is one
        assertEquals("2", result("L[x->2.5]{floor(x)}"))
        assertEquals("0", result("L[x->0]{x*sign(x)}"))
        assertEquals("1", result("L[x->0]{sign(x)^2}"))
    }

    @Test
    fun definiteIntegralsThatWereTakenForUndefined() {
        // Oscillating over a long range: the numeric accuracy was asked of the result (0.44), not of ∫|f|
        assertEquals(1 - kotlin.math.cos(1000.0), calc("I[0,1000]{sin(x)}").numericValue, 1e-12)
        assertEquals(kotlin.math.sin(1000.0), calc("I[0,1000]{cos(x)}").numericValue, 1e-12)
        assertTrue(calc("I[0,10]{sin(100x)}").isSuccess)
        // A logarithm at a bound: no antiderivative was found, and F(0) needs a limit
        assertEquals("2", result("I[0,1]{ln(x)^2}"))
        assertEquals("−(4)/(9)", result("I[0,1]{sqrt(x)*ln(x)}"))
        assertEquals("−4", result("I[0,1]{ln(x)/sqrt(x)}"))
        assertEquals("−6", result("I[0,1]{ln(x)^3}"))
        // Divergent ones stay so
        for (text in listOf("I[0,1]{1/x}", "I[0,1]{ln(x)/x}", "I[-1,1]{1/x^2}", "I[0,pi]{tan(x)}", "I[1,inf]{1/x}")) assertEquals(text, MathIssue.UNDEFINED, issue(text))
    }

    @Test
    fun powersOfALogarithmHaveAnAntiderivative() {
        for (text in listOf("I{ln(x)^2}", "I{ln(x)^3}", "I{sqrt(x)*ln(x)}", "I{ln(x)/sqrt(x)}", "I{x^2*ln(x)^2}", "I{ln(2x+1)^2}")) assertTrue(text, calc(text).isSuccess)
    }

    private fun physical(text: String) = result(text, MathContext(physicalTemperatures = true)).replace(" ", "")

    @Test
    fun temperaturesAsPointsOnTheirScale() {
        assertEquals("20°C", physical("20#u{°C}"))
        assertEquals("−5°C", physical("-5#u{°C}"))
        assertEquals("25°C", physical("20#u{°C}+5#u{K}"))
        assertEquals("15°C", physical("20#u{°C}-5#u{K}"))
        assertEquals("10K", physical("30#u{°C}-20#u{°C}"))
        assertEquals("10(J)/(K)", physical("100#u{J}/(10#u{°C})"))
        assertEquals("25°C", physical("mean(20#u{°C},30#u{°C})"))
        assertEquals("68°F", physical("20#u{°C}->#u{°F}"))
        // Equations and comparisons take both sides as absolute temperatures
        assertEquals("x=(537)/(20)", physical("x#u{°C}=300#u{K}"))
        assertEquals("true", physical("20#u{°C}>60#u{°F}"))
        // No point on the scale
        for (text in listOf("20#u{°C}+10#u{°C}", "20#u{°C}*2", "20#u{°C}/2", "(20#u{°C})^2")) {
            assertEquals(text, MathIssue.TEMPERATURE_ARITHMETIC, issue(text, MathContext(physicalTemperatures = true)))
        }
        // Kelvin is free in both modes
        assertEquals("600K", physical("300#u{K}*2"))
        // And the default stays: in kelvin
        assertEquals("293.15K", result("20#u{°C}").replace(" ", ""))
        assertEquals("586.3K", result("20#u{°C}*2").replace(" ", ""))
    }

    @Test
    fun unitsAreNotDroppedSilently() {
        // ln(5 m) was ln(5), e^(2 s) was e², 2^(3 m) was 8
        for (text in listOf("ln(5#u{m})", "log(100#u{m})", "e^{2#u{s}}", "2^{3#u{m}}")) assertEquals(text, MathIssue.INVALID_UNIT, issue(text))
        // Units that cancel are a number
        assertTrue(calc("ln(1#u{km}/1#u{m})").isSuccess)
        assertTrue(calc("2^{1#u{km}/1#u{m}}").isSuccess)
        // An integral from 0 s to 2 s of 3 m/s was 6
        assertEquals("6m", result("I[0#u{s},2#u{s}]{3#u{m}/#u{s}}").replace(" ", ""))
        assertEquals("0.12m³", result("I[0#u{min},1#u{h}]{2#u{L}/#u{min}}").replace(" ", ""))
        assertEquals(MathIssue.INVALID_UNIT, issue("I[1#u{s},2#u{m}]{x}"))
        // The decimal form of a complex quantity keeps its unit
        val complex = calc("5#u{m}*#i", MathContext(complexNumbers = true))
        assertEquals("5i m", com.vortessence.arithmeticus.core.math.type.MathText.toPlainText(com.vortessence.arithmeticus.core.math.type.ResultFormatter.format(complex, com.vortessence.arithmeticus.core.math.type.ResultForm.DECIMAL)))
    }

    @Test
    fun inDegreesAnAngleIsShownInDegrees() {
        val deg = MathContext(angleUnit = com.vortessence.arithmeticus.core.math.context.AngleUnit.DEGREES)
        // sin(18) was shown as sin(π/10), the radians it is worked out in
        assertEquals("sin(18)", result("sin(18)", deg))
        assertEquals("2sin(18)", result("2sin(18)", deg))
        // And sin(1 rad) as sin(1), which read as degrees, also when used again as Ans
        val radian = calc("sin(1#u{rad})", deg)
        assertEquals("sin((180)/(π))", result("sin(1#u{rad})", deg))
        assertEquals(radian.numericValue, runBlocking { MathEngine.calculate(radian.answer!!, deg) }.numericValue, 1e-12)
    }

    @Test
    fun factorOfAPolynomial() {
        // factor(x² − 1) gave itself back, unevaluated
        assertEquals("(x + 1)(x −1)", result("factor(x^2-1)"))
        assertEquals("(x −1)(x −2)(x −3)", result("factor(x^3-6x^2+11x-6)"))
        assertEquals("x(x + 1)(x −1)", result("factor(x^3-x)"))
        // A common factor, also of several letters
        assertEquals("y(x −1)", result("factor(x*y-y)"))
        assertEquals("6y(x + 1)(x −1)", result("factor(6x^2*y-6y)"))
        assertEquals("−(x + y)", result("factor(-x-y)"))
        // Without factors it stays as it is, not as factor(…)
        assertFalse(result("factor(x^2+1)").contains("factor"))
        assertEquals("(2)^(3) × (3)^(2) × 5", result("factor(360)"))
    }

    @Test
    fun expandOfAHighPower() {
        // expand((x + 1)¹⁰) was left as it is
        assertTrue(result("expand((x+1)^{10})").startsWith("(x)^(10) + 10(x)^(9) + 45(x)^(8)"))
        val start = System.currentTimeMillis()
        assertTrue(calc("expand((x+1)^{200})").isSuccess)
        assertEquals(MathIssue.VALUES_TOO_LARGE, issue("expand((x+y+z+w)^{60})"))
        assertTrue(System.currentTimeMillis() - start < 3000)
    }

    @Test
    fun withComplexNumbersAPolynomialHasAllItsRoots() {
        val complex = MathContext(complexNumbers = true)
        fun roots(text: String) = result(text, complex).split(", ").size
        // x³ = 1 gave only 1, x⁴ = −1 no solution
        assertEquals("x = −(1)/(2) −(isqrt(3))/(2), x = −(1)/(2) + (isqrt(3))/(2), x = 1", result("x^3=1", complex))
        assertEquals("x = −1 −i, x = −1 + i, x = 1 −i, x = 1 + i", result("x^4+4=0", complex))
        assertEquals("x = −2, x = −1 −isqrt(3), x = −1 + isqrt(3), x = 1 −isqrt(3), x = 1 + isqrt(3), x = 2", result("x^6=64", complex))
        assertEquals("x = −2i, x = −i, x = i, x = 2i", result("(x^2+1)(x^2+4)=0", complex))
        assertEquals(4, roots("x^4=-1"))
        assertEquals(5, roots("x^5-x-1=0"))
        // Without complex numbers, the real ones as before
        assertEquals("x = 1", result("x^3=1"))
        assertEquals(MathIssue.NO_REAL_SOLUTION, issue("x^4=-1"))
    }

    @Test
    fun anIntegralWithLettersIsWorkedOut() {
        // These stayed as they were typed
        assertEquals("((t)^(2))/(2)", result("I[0,t]{x}"))
        assertEquals("((x)^(2))/(2)", result("I[0,x]{x}"))
        assertEquals("((b)^(2))/(2) −((a)^(2))/(2)", result("I[a,b]{x}"))
        assertEquals("(a)/(2)", result("I[0,1]{a*x}"))
        assertEquals("(e)^( −t)", result("I[t,inf]{e^{-x}}"))
        assertEquals("ln(abs(t))", result("I[1,t]{1/x}"))
        assertEquals("tln(t) −t + 1", result("I[1,t]{ln(x)}"))
        // Not where a pole lies between the bounds, or where F has no value at one
        for (text in listOf("I[-1,t]{1/x^2}", "I[0,t]{1/x}", "I[0,t]{e^{x^2}}")) assertTrue(text, result(text).startsWith("int_"))
    }

    @Test
    fun aLimitFromOneSideOfAJump() {
        // Where the limit from both sides does not exist, each side has its own
        assertEquals("0", result("L[x->0^+]{floor(x)}"))
        assertEquals("−1", result("L[x->0^-]{floor(x)}"))
        assertEquals("1", result("L[x->0^+]{abs(x)/x}"))
        assertEquals("−1", result("L[x->0^-]{abs(x)/x}"))
        assertEquals("∞", result("L[x->0^+]{1/x}"))
        assertEquals("0", result("L[x->0^-]{e^{1/x}}"))
    }

    private fun decimal(text: String) = calc(text).numericValue

    @Test
    fun decimalsAreRightToTheLastDigit() {
        // In doubles: 0, 0, 0 and wrong digits
        assertEquals(5e-11, decimal("sqrt(10^{20}+1)-10^{10}"), 1e-20)
        assertEquals(5e-51, decimal("sqrt(10^{100}+1)-10^{50}"), 1e-60)
        assertEquals(5e-17, decimal("1-cos(10^{-8})"), 1e-26)
        assertEquals(1e-7, decimal("sqrt(25000000000001)-5000000"), 1e-16)
        // sin(10³⁰) needs π to 50 digits (values from a 120-digit calculation)
        assertEquals(-0.09011690191213806, decimal("sin(10^{30})"), 1e-15)
        assertEquals(-0.6135286082336636, decimal("cos(10^{50})"), 1e-15)
        assertEquals(-7.499274028018143e-13, decimal("e^{pi*sqrt(163)}-262537412640768744"), 1e-22)
        // A value that is 0 is 0
        assertEquals(0.0, decimal("sin(pi/7)^2+cos(pi/7)^2-1"), 0.0)
    }

    @Test
    fun powersOfIGoRoundInFour() {
        val complex = MathContext(complexNumbers = true)
        // i^(10²⁰) was −0.98 + 0.20i, worked out with doubles
        assertEquals("1", result("#i^{10^{20}}", complex))
        assertEquals("i", result("#i^{10^{20}+1}", complex))
        assertEquals("−i", result("(-#i)^{10^{20}+1}", complex))
    }

    @Test
    fun aChainOfConditions() {
        // 2 < x < 5 was a syntax error
        assertEquals("2 < x < 5", result("2<x<5"))
        assertEquals("2 ≤ x < 5", result("5>x>=2"))
        assertEquals("1 < x < 4", result("1<2x-1<7"))
        assertEquals("true", result("1<2<3"))
        assertEquals("false", result("3<2<1"))
        assertEquals(MathIssue.SYNTAX_ERROR, issue("2<x<5=3"))
    }

    @Test
    fun anEquationWithConditions() {
        // An equation with conditions on the lines after it was a syntax error
        assertEquals("x = 2", result("x^2=4|x>0"))
        assertEquals("x = −2", result("x^2=4|x<0"))
        assertEquals(MathIssue.NO_REAL_SOLUTION, issue("x^2=4|x>5"))
        assertEquals("x = ln(5)", result("e^x=5|x>0"))
        assertEquals("x > 2", result("x=x|x>2"))
        // A periodic equation in a finite range: all its solutions there
        assertEquals("x = π, x = 2π, x = 3π", result("sin(x)=0|0<x<10"))
        assertEquals("x = (π)/(6), x = (5π)/(6)", result("sin(x)=[1;2]|0<x<2pi"))
    }

    @Test
    fun aMatrixPowerDoesNotGrowWithoutLimit() {
        // The millionth power had entries of millions of digits, and took longer than the time limit
        val start = System.currentTimeMillis()
        assertEquals(MathIssue.VALUES_TOO_LARGE, issue("M[1 2/3 4]^{1000000}"))
        assertEquals(MathIssue.VALUES_TOO_LARGE, issue("M[1 2/3 4]^{10000}"))
        assertEquals(MathIssue.VALUES_TOO_LARGE, issue("M[1 2/3 4]^{-100000}"))
        assertTrue(System.currentTimeMillis() - start < 3000)
        // What fits is still calculated
        assertTrue(calc("M[1 2/3 4]^{100}").isSuccess)
        assertTrue(calc("M[1 2/3 4]^{-100}").isSuccess)
    }

    /** The results of [lines] calculated one after the other, each with the definitions of the lines before it, as in the history. */
    private fun session(vararg lines: String): List<String> {
        val definitions = LinkedHashMap<String, com.vortessence.arithmeticus.core.math.type.MathString>()
        return lines.map { line ->
            val r = calc(line, MathContext(variables = LinkedHashMap(definitions)))
            if (!r.isSuccess) return@map "error ${r.issues}"
            val key = r.definedVariable
            val answer = r.answer
            if (key != null && answer != null) com.vortessence.arithmeticus.core.math.type.FunctionDefinitions.define(definitions, key, answer)
            r.result.toString().replace(Regex("\\s+"), "")
        }
    }

    private fun solved(text: String, context: MathContext = MathContext()) = calc(text, context).let {
        assertTrue("$text: ${it.issues}", it.isSuccess)
        it.result.toString().replace(Regex("\\s+"), "")
    }

    private fun hasSteps(text: String) = runBlocking { MathEngine.steps(TestInput.parse(text)) } != null

    @Test
    fun integralsOfAbsoluteValuesAndLogarithms() {
        // No antiderivative before
        assertEquals("(xabs(x))/(2)+C", solved("I{abs(x)}"))
        assertEquals("((x)^(2)abs(x))/(3)+C", solved("I{x*abs(x)}"))
        assertEquals("(abs(2x−1)(2x−1))/(4)+C", solved("I{abs(2x-1)}"))
        assertEquals("(2x)/(sqrt(abs(x)))+C", solved("I{1/sqrt(abs(x))}"))
        // The steps of these, which had none
        for (t in listOf("I{abs(x)}", "I{ln(x)^2}", "I{sqrt(x)*ln(x)}")) assertTrue(t, hasSteps(t))
        // ln|u|' is 1/u, not u/u²
        assertEquals("(1)/(x)", solved("D{ln(abs(x))}"))
        assertEquals("(1)/(x−1)", solved("D{ln(abs(x-1))}"))
    }

    @Test
    fun integralsOverAPointWhereTheIntegrandHasNoValue() {
        // Undefined before: the point inside is split off, each side is integrable
        assertEquals("−2", solved("I[-1,1]{ln(abs(x))}"))
        assertEquals("−2", solved("I[0,2]{ln(abs(x-1))}"))
        assertEquals("4", solved("I[-1,1]{1/sqrt(abs(x))}"))
        // … and to ∞, where the numbers do not settle
        assertEquals("(π)/(2)", solved("I[0,inf]{sin(x)/x}"))
        // What diverges still does
        assertEquals(MathIssue.UNDEFINED, issue("I[-1,1]{1/x}"))
        assertEquals(MathIssue.UNDEFINED, issue("I[-1,1]{1/x^2}"))
        assertEquals(MathIssue.UNDEFINED, issue("I[0,inf]{sin(x)}"))
        // Limits the integrals need: 0·∞ from one side, with |…|
        assertEquals("0", solved("L[x->1^-]{ln(abs(x-1))(x-1)-x+1}"))
        assertEquals("0", solved("L[x->0^+]{2x/sqrt(abs(x))}"))
        assertEquals("0", solved("L[x->0]{2x/sqrt(abs(x))}"))
    }

    private fun decimalForm(text: String, context: MathContext = MathContext()): String? {
        val r = calc(text, context)
        val decimal = com.vortessence.arithmeticus.core.math.type.ResultForm.DECIMAL
        if (decimal !in com.vortessence.arithmeticus.core.math.type.ResultFormatter.availableForms(r)) return null
        return com.vortessence.arithmeticus.core.math.type.ResultFormatter.format(r, decimal).toString().replace(Regex("\\s+"), "")
    }

    private fun stepsOf(text: String, transform: com.vortessence.arithmeticus.core.math.steps.Transform? = null) =
        runBlocking { MathEngine.steps(TestInput.parse(text), transform = transform) }

    @Test
    fun theStepsOfASystemWithConditions() {
        val steps = stepsOf("x^2+y^2=25|x+y=7|x>y")!!
        // The input with its condition comes first
        assertTrue(steps.steps.first().expression.toString().contains("x > y"))
        val check = steps.steps.single { it.rule == com.vortessence.arithmeticus.core.math.steps.StepRule.CHECK_CONDITIONS }
        assertEquals(listOf("3≯4", "4>3"), check.notes.map { it.toString().replace(Regex("\\s+"), "") })
        assertEquals("x=4,y=3", steps.result.toString().replace(Regex("\\s+"), ""))
        assertEquals("norealsolution", stepsOf("x+y=3|x-y=1|x<0")!!.result.toString().replace(Regex("\\s+"), ""))
    }

    @Test
    fun theStepsOfTheNewResults() {
        // None of these had steps
        for (t in listOf("x*sin(x)=0", "(x-1)*sin(x)=0", "sin(x^2)=0", "ln(x)=1000", "e^x=10^{500}", "I{x*abs(x)}", "I{e^x/x^2}")) assertNotNull(t, stepsOf(t))
        val power = stepsOf("sin(x^2)=0")!!
        assertEquals(com.vortessence.arithmeticus.core.math.steps.StepRule.SUBSTITUTE_POWER, power.steps[1].rule)
        assertEquals("x=sqrt(πk),x=−sqrt(πk),k∈ℤ,k≥0", power.result.toString().replace(Regex("\\s+"), ""))
        // factor: what has no rational root is split, with the product multiplied out as the check
        val factored = stepsOf("x^4+4", com.vortessence.arithmeticus.core.math.steps.Transform.FACTOR)!!
        assertEquals("((x)^(2)−2x+2)((x)^(2)+2x+2)", factored.result.toString().replace(Regex("\\s+"), ""))
        // Beyond the numbers the search tries: a root on the way that is ∞ as a double, a tangent family without found members
        assertEquals("x=atan(10000000000)+πk,k∈ℤ", stepsOf("tan(x)=10^{10}")!!.result.toString().replace(Regex("\\s+"), ""))
        assertTrue(stepsOf("x^3=10^{400}")!!.steps.any { it.rule == com.vortessence.arithmeticus.core.math.steps.StepRule.NTH_ROOT_BOTH_SIDES })
        assertTrue(factored.steps.last().notes.single().toString().replace(Regex("\\s+"), "").endsWith("=(x)^(4)+4"))
    }

    @Test
    fun aFunctionOverAPowerOfXByParts() {
        // No antiderivative before
        assertEquals("−((e)^(x))/(x)+Ei(x)+C", solved("I{e^x/x^2}"))
        assertEquals("−(cos(x))/(x)−Si(x)+C", solved("I{cos(x)/x^2}"))
        assertTrue(solved("I{sin(x)^2/x^2}").contains("Si(2x)"))
        assertEquals("(π)/(2)", solved("I[0,inf]{sin(x)^2/x^2}"))
    }

    @Test
    fun aSeriesWithoutAnAntiderivativeHasItsRestWorkedOutByNumbers() {
        // "Cannot be calculated" before; Σ ln(n)/n² − Σ ln(n)/(n²(n² + 1)) = 0.93755 − 0.05840
        assertSeries(0.8791469081003892, "#S[n=1,inf]{ln(n)/(n^2+1)}")
    }

    @Test
    fun integralsThatSwingTooOftenForTheAdaptiveRule() {
        // Undefined before. Fresnel: √(π/8) − cos(10⁶)/2000 + … and √(π/8) + sin(10⁴)/200 + …
        assertEquals(0.6261886926789387, calc("I[0,1000]{sin(x^2)}").numericValue, 1e-9)
        assertEquals(0.6251292347635955, calc("I[0,100]{cos(x^2)}").numericValue, 1e-9)
        // Poles are not taken for swings: the halves of 1/x cancel, but the integral diverges
        for (pole in listOf("I[-1,1]{1/x}", "I[-1,1]{1/x^3}")) assertEquals(pole, MathIssue.UNDEFINED, issue(pole))
    }

    @Test
    fun integralsThatSwingInfinitelyOftenAtABound() {
        // Undefined before: with x = 1/u the swings are spread out to ∞
        assertEquals("sin(1)−Ci(1)", solved("I[0,1]{sin(1/x)}"))
        assertEquals("sin(1)−Ci(1)", solved("I[0,1]{sin(1/(1-x))}"))
        assertEquals(-0.08441095055957382, calc("I[0,1]{cos(1/x)}").numericValue, 1e-12)
        assertEquals(0.37853001712416134, calc("I[0,1]{x*sin(1/x)}").numericValue, 1e-12)
        // What diverges still does
        assertEquals(MathIssue.UNDEFINED, issue("I[0,1]{1/x}"))
    }

    @Test
    fun integralsToInfinityThatSwingAndDieOff() {
        // Undefined before: f(1/t)/t² swings infinitely often at t = 0; now by the half-waves, summed with Wynn's ε-algorithm
        assertEquals(Math.PI / (2 * Math.E), calc("I[0,inf]{cos(x)/(1+x^2)}").numericValue, 1e-12)
        assertEquals(Math.PI / Math.E, calc("I[-inf,inf]{cos(x)/(1+x^2)}").numericValue, 1e-11)
        assertEquals(Math.PI / 4 * Math.exp(-6.0), calc("I[0,inf]{cos(3x)/(4+x^2)}").numericValue, 1e-12)
        assertEquals(0.6327775338687378, calc("I[1,inf]{sin(x)/sqrt(x)}").numericValue, 1e-9)
        // Swings that do not die off are no integral
        for (t in listOf("I[0,inf]{sin(x)}", "I[0,inf]{sin(x)*(1+1/x)}")) assertEquals(t, MathIssue.UNDEFINED, issue(t))
    }

    @Test
    fun aTrigonometricEquationInAPowerOfX() {
        // Ten roots near 0 and ", …" before
        assertEquals("x=sqrt(πk),x=−sqrt(πk),k∈ℤ,k≥0", solved("sin(x^2)=0"))
        assertEquals("x=root[3](πk),k∈ℤ", solved("sin(x^3)=0"))
        // −x² = π/6 + 2πk has roots only for k ≤ −1
        assertTrue(solved("sin(-x^2)=1/2").endsWith("k∈ℤ,k≤−1"))
        assertEquals(MathIssue.NO_REAL_SOLUTION, issue("sin(x^2)=2"))
    }

    @Test
    fun aWeightedSumOfTemperaturesIsATemperature() {
        // An error before: weights ½ + ½ add up to 1, so it is a point on the scale
        assertEquals("25°C", physical("(20#u{°C}+30#u{°C})/2"))
        assertEquals("30°C", physical("(20#u{°C}+30#u{°C}+40#u{°C})/3"))
        assertEquals("68°F", physical("(68#u{°F}+20#u{°C})/2"))
        // Also with the weight in several factors (was an error)
        assertEquals("25°C", physical("2*(20#u{°C}+30#u{°C})/4"))
        // Weights that add up to anything else are none
        assertEquals(MathIssue.TEMPERATURE_ARITHMETIC, issue("(20#u{°C}+30#u{°C})/3", MathContext(physicalTemperatures = true)))
        // Kelvin arithmetic (the default) as before
        assertEquals("298.15K", result("(20#u{°C}+30#u{°C})/2").replace(" ", ""))
    }

    @Test
    fun decimalsBeyondTheRangeOfDoubles() {
        // No decimal form before: ∞ and 0 as doubles
        assertEquals("1.970071114×10^(434)", decimalForm("e^{1000}"))
        assertEquals("5.075958898×10^(−435)", decimalForm("e^{-1000}"))
        assertEquals("1.414213562×10^(400)", decimalForm("2^{0.5}*10^{400}"))
        // An exact fraction beyond them was "too large"
        assertEquals("3.333333333×10^(399)", decimalForm("10^{400}/3"))
        assertEquals("1×10^(−400)", decimalForm("1/10^{400}"))
        // The imaginary part with all its digits right, not 0
        assertEquals("5×10^(−11)i", decimalForm("#i*(sqrt(10^{20}+1)-10^{10})", MathContext(complexNumbers = true)))
    }

    @Test
    fun equationsWithConditionsInSeveralVariables() {
        // "Cannot solve" before: the system is solved, the conditions keep some of its solutions
        assertEquals("x=2,y=1", solved("x+y=3|x-y=1|x>0"))
        assertEquals(MathIssue.NO_REAL_SOLUTION, issue("x+y=3|x-y=1|x<0"))
        assertEquals("x=4,y=3", solved("x^2+y^2=25|x+y=7|x>y"))
        assertEquals("x=2,y=3", solved("x*y=6|x+y=5|x<y"))
        // One equation does not settle two variables
        assertEquals(MathIssue.CANT_SOLVE_GENERIC, issue("x+y=3|x>0"))
    }

    @Test
    fun theStepsOfAnEquationWithConditions() {
        val steps = runBlocking { MathEngine.steps(TestInput.parse("x^2=4|x>0")) }!!
        val check = steps.steps.single { it.rule == com.vortessence.arithmeticus.core.math.steps.StepRule.CHECK_CONDITIONS }
        assertEquals(listOf("2>0", "−2≯0"), check.notes.map { it.toString().replace(Regex("\\s+"), "") })
        assertEquals("x=2", steps.result.toString().replace(Regex("\\s+"), ""))
        assertNotNull(runBlocking { MathEngine.steps(TestInput.parse("e^x=5|x>0")) })
    }

    @Test
    fun rootsOfNegativeNumbersThatAreNoPlainNumbersWithComplexNumbers() {
        val complex = MathContext(complexNumbers = true)
        // These stayed as they were, with no value
        assertEquals("isqrt(ln(2))", solved("sqrt(ln(0.5))", complex))
        assertEquals("isqrt(π)", solved("sqrt(-pi)", complex))
        val r = calc("sqrt(1-e)", complex)
        assertEquals(0.0, r.numericValue, 1e-15)
        assertEquals(kotlin.math.sqrt(kotlin.math.E - 1), r.numericImaginary, 1e-12)
        // An odd root is the real one
        assertEquals("−root[3](ln(2))", solved("(ln(0.5))^{1/3}", complex))
        // Without complex numbers it is still an error
        assertEquals(MathIssue.NEGATIVE_ROOT, issue("sqrt(ln(0.5))"))
    }

    @Test
    fun aSumOfTemperaturesIsJudgedAsAWhole() {
        // 20 °C + 30 °C was judged on its own (no temperature) before the − 10 °C came
        assertEquals("40°C", physical("20#u{°C}+30#u{°C}-10#u{°C}"))
        // A sum after a minus counts with the others too (an error before), and −(a temperature) worked out is none (was −10 °C)
        assertEquals("50K", physical("60#u{°C}-(20#u{°C}+30#u{°C})+40#u{°C}"))
        assertEquals("−40K", physical("(20#u{°C}+30#u{°C})-(40#u{°C}+50#u{°C})"))
        assertEquals("20°C", physical("30#u{°C}-(20#u{°C}-10#u{°C})"))
        for (t in listOf("-(20#u{°C}+30#u{°C}-40#u{°C})", "100#u{°C}-(20#u{°C}+30#u{°C})")) {
            assertEquals(t, MathIssue.TEMPERATURE_ARITHMETIC, issue(t, MathContext(physicalTemperatures = true)))
        }
        assertEquals(MathIssue.TEMPERATURE_ARITHMETIC, issue("20#u{°C}+30#u{°C}", MathContext(physicalTemperatures = true)))
    }

    @Test
    fun seriesWithALogarithmInTheTerms() {
        // "Cannot be calculated" before: the integral test decides, Euler–Maclaurin gives the value
        assertSeries(0.9375482543158437, "#S[n=1,inf]{ln(n)/n^2}") // −ζ′(2)
        assertSeries(1.9892802342989010, "#S[n=1,inf]{ln(n)^2/n^2}") // ζ″(2)
        assertSeries(2.1097428012368919, "#S[n=2,inf]{1/(n*ln(n)^2)}")
        // Diverges like ln(ln n), which no number of terms shows
        assertEquals(Double.POSITIVE_INFINITY, calc("#S[n=2,inf]{1/(n*ln(n))}").numericValue, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, calc("#S[n=2,inf]{1/(n*sqrt(ln(n)))}").numericValue, 0.0)
    }

    @Test
    fun factorsWithoutRationalRoots() {
        // These stayed as they were: no rational root, but factors of degree 2 and more
        assertEquals("((x)^(2)−2x+2)((x)^(2)+2x+2)", solved("factor(x^4+4)"))
        assertEquals("x((x)^(2)−2x+2)((x)^(2)+2x+2)", solved("factor(x^5+4x)"))
        assertEquals("2((x)^(2)−2x+2)((x)^(2)+2x+2)", solved("factor(2x^4+8)"))
        assertEquals("((x)^(2)+1)((x)^(4)−(x)^(2)+1)", solved("factor(x^6+1)"))
        assertEquals("((x)^(2)+x+1)((x)^(3)−(x)^(2)+1)", solved("factor(x^5+x+1)"))
        assertEquals("(((x)^(2)+1))^(2)", solved("factor(x^4+2x^2+1)"))
        assertEquals("(x+1)(x−1)((x)^(2)−x+1)((x)^(2)+1)((x)^(2)+x+1)((x)^(4)−(x)^(2)+1)", solved("factor(x^{12}-1)"))
        assertEquals("y((x)^(2)−2x+2)((x)^(2)+2x+2)", solved("factor(x^4y+4y)"))
        // No factors with rational coefficients: they stay
        for (prime in listOf("x^4+1", "x^3+2", "x^4-10x^2+1", "x^2+1")) {
            assertEquals(prime, solved("factor($prime)").replace("(x)", "x").replace("^(", "^").replace(")", "").replace("−", "-"), prime.replace("−", "-"))
        }
    }

    @Test
    fun aProductWithARepeatingFactorHasItsWholeFamily() {
        // These listed the roots between −10 and 10
        assertEquals("x=πk,k∈ℤ", solved("x*sin(x)=0"))
        assertEquals("x=1,x=πk,k∈ℤ", solved("(x-1)*sin(x)=0"))
        assertEquals("x=−2,x=2,x=(π)/(2)+πk,k∈ℤ", solved("(x^2-4)*cos(x)=0"))
        assertEquals("x=0,x=(π)/(6)+2πk,x=(5π)/(6)+2πk,k∈ℤ", solved("x*(2sin(x)-1)=0"))
        // Where the product is not defined it is no solution: tan(x)·cos(x) is sin(x), not 0 where cos(x) is
        assertEquals("x=πk,k∈ℤ", solved("tan(x)*cos(x)=0"))
        // A family that is in another is not written again
        assertEquals("x=πk,x=(π)/(2)+πk,k∈ℤ", solved("sin(x)*sin(2x)=0"))
        assertEquals("x=180k,k∈ℤ", solved("x*sin(x)=0", MathContext(angleUnit = com.vortessence.arithmeticus.core.math.context.AngleUnit.DEGREES)))
    }

    @Test
    fun solutionsBeyondTheNumbersTheSearchTries() {
        // These said "No solution found", or (x³ = 10⁴⁰⁰) "no real solution"
        assertEquals("x=atan(10000000000)+πk,k∈ℤ", solved("tan(x)=10^{10}"))
        assertEquals("x=(e)^(1000)", solved("ln(x)=1000"))
        assertEquals("x=500ln(10)", solved("e^x=10^{500}"))
        assertEquals("x=(400ln(10))/(ln(2))", solved("2^x=10^{400}"))
        assertEquals("x=((e)^(3335))/(2)−(1)/(2)", solved("3ln(2x+1)-5=10^{4}"))
        assertTrue(solved("x^3=10^{400}").startsWith("x=1000"))
        assertTrue(solved("x^3=-10^{400}").startsWith("x=−1000"))
        assertEquals(2, solved("x^2=10^{400}").split(",").size)
        // What has none still has none
        for (none in listOf("x^2=-10^{400}", "e^x=-5", "x^2=-4")) assertEquals(none, MathIssue.NO_REAL_SOLUTION, issue(none))
        // atan of a large number is close to π/2, but not it
        assertEquals("atan(10000000000)", result("atan(10^{10})").replace(" ", ""))
    }

    @Test
    fun aFamilyWhereTheEquationIsDefinedFromSomeKOn() {
        // Was a list with ", …" (ln(x)·sin(x) = 0) and x = πk for every k (√x·sin(x) = 0, also for x < 0)
        assertEquals("x=1,x=πk,k∈ℤ,k≥1", solved("ln(x)*sin(x)=0"))
        assertEquals("x=πk,k∈ℤ,k≥0", solved("sqrt(x)*sin(x)=0"))
        assertEquals("x=−1,x=(π)/(2)+πk,k∈ℤ,k≤−1", solved("ln(-x)*cos(x)=0"))
        // Where it is defined nowhere the family is left out, in the steps too (they ended in x = ±π/2 + 2πk)
        assertEquals("x=πk,k∈ℤ", solved("tan(x)*cos(x)=0"))
        assertEquals("x=πk,k∈ℤ", stepsOf("tan(x)*cos(x)=0")!!.result.toString().replace(Regex("\\s+"), ""))
        // The steps end in what the calculator shows (they kept x = 2πk and x = π + 2πk beside x = πk; another order)
        for (t in listOf("sin(x)*sin(2x)=0", "x^2-1=0|x<5")) {
            assertEquals(t, solved(t), stepsOf(t)!!.result.toString().replace(Regex("\\s+"), ""))
        }
        // For a few k only: single solutions, all of them (was "x = −1, x = 0, x = 1, …")
        assertEquals("x=−1,x=0,x=1", solved("sin(x)*sqrt(1-x^2)=0"))
        assertEquals("x=−2,x=−(π)/(2),x=(π)/(2),x=2", solved("cos(x)*sqrt(4-x^2)=0"))
        // Up to k = ±31 only: not taken for every k
        assertTrue(solved("sin(x)*sqrt(10000-x^2)=0").endsWith("…"))
        // The steps end in the same, with a note on k, not in a few numbers
        val steps = stepsOf("ln(x)*sin(x)=0")!!
        assertEquals("x=1,x=πk,k∈ℤ,k≥1", steps.result.toString().replace(Regex("\\s+"), ""))
        assertEquals("x=πk:k≥1", steps.steps.last().notes.single().toString().replace(Regex("\\s+"), ""))
    }

    @Test
    fun aListOfSolutionsSaysWhenThereAreMore() {
        // (sin(x²) = 0 has its family now: aTrigonometricEquationInAPowerOfX)
        for (more in listOf("tan(x)=x", "sin(1/x)=0", "e^{-x}=sin(x)", "x*sin(x)=1")) assertTrue(more, solved(more).endsWith(",…"))
        // These have all their solutions where they were looked for
        for (all in listOf("sin(x)=x/10", "sin(x)=x/2", "cos(x)=x", "x^2=sin(x)", "x^2=4")) assertFalse(all, solved(all).endsWith("…"))
    }

    @Test
    fun aLogarithmWithItsBaseAfterAComma() {
        // log(8, 2) as typed on a keyboard was a syntax error; pasting read it already
        assertEquals(3.0, calc("log(8,2)").numericValue, 1e-12)
        assertEquals("3", result("log(8,2)"))
        assertEquals("2", result("log(100)"))
        assertEquals(MathIssue.SYNTAX_ERROR, issue("log(8,2,3)"))
    }

    @Test
    fun userDefinedFunctions() {
        // f(3) was f·3
        assertEquals(listOf("f(x)=(x)^(2)+1", "10", "a=2", "10", "25", "2x"), session("f(x):=x^2+1", "f(3)", "a:=2", "f(a+1)", "f(2)^2", "D{f(x)}"))
        // Several parameters, replaced all at once
        assertEquals(listOf("g(x,y)=x−y", "3", "y−x"), session("g(x,y):=x-y", "g(5,2)", "g(y,x)"))
        // The parameter is the function's own letter, whatever x is defined as; f(x) then uses that x
        assertEquals(listOf("x=3", "f(x)=(x)^(2)", "9"), session("x:=3", "f(x):=x^2", "f(x)"))
        // A function uses the functions defined before it
        assertEquals("9", session("f(x):=x^2+1", "h(t):=2f(t)-1", "h(2)").last())
        // A function and a variable of the same name replace each other
        assertEquals("8", session("f:=2", "f(x):=x^3", "f(2)").last())
        assertEquals("6", session("f(x):=x^3", "f:=2", "f(3)").last())
        // The wrong number of arguments is no call
        assertTrue(session("f(x):=x", "f(1,2)").last().startsWith("error"))
        // Not a list of letters: no definition
        assertTrue(session("f(2):=3").single().startsWith("error"))
        // A function defined by itself is no definition (was h·x + 1, and h(1) gave h + 21)
        assertTrue(session("h(x):=h(x)+1").single().startsWith("error"))
        // The x inside an integral that stays one is the integration variable, not the parameter (f(1) was e^sin(1))
        assertEquals(listOf("1.631869608", "4.236531157", "(e)^(sin(x))"), session("f(x):=I[0,x]{e^{sin(x)}}", "f(1)", "f(2)", "D{f(x)}").drop(1))
    }

    @Test
    fun theStepsEndInWhatTheCalculatorShows() {
        // A check over the equations that got new results or new steps in the review
        val equations = listOf("x*sin(x)=0", "(x-1)*sin(x)=0", "sin(x^2)=0", "ln(x)=1000", "e^x=10^{500}", "x^3=10^{400}", "tan(x)=10^{10}", "ln(x)*sin(x)=0", "sqrt(x)*sin(x)=0",
            "tan(x)*cos(x)=0", "cos(x)*sin(x)=0", "sin(x)*sin(2x)=0", "x^2*cos(x)=0", "(x^2-4)*sin(x)=0", "sin(x)*ln(x+5)=0", "ln(-x)*cos(x)=0", "cos(x^3)=1", "sin(2x^2+1)=0", "tan(x^2)=1",
            "x^5=10^{500}", "e^{2x}=10^{800}", "ln(2x+1)=2000", "2^x=10^{400}", "sin(x)=1/2", "cos(x)=-1", "tan(x)=5", "2sin(x)^2-sin(x)-1=0", "x^2-5x+6=0", "sqrt(x+1)=x-1", "abs(x-1)=3",
            "x^2+y^2=25|x+y=7|x>y", "x^2=4|x>0", "x^2-1=0|x<5", "x*y=6|x+y=5|x<y")
        for (t in equations) {
            val steps = stepsOf(t)?.result?.toString()?.replace(Regex("\\s+"), "") ?: "no steps"
            assertEquals(t, solved(t), steps)
        }
    }
}
