package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.MatrixNode
import com.vortessence.calculator.core.math.type.MathString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Row reduction and eigenvalues, the forms offered for a matrix. */
class MatrixEigenTest {
    private fun steps(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun calc(text: String, context: MathContext = MathContext()) = runBlocking { MathEngine.calculate(TestInput.parse(text), context) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    /** The entries of every matrix in a line, matrix by matrix, e.g. [[1, 0, 0, 1]] for the identity. */
    private fun matrices(line: MathString): List<List<String>> =
        line.nodes.filterIsInstance<MatrixNode>().map { m -> m.elements.map { clean(it.toString()) } }

    private fun rules(s: Solution) = s.steps.map { it.rule }

    @Test
    fun rowReduction() {
        val full = steps("rref(M[1 2 3/4 5 6/7 8 10])")!!
        // The identity, with the rank in the last step
        assertEquals(listOf("1", "0", "0", "0", "1", "0", "0", "0", "1"), matrices(full.result).single())
        assertTrue(rules(full).containsAll(listOf(StepRule.ROW_SCALE, StepRule.ROW_ADD)))
        assertEquals(listOf("rank = 3"), full.steps.last().notes.map { clean(it.toString()) }.filter { it.startsWith("rank") })
        // A negative factor is added: R₂ → R₂ + 2·R₁
        val rows = steps("rref(M[1 2/-2 3])")!!
        assertTrue(rows.steps.any { n -> n.notes.any { clean(it.toString()).contains("R₂ → R₂ + 2·R₁") } })
        // Rank 1: one row of zeros, a pivot only in the first column
        val dependent = steps("rref(M[1 2 3/2 4 6])")!!
        assertEquals(listOf("1", "2", "3", "0", "0", "0"), matrices(dependent.result).single())
        assertTrue(dependent.steps.last().notes.any { clean(it.toString()) == "rank = 1" })
        // A swap for a zero in the pivot place
        assertTrue(rules(steps("rref(M[0 1/1 0])")!!).contains(StepRule.ROW_SWAP))
        // Already reduced, or not a matrix of numbers: nothing to show
        assertNull(steps("rref(M[1 0/0 1])"))
        assertNull(steps("rref(M[a b/c d])"))
    }

    @Test
    fun eigenvalues2x2() {
        val s = steps("eig(M[2 1/1 2])")!!
        assertEquals(StepRule.EIGEN_CHARACTERISTIC, s.steps[1].rule)
        assertEquals(StepRule.DETERMINANT_2X2, s.steps[2].rule)
        assertTrue(rules(s).contains(StepRule.QUADRATIC_FORMULA))
        // λ = 1 with (−1, 1), λ = 3 with (1, 1): the free variable is 1
        assertEquals(listOf(listOf("−1", "1"), listOf("1", "1")), matrices(s.result))
        assertEquals(2, s.steps.count { it.rule == StepRule.EIGEN_VECTOR })
        assertEquals(StepRule.EIGEN_RESULT, s.steps.last().rule)
        assertEquals(2, matrices(s.result).size)
    }

    @Test
    fun eigenvalues3x3WithALargeRoot() {
        // det(A − λI) = −λ³ + 14λ² − 35λ + 22 = −(λ − 1)(λ − 2)(λ − 11)
        val s = steps("eig(M[2 0 0/0 3 4/0 4 9])")!!
        assertEquals(3, s.steps.count { it.rule == StepRule.EIGEN_VECTOR })
        val vectors = matrices(s.result)
        assertEquals(listOf(listOf("0", "−2", "1"), listOf("1", "0", "0"), listOf("0", "1", "2")), vectors)
        assertTrue(rules(s).contains(StepRule.FACTOR_BY_ROOT))
    }

    @Test
    fun repeatedAndIrrationalEigenvalues() {
        // The identity-like 2I: both unit vectors
        val twice = steps("eig(M[2 0/0 2])")!!
        assertEquals(listOf(listOf("1", "0"), listOf("0", "1")), matrices(twice.result))
        // 1 ± √5 over 2: the eigenvalues only (no steps for vectors with roots in them)
        val golden = steps("eig(M[1 1/1 0])")!!
        assertEquals(0, golden.steps.count { it.rule == StepRule.EIGEN_VECTOR })
        assertEquals(2, clean(golden.result.toString()).split("λ").size - 1)
        // A rotation has none in the reals: no steps (and the calculator says so)
        assertNull(steps("eig(M[0 -1/1 0])"))
        // Not square, or not numbers
        assertNull(steps("eig(M[1 2 3/4 5 6])"))
        assertNull(steps("eig(M[a 1/1 2])"))
    }

    @Test
    fun theFunctionsAreResultsOfTheirOwn() {
        // The result is shown right away, the steps are the way to it
        val rref = calc("rref(M[1 2 3/4 5 6/7 8 10])")
        assertTrue(rref.isSuccess)
        assertEquals(listOf("1", "0", "0", "0", "1", "0", "0", "0", "1"), matrices(rref.result).single())
        // Already reduced: the result, without steps
        assertTrue(calc("rref(M[1 0/0 1])").isSuccess)
        assertNull(steps("rref(M[1 0/0 1])"))
        val eig = calc("eig(M[2 1/1 2])")
        assertTrue(eig.isSuccess)
        assertEquals(listOf(listOf("−1", "1"), listOf("1", "1")), matrices(eig.result))
        assertEquals("λ", clean(eig.result.toString()).take(1))
        // Typed from a stored matrix, and inside a matrix product
        assertTrue(calc("rref(M[1 2/3 4] M[0 1/1 0])").isSuccess)
        // What cannot be done says why
        assertEquals("Needs a matrix of numbers", calc("rref(M[a b/c d])").issues.first().userMessage)
        assertEquals("Needs a matrix", calc("eig(5)").issues.first().userMessage)
        assertEquals("Matrix must be square", calc("eig(M[1 2 3/4 5 6])").issues.first().userMessage)
        assertEquals("No real eigenvalues", calc("eig(M[0 -1/1 0])").issues.first().userMessage)
        // With complex numbers the eigenvalues of a rotation are ±i (no vectors)
        val complex = calc("eig(M[0 -1/1 0])", MathContext().also { it.complexNumbers = true })
        assertTrue(complex.isSuccess)
        assertEquals(0, matrices(complex.result).size)
        // 1×1
        assertEquals(listOf(listOf("1")), matrices(calc("eig(M[5])").result))
    }

    @Test
    fun polynomialRootsBeyondTheNumericSearch() {
        // x³ − 14x² + 35x − 22 = (x − 1)(x − 2)(x − 11): the numeric search around 0 found only two of them
        val result = runBlocking { MathEngine.calculate(TestInput.parse("x^3-14x^2+35x-22=0"), MathContext()) }
        assertEquals("x = 1, x = 2, x = 11", clean(result.result.toString()))
        // Irrational ones after a rational one: (x − 1)(x² − 2x − 4)
        val surds = runBlocking { MathEngine.calculate(TestInput.parse("x^3-3x^2-2x+4=0"), MathContext()) }
        assertEquals("x = 1 −sqrt(5), x = 1, x = 1 + sqrt(5)", clean(surds.result.toString()))
    }

    @Test
    fun entriesTypedAsExpressionsHaveStepsToo() {
        // 1 + 1 is 2: the steps start from the matrix as typed and work with the numbers
        assertNotNull(steps("rref(M[1+1 2 3/4 5 6])"))
        assertEquals(2, matrices(calc("eig(M[1+1 1/1 2])").result).size)
        assertNotNull(steps("eig(M[1+1 1/1 2])"))
    }

    @Test
    fun bigAndRepeatedEigenvalues() {
        // (λ − 1)³(λ − 5): one eigenvalue with three eigenvectors, one with a single one
        val square = calc("eig(M[2 1 1 1/1 2 1 1/1 1 2 1/1 1 1 2])")
        assertTrue(square.isSuccess)
        // λ = 1 with three vectors, λ = 5 with one
        assertEquals(4, matrices(square.result).size)
        assertNotNull(steps("eig(M[2 1 1 1/1 2 1 1/1 1 2 1/1 1 1 2])"))
        // 5×5: the result is there, the steps are not (too many)
        val five = calc("eig(M[1 0 0 0 0/0 2 0 0 0/0 0 3 0 0/0 0 0 4 0/0 0 0 0 5])")
        assertTrue(five.isSuccess)
        assertEquals(5, matrices(five.result).size)
        assertNull(steps("eig(M[1 0 0 0 0/0 2 0 0 0/0 0 3 0 0/0 0 0 4 0/0 0 0 0 5])"))
        // A zero eigenvalue
        assertEquals(listOf(listOf("−1", "1"), listOf("1", "1")), matrices(calc("eig(M[1 1/1 1])").result))
    }

    @Test
    fun eigIsAResultOnItsOwn() {
        // Not a number to calculate with, nor to store, nor to reuse as Ans
        assertEquals("eig can only be used on its own", calc("2*eig(M[2 1/1 2])").issues.first().userMessage)
        assertFalse(calc("det(eig(M[2 1/1 2]))").isSuccess)
        assertFalse(calc("eig(M[2 1/1 2]) + 1").isSuccess)
        assertNull(calc("eig(M[2 1/1 2])").answer)
        // rref gives a matrix, which can be used
        assertTrue(calc("rref(M[1 2/3 4]) * M[1 2/3 4]").isSuccess)
        assertNotNull(calc("rref(M[1 2/3 4])").answer)
        // From a stored matrix
        val stored = MathContext(variables = mapOf("m" to TestInput.parse("M[2 1/1 2]")))
        assertEquals(2, matrices(runBlocking { MathEngine.calculate(TestInput.parse("eig(m)"), stored) }.result).size)
    }
}
