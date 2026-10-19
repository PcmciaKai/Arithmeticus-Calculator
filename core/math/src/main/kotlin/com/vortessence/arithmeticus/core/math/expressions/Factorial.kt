package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.numbers.BigInt
import com.vortessence.arithmeticus.core.math.numbers.SpecialMath
import kotlin.math.floor

/**
 * n! of a whole number n ≥ 0. With a variable (n! in a sum) it stays symbolic until n is known.
 * Other numbers go by the gamma function, x! = Γ(x + 1): (1/2)! = √π/2.
 */
class Factorial(val operand: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression = Factorial(children.single())

    override fun canonicalize(context: MathContext): Expression {
        val canonical = operand.canonicalize(context)
        if (context.shouldStop) return this
        if (canonical.containsExpressionMatching { it is MatrixExpression }) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }
        if (canonical.variables().isNotEmpty()) {
            return Factorial(canonical)
        }
        val n: Long? = (canonical as? Value)?.let { value ->
            val r = value.rational
            if (r != null) {
                if (r.isFractional || r.isNegative) null
                else if (r.numerator > BigInt(MAX_FACTORIAL)) MAX_FACTORIAL + 1
                else r.numerator.toLong()
            } else {
                val d = value.real.value
                if (d >= 0.0 && d == floor(d)) minOf(d, (MAX_FACTORIAL + 1).toDouble()).toLong() else null
            }
        }
        return when {
            // Not a whole number: Γ(x + 1), which is undefined for the negative whole numbers
            n == null && !canonical.containsImaginaryUnit() && canonical.partWithUnit() == null && !isWholeNumber(canonical) ->
                SpecialFunction(SpecialType.GAMMA, Additions(listOf(canonical, Value.ONE))).canonicalize(context)
            n == null -> {
                context.stopWithError(MathIssue.UNDEFINED)
                this
            }
            n > MAX_FACTORIAL -> {
                context.stopWithError(MathIssue.VALUES_TOO_LARGE)
                this
            }
            else -> Value(BigInt.factorial(n))
        }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val n = operand.evaluateNumerically(bindings)
        if (n.isNaN()) return Double.NaN
        if (n != floor(n)) return SpecialMath.gamma(n + 1.0)
        if (n < 0.0) return Double.NaN
        if (n > MAX_DOUBLE_FACTORIAL) return Double.POSITIVE_INFINITY
        var result = 1.0
        for (k in 2..n.toInt()) result *= k
        return result
    }

    private fun isWholeNumber(e: Expression): Boolean {
        val d = e.evaluateNumerically()
        return d == floor(d)
    }

    override fun equals(other: Any?): Boolean = other is Factorial && operand == other.operand

    override fun hashCode(): Int = operand.hashCode() * 31 + 11

    override fun toString(): String = "($operand)!"

    private companion object {
        const val MAX_FACTORIAL = 3000L
        /** 171! is too large for a double. */
        const val MAX_DOUBLE_FACTORIAL = 170.0
    }
}
