package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.CalculationMode
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.solvers.NumericLimit
import com.vortessence.arithmeticus.core.math.solvers.NumericOutcome
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The limit of [body] as [variable] approaches [point] (which may be ±∞). The variable is bound,
 * like an integration variable.
 *
 * Exact where possible: by substituting the point (lim x→2 x² = 4) and by l'Hôpital's rule for
 * 0/0 (lim x→0 sin(x)/x = 1, also with other variables). Otherwise from both sides in floating
 * point, where exact mode recognises simple values such as e for lim x→∞ (1 + 1/x)ˣ.
 */
class Limit(
    val body: Expression,
    val variable: Variable,
    val point: Expression,
    /** From the right (1, x → 0⁺), from the left (−1, x → 0⁻), or from both sides (0). */
    val side: Int = 0
) : Expression() {

    override val children: List<Expression> get() = listOf(body, point)

    override fun variables(): Set<Variable> = (body.variables() - variable) + point.variables()

    override fun copyWithChildren(children: List<Expression>): Expression = Limit(children[0], variable, children[1], side)

    override fun canonicalize(context: MathContext): Expression {
        val cPoint = point.canonicalize(context)
        if (context.shouldStop) return this
        val cBody = body.canonicalize(context)
        if (context.shouldStop) return this
        if (cBody.containsExpressionMatching { it is MatrixExpression } || cPoint.containsExpressionMatching { it is MatrixExpression }) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }
        if (!cBody.containsVariable(variable)) return cBody

        val isInfinite = (cPoint as? Value)?.real?.isInfinite == true
        if (!isInfinite) {
            substituted(cBody, cPoint, context)?.let { return it }
            lHopital(cBody, cPoint, context)?.let { return it }
            if (context.shouldStop) return this
        }

        val symbolic = Limit(cBody, variable, cPoint, side)
        if ((cBody.variables() - variable).isNotEmpty() || cPoint.variables().isNotEmpty()) return symbolic
        if (cBody.containsImaginaryUnit() || cPoint.containsImaginaryUnit()) {
            context.stopWithError(MathIssue.UNDEFINED)
            return this
        }

        return when (val outcome = numericLimit(cBody, cPoint.evaluateNumerically(), emptyMap(), context)) {
            is NumericOutcome.Converges -> {
                if (context.mode == CalculationMode.EXACT) {
                    // Samples far out are tiny but not 0, e.g. sin(x)/x
                    if (abs(outcome.value) <= ZERO_TOLERANCE) return Value.ZERO
                    val tolerance = max(EXACT_TOLERANCE, min(4.0 * outcome.error, MAX_SNAP_TOLERANCE))
                    recognizeExact(outcome.value, tolerance, context)?.let { return it }
                }
                Value(outcome.value)
            }
            is NumericOutcome.Diverges -> Value(if (outcome.sign > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY)
            NumericOutcome.Undefined -> {
                if (!context.shouldStop) context.stopWithError(MathIssue.UNDEFINED)
                this
            }
        }
    }

    /**
     * [e] at variable = [at], if it is defined there (exactly, in a scratch context, so that
     * a division by zero only rules this method out). Null otherwise.
     */
    private fun substituted(e: Expression, at: Expression, context: MathContext): Expression? {
        val scratch = context.copy()
        val result = e.substituteVariables(mapOf(variable to at)).canonicalize(scratch)
        if (scratch.shouldStop) return null
        if (result.containsExpressionMatching { it is Value && !it.real.isFinite }) return null
        if (result.variables().isEmpty() && !result.containsImaginaryUnit() && !result.evaluateNumerically().isFinite()) return null
        return result
    }

    /** l'Hôpital's rule for 0/0: lim f/g = lim f′/g′, applied a few times if needed. */
    private fun lHopital(e: Expression, at: Expression, context: MathContext): Expression? {
        val scratch = context.copy()
        var (numerator, denominator) = splitFraction(e) ?: return null
        repeat(MAX_LHOPITAL_STEPS) {
            if (substituted(numerator, at, context)?.isZero != true) return null
            if (substituted(denominator, at, context)?.isZero != true) return null
            numerator = Differentiator.derivative(numerator, variable)?.canonicalize(scratch) ?: return null
            denominator = Differentiator.derivative(denominator, variable)?.canonicalize(scratch) ?: return null
            if (scratch.shouldStop) return null
            val quotient = Multiplications.of(numerator, Power(denominator, Value.MINUS_ONE)).canonicalize(scratch)
            if (scratch.shouldStop) return null
            substituted(quotient, at, context)?.let { return it }
        }
        return null
    }

    /** (numerator, denominator) of a product with factors such as (x − 1)⁻¹; null without denominator. */
    private fun splitFraction(e: Expression): Pair<Expression, Expression>? {
        val factors = if (e is Multiplications) e.operands else listOf(e)
        val numerator = mutableListOf<Expression>()
        val denominator = mutableListOf<Expression>()
        for (factor in factors) {
            val exponent = (factor as? Power)?.exponent as? Value
            if (factor is Power && exponent != null && exponent.isNegative && factor.containsVariable(variable)) {
                val positive = exponent.negate()
                denominator.add(if (positive == Value.ONE) factor.base else Power(factor.base, positive))
            } else {
                numerator.add(factor)
            }
        }
        if (denominator.isEmpty()) return null
        return Multiplications.of(*numerator.toTypedArray()) to Multiplications.of(*denominator.toTypedArray())
    }

    /** The limit from both sides (from one side for ±∞ or where only one side is defined). */
    private fun numericLimit(e: Expression, a: Double, bindings: Map<Variable, Double>, context: MathContext?): NumericOutcome {
        fun f(x: Double) = e.evaluateNumerically(bindings + (variable to x))
        if (a.isNaN()) return NumericOutcome.Undefined
        if (a.isInfinite()) {
            val sign = if (a > 0.0) 1.0 else -1.0
            return NumericLimit.oneSided({ h -> f(sign / h) }, INFINITY_START, INFINITY_STEPS, context) ?: NumericOutcome.Undefined
        }
        val h0 = if (abs(a) <= 8.0) FINITE_START else abs(a) / 64.0
        val right = NumericLimit.oneSided({ h -> f(a + h) }, h0, FINITE_STEPS, context)
        val left = NumericLimit.oneSided({ h -> f(a - h) }, h0, FINITE_STEPS, context)
        // x → a⁺ looks from one side only
        if (side > 0) return right ?: NumericOutcome.Undefined
        if (side < 0) return left ?: NumericOutcome.Undefined
        return when {
            right == null -> left ?: NumericOutcome.Undefined
            left == null -> right
            right is NumericOutcome.Converges && left is NumericOutcome.Converges -> {
                val scale = max(1.0, max(abs(left.value), abs(right.value)))
                if (abs(left.value - right.value) <= max(1e-6 * scale, 10.0 * (left.error + right.error))) {
                    if (left.error < right.error) left else right
                } else {
                    NumericOutcome.Undefined
                }
            }
            right is NumericOutcome.Diverges && left is NumericOutcome.Diverges && right.sign == left.sign -> right
            // E.g. 1/x at 0: +∞ from the right, −∞ from the left
            else -> NumericOutcome.Undefined
        }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        return when (val outcome = numericLimit(body, point.evaluateNumerically(bindings), bindings, MathContext.current())) {
            is NumericOutcome.Converges -> outcome.value
            is NumericOutcome.Diverges -> outcome.sign * Double.POSITIVE_INFINITY
            NumericOutcome.Undefined -> Double.NaN
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Limit) return false
        return body == other.body && variable == other.variable && point == other.point && side == other.side
    }

    override fun hashCode(): Int = ((body.hashCode() * 31 + variable.hashCode()) * 31 + point.hashCode()) * 31 + side

    override fun toString(): String = "lim($variable->$point${if (side > 0) "+" else if (side < 0) "-" else ""}) $body"

    private companion object {
        const val MAX_LHOPITAL_STEPS = 4
        const val FINITE_START = 0.125
        const val FINITE_STEPS = 18
        /** x from 8 up to about 7·10¹⁰. */
        const val INFINITY_START = 0.125
        const val INFINITY_STEPS = 34
        const val EXACT_TOLERANCE = 1e-10
        const val MAX_SNAP_TOLERANCE = 1e-7
        const val ZERO_TOLERANCE = 1e-9
    }
}
