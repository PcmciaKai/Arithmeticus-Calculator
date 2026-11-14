package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.CalculationMode
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.numbers.Rational
import com.vortessence.arithmeticus.core.math.solvers.NumericOutcome
import com.vortessence.arithmeticus.core.math.solvers.SeriesSummation
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * The sum Σ (or with [isProduct] the product ∏) of [body] for the [index] running through the
 * whole numbers from [lower] to [upper]; [upper] may be ∞. Like an integration variable, the
 * index is bound: it is not a free variable of the sum.
 *
 * Short ranges are added up exactly (also with other variables: Σₙ₌₁³ n·x = 6x), long ones and
 * infinite series in floating point, where exact mode recognises simple values such as π²/6.
 */
class Series(
    val isProduct: Boolean,
    val index: Variable,
    val lower: Expression,
    val upper: Expression,
    val body: Expression
) : Expression() {

    override val children: List<Expression> get() = listOf(lower, upper, body)

    override fun variables(): Set<Variable> = lower.variables() + upper.variables() + (body.variables() - index)

    override fun copyWithChildren(children: List<Expression>): Expression =
        Series(isProduct, index, children[0], children[1], children[2])

    private val identity: Value get() = if (isProduct) Value.ONE else Value.ZERO

    override fun canonicalize(context: MathContext): Expression {
        val cLower = lower.canonicalize(context)
        if (context.shouldStop) return this
        val cUpper = upper.canonicalize(context)
        if (context.shouldStop) return this
        val cBody = body.canonicalize(context)
        if (context.shouldStop) return this
        if (listOf(cLower, cUpper, cBody).any { e -> e.containsExpressionMatching { it is MatrixExpression } }) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }
        val symbolic = Series(isProduct, index, cLower, cUpper, cBody)
        if (cLower.variables().isNotEmpty() || cUpper.variables().isNotEmpty()) return symbolic

        val from = wholeNumber(cLower)
        val isInfinite = cUpper is Value && cUpper.real.value == Double.POSITIVE_INFINITY
        val to = if (isInfinite) null else wholeNumber(cUpper)
        if (from == null || (!isInfinite && to == null)) {
            context.stopWithError(MathIssue.INVALID_BOUNDS)
            return this
        }
        val freeVariables = cBody.variables() - index

        if (to != null) {
            val count = to - from + 1
            if (count <= 0) return identity
            if (!cBody.containsVariable(index)) {
                // A constant body: n·body or body^n
                return (if (isProduct) Power(cBody, Value(count)) else Multiplications.of(Value(count), cBody)).canonicalize(context)
            }
            if (count <= MAX_EXACT_TERMS || cBody.containsImaginaryUnit()) {
                val exact = exactResult(cBody, from, to, context)
                if (context.shouldStop) return this
                // Keep exact results readable: Σ √n over many terms is better as a decimal
                if (freeVariables.isNotEmpty() || expressionSize(exact) <= MAX_EXACT_SIZE || cBody.containsImaginaryUnit()) {
                    return exact
                }
            }
            if (freeVariables.isNotEmpty()) return symbolic
            return finiteValue(numericFinite(cBody, from, to, emptyMap(), context), context)
        }

        if (freeVariables.isNotEmpty()) return symbolic
        if (cBody.containsImaginaryUnit()) {
            context.stopWithError(MathIssue.UNDEFINED)
            return this
        }
        if (!cBody.containsVariable(index)) return constantInfinite(cBody, context)
        return outcomeValue(numericInfinite(cBody, from, emptyMap(), context), context)
    }

    /** All terms added (multiplied) and simplified. */
    private fun exactResult(cBody: Expression, from: Long, to: Long, context: MathContext): Expression {
        val terms = (from..to).map { k ->
            if (context.shouldStop) return this
            cBody.substituteVariables(mapOf(index to Value(k)))
        }
        return (if (isProduct) Multiplications(terms) else Additions(terms)).canonicalize(context)
    }

    /** Σ or ∏ of a body without the index up to ∞: ∞, 0, or undefined. */
    private fun constantInfinite(cBody: Expression, context: MathContext): Expression {
        val v = cBody.evaluateNumerically()
        return when {
            !isProduct && v == 0.0 -> Value.ZERO
            !isProduct -> Value(if (v > 0.0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY)
            v == 1.0 -> Value.ONE
            abs(v) < 1.0 -> Value.ZERO
            v > 1.0 -> Value(Double.POSITIVE_INFINITY)
            else -> {
                context.stopWithError(MathIssue.DIVERGES)
                this
            }
        }
    }

    private fun finiteValue(value: Double, context: MathContext): Expression {
        if (context.shouldStop) return this
        if (value.isNaN()) {
            context.stopWithError(MathIssue.UNDEFINED)
            return this
        }
        if (value.isInfinite()) {
            context.stopWithError(MathIssue.VALUES_TOO_LARGE)
            return this
        }
        return Value(value)
    }

    private fun outcomeValue(outcome: NumericOutcome, context: MathContext): Expression {
        if (context.shouldStop) return this
        return when (outcome) {
            is NumericOutcome.Converges -> {
                if (context.mode == CalculationMode.EXACT) {
                    recognizeExact(outcome.value, EXACT_TOLERANCE, context)?.let { return it }
                }
                Value(outcome.value)
            }
            is NumericOutcome.Diverges -> {
                // An infinite product that tends to 0 has the value 0
                if (isProduct && outcome.sign < 0) Value.ZERO
                else Value(if (outcome.sign > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY)
            }
            NumericOutcome.Undefined -> {
                context.stopWithError(MathIssue.DIVERGES)
                this
            }
            // Not "does not converge": that is not known
            NumericOutcome.Undecided -> {
                context.stopWithError(MathIssue.SERIES_UNDECIDED)
                this
            }
        }
    }

    private fun term(cBody: Expression, k: Long, bindings: Map<Variable, Double>): Double =
        cBody.evaluateNumerically(bindings + (index to k.toDouble()))

    private fun numericFinite(cBody: Expression, from: Long, to: Long, bindings: Map<Variable, Double>, context: MathContext?): Double {
        var result = identity.real.value
        var compensation = 0.0
        var k = from
        while (k <= to) {
            if ((k - from) % 4096 == 0L && context?.shouldStop == true) return Double.NaN
            val t = term(cBody, k, bindings)
            if (isProduct) {
                result *= t
            } else {
                // Neumaier summation
                val s = result + t
                compensation += if (abs(result) >= abs(t)) (result - s) + t else (t - s) + result
                result = s
            }
            k++
        }
        return result + compensation
    }

    /**
     * The sum of terms f(n) that are of one sign and get smaller (from some n on) by the integral test: it converges where ∫ f
     * does up to ∞ (Σ ln(n)/n², Σ 1/(n·ln²n)) and diverges where that is infinite (Σ 1/(n·ln n), like ln(ln n)). The value is
     * the terms up to N and the rest by Euler–Maclaurin, F(∞) − F(N) + f(N)/2 − f′(N)/12 + f‴(N)/720 with the antiderivative F.
     * Null where there is no antiderivative, F(∞) has no value, or the terms are not of that kind.
     */
    private fun integralTest(cBody: Expression, from: Long, bindings: Map<Variable, Double>, context: MathContext?): NumericOutcome? {
        if (isProduct || bindings.isNotEmpty() || context == null || context.mode != CalculationMode.EXACT || cBody.variables() != setOf(index)) return null
        // Of one sign, and smaller and smaller in size, far out
        val samples = listOf(1e2, 1e3, 1e4, 1e5, 1e6).map { cBody.evaluateNumerically(mapOf(index to it)) }
        if (samples.any { !it.isFinite() || it == 0.0 } || samples.map { it > 0.0 }.distinct().size != 1) return null
        if (samples.zipWithNext().any { (a, b) -> abs(b) >= abs(a) }) return null
        val sign = if (samples.first() > 0.0) 1 else -1
        val scratch = context.copy()
        val n = maxOf(from, 0L) + INTEGRAL_TEST_TERMS
        val at = mapOf(index to n.toDouble())
        val antiderivative = Integrator(scratch).antiderivative(cBody, index)
        if (scratch.shouldStop) return null
        // ∫ from N to ∞ of the terms: F(∞) − F(N), or by numbers where there is no F
        val tail: Double = if (antiderivative != null) {
            val limitContext = context.copy()
            val atInfinity = Limit(antiderivative, index, Value(Double.POSITIVE_INFINITY)).canonicalize(limitContext)
            val limit = if (!limitContext.shouldStop && !atInfinity.containsExpressionMatching { it is Limit } && atInfinity.variables().isEmpty()) {
                atInfinity.evaluateNumerically()
            } else {
                // −1/ln(n) and ln(ln n) get to their limit too slowly for the numbers to see it; at ∞ itself they are 0 and ∞ (what
                // is not clear there, such as ∞ − ∞ or ∞/∞, is NaN)
                antiderivative.evaluateNumerically(mapOf(index to Double.POSITIVE_INFINITY))
            }
            if (limit.isNaN()) return null
            if (limit.isInfinite()) return NumericOutcome.Diverges(sign)
            limit - antiderivative.evaluateNumerically(at)
        } else {
            // Numbers cannot tell a tail that converges from one that does not (1/(n·ln n)): only for terms that fall at least as
            // fast as 1/(n·ln²n), whose sum converges, i.e. |f(n)|·n·ln²n gets smaller far out (ln(n)/(n² + 1))
            val bounded = listOf(1e3, 1e4, 1e5, 1e6).map { x -> abs(cBody.evaluateNumerically(mapOf(index to x))) * x * ln(x) * ln(x) }
            if (bounded.any { !it.isFinite() } || bounded.zipWithNext().any { (a, b) -> b >= a }) return null
            val numeric = MathContext.withContext(context) {
                com.vortessence.arithmeticus.core.math.solvers.NumericIntegrator.integrateImproper(
                    { x -> cBody.evaluateNumerically(mapOf(index to x)) }, n.toDouble(), Double.POSITIVE_INFINITY, context
                )
            }
            if (!numeric.isFinite()) return null
            numeric
        }

        val first = Differentiator.derivative(cBody, index)?.canonicalize(scratch) ?: return null
        val third = Differentiator.derivative(Differentiator.derivative(first, index) ?: return null, index)?.canonicalize(scratch) ?: return null
        if (scratch.shouldStop) return null
        val rest = tail + cBody.evaluateNumerically(at) / 2.0 -
            first.evaluateNumerically(at) / 12.0 + third.evaluateNumerically(at) / 720.0
        var sum = 0.0
        var compensation = 0.0
        for (k in from until n) {
            if (k % 1024 == 0L && context.shouldStop) return null
            val t = term(cBody, k, bindings)
            if (!t.isFinite()) return null
            val s = sum + t
            compensation += if (abs(sum) >= abs(t)) (sum - s) + t else (t - s) + sum
            sum = s
        }
        val total = sum + compensation + rest
        if (!total.isFinite()) return null
        return NumericOutcome.Converges(total, abs(third.evaluateNumerically(at)) / 720.0 + 1e-14 * abs(total))
    }

    private fun numericInfinite(cBody: Expression, from: Long, bindings: Map<Variable, Double>, context: MathContext?): NumericOutcome {
        if (!isProduct) {
            val outcome = SeriesSummation.sumToInfinity({ term(cBody, it, bindings) }, from, context)
            // Too slow for the numbers (a logarithm in the terms): the integral says it
            if (outcome == NumericOutcome.Undecided) integralTest(cBody, from, bindings, context)?.let { return it }
            return outcome
        }
        // ∏ aₙ = e^(Σ ln aₙ); a zero factor makes it 0, negative factors are not supported
        var sawNonPositive = false
        var sawZero = false
        val outcome = SeriesSummation.sumToInfinity({ k ->
            val a = term(cBody, k, bindings)
            if (a == 0.0) sawZero = true
            if (a <= 0.0) sawNonPositive = true
            if (a > 0.0) ln(a) else 0.0
        }, from, context)
        return when {
            sawZero -> NumericOutcome.Converges(0.0, 0.0)
            sawNonPositive -> NumericOutcome.Undefined
            outcome is NumericOutcome.Converges -> NumericOutcome.Converges(exp(outcome.value), exp(outcome.value) * outcome.error)
            else -> outcome
        }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val from = lower.evaluateNumerically(bindings)
        val to = upper.evaluateNumerically(bindings)
        if (from.isNaN() || to.isNaN() || from % 1.0 != 0.0 || from.isInfinite()) return Double.NaN
        val context = MathContext.current()
        if (to == Double.POSITIVE_INFINITY) {
            return when (val outcome = numericInfinite(body, from.toLong(), bindings, context)) {
                is NumericOutcome.Converges -> outcome.value
                is NumericOutcome.Diverges -> if (isProduct && outcome.sign < 0) 0.0 else outcome.sign * Double.POSITIVE_INFINITY
                NumericOutcome.Undefined, NumericOutcome.Undecided -> Double.NaN
            }
        }
        if (to % 1.0 != 0.0) return Double.NaN
        return numericFinite(body, from.toLong(), to.toLong(), bindings, context)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Series) return false
        return isProduct == other.isProduct && index == other.index && lower == other.lower &&
            upper == other.upper && body == other.body
    }

    override fun hashCode(): Int =
        (((isProduct.hashCode() * 31 + index.hashCode()) * 31 + lower.hashCode()) * 31 + upper.hashCode()) * 31 + body.hashCode()

    override fun toString(): String = "${if (isProduct) "prod" else "sum"}($index=$lower to $upper) $body"

    private companion object {
        /** Up to this many terms are added exactly. */
        const val MAX_EXACT_TERMS = 500L
        /** Larger exact results (of irrational terms) are shown as a decimal instead. */
        const val MAX_EXACT_SIZE = 40
        const val EXACT_TOLERANCE = 1e-10
        /** How many terms the integral test adds up before the rest is worked out from the integral. */
        const val INTEGRAL_TEST_TERMS = 1000L

        /** The whole number [e] stands for, or null (also for ∞). */
        fun wholeNumber(e: Expression): Long? {
            val value = e as? Value ?: return null
            val r: Rational? = value.rational
            if (r != null) return if (r.isFractional || r.numerator.value.bitLength() > 62) null else r.numerator.toLong()
            val d = value.real.value
            return if (d.isFinite() && d % 1.0 == 0.0 && abs(d) < 1e15) d.toLong() else null
        }
    }
}
