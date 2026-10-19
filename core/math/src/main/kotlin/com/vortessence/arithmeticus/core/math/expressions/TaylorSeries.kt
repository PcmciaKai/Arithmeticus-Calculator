package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.numbers.Rational

/**
 * The Taylor polynomial of a function around a point: Σ f⁽ᵏ⁾(a)/k! · (x − a)ᵏ up to a given order, found by differentiating
 * k times and putting the point in.
 */
internal object TaylorSeries {

    /** The highest order; the derivatives grow quickly. */
    const val MAX_ORDER = 12

    class Expansion(
        val variable: Variable,
        val point: Expression,
        /** f⁽ᵏ⁾(x) for k = 0 up to the order. */
        val derivatives: List<Expression>,
        /** f⁽ᵏ⁾(a) */
        val values: List<Expression>,
        /** f⁽ᵏ⁾(a)/k! */
        val coefficients: List<Expression>
    ) {
        val order: Int get() = values.size - 1

        /** x − a, or x around 0. */
        val base: Expression get() = if (point.isZero) variable else Additions(variable, point.negate())

        /**
         * The polynomial from the lowest power up, in powers of (x − a) and without the terms that are 0:
         * 1 + x + x²/2 for eˣ. Not canonical (canonicalizing would multiply (x − 1)² out), so only to be written.
         */
        fun toSum(): Expression {
            val terms = coefficients.mapIndexedNotNull { k, c ->
                if (c.isZero) return@mapIndexedNotNull null
                val power: Expression? = when (k) { 0 -> null; 1 -> base; else -> Power(base, Value(k.toLong())) }
                if (power == null) c else Multiplications.of(*listOfNotNull(c.takeIf { it != Value.ONE }, power).toTypedArray())
            }
            return when (terms.size) { 0 -> Value.ZERO; 1 -> terms.single(); else -> Additions(terms) }
        }
    }

    /**
     * The expansion of the canonical [body] in [variable] around [point] up to [order]; null, with the error reported to
     * [context], if a derivative cannot be found or the function or a derivative is not defined at the point (ln(x) at 0).
     */
    fun expand(body: Expression, variable: Variable, point: Expression, order: Int, context: MathContext): Expansion? {
        val derivatives = mutableListOf(body)
        val values = mutableListOf<Expression>()
        val coefficients = mutableListOf<Expression>()
        var factorial = 1L
        for (k in 0..order) {
            if (k > 0) {
                derivatives.add(Derivative(derivatives.last(), variable).canonicalize(context))
                if (context.shouldStop) return null
                factorial *= k
            }
            val value = valueAt(derivatives.last(), variable, point, context) ?: return null
            val coefficient = Multiplications.of(value, Value(Rational.of(1L, factorial))).canonicalize(context.copy())
            values.add(value)
            coefficients.add(coefficient)
        }
        return Expansion(variable, point, derivatives, values, coefficients)
    }

    /**
     * [e] at [point]. Null, with the error reported to [context], if it is not defined there. There is no limit for a removable
     * singularity (sin(x)/x at 0): the limits of the higher derivatives are only found in floating point, which would give a wrong
     * series; an error is better.
     */
    private fun valueAt(e: Expression, variable: Variable, point: Expression, context: MathContext): Expression? {
        val scratch = context.copy()
        val value = e.substituteVariables(mapOf(variable to point)).canonicalize(scratch)
        val usable = !scratch.shouldStop && scratch.issues.isEmpty() &&
            (value.variables().isNotEmpty() || value.containsImaginaryUnit() || value.evaluateNumerically().isFinite())
        if (usable) return value
        context.stopWithError(scratch.issues.firstOrNull() ?: MathIssue.UNDEFINED)
        return null
    }
}
