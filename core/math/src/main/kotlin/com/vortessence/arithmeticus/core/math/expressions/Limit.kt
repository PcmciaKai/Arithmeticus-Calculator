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
        // floor(x) at 0 is not floor(0): a function that jumps may jump right there, and only the two sides tell
        val jumps = cBody.containsExpressionMatching { (it is NamedFunction && it.jumpsIn(variable)) || (it is Modulo && it.containsVariable(variable)) }
        if (!isInfinite && !jumps) {
            substituted(cBody, cPoint, context)?.let { return it }
            lHopital(cBody, cPoint, context)?.let { return it }
            if (body is Multiplications && cBody !is Multiplications) {
                val scratch = context.copy()
                val factors = body.operands.map { it.canonicalize(scratch) }
                if (!scratch.shouldStop) lHopital(cBody, cPoint, context, factors)?.let { return it }
            }
            if (context.shouldStop) return this
        }
        // A sum term by term, where each term has a limit: x·ln²x − 2x·ln x + 2x at 0 (which numbers alone approach too slowly)
        if (!jumps && cBody is Additions && cBody.variables() == setOf(variable)) {
            termwise(cBody, cPoint, context)?.let { return it }
            if (context.shouldStop) return this
            // The terms as written, where simplifying multiplied one out: (x − 1)·ln|x − 1| − x + 1 at 1, whose first term is 0·∞ as a
            // product, but x·ln|x − 1| − ln|x − 1| multiplied out (two terms without a limit each)
            if (body is Additions && body.operands.size != cBody.operands.size) {
                termwise(body, cPoint, context)?.let { return it }
                if (context.shouldStop) return this
            }
        }

        // x/√|x| at 0: on each side |x| is x or −x, and then it simplifies (to √x from the right)
        if (!isInfinite && !jumps && cPoint.variables().isEmpty()) {
            withoutAbsoluteValues(cBody, cPoint, context)?.let { return it }
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
            NumericOutcome.Undefined, NumericOutcome.Undecided -> {
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

    /**
     * l'Hôpital's rule for 0/0 and ∞/∞: lim f/g = lim f′/g′, applied a few times if needed. A product 0·∞, such as x·ln(x) at 0,
     * is the quotient ln(x)/(1/x) first.
     */
    private fun lHopital(
        e: Expression, at: Expression, context: MathContext,
        /** The factors of a product as typed, where simplifying has multiplied it out: (x − 1)·ln(x − 1) */
        factors: List<Expression>? = (e as? Multiplications)?.operands
    ): Expression? {
        val scratch = context.copy()
        var (numerator, denominator) = splitFraction(e) ?: factors?.let { productAsQuotient(it, at, context) } ?: return null
        repeat(MAX_LHOPITAL_STEPS) {
            val zeroOverZero = substituted(numerator, at, context)?.isZero == true && substituted(denominator, at, context)?.isZero == true
            if (!zeroOverZero && !(blowsUp(numerator, at) && blowsUp(denominator, at))) return null
            numerator = Differentiator.derivative(numerator, variable)?.canonicalize(scratch) ?: return null
            denominator = Differentiator.derivative(denominator, variable)?.canonicalize(scratch) ?: return null
            if (scratch.shouldStop) return null
            val quotient = Multiplications.of(numerator, Power(denominator, Value.MINUS_ONE)).canonicalize(scratch)
            if (scratch.shouldStop) return null
            substituted(quotient, at, context)?.let { return it }
            // Simplified, the quotient may be another 0/0 (−sin²x/(x·cos x) for sin(x)·ln(x)) or 0·∞ (−2x·ln(x) for x·ln²x)
            (splitFraction(quotient) ?: (quotient as? Multiplications)?.let { productAsQuotient(it.operands, at, context) })
                ?.let { (n, d) -> numerator = n; denominator = d }
        }
        return null
    }

    /**
     * The limit with each |u| that is 0 at the point written as u or −u, as it is on the side the limit comes from; from both sides
     * (each its own way) where it comes from both, if they agree. Null if there is no such |u|, or that gives no limit.
     */
    private fun withoutAbsoluteValues(body: Expression, at: Expression, context: MathContext): Expression? {
        val a = at.evaluateNumerically()
        if (!a.isFinite()) return null
        fun vanishing(e: Expression) = e is AbsoluteValue && e.operand.containsVariable(variable) &&
            abs(e.operand.evaluateNumerically(mapOf(variable to a))) <= 1e-12
        if (!body.containsExpressionMatching { vanishing(it) }) return null
        fun from(s: Int): Expression? {
            var unclear = false
            val signed = body.transform { e ->
                if (!vanishing(e)) return@transform e
                val inside = (e as AbsoluteValue).operand
                val sign = inside.evaluateNumerically(mapOf(variable to a + s * 1e-7))
                if (!sign.isFinite() || sign == 0.0) unclear = true
                if (sign > 0) inside else Multiplications.of(Value.MINUS_ONE, inside)
            }
            if (unclear) return null
            val scratch = context.copy()
            val limit = Limit(signed, variable, at, s).canonicalize(scratch)
            if (scratch.shouldStop || limit.containsExpressionMatching { it is Limit } || limit.variables().isNotEmpty()) return null
            return limit
        }
        if (side != 0) return from(side)
        val right = from(1) ?: return null
        val left = from(-1) ?: return null
        val r = right.evaluateNumerically()
        val l = left.evaluateNumerically()
        return right.takeIf { r == l || abs(r - l) <= 1e-12 * max(1.0, abs(r)) }
    }

    /** The sum of the limits of the terms of [sum], if each has a finite one (null otherwise). */
    private fun termwise(sum: Additions, at: Expression, context: MathContext): Expression? {
        val limits = mutableListOf<Expression>()
        // Terms without a finite limit of their own: together they may have one
        val open = mutableListOf<Expression>()
        for (term in sum.operands) {
            val scratch = context.copy()
            val limit = Limit(term, variable, at, side).canonicalize(scratch)
            if (context.shouldStop) return null
            if (scratch.shouldStop || limit.variables().isNotEmpty() || limit.containsExpressionMatching { it is Limit } || !limit.evaluateNumerically().isFinite()) {
                open.add(term)
            } else {
                limits.add(limit)
            }
        }
        if (open.isNotEmpty()) limits.add(withCommonFactor(open, at, context) ?: return null)
        val scratch = context.copy()
        val total = Additions(limits).canonicalize(scratch)
        return if (scratch.shouldStop) null else total
    }

    /**
     * The limit of the sum of [terms] that have none each, with a factor they share taken out: x·ln|x − 1| − ln|x − 1| is
     * (x − 1)·ln|x − 1|, 0·∞ at 1, which l'Hôpital's rule works out. Null if they share no factor, or that has no finite limit.
     */
    private fun withCommonFactor(terms: List<Expression>, at: Expression, context: MathContext): Expression? {
        if (terms.size < 2) return null
        // The product is multiplied out again when it is simplified, and then its terms have no limit each again: not once more
        if (FACTORING.get()) return null
        FACTORING.set(true)
        try {
            return limitWithCommonFactor(terms, at, context)
        } finally {
            FACTORING.set(false)
        }
    }

    private fun limitWithCommonFactor(terms: List<Expression>, at: Expression, context: MathContext): Expression? {
        fun factorsOf(t: Expression) = if (t is Multiplications) t.operands else listOf(t)
        val common = factorsOf(terms.first()).firstOrNull { f -> f.containsVariable(variable) && terms.all { f in factorsOf(it) } } ?: return null
        val rests = terms.map { t ->
            val others = factorsOf(t).toMutableList().also { it.remove(common) }
            if (others.isEmpty()) Value.ONE else Multiplications.of(*others.toTypedArray())
        }
        val scratch = context.copy()
        val limit = Limit(Multiplications(listOf(Additions(rests), common)), variable, at, side).canonicalize(scratch)
        if (scratch.shouldStop || limit.variables().isNotEmpty() || limit.containsExpressionMatching { it is Limit } || !limit.evaluateNumerically().isFinite()) return null
        return limit
    }

    /**
     * A product f·g with a factor f that is 0 at the point and others that grow without bound there, as the quotient g/(1/f) of two
     * that grow without bound: x·ln(x) is ln(x)/x⁻¹. Null for other products.
     */
    private fun productAsQuotient(factors: List<Expression>, at: Expression, context: MathContext): Pair<Expression, Expression>? {
        val vanishing = factors.filter { it.containsVariable(variable) && substituted(it, at, context)?.isZero == true }
        val growing = factors.filter { it !in vanishing }
        if (vanishing.isEmpty() || growing.none { it.containsVariable(variable) && blowsUp(it, at) }) return null
        return Multiplications.of(*growing.toTypedArray()) to Power(Multiplications.of(*vanishing.toTypedArray()), Value.MINUS_ONE)
    }

    /** Whether [e] grows without bound in size as the variable approaches [at] (from a side where it is defined). */
    private fun blowsUp(e: Expression, at: Expression): Boolean {
        val a = at.evaluateNumerically()
        if (!a.isFinite()) return false
        return listOf(1.0, -1.0).any { side ->
            val values = listOf(1e-4, 1e-8, 1e-12).map { h -> abs(e.evaluateNumerically(mapOf(variable to a + side * h))) }
            values.all { it.isFinite() } && values[2] > 10.0 && values[2] > values[1] && values[1] > values[0]
        }
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
            NumericOutcome.Undefined, NumericOutcome.Undecided -> Double.NaN
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
        /** Set while the terms of a sum are tried with a factor taken out, which is not done again inside (see withCommonFactor). */
        val FACTORING: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

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
