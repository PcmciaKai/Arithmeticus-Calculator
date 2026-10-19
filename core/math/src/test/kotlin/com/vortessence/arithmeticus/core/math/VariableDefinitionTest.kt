package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VariableDefinitionTest {

    private fun n(s: String) = NumberNode(s)
    private fun op(o: MathOp) = OperatorNode(o)
    private fun v(name: String) = VariableNode(name)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun square(base: MathNode) = ExponentNode(ms(base), ms(n("2")))
    private val define = op(MathOp.DEFINE)

    private fun calc(variables: Map<String, MathString>, vararg nodes: MathNode): CalculationResult = runBlocking {
        MathEngine.calculate(ms(*nodes), MathContext(variables = variables))
    }

    /** Defines a variable like the history does: by name, with the answer of the definition. */
    private fun defineVar(variables: Map<String, MathString>, vararg nodes: MathNode): Map<String, MathString> {
        val res = calc(variables, *nodes)
        assertTrue("Expected success but got ${res.issues}", res.isSuccess)
        return variables + (res.definedVariable!! to res.answer!!)
    }

    @Test
    fun testDefinitionShowsVariableAndValue() {
        val res = calc(emptyMap(), v("a"), define, n("2"), square(v("x")))
        assertTrue(res.isSuccess)
        assertEquals("a", res.definedVariable)
        assertEquals("a = 2(x)^(2)", res.result.toString())
    }

    @Test
    fun testDefinedVariableIsUsedInCalculations() {
        val vars = defineVar(emptyMap(), v("a"), define, n("5"))
        assertEquals(15.0, calc(vars, n("3"), v("a")).numericValue, 1e-12)
        // Without the definition, a is just a symbol
        assertEquals("3a", calc(emptyMap(), n("3"), v("a")).result.toString())
    }

    @Test
    fun testDefinitionWithFreeVariableActsLikeExpression() {
        // a := 2x², then a = 8 gives x = ±2
        val vars = defineVar(emptyMap(), v("a"), define, n("2"), square(v("x")))
        val res = calc(vars, v("a"), op(MathOp.EQUALS), n("8"))
        assertTrue(res.isSuccess)
        assertEquals("x = 2, x =  − 2", res.result.toString())

        // Defining x later gives a a value as well
        val withX = defineVar(vars, v("x"), define, n("3"))
        assertEquals(18.0, calc(withX, v("a")).numericValue, 1e-12)
    }

    @Test
    fun testRedefinitionUsesPreviousValue() {
        val vars = defineVar(emptyMap(), v("f"), define, n("3"))
        val incremented = defineVar(vars, v("f"), define, v("f"), op(MathOp.PLUS), n("1"))
        assertEquals("4", incremented.getValue("f").toString())
    }

    @Test
    fun testSelfReferenceDoesNotRecurseForever() {
        // x stands for 2x: the x inside its own value stays a plain x
        val res = calc(mapOf("x" to ms(n("2"), v("x"))), v("x"))
        assertTrue(res.isSuccess)
        assertEquals("2x", res.result.toString())
    }

    @Test
    fun testMatrixCanBeDefined() {
        val matrix = MatrixNode(2, 2, listOf(ms(n("1")), ms(n("2")), ms(n("3")), ms(n("4"))))
        val vars = defineVar(emptyMap(), v("m"), define, matrix)
        val det = calc(vars, FunctionNode("det", ms(v("m"))))
        assertEquals(-2.0, det.numericValue, 1e-12)
    }

    @Test
    fun testIntegrationVariableIsNotReplaced() {
        // With x := 5, ∫₀¹ x dx is still 1/2
        val integral = IntegralNode(ms(n("0")), ms(n("1")), ms(v("x")), "x")
        val res = calc(mapOf("x" to ms(n("5"))), integral)
        assertEquals(0.5, res.numericValue, 1e-9)
    }

    @Test
    fun testInvalidDefinitionsAreSyntaxErrors() {
        // Missing value
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(emptyMap(), v("a"), define).issues)
        // ":=" not right after a single variable
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(emptyMap(), n("2"), v("a"), define, n("3")).issues)
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(emptyMap(), v("a"), define, v("f"), define, n("3")).issues)
        // A variable cannot stand for an equation
        assertEquals(listOf(MathIssue.SYNTAX_ERROR), calc(emptyMap(), v("a"), define, v("x"), op(MathOp.EQUALS), n("3")).issues)
    }

    @Test
    fun aMatrixVariableUsedWithANumberSaysWhichVariableIsTheMatrix() {
        val matrix = MatrixNode(2, 2, listOf(ms(n("1")), ms(n("2")), ms(n("3")), ms(n("4"))))
        val vars = defineVar(emptyMap(), v("m"), define, matrix)
        // 2 + m: a matrix and a number
        val sum = calc(vars, n("2"), op(MathOp.PLUS), v("m"))
        assertEquals(listOf(MathIssue.MATRIX_AND_SCALAR), sum.issues)
        assertEquals("m is a matrix. A matrix and a number cannot be added", sum.issueNote)
        // sin(m), m!, a comparison
        assertEquals("m is a matrix. Not defined for matrices", calc(vars, FunctionNode("sin", ms(v("m")))).issueNote)
        assertEquals("m is a matrix. Not defined for matrices", calc(vars, v("m"), op(MathOp.FACTORIAL)).issueNote)
        assertEquals("m is a matrix. Not defined for matrices", calc(vars, v("m"), op(MathOp.LESS), n("2")).issueNote)
        // Two of them, and what works stays as it is
        val both = defineVar(vars, v("n"), define, MatrixNode(3, 3, List(9) { ms(n("1")) }))
        assertEquals("m and n are matrices. Matrix dimensions do not match", calc(both, v("m"), op(MathOp.PLUS), v("n")).issueNote)
        assertTrue(calc(vars, v("m"), v("m")).isSuccess)
        // No note for an error that has nothing to do with matrices, or without matrix variables
        assertNull(calc(vars, n("1"), op(MathOp.DIVIDE), n("0")).issueNote)
        assertNull(calc(emptyMap(), n("2"), op(MathOp.PLUS), MatrixNode(1, 1, listOf(ms(n("1"))))).issueNote)
    }
}
