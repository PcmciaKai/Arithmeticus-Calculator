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
        if (function.type !in listOf(TrigType.SIN, TrigType.COS, TrigType.TAN) || (function.angleUnit ?: unit) != unit) return null
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
        val periodExpression = if (function.type == TrigType.TAN) pi else turn
        val periodNumber = if (function.type == TrigType.TAN) halfTurnNumber else 2 * halfTurnNumber
        val shift = Multiplications.of(periodExpression, k)

        fun canon(e: Expression): Expression = e.canonicalize(context.copy())
        fun plus(a: Expression, b: Expression) = canon(Additions(a, b))
        fun minus(a: Expression) = canon(a.negate())
        val quarter: Expression = canon(Multiplications.of(pi, Value.HALF))

        // The solution inside one period, as bounds (and whether each is included)
        class Bounds(val lower: Expression, val upper: Expression, val lowerClosed: Boolean, val upperClosed: Boolean)
        val strict = relation.strict
        val greater = relation.greater
        var inverse: Expression = TrigonometricFunction(
            when (function.type) { TrigType.SIN -> TrigType.ASIN; TrigType.COS -> TrigType.ACOS; else -> TrigType.ATAN }, c, unit
        )
        var exact: Expression = inverse
        var bounds: Bounds? = null
        var always = false
        var never = false
        if (function.type != TrigType.TAN && abs(number) > 1 + 1e-12) {
            // |sin| ≤ 1: sin u > 2 never, sin u < 2 always
            val above = number > 0
            if (greater == above) never = true else always = true
        } else {
            val atan = number
            exact = canon(inverse).takeIf { e -> !(e is Value && e.rational == null) } ?: inverse
            val alpha = exact
            bounds = when (function.type) {
                TrigType.SIN ->
                    if (greater) Bounds(alpha, plus(pi, minus(alpha)), !strict, !strict)
                    else Bounds(plus(pi, minus(alpha)), plus(turn, alpha), !strict, !strict)
                TrigType.COS ->
                    if (greater) Bounds(minus(alpha), alpha, !strict, !strict)
                    else Bounds(alpha, plus(turn, minus(alpha)), !strict, !strict)
                else ->
                    if (greater) Bounds(alpha, quarter, !strict, false)
                    else Bounds(minus(quarter), alpha, false, !strict)
            }
            if (atan.isNaN()) return null
        }
        var kind = TrigInequality.Kind.INTERVAL
        if (never) kind = TrigInequality.Kind.NONE
        if (always) kind = TrigInequality.Kind.ALL
        if (bounds != null) {
            val width = bounds.upper.evaluateNumerically() - bounds.lower.evaluateNumerically()
            if (width <= 1e-12) kind = if (bounds.lowerClosed && bounds.upperClosed) TrigInequality.Kind.POINTS else TrigInequality.Kind.NONE
            else if (width >= periodNumber - 1e-12 && bounds.lowerClosed && bounds.upperClosed) kind = TrigInequality.Kind.ALL
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
                val b = bounds!!
                val uVariable: Expression = function.operand
                fun shifted(e: Expression) = plus(e, canon(shift))
                val lowerU = shifted(b.lower)
                val upperU = shifted(b.upper)
                fun relationOf(closed: Boolean) = if (closed) Relation.LESS_EQUAL else Relation.LESS
                val setK = TextExpression("${k.identifier} ∈ ℤ")
                forU = if (kind == TrigInequality.Kind.POINTS) Alternatives(listOf(Equals(uVariable, lowerU), setK), inline = true)
                else Alternatives(listOf(Between(lowerU, relationOf(b.lowerClosed), uVariable, relationOf(b.upperClosed), upperU), setK), inline = true)
                // x = (u − b)/a: each bound mapped, swapped if a is negative
                fun mapped(e: Expression): Expression = canon(expandProducts(Additions(Multiplications.of(e, invA), solvedX)))
                val negative = invA.evaluateNumerically() < 0
                val lowerX = mapped(if (negative) upperU else lowerU)
                val upperX = mapped(if (negative) lowerU else upperU)
                val lowerClosed = if (negative) b.upperClosed else b.lowerClosed
                val upperClosed = if (negative) b.lowerClosed else b.upperClosed
                forX = if (kind == TrigInequality.Kind.POINTS) Alternatives(listOf(Equals(xVariable, mapped(lowerU)), setK), inline = true)
                else Alternatives(listOf(Between(lowerX, relationOf(lowerClosed), xVariable, relationOf(upperClosed), upperX), setK), inline = true)
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
