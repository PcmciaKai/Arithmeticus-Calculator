package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Several inequalities in one variable that must all hold (x > 1 and x < 5): each is solved on its own ([InequalitySolver]),
 * and the solution is what the solutions have in common.
 */
internal object InequalitySystemSolver {

    class Result(
        val variable: Variable,
        /** The analysis of each inequality, in the order of the lines. */
        val parts: List<InequalityAnalysis>,
        /** What they have in common, sorted. */
        val pieces: List<Interval>
    ) {
        fun toExpression(): Expression = intervalsExpression(variable, pieces)

        /** All the critical points of the parts, sorted, each once: for the number line. */
        val critical: List<Expression>
            get() {
                val all = parts.flatMap { it.critical }.sortedBy { it.evaluateNumerically() }
                val result = mutableListOf<Expression>()
                for (p in all) if (result.none { same(it, p) }) result.add(p)
                return result
            }
    }

    /** Null if a line has no solution set of this kind, or the lines are about different variables. */
    fun solve(inequalities: List<Inequality>, context: MathContext): Result? {
        val variable = inequalities.firstOrNull()?.variables()?.singleOrNull() ?: return null
        if (inequalities.any { it.variables() != setOf(variable) }) return null
        val parts = inequalities.map { InequalitySolver.analyze(it, context.copy().also { c -> c.complexNumbers = false }) ?: return null }
        var pieces: List<Interval> = listOf(Interval(null, false, null, false))
        for (part in parts) pieces = intersect(pieces, part.pieces)
        val sorted = pieces.sortedBy { it.lower?.evaluateNumerically() ?: Double.NEGATIVE_INFINITY }
        return Result(variable, parts, sorted)
    }

    fun intersect(a: List<Interval>, b: List<Interval>): List<Interval> =
        a.flatMap { x -> b.mapNotNull { y -> intersect(x, y) } }

    private fun intersect(a: Interval, b: Interval): Interval? {
        // The larger lower end, the smaller upper end; where they are equal it is in only if it is in both
        val lower: Expression?
        val lowerClosed: Boolean
        when {
            a.lower == null -> { lower = b.lower; lowerClosed = b.lowerClosed }
            b.lower == null -> { lower = a.lower; lowerClosed = a.lowerClosed }
            else -> {
                val x = a.lower.evaluateNumerically()
                val y = b.lower.evaluateNumerically()
                when {
                    same(x, y) -> { lower = a.lower; lowerClosed = a.lowerClosed && b.lowerClosed }
                    x > y -> { lower = a.lower; lowerClosed = a.lowerClosed }
                    else -> { lower = b.lower; lowerClosed = b.lowerClosed }
                }
            }
        }
        val upper: Expression?
        val upperClosed: Boolean
        when {
            a.upper == null -> { upper = b.upper; upperClosed = b.upperClosed }
            b.upper == null -> { upper = a.upper; upperClosed = a.upperClosed }
            else -> {
                val x = a.upper.evaluateNumerically()
                val y = b.upper.evaluateNumerically()
                when {
                    same(x, y) -> { upper = a.upper; upperClosed = a.upperClosed && b.upperClosed }
                    x < y -> { upper = a.upper; upperClosed = a.upperClosed }
                    else -> { upper = b.upper; upperClosed = b.upperClosed }
                }
            }
        }
        if (lower != null && upper != null) {
            val l = lower.evaluateNumerically()
            val u = upper.evaluateNumerically()
            if (l > u && !same(l, u)) return null
            if (same(l, u) && !(lowerClosed && upperClosed)) return null
        }
        return Interval(lower, lowerClosed && lower != null, upper, upperClosed && upper != null)
    }

    private fun same(a: Expression, b: Expression) = same(a.evaluateNumerically(), b.evaluateNumerically())

    private fun same(x: Double, y: Double) = abs(x - y) <= 1e-9 * max(1.0, max(abs(x), abs(y)))
}
