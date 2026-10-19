package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.numbers.BigInt
import com.vortessence.arithmeticus.core.math.numbers.Rational

/**
 * A mixed number as it was typed, 2 1/3. It is the number 7/3 as soon as it is canonicalized; only
 * the steps of a calculation keep it, to show how it is converted.
 */
internal class MixedNumber(val whole: BigInt, val numerator: BigInt, val denominator: BigInt) : Expression() {

    val value: Rational get() = Rational.of(whole) + Rational.of(numerator, denominator)

    override fun canonicalize(context: MathContext): Expression = Value(value)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = value.toDouble()

    override fun equals(other: Any?): Boolean =
        other is MixedNumber && whole == other.whole && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = (whole.hashCode() * 31 + numerator.hashCode()) * 31 + denominator.hashCode()

    override fun toString(): String = "$whole $numerator/$denominator"
}
