package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

/** rand(), randint(a, b) and randnorm(μ, σ): numbers that belong to the seed of a line. */
class RandomNumberTest {

    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())

    /** The input "name(a, b, ...)", each argument typed like "-2.5" or "x"; no argument at all for none. */
    private fun call(name: String, vararg arguments: String): MathNode {
        val argument = mutableListOf<MathNode>()
        arguments.forEachIndexed { i, text ->
            if (i > 0) argument.add(SeparatorNode)
            argument.addAll(MathText.fromPlainText(text)?.nodes ?: listOf(VariableNode(text)))
        }
        return FunctionNode(name, MathString(argument))
    }

    private fun calc(input: MathString, seed: Long = 1L, mode: CalculationMode = CalculationMode.EXACT, variables: Map<String, MathString> = emptyMap()): CalculationResult =
        runBlocking { MathEngine.calculate(input, MathContext(mode = mode, randomSeed = seed, variables = variables)) }

    private fun value(name: String, vararg arguments: String, seed: Long = 1L): Double {
        val res = calc(ms(call(name, *arguments)), seed)
        assertTrue("$name: ${res.issues}", res.isSuccess)
        return res.numericValue
    }

    private fun issues(name: String, vararg arguments: String) = calc(ms(call(name, *arguments))).issues

    @Test
    fun theSameSeedGivesTheSameNumberEveryTime() {
        val first = value("rand", seed = 42L)
        repeat(5) { assertEquals(first, value("rand", seed = 42L), 0.0) }
        // In both calculation modes, and as the live result, the steps and the history calculate it
        assertEquals(first, calc(ms(call("rand")), seed = 42L, mode = CalculationMode.NUMERIC).numericValue, 0.0)
        assertTrue(first >= 0.0 && first < 1.0)
        // Another line, another number
        val others = (1L..50L).map { value("rand", seed = it) }.toSet()
        assertTrue("only ${others.size} different numbers in 50 seeds", others.size >= 49)
    }

    @Test
    fun everyCallInALineHasANumberOfItsOwn() {
        val sum = ms(call("rand"), OperatorNode(MathOp.PLUS), call("rand"))
        val difference = ms(call("rand"), OperatorNode(MathOp.MINUS), call("rand"))
        val a = calc(sum, seed = 7L).numericValue
        val d = calc(difference, seed = 7L).numericValue
        // rand() + rand() and rand() − rand() with the same two numbers x and y
        val x = (a + d) / 2
        val y = (a - d) / 2
        assertTrue(x in 0.0..1.0 && y in 0.0..1.0)
        assertNotEquals(x, y, 0.0)
        assertEquals(value("rand", seed = 7L), x, 1e-12)
        // The numbers are the same however often the line is calculated
        assertEquals(a, calc(sum, seed = 7L).numericValue, 0.0)
    }

    @Test
    fun uniformNumbersAreSpreadEvenly() {
        val draws = (1L..20000L).map { value("rand", seed = it * 7919L) }
        assertEquals(0.5, draws.average(), 0.01)
        assertEquals(1.0 / 12, draws.sumOf { (it - 0.5) * (it - 0.5) } / draws.size, 0.004)
        // Ten bins of 2000: a chi-square statistic with 9 degrees of freedom is below 30 with a probability of 1 − 0.0004
        val bins = IntArray(10)
        draws.forEach { bins[(it * 10).toInt()]++ }
        val chiSquare = bins.sumOf { (it - 2000.0) * (it - 2000.0) / 2000.0 }
        assertTrue("bins ${bins.toList()}, chi-square $chiSquare", chiSquare < 30.0)
        // No two seeds of a run that are next to each other give nearby numbers
        val neighbours = (1L..2000L).map { abs(value("rand", seed = it) - value("rand", seed = it + 1)) }
        assertTrue(neighbours.average() > 0.25)
    }

    @Test
    fun wholeNumbersFromAToBAreAllEquallyLikely() {
        val counts = IntArray(6)
        for (seed in 1L..12000L) {
            val res = calc(ms(call("randint", "1", "6")), seed)
            assertTrue("${res.issues}", res.isSuccess)
            val n = res.numericValue.toInt()
            assertEquals(n.toDouble(), res.numericValue, 0.0)
            counts[n - 1]++
            // A whole number, shown as one
            assertFalse(res.result.toString(), res.result.toString().contains("."))
        }
        // 2000 each: a chi-square statistic with 5 degrees of freedom is below 25 with a probability of 1 − 0.0002
        val chiSquare = counts.sumOf { (it - 2000.0) * (it - 2000.0) / 2000.0 }
        assertTrue("counts ${counts.toList()}, chi-square $chiSquare", chiSquare < 25.0)
        assertTrue(counts.all { it > 0 })
    }

    @Test
    fun theLimitsOfRandintAreIncludedAndMayBeNegative() {
        val seen = (1L..400L).map { value("randint", "-3", "3", seed = it).toInt() }.toSet()
        assertEquals((-3..3).toSet(), seen)
        assertEquals(5.0, value("randint", "5", "5"), 0.0)
        assertEquals(setOf(0, 1), (1L..100L).map { value("randint", "0", "1", seed = it).toInt() }.toSet())
        // Large ranges: still within them, and not always the same
        val big = (1L..40L).map { calc(ms(call("randint", "0", "1000000000000000000000000000000")), it) }
        assertTrue(big.all { it.isSuccess })
        assertTrue(big.map { it.result.toString() }.toSet().size > 35)
        assertTrue(big.all { it.numericValue in 0.0..1e30 })
    }

    @Test
    fun randintChecksItsArguments() {
        assertEquals(listOf(MathIssue.UNDEFINED), issues("randint", "6", "1"))
        assertEquals(listOf(MathIssue.WHOLE_NUMBERS_ONLY), issues("randint", "1.5", "3"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("randint", "1"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("randint"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("rand", "1"))
        assertEquals(listOf(MathIssue.VALUES_TOO_LARGE), calc(ms(call("randint", "0", "1" + "0".repeat(400)))).issues)
        // The number that stands for a whole number in numeric mode
        assertTrue(calc(ms(call("randint", "1", "6")), seed = 3L, mode = CalculationMode.NUMERIC).isSuccess)
    }

    @Test
    fun normalNumbers() {
        val standard = (1L..20000L).map { value("randnorm", seed = it * 104729L) }
        assertEquals(0.0, standard.average(), 0.03)
        assertEquals(1.0, standard.sumOf { it * it } / standard.size, 0.05)
        // About 68.3% are within one standard deviation, 95.4% within two
        assertEquals(0.6827, standard.count { abs(it) < 1 } / 20000.0, 0.012)
        assertEquals(0.9545, standard.count { abs(it) < 2 } / 20000.0, 0.008)
        val scaled = (1L..20000L).map { value("randnorm", "100", "15", seed = it * 104729L) }
        assertEquals(100.0, scaled.average(), 0.5)
        assertEquals(15.0, Math.sqrt(scaled.sumOf { (it - 100) * (it - 100) } / scaled.size), 0.4)
        // Scaling the standard number: the same draw
        assertEquals(100 + 15 * value("randnorm", seed = 9L), value("randnorm", "100", "15", seed = 9L), 1e-9)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("randnorm", "0", "0"))
        assertEquals(listOf(MathIssue.UNDEFINED), issues("randnorm", "0", "-1"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("randnorm", "5"))
    }

    @Test
    fun randomNumbersAreNumbersInCalculations() {
        val r = value("rand", seed = 5L)
        assertEquals(2 * r + 1, calc(ms(NumberNode("2"), call("rand"), OperatorNode(MathOp.PLUS), NumberNode("1")), seed = 5L).numericValue, 1e-12)
        assertEquals(r * r, calc(ms(call("rand"), ExponentNode(null, ms(NumberNode("2")))), seed = 5L).numericValue, 1e-12)
        // Exact mode keeps a decimal for it: no fraction with 16 digits
        val shown = calc(ms(call("rand")), seed = 5L).result.toString()
        assertTrue(shown, shown.contains(".") && !shown.contains("/"))
        // A random number is a constant for the calculus
        val derivative = calc(ms(DerivativeNode(ms(VariableNode("x"), call("rand")))), seed = 5L)
        assertTrue("${derivative.issues}", derivative.isSuccess)
        assertEquals(r, derivative.numericValue, 1e-12)
        val antiderivative = calc(ms(AntiderivativeNode(ms(call("rand")))), seed = 5L)
        assertTrue("${antiderivative.issues}", antiderivative.isSuccess)
        // Complex numbers and units have no random numbers
        assertEquals(listOf(MathIssue.INVALID_UNIT), calc(ms(FunctionNode("randint", ms(NumberNode("1"), UnitNode("m"), SeparatorNode, NumberNode("6"))))).issues)
    }

    @Test
    fun argumentsThatAreNotKnownYetKeepTheNumberOpen() {
        // randint(1, n) is only a number once n is
        val open = calc(ms(call("randint", "1", "n")))
        assertTrue("${open.issues}", open.isSuccess)
        assertTrue(open.result.toString(), open.result.toString().contains("randint"))
        val defined = mapOf("n" to ms(NumberNode("6")))
        val values = (1L..300L).map { calc(ms(call("randint", "1", "n")), it, variables = defined).numericValue.toInt() }.toSet()
        assertEquals((1..6).toSet(), values)
        // And it is the number randint(1, 6) gives for the same seed
        assertEquals(value("randint", "1", "6", seed = 77L), calc(ms(call("randint", "1", "n")), 77L, variables = defined).numericValue, 0.0)
    }

    @Test
    fun calculationsOnSeveralThreadsAtOnceGiveTheSameNumbers() {
        // The parser is shared: its count of random numbers must not be shared with it
        val input = ms(call("rand"), OperatorNode(MathOp.PLUS), call("randnorm"), OperatorNode(MathOp.PLUS), call("randint", "1", "100"))
        val expected = calc(input, seed = 123L).numericValue
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..64).map { Callable { calc(input, seed = 123L).numericValue } }).map { it.get() }
            assertTrue(results.all { it == expected })
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun theTypedNameIsFoundWhateverTheCase() {
        assertEquals(value("rand", seed = 4L), calc(ms(FunctionNode("RAND", MathString())), seed = 4L).numericValue, 0.0)
        assertEquals(value("randint", "1", "9", seed = 4L), calc(ms(FunctionNode("randInt", ms(NumberNode("1"), SeparatorNode, NumberNode("9")))), seed = 4L).numericValue, 0.0)
    }

    @Test
    fun linesOfSeveralEquationsCountTheirRandomNumbersOnce() {
        // The count restarts for every input, not for every line of it: a system has no two numbers with the same place
        val twoLines = ms(call("rand"), OperatorNode(MathOp.PLUS), VariableNode("x"), OperatorNode(MathOp.EQUALS), NumberNode("1"), LineBreakNode, call("rand"), OperatorNode(MathOp.MINUS), VariableNode("x"), OperatorNode(MathOp.EQUALS), NumberNode("0"))
        val res = calc(twoLines, seed = 8L)
        assertNotNull(res)
    }
}
