package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.EquationSystem
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinearSystemTest {

    /**
     * Input from text such as "-2x+y=-3\n3x+2y=8": digits, ".", the operators + − × =,
     * single-letter variables and line breaks, each character one node.
     */
    private fun input(text: String): MathString = MathString(text.map { c ->
        when (c) {
            '\n' -> LineBreakNode
            '+' -> OperatorNode(MathOp.PLUS)
            '-' -> OperatorNode(MathOp.MINUS)
            '*' -> OperatorNode(MathOp.MULTIPLY)
            '=' -> OperatorNode(MathOp.EQUALS)
            else -> if (c.isLetter()) VariableNode(c.toString()) else NumberNode(c.toString())
        }
    })

    private fun calc(text: String): CalculationResult = runBlocking {
        MathEngine.calculate(input(text), MathContext())
    }

    private fun assertSolution(text: String, expected: String) {
        val res = calc(text)
        assertTrue("Expected success but got ${res.issues}", res.isSuccess)
        assertEquals(expected, res.result.toString())
    }

    private fun assertIssue(text: String, issue: MathIssue) {
        val res = calc(text)
        assertFalse(res.isSuccess)
        assertEquals(listOf(issue), res.issues)
    }

    @Test
    fun twoByTwo() {
        assertSolution("-2x+y=-3\n3x+2y=8", "x = 2, y = 1")
        assertTrue(calc("-2x+y=-3\n3x+2y=8").expression is EquationSystem)
    }

    @Test
    fun exactFractions() {
        // MathString.toString() spaces every operator, so −1/2 reads " − (1)/(2)"
        assertSolution("x+y=1\nx-y=2", "x = (3)/(2), y =  − (1)/(2)")
    }

    @Test
    fun threeByThreeNeedingRowSwaps() {
        // The first equation has no x, so elimination has to swap rows
        assertSolution("y+z=5\nx+y+z=6\n2x+3z=11", "x = 1, y = 2, z = 3")
    }

    @Test
    fun variablesOnBothSidesAndInAnyOrder() {
        assertSolution("y=2x-3\n3x=8-2y", "x = 2, y = 1")
    }

    @Test
    fun otherVariableNamesAreSolvedAlphabetically() {
        assertSolution("a+b=3\na-b=1", "a = 2, b = 1")
    }

    @Test
    fun emptyLinesAreIgnored() {
        assertSolution("x+y=3\n\nx-y=1\n", "x = 2, y = 1")
        // A single equation followed by a new line is just that equation
        assertSolution("2x=4\n", "x = 2")
    }

    @Test
    fun overdeterminedButConsistent() {
        assertSolution("x+y=3\nx-y=1\n2x+2y=6", "x = 2, y = 1")
    }

    @Test
    fun underdeterminedIsSolvedInTermsOfTheFreeVariable() {
        assertSolution("x+y+z=6\ny-z=1", "x = 5 − 2z, y = 1 + z")
    }

    @Test
    fun inconsistentSystem() {
        assertIssue("x+y=1\nx+y=2", MathIssue.CONTRADICTION)
    }

    @Test
    fun identitiesAreTrue() {
        assertSolution("x+y=y+x\n2x=x+x", "true")
        assertSolution("1=1\n2=3", "false")
    }

    @Test
    fun systemsWithoutAnEquationLinearInAVariableAreNotSolved() {
        // (xy = 2 with x + y = 3 is solved by substitution, see NonlinearSystemTest)
        assertIssue("x*x+y*y=25\nx*x*x+y*y*y=10", MathIssue.CANT_SOLVE_GENERIC)
    }

    @Test
    fun lineWithoutEqualsIsIncomplete() {
        assertIssue("x+y=3\nx-y", MathIssue.SYNTAX_ERROR)
    }
}
