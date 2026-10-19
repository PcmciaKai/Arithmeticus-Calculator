package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.numbers.BigInt
import com.vortessence.arithmeticus.core.math.numbers.Rational
import kotlin.math.floor

/**
 * The remainder of [dividend] ÷ [divisor], with the sign of the divisor (as in mathematics):
 * 7 mod 3 = 1, −7 mod 3 = 2, 7.5 mod 2 = 1.5, π mod 3 = π − 3.
 */
class Modulo(val dividend: Expression, val divisor: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(dividend, divisor)

    override fun copyWithChildren(children: List<Expression>): Expression = Modulo(children[0], children[1])

    override fun canonicalize(context: MathContext): Expression {
        val a = dividend.canonicalize(context)
        if (context.shouldStop) return this
        val b = divisor.canonicalize(context)
        if (context.shouldStop) return this
        if (listOf(a, b).any { e -> e.containsExpressionMatching { it is MatrixExpression } }) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }
        if (a.containsImaginaryUnit() || b.containsImaginaryUnit() || a.containsUnit() || b.containsUnit()) {
            context.stopWithError(MathIssue.UNDEFINED)
            return this
        }
        if (a.variables().isNotEmpty() || b.variables().isNotEmpty()) return Modulo(a, b)
        if (b.isZero) {
            context.stopWithError(MathIssue.DIVISION_BY_ZERO)
            return this
        }

        val ra = (a as? Value)?.rational
        val rb = (b as? Value)?.rational
        if (ra != null && rb != null) {
            val quotient = ra / rb
            return Value(ra - rb * floorOf(quotient))
        }
        // a − b·⌊a/b⌋, which stays exact for constants such as π
        val q = floor(a.evaluateNumerically() / b.evaluateNumerically())
        if (!q.isFinite()) {
            context.stopWithError(MathIssue.UNDEFINED)
            return this
        }
        val wholeQuotient = if (kotlin.math.abs(q) < 1e15) Value(-q.toLong()) else Value(-q)
        return Additions(listOf(a, Multiplications.of(wholeQuotient, b))).canonicalize(context)
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val a = dividend.evaluateNumerically(bindings)
        val b = divisor.evaluateNumerically(bindings)
        return a - b * floor(a / b)
    }

    override fun equals(other: Any?): Boolean = other is Modulo && dividend == other.dividend && divisor == other.divisor

    override fun hashCode(): Int = dividend.hashCode() * 31 + divisor.hashCode() + 13

    override fun toString(): String = "($dividend mod $divisor)"

    private companion object {
        /** ⌊r⌋ (the denominator of a rational is positive). */
        fun floorOf(r: Rational): BigInt {
            val q = r.numerator.value.divide(r.denominator.value)
            val exact = q.multiply(r.denominator.value) == r.numerator.value
            return BigInt(if (r.isNegative && !exact) q.subtract(java.math.BigInteger.ONE) else q)
        }
    }
}
