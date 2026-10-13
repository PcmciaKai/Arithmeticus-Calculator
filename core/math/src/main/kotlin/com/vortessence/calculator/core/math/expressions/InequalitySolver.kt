package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.i18n.Text
import com.vortessence.calculator.core.math.i18n.Texts
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.steps.Alternatives
import com.vortessence.calculator.core.math.steps.TextExpression
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** One interval of a solution: [lower] and [upper] are null where it goes on forever; the ends are in it if closed. */
internal class Interval(val lower: Expression?, val lowerClosed: Boolean, val upper: Expression?, val upperClosed: Boolean) {
    val isPoint: Boolean get() = lower != null && upper != null && lowerClosed && upperClosed && lower == upper
}

/**
 * Where an inequality holds, found by the places where the sign can change: the zeros of left − right
 * and the points where something is not defined (a denominator is 0, a root or a logarithm begins).
 * Between two of them the sign stays the same, so one test value tells for the whole interval.
 */
internal class InequalityAnalysis(
    val variable: Variable,
    val inequality: Inequality,
    /** The exact zeros of left − right, sorted. */
    val zeros: List<Expression>,
    /** Where it is not defined or begins to be, e.g. x = −2 for 1/(x + 2), sorted; not those already among the zeros. */
    val breaks: List<Expression>,
    /** The parts that make a break, each with the points where it is 0 (those that have some): x + 2 for 1/(x + 2). */
    val breakSources: List<Pair<Expression, List<Expression>>>,
    /** All of them, sorted, each once. */
    val critical: List<Expression>,
    /** The sample points, one for each interval in between: (−∞, c₁), (c₁, c₂) … (cₙ, ∞). */
    val samples: List<Double>,
    /** left − right at each sample point, NaN where it is not defined. */
    val sampleValues: List<Double>,
    /** For each critical point: left − right there (NaN where it is not defined). */
    val pointValues: List<Double>,
    val intervalHolds: List<Boolean>,
    val pointHolds: List<Boolean>,
    val pieces: List<Interval>
) {
    /** The solution as a line of the steps and as the result: x < −2 or x > 2. */
    fun toExpression(): Expression = intervalsExpression(variable, pieces)
}

/** The intervals of a solution as a line: x < −2 or x > 2, 1 < x ≤ 3, x ∈ ℝ; "no real solution" for none. */
internal fun intervalsExpression(v: Variable, pieces: List<Interval>): Expression {
    if (pieces.isEmpty()) return TextExpression(Texts.get(Text.NO_REAL_SOLUTION_TEXT))
    val parts = pieces.map { piece ->
        when {
            piece.lower == null && piece.upper == null -> TextExpression("${v.identifier} ∈ ℝ")
            piece.lower == null -> Inequality(v, piece.upper!!, if (piece.upperClosed) Relation.LESS_EQUAL else Relation.LESS)
            piece.upper == null -> Inequality(v, piece.lower, if (piece.lowerClosed) Relation.GREATER_EQUAL else Relation.GREATER)
            piece.isPoint -> Equals(v, piece.lower)
            else -> Between(
                piece.lower, if (piece.lowerClosed) Relation.LESS_EQUAL else Relation.LESS, v,
                if (piece.upperClosed) Relation.LESS_EQUAL else Relation.LESS, piece.upper
            )
        }
    }
    return if (parts.size == 1) parts.single() else Alternatives(parts, inline = true, separator = " or ")
}

internal object InequalitySolver {

    private const val MAX_CRITICAL_POINTS = 12

    /**
     * The analysis of [inequality] for its only variable; null if it has several, or kinds of functions
     * whose sign does not change at a few points (sin, cos ...), or if it is too involved.
     */
    fun analyze(inequality: Inequality, context: MathContext): InequalityAnalysis? {
        val variables = inequality.variables()
        val v = variables.singleOrNull() ?: return null
        if (inequality.containsExpressionMatching { unsupported(it) }) return null
        fun f(x: Double): Double {
            val bindings = mapOf(v to x)
            return inequality.left.evaluateNumerically(bindings) - inequality.right.evaluateNumerically(bindings)
        }

        // The zeros of left − right
        val zeroSolutions = realRoots(Equals(inequality.left, inequality.right), v, context) ?: return null
        // Where something is undefined or starts: denominators, even roots, logarithms
        val breakExpressions = mutableListOf<Expression>()
        fun collect(e: Expression) {
            when {
                e is Power && e.base.containsVariable(v) && ((e.exponent as? Value)?.isNegative == true) -> breakExpressions.add(e.base)
                e is Power && e.base.containsVariable(v) && ((e.exponent as? Value)?.rational?.let { r -> r.isFractional && r.denominator.isEven }) == true -> breakExpressions.add(e.base)
                e is Logarithm && e.operand.containsVariable(v) -> breakExpressions.add(e.operand)
            }
            e.children.forEach { collect(it) }
        }
        collect(inequality.left)
        collect(inequality.right)
        val sources = breakExpressions.distinctBy { it.toString() }.map { part -> part to (realRoots(Equals(part, Value.ZERO), v, context) ?: return null) }
        val breakSources = sources.filter { it.second.isNotEmpty() }
        val breakRoots = breakSources.flatMap { it.second }

        val zeros = distinct(zeroSolutions)
        val breaks = distinct(breakRoots).filter { b -> zeros.none { sameNumber(it, b) } }
        val critical = distinct(zeros + breaks)
        if (critical.size > MAX_CRITICAL_POINTS) return null

        val numbers = critical.map { it.evaluateNumerically() }
        val samples = samplePoints(numbers)
        val sampleValues = samples.map { f(it) }
        val pointValues = numbers.map { f(it) }
        fun tolerance(x: Double): Double = 1e-9 * max(1.0, max(abs(inequality.left.evaluateNumerically(mapOf(v to x))), abs(inequality.right.evaluateNumerically(mapOf(v to x)))).let { if (it.isFinite()) it else 1.0 })
        val intervalHolds = samples.indices.map { i -> sampleValues[i].isFinite() && inequality.relation.holds(sampleValues[i], tolerance(samples[i])) }
        val pointHolds = numbers.indices.map { i -> pointValues[i].isFinite() && inequality.relation.holds(pointValues[i], tolerance(numbers[i])) }

        return InequalityAnalysis(
            v, inequality, zeros, breaks, breakSources, critical, samples, sampleValues, pointValues, intervalHolds, pointHolds,
            pieces(critical, intervalHolds, pointHolds)
        )
    }

    /** Sin, cos ... repeat forever, matrices and the like are no real functions of one number. */
    private fun unsupported(e: Expression): Boolean =
        e is TrigonometricFunction || e is MatrixExpression || e is UnitExpression || e is Derivative || e is Antiderivative ||
            e is Integral || e is Limit || e is Series || e is Factorial || e is SpecialFunction || (e is NamedFunction && e.kind.isStepwise && e.arguments.indices.any { e.kind.jumpsIn(it) && e.arguments[it].variables().isNotEmpty() }) || e is Modulo || e.containsImaginaryUnit() && e !is Value

    /** The real solutions of [equation] for [v]; empty if there are none, null if they cannot be found. */
    private fun realRoots(equation: Equals, v: Variable, context: MathContext): List<Expression>? {
        val scratch = context.copy()
        val solutions = equation.solveForVariable(v, scratch)
        if (scratch.issues.any { it != com.vortessence.calculator.core.math.context.MathIssue.NO_REAL_SOLUTION && it != com.vortessence.calculator.core.math.context.MathIssue.CONTRADICTION }) return null
        // No solution or an identity: no point where the sign changes
        if (solutions.isEmpty() || solutions == setOf(v)) return emptyList()
        return solutions.filter { s -> !s.containsImaginaryUnit() && s.variables().isEmpty() && s.evaluateNumerically().isFinite() }
            .let { roots ->
                // ln(x) = 1 gives 2.718…: e, from the steps of the equation; else a decimal that is a simple fraction (−1/3)
                com.vortessence.calculator.core.math.steps.EquationSteps.exactSolutions(equation, v, roots, context)
                    ?: roots.map { exactIfRational(it, equation, v) }
            }
    }

    /** −0.3333333333 found numerically is −1/3 if that satisfies the equation as well. */
    private fun exactIfRational(root: Expression, equation: Equals, v: Variable): Expression {
        if (root !is Value || root.rational != null) return root
        val x = root.real.value
        val candidate = com.vortessence.calculator.core.math.numbers.Rational.approximateFromDouble(x, 1000L) ?: return root
        if (abs(candidate.toDouble() - x) > 1e-7 * max(1.0, abs(x))) return root
        val bindings = mapOf(v to candidate.toDouble())
        val l = equation.left.evaluateNumerically(bindings)
        val r = equation.right.evaluateNumerically(bindings)
        return if (l.isFinite() && r.isFinite() && abs(l - r) <= 1e-9 * max(1.0, max(abs(l), abs(r)))) Value(candidate) else root
    }

    private fun sameNumber(a: Expression, b: Expression): Boolean {
        val x = a.evaluateNumerically()
        val y = b.evaluateNumerically()
        return abs(x - y) <= 1e-9 * max(1.0, max(abs(x), abs(y)))
    }

    /** Sorted by value, equal ones once. */
    private fun distinct(points: List<Expression>): List<Expression> {
        val sorted = points.sortedBy { it.evaluateNumerically() }
        val result = mutableListOf<Expression>()
        for (p in sorted) if (result.none { sameNumber(it, p) }) result.add(p)
        return result
    }

    /** A value in each of the intervals between the points, a whole number where there is one. */
    internal fun samplePoints(points: List<Double>): List<Double> {
        if (points.isEmpty()) return listOf(0.0)
        val result = mutableListOf<Double>()
        result.add(ceilBelow(points.first()))
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val whole = floor(a) + 1
            // The middle whole number, or the middle of the interval if there is none
            result.add(if (whole < b - 1e-9) whole.let { first -> first + floor((ceil(b) - 1 - first) / 2) } else (a + b) / 2)
        }
        result.add(floor(points.last()) + 1)
        return result
    }

    private fun ceilBelow(x: Double): Double = ceil(x) - 1

    /** The runs of the intervals and points that hold, as intervals. */
    private fun pieces(critical: List<Expression>, intervalHolds: List<Boolean>, pointHolds: List<Boolean>): List<Interval> {
        val n = critical.size
        // Interval 0, point 1, interval 1 … point n, interval n
        val included = (0..2 * n).map { if (it % 2 == 0) intervalHolds[it / 2] else pointHolds[it / 2] }
        val pieces = mutableListOf<Interval>()
        var i = 0
        while (i <= 2 * n) {
            if (!included[i]) { i++; continue }
            var j = i
            while (j + 1 <= 2 * n && included[j + 1]) j++
            val lower: Expression? = when {
                i == 0 -> null
                i % 2 == 0 -> critical[i / 2 - 1]
                else -> critical[i / 2]
            }
            val lowerClosed = i % 2 == 1
            val upper: Expression? = when {
                j == 2 * n -> null
                j % 2 == 0 -> critical[j / 2]
                else -> critical[j / 2]
            }
            val upperClosed = j % 2 == 1
            pieces.add(Interval(lower, lowerClosed && lower != null, upper, upperClosed && upper != null))
            i = j + 1
        }
        return pieces
    }
}
