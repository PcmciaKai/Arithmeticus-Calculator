package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.BigInt
import kotlin.math.floor

/** n! of a whole number n ≥ 0. With a variable (n! in a sum) it stays symbolic until n is known. */
class Factorial(val operand: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression = Factorial(children.single())

    override fun canonicalize(context: MathContext): Expression {
        val canonical = operand.canonicalize(context)
        if (context.shouldStop) return this
        if (canonical.variables().isNotEmpty() && !canonical.containsExpressionMatching { it is MatrixExpression }) {
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
            n == null -> {
                // Negative, fractional or symbolic arguments are not supported
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
        if (n.isNaN() || n < 0.0 || n != floor(n)) return Double.NaN
        if (n > MAX_DOUBLE_FACTORIAL) return Double.POSITIVE_INFINITY
        var result = 1.0
        for (k in 2..n.toInt()) result *= k
        return result
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
