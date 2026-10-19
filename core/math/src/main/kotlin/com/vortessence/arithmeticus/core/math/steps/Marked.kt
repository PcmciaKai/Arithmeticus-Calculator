package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Variable

/**
 * A part of a solution step that is highlighted in color [colorIndex] (see
 * [com.vortessence.arithmeticus.core.math.type.HighlightNode]): the part the next step changes,
 * or with [boxed] the part the previous step produced. Mathematically it is just [inner].
 */
internal class Marked(
    val inner: Expression,
    val colorIndex: Int,
    val boxed: Boolean,
    /** A value written in for a variable: kept as a factor even if it is 1 (or 0), as in 3·1. */
    val keep: Boolean = false
) : Expression() {

    override val children: List<Expression> get() = listOf(inner)

    override fun copyWithChildren(children: List<Expression>): Expression = Marked(children[0], colorIndex, boxed, keep)

    override fun canonicalize(context: MathContext): Expression = inner.canonicalize(context)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = inner.evaluateNumerically(bindings)

    override val isNegative: Boolean get() = inner.isNegative
    override val isZero: Boolean get() = inner.isZero

    override fun equals(other: Any?): Boolean =
        other is Marked && inner == other.inner && colorIndex == other.colorIndex && boxed == other.boxed && keep == other.keep

    override fun hashCode(): Int = (inner.hashCode() * 31 + colorIndex) * 31 + boxed.hashCode()

    override fun toString(): String = if (boxed) "[$inner]" else "{$inner}"
}

/** [e] without any highlighting. */
internal fun unmarked(e: Expression): Expression = when {
    e is Marked -> unmarked(e.inner)
    e.children.isEmpty() -> e
    else -> e.copyWithChildren(e.children.map { unmarked(it) })
}

/**
 * A power that a solution step shows as a power, e.g. x^(1/2) or x^(−1), where it is otherwise
 * written as a root or a fraction (√x, 1/x): the power rule needs it in this form. A last step
 * writes it as usual again ([StepRule.POWER_AS_ROOT], [StepRule.POWER_AS_FRACTION]).
 * Around a fraction, e.g. the ½ in ½·x^(−½), it keeps the fraction in front of the power.
 */
internal class LiteralPower(val inner: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(inner)

    override fun copyWithChildren(children: List<Expression>): Expression = LiteralPower(children[0])

    override fun canonicalize(context: MathContext): Expression = inner.canonicalize(context)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = inner.evaluateNumerically(bindings)

    override val isNegative: Boolean get() = inner.isNegative
    override val isZero: Boolean get() = inner.isZero

    override fun equals(other: Any?): Boolean = other is LiteralPower && inner == other.inner

    override fun hashCode(): Int = inner.hashCode() * 17

    override fun toString(): String = "literal($inner)"
}
