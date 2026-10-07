package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.*
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
        return res.numericValue!!
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
        assertEquals(3.142, calc(pi).numericValue!!, 0.0)
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
}
