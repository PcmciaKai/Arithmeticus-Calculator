package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.steps.Alternatives
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.DerivativeNode
import com.vortessence.calculator.core.math.type.FunctionNode
import com.vortessence.calculator.core.math.type.MathNode
import com.vortessence.calculator.core.math.type.MathString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.cos

/** dsolve: first order (linear, separable) and second order with constant coefficients, with and without initial conditions. */
class DifferentialEquationsTest {

    private fun calc(text: String) = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }

    private fun shown(text: String): String {
        val result = calc(text)
        assertTrue("$text: ${result.issues}", result.isSuccess)
        return result.result.toString()
    }

    private fun issues(text: String) = calc(text).issues

    private val x = Variable("x")

    /** The explicit solutions in y = … (one, or several for ±) of [text]. */
    private fun solutions(text: String): List<Expression> {
        val result = calc(text)
        assertTrue("$text: ${result.issues}", result.isSuccess)
        val e = result.expression
        val equations = if (e is Alternatives) e.equations else listOf(e)
        return equations.map { ((it as? Equals) ?: throw AssertionError("$text: not an equation: $it")).also { eq -> assertEquals("$text: not explicit", "y", eq.left.toString()) }.right }
    }

    /** y(x), y′(x) and y″(x) by central differences, with the constants (C, C₁, …) given the values [constants]. */
    private fun check(text: String, residual: (x: Double, y: Double, y1: Double, y2: Double) -> Double, constants: Double = 0.7) {
        val h = 1e-4
        for (solution in solutions(text)) {
            fun y(at: Double) = solution.evaluateNumerically(solution.variables().associateWith { if (it == x) at else constants })
            var checked = 0
            for (at in listOf(0.4, 0.8, 1.3, 1.9)) {
                val value = y(at)
                if (!value.isFinite()) continue
                val y1 = (y(at + h) - y(at - h)) / (2 * h)
                val y2 = (y(at + h) - 2 * value + y(at - h)) / (h * h)
                if (!y1.isFinite() || !y2.isFinite()) continue
                val scale = 1.0 + abs(value) + abs(y1) + abs(y2)
                assertEquals("$text at x = $at: $solution", 0.0, residual(at, value, y1, y2), 2e-4 * scale)
                checked++
            }
            assertTrue("$text: nothing to check for $solution", checked >= 2)
        }
    }

    @Test
    fun linearFirstOrder() {
        assertEquals("y = C(e)^((x)^(2))", shown("dsolve(D{y}=2xy)"))
        assertEquals("y = x − 1 + C(e)^( − x)", shown("dsolve(D{y}+y=x)"))
        assertEquals("y = (x)^(2) + Cx", shown("dsolve(x*D{y}=y+x^2)"))
        assertEquals("y = ((x)^(2))/(3) + (C)/(x)", shown("dsolve(D{y}+y/x=x)"))
        check("dsolve(D{y}=2xy)", { x, y, y1, _ -> y1 - 2 * x * y })
        check("dsolve(D{y}+y=x)", { x, y, y1, _ -> y1 + y - x })
        check("dsolve(D{y}+2y=e^{-x})", { x, y, y1, _ -> y1 + 2 * y - exp(-x) })
        check("dsolve(x*D{y}=y+x^2)", { x, y, y1, _ -> x * y1 - y - x * x })
        check("dsolve(D{y}+2y/x=1)", { x, y, y1, _ -> y1 + 2 * y / x - 1 })
        check("dsolve(D{y}=cos(x)y)", { x, y, y1, _ -> y1 - cos(x) * y })
        check("dsolve(D{y}=y/x+x)", { x, y, y1, _ -> y1 - y / x - x })
        check("dsolve(D{y}=k y)", { _, y, y1, _ -> y1 - 0.7 * y })
        // The unknown can have another name
        assertEquals("f = C(e)^(3x)", shown("dsolve(D{f}=3f)"))
    }

    @Test
    fun separableFirstOrder() {
        // Solved for y where that can be done
        assertEquals("y = (1)/( − x − C)", shown("dsolve(D{y}=y^2)"))
        assertEquals("y = tan(((x)^(2))/(2) + C)", shown("dsolve(D{y}=x(1+y^2))"))
        assertEquals("y = ln((e)^(x) + C)", shown("dsolve(D{y}=e^{x-y})"))
        check("dsolve(D{y}=y^2)", { _, y, y1, _ -> y1 - y * y })
        check("dsolve(D{y}=x(1+y^2))", { x, y, y1, _ -> y1 - x * (1 + y * y) }, constants = 0.2)
        check("dsolve(D{y}=x^2/y^2)", { x, y, y1, _ -> y1 - x * x / (y * y) })
        check("dsolve(D{y}=e^{x-y})", { x, y, y1, _ -> y1 - exp(x - y) })
        check("dsolve(D{y}=x/y)", { x, y, y1, _ -> y1 - x / y })
        check("dsolve(D{y}=x/(1+y))", { x, y, y1, _ -> y1 - x / (1 + y) })
        check("dsolve(D{y}=x y^3)", { x, y, y1, _ -> y1 - x * y * y * y }, constants = -1.5)
        // Both signs
        assertEquals(2, solutions("dsolve(D{y}=x/y)").size)
        // Otherwise the equation between y and x
        assertEquals("ln(abs(y)) − ln(abs(y − 1)) = x + C", shown("dsolve(D{y}=y(1-y))"))
    }

    @Test
    fun secondOrderHomogeneous() {
        assertEquals("y = C₁(e)^(2x) + C₂(e)^(x)", shown("dsolve(D{D{y}}-3D{y}+2y=0)"))
        assertEquals("y = C₁cos(x) + C₂sin(x)", shown("dsolve(D{D{y}}+y=0)"))
        assertEquals("y = (e)^( − x)(C₁ + C₂x)", shown("dsolve(D{D{y}}+2D{y}+y=0)"))
        assertEquals("y = (e)^( − x)(C₁cos(2x) + C₂sin(2x))", shown("dsolve(D{D{y}}+2D{y}+5y=0)"))
        assertEquals("y = C₁ + C₂x", shown("dsolve(D{D{y}}=0)"))
        check("dsolve(D{D{y}}-3D{y}+2y=0)", { _, y, y1, y2 -> y2 - 3 * y1 + 2 * y })
        check("dsolve(D{D{y}}-2y=0)", { _, y, _, y2 -> y2 - 2 * y })
        check("dsolve(D{D{y}}+2y=0)", { _, y, _, y2 -> y2 + 2 * y })
        check("dsolve(2D{D{y}}-D{y}-y=0)", { _, y, y1, y2 -> 2 * y2 - y1 - y })
        check("dsolve(D{D{y}}+4D{y}+4y=0)", { _, y, y1, y2 -> y2 + 4 * y1 + 4 * y })
    }

    @Test
    fun secondOrderWithARightHandSide() {
        assertEquals("y = C₁ + C₂x + ((x)^(3))/(6)", shown("dsolve(D{D{y}}=x)"))
        check("dsolve(D{D{y}}-y=x)", { x, y, _, y2 -> y2 - y - x })
        check("dsolve(D{D{y}}+y=e^x)", { x, y, _, y2 -> y2 + y - exp(x) })
        check("dsolve(D{D{y}}-2D{y}+y=e^x)", { x, y, y1, y2 -> y2 - 2 * y1 + y - exp(x) })
        // Resonance: the right side is a solution of the homogeneous equation
        check("dsolve(D{D{y}}+y=sin(x))", { x, y, _, y2 -> y2 + y - sin(x) })
        check("dsolve(D{D{y}}+4D{y}+4y=e^{-2x})", { x, y, y1, y2 -> y2 + 4 * y1 + 4 * y - exp(-2 * x) })
        check("dsolve(D{D{y}}+3D{y}+2y=x^2)", { x, y, y1, y2 -> y2 + 3 * y1 + 2 * y - x * x })
    }

    @Test
    fun initialConditions() {
        assertEquals("y = 3(e)^(2x)", shown("dsolve(D{y}=2y,0,3)"))
        assertEquals("y = x − 1 + 2(e)^( − x)", shown("dsolve(D{y}+y=x,0,1)"))
        assertEquals("y = ((x)^(2))/(3) + (5)/(3x)", shown("dsolve(x*D{y}+y=x^2,1,2)"))
        assertEquals("y =  − sqrt(2(x)^(2) + 9)", shown("dsolve(D{y}=2x/y,0,-3)"))
        assertEquals("y = tan(((x)^(2))/(2) + (π)/(4))", shown("dsolve(D{y}=x(1+y^2),0,1)"))
        assertEquals("y = (1)/(sqrt( − (x)^(2) + 1))", shown("dsolve(D{y}=x y^3,0,1)"))
        assertEquals("y = x", shown("dsolve(D{y}=e^{x-y},0,0)"))
        // A solution that is not in the family
        assertEquals("y = 0", shown("dsolve(D{y}=y^2,0,0)"))
        // Second order: y(x₀) and y′(x₀)
        assertEquals("y =  − (e)^(2x) + 2(e)^(x)", shown("dsolve(D{D{y}}-3D{y}+2y=0,0,1,0)"))
        assertEquals("y = sin(x)", shown("dsolve(D{D{y}}+y=0,0,0,1)"))
        assertEquals("y = (e)^( − x)(cos(2x) + (sin(2x))/(2))", shown("dsolve(D{D{y}}+2D{y}+5y=0,0,1,0)"))
        assertEquals("y = 3 − 2(e)^( − x) − x + ((x)^(2))/(2)", shown("dsolve(D{D{y}}+D{y}=x,0,1,1)"))
        assertEquals("y = (sin(3x))/(18) − (xcos(3x))/(6)", shown("dsolve(D{D{y}}+9y=sin(3x),0,0,0)"))
        // The conditions are met: y(1) = 2, and y(2) = −1 with y′(2) = 3
        val first = solutions("dsolve(x*D{y}+y=x^2,1,2)").single()
        assertEquals(2.0, first.evaluateNumerically(mapOf(x to 1.0)), 1e-9)
        val second = solutions("dsolve(D{D{y}}-y=x,2,-1,3)").single()
        assertEquals(-1.0, second.evaluateNumerically(mapOf(x to 2.0)), 1e-9)
        val h = 1e-6
        assertEquals(3.0, (second.evaluateNumerically(mapOf(x to 2.0 + h)) - second.evaluateNumerically(mapOf(x to 2.0 - h))) / (2 * h), 1e-5)
    }

    @Test
    fun whatCannotBeSolvedIsAnError() {
        // Not linear or separable; variable coefficients; an integral that has no closed form
        assertEquals(listOf(MathIssue.CANT_SOLVE_ODE), issues("dsolve(D{y}=y^2+x)"))
        assertEquals(listOf(MathIssue.CANT_SOLVE_ODE), issues("dsolve(D{D{y}}+x y=0)"))
        assertEquals(listOf(MathIssue.CANT_SOLVE_ODE), issues("dsolve(D{D{y}}=x*D{y}*y)"))
        assertEquals(listOf(MathIssue.CANT_SOLVE_ODE), issues("dsolve(D{D{y}}+y=tan(x))"))
        // Third order, no derivative, no equation
        assertEquals(listOf(MathIssue.CANT_SOLVE_ODE), issues("dsolve(D{D{D{y}}}=y)"))
        assertEquals(listOf(MathIssue.CANT_SOLVE_ODE), issues("dsolve(y=1)"))
        assertEquals(listOf(MathIssue.CANT_SOLVE_ODE), issues("dsolve(D{sin(y)}=1)"))
        assertEquals(listOf(MathIssue.CANT_SOLVE_ODE), issues("dsolve(x+1)"))
        // The conditions fit the order
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("dsolve(D{y}=y,0)"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("dsolve(D{y}=y,0,1,2)"))
        assertEquals(listOf(MathIssue.ARGUMENT_COUNT), issues("dsolve(D{D{y}}+y=0,0,1)"))
    }

    private fun rules(text: String) = runBlocking { MathEngine.steps(TestInput.parse(text), MathContext()) }?.steps?.mapNotNull { it.rule }

    @Test
    fun theStepsFollowTheMethod() {
        assertEquals(
            listOf(StepRule.ODE_STANDARD_FORM, StepRule.ODE_INTEGRATING_FACTOR, StepRule.ODE_LINEAR_SOLUTION, StepRule.ODE_SOLVE_FOR_UNKNOWN, StepRule.ODE_CONSTANTS),
            rules("dsolve(D{y}+y=x,0,1)")
        )
        assertEquals(listOf(StepRule.ODE_SEPARATE, StepRule.ODE_INTEGRATE_BOTH, StepRule.ODE_SOLVE_FOR_UNKNOWN), rules("dsolve(D{y}=x/y)"))
        // An equation that stays implicit has no step for solving
        assertEquals(listOf(StepRule.ODE_SEPARATE, StepRule.ODE_INTEGRATE_BOTH), rules("dsolve(D{y}=y(1-y))"))
        assertEquals(listOf(StepRule.ODE_CHARACTERISTIC, StepRule.ODE_HOMOGENEOUS), rules("dsolve(D{D{y}}+2D{y}+5y=0)"))
        assertEquals(
            listOf(StepRule.ODE_CHARACTERISTIC, StepRule.ODE_HOMOGENEOUS, StepRule.ODE_UNDETERMINED_COEFFICIENTS, StepRule.ODE_CONSTANTS),
            rules("dsolve(D{D{y}}+y=sin(x),0,0,1)")
        )
        // What is worked out is shown with the stage: the roots, the integral
        val steps = runBlocking { MathEngine.steps(TestInput.parse("dsolve(D{D{y}}-3D{y}+2y=0)"), MathContext()) }!!
        val notes = steps.steps[1].notes.map { it.toString() }
        assertEquals(listOf("D = 1", "r₁ = 2", "r₂ = 1"), notes)
        assertEquals("y = C₁(e)^(2x) + C₂(e)^(x)", steps.result.toString())
        // Nothing to show for what is not solved
        assertNull(rules("dsolve(D{y}=y^2+x)"))
    }

    @Test
    fun definedLettersAreNotUsedForTheEquation() {
        // x and y are the letters of the equation, whatever they were defined as; another letter is used
        val defined = MathContext(variables = mapOf("x" to TestInput.parse("3"), "y" to TestInput.parse("5"), "k" to TestInput.parse("2")))
        assertEquals("y = C(e)^((x)^(2))", runBlocking { MathEngine.calculate(TestInput.parse("dsolve(D{y}=2xy)"), defined) }.result.toString())
        assertEquals("y = C(e)^(2x)", runBlocking { MathEngine.calculate(TestInput.parse("dsolve(D{y}=k y)"), defined) }.result.toString())
    }

    /** [text] with its derivatives taken by t, as in a rate equation d[A]/dt = 2k[A]². */
    private fun byTime(text: String): MathString {
        fun fix(n: MathNode): MathNode = if (n is DerivativeNode) n.copy(variable = "t") else n
        return MathString(TestInput.parse(text).nodes.map { n ->
            if (n is FunctionNode) FunctionNode(n.name, MathString(n.argument.nodes.map { fix(it) })) else fix(n)
        })
    }

    private fun byTimeShown(text: String): String {
        val result = runBlocking { MathEngine.calculate(byTime(text), MathContext()) }
        assertTrue("$text: ${result.issues}", result.isSuccess)
        return result.result.toString()
    }

    @Test
    fun rateEquationsWithAConstantAndALetterAsInitialValue() {
        // d[A]/dt = 2k[A]²: separable with a parameter k (the numeric check of the split needs values for it)
        assertEquals("a = (1)/( − 2kt − C)", byTimeShown("dsolve(D{a}=2ka^2)"))
        assertEquals("a = (3)/( − 6kt + 1)", byTimeShown("dsolve(D{a}=2ka^2,0,3)"))
        // [A](0) = [A]₀ as a letter: a = 1/(1/b − 2kt) = b/(1 − 2kbt)
        assertEquals("a = (b)/( − 2bkt + 1)", byTimeShown("dsolve(D{a}=2ka^2,0,b)"))
        assertEquals("a = b(e)^(kt)", byTimeShown("dsolve(D{a}=ka,0,b)"))
        assertEquals("a = b(e)^( − kt)", byTimeShown("dsolve(D{a}=-ka,0,b)"))
        // The solution has the initial value at 0 and satisfies the equation (k = 0.3, b = 2): a(t) = b/(1 − 2kbt)
        val solution = (runBlocking { MathEngine.calculate(byTime("dsolve(D{a}=2ka^2,0,b)"), MathContext()) }.expression as Equals).right
        fun a(t: Double) = solution.evaluateNumerically(mapOf(Variable("t") to t, Variable("k") to 0.3, Variable("b") to 2.0))
        assertEquals(2.0, a(0.0), 1e-12)
        assertEquals(2.0 / (1 - 2 * 0.3 * 2.0 * 0.5), a(0.5), 1e-12)
        val h = 1e-6
        assertEquals(2 * 0.3 * a(0.5) * a(0.5), (a(0.5 + h) - a(0.5 - h)) / (2 * h), 1e-5)
        // The initial value must not depend on the unknown or its variable
        assertEquals(listOf(MathIssue.CANT_SOLVE_ODE), runBlocking { MathEngine.calculate(byTime("dsolve(D{a}=2ka^2,0,a)"), MathContext()) }.issues)
        // Parameters in other separable equations
        check("dsolve(D{y}=k y^2)", { _, y, y1, _ -> y1 - 0.8 * y * y }, constants = 0.8)
    }

    @Test
    fun secondOrderWithComplexRootsAndARightSide() {
        // Undetermined coefficients, so no integral is needed (complex roots with x or sin on the right were refused before)
        check("dsolve(D{D{y}}+2D{y}+5y=sin(x))", { x, y, y1, y2 -> y2 + 2 * y1 + 5 * y - sin(x) })
        check("dsolve(D{D{y}}+D{y}+3y=sin(x))", { x, y, y1, y2 -> y2 + y1 + 3 * y - sin(x) })
        check("dsolve(5D{D{y}}-3D{y}+y=x)", { x, y, y1, y2 -> 5 * y2 - 3 * y1 + y - x })
        check("dsolve(5D{D{y}}+4D{y}+4y=x)", { x, y, y1, y2 -> 5 * y2 + 4 * y1 + 4 * y - x })
        check("dsolve(-5D{D{y}}-3D{y}-2y=cos(2x))", { x, y, y1, y2 -> -5 * y2 - 3 * y1 - 2 * y - cos(2 * x) })
        check("dsolve(3D{D{y}}-3D{y}+3y=sin(x))", { x, y, y1, y2 -> 3 * y2 - 3 * y1 + 3 * y - sin(x) })
        // Resonance: the right side solves the homogeneous equation, and a double root
        check("dsolve(D{D{y}}+4y=cos(2x))", { x, y, _, y2 -> y2 + 4 * y - cos(2 * x) })
        check("dsolve(D{D{y}}-2D{y}+y=x e^x)", { x, y, y1, y2 -> y2 - 2 * y1 + y - x * exp(x) })
        check("dsolve(D{D{y}}-y=e^x+x^2)", { x, y, _, y2 -> y2 - y - exp(x) - x * x })
        check("dsolve(D{D{y}}+y=x e^{-x} cos(x))", { x, y, _, y2 -> y2 + y - x * exp(-x) * cos(x) })
        assertEquals("y = C₁cos(x) + C₂sin(x) + ((e)^(x))/(2)", shown("dsolve(D{D{y}}+y=e^x)"))
        // A right side it does not know is left to variation of parameters
        check("dsolve(D{D{y}}-y=1/x)", { x, y, _, y2 -> y2 - y - 1 / x })
    }

    @Test
    fun separableWithConditionsWhereTheSolutionExistsOnlyNearThePoint() {
        // y = 1/√(9/4 − 2eˣ) exists for x < 0.118 only; and y(1) = 1 for y′ = x·y(2 − y) needs ln|y − 2|
        assertEquals("y = (1)/(sqrt( − 2(e)^(x) + (9)/(4)))", shown("dsolve(D{y}=e^x*y^3,0,2)"))
        assertEquals(listOf<MathIssue>(), issues("dsolve(D{y}=x*y(2-y),1,1)"))
        assertEquals(listOf<MathIssue>(), issues("dsolve(D{y}=2*y(2-y),1,1)"))
        // The bars stay in the answer
        assertTrue(shown("dsolve(D{y}=y(2-y),0,1)").contains("abs"))
    }

    @Test
    fun otherModes() {
        fun result(text: String, context: MathContext) = runBlocking { MathEngine.calculate(TestInput.parse(text), context) }
        // NUMERIC: worked out exactly, written in decimals; second order used to be refused there
        val numeric = MathContext(mode = com.vortessence.calculator.core.math.context.CalculationMode.NUMERIC)
        assertTrue(result("dsolve(D{D{y}}+y=0,0,1,0)", numeric).isSuccess)
        assertTrue(result("dsolve(D{D{y}}+0.5y=0)", numeric).isSuccess)
        assertTrue(result("dsolve(D{D{y}}+2D{y}+5y=sin(x))", numeric).isSuccess)
        // DEGREES: cos(βx) means the same as in radians, so it is written for degrees
        val degrees = MathContext(angleUnit = com.vortessence.calculator.core.math.context.AngleUnit.DEGREES)
        val solution = (result("dsolve(D{D{y}}+y=0,0,1,0)", degrees).expression as Equals).right
        fun y(t: Double) = solution.evaluateNumerically(mapOf(x to t))
        assertEquals(cos(1.0), y(1.0), 1e-9)
        assertEquals(cos(2.5), y(2.5), 1e-9)
    }

    @Test
    fun dsolveIsTheWholeLine() {
        assertEquals(listOf(MathIssue.DSOLVE_ALONE), issues("dsolve(D{y}=y)+1"))
        assertEquals(listOf(MathIssue.DSOLVE_ALONE), issues("2*dsolve(D{y}=y)"))
        assertEquals(listOf(MathIssue.DSOLVE_ALONE), issues("dsolve(D{D{y}}+y=0,0,1,0)+1"))
        // Not stored in a variable either
        val define = MathString(listOf(com.vortessence.calculator.core.math.type.VariableNode("a"), com.vortessence.calculator.core.math.type.OperatorNode(com.vortessence.calculator.core.math.type.MathOp.DEFINE)) + TestInput.parse("dsolve(D{y}=y)").nodes)
        assertEquals(listOf(MathIssue.DSOLVE_ALONE), runBlocking { MathEngine.calculate(define, MathContext()) }.issues)
    }

    @Test
    fun timeAndTimeout() {
        // Out of time is a timeout, not "cannot be solved"
        val start = System.currentTimeMillis()
        val result = runBlocking { MathEngine.calculate(TestInput.parse("dsolve(D{D{y}}+y=sin(x)*e^x*x^3*cos(3x)/(1+x^2))"), MathContext(), timeoutMillis = 100) }
        assertTrue("took ${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 3000)
        assertFalse(result.isSuccess)
        assertTrue(result.issues.toString(), result.issues == listOf(MathIssue.TIMEOUT) || result.issues == listOf(MathIssue.CANT_SOLVE_ODE))
    }
}
