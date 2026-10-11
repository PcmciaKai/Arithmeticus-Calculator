package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MatrixTest {

    private fun n(s: String) = NumberNode(s)
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun pow(base: MathNode, vararg exponent: MathNode) = ExponentNode(ms(base), ms(*exponent))

    /** A matrix from rows such as "1 -2 x": numbers, negative numbers or variables. */
    private fun mat(vararg rows: String): MatrixNode {
        val cells = rows.map { it.trim().split(Regex("\\s+")) }
        val elements = cells.flatten().map { cell ->
            when {
                cell.startsWith("-") -> ms(op(MathOp.MINUS), n(cell.drop(1)))
                cell.first().isLetter() -> ms(VariableNode(cell))
                else -> ms(n(cell))
            }
        }
        return MatrixNode(rows.size, cells.first().size, elements)
    }

    private fun calc(vararg nodes: MathNode): CalculationResult = runBlocking {
        MathEngine.calculate(ms(*nodes), MathContext())
    }

    /** Asserts a successful matrix result with the given entries (integers or "p/q"), row by row. */
    private fun assertMatrix(res: CalculationResult, rows: Int, cols: Int, vararg entries: String) {
        assertTrue("Expected success but got ${res.issues}", res.isSuccess)
        val m = res.expression as MatrixExpression
        assertEquals(rows, m.rows)
        assertEquals(cols, m.cols)
        val expected = entries.map { e ->
            val parts = e.split("/")
            if (parts.size == 2) Value(parts[0].toLong(), parts[1].toLong()) else Value(e.toLong())
        }
        assertEquals(expected, m.elements)
    }

    private fun assertIssue(res: CalculationResult, issue: MathIssue) {
        assertFalse(res.isSuccess)
        assertEquals(listOf(issue), res.issues)
    }

    @Test
    fun additionAndSubtraction() {
        assertMatrix(calc(mat("1 2", "3 4"), op(MathOp.PLUS), mat("5 6", "7 8")), 2, 2, "6", "8", "10", "12")
        assertMatrix(calc(mat("1 2", "3 4"), op(MathOp.MINUS), mat("1 2", "3 4")), 2, 2, "0", "0", "0", "0")
        assertIssue(calc(mat("1 2", "3 4"), op(MathOp.PLUS), mat("1 2 3")), MathIssue.DIMENSION_MISMATCH)
        assertIssue(calc(mat("1 2", "3 4"), op(MathOp.PLUS), n("1")), MathIssue.MATRIX_AND_SCALAR)
    }

    @Test
    fun multiplicationKeepsTheOrderOfMatrices() {
        val a = mat("1 2", "3 4")
        val b = mat("0 1", "1 0")
        assertMatrix(calc(a, op(MathOp.MULTIPLY), b), 2, 2, "2", "1", "4", "3")
        assertMatrix(calc(b, op(MathOp.MULTIPLY), a), 2, 2, "3", "4", "1", "2")
        // A·B·A must not be merged into A²·B
        assertMatrix(calc(a, op(MathOp.MULTIPLY), b, op(MathOp.MULTIPLY), a), 2, 2, "5", "8", "13", "20")
        // Implicit multiplication of adjacent matrices
        assertMatrix(calc(a, b), 2, 2, "2", "1", "4", "3")
    }

    @Test
    fun multiplicationOfNonSquareMatrices() {
        // (2×3)(3×1) = 2×1
        assertMatrix(calc(mat("1 2 3", "4 5 6"), op(MathOp.MULTIPLY), mat("1", "0", "-1")), 2, 1, "-2", "-2")
        assertIssue(calc(mat("1 2 3"), op(MathOp.MULTIPLY), mat("1 2 3")), MathIssue.DIMENSION_MISMATCH)
    }

    @Test
    fun scalarMultiplicationAndDivision() {
        assertMatrix(calc(n("2"), op(MathOp.MULTIPLY), mat("1 2", "3 4")), 2, 2, "2", "4", "6", "8")
        assertMatrix(calc(mat("1 2", "3 4"), op(MathOp.MULTIPLY), n("3")), 2, 2, "3", "6", "9", "12")
        assertMatrix(calc(op(MathOp.MINUS), mat("1 -2")), 1, 2, "-1", "2")
        assertMatrix(
            calc(FractionNode(ms(mat("1 2", "3 4")), ms(n("2")))),
            2, 2, "1/2", "1", "3/2", "2"
        )
    }

    @Test
    fun inverseAndDivision() {
        val a = mat("1 2", "3 4")
        assertMatrix(calc(pow(a, op(MathOp.MINUS), n("1"))), 2, 2, "-2", "1", "3/2", "-1/2")
        // A / A = I
        assertMatrix(calc(FractionNode(ms(a), ms(a.copy()))), 2, 2, "1", "0", "0", "1")
        // 2 / A = 2·A⁻¹
        assertMatrix(calc(FractionNode(ms(n("2")), ms(a))), 2, 2, "-4", "2", "3", "-1")
        assertIssue(calc(pow(mat("1 2", "2 4"), op(MathOp.MINUS), n("1"))), MathIssue.SINGULAR_MATRIX)
        assertIssue(calc(pow(mat("1 2 3"), op(MathOp.MINUS), n("1"))), MathIssue.MATRIX_NOT_SQUARE)
    }

    @Test
    fun inverseOf5x5() {
        val a = mat("2 0 0 0 1", "0 3 0 0 0", "0 0 1 4 0", "0 0 0 1 0", "1 0 0 0 1")
        val res = calc(a, op(MathOp.MULTIPLY), pow(a.copy(), op(MathOp.MINUS), n("1")))
        assertMatrix(res, 5, 5, *Array(25) { if (it / 5 == it % 5) "1" else "0" })
    }

    @Test
    fun inverseNeedsRowSwaps() {
        // Zero in the top-left corner
        assertMatrix(calc(pow(mat("0 1", "2 0"), op(MathOp.MINUS), n("1"))), 2, 2, "0", "1/2", "1", "0")
    }

    @Test
    fun symbolicInverse() {
        val res = calc(pow(mat("x 0", "0 2"), op(MathOp.MINUS), n("1")))
        assertTrue(res.isSuccess)
        val m = res.expression as MatrixExpression
        assertEquals(Power(Variable("x"), Value.MINUS_ONE), m.elements[0])
        assertEquals(Value.HALF, m.elements[3])
    }

    @Test
    fun powers() {
        val a = mat("1 1", "0 1")
        assertMatrix(calc(pow(a, n("5"))), 2, 2, "1", "5", "0", "1")
        assertMatrix(calc(pow(a, n("0"))), 2, 2, "1", "0", "0", "1")
        assertMatrix(calc(pow(a, op(MathOp.MINUS), n("2"))), 2, 2, "1", "-2", "0", "1")
        assertIssue(calc(pow(mat("1 2"), n("2"))), MathIssue.MATRIX_NOT_SQUARE)
        assertIssue(calc(pow(a, n("1"), op(MathOp.DIVIDE), n("2"))), MathIssue.MATRIX_UNSUPPORTED)
    }

    @Test
    fun transpose() {
        assertMatrix(calc(pow(mat("1 2 3", "4 5 6"), VariableNode("T"))), 3, 2, "1", "4", "2", "5", "3", "6")
        // T is only a transpose for matrices: x^T stays an ordinary power
        assertTrue(calc(pow(VariableNode("x"), VariableNode("T"))).isSuccess)
    }

    @Test
    fun determinant() {
        fun det(m: MatrixNode) = calc(FunctionNode("det", ms(m)))
        assertEquals(Value(-2L), det(mat("1 2", "3 4")).expression)
        assertEquals(Value(6L), det(mat("2 0 1", "1 3 2", "1 1 2")).expression)
        assertEquals(Value(24L), det(mat("2 0 0 0 0", "5 1 0 0 0", "7 3 3 0 0", "1 1 1 4 0", "9 9 9 9 1")).expression)
        assertEquals(Value.ZERO, det(mat("1 2", "2 4")).expression)
        assertIssue(det(mat("1 2 3")), MathIssue.MATRIX_NOT_SQUARE)
        // det(A) is a number and can be used in further calculations
        assertEquals(Value(-4L), calc(n("2"), FunctionNode("det", ms(mat("1 2", "3 4")))).expression)
    }

    @Test
    fun symbolicDeterminant() {
        val res = calc(FunctionNode("det", ms(mat("x 1", "1 x"))))
        assertTrue(res.isSuccess)
        assertEquals(Additions(Power(Variable("x"), Value.TWO), Value.MINUS_ONE), res.expression)
    }

    @Test
    fun functionsOfMatricesAreRejected() {
        assertIssue(calc(FunctionNode("sin", ms(mat("1 2")))), MathIssue.MATRIX_UNSUPPORTED)
        assertIssue(calc(FunctionNode("ln", ms(mat("1 2")))), MathIssue.MATRIX_UNSUPPORTED)
        assertIssue(calc(mat("1 2"), op(MathOp.EQUALS), mat("1 2")), MathIssue.MATRIX_UNSUPPORTED)
        assertFalse(calc(mat("1 2"), op(MathOp.FACTORIAL)).isSuccess)
    }

    @Test
    fun resultCanBeReusedAsInput() {
        val res = calc(mat("1 2", "3 4"), op(MathOp.MULTIPLY), n("2"))
        val answer = res.answer!!
        assertTrue(answer.nodeAt(0) is MatrixNode)
        assertMatrix(calc(*answer.nodes.toTypedArray()), 2, 2, "2", "4", "6", "8")
    }
}
