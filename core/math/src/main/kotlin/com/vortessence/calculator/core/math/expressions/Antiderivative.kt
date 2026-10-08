package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import kotlin.math.abs
import kotlin.math.max

/**
 * The indefinite integral ∫ [integrand] d[variable], computed symbolically, e.g. ∫ x² dx = x³/3.
 * The constant of integration is left out (the result is the antiderivative with C = 0); the
 * display adds "+ C". Inside, the variable is bound like in a [Derivative] without point.
 */
class Antiderivative(
    val integrand: Expression,
    val variable: Variable
) : Expression() {

    override val children: List<Expression> get() = listOf(integrand)

    override fun copyWithChildren(children: List<Expression>): Expression = Antiderivative(children.single(), variable)

    override fun canonicalize(context: MathContext): Expression {
        val cIntegrand = integrand.canonicalize(context)
        if (context.shouldStop) return this
        if (cIntegrand.containsExpressionMatching { it is MatrixExpression }) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }
        val result = Integrator(context).antiderivative(cIntegrand, variable)
        if (context.shouldStop) return this
        if (result == null) {
            context.stopWithError(MathIssue.CANT_INTEGRATE)
            return this
        }
        return result
    }

    /** The symbolic antiderivative, for numeric evaluation (e.g. plotting); null if there is none. */
    private val symbolic: Expression? by lazy {
        val context = MathContext.current().copy()
        canonicalize(context).takeUnless { context.shouldStop || it is Antiderivative }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double =
        symbolic?.evaluateNumerically(bindings) ?: Double.NaN

    override fun equals(other: Any?): Boolean =
        other is Antiderivative && integrand == other.integrand && variable == other.variable

    override fun hashCode(): Int = integrand.hashCode() * 31 + variable.hashCode()

    override fun toString(): String = "int($integrand) d$variable"
}

/**
 * Rule-based symbolic integration of canonical expressions. Covers the usual textbook cases:
 * - sums and constant factors,
 * - powers, exponentials, logarithms and trigonometric functions of a linear argument,
 * - 1/(p + qx²) and 1/√(p − qx²) (atan / asin, only with angles in radians),
 * - substitution where the integrand is f(u(x))·u'(x) up to a constant factor,
 * - integration by parts for polynomial × exponential / sin / cos, and polynomial × ln / atan / ...
 * - products and powers of sums, multiplied out.
 *
 * Every result is checked by differentiating it and comparing with the integrand numerically,
 * so a wrong rule can never produce a wrong answer, only "not found".
 */
internal class Integrator(private val context: MathContext) {

    fun antiderivative(integrand: Expression, v: Variable): Expression? {
        val raw = integrate(integrand, v, 0) ?: return null
        val result = canonical(raw) ?: return null
        return if (matchesIntegrand(result, integrand, v)) result else null
    }

    /** Canonical form computed in a scratch context, so failed attempts leave no issues behind. */
    private fun canonical(e: Expression): Expression? {
        val scratch = context.copy()
        val result = e.canonicalize(scratch)
        return if (scratch.shouldStop) null else result
    }

    private fun integrate(e: Expression, v: Variable, depth: Int): Expression? {
        if (depth > MAX_DEPTH || context.shouldStop) return null
        if (!e.containsVariable(v)) return Multiplications(listOf(e, v))
        return when (e) {
            is Additions -> Additions(e.operands.map { integrate(it, v, depth + 1) ?: return null })
            is Multiplications -> {
                val (constants, factors) = e.operands.partition { !it.containsVariable(v) }
                if (constants.isNotEmpty()) {
                    val rest = integrate(Multiplications.of(*factors.toTypedArray()), v, depth + 1) ?: return null
                    Multiplications(constants + rest)
                } else {
                    expanded(e, v, depth) ?: bySubstitution(e, v, depth) ?: byParts(e, v, depth)
                }
            }
            else -> basic(e, v) ?: expanded(e, v, depth) ?: bySubstitution(e, v, depth) ?: byParts(e, v, depth)
        }
    }

    /** The slope a of [u] = a·v + b, or null if [u] is not linear in [v]. */
    internal fun linearSlope(u: Expression, v: Variable): Expression? {
        val du = canonical(Differentiator.derivative(u, v) ?: return null) ?: return null
        return du.takeIf { !it.containsVariable(v) && !it.isZero }
    }

    private fun lnAbs(u: Expression) = Logarithm.ln(AbsoluteValue(u))
    private fun inverse(e: Expression) = Power(e, Value.MINUS_ONE)
    private fun trig(type: TrigType, u: Expression) = TrigonometricFunction(type, u, AngleUnit.RADIANS)

    /** Table integrals of a single function of a linear argument u = a·v + b (divided by a). */
    internal fun basic(f: Expression, v: Variable): Expression? {
        if (f == v) return Multiplications(listOf(Value.HALF, Power(v, Value.TWO)))
        return when (f) {
            is Power -> {
                val base = f.base
                val exponent = f.exponent
                if (!exponent.containsVariable(v)) {
                    squaredSineOrCosine(base, exponent, v)?.let { return it }
                    val a = linearSlope(base, v) ?: return quadratic(base, exponent, v)
                    if (exponent == Value.MINUS_ONE) {
                        Multiplications(listOf(lnAbs(base), inverse(a)))
                    } else {
                        val next = Additions(listOf(exponent, Value.ONE))
                        Multiplications(listOf(Power(base, next), inverse(Multiplications(listOf(next, a)))))
                    }
                } else if (!base.containsVariable(v)) {
                    // c^u: c^u / (a·ln c)
                    val a = linearSlope(exponent, v) ?: return null
                    Multiplications(listOf(f, inverse(Multiplications(listOf(a, Logarithm.ln(base))))))
                } else {
                    null
                }
            }
            is TrigonometricFunction -> {
                if (f.type.isInverse || f.angleUnit != AngleUnit.RADIANS) return null
                val u = f.operand
                val a = linearSlope(u, v) ?: return null
                val antiderivative = when (f.type) {
                    TrigType.SIN -> Multiplications(listOf(Value.MINUS_ONE, trig(TrigType.COS, u)))
                    TrigType.COS -> trig(TrigType.SIN, u)
                    else -> Multiplications(listOf(Value.MINUS_ONE, lnAbs(trig(TrigType.COS, u))))
                }
                Multiplications(listOf(antiderivative, inverse(a)))
            }
            is Logarithm -> {
                if (f.base.containsVariable(v)) return null
                // ∫ log_b(u) = (u·ln u − u) / (a·ln b)
                val u = f.operand
                val a = linearSlope(u, v) ?: return null
                Multiplications(listOf(
                    Additions(listOf(Multiplications(listOf(u, Logarithm.ln(u))), Multiplications(listOf(Value.MINUS_ONE, u)))),
                    inverse(Multiplications(listOf(a, Logarithm.ln(f.base))))
                ))
            }
            else -> null
        }
    }

    /** sin²(u) = (u − sin(u)cos(u))/2 and cos²(u) = (u + sin(u)cos(u))/2, divided by a. */
    private fun squaredSineOrCosine(base: Expression, exponent: Expression, v: Variable): Expression? {
        if (exponent != Value.TWO || base !is TrigonometricFunction || base.angleUnit != AngleUnit.RADIANS) return null
        val sign = when (base.type) {
            TrigType.SIN -> Value.MINUS_ONE
            TrigType.COS -> Value.ONE
            else -> return null
        }
        val u = base.operand
        val a = linearSlope(u, v) ?: return null
        val sinCos = Multiplications(listOf(sign, trig(TrigType.SIN, u), trig(TrigType.COS, u)))
        return Multiplications(listOf(Additions(listOf(u, sinCos)), inverse(Multiplications(listOf(Value.TWO, a)))))
    }

    /** 1/(p + qx²) → atan, 1/√(p − qx²) → asin; the results are angles in radians. */
    private fun quadratic(base: Expression, exponent: Expression, v: Variable): Expression? {
        if (context.angleUnit != AngleUnit.RADIANS) return null
        val coefficients = polynomialCoefficients(base, v) ?: return null
        if (coefficients.keys.any { it != 0 && it != 2 }) return null
        val p = coefficients[0]?.real?.value ?: return null
        val q = coefficients[2]?.real?.value ?: return null
        val pValue = coefficients.getValue(0)
        val qValue = coefficients.getValue(2)
        return when {
            exponent == Value.MINUS_ONE && p > 0 && q > 0 -> Multiplications(listOf(
                Power(Multiplications(listOf(pValue, qValue)), Value(-1L, 2L)),
                trig(TrigType.ATAN, Multiplications(listOf(v, Power(Multiplications(listOf(qValue, inverse(pValue))), Value.HALF))))
            ))
            exponent == Value(-1L, 2L) && p > 0 && q < 0 -> Multiplications(listOf(
                Power(qValue.negate(), Value(-1L, 2L)),
                trig(TrigType.ASIN, Multiplications(listOf(v, Power(Multiplications(listOf(qValue.negate(), inverse(pValue))), Value.HALF))))
            ))
            else -> null
        }
    }

    /** Numeric coefficients by degree of a canonical polynomial in [v], or null if it is none. */
    private fun polynomialCoefficients(e: Expression, v: Variable): Map<Int, Value>? {
        val terms = if (e is Additions) e.operands else listOf(e)
        val result = mutableMapOf<Int, Value>()
        for (term in terms) {
            val factors = if (term is Multiplications) term.operands else listOf(term)
            var coefficient = Value.ONE
            var degree = 0
            for (factor in factors) {
                when {
                    factor is Value -> coefficient = coefficient.times(factor)
                    factor == v -> degree += 1
                    factor is Power && factor.base == v && factor.exponent is Value -> {
                        val n = (factor.exponent as Value).rational ?: return null
                        if (!!n.isFractional || n.isNegative) return null
                        degree += n.numerator.value.toInt()
                    }
                    else -> return null
                }
            }
            result[degree] = result[degree]?.plus(coefficient) ?: coefficient
        }
        return result
    }

    /** Products and powers of sums multiplied out, e.g. x(x+1)² = x³ + 2x² + x. */
    private fun expanded(e: Expression, v: Variable, depth: Int): Expression? {
        val expanded = canonical(expandProducts(e)) ?: return null
        if (expanded == e || expanded !is Additions) return null
        return integrate(expanded, v, depth + 1)
    }

    /**
     * Substitution ("derivative divides"): for each inner expression u of [f], if f / u'
     * depends on x only through u, then ∫ f dx = ∫ g(w) dw with w = u.
     */
    private fun bySubstitution(f: Expression, v: Variable, depth: Int): Expression? {
        val w = Variable("_w$depth")
        for (u in innerExpressions(f, v)) {
            val du = canonical(Differentiator.derivative(u, v) ?: continue) ?: continue
            if (du.isZero) continue
            val quotient = canonical(Multiplications(listOf(f, inverse(du)))) ?: continue
            val inW = quotient.substitute(u, w)
            if (inW.containsVariable(v)) continue
            val g = integrate(inW, w, depth + 1) ?: continue
            return g.substituteVariables(mapOf(w to u))
        }
        return null
    }

    /** The candidates for u: arguments of functions, bases and exponents of powers. */
    private fun innerExpressions(f: Expression, v: Variable): List<Expression> {
        val result = LinkedHashSet<Expression>()
        val queue = ArrayDeque(listOf(f))
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            if (e != f && e != v && e.containsVariable(v) && e !is Value) result.add(e)
            queue.addAll(e.children)
        }
        return result.toList()
    }

    /**
     * Integration by parts: polynomial × (eˣ, sin, cos of a linear argument) integrates the
     * second factor and differentiates the polynomial until it vanishes; polynomial × (ln, atan,
     * ...) differentiates the function and integrates the polynomial.
     */
    private fun byParts(f: Expression, v: Variable, depth: Int): Expression? {
        val factors = if (f is Multiplications) f.operands else listOf(f)
        for ((index, g) in factors.withIndex()) {
            val rest = factors.filterIndexed { i, _ -> i != index }
            val p = Multiplications.of(*rest.toTypedArray())
            if (!isPolynomial(p, v)) continue
            val dp = canonical(Differentiator.derivative(p, v) ?: continue) ?: continue
            val integratedG = if (isRepeatedlyIntegrable(g, v)) basic(g, v) else null
            if (integratedG != null && p.containsVariable(v)) {
                // ∫ p·g = p·G − ∫ p'·G
                val remainder = integrate(canonical(Multiplications(listOf(dp, integratedG))) ?: continue, v, depth + 1) ?: continue
                return Additions(listOf(Multiplications(listOf(p, integratedG)), Multiplications(listOf(Value.MINUS_ONE, remainder))))
            }
            if (g is Logarithm || (g is TrigonometricFunction && g.type.isInverse)) {
                // ∫ p·g = P·g − ∫ P·g'
                val integratedP = canonical(integrate(p, v, depth + 1) ?: continue) ?: continue
                val dg = Differentiator.derivative(g, v) ?: continue
                val remainder = integrate(canonical(Multiplications(listOf(integratedP, dg))) ?: continue, v, depth + 1) ?: continue
                return Additions(listOf(Multiplications(listOf(integratedP, g)), Multiplications(listOf(Value.MINUS_ONE, remainder))))
            }
        }
        return null
    }

    /** eˣ, sin and cos of a linear argument stay of the same kind when integrated. */
    internal fun isRepeatedlyIntegrable(g: Expression, v: Variable): Boolean = when (g) {
        is Power -> !g.base.containsVariable(v) && linearSlope(g.exponent, v) != null
        is TrigonometricFunction -> (g.type == TrigType.SIN || g.type == TrigType.COS) && linearSlope(g.operand, v) != null
        else -> false
    }

    internal fun isPolynomial(p: Expression, v: Variable): Boolean {
        var current = p
        repeat(MAX_POLYNOMIAL_DEGREE + 1) {
            if (!current.containsVariable(v)) return true
            current = canonical(Differentiator.derivative(current, v) ?: return false) ?: return false
        }
        return false
    }

    /** Whether d/dv [antiderivative] equals [integrand] at several sample points. */
    private fun matchesIntegrand(antiderivative: Expression, integrand: Expression, v: Variable): Boolean {
        val derivative = canonical(Differentiator.derivative(antiderivative, v) ?: return false) ?: return false
        val others = (integrand.variables() + antiderivative.variables() - v).toList()
        var checked = 0
        for ((i, x) in SAMPLE_POINTS.withIndex()) {
            val bindings = others.withIndex().associate { (j, other) -> other to OTHER_VALUES[(i + j) % OTHER_VALUES.size] } + (v to x)
            val expected = integrand.evaluateNumerically(bindings)
            val actual = derivative.evaluateNumerically(bindings)
            if (!expected.isFinite() || !actual.isFinite()) continue
            if (abs(expected - actual) > TOLERANCE * max(1.0, abs(expected))) return false
            checked++
        }
        return checked >= MIN_CHECKED_POINTS
    }

    companion object {
        private const val MAX_DEPTH = 12
        private const val MAX_POLYNOMIAL_DEGREE = 8
        private const val TOLERANCE = 1e-7
        private const val MIN_CHECKED_POINTS = 3
        private val SAMPLE_POINTS = listOf(0.37, 1.13, 2.71, -0.83, 0.52, -1.9, 3.3, 0.071)
        private val OTHER_VALUES = listOf(1.7, 0.6, 2.3, -1.1)
    }
}
