package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.CalculationMode
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.numbers.Rational
import com.vortessence.arithmeticus.core.math.numbers.Real
import com.vortessence.arithmeticus.core.math.solvers.NumericIntegrator
import kotlin.math.*

class Integral(
    val lowerBound: Expression,
    val upperBound: Expression,
    val integrand: Expression,
    val variable: Variable
) : Expression() {

    override val children: List<Expression> get() = listOf(lowerBound, upperBound, integrand)

    override fun variables(): Set<Variable> =
        lowerBound.variables() + upperBound.variables() + (integrand.variables() - variable)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 3)
        return Integral(children[0], children[1], children[2], variable)
    }

    override fun canonicalize(context: MathContext): Expression {
        val cLower = lowerBound.canonicalize(context)
        if (context.shouldStop) return this
        val cUpper = upperBound.canonicalize(context)
        if (context.shouldStop) return this
        val cIntegrand = integrand.canonicalize(context)
        if (context.shouldStop) return this
        // Bounds with a unit (from 0 s to 2 s) are numbers of that unit, which the result is multiplied by: ∫ 3 m/s dt is 6 m, not 6
        withUnitBounds(cLower, cUpper, cIntegrand, context)?.let { return it }
        if (context.shouldStop) return this
        // The unit of the integrand is a factor of the integral (∫ 3 m/s is 3 m/s · ∫ 1), which the numbers alone would drop
        val integrandUnit = cIntegrand.partWithUnit()
        if (integrandUnit != null && isUnitOnly(integrandUnit)) {
            val magnitude = Multiplications.of(cIntegrand, Power(integrandUnit, Value.MINUS_ONE)).canonicalize(context)
            if (context.shouldStop) return this
            if (!magnitude.containsUnit()) return Multiplications.of(Integral(cLower, cUpper, magnitude, variable), integrandUnit).canonicalize(context)
        }
        val intExpr = Integral(cLower, cUpper, cIntegrand, variable)

        if (cLower.variables().isEmpty() && cUpper.variables().isEmpty() &&
            cIntegrand.variables().all { it == variable }
        ) {
            val numVal = MathContext.withContext(context) { intExpr.evaluateNumerically() }
            if (context.shouldStop) return this // interrupted (timeout/cancel), not undefined
            if (numVal.isNaN() || numVal.isInfinite()) {
                // sin(x)/x is not defined at its bound 0, but the integral is fine
                removableAtBound(cLower, cUpper, cIntegrand, context)?.let { return it }
                if (context.shouldStop) return this
                // ln|x| on [−1, 1]: not defined at 0 inside, but integrable on both sides of it
                splitAtSingularities(cLower, cUpper, cIntegrand, context)?.let { return it }
                if (context.shouldStop) return this
                // sin(x)/x up to ∞: the numbers do not settle, F(∞) is a limit (Si(∞) = π/2)
                toInfinity(cLower, cUpper, cIntegrand, context)?.let { return it }
                if (context.shouldStop) return this
                // sin(x²) from 0 to 1000: too many swings for the adaptive rule, not for many equal pieces
                val from = cLower.evaluateNumerically()
                val to = cUpper.evaluateNumerically()
                if (from.isFinite() && to.isFinite()) {
                    val oscillating = MathContext.withContext(context) {
                        NumericIntegrator.integrateOscillating({ x -> cIntegrand.evaluateNumerically(mapOf(variable to x)) }, from, to, context)
                    }
                    if (context.shouldStop) return this
                    if (oscillating.isFinite()) return Value(oscillating)
                    // sin(1/x) from 0 to 1: infinitely many swings at a bound, spread out to ∞
                    swingsAtBound(cLower, cUpper, cIntegrand, context)?.let { return it }
                    if (context.shouldStop) return this
                }
                context.stopWithError(MathIssue.UNDEFINED)
                return this
            }
            if (context.mode == CalculationMode.EXACT) {
                exactValue(cLower, cUpper, cIntegrand, numVal, context)?.let { return it }
                if (context.shouldStop) return this
                // Otherwise only recognize results that are (numerically) exact simple fractions,
                // e.g. 1/3. A loose tolerance would turn every irrational result into a fake fraction.
                val rat = Rational.approximateFromDouble(numVal, maxDenominator = MAX_SNAP_DENOMINATOR)
                if (rat != null && abs(rat.toDouble() - numVal) < SNAP_TOLERANCE * max(1.0, abs(numVal))) {
                    return Value(rat)
                }
            }
            return Value(numVal)
        }
        // With letters in the bounds or the integrand: F(b) − F(a), ∫₀ᵗ x dx = t²/2
        return symbolicValue(cLower, cUpper, cIntegrand, context) ?: intExpr
    }

    /**
     * F(b) − F(a) for bounds or an integrand with letters other than the variable. Checked with numbers for those letters, as a
     * pole between the bounds makes it wrong (∫₋₁ᵗ 1/x² dx is no −1/t − 1): null where the numbers do not agree, where there is
     * no antiderivative, or where F has no finite value at a bound.
     */
    private fun symbolicValue(a: Expression, b: Expression, f: Expression, context: MathContext): Expression? {
        if (context.mode != CalculationMode.EXACT) return null
        val antiderivative = Integrator(context).antiderivative(f, variable) ?: return null
        fun at(bound: Expression, side: Int): Expression? {
            if ((bound as? Value)?.real?.isInfinite == true) return Limit(antiderivative, variable, bound)
            val substituted = antiderivative.substituteVariables(mapOf(variable to bound))
            val probe = context.copy()
            substituted.canonicalize(probe)
            if (!probe.shouldStop) return substituted
            return if (bound.variables().isEmpty()) Limit(antiderivative, variable, bound, side) else null
        }
        val scratch = context.copy()
        val upper = at(b, -1) ?: return null
        val lower = at(a, 1) ?: return null
        val difference = Additions(upper, Multiplications.of(Value.MINUS_ONE, lower)).canonicalize(scratch)
        if (scratch.shouldStop || difference.containsExpressionMatching { it is Limit || it is Integral || (it is Value && !it.real.isFinite) }) return null
        // Some numbers for the letters: the integral by numbers must be what F(b) − F(a) gives
        // (An x in a bound is the x outside, ∫₀ˣ x dx = x²/2: it gets a number too)
        val letters = (a.variables() + b.variables() + (f.variables() - variable)).toList()
        for (samples in SAMPLE_LETTER_VALUES) {
            val bindings = letters.withIndex().associate { (i, v) -> v to samples[i % samples.size] }
            val numeric = MathContext.withContext(context) { evaluateNumerically(bindings) }
            val exact = difference.evaluateNumerically(bindings)
            if (!numeric.isFinite() || !exact.isFinite() || abs(numeric - exact) > EXACT_AGREEMENT * max(1.0, abs(numeric))) return null
        }
        return difference
    }

    /**
     * The integral with bounds that have a unit u: the variable stands for a number of u, so it is x·u in the integrand and dx is
     * a number of u as well. Null if the bounds have no unit; an error if they do not have the same dimension.
     */
    private fun withUnitBounds(lower: Expression, upper: Expression, f: Expression, context: MathContext): Expression? {
        if (!lower.containsUnit() && !upper.containsUnit()) return null
        val unit = (upper.partWithUnit() ?: lower.partWithUnit())!!
        // Both are numbers of that unit (a bound of 0 needs none)
        fun magnitude(bound: Expression): Expression? =
            if (bound.containsUnit()) bound.convertToUnit(unit, includeUnitInResult = false, context = context) else bound.takeIf { it.isZero }
        val a = magnitude(lower)
        val b = magnitude(upper)
        if (a == null || b == null) {
            if (!context.shouldStop) context.stopWithError(MathIssue.INVALID_UNIT)
            return this
        }
        val scaled = f.substituteVariables(mapOf(variable to Multiplications.of(variable, unit)))
        return Multiplications.of(Integral(a, b, scaled, variable), unit).canonicalize(context)
    }

    /**
     * The points strictly between [from] and [to] where [f] may not be defined: where a denominator, the argument of a logarithm
     * or of a root is 0 (0 for ln|x|, 1/x, 1/√|x|). Null if one of those cannot be solved; empty if there is none.
     */
    private fun singularPoints(f: Expression, from: Double, to: Double, context: MathContext): List<Expression>? {
        val candidates = mutableSetOf<Expression>()
        fun scan(e: Expression) {
            when (e) {
                is Power -> {
                    val exponent = (e.exponent as? Value)?.rational
                    if (e.base.containsVariable(variable) && exponent != null && (exponent.isNegative || exponent.isFractional)) candidates.add(e.base)
                }
                is Logarithm -> if (e.operand.containsVariable(variable)) candidates.add(e.operand)
                else -> {}
            }
            e.children.forEach { scan(it) }
        }
        scan(f)
        val points = mutableListOf<Expression>()
        for (g in candidates) {
            val scratch = context.copy()
            val roots = Equals(g, Value.ZERO).solveForVariable(variable, scratch)
            if (context.shouldStop) return null
            if (scratch.issues.isNotEmpty()) {
                if (scratch.issues.all { it == MathIssue.NO_REAL_SOLUTION || it == MathIssue.CONTRADICTION } && !scratch.solutionNotFound) continue
                return null
            }
            if (variable in roots) return null
            for (root in roots) {
                val x = root.evaluateNumerically()
                if (!x.isFinite()) return null
                val margin = 1e-12 * max(1.0, abs(to - from))
                if (x > from + margin && x < to - margin && points.none { abs(it.evaluateNumerically() - x) <= margin }) points.add(root)
            }
        }
        return points.sortedBy { it.evaluateNumerically() }.takeIf { it.size <= MAX_SPLIT_POINTS }
    }

    /**
     * The integral as the sum of the pieces between the [singularPoints] inside it, each of which has its point at a bound, where
     * an integrable singularity is dealt with (∫₋₁¹ ln|x| dx = −2). Null if there is no such point or a piece has no value
     * (∫₋₁¹ 1/x dx diverges on both sides).
     */
    private fun splitAtSingularities(a: Expression, b: Expression, f: Expression, context: MathContext): Expression? {
        val from = a.evaluateNumerically()
        val to = b.evaluateNumerically()
        if (!from.isFinite() || !to.isFinite() || from >= to) return null
        val points = singularPoints(f, from, to, context)?.takeIf { it.isNotEmpty() } ?: return null
        val bounds = listOf(a) + points + listOf(b)
        val pieces = bounds.zipWithNext().map { (lo, hi) ->
            val scratch = context.copy()
            val piece = Integral(lo, hi, f, variable).canonicalize(scratch)
            if (scratch.shouldStop || piece is Integral) return null
            piece
        }
        val scratch = context.copy()
        val sum = Additions(pieces).canonicalize(scratch)
        return sum.takeIf { !scratch.shouldStop && it.evaluateNumerically().isFinite() }
    }

    /**
     * F(b) − F(a) with a bound at ±∞, where F(±∞) is a limit, for an integral the numbers do not settle (sin(x)/x, which
     * oscillates more and more slowly to 0). Only without a point where the integrand is not defined, and with F checked against
     * the numeric integral over a finite stretch. Null where the limit does not exist (∫₀^∞ sin(x) dx).
     */
    private fun toInfinity(a: Expression, b: Expression, f: Expression, context: MathContext): Expression? {
        if (context.mode != CalculationMode.EXACT) return null
        val from = a.evaluateNumerically()
        val to = b.evaluateNumerically()
        if (from.isNaN() || to.isNaN() || (from.isFinite() && to.isFinite()) || from >= to) return null
        if (singularPoints(f, from, to, context)?.isEmpty() != true) return null
        // A stretch a step away from a finite bound, where the integrand may have no value (sin(x)/x at 0)
        val start = if (from.isFinite()) from + 1.0 else if (to.isFinite()) to - 11.0 else -5.0
        val end = if (to.isFinite()) to - 1.0 else start + 10.0
        val antiderivative = Integrator(context).antiderivative(f, variable) ?: return null
        val numeric = MathContext.withContext(context) { Integral(Value(Real(start)), Value(Real(end)), f, variable).evaluateNumerically() }
        if (context.shouldStop || !numeric.isFinite()) return null
        val stretch = Additions(antiderivative.substituteVariables(mapOf(variable to Value(Real(end)))), antiderivative.substituteVariables(mapOf(variable to Value(Real(start)))).negate())
            .evaluateNumerically()
        if (!stretch.isFinite() || abs(stretch - numeric) > 1e-8 * max(1.0, abs(numeric))) return null
        val scratch = context.copy()
        return exactValue(a, b, f, null, scratch)?.takeIf { !scratch.shouldStop }
    }

    /**
     * The integral of what swings infinitely often at a finite bound, sin(1/x) at 0: with x = a + 1/u (or b − 1/u at the upper
     * bound) the swings are spread out to ∞, ∫₀¹ sin(1/x) dx = ∫₁^∞ sin(u)/u² du, which [toInfinity] works out. Null where neither
     * bound gives a finite result.
     */
    private fun swingsAtBound(a: Expression, b: Expression, f: Expression, context: MathContext): Expression? {
        val from = a.evaluateNumerically()
        val to = b.evaluateNumerically()
        if (!from.isFinite() || !to.isFinite() || from >= to) return null
        // Only for the bounds: a pole inside (1/x on [−1, 1], tan(x) on [0, π]) would be cancelled away by the substitution
        if (singularPoints(f, from, to, context)?.isEmpty() != true) return null
        val width = to - from
        val middle = MathContext.withContext(context) {
            Integral(Value(Real(from + width / 100)), Value(Real(to - width / 100)), f, variable).evaluateNumerically()
        }
        if (context.shouldStop || !middle.isFinite()) return null
        // u from 1/(b − a) to ∞
        val start = Power(Additions(b, a.negate()), Value.MINUS_ONE)
        val inverse = Power(variable, Value.MINUS_ONE)
        for ((bound, sign) in listOf(a to Value.ONE, b to Value.MINUS_ONE)) {
            // Swings there: the sign changes again and again on the way to the bound (a pole changes it once)
            val edge = bound.evaluateNumerically()
            val direction = sign.evaluateNumerically()
            var changes = 0
            var last = 0.0
            for (i in 0..SWING_SAMPLES) {
                val y = f.evaluateNumerically(mapOf(variable to edge + direction * width * 10.0.pow(-2.0 - 8.0 * i / SWING_SAMPLES)))
                if (!y.isFinite()) continue
                if (y * last < 0) changes++
                if (y != 0.0) last = y
            }
            if (changes < MIN_SWINGS) continue
            val x = Additions(bound, Multiplications.of(sign, inverse))
            val g = Multiplications.of(f.substituteVariables(mapOf(variable to x)), Power(variable, Value(-2L)))
            val scratch = context.copy()
            val g0 = g.canonicalize(scratch)
            if (scratch.shouldStop) continue
            val spread = Integral(start, Value(Double.POSITIVE_INFINITY), g0, variable)
            val value = MathContext.withContext(scratch) { spread.canonicalize(scratch) }
            if (context.shouldStop) return null
            if (!scratch.shouldStop && value !is Integral && value.evaluateNumerically().isFinite()) return value
        }
        return null
    }

    /**
     * The integral where the integrand has no value at a bound (0/0 for sin(x)/x at 0) but a continuous antiderivative F does
     * (Si): F(b) − F(a), if it agrees with the numeric integral a hair inside the bounds. Null where the integral really diverges
     * (F is not defined at the bound or the numeric integral is not finite).
     */
    private fun removableAtBound(a: Expression, b: Expression, f: Expression, context: MathContext): Expression? {
        if (a !is Value || b !is Value) return null
        val from = a.evaluateNumerically()
        val to = b.evaluateNumerically()
        if (!from.isFinite() || !to.isFinite() || from == to) return null
        val inset = (to - from) * 1e-9
        val inner = Integral(Value(Real(from + inset)), Value(Real(to - inset)), f, variable)
        val numeric = MathContext.withContext(context) { inner.evaluateNumerically() }
        if (context.shouldStop) return null
        // (Finite but off where the integrand is steep at the bound, e.g. ln(x)/√x: then checked the other way)
        val exact = (if (numeric.isFinite()) exactValue(a, b, f, numeric, context) else null) ?: integrableAtBound(a, b, f, context)
        if (exact == null) return null
        return if (context.mode == CalculationMode.EXACT) exact else Value(Real(exact.evaluateNumerically()))
    }

    /**
     * F(b) − F(a) for an integrand that grows without bound at a bound but not so fast that the integral diverges, as ln²x at 0:
     * the numbers cannot get close enough to the bound to tell. F is checked on the interval without its outer thousandths, where the
     * numbers can: a pole inside (1/x² on [−1, 1]) shows there. F at the bound is its limit, which is infinite where the integral
     * diverges (ln x for 1/x at 0). Null if any of that does not work out.
     */
    private fun integrableAtBound(a: Value, b: Value, f: Expression, context: MathContext): Expression? {
        val from = a.evaluateNumerically()
        val to = b.evaluateNumerically()
        val margin = (to - from) * 1e-3
        val innerFrom = Value(Real(from + margin))
        val innerTo = Value(Real(to - margin))
        val numeric = MathContext.withContext(context) { Integral(innerFrom, innerTo, f, variable).evaluateNumerically() }
        if (context.shouldStop || !numeric.isFinite()) return null
        val antiderivative = Integrator(context).antiderivative(f, variable) ?: return null
        val scratch = context.copy()
        val inner = Additions(antiderivative.substituteVariables(mapOf(variable to innerTo)), antiderivative.substituteVariables(mapOf(variable to innerFrom)).negate())
            .evaluateNumerically()
        if (!inner.isFinite() || abs(inner - numeric) > 1e-8 * max(1.0, abs(numeric))) return null
        val exact = exactValue(a, b, f, null, scratch) ?: return null
        return exact.takeIf { !scratch.shouldStop }
    }

    /**
     * F(b) − F(a) with the symbolic antiderivative F, e.g. π/4 for ∫₀¹ 1/(1+x²) dx, or null if
     * there is none. F(b) − F(a) is wrong if the integrand has a pole between the bounds (it
     * gives −2 for ∫₋₁¹ 1/x² dx), so the result must agree with the [numeric] value.
     */
    private fun exactValue(a: Expression, b: Expression, f: Expression, numeric: Double?, context: MathContext): Expression? {
        val antiderivative = Integrator(context).antiderivative(f, variable) ?: return null
        val scratch = context.copy()
        // At an infinite bound F(±∞) is a limit, and so is F at a bound where it has no value (x·ln²x at 0), from inside the interval
        fun at(bound: Expression, side: Int): Expression {
            if ((bound as? Value)?.real?.isInfinite == true) return Limit(antiderivative, variable, bound)
            val substituted = antiderivative.substituteVariables(mapOf(variable to bound))
            val probe = context.copy()
            val value = substituted.canonicalize(probe)
            if (!probe.shouldStop && value.variables().isEmpty() && value.evaluateNumerically().isFinite()) return substituted
            return Limit(antiderivative, variable, bound, side)
        }
        val inward = if (b.evaluateNumerically() >= a.evaluateNumerically()) 1 else -1
        val difference = Additions(listOf(at(b, -inward), Multiplications(listOf(Value.MINUS_ONE, at(a, inward))))).canonicalize(scratch)
        if (scratch.shouldStop || difference.variables().isNotEmpty()) return null
        val value = difference.evaluateNumerically()
        if (!value.isFinite() || (numeric != null && abs(value - numeric) > EXACT_AGREEMENT * max(1.0, abs(numeric)))) return null
        return difference
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val a = lowerBound.evaluateNumerically(bindings)
        val b = upperBound.evaluateNumerically(bindings)
        if (a.isNaN() || b.isNaN()) return Double.NaN

        // The integrand sees the outer variables too, with the integration variable bound on top
        val inner = HashMap(bindings)
        fun integrandAt(x: Double): Double {
            inner[this.variable] = x
            return integrand.evaluateNumerically(inner)
        }
        // Adaptive, so it also notices a pole between the bounds (the integral diverges: NaN)
        return NumericIntegrator.integrateImproper(::integrandAt, a, b, MathContext.current())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Integral) return false
        return lowerBound == other.lowerBound &&
                upperBound == other.upperBound &&
                integrand == other.integrand &&
                variable == other.variable
    }

    override fun hashCode(): Int {
        var result = lowerBound.hashCode()
        result = 31 * result + upperBound.hashCode()
        result = 31 * result + integrand.hashCode()
        result = 31 * result + variable.hashCode()
        return result
    }

    override fun toString(): String = "int($lowerBound to $upperBound) $integrand d$variable"

    companion object {
        private const val MAX_SNAP_DENOMINATOR = 100L
        /** At most this many points inside an integral where it is split. */
        private const val MAX_SPLIT_POINTS = 10

        /** Points tried on the way to a bound, from 1/100 to 10⁻¹⁰ of the interval away, and the sign changes that are swings. */
        private const val SWING_SAMPLES = 2000
        private const val MIN_SWINGS = 20
        private const val SNAP_TOLERANCE = 1e-10
        /**
         * How closely an exact result must match the numeric value. Loose, because the numeric
         * value is less accurate where the integrand is steep; a wrong F(b) − F(a) is far off.
         */
        private const val EXACT_AGREEMENT = 1e-4

        /**
         * Values for the letters of an integral when F(b) − F(a) is checked: irregular, some below 1, positive, as a t in a bound
         * usually is (∫₁ᵗ 1/x dx = ln|t| holds for t > 0, ∫₋₁ᵗ 1/x² dx = −1/t − 1 only for t < 0, so that one is not taken).
         */
        private val SAMPLE_LETTER_VALUES = listOf(listOf(1.37, 2.11, 0.63), listOf(2.9, 0.45, 1.8), listOf(0.83, 1.52, 3.3))
    }
}
