package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.type.*
import org.junit.Assert.*
import org.junit.Test

class MathTextTest {
    private fun n(s: String) = NumberNode(s)
    private fun op(op: MathOp) = OperatorNode(op)
    private fun v(name: String) = VariableNode(name)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun text(vararg nodes: MathNode) = MathText.toPlainText(ms(*nodes))

    @Test
    fun operatorsAndSigns() {
        assertEquals("12 + 3*4 - 5/6", text(n("1"), n("2"), op(MathOp.PLUS), n("3"), op(MathOp.MULTIPLY), n("4"),
            op(MathOp.MINUS), n("5"), op(MathOp.DIVIDE), n("6")))
        // A minus at the start or after an operator is a sign
        assertEquals("-2*-3", text(op(MathOp.MINUS), n("2"), op(MathOp.MULTIPLY), op(MathOp.MINUS), n("3")))
        assertEquals("5! - 1", text(n("5"), op(MathOp.FACTORIAL), op(MathOp.MINUS), n("1")))
        assertEquals("+3", text(op(MathOp.PLUS), n("3")))
    }

    @Test
    fun fractionsGetParenthesesOnlyWhenNeeded() {
        val half = FractionNode(ms(n("1")), ms(n("2")))
        assertEquals("1/2", text(half))
        assertEquals("1/2 + 1", text(half, op(MathOp.PLUS), n("1")))
        // Implicit products and powers would read differently without them
        assertEquals("3(1/2)", text(n("3"), half))
        assertEquals("(1/2)x", text(half, v("x")))
        assertEquals("(1/2)^2", text(half, ExponentNode(exponent = ms(n("2")))))
        assertEquals("(x + 1)/(2y)", text(FractionNode(ms(v("x"), op(MathOp.PLUS), n("1")), ms(n("2"), v("y")))))
    }

    @Test
    fun powersRootsAndFunctions() {
        assertEquals("x^2", text(v("x"), ExponentNode(exponent = ms(n("2")))))
        assertEquals("x^(n + 1)", text(v("x"), ExponentNode(exponent = ms(v("n"), op(MathOp.PLUS), n("1")))))
        assertEquals("x^(-1)", text(v("x"), ExponentNode(exponent = ms(op(MathOp.MINUS), n("1")))))
        assertEquals("sqrt(2)", text(RadicalNode(radicand = ms(n("2")))))
        assertEquals("(x + 1)^(1/3)", text(RadicalNode(ms(n("3")), ms(v("x"), op(MathOp.PLUS), n("1")))))
        assertEquals("sin(2x)", text(FunctionNode("sin", ms(n("2"), v("x")))))
        assertEquals("log(8, 2)", text(LogBaseNode(ms(n("2")), ms(n("8")))))
    }

    @Test
    fun numbersWithUnitsAndScientificNotation() {
        assertEquals("5 km", text(n("5"), UnitNode("km")))
        assertEquals("1.5E-3", text(n("1"), n("."), n("5"), SciExponentNode(ms(op(MathOp.MINUS), n("3")))))
        assertEquals("10^3", text(SciExponentNode(ms(n("3")))))
        assertEquals("-(3 + 1/2)", text(op(MathOp.MINUS), MixedFractionNode(ms(n("3")), ms(n("1")), ms(n("2")))))
    }

    @Test
    fun largerStructures() {
        assertEquals("[[1, 2], [3, 4]]", text(MatrixNode(2, 2, listOf(ms(n("1")), ms(n("2")), ms(n("3")), ms(n("4"))))))
        assertEquals("integral(x, x, 0, 1)", text(IntegralNode(ms(n("0")), ms(n("1")), ms(v("x")))))
        assertEquals("sum(n, n = 1, 10)", text(BigOperatorNode(BigOperator.SUM, ms(v("n"), op(MathOp.EQUALS), n("1")),
            ms(n("1"), n("0")), ms(v("n")))))
        assertEquals("x + y = 1\nx = 2", text(v("x"), op(MathOp.PLUS), v("y"), op(MathOp.EQUALS), n("1"), LineBreakNode,
            v("x"), op(MathOp.EQUALS), n("2")))
    }

    @Test
    fun readsPlainNumbers() {
        assertEquals(ms(n("1"), n("2"), n("."), n("5")), MathText.fromPlainText(" 12.5 "))
        assertEquals(ms(op(MathOp.MINUS), n("4")), MathText.fromPlainText("-4"))
        assertEquals(ms(n("1"), n("2"), n("3"), n("4")), MathText.fromPlainText("1 234"))
        assertEquals(ms(n("1"), n("2"), n("3"), n("4"), n("."), n("5")), MathText.fromPlainText("1,234.5"))
        // A single comma that does not group thousands is a decimal comma
        assertEquals(ms(n("3"), n("."), n("5")), MathText.fromPlainText("3,5"))
        assertEquals(ms(n("2"), SciExponentNode(ms(op(MathOp.MINUS), n("4")))), MathText.fromPlainText("2e-4"))
        assertNull(MathText.fromPlainText("1.2.3"))
        assertNull(MathText.fromPlainText("hello"))
        assertNull(MathText.fromPlainText(""))
    }

    @Test
    fun splitsNumbersIntoDigits() {
        val result = ms(n("125"), op(MathOp.PLUS), FractionNode(ms(n("10")), ms(n("3"))))
        assertEquals(
            ms(n("1"), n("2"), n("5"), op(MathOp.PLUS), FractionNode(ms(n("1"), n("0")), ms(n("3")))),
            MathText.splitNumbers(result)
        )
    }
}
