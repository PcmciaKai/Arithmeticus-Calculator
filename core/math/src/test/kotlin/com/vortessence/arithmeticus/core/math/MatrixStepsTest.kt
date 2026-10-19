package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.steps.Solution
import com.vortessence.arithmeticus.core.math.steps.StepRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MatrixStepsTest {
    private fun solution(text: String): Solution? = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }

    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun rules(text: String) = solution(text)?.steps?.drop(1)?.map { it.rule }

    /** The entries of a result that is a matrix, e.g. [−2, 1, (3)/(2), −(1)/(2)]. */
    private fun entries(result: com.vortessence.arithmeticus.core.math.type.MathString): List<String> =
        (result.nodes.single() as com.vortessence.arithmeticus.core.math.type.MatrixNode).elements.map { clean(it.toString()) }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace("− ", "−").trim()

    @Test
    fun determinants() {
        // 2×2: ad − bc
        val two = solution("det(M[1 2/3 4])")!!
        assertEquals(StepRule.DETERMINANT_2X2, two.steps[1].rule)
        assertEquals("−2", clean(two.result.toString()))
        // 3×3: along the row with a zero, then 2×2 each; the zero entry's term is left out
        val three = solution("det(M[2 0 1/1 3 2/0 1 4])")!!
        assertEquals(StepRule.DETERMINANT_COFACTOR, three.steps[1].rule)
        assertEquals(listOf("expanded along row 1"), three.steps[1].notes.map { clean(it.toString()) })
        assertEquals(2, three.steps.count { it.rule == StepRule.DETERMINANT_2X2 })
        assertEquals("21", clean(three.result.toString()))
        // 4×4, with letters in a 2×2
        assertEquals("1", clean(solution("det(M[1 0 0 0/0 1 0 0/0 0 1 0/0 0 0 1])")!!.result.toString()))
        assertEquals("a·d − b·c", clean(solution("det(M[a b/c d])")!!.result.toString()).replace("ad −bc", "a·d − b·c").replace("ad − bc", "a·d − b·c"))
    }

    @Test
    fun inverses() {
        // 2×2: the formula, with the determinant calculated
        val two = solution("M[1 2/3 4]^-1")!!
        assertEquals(StepRule.INVERSE_2X2, two.steps[1].rule)
        assertTrue(two.steps.any { it.rule == StepRule.MATRIX_SCALAR })
        assertEquals(listOf("−2", "1", "(3)/(2)", "−(1)/(2)"), entries(two.result))
        // 3×3: Gauss-Jordan on (A | I), ending with the right half
        val three = solution("M[2 1 1/1 3 2/1 0 0]^-1")!!
        assertEquals(StepRule.INVERSE_AUGMENT, three.steps[1].rule)
        assertEquals(StepRule.INVERSE_RESULT, three.steps.last().rule)
        assertTrue(three.steps.any { it.rule == StepRule.ROW_SCALE })
        assertTrue(three.steps.any { it.rule == StepRule.ROW_ADD })
        assertTrue(three.steps.first { it.rule == StepRule.ROW_SCALE }.notes.single().toString().contains("R"))
        // A zero in the pivot: the rows are swapped
        assertTrue(rules("M[0 1 0/1 0 0/0 0 1]^-1")!!.contains(StepRule.ROW_SWAP))
        // Not invertible: no steps (and the calculator says so)
        assertNull(solution("M[1 2/2 4]^-1"))
        assertFalse(calc("M[1 2/2 4]^-1").isSuccess)
    }

    @Test
    fun productsSumsAndPowers() {
        assertEquals(listOf(StepRule.MATRIX_MULTIPLY), rules("M[1 2/3 4] M[0 1/1 0]")!!.take(1))
        assertEquals(StepRule.MATRIX_ADD, rules("M[1 2/3 4] + M[5 6/7 8]")!!.first())
        assertEquals(StepRule.MATRIX_SUBTRACT, rules("M[1 2/3 4] - M[5 6/7 8]")!!.first())
        assertEquals(StepRule.MATRIX_SCALAR, rules("3 M[1 2/3 4]")!!.first())
        assertEquals(listOf(StepRule.MATRIX_TRANSPOSE), rules("M[1 2/3 4]^T"))
        assertEquals(listOf(StepRule.MATRIX_POWER, StepRule.MATRIX_MULTIPLY), rules("M[1 2/3 4]^2")!!.take(2))
        // One operation after the other: A·B + C, det(A·B), A⁻¹·A
        val mixed = solution("M[1 2/3 4] M[0 1/1 0] + M[1 1/1 1]")!!
        assertEquals(listOf(StepRule.MATRIX_MULTIPLY), mixed.steps.map { it.rule }.filter { it == StepRule.MATRIX_MULTIPLY })
        assertEquals("2", clean(solution("det(M[1 2/3 4] M[0 1/1 0])")!!.result.toString()))
        assertNotNull(solution("M[1 2/3 4]^-1 M[1 2/3 4]"))
    }

    @Test
    fun theStepsEndAtTheCalculatedResult() {
        for (text in listOf("M[1 2/3 4]^-1", "M[2 1 1/1 3 2/1 0 0]^-1", "M[1 2/3 4] M[0 1/1 0] + M[1 1/1 1]", "M[1 2/3 4]^T - 2 M[1 0/0 1]", "M[1 2/3 4]^3", "M[1 2 3/4 5 6/7 8 10] M[1 0 0/0 1 0/0 0 1]")) {
            val steps = solution(text)
            assertNotNull(text, steps)
            val calculated = calc(text)
            assertEquals(text, entries(calculated.result), entries(steps!!.result))
        }
    }

    @Test
    fun noStepsWhereTheyAreNotWorkedOut() {
        // A matrix by itself, mismatched sizes, or something that is not matrix algebra
        assertNull(solution("M[1 2/3 4]"))
        assertNull(solution("M[1 2/3 4] M[1 2 3]"))
        assertNull(solution("sin(M[1 2/3 4])"))
    }
}
