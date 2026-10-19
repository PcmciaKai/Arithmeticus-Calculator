package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.expressions.containsUnit
import com.vortessence.arithmeticus.core.math.expressions.withUnitsAsSiFactors
import com.vortessence.arithmeticus.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NamedFunctionTest {

    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(nodes: List<MathNode>) = MathString(nodes)

    /** The input "name(a, b, ...)", each argument typed like "-2.5" or "x". */
    private fun call(name: String, vararg arguments: String): MathString {
        val argument = mutableListOf<MathNode>()
        arguments.forEachIndexed { i, text ->
            if (i > 0) argument.add(SeparatorNode)
            argument.addAll(MathText.fromPlainText(text)?.nodes ?: listOf(VariableNode(text)))
        }
        return ms(listOf(FunctionNode(name, ms(argument))))
    }

    private fun calc(input: MathString): CalculationResult = runBlocking { MathEngine.calculate(input, MathContext()) }

    private fun exact(name: String, vararg arguments: String): String {
        val res = calc(call(name, *arguments))
        assertTrue("$name: ${res.issues}", res.isSuccess)
        return res.result.toString()
    }

    private fun numeric(name: String, vararg arguments: String): Double {
        val res = calc(call(name, *arguments))
        assertTrue("$name: ${res.issues}", res.isSuccess)
        return res.numericValue
    }

    private fun issues(name: String, vararg arguments: String) = calc(call(name, *arguments)).issues

    @Test
    fun meanAndMedianAreExact() {
        assertEquals("7/3", exact("mean", "1", "2", "4").replace("(", "").replace(")", ""))
        assertEquals(5.0, numeric("median", "9", "1", "5"), 0.0)
        assertEquals(3.5, numeric("median", "4", "1", "3", "9"), 1e-12)
    }

    @Test
    fun standardDeviations() {
        // 2, 4, 4, 4, 5, 5, 7, 9: mean 5, squared deviations sum to 32
        val data = arrayOf("2", "4", "4", "4", "5", "5", "7", "9")
        assertEquals(2.0, numeric("stdevp", *data), 1e-12)
        assertEquals(4.0, numeric("varp", *data), 1e-12)
        assertEquals(32.0 / 7, numeric("var", *data), 1e-12)
        assertEquals(Math.sqrt(32.0 / 7), numeric("stdev", *data), 1e-12)
        assertEquals(Math.sqrt(32.0 / 7 / 8), numeric("sem", *data), 1e-12)
        // A sample needs at least two values
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("stdev", "3"))
        assertEquals(0.0, numeric("stdevp", "3"), 0.0)
    }

    @Test
    fun minAndMax() {
        assertEquals(-2.5, numeric("min", "4", "-2.5", "3"), 0.0)
        assertEquals(4.0, numeric("max", "4", "-2.5", "3"), 0.0)
    }

    @Test
    fun combinatoricsAndDivisors() {
        assertEquals(10.0, numeric("nCr", "5", "2"), 0.0)
        assertEquals(20.0, numeric("nPr", "5", "2"), 0.0)
        assertEquals(0.0, numeric("nCr", "2", "5"), 0.0)
        assertEquals(6.0, numeric("gcd", "12", "18", "30"), 0.0)
        assertEquals(36.0, numeric("lcm", "12", "18"), 0.0)
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), issues("gcd", "1.5", "3"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("nCr", "5"))
    }

    @Test
    fun rounding() {
        assertEquals(3.0, numeric("round", "2.5"), 0.0)
        assertEquals(-3.0, numeric("round", "-2.5"), 0.0)
        assertEquals(3.14, numeric("round", "3.14159", "2"), 0.0)
        assertEquals(1200.0, numeric("round", "1234", "-2"), 0.0)
        assertEquals(-1.0, numeric("floor", "-0.1"), 0.0)
        assertEquals(1.0, numeric("ceil", "0.1"), 0.0)
        // Exact: the decimal 2.675 is rounded as written, not as its binary approximation
        assertEquals(2.68, numeric("round", "2.675", "2"), 0.0)
        // Rounding π, which is not a fraction
        val pi = ms(listOf(FunctionNode("round", ms(listOf(ConstantNode("π"), SeparatorNode, NumberNode("3"))))))
        assertEquals(3.142, calc(pi).numericValue, 0.0)
    }

    @Test
    fun staysSymbolicUntilTheValuesAreKnown() {
        val res = calc(call("mean", "x", "4"))
        assertTrue(res.issues.toString(), res.isSuccess)
        // The result is written back with commas, so it can be reused as input
        assertTrue(res.answer!!.nodes.any { node -> node is FunctionNode || node.subStrings.isNotEmpty() })
    }

    @Test
    fun commaOutsideOfAFunctionIsAnError() {
        val res = calc(ms(listOf(NumberNode("1"), SeparatorNode, NumberNode("2"))))
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), res.issues)
        // Functions with a single argument do not take several
        assertFalse(calc(call("sin", "1", "2")).isSuccess)
    }

    @Test
    fun plainTextWithCommas() {
        assertEquals("stdev(1, 2, 3)", MathText.toPlainText(call("stdev", "1", "2", "3")))
    }

    private fun exactComplex(name: String, vararg arguments: String): String {
        val res = runBlocking { MathEngine.calculate(call(name, *arguments), MathContext(complexNumbers = true)) }
        assertTrue("$name: ${res.issues}", res.isSuccess)
        return res.result.toString()
    }

    private fun single(name: String, argument: MathString): CalculationResult =
        runBlocking { MathEngine.calculate(ms(listOf(FunctionNode(name, argument))), MathContext(complexNumbers = true)) }

    @Test
    fun modeRangeAndTotals() {
        assertEquals(4.0, numeric("mode", "1", "4", "4", "2"), 0.0)
        // Several values occurring equally often: the first of them
        assertEquals(3.0, numeric("mode", "3", "1", "1", "3"), 0.0)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("mode", "1", "2", "3"))
        assertEquals(6.5, numeric("range", "4", "-2.5", "3"), 0.0)
        assertEquals("10", exact("sum", "1", "2", "3", "4"))
        assertEquals("24", exact("prod", "1", "2", "3", "4"))
        assertEquals("30", exact("sumsq", "1", "2", "3", "4"))
        assertEquals("5", exact("hypot", "3", "4"))
        assertEquals("13", exact("hypot", "3", "4", "12"))
    }

    @Test
    fun arithmeticFunctionsWorkWithVariables() {
        val res = calc(call("sum", "x", "x", "2"))
        assertTrue(res.issues.toString(), res.isSuccess)
        // Written out, not kept as sum(...)
        assertFalse(res.answer!!.nodes.any { it is FunctionNode })
        assertTrue(calc(call("hypot", "x", "4")).isSuccess)
    }

    @Test
    fun percentilesInterpolate() {
        // 15, 20, 35, 40, 50: the 40th percentile is at position 0.4 · 4 = 1.6
        val data = arrayOf("15", "20", "35", "40", "50")
        assertEquals("29", exact("percentile", "40", *data))
        assertEquals(15.0, numeric("percentile", "0", *data), 0.0)
        assertEquals(50.0, numeric("percentile", "100", *data), 0.0)
        assertEquals(35.0, numeric("percentile", "50", *data), 0.0)
        assertEquals(numeric("median", "1", "2", "3", "10"), numeric("percentile", "50", "10", "1", "3", "2"), 1e-12)
        assertEquals(20.0, numeric("quartile", "1", *data), 0.0)
        assertEquals(27.5, numeric("percentile", "37.5", *data), 0.0)
        assertEquals(35.0, numeric("quartile", "2", *data), 0.0)
        assertEquals(40.0, numeric("quartile", "3", *data), 0.0)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("percentile", "101", *data))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("quartile", "5", *data))
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), issues("quartile", "1.5", *data))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("percentile", "50"))
    }

    @Test
    fun skewness() {
        assertEquals(0.0, numeric("skew", "1", "2", "3"), 1e-12)
        // 1, 1, 4: mean 2, deviations -1, -1, 2: m2 = 2, m3 = 2 -> 2 / 2^1.5
        assertEquals(2.0 / Math.pow(2.0, 1.5), numeric("skew", "1", "1", "4"), 1e-12)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("skew", "2", "2", "2"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("skew", "1", "2"))
    }

    @Test
    fun signTruncAndFraction() {
        assertEquals(-1.0, numeric("sign", "-3.5"), 0.0)
        assertEquals(0.0, numeric("sign", "0"), 0.0)
        assertEquals(1.0, numeric("sign", "0.001"), 0.0)
        assertEquals(-2.0, numeric("trunc", "-2.75"), 0.0)
        assertEquals(3.14, numeric("trunc", "3.14159", "2"), 0.0)
        assertEquals(-0.75, numeric("frac", "-2.75"), 0.0)
        assertEquals("(3)/(4)", exact("frac", "2.75"))
        val pi = ms(listOf(FunctionNode("frac", ms(listOf(ConstantNode("π"))))))
        assertEquals(Math.PI - 3, calc(pi).numericValue, 1e-12)
    }

    @Test
    fun combinatoricsAndPrimes() {
        // 4! / (2! 1! 1!) = 12
        assertEquals("12", exact("multinomial", "2", "1", "1"))
        assertEquals("1", exact("multinomial", "5"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("multinomial", "2", "-1"))
        assertEquals("0", exact("fib", "0"))
        assertEquals("55", exact("fib", "10"))
        assertEquals("12586269025", exact("fib", "50"))
        assertEquals(-8.0, numeric("fib", "-6"), 0.0)
        assertEquals("5", exact("fib", "-5"))
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), issues("fib", "100000"))
        assertEquals("2", exact("nextprime", "-5"))
        assertEquals("11", exact("nextprime", "7"))
        assertEquals("1009", exact("nextprime", "1000"))
        assertEquals("1", exact("isprime", "97"))
        assertEquals("0", exact("isprime", "91"))
        assertEquals("0", exact("isprime", "1"))
        assertEquals("1", exact("isprime", "2"))
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), issues("isprime", "2.5"))
    }

    @Test
    fun complexParts() {
        val z = listOf(NumberNode("3"), OperatorNode(MathOp.PLUS), NumberNode("4"), ConstantNode("i"))
        assertEquals("3", single("re", ms(z)).result.toString())
        assertEquals("4", single("im", ms(z)).result.toString())
        assertEquals("3 - 4i", single("conj", ms(z)).result.toString().replace("(", "").replace(")", "").replace("−", "-"))
        assertEquals(Math.atan2(4.0, 3.0), single("arg", ms(z)).numericValue, 1e-12)
        // Real numbers
        assertEquals("0", exactComplex("arg", "5"))
        assertEquals("π", exactComplex("arg", "-5"))
        assertEquals("5", exactComplex("re", "5"))
        assertEquals("0", exactComplex("im", "5"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("arg", "0"))
    }

    private fun withUnit(vararg values: Pair<String, String>): MathString {
        val argument = mutableListOf<MathNode>()
        values.forEachIndexed { i, (number, unit) ->
            if (i > 0) argument.add(SeparatorNode)
            argument.add(NumberNode(number))
            argument.add(UnitNode(unit))
        }
        return ms(argument)
    }

    @Test
    fun newStatisticsKeepUnits() {
        val range = single("range", withUnit("2" to "m", "5" to "m", "100" to "cm"))
        assertTrue(range.issues.toString(), range.isSuccess)
        assertTrue(range.expression.containsUnit())
        assertEquals(4.0, single("range", withUnit("2" to "m", "5" to "m", "100" to "cm")).expression.withUnitsAsSiFactors().evaluateNumerically(), 1e-12)
        val sum = single("sum", withUnit("2" to "m", "50" to "cm"))
        assertEquals(2.5, sum.expression.withUnitsAsSiFactors().evaluateNumerically(), 1e-12)
        val hypot = single("hypot", withUnit("3" to "m", "400" to "cm"))
        assertEquals(5.0, hypot.expression.withUnitsAsSiFactors().evaluateNumerically(), 1e-12)
        assertEquals(1.0, single("sign", withUnit("3" to "m")).numericValue, 0.0)
        assertEquals(listOf(MathIssue.INVALID_UNIT), single("trunc", withUnit("3" to "m")).issues)
        val mode = single("mode", withUnit("1" to "m", "5" to "m", "100" to "cm"))
        assertTrue(mode.issues.toString(), mode.isSuccess)
        // Mixed dimensions cannot be compared
        assertEquals(listOf(MathIssue.INVALID_UNIT), single("range", withUnit("2" to "m", "5" to "s")).issues)
    }

    @Test
    fun skewnessOfValuesWithAUnitHasNone() {
        val res = single("skew", withUnit("1" to "m", "1" to "m", "4" to "m"))
        assertTrue(res.issues.toString(), res.isSuccess)
        assertEquals(2.0 / Math.pow(2.0, 1.5), res.numericValue, 1e-12)
        assertFalse(res.expression.containsUnit())
    }

    @Test
    fun exponentialRootAndAngleConversion() {
        fun value(name: String, argument: String): Double {
            val res = single(name, ms(listOf(NumberNode(argument))))
            assertTrue("$name: ${res.issues}", res.isSuccess)
            return res.numericValue
        }
        assertEquals(Math.E, value("exp", "1"), 1e-12)
        assertEquals("1", single("exp", ms(listOf(NumberNode("0")))).result.toString())
        assertEquals(-2.0, value("cbrt", "-8"), 1e-12)
        assertEquals("2", single("cbrt", ms(listOf(NumberNode("8")))).result.toString())
        assertEquals(180.0, value("deg", "${Math.PI}"), 1e-9)
        assertEquals(Math.PI / 2, value("rad", "90"), 1e-12)
        val exactDegrees = single("deg", ms(listOf(ConstantNode("π"))))
        assertEquals("180", exactDegrees.result.toString())
        assertEquals("π", single("rad", ms(listOf(NumberNode("180")))).result.toString())
    }

    @Test
    fun exponentialCanBeDifferentiated() {
        val body = ms(listOf(FunctionNode("exp", ms(listOf(VariableNode("x"))))))
        val res = runBlocking { MathEngine.calculate(ms(listOf(DerivativeNode(body))), MathContext()) }
        assertTrue(res.issues.toString(), res.isSuccess)
    }

    private fun numericMode(name: String, vararg arguments: String): CalculationResult =
        runBlocking { MathEngine.calculate(call(name, *arguments), MathContext(mode = com.vortessence.arithmeticus.core.math.context.CalculationMode.NUMERIC)) }

    @Test
    fun percentilesInNumericMode() {
        // In numeric mode every number is a decimal; 50 and 37.5 are still read as the levels they are
        assertEquals(2.0, numericMode("percentile", "50", "1", "2", "3").numericValue, 1e-12)
        assertEquals(2.5, numericMode("quartile", "2", "1", "2", "3", "4").numericValue, 1e-12)
        assertEquals(27.5, numericMode("percentile", "37.5", "15", "20", "35", "40", "50").numericValue, 1e-12)
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), numericMode("quartile", "1.5", "1", "2", "3").issues)
        assertEquals(listOf(MathIssue.UNDEFINED), numericMode("percentile", "101", "1", "2", "3").issues)
    }

    @Test
    fun theConjugateOfARealNumberNeedsNoImaginaryUnit() {
        // With complex numbers off (the default) there is no i, and none is needed for conj(3)
        assertEquals(3.0, numeric("conj", "3"), 0.0)
        assertEquals(-2.5, numeric("conj", "-2.5"), 0.0)
        assertEquals("3 - 4i", single("conj", ms(listOf(NumberNode("3"), OperatorNode(MathOp.PLUS), NumberNode("4"), ConstantNode("i")))).result.toString().replace("(", "").replace(")", "").replace("−", "-").trim())
    }

    @Test
    fun roundingTooManyPlacesNumericallyIsUndefinedAtOnce() {
        // trunc(x, 100000000) with x unknown used to be worked out digit by digit when plotted or solved
        val started = System.nanoTime()
        val function = com.vortessence.arithmeticus.core.math.expressions.NamedFunction(
            com.vortessence.arithmeticus.core.math.expressions.NamedFunctionKind.TRUNC,
            listOf(com.vortessence.arithmeticus.core.math.expressions.Variable("x"), com.vortessence.arithmeticus.core.math.expressions.Value(100000000L))
        )
        assertTrue(function.evaluateNumerically(mapOf(com.vortessence.arithmeticus.core.math.expressions.Variable("x") to 1.5)).isNaN())
        assertTrue((System.nanoTime() - started) < 1_000_000_000L)
        assertEquals(1.23, com.vortessence.arithmeticus.core.math.expressions.NamedFunction(
            com.vortessence.arithmeticus.core.math.expressions.NamedFunctionKind.TRUNC,
            listOf(com.vortessence.arithmeticus.core.math.expressions.Variable("x"), com.vortessence.arithmeticus.core.math.expressions.Value(2L))
        ).evaluateNumerically(mapOf(com.vortessence.arithmeticus.core.math.expressions.Variable("x") to 1.2345)), 0.0)
    }

    @Test
    fun functionsWithJumpsAreNotSolved() {
        val x = VariableNode("x")
        fun input(name: String, op: MathOp, right: String) = ms(listOf(FunctionNode(name, ms(listOf(x))), OperatorNode(op), NumberNode(right)))
        // These used to give "no real solution" or a list of the jumps
        assertEquals(listOf(MathIssue.CANT_SOLVE_GENERIC), calc(input("sign", MathOp.EQUALS, "1")).issues)
        assertEquals(listOf(MathIssue.CANT_SOLVE_GENERIC), calc(input("trunc", MathOp.EQUALS, "2")).issues)
        assertFalse(calc(input("frac", MathOp.EQUALS, "0.25")).isSuccess)
        assertFalse(calc(input("floor", MathOp.GREATER, "2")).isSuccess)
        assertFalse(calc(input("frac", MathOp.LESS, "0.5")).isSuccess)
        assertFalse(calc(input("sign", MathOp.GREATER, "0")).isSuccess)
        // A function without jumps still is
        assertTrue(calc(ms(listOf(FunctionNode("mean", ms(listOf(x, SeparatorNode, NumberNode("3")))), OperatorNode(MathOp.EQUALS), NumberNode("5")))).isSuccess)
    }

    @Test
    fun theProbabilityAndTheMeanOfADistributionCanBeSolvedFor() {
        val p = VariableNode("p")
        // binomcdf(10, p, 5) = 0.5: continuous in p, so it can be solved (p is about 0.5)
        val input = ms(listOf(
            FunctionNode("binomcdf", ms(listOf(NumberNode("1"), NumberNode("0"), SeparatorNode, p, SeparatorNode, NumberNode("5")))),
            OperatorNode(MathOp.EQUALS), NumberNode("0.5")
        ))
        val res = calc(input)
        assertTrue("${res.issues}", res.isSuccess)
        assertEquals(0.5, res.numericValue, 0.06)
        val count = ms(listOf(
            FunctionNode("binomcdf", ms(listOf(NumberNode("1"), NumberNode("0"), SeparatorNode, NumberNode("0"), NumberNode("."), NumberNode("5"), SeparatorNode, VariableNode("k")))),
            OperatorNode(MathOp.EQUALS), NumberNode("0.5")
        ))
        // In k (a count) it jumps: no solution to find by sign changes
        assertFalse(calc(count).isSuccess)
    }
}
