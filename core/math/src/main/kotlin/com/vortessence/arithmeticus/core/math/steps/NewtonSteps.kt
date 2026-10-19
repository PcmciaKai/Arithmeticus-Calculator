package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.Derivative
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.Variable
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The steps of an equation that no rule solves, e.g. sin(x) = x/2 or x³ − 2x − 5 = 0, which the
 * calculator solves by approximation: everything to one side, f(x) = 0, then Newton's method,
 * x → x − f(x)/f′(x), repeated from a starting value until it settles, once for each solution.
 */
internal object NewtonSteps {

    private const val MAX_SOLUTIONS = 4
    private const val MAX_ITERATIONS = 8
    private const val TOLERANCE = 1e-9

    fun steps(equation: Equals, v: Variable, solutions: List<Expression>, context: MathContext): ExpressionSolution? {
        if (context.angleUnit != AngleUnit.RADIANS) return null
        if (equation.variables() != setOf(v)) return null
        // Only what the calculator approximated: decimal numbers (some roots, such as 0, come out exact)
        val roots = solutions.map { it as? Value ?: return null }
        if (roots.isEmpty() || roots.size > MAX_SOLUTIONS || roots.none { it.rational == null }) return null

        val scratch = context.copy()
        val f = Additions(equation.left, Multiplications(Value.MINUS_ONE, equation.right)).canonicalize(scratch)
        val df = Derivative(f, v).canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || df.containsExpressionMatching { it is Derivative }) return null
        fun value(e: Expression, x: Double) = e.evaluateNumerically(mapOf(v to x))

        val solution = Solver(context)
        solution.start(normalize(equation))
        val oneSide = Equals(displayForm(f, context), Value.ZERO)
        solution.apply(Match(StepRule.ONE_SIDE, mapOf(emptyList<Int>() to oneSide), equation, oneSide, showChange = false, boxReplacements = false))

        val found = mutableListOf<Expression>()
        for ((index, root) in roots.withIndex()) {
            val target = root.real.value
            val iterations = iterate({ x -> value(f, x) }, { x -> value(df, x) }, target) ?: return null
            val notes = mutableListOf<Note>()
            if (index == 0) notes.add(Note(Variable("f′(${v.identifier})"), displayForm(df, context)))
            iterations.forEachIndexed { i, x -> notes.add(Note(Variable(v.identifier + subscript(i)), Value(x))) }
            found.add(Equals(v, root))
            val shown: Expression = if (found.size == 1) found.single() else Alternatives(found.toList())
            solution.apply(Match(StepRule.NEWTON_METHOD, mapOf(emptyList<Int>() to shown), solution.current, shown, notes = notes, showChange = false, boxReplacements = false))
        }
        return solution.finish(if (roots.size == 1) Equals(v, roots.single()) else Alternatives(roots.map { Equals(v, it) }, inline = true))
    }

    /**
     * The values of Newton's method from a starting value near [target] to where it settles;
     * the start is [target] rounded to as few digits as work. Null if it does not settle on it.
     */
    private fun iterate(f: (Double) -> Double, df: (Double) -> Double, target: Double): List<Double>? {
        for (digits in 0..2) {
            val factor = 10.0.pow(digits)
            val start = (target * factor).roundToLong() / factor
            val values = mutableListOf(start)
            var x = start
            var settled = false
            for (step in 1..MAX_ITERATIONS) {
                val slope = df(x)
                if (!slope.isFinite() || abs(slope) < 1e-12) break
                val next = x - f(x) / slope
                if (!next.isFinite()) break
                x = next
                values.add(x)
                if (abs(f(x)) < TOLERANCE) {
                    settled = true
                    break
                }
            }
            if (settled && abs(x - target) < 1e-6 * maxOf(1.0, abs(target))) return values
        }
        return null
    }

    private fun subscript(i: Int) = i.toString().map { "₀₁₂₃₄₅₆₇₈₉"[it - '0'] }.joinToString("")
}
