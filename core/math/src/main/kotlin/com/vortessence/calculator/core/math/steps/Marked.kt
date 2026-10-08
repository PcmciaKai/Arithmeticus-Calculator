package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Variable

/**
 * A part of a solution step that is highlighted in color [colorIndex] (see
 * [com.vortessence.calculator.core.math.type.HighlightNode]): the part the next step changes,
 * or with [boxed] the part the previous step produced. Mathematically it is just [inner].
 */
internal class Marked(val inner: Expression, val colorIndex: Int, val boxed: Boolean) : Expression() {

    override val children: List<Expression> get() = listOf(inner)

    override fun copyWithChildren(children: List<Expression>): Expression = Marked(children[0], colorIndex, boxed)

    override fun canonicalize(context: MathContext): Expression = inner.canonicalize(context)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = inner.evaluateNumerically(bindings)

    override val isNegative: Boolean get() = inner.isNegative
    override val isZero: Boolean get() = inner.isZero

    override fun equals(other: Any?): Boolean =
        other is Marked && inner == other.inner && colorIndex == other.colorIndex && boxed == other.boxed

    override fun hashCode(): Int = (inner.hashCode() * 31 + colorIndex) * 31 + boxed.hashCode()

    override fun toString(): String = if (boxed) "[$inner]" else "{$inner}"
}

/** [e] without any highlighting. */
internal fun unmarked(e: Expression): Expression = when {
    e is Marked -> unmarked(e.inner)
    e.children.isEmpty() -> e
    else -> e.copyWithChildren(e.children.map { unmarked(it) })
}
