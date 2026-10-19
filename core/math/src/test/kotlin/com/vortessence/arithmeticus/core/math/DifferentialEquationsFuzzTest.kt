package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.steps.Alternatives
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

/**
 * Random equations of every kind dsolve knows, with and without initial conditions, each answer checked in plain Kotlin by central
 * differences (independent of the solver's own check). An answer may be refused, never wrong.
 */
class DifferentialEquationsFuzzTest {
    private val x = Variable("x")
    private val y = Variable("y")

    private class Case(val text: String, val order: Int, val residual: (Double, Double, Double, Double) -> Double, val x0: Double? = null, val y0: Double? = null, val y1: Double? = null)

    private fun generate(seed: Long): List<Case> {
        val rnd = Random(seed)
        fun small() = rnd.nextInt(5) + 1
        fun signed() = (if (rnd.nextBoolean()) 1 else -1) * small()
        val cases = mutableListOf<Case>()
        // y′ + p(x)·y = q(x)
        repeat(60) {
            val c = signed()
            val (pText, p) = when (rnd.nextInt(3)) { 0 -> "$c" to { _: Double -> c.toDouble() }; 1 -> "${c}x" to { t: Double -> c * t }; else -> "[$c;x]" to { t: Double -> c / t } }
            val (qText, q) = when (rnd.nextInt(6)) {
                0 -> "0" to { _: Double -> 0.0 }; 1 -> "3" to { _: Double -> 3.0 }; 2 -> "x" to { t: Double -> t }
                3 -> "e^{-x}" to { t: Double -> exp(-t) }; 4 -> "sin(x)" to { t: Double -> sin(t) }; else -> "x^2" to { t: Double -> t * t }
            }
            val withCondition = rnd.nextBoolean()
            val x0 = rnd.nextInt(3) + 1; val y0 = signed()
            cases.add(Case("dsolve(D{y}+$pText*y=$qText${if (withCondition) ",$x0,$y0" else ""})", 1, { t, v, d1, _ -> d1 + p(t) * v - q(t) }, if (withCondition) x0.toDouble() else null, if (withCondition) y0.toDouble() else null))
        }
        // y′ = f(x)·g(y)
        repeat(60) {
            val (fText, f) = when (rnd.nextInt(5)) {
                0 -> "x" to { t: Double -> t }; 1 -> small().let { c -> "$c" to { _: Double -> c.toDouble() } }; 2 -> "x^2" to { t: Double -> t * t }
                3 -> "cos(x)" to { t: Double -> cos(t) }; else -> "e^x" to { t: Double -> exp(t) }
            }
            val (gText, g) = when (rnd.nextInt(7)) {
                0 -> "y^2" to { v: Double -> v * v }; 1 -> "(1+y^2)" to { v: Double -> 1 + v * v }; 2 -> "[1;y]" to { v: Double -> 1 / v }
                3 -> "y^3" to { v: Double -> v * v * v }; 4 -> "e^{-y}" to { v: Double -> exp(-v) }; 5 -> "(y+1)" to { v: Double -> v + 1 }; else -> "y(2-y)" to { v: Double -> v * (2 - v) }
            }
            val withCondition = rnd.nextBoolean()
            val x0 = rnd.nextInt(2); val y0 = rnd.nextInt(3) + 1
            cases.add(Case("dsolve(D{y}=$fText*$gText${if (withCondition) ",$x0,$y0" else ""})", 1, { t, v, d1, _ -> d1 - f(t) * g(v) }, if (withCondition) x0.toDouble() else null, if (withCondition) y0.toDouble() else null))
        }
        // a·y″ + b·y′ + c·y = r(x)
        repeat(80) {
            val a = signed(); val b = rnd.nextInt(9) - 4; val c = rnd.nextInt(9) - 4
            val (rText, r) = when (rnd.nextInt(8)) {
                0 -> "0" to { _: Double -> 0.0 }; 1 -> "1" to { _: Double -> 1.0 }; 2 -> "x" to { t: Double -> t }; 3 -> "x^2" to { t: Double -> t * t }
                4 -> "e^x" to { t: Double -> exp(t) }; 5 -> "sin(x)" to { t: Double -> sin(t) }; 6 -> "cos(2x)" to { t: Double -> cos(2 * t) }; else -> "e^{-x}" to { t: Double -> exp(-t) }
            }
            val withCondition = rnd.nextBoolean()
            val x0 = rnd.nextInt(2); val y0 = rnd.nextInt(7) - 3; val y1 = rnd.nextInt(7) - 3
            cases.add(Case("dsolve(${a}D{D{y}}+${b}D{y}+${c}y=$rText${if (withCondition) ",$x0,$y0,$y1" else ""})".replace("+-", "-"), 2, { t, v, d1, d2 -> a * d2 + b * d1 + c * v - r(t) },
                if (withCondition) x0.toDouble() else null, if (withCondition) y0.toDouble() else null, if (withCondition) y1.toDouble() else null))
        }
        return cases
    }

    private fun value(e: Expression, t: Double) = e.evaluateNumerically(e.variables().associateWith { if (it == x) t else 0.7 })

    @Test
    fun anAnswerIsRefusedOrRight() {
        val h = 1e-4
        var solved = 0
        val refused = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        val cases = generate(20261018L)
        for (case in cases) {
            val result = runBlocking { MathEngine.calculate(TestInput.parse(case.text), MathContext()) }
            if (!result.isSuccess) { refused.add("${case.text} ${result.issues}"); continue }
            val equations = (result.expression as? Alternatives)?.equations ?: listOf(result.expression)
            val explicit = equations.filterIsInstance<Equals>().filter { it.left == y }.map { it.right }
            val implicit = equations.filterIsInstance<Equals>().firstOrNull { it.left != y }
            if (explicit.isNotEmpty()) {
                // Every branch solves the equation where it is defined; one of them passes through the point
                for (solution in explicit) {
                    var checked = 0
                    for (t in listOf(0.05, 0.4, 0.9, 1.4, 2.2, 2.9)) {
                        val v = value(solution, t)
                        val d1 = (value(solution, t + h) - value(solution, t - h)) / (2 * h)
                        val d2 = (value(solution, t + h) - 2 * v + value(solution, t - h)) / (h * h)
                        if (!v.isFinite() || !d1.isFinite() || !d2.isFinite()) continue
                        val scale = 1 + abs(v) + abs(d1) + abs(d2)
                        if (abs(case.residual(t, v, d1, d2)) > 3e-4 * scale * (if (case.order == 2) 10.0 else 1.0)) wrong.add("${case.text}: $solution is not a solution at x = $t")
                        checked++
                    }
                    // A domain that has no sample point is not wrong: it is checked by the solver around the point
                    if (checked > 0) solved++
                }
                if (case.x0 != null) {
                    val hits = explicit.any { s ->
                        abs(value(s, case.x0) - case.y0!!) < 1e-7 && (case.y1 == null || abs((value(s, case.x0 + 1e-5) - value(s, case.x0 - 1e-5)) / 2e-5 - case.y1) < 1e-3)
                    }
                    if (!hits) wrong.add("${case.text}: no branch passes through the point")
                }
            } else if (implicit != null) {
                // Φ(y) = F(x) + C is consistent with y′ = h(x, y): Φ′(y)·y′ = F′(x) wherever the equation can be solved for y′
                for (t in listOf(0.5, 1.2, 1.9)) for (v in listOf(0.6, 1.3, 2.4)) {
                    val point = (implicit.left.variables() + implicit.right.variables()).associateWith { 0.7 } + mapOf(x to t, y to v)
                    val dLeft = (implicit.left.evaluateNumerically(point + (y to v + h)) - implicit.left.evaluateNumerically(point + (y to v - h))) / (2 * h)
                    val dRight = (implicit.right.evaluateNumerically(point + (x to t + h)) - implicit.right.evaluateNumerically(point + (x to t - h))) / (2 * h)
                    if (!dLeft.isFinite() || !dRight.isFinite()) continue
                    val r0 = case.residual(t, v, 0.0, 0.0)
                    val slope = -r0 / (case.residual(t, v, 1.0, 0.0) - r0)
                    if (abs(dLeft * slope - dRight) > 2e-4 * (1 + abs(dLeft * slope) + abs(dRight))) wrong.add("${case.text}: ${implicit.left} = ${implicit.right} is not consistent")
                }
                solved++
            } else wrong.add("${case.text}: no solution in the result")
        }
        println("FUZZ cases=${cases.size} answered=$solved refused=${refused.size}")
        refused.forEach { println("FUZZ refused $it") }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        // Not everything may be refused: most of what it is given has an answer
        assertTrue("only $solved of ${cases.size} answered", solved >= cases.size * 0.8)
    }
}
