package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Variable

/**
 * Equations that hold one or the other, e.g. x + 1 = 2 or x + 1 = −2 after taking the square
 * root: one per line, or with [inline] separated by commas ("x = 1, x = −3", as results are).
 */
internal class Alternatives(val equations: List<Expression>, val inline: Boolean = false, val separator: String = ", ") : Expression() {

    override val children: List<Expression> get() = equations

    override fun copyWithChildren(children: List<Expression>): Expression = Alternatives(children, inline, separator)

    override fun canonicalize(context: MathContext): Expression = Alternatives(equations.map { it.canonicalize(context) }, inline, separator)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = Double.NaN

    override fun equals(other: Any?): Boolean = other is Alternatives && equations == other.equations && inline == other.inline && separator == other.separator

    override fun hashCode(): Int = equations.hashCode() * 31 + inline.hashCode()

    override fun toString(): String = equations.joinToString(" or ")
}

/** A line of text in a solution, e.g. "true" for an equation that holds for every value. */
internal class TextExpression(val text: String) : Expression() {

    override fun canonicalize(context: MathContext): Expression = this

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = Double.NaN

    override fun equals(other: Any?): Boolean = other is TextExpression && text == other.text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text
}

/**
 * A product written out factor by factor, with every 1 and every sign, e.g. 4·1·(−1) in the
 * discriminant b² − 4·a·c (a product would be simplified for display).
 */
internal class WrittenOut(val factors: List<Expression>) : Expression() {

    override val children: List<Expression> get() = factors

    override fun copyWithChildren(children: List<Expression>): Expression = WrittenOut(children)

    override fun canonicalize(context: MathContext): Expression =
        com.vortessence.calculator.core.math.expressions.Multiplications(factors).canonicalize(context)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = factors.fold(1.0) { p, f -> p * f.evaluateNumerically(bindings) }

    override fun equals(other: Any?): Boolean = other is WrittenOut && factors == other.factors

    override fun hashCode(): Int = factors.hashCode()

    override fun toString(): String = factors.joinToString(" · ")
}

/**
 * A fraction written as it is, 3/6 or (1 + 2)/6, which is not reduced or calculated until a step
 * does it (a number 1/2 would stand for the reduced value).
 */
internal class LiteralFraction(val numerator: Expression, val denominator: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(numerator, denominator)

    override fun copyWithChildren(children: List<Expression>): Expression = LiteralFraction(children[0], children[1])

    override fun canonicalize(context: MathContext): Expression =
        com.vortessence.calculator.core.math.expressions.Multiplications.of(
            numerator, com.vortessence.calculator.core.math.expressions.Power(denominator, com.vortessence.calculator.core.math.expressions.Value.MINUS_ONE)
        ).canonicalize(context)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double =
        numerator.evaluateNumerically(bindings) / denominator.evaluateNumerically(bindings)

    override val isNegative: Boolean get() = numerator.isNegative != denominator.isNegative
    override val isZero: Boolean get() = numerator.isZero

    override fun equals(other: Any?): Boolean = other is LiteralFraction && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = numerator.hashCode() * 31 + denominator.hashCode() + 7

    override fun toString(): String = "($numerator)/($denominator)"
}
