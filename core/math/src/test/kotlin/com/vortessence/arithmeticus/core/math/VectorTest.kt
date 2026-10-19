package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.expressions.MatrixExpression
import com.vortessence.arithmeticus.core.math.steps.StepRule
import com.vortessence.arithmeticus.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** dot, cross, norm, angle, proj and unitvec, and |v| for a vector. */
class VectorTest {

    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())

    private fun cell(text: String) = MathText.fromPlainText(text) ?: ms(VariableNode(text))

    /** A column vector, or a row vector with [row]. */
    private fun vector(vararg components: String, row: Boolean = false): MathNode =
        if (row) MatrixNode(1, components.size, components.map { cell(it) }) else MatrixNode(components.size, 1, components.map { cell(it) })

    private fun call(name: String, vararg arguments: MathNode): MathString {
        val argument = mutableListOf<MathNode>()
        arguments.forEachIndexed { i, node ->
            if (i > 0) argument.add(SeparatorNode)
            argument.add(node)
        }
        return ms(FunctionNode(name, MathString(argument)))
    }

    private fun calc(input: MathString, angle: AngleUnit = AngleUnit.RADIANS, complex: Boolean = false) =
        runBlocking { MathEngine.calculate(input, MathContext(angleUnit = angle, complexNumbers = complex)) }

    private fun number(name: String, vararg arguments: MathNode, angle: AngleUnit = AngleUnit.RADIANS): Double {
        val res = calc(call(name, *arguments), angle)
        assertTrue("$name: ${res.issues}", res.isSuccess)
        return res.numericValue
    }

    private fun components(name: String, vararg arguments: MathNode): List<Double> {
        val res = calc(call(name, *arguments))
        assertTrue("$name: ${res.issues}", res.isSuccess)
        val matrix = res.expression as MatrixExpression
        assertTrue(matrix.isVector)
        return matrix.elements.map { it.evaluateNumerically() }
    }

    private fun issues(name: String, vararg arguments: MathNode) = calc(call(name, *arguments)).issues

    @Test
    fun dotProduct() {
        assertEquals(32.0, number("dot", vector("1", "2", "3"), vector("4", "5", "6")), 0.0)
        // A row and a column of the same length are the same vector
        assertEquals(32.0, number("dot", vector("1", "2", "3", row = true), vector("4", "5", "6")), 0.0)
        assertEquals(0.0, number("dot", vector("1", "0"), vector("0", "7")), 0.0)
        assertEquals(5.0, number("dot", vector("-1", "2"), vector("3", "4")), 0.0)
        // Exact
        assertEquals("(1)/(2)", calc(call("dot", vector("1", "1"), vector("0.5", "0"))).result.toString().replace(" ", ""))
        // With variables it stays an expression
        val symbolic = calc(call("dot", vector("x", "y"), vector("1", "2")))
        assertTrue("${symbolic.issues}", symbolic.isSuccess)
        assertTrue(symbolic.result.toString(), symbolic.result.toString().contains("x") && symbolic.result.toString().contains("y"))
        assertEquals(listOf(MathIssue.DIMENSION_MISMATCH), issues("dot", vector("1", "2"), vector("1", "2", "3")))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("dot", vector("1", "2")))
        assertEquals(listOf(MathIssue.VECTOR_EXPECTED), issues("dot", MatrixNode(2, 2, listOf("1", "2", "3", "4").map { cell(it) }), vector("1", "2")))
        assertEquals(listOf(MathIssue.VECTOR_EXPECTED), calc(ms(FunctionNode("dot", ms(NumberNode("2"), SeparatorNode, NumberNode("3"))))).issues)
    }

    @Test
    fun crossProduct() {
        assertEquals(listOf(0.0, 0.0, 1.0), components("cross", vector("1", "0", "0"), vector("0", "1", "0")))
        assertEquals(listOf(-3.0, 6.0, -3.0), components("cross", vector("1", "2", "3"), vector("4", "5", "6")))
        // Anticommutative, and zero for parallel vectors
        assertEquals(listOf(3.0, -6.0, 3.0), components("cross", vector("4", "5", "6"), vector("1", "2", "3")))
        assertEquals(listOf(0.0, 0.0, 0.0), components("cross", vector("1", "2", "3"), vector("2", "4", "6")))
        // The result has the form of the first vector
        val row = calc(call("cross", vector("1", "0", "0", row = true), vector("0", "1", "0"))).expression as MatrixExpression
        assertEquals(1, row.rows)
        assertEquals(3, row.cols)
        // With fractions and variables
        assertEquals(listOf(0.0, 0.0, 0.25), components("cross", vector("0.5", "0", "0"), vector("0", "0.5", "0")))
        val symbolic = calc(call("cross", vector("a", "b", "c"), vector("1", "0", "0")))
        assertTrue("${symbolic.issues}", symbolic.isSuccess)
        assertEquals(listOf(MathIssue.CROSS_NEEDS_THREE), issues("cross", vector("1", "2"), vector("3", "4")))
        assertEquals(listOf(MathIssue.DIMENSION_MISMATCH), issues("cross", vector("1", "2", "3"), vector("1", "2")))
    }

    @Test
    fun lengthOfAVector() {
        assertEquals(5.0, number("norm", vector("3", "4")), 0.0)
        assertEquals(3.0, number("norm", vector("1", "2", "2", row = true)), 0.0)
        assertEquals("sqrt(2)", calc(call("norm", vector("1", "1"))).result.toString())
        assertEquals(Math.sqrt(14.0), number("norm", vector("1", "2", "3")), 1e-12)
        assertEquals(0.0, number("norm", vector("0", "0", "0")), 0.0)
        val symbolic = calc(call("norm", vector("x", "y")))
        assertTrue("${symbolic.issues}", symbolic.isSuccess)
        assertEquals(listOf(MathIssue.VECTOR_EXPECTED), issues("norm", MatrixNode(2, 2, listOf("1", "2", "3", "4").map { cell(it) })))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("norm", vector("1", "2"), vector("3", "4")))
    }

    @Test
    fun theBarsGiveTheLengthOfAVector() {
        // |v| for a vector, as for a number
        assertEquals(5.0, calc(ms(FunctionNode("abs", ms(vector("3", "4"))))).numericValue, 0.0)
        assertEquals(5.0, calc(ms(FunctionNode("abs", ms(vector("3", "4", row = true))))).numericValue, 0.0)
        // And in a calculation
        assertEquals(10.0, calc(ms(NumberNode("2"), FunctionNode("abs", ms(vector("3", "4"))))).numericValue, 0.0)
        // A matrix has no absolute value
        assertEquals(
            listOf(MathIssue.MATRIX_UNSUPPORTED),
            calc(ms(FunctionNode("abs", ms(MatrixNode(2, 2, listOf("1", "2", "3", "4").map { cell(it) }))))).issues
        )
        // A number as before
        assertEquals(3.0, calc(ms(FunctionNode("abs", ms(OperatorNode(MathOp.MINUS), NumberNode("3"))))).numericValue, 0.0)
    }

    @Test
    fun lengthOfAComplexVector() {
        // |(3, 4i)| = √(3² + 4²) = 5
        val withI = calc(ms(FunctionNode("norm", ms(MatrixNode(2, 1, listOf(ms(NumberNode("3")), ms(NumberNode("4"), ConstantNode("i"))))))), complex = true)
        assertTrue("${withI.issues}", withI.isSuccess)
        assertEquals(5.0, withI.numericValue, 1e-12)
    }

    @Test
    fun angleBetweenVectors() {
        assertEquals(Math.PI / 2, number("angle", vector("1", "0"), vector("0", "1")), 1e-12)
        assertEquals(Math.PI / 4, number("angle", vector("1", "0"), vector("1", "1")), 1e-12)
        assertEquals(0.0, number("angle", vector("1", "2"), vector("2", "4")), 1e-7)
        assertEquals(Math.PI, number("angle", vector("1", "2"), vector("-1", "-2")), 1e-7)
        assertEquals(Math.PI / 3, number("angle", vector("1", "0", "0"), vector("1", "1.7320508075688772", "0")), 1e-9)
        // Exact where it is: π/2 and π/4
        assertEquals("(π)/(2)", calc(call("angle", vector("1", "0"), vector("0", "1"))).result.toString().replace(" ", ""))
        assertEquals("(π)/(4)", calc(call("angle", vector("1", "0"), vector("1", "1"))).result.toString().replace(" ", ""))
        // In the unit of the angle setting
        assertEquals(90.0, number("angle", vector("1", "0"), vector("0", "1"), angle = AngleUnit.DEGREES), 1e-9)
        assertEquals(45.0, number("angle", vector("2", "0"), vector("5", "5"), angle = AngleUnit.DEGREES), 1e-9)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("angle", vector("0", "0"), vector("1", "1")))
        assertEquals(listOf(MathIssue.DIMENSION_MISMATCH), issues("angle", vector("1", "2"), vector("1", "2", "3")))
    }

    @Test
    fun projection() {
        assertEquals(listOf(3.0, 0.0), components("proj", vector("3", "4"), vector("1", "0")))
        assertEquals(listOf(1.0, 2.0, 3.0), components("proj", vector("1", "2", "3"), vector("1", "2", "3")))
        // (2, 1) onto (1, 1): (3/2)(1, 1)
        assertEquals(listOf(1.5, 1.5), components("proj", vector("2", "1"), vector("1", "1")))
        // The form of the vector it is projected onto
        val row = calc(call("proj", vector("3", "4"), vector("1", "0", row = true))).expression as MatrixExpression
        assertEquals(1, row.rows)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("proj", vector("1", "2"), vector("0", "0")))
        assertEquals(listOf(MathIssue.DIMENSION_MISMATCH), issues("proj", vector("1", "2"), vector("1", "2", "3")))
    }

    @Test
    fun unitVector() {
        assertEquals(listOf(0.6, 0.8), components("unitvec", vector("3", "4")))
        assertEquals(listOf(1.0, 0.0, 0.0), components("unitvec", vector("5", "0", "0")))
        val diagonal = components("unitvec", vector("1", "1"))
        assertEquals(Math.sqrt(0.5), diagonal[0], 1e-12)
        assertEquals(1.0, Math.sqrt(diagonal.sumOf { it * it }), 1e-12)
        assertEquals(listOf(MathIssue.UNDEFINED), issues("unitvec", vector("0", "0")))
    }

    @Test
    fun theNamesAreFoundWhateverTheCase() {
        assertEquals(5.0, calc(ms(FunctionNode("NORM", ms(vector("3", "4"))))).numericValue, 0.0)
        assertEquals(32.0, calc(ms(FunctionNode("Dot", ms(vector("1", "2", "3"), SeparatorNode, vector("4", "5", "6"))))).numericValue, 0.0)
    }

    @Test
    fun definedVectorsCanBeUsed() {
        // v := a vector, then norm(v) and dot(v, v)
        val definitions = mapOf("v" to ms(vector("3", "4")), "w" to ms(vector("1", "0")))
        fun withDefinitions(input: MathString) = runBlocking { MathEngine.calculate(input, MathContext(variables = definitions)) }
        assertEquals(5.0, withDefinitions(call("norm", VariableNode("v"))).numericValue, 0.0)
        assertEquals(3.0, withDefinitions(call("dot", VariableNode("v"), VariableNode("w"))).numericValue, 0.0)
        assertEquals(25.0, withDefinitions(call("dot", VariableNode("v"), VariableNode("v"))).numericValue, 0.0)
        // And undefined ones wait
        val open = calc(call("norm", VariableNode("q")))
        assertTrue("${open.issues}", open.isSuccess)
    }

    private fun steps(name: String, vararg arguments: MathNode) = runBlocking { MathEngine.steps(call(name, *arguments), MathContext()) }

    private fun rules(name: String, vararg arguments: MathNode): List<StepRule> =
        (steps(name, *arguments) ?: throw AssertionError("No steps for $name")).steps.mapNotNull { it.rule }

    /** The steps end with what the calculator shows. */
    private fun assertEndsWithTheResult(name: String, vararg arguments: MathNode) {
        val solution = steps(name, *arguments)!!
        assertEquals(calc(call(name, *arguments)).result.toString(), solution.result.toString())
    }

    @Test
    fun stepsOfTheVectorFunctions() {
        val a = vector("1", "2", "3")
        val b = vector("4", "5", "6")
        assertEquals(StepRule.DOT_PRODUCT, rules("dot", a, b).first())
        assertTrue(StepRule.MULTIPLY_NUMBERS in rules("dot", a, b))
        assertEndsWithTheResult("dot", a, b)
        assertEquals("32", steps("dot", a, b)!!.result.toString())
        assertEquals(StepRule.CROSS_PRODUCT, rules("cross", a, b).first())
        assertEndsWithTheResult("cross", a, b)
        assertEquals(StepRule.VECTOR_NORM, rules("norm", vector("3", "4")).first())
        assertEquals("5", steps("norm", vector("3", "4"))!!.result.toString())
        assertEndsWithTheResult("norm", vector("1", "1"))
        assertEquals(StepRule.VECTOR_ANGLE, rules("angle", vector("1", "0"), vector("1", "1")).first())
        assertEquals("(π)/(4)", steps("angle", vector("1", "0"), vector("1", "1"))!!.result.toString().replace(" ", ""))
        assertEquals(StepRule.VECTOR_PROJECTION, rules("proj", vector("2", "1"), vector("1", "1")).first())
        assertEndsWithTheResult("proj", vector("2", "1"), vector("1", "1"))
        assertEquals(StepRule.UNIT_VECTOR, rules("unitvec", vector("3", "4")).first())
        assertEndsWithTheResult("unitvec", vector("3", "4"))
        // With a row, and with variables
        assertEndsWithTheResult("dot", vector("1", "2", "3", row = true), b)
        assertEquals(listOf(StepRule.DOT_PRODUCT), rules("dot", vector("x", "y"), vector("1", "2")))
    }

    @Test
    fun theBarsOfAVectorHaveTheStepsOfItsLength() {
        val bars = ms(FunctionNode("abs", ms(vector("3", "4"))))
        val solution = runBlocking { MathEngine.steps(bars, MathContext()) }
        assertNotNull(solution)
        assertEquals(StepRule.VECTOR_NORM, solution!!.steps.mapNotNull { it.rule }.first())
        assertEquals("5", solution.result.toString())
        assertTrue(runBlocking { MathEngine.hasSteps(bars, MathContext()) })
        // The bars of a number or a matrix have none of these
        assertNull(runBlocking { MathEngine.steps(ms(FunctionNode("abs", ms(MatrixNode(2, 2, listOf("1", "2", "3", "4").map { cell(it) })))), MathContext()) })
    }

    @Test
    fun noStepsForVectorsWithUnits() {
        // The result is converted (m·N = J): steps that end on m·N would not end on the result
        val metre = { n: String -> ms(NumberNode(n), UnitNode("m")) }
        val newton = { n: String -> ms(NumberNode(n), UnitNode("N")) }
        val input = ms(FunctionNode("dot", ms(MatrixNode(2, 1, listOf(metre("1"), metre("2"))), SeparatorNode, MatrixNode(2, 1, listOf(newton("3"), newton("4"))))))
        assertEquals("11J", calc(input).result.toString().replace(" ", ""))
        assertNull(runBlocking { MathEngine.steps(input, MathContext()) })
    }

    @Test
    fun noStepsWhereThereIsAnError() {
        assertNull(steps("dot", vector("1", "2"), vector("1", "2", "3")))
        assertNull(steps("cross", vector("1", "2"), vector("3", "4")))
        assertNull(steps("angle", vector("0", "0"), vector("1", "1")))
        assertNull(steps("norm", MatrixNode(2, 2, listOf("1", "2", "3", "4").map { cell(it) })))
    }

    private fun calcNumeric(input: MathString) = runBlocking { MathEngine.calculate(input, MathContext(mode = com.vortessence.arithmeticus.core.math.context.CalculationMode.NUMERIC)) }

    @Test
    fun theAngleOfDecimalVectorsThatPointTheSameWayIsZero() {
        // Rounding puts the cosine of parallel vectors just past 1 (or next to it): no domain error, no acos(1)
        val random = kotlin.random.Random(7)
        var failures = 0
        repeat(400) {
            val x = Math.round(random.nextDouble(0.1, 9.0) * 1000) / 1000.0
            val y = Math.round(random.nextDouble(0.1, 9.0) * 1000) / 1000.0
            val scale = Math.round(random.nextDouble(0.3, 5.0) * 1000) / 1000.0
            val same = calcNumeric(call("angle", vector("$x", "$y"), vector("${x * scale}", "${y * scale}")))
            val opposite = calcNumeric(call("angle", vector("$x", "$y"), vector("${-x * scale}", "${-y * scale}")))
            if (!same.isSuccess || Math.abs(same.numericValue) > 1e-6 || !opposite.isSuccess || Math.abs(opposite.numericValue - Math.PI) > 1e-6) failures++
        }
        assertEquals(0, failures)
        assertEquals(0.0, calcNumeric(call("angle", vector("1.617", "4.22"), vector("1.617", "4.22"))).numericValue, 1e-6)
        assertEquals("0", calcNumeric(call("angle", vector("1.617", "4.22"), vector("1.617", "4.22"))).result.toString())
    }

    @Test
    fun complexVectorsHaveOneInnerProduct() {
        val i = ConstantNode("i")
        fun complexVector(vararg c: MathString) = MatrixNode(c.size, 1, c.toList())
        val a = complexVector(ms(i), ms(NumberNode("0")))
        // dot(v, v) = |v|², and a vector has the angle 0 with itself
        assertEquals(1.0, calc(call("dot", a, a), complex = true).numericValue, 1e-12)
        assertEquals(0.0, calc(call("angle", a, a), complex = true).numericValue, 1e-12)
        val v = complexVector(ms(NumberNode("3")), ms(NumberNode("4"), i))
        assertEquals(25.0, calc(call("dot", v, v), complex = true).numericValue, 1e-12)
        assertEquals(5.0, calc(call("norm", v), complex = true).numericValue, 1e-12)
        // Not "the zero vector" because 1 + i² = 0
        val projection = calc(call("proj", vector("1", "1"), complexVector(ms(NumberNode("1")), ms(i))), complex = true)
        assertTrue("${projection.issues}", projection.isSuccess)
        // Real vectors as before
        assertEquals(32.0, calc(call("dot", vector("1", "2", "3"), vector("4", "5", "6")), complex = true).numericValue, 0.0)
        // No steps for complex components
        assertNull(runBlocking { MathEngine.steps(call("dot", v, v), MathContext(complexNumbers = true)) })
    }

    @Test
    fun equationsAboutVectorFunctionsAreSolved() {
        val equals = OperatorNode(MathOp.EQUALS)
        // Orthogonal: dot((x, 2), (3, 1)) = 0 → x = −2/3
        val orthogonal = calc(ms(FunctionNode("dot", ms(vector("x", "2"), SeparatorNode, vector("3", "1"))), equals, NumberNode("0")))
        assertTrue("${orthogonal.issues}", orthogonal.isSuccess)
        assertEquals(-2.0 / 3.0, orthogonal.numericValue, 1e-12)
        // |(x, 4)| = 5 → x = ±3
        val length = calc(ms(FunctionNode("norm", ms(vector("x", "4"))), equals, NumberNode("5")))
        assertTrue("${length.issues}", length.isSuccess)
        assertTrue(length.result.toString(), length.result.toString().contains("3"))
        // And an inequality: |(x, 4)| < 5
        val inequality = calc(ms(FunctionNode("norm", ms(vector("x", "4"))), OperatorNode(MathOp.LESS), NumberNode("5")))
        assertTrue("${inequality.issues}", inequality.isSuccess)
        // A vector function that gives a vector is still no equation of numbers
        assertEquals(listOf(MathIssue.MATRIX_UNSUPPORTED), calc(ms(FunctionNode("unitvec", ms(vector("x", "4"))), equals, NumberNode("5"))).issues)
        // A mistake inside keeps its own message
        assertEquals(listOf(MathIssue.DIMENSION_MISMATCH), calc(ms(FunctionNode("dot", ms(vector("x", "4"), SeparatorNode, vector("1", "2", "3"))), equals, NumberNode("5"))).issues)
    }

    @Test
    fun aVectorFunctionWaitsOnlyForVariablesThatAreAllThereIsToWaitFor() {
        // Only variables: nothing is known yet
        assertTrue(calc(call("dot", VariableNode("p"), VariableNode("q"))).isSuccess)
        // A vector and a variable that is not defined: it cannot wait, the vector cannot become part of a number
        val definitions = mapOf("v" to ms(vector("1", "2")))
        val res = runBlocking { MathEngine.calculate(call("dot", VariableNode("v"), VariableNode("q")), MathContext(variables = definitions)) }
        assertEquals(listOf(MathIssue.VECTOR_EXPECTED), res.issues)
    }

    @Test
    fun numericEvaluationOfVectorFunctions() {
        val a = com.vortessence.arithmeticus.core.math.expressions.MatrixExpression(2, 1, listOf(com.vortessence.arithmeticus.core.math.expressions.Value(1L), com.vortessence.arithmeticus.core.math.expressions.Value(0L)))
        val b = com.vortessence.arithmeticus.core.math.expressions.MatrixExpression(2, 1, listOf(com.vortessence.arithmeticus.core.math.expressions.Value(0L), com.vortessence.arithmeticus.core.math.expressions.Value(1L)))
        val kind = com.vortessence.arithmeticus.core.math.expressions.VectorKind.ANGLE
        val angle = com.vortessence.arithmeticus.core.math.expressions.VectorFunction(kind, listOf(a, b))
        assertEquals(Math.PI / 2, angle.evaluateNumerically(emptyMap()), 1e-12)
        // In the unit of the setting, like the calculated result
        val degrees = MathContext.withContext(MathContext(angleUnit = AngleUnit.DEGREES)) { angle.evaluateNumerically(emptyMap()) }
        assertEquals(90.0, degrees, 1e-9)
        // Without the right number of arguments there is no number (and no crash)
        val dotKind = com.vortessence.arithmeticus.core.math.expressions.VectorKind.DOT
        assertTrue(com.vortessence.arithmeticus.core.math.expressions.VectorFunction(dotKind, listOf(a)).evaluateNumerically(emptyMap()).isNaN())
    }

    @Test
    fun theMatrixNoteNamesTheVariableThatIsNotAVector() {
        val definitions = mapOf("m" to ms(MatrixNode(2, 2, listOf("1", "2", "3", "4").map { cell(it) })))
        val res = runBlocking { MathEngine.calculate(call("norm", VariableNode("m")), MathContext(variables = definitions)) }
        assertEquals(listOf(MathIssue.VECTOR_EXPECTED), res.issues)
        assertTrue(res.issueNote.toString(), res.issueNote?.contains("m is a matrix") == true)
    }
}
