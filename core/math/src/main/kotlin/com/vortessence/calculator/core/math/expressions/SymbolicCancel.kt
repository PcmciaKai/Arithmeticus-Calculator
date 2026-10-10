package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext

/**
 * Cancelling a linear factor of a denominator that has letters in it, e.g. (x² − y²)/(x − y):
 * the denominator a·v + b (a and b without v) has the root r = −b/a, and if the numerator is
 * 0 there (the factor theorem) it is (v − r)·Q, so the fraction is Q/a. The coefficients may be
 * any expressions; they are compared as canonical forms.
 */
internal object SymbolicCancel {

    /** The result of cancelling: numerator = [quotient]·[factor], denominator = [leading]·[factor], factor = v − r. */
    class Cancelled(val factor: Expression, val quotient: Expression, val leading: Expression)

    /** The coefficients of [e] as a polynomial in [v], by power of v, or null if [e] is not one. */
    fun coefficients(e: Expression, v: Variable, context: MathContext): Map<Int, Expression>? {
        val scratch = context.copy()
        val expanded = expandProducts(e).canonicalize(scratch)
        if (scratch.shouldStop) return null
        val parts = mutableMapOf<Int, MutableList<Expression>>()
        for (term in if (expanded is Additions) expanded.operands else listOf(expanded)) {
            var degree = 0
            val rest = mutableListOf<Expression>()
            for (factor in if (term is Multiplications) term.operands else listOf(term)) {
                when {
                    factor == v -> degree += 1
                    factor is Power && factor.base == v -> {
                        val n = (factor.exponent as? Value)?.rational?.takeIf { !it.isFractional && !it.isNegative } ?: return null
                        degree += n.toDouble().toInt()
                    }
                    factor.containsVariable(v) -> return null
                    else -> rest.add(factor)
                }
            }
            parts.getOrPut(degree) { mutableListOf() }.add(Multiplications.of(*rest.toTypedArray()))
        }
        val result = mutableMapOf<Int, Expression>()
        for ((degree, terms) in parts) {
            val c = Additions(terms).canonicalize(scratch)
            if (scratch.shouldStop) return null
            if (!c.isZero) result[degree] = c
        }
        return result
    }

    /** [numerator]/[denominator] with a linear factor in [v] in common, or null. */
    fun cancelLinear(numerator: Expression, denominator: Expression, v: Variable, context: MathContext): Cancelled? {
        val d = coefficients(denominator, v, context) ?: return null
        if (d.keys.maxOrNull() != 1) return null
        val a = d[1] ?: return null
        val b = d[0] ?: Value.ZERO
        val n = coefficients(numerator, v, context) ?: return null
        val degree = n.keys.maxOrNull() ?: return null
        if (degree < 1) return null
        val scratch = context.copy()
        val root = Multiplications(Value.MINUS_ONE, b, Power(a, Value.MINUS_ONE)).canonicalize(scratch)
        if (scratch.shouldStop) return null
        // Synthetic division of the numerator by (v − r): the remainder must be 0
        val quotient = ArrayList<Expression>()
        var carry: Expression = Value.ZERO
        for (k in degree downTo 1) {
            carry = Additions(n[k] ?: Value.ZERO, Multiplications(root, carry)).canonicalize(scratch)
            quotient.add(carry)
        }
        val remainder = Additions(n[0] ?: Value.ZERO, Multiplications(root, carry)).canonicalize(scratch)
        if (scratch.shouldStop || !remainder.isZero) return null
        val q = Additions(quotient.mapIndexed { i, c ->
            val power = degree - 1 - i
            Multiplications.of(c, if (power == 0) Value.ONE else if (power == 1) v else Power(v, Value(power.toLong())))
        }).canonicalize(scratch)
        val factor = Additions(v, Multiplications(Value.MINUS_ONE, root)).canonicalize(scratch)
        if (scratch.shouldStop) return null
        return Cancelled(factor, q, a)
    }
}
