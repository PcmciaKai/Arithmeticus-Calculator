package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.i18n.Text
import com.vortessence.calculator.core.math.i18n.Texts
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.solvers.NonlinearSystemSolver
import com.vortessence.calculator.core.math.steps.Alternatives
import com.vortessence.calculator.core.math.steps.TextExpression
import kotlin.math.abs

/**
 * sin(u) > c, cos(u) ≤ c, tan(u) < c … with u = ax + b: the solution inside one period (from asin, acos or atan of c), repeated
 * by 2kπ (kπ for tan) for every whole number k, then solved for x. In degrees the period is 360.
 */
internal class TrigInequality(
    val variable: Variable,
    /** sin(u), cos(u) or tan(u) as typed. */
    val function: TrigonometricFunction,
    /** The constant it is compared with, after moving everything else to the other side: sin(u) > c. */
    val constant: Expression,
    val relation: Relation,
    /** Whether it was typed as sin(u) > c, without anything to move. */
    val typedIsolated: Boolean,
    val k: Variable,
    val kind: Kind,
    /** asin(c), acos(c) or atan(c), and its value where the calculator knows the angle. */
    val inverse: Expression,
    val exact: Expression,
    /** The solution for u (for example α + 2kπ < u < π − α + 2kπ) and for x. */
    val forU: Expression,
    val forX: Expression,
    val period: Expression,
    /** x = (u − b)/a: the factor 1/a and the number −b/a. */
    val inner: Pair<Expression, Expression>?
) {
    enum class Kind { INTERVAL, POINTS, ALL, NONE }
}

internal object TrigInequalitySolver {

    fun solve(inequality: Inequality, context: MathContext): TrigInequality? {
        val v = inequality.variables().singleOrNull() ?: return null
        val unit = context.angleUnit
        val functions = LinkedHashSet<TrigonometricFunction>()
        fun collect(e: Expression) {
            if (e is TrigonometricFunction) {
                if (e.type.isInverse) return
                if (e.operand.containsVariable(v)) { functions.add(e); return }
            }
            e.children.forEach { collect(it) }
        }
        collect(inequality.left)
        collect(inequality.right)
        val function = functions.singleOrNull() ?: return null
        if (function.type.isInverse || (function.angleUnit ?: unit) != unit) return null
        // The function replaced by a letter t: what is left must be linear in t and have no x
        val t = Variable("§")
        val replaced = Inequality(
            inequality.left.transform { if (it == function) t else it },
            inequality.right.transform { if (it == function) t else it },
            inequality.relation
        )
        if (replaced.containsVariable(v)) return null
        val scratch = context.copy()
        val (coefficient, solved) = NonlinearSystemSolver.linearIn(Equals(replaced.left, replaced.right), t, scratch) ?: return null
        if (coefficient.variables().isNotEmpty() || solved.variables().isNotEmpty()) return null
        val sign = coefficient.evaluateNumerically()
        if (!sign.isFinite() || sign == 0.0) return null
        // a·t + b R 0 is t R c, the other way round if a is negative
        val relation = if (sign > 0) inequality.relation else inequality.relation.reversed()
        val c = solved
        val number = c.evaluateNumerically()
        if (!number.isFinite()) return null
        val typedIsolated = inequality.left == function && !inequality.right.containsVariable(v) && inequality.relation == relation

        // u = ax + b
        val (invA, solvedX) = run {
            val linear = NonlinearSystemSolver.linearIn(Equals(function.operand, Value.ZERO), v, scratch) ?: return null
            if (linear.first.variables().isNotEmpty() || !linear.first.evaluateNumerically().isFinite() || linear.first.evaluateNumerically() == 0.0) return null
            linear.first.invert() to linear.second
        }
        val k = listOf("k", "n", "m").map { Variable(it) }.firstOrNull { it != v } ?: return null
        val pi: Expression = if (unit == AngleUnit.DEGREES) Value(180L) else Constant.PI
        val turn: Expression = if (unit == AngleUnit.DEGREES) Value(360L) else Multiplications.of(Value.TWO, Constant.PI)
        val halfTurnNumber = if (unit == AngleUnit.DEGREES) 180.0 else Math.PI
        val halfPeriod = function.type == TrigType.TAN || function.type == TrigType.COT
        val periodExpression = if (halfPeriod) pi else turn
        val periodNumber = if (halfPeriod) halfTurnNumber else 2 * halfTurnNumber
        val shift = Multiplications.of(periodExpression, k)

        fun canon(e: Expression): Expression = e.canonicalize(context.copy())
        fun plus(a: Expression, b: Expression) = canon(Additions(a, b))
        fun minus(a: Expression) = canon(a.negate())
        val quarter: Expression = canon(Multiplications.of(pi, Value.HALF))

        // The solution inside one period, as pieces with bounds (and whether each is included)
        class Bounds(val lower: Expression, val upper: Expression, val lowerClosed: Boolean, val upperClosed: Boolean)
        val strict = relation.strict
        val greater = relation.greater
        val inverseType = when (function.type) {
            TrigType.SIN -> TrigType.ASIN
            TrigType.COS -> TrigType.ACOS
            TrigType.TAN -> TrigType.ATAN
            TrigType.SEC -> TrigType.ASEC
            TrigType.CSC -> TrigType.ACSC
            else -> TrigType.ACOT
        }
        val isReciprocal = function.type == TrigType.SEC || function.type == TrigType.CSC
        // sec and csc have |value| ≥ 1: where c is inside (−1, 1) there is no angle to find
        val needsAngle = if (isReciprocal) abs(number) >= 1 - 1e-12 else function.type == TrigType.TAN || function.type == TrigType.COT || abs(number) <= 1 + 1e-12
        var inverse: Expression = TrigonometricFunction(inverseType, if (isReciprocal && number < 0) canon(c.negate()) else c, unit)
        var exact: Expression = inverse
        var pieces: List<Bounds> = emptyList()
        var always = false
        var never = false
        if (!needsAngle && !isReciprocal) {
            // |sin| ≤ 1: sin u > 2 never, sin u < 2 always
            val above = number > 0
            if (greater == above) never = true else always = true
        } else {
            if (needsAngle) exact = canon(inverse).takeIf { e -> !(e is Value && e.rational == null) } ?: inverse
            val alpha = exact
            val eps = 1e-12
            if (isReciprocal) {
                val half = quarter
                val zero: Expression = Value.ZERO
                // sec(w) R c where cos(w) > 0, i.e. −π/2 < w < π/2: sec is at least 1, smallest at w = 0, bigger toward ±π/2
                fun secantPart(value: Double, greater: Boolean, beta: Expression?): List<Bounds> {
                    val open = Bounds(minus(half), half, false, false)
                    val near = abs(value - 1) <= eps
                    return if (greater) when {
                        value < 1 - eps -> listOf(open)
                        near -> if (strict) listOf(Bounds(minus(half), zero, false, false), Bounds(zero, half, false, false)) else listOf(open)
                        else -> listOf(Bounds(minus(half), minus(beta!!), false, !strict), Bounds(beta, half, !strict, false))
                    } else when {
                        value < 1 - eps -> emptyList()
                        near -> if (strict) emptyList() else listOf(Bounds(zero, zero, true, true))
                        else -> listOf(Bounds(minus(beta!!), beta, !strict, !strict))
                    }
                }
                // β = asec(|c|), the angle where sec reaches |c| (for csc: π/2 − acsc(|c|), the same angle seen from the sine)
                val beta: Expression? = if (abs(number) > 1 + eps) {
                    if (function.type == TrigType.SEC) alpha else canon(Additions(half, minus(alpha)))
                } else null
                val positive = secantPart(number, greater, beta)
                // cos(w) < 0 is w = π + w' with sec(w) = −sec(w'): sec(w') R' −c, the relation reversed
                val negative = secantPart(-number, !greater, beta).map { Bounds(plus(pi, it.lower), plus(pi, it.upper), it.lowerClosed, it.upperClosed) }
                // csc(u) = sec(u − π/2)
                val turned = positive + negative
                pieces = if (function.type == TrigType.CSC) turned.map { Bounds(plus(half, it.lower), plus(half, it.upper), it.lowerClosed, it.upperClosed) } else turned
            } else {
                pieces = listOf(when (function.type) {
                    TrigType.SIN ->
                        if (greater) Bounds(alpha, plus(pi, minus(alpha)), !strict, !strict)
                        else Bounds(plus(pi, minus(alpha)), plus(turn, alpha), !strict, !strict)
                    TrigType.COS ->
                        if (greater) Bounds(minus(alpha), alpha, !strict, !strict)
                        else Bounds(alpha, plus(turn, minus(alpha)), !strict, !strict)
                    // cot decreases from ∞ at 0 to −∞ at π: cot u > c up to acot(c), cot u < c from there
                    TrigType.COT ->
                        if (greater) Bounds(Value.ZERO, alpha, false, !strict)
                        else Bounds(alpha, pi, !strict, false)
                    else ->
                        if (greater) Bounds(alpha, quarter, !strict, false)
                        else Bounds(minus(quarter), alpha, false, !strict)
                })
            }
            if (!isReciprocal && number.isNaN()) return null
        }
        // The pieces in order of where they are (the shifted ones of sec and csc may be out of order)
        pieces = pieces.sortedBy { it.lower.evaluateNumerically() }
        fun isPoint(b: Bounds) = b.upper.evaluateNumerically() - b.lower.evaluateNumerically() <= 1e-12
        var kind = TrigInequality.Kind.INTERVAL
        if (never) kind = TrigInequality.Kind.NONE
        if (always) kind = TrigInequality.Kind.ALL
        if (!never && !always) {
            if (pieces.isEmpty() || pieces.all { isPoint(it) && !(it.lowerClosed && it.upperClosed) }) kind = TrigInequality.Kind.NONE
            else if (pieces.all { isPoint(it) }) kind = TrigInequality.Kind.POINTS
            else if (pieces.size == 1 && pieces[0].let { it.upper.evaluateNumerically() - it.lower.evaluateNumerically() >= periodNumber - 1e-12 && it.lowerClosed && it.upperClosed }) kind = TrigInequality.Kind.ALL
            // A piece that is only a point among intervals (sec u ≤ 1 at 0 and nothing else) is dropped if it is not included
            pieces = pieces.filterNot { isPoint(it) && !(it.lowerClosed && it.upperClosed) }
        }

        val identity = v
        val xVariable: Expression = v
        val text: (String) -> Expression = { TextExpression(it) }
        val forU: Expression
        val forX: Expression
        when (kind) {
            TrigInequality.Kind.ALL -> { forU = text("${v.identifier} ∈ ℝ"); forX = forU }
            TrigInequality.Kind.NONE -> { forU = text(Texts.get(Text.NO_REAL_SOLUTION_TEXT)); forX = forU }
            else -> {
                val uVariable: Expression = function.operand
                fun shifted(e: Expression) = plus(e, canon(shift))
                fun relationOf(closed: Boolean) = if (closed) Relation.LESS_EQUAL else Relation.LESS
                val setK = TextExpression("${k.identifier} ∈ ℤ")
                // x = (u − b)/a: each bound mapped, swapped if a is negative
                fun mapped(e: Expression): Expression = canon(expandProducts(Additions(Multiplications.of(e, invA), solvedX)))
                val negative = invA.evaluateNumerically() < 0
                fun forU(b: Bounds): Expression =
                    if (isPoint(b)) Equals(uVariable, shifted(b.lower))
                    else Between(shifted(b.lower), relationOf(b.lowerClosed), uVariable, relationOf(b.upperClosed), shifted(b.upper))
                fun forX(b: Bounds): Expression {
                    if (isPoint(b)) return Equals(xVariable, mapped(shifted(b.lower)))
                    val lowerU = shifted(b.lower)
                    val upperU = shifted(b.upper)
                    val lowerX = mapped(if (negative) upperU else lowerU)
                    val upperX = mapped(if (negative) lowerU else upperU)
                    val lowerClosed = if (negative) b.upperClosed else b.lowerClosed
                    val upperClosed = if (negative) b.lowerClosed else b.upperClosed
                    return Between(lowerX, relationOf(lowerClosed), xVariable, relationOf(upperClosed), upperX)
                }
                // Several pieces in a period: "a < x < b or c < x < d"
                fun together(parts: List<Expression>): Expression =
                    if (parts.size == 1) parts.single() else Alternatives(parts, inline = true, separator = " or ", group = true)
                forU = Alternatives(listOf(together(pieces.map { forU(it) }), setK), inline = true)
                forX = Alternatives(listOf(together((if (negative) pieces.reversed() else pieces).map { forX(it) }), setK), inline = true)
            }
        }
        if (context.shouldStop) return null
        val unused = identity
        return TrigInequality(
            v, function, c, relation, typedIsolated, k, kind, inverse, exact, forU, forX, periodExpression,
            if (invA == Value.ONE && solvedX.isZero) null else invA to solvedX
        ).also { if (unused != v) return null }
    }
}
