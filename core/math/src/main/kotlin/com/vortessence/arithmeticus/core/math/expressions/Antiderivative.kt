package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
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
                    integralOverVariable(e, v) ?: expanded(e, v, depth) ?: bySubstitution(e, v, depth) ?: partialFractions(e, v, depth) ?: irreducibleQuadratic(e, v, depth) ?: dividedPolynomial(e, v, depth) ?: byParts(e, v, depth) ?: cyclicByParts(e, v)
                }
            }
            else -> basic(e, v) ?: oddTrigonometricPower(e, v, depth) ?: expanded(e, v, depth) ?: bySubstitution(e, v, depth) ?: partialFractions(e, v, depth) ?: irreducibleQuadratic(e, v, depth) ?: dividedPolynomial(e, v, depth) ?: byParts(e, v, depth)
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
    private fun hyperbolic(type: HyperbolicType, u: Expression) = HyperbolicFunction(type, u)

    /** Table integrals of a single function of a linear argument u = a·v + b (divided by a). */
    internal fun basic(f: Expression, v: Variable): Expression? {
        if (f == v) return Multiplications(listOf(Value.HALF, Power(v, Value.TWO)))
        return when (f) {
            is Power -> {
                val base = f.base
                val exponent = f.exponent
                if (!exponent.containsVariable(v)) {
                    squaredSineOrCosine(base, exponent, v)?.let { return it }
                    squaredHyperbolic(base, exponent, v)?.let { return it }
                    val a = linearSlope(base, v) ?: return quadratic(base, exponent, v)
                    if (exponent == Value.MINUS_ONE) {
                        Multiplications(listOf(lnAbs(base), inverse(a)))
                    } else {
                        val next = Additions(listOf(exponent, Value.ONE))
                        Multiplications(listOf(Power(base, next), inverse(Multiplications(listOf(next, a)))))
                    }
                } else if (!base.containsVariable(v)) {
                    // c^u: c^u / (a·ln c)
                    val a = linearSlope(exponent, v) ?: return gaussian(base, exponent, v)
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
                    TrigType.TAN -> Multiplications(listOf(Value.MINUS_ONE, lnAbs(trig(TrigType.COS, u))))
                    TrigType.SEC -> lnAbs(Additions(listOf(trig(TrigType.SEC, u), trig(TrigType.TAN, u))))
                    TrigType.CSC -> Multiplications(listOf(Value.MINUS_ONE, lnAbs(Additions(listOf(trig(TrigType.CSC, u), trig(TrigType.COT, u))))))
                    TrigType.COT -> lnAbs(trig(TrigType.SIN, u))
                    else -> return null
                }
                Multiplications(listOf(antiderivative, inverse(a)))
            }
            is HyperbolicFunction -> {
                if (f.type.isInverse) return null
                val u = f.operand
                val a = linearSlope(u, v) ?: return null
                val antiderivative = when (f.type) {
                    HyperbolicType.SINH -> hyperbolic(HyperbolicType.COSH, u)
                    HyperbolicType.COSH -> hyperbolic(HyperbolicType.SINH, u)
                    // cosh is always positive
                    HyperbolicType.TANH -> Logarithm.ln(hyperbolic(HyperbolicType.COSH, u))
                    HyperbolicType.COTH -> lnAbs(hyperbolic(HyperbolicType.SINH, u))
                    // ∫sech = atan(sinh), ∫csch = ln|tanh(u/2)|
                    HyperbolicType.SECH -> trig(TrigType.ATAN, hyperbolic(HyperbolicType.SINH, u))
                    HyperbolicType.CSCH -> lnAbs(hyperbolic(HyperbolicType.TANH, Multiplications(listOf(Value.HALF, u))))
                    else -> return null
                }
                Multiplications(listOf(antiderivative, inverse(a)))
            }
            is SpecialFunction -> {
                // ∫erf(u) = (u·erf(u) + e^(−u²)/√π)/a and ∫erfc(u) = (u·erfc(u) − e^(−u²)/√π)/a
                if (f.type != SpecialType.ERF && f.type != SpecialType.ERFC) return null
                val u = f.operand
                val a = linearSlope(u, v) ?: return null
                val gauss = Multiplications(listOf(Power(Constant.E, Multiplications(listOf(Value.MINUS_ONE, Power(u, Value.TWO)))), Power(Constant.PI, Value(-1L, 2L))))
                val sign = if (f.type == SpecialType.ERF) Value.ONE else Value.MINUS_ONE
                Multiplications(listOf(Additions(listOf(Multiplications(listOf(u, f)), Multiplications(listOf(sign, gauss)))), inverse(a)))
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

    /** ∫ sin(a·v)/v dv = Si(a·v), ∫ cos(a·v)/v dv = Ci(a·v) and ∫ e^(a·v)/v dv = Ei(a·v). */
    internal fun integralOverVariable(e: Multiplications, v: Variable): Expression? {
        if (e.operands.size != 2) return null
        val reciprocal = e.operands.indexOfFirst { it is Power && it.base == v && it.exponent == Value.MINUS_ONE }
        if (reciprocal < 0) return null
        val other = e.operands[1 - reciprocal]
        val (type, u) = when {
            other is TrigonometricFunction && other.type == TrigType.SIN && (other.angleUnit ?: context.angleUnit) == AngleUnit.RADIANS -> SpecialType.SININT to other.operand
            other is TrigonometricFunction && other.type == TrigType.COS && (other.angleUnit ?: context.angleUnit) == AngleUnit.RADIANS -> SpecialType.COSINT to other.operand
            other is Power && other.base == Constant.E && other.exponent.containsVariable(v) -> SpecialType.EXPINT to other.exponent
            else -> return null
        }
        // The argument must be a multiple of v (a + b·v would not leave a·v as the divisor)
        val slope = linearSlope(u, v) ?: return null
        val rest = canonical(Additions(listOf(u, Multiplications.of(Value.MINUS_ONE, slope, v)))) ?: return null
        return if (rest.isZero) SpecialFunction(type, u) else null
    }

    /** ∫ e^(−k·v²) dv = √π/(2√k) · erf(√k·v) for k > 0 (e^(k·v²) with k > 0 has no such form). */
    internal fun gaussian(base: Expression, exponent: Expression, v: Variable): Expression? {
        if (base != Constant.E) return null
        val second = canonical(Differentiator.derivative(Differentiator.derivative(exponent, v) ?: return null, v) ?: return null) ?: return null
        if (second.containsVariable(v) || second.isZero) return null
        // exponent = A·v² exactly, and A < 0
        val a = canonical(Multiplications.of(Value.HALF, second)) ?: return null
        val rest = canonical(Additions(listOf(exponent, Multiplications.of(Value.MINUS_ONE, a, Power(v, Value.TWO))))) ?: return null
        // A must be a known negative number (with y in e^(−y·x²) the sign is not known)
        if (!rest.isZero || a.variables().isNotEmpty() || !(a.evaluateNumerically() < 0.0)) return null
        val root = Power.squareRoot(Multiplications.of(Value.MINUS_ONE, a))
        return Multiplications(listOf(
            Power(Constant.PI, Value.HALF), Value.HALF, inverse(root),
            SpecialFunction(SpecialType.ERF, Multiplications(listOf(root, v)))
        ))
    }

    /**
     * sin²(u) = (u − sin(u)cos(u))/2, cos²(u) = (u + sin(u)cos(u))/2, tan²(u) = tan(u) − u, sec²(u) = tan(u),
     * csc²(u) = −cot(u) and cot²(u) = −cot(u) − u, divided by a.
     */
    private fun squaredSineOrCosine(base: Expression, exponent: Expression, v: Variable): Expression? {
        if (exponent != Value.TWO || base !is TrigonometricFunction || base.angleUnit != AngleUnit.RADIANS) return null
        val u = base.operand
        val a = linearSlope(u, v) ?: return null
        fun divided(e: Expression) = Multiplications(listOf(e, inverse(a)))
        fun minus(e: Expression) = Multiplications(listOf(Value.MINUS_ONE, e))
        when (base.type) {
            // tan²(u) = sec²(u) − 1
            TrigType.TAN -> return divided(Additions(listOf(trig(TrigType.TAN, u), minus(u))))
            TrigType.SEC -> return divided(trig(TrigType.TAN, u))
            TrigType.CSC -> return divided(minus(trig(TrigType.COT, u)))
            TrigType.COT -> return divided(Additions(listOf(minus(trig(TrigType.COT, u)), minus(u))))
            else -> {}
        }
        val sign = when (base.type) {
            TrigType.SIN -> Value.MINUS_ONE
            TrigType.COS -> Value.ONE
            else -> return null
        }
        val sinCos = Multiplications(listOf(sign, trig(TrigType.SIN, u), trig(TrigType.COS, u)))
        return Multiplications(listOf(Additions(listOf(u, sinCos)), inverse(Multiplications(listOf(Value.TWO, a)))))
    }

    /** sinh²(u) = (sinh(u)cosh(u) − u)/2, cosh²(u) = (sinh(u)cosh(u) + u)/2, tanh² = u − tanh, sech² = tanh, csch² = −coth, coth² = u − coth. */
    private fun squaredHyperbolic(base: Expression, exponent: Expression, v: Variable): Expression? {
        if (exponent != Value.TWO || base !is HyperbolicFunction || base.type.isInverse) return null
        val u = base.operand
        val a = linearSlope(u, v) ?: return null
        fun divided(e: Expression) = Multiplications(listOf(e, inverse(a)))
        fun minus(e: Expression) = Multiplications(listOf(Value.MINUS_ONE, e))
        val sinhCosh = Multiplications(listOf(hyperbolic(HyperbolicType.SINH, u), hyperbolic(HyperbolicType.COSH, u)))
        val half = inverse(Multiplications(listOf(Value.TWO, a)))
        return when (base.type) {
            HyperbolicType.SINH -> Multiplications(listOf(Additions(listOf(sinhCosh, minus(u))), half))
            HyperbolicType.COSH -> Multiplications(listOf(Additions(listOf(sinhCosh, u)), half))
            HyperbolicType.TANH -> divided(Additions(listOf(u, minus(hyperbolic(HyperbolicType.TANH, u)))))
            HyperbolicType.SECH -> divided(hyperbolic(HyperbolicType.TANH, u))
            HyperbolicType.CSCH -> divided(minus(hyperbolic(HyperbolicType.COTH, u)))
            HyperbolicType.COTH -> divided(Additions(listOf(u, minus(hyperbolic(HyperbolicType.COTH, u)))))
            else -> null
        }
    }

    /** sec·tan = (sec)', csc·cot = −(csc)', sech·tanh = −(sech)' and csch·coth = −(csch)' of a linear argument. */
    private fun derivativePair(e: Multiplications, v: Variable): Expression? {
        if (e.operands.size != 2) return null
        val (f, g) = e.operands
        fun result(u: Expression, antiderivative: Expression, sign: Value): Expression? {
            val a = linearSlope(u, v) ?: return null
            return Multiplications(listOf(sign, antiderivative, inverse(a)))
        }
        if (f is TrigonometricFunction && g is TrigonometricFunction && f.operand == g.operand &&
            f.angleUnit == AngleUnit.RADIANS && g.angleUnit == AngleUnit.RADIANS) {
            return when (setOf(f.type, g.type)) {
                setOf(TrigType.SEC, TrigType.TAN) -> result(f.operand, trig(TrigType.SEC, f.operand), Value.ONE)
                setOf(TrigType.CSC, TrigType.COT) -> result(f.operand, trig(TrigType.CSC, f.operand), Value.MINUS_ONE)
                else -> null
            }
        }
        if (f is HyperbolicFunction && g is HyperbolicFunction && f.operand == g.operand) {
            return when (setOf(f.type, g.type)) {
                setOf(HyperbolicType.SECH, HyperbolicType.TANH) -> result(f.operand, hyperbolic(HyperbolicType.SECH, f.operand), Value.MINUS_ONE)
                setOf(HyperbolicType.CSCH, HyperbolicType.COTH) -> result(f.operand, hyperbolic(HyperbolicType.CSCH, f.operand), Value.MINUS_ONE)
                else -> null
            }
        }
        return null
    }

    /**
     * 1/(p + qx²) → atan, 1/√(p − qx²) → asin, 1/√(p + qx²) → asinh (a logarithm where p is negative: asinh only goes with
     * a positive p), √(p ∓ qx²); the angles are in radians. Under a root, a term in x does not matter: the square is completed,
     * ax² + bx + c = a·u² + (c − b²/4a) with u = x + b/2a, so that √(x² + 2x + 5) is √(u² + 4).
     */
    private fun quadratic(base: Expression, exponent: Expression, v: Variable): Expression? {
        if (context.angleUnit != AngleUnit.RADIANS) return null
        val coefficients = polynomialCoefficients(base, v) ?: return null
        if (coefficients.keys.any { it !in 0..2 }) return null
        val qValue = coefficients[2] ?: return null
        val isRoot = exponent == Value.HALF || exponent == Value(-1L, 2L)
        val linear = coefficients[1]
        if (linear != null && !isRoot) return null
        // u = x + h, p = c − q·h²
        var u: Expression = v
        var pValue = coefficients[0] ?: if (linear != null) Value.ZERO else return null
        if (linear != null) {
            val h = canonical(Multiplications(listOf(linear, inverse(Multiplications(listOf(Value.TWO, qValue)))))) as? Value ?: return null
            u = canonical(Additions(listOf(v, h))) ?: return null
            pValue = canonical(Additions(listOf(pValue, Multiplications(listOf(Value.MINUS_ONE, qValue, Power(h, Value.TWO)))))) as? Value ?: return null
        }
        val p = pValue.real.value
        val q = qValue.real.value
        if (p == 0.0 || q == 0.0) return null
        return when {
            exponent == Value.MINUS_ONE && p > 0 && q > 0 -> Multiplications(listOf(
                Power(Multiplications(listOf(pValue, qValue)), Value(-1L, 2L)),
                trig(TrigType.ATAN, Multiplications(listOf(u, Power(Multiplications(listOf(qValue, inverse(pValue))), Value.HALF))))
            ))
            exponent == Value(-1L, 2L) && p > 0 && q < 0 -> Multiplications(listOf(
                Power(qValue.negate(), Value(-1L, 2L)),
                trig(TrigType.ASIN, Multiplications(listOf(u, Power(Multiplications(listOf(qValue.negate(), inverse(pValue))), Value.HALF))))
            ))
            // ∫1/√(p + q·x²) = asinh(x·√(q/p))/√q
            exponent == Value(-1L, 2L) && p > 0 && q > 0 -> Multiplications(listOf(
                Power(qValue, Value(-1L, 2L)),
                HyperbolicFunction(HyperbolicType.ASINH, Multiplications(listOf(u, Power(Multiplications(listOf(qValue, inverse(pValue))), Value.HALF))))
            ))
            // ∫1/√(q·x² − r) = ln|x·√q + √(q·x² − r)|/√q: the same for either sign of x (acosh is only for x > 0)
            exponent == Value(-1L, 2L) && p < 0 && q > 0 -> Multiplications(listOf(
                Power(qValue, Value(-1L, 2L)),
                lnAbs(Additions(listOf(Multiplications(listOf(u, Power(qValue, Value.HALF))), Power(base, Value.HALF))))
            ))
            // ∫√(p − r·x²) = x/2·√(p − r·x²) + p/(2√r)·asin(x·√(r/p))
            exponent == Value.HALF && p > 0 && q < 0 -> {
                val r = qValue.negate()
                Additions(listOf(
                    Multiplications(listOf(Value.HALF, u, Power(base, Value.HALF))),
                    Multiplications(listOf(
                        pValue, Value.HALF, Power(r, Value(-1L, 2L)),
                        trig(TrigType.ASIN, Multiplications(listOf(u, Power(Multiplications(listOf(r, inverse(pValue))), Value.HALF))))
                    ))
                ))
            }
            // ∫√(p + q·x²) = x/2·√(p + q·x²) + p/(2√q)·ln|x·√q + √(p + q·x²)|, for a negative p as well
            exponent == Value.HALF && q > 0 -> Additions(listOf(
                Multiplications(listOf(Value.HALF, u, Power(base, Value.HALF))),
                Multiplications(listOf(
                    pValue, Value.HALF, Power(qValue, Value(-1L, 2L)),
                    lnAbs(Additions(listOf(Multiplications(listOf(u, Power(qValue, Value.HALF))), Power(base, Value.HALF))))
                ))
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
                        val n = factor.exponent.rational ?: return null
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
    /**
     * P(x)/Q(x) with Q splitting into linear factors: a polynomial plus fractions A/(x − r)ᵏ, each
     * integrated on its own (1/(x² − 1) = ½/(x − 1) − ½/(x + 1)).
     */
    internal fun partialFractions(f: Expression, v: Variable, depth: Int): Expression? {
        val split = splitRational(f, v) ?: return null
        val decomposition = split.first
        val integrand = decomposition.toExpression(v)
        return integrate(integrand, v, depth + 1)
    }

    /** The decomposition of [f] if it is a rational function with a denominator of degree 2 or more that splits. */
    internal fun splitRational(f: Expression, v: Variable): Pair<PartialFractions.Decomposition, Pair<Expression, Expression>>? {
        val factors = if (f is Multiplications) f.operands else listOf(f)
        val denominators = factors.filter { it is Power && it.base.containsVariable(v) && (it.exponent as? Value)?.rational?.let { r -> r.isNegative && !r.isFractional } == true }
        if (denominators.isEmpty()) return null
        val denominator = Multiplications.of(*denominators.map { d ->
            val positive = ((d as Power).exponent as Value).negate()
            if (positive == Value.ONE) d.base else Power(d.base, positive)
        }.toTypedArray())
        val numerator = Multiplications.of(*factors.filterNot { it in denominators }.toTypedArray())
        val q = PartialFractions.polynomial(denominator, v, context) ?: return null
        val p = PartialFractions.polynomial(numerator, v, context) ?: return null
        val decomposition = PartialFractions.decompose(p, q) ?: return null
        return decomposition to (numerator to denominator)
    }

    /**
     * (px + q)/(ax² + bx + c) with no real roots: the part of the numerator that is the derivative
     * of the denominator gives a logarithm, the rest atan after completing the square.
     */
    private fun irreducibleQuadratic(f: Expression, v: Variable, depth: Int): Expression? {
        val fraction = splitQuadratic(f, v) ?: return null
        val parts = mutableListOf<Expression>()
        // (p/2a)·ln|D| for the derivative of the denominator in the numerator
        if (!fraction.p.isZero) parts.add(Multiplications.of(Value(fraction.derivativePart), lnAbs(fraction.denominator)))
        if (!fraction.constantPart.isZero) {
            // ∫ c/((x + h)² + k) dx = c/√k·atan((x + h)/√k): directly, as canonicalizing would multiply (x + h)² out
            val k = (fraction.a * fraction.c * 4L - fraction.b * fraction.b) / (fraction.a * fraction.a * 4L)
            val root = Power(Value(k), Value(com.vortessence.arithmeticus.core.math.numbers.Rational.HALF))
            val argument = Multiplications(fraction.shifted(v), inverse(root))
            parts.add(
                Multiplications.of(
                    Value(fraction.constantPart / fraction.a), inverse(root),
                    TrigonometricFunction(TrigType.ATAN, argument, AngleUnit.RADIANS)
                )
            )
        }
        return if (parts.size == 1) parts.single() else Additions(parts)
    }

    /** A fraction with a quadratic denominator without real roots, a, b, c, p and q as in (px + q)/(ax² + bx + c). */
    internal class QuadraticFraction(
        val a: com.vortessence.arithmeticus.core.math.numbers.Rational,
        val b: com.vortessence.arithmeticus.core.math.numbers.Rational,
        val c: com.vortessence.arithmeticus.core.math.numbers.Rational,
        val p: com.vortessence.arithmeticus.core.math.numbers.Rational,
        val q: com.vortessence.arithmeticus.core.math.numbers.Rational,
        val denominator: Expression
    ) {
        /** x + b/(2a) */
        fun shifted(v: Variable): Expression {
            val h = b / (a * 2L)
            return if (h.isZero) v else Additions(v, Value(h))
        }

        /** (x + h)² + k, with ax² + bx + c = a((x + h)² + k) */
        fun completedSquare(v: Variable): Expression {
            val k = (a * c * 4L - b * b) / (a * a * 4L)
            return Additions(Power(shifted(v), Value.TWO), Value(k))
        }

        /** The numerator's part that is the derivative 2ax + b of the denominator, times p/(2a) (0 if p = 0). */
        val derivativePart: com.vortessence.arithmeticus.core.math.numbers.Rational get() = p / (a * 2L)

        /** What is left of the numerator, as a constant: q − pb/(2a). */
        val constantPart: com.vortessence.arithmeticus.core.math.numbers.Rational get() = q - p * b / (a * 2L)

        fun derivative(v: Variable): Expression = PartialFractions.toExpression(listOf(b, a * 2L), v)

        /** p/(2a)·(2ax + b)/D + (q − pb/(2a))/a · 1/((x + h)² + k) */
        fun rewritten(v: Variable): Expression {
            val terms = mutableListOf<Expression>()
            if (!p.isZero) {
                terms.add(Multiplications.of(Value(derivativePart), derivative(v), Power(denominator, Value.MINUS_ONE)))
            }
            if (!constantPart.isZero) {
                terms.add(Multiplications.of(Value(constantPart / a), Power(completedSquare(v), Value.MINUS_ONE)))
            }
            return if (terms.size == 1) terms.single() else Additions(terms)
        }
    }

    internal fun splitQuadratic(f: Expression, v: Variable): QuadraticFraction? {
        val factors = if (f is Multiplications) f.operands else listOf(f)
        val denominators = factors.filter { it is Power && it.base is Additions && (it.exponent as? Value)?.rational?.let { r -> r == com.vortessence.arithmeticus.core.math.numbers.Rational.MINUS_ONE } == true }
        if (denominators.size != 1) return null
        val denominator = (denominators.single() as Power).base
        val numerator = Multiplications.of(*factors.filter { it !== denominators.single() }.toTypedArray())
        val d = PartialFractions.polynomial(denominator, v, context) ?: return null
        val n = PartialFractions.polynomial(numerator, v, context) ?: return null
        if (d.size != 3 || n.size > 2 || n.isEmpty()) return null
        val (c, b, a) = d
        // Real roots are the business of the partial fractions
        if (b * b - a * c * 4L >= com.vortessence.arithmeticus.core.math.numbers.Rational.ZERO) return null
        val q = n[0]
        val p = n.getOrElse(1) { com.vortessence.arithmeticus.core.math.numbers.Rational.ZERO }
        // 1/(p + qx²) is in the table
        if (p.isZero && b.isZero) return null
        return QuadraticFraction(a, b, c, p, q, denominator)
    }

    /** sin³(u) = sin(u)·(1 − cos²(u)), cos³(u) = cos(u)·(1 − sin²(u)), and so on for odd powers: then a substitution. */
    internal fun oddTrigonometricPower(f: Expression, v: Variable, depth: Int): Expression? = rewrittenOddPower(f, v)?.let { integrate(it, v, depth + 1) }

    internal fun rewrittenOddPower(f: Expression, v: Variable): Expression? {
        val power = f as? Power ?: return null
        val n = (power.exponent as? Value)?.rational?.takeIf { !it.isFractional && it.numerator.isOdd && it.numerator.value.toLong() >= 3 }?.numerator?.value?.toLong() ?: return null
        val trig = power.base as? TrigonometricFunction ?: return null
        if ((trig.angleUnit ?: context.angleUnit) != AngleUnit.RADIANS || linearSlope(trig.operand, v) == null) return null
        val other = when (trig.type) {
            TrigType.SIN -> TrigType.COS
            TrigType.COS -> TrigType.SIN
            else -> return null
        }
        val rest = Power(Additions(listOf(Value.ONE, Multiplications(listOf(Value.MINUS_ONE, Power(trig(other, trig.operand), Value.TWO))))), Value((n - 1) / 2))
        return Multiplications(listOf(trig(trig.type, trig.operand), rest))
    }

    /** The quotient and the remainder of P(x)/(ax² + bx + c) with no real roots, when P has degree 2 or more. */
    internal class DividedFraction(val quotient: List<com.vortessence.arithmeticus.core.math.numbers.Rational>, val remainder: List<com.vortessence.arithmeticus.core.math.numbers.Rational>, val denominator: Expression, val numerator: Expression)

    internal fun splitByDivision(f: Expression, v: Variable): DividedFraction? {
        val factors = if (f is Multiplications) f.operands else listOf(f)
        val denominators = factors.filter { it is Power && it.base is Additions && (it.exponent as? Value)?.rational?.let { r -> r == com.vortessence.arithmeticus.core.math.numbers.Rational.MINUS_ONE } == true }
        if (denominators.size != 1) return null
        val denominator = (denominators.single() as Power).base
        val numerator = Multiplications.of(*factors.filter { it !== denominators.single() }.toTypedArray())
        val d = PartialFractions.polynomial(denominator, v, context) ?: return null
        val n = PartialFractions.polynomial(numerator, v, context) ?: return null
        if (d.size != 3 || n.size < 3) return null
        val (c, b, a) = d
        if (b * b - a * c * 4L >= com.vortessence.arithmeticus.core.math.numbers.Rational.ZERO) return null
        val (quotient, remainder) = PartialFractions.divide(n, d)
        return DividedFraction(quotient, remainder, denominator, numerator)
    }

    /** x³/(x² + 1) = x − x/(x² + 1): the polynomial part first, then a fraction with a lower degree. */
    private fun dividedPolynomial(f: Expression, v: Variable, depth: Int): Expression? {
        val division = splitByDivision(f, v) ?: return null
        val parts = listOf(PartialFractions.toExpression(division.quotient, v), Multiplications.of(PartialFractions.toExpression(division.remainder, v), inverse(division.denominator)))
        return integrate(Additions(parts), v, depth + 1)
    }

    /**
     * eᵖˣ·sin(qx) and eᵖˣ·cos(qx), by parts twice (the integral comes back): the closed form
     * e^u·(p·sin w − q·cos w)/(p² + q²), and e^u·(p·cos w + q·sin w)/(p² + q²), for u and w linear.
     */
    internal fun cyclicByParts(f: Expression, v: Variable): Expression? = cyclicParts(f, v)?.antiderivative

    internal class CyclicParts(val exponential: Expression, val trig: TrigonometricFunction, val p: Expression, val q: Expression, val antiderivative: Expression)

    internal fun cyclicParts(f: Expression, v: Variable): CyclicParts? {
        val factors = if (f is Multiplications) f.operands else return null
        if (factors.size != 2) return null
        val exponential = factors.firstOrNull { it is Power && !it.base.containsVariable(v) && it.exponent.containsVariable(v) } as? Power ?: return null
        val trig = factors.firstOrNull { it is TrigonometricFunction && (it.type == TrigType.SIN || it.type == TrigType.COS) && it.operand.containsVariable(v) } as? TrigonometricFunction ?: return null
        if ((trig.angleUnit ?: context.angleUnit) != AngleUnit.RADIANS) return null
        val a = linearSlope(exponential.exponent, v) ?: return null
        val q = linearSlope(trig.operand, v) ?: return null
        val p = if (exponential.base == Constant.E) a else canonical(Multiplications(a, Logarithm.ln(exponential.base))) ?: return null
        val denominator = canonical(Additions(Power(p, Value.TWO), Power(q, Value.TWO))) ?: return null
        if (denominator.isZero) return null
        val sine = trig(TrigType.SIN, trig.operand)
        val cosine = trig(TrigType.COS, trig.operand)
        val bracket = if (trig.type == TrigType.SIN) {
            Additions(Multiplications.of(p, sine), Multiplications.of(Value.MINUS_ONE, q, cosine))
        } else {
            Additions(Multiplications.of(p, cosine), Multiplications.of(q, sine))
        }
        val result = Multiplications.of(exponential, bracket, inverse(denominator))
        return CyclicParts(exponential, trig, p, q, result)
    }

    private fun bySubstitution(f: Expression, v: Variable, depth: Int): Expression? {
        val w = Variable("_w$depth")
        for (u in substitutionCandidates(f, v)) {
            val du = canonical(Differentiator.derivative(u, v) ?: continue) ?: continue
            if (du.isZero) continue
            val quotient = canonical(Multiplications(listOf(f, inverse(du)))) ?: continue
            val inW = inTermsOf(quotient, u, w, v)
            if (inW.containsVariable(v)) continue
            val g = integrate(inW, w, depth + 1) ?: continue
            return g.substituteVariables(mapOf(w to u))
        }
        return null
    }

    /**
     * The candidates for u: arguments of functions, bases and exponents of powers, innermost
     * first (u = x² rather than u = e^(x²) for x·e^(x²), as one would choose by hand), and then
     * powers of [v] that a higher power in [f] is a power of, the highest first, e.g. x² for
     * x/(1 + x⁴), where x⁴ = (x²)² (u = x² does not appear in it as it is).
     */
    internal fun substitutionCandidates(f: Expression, v: Variable): List<Expression> {
        val inner = innerExpressions(f, v)
        // Deeper ones first; side by side (sin(x)·cos(x)) in the order they appear
        val result = LinkedHashSet(inner.sortedByDescending { depthIn(f, it) })
        val exponents = inner.mapNotNull { e -> integerExponentOf(e, v) }.distinct()
        for (n in exponents.sortedDescending()) {
            for (k in (n - 1) downTo 2) if (n % k == 0) result.add(Power(v, Value(k.toLong())))
        }
        return result.toList()
    }

    /**
     * [e] written in [w] = [u]: u replaced by w, and for u = vᵏ also each power v^(n·k) by wⁿ,
     * e.g. x⁴ by w² for u = x².
     */
    internal fun inTermsOf(e: Expression, u: Expression, w: Variable, v: Variable): Expression {
        val replaced = e.substitute(u, w)
        val k = integerExponentOf(u, v) ?: return replaced
        return replaced.transform { part ->
            val n = integerExponentOf(part, v)
            if (n != null && n % k == 0) Power(w, Value((n / k).toLong())) else part
        }
    }

    /** How deep [part] first appears in [e] (0 for [e] itself), or −1 if it does not. */
    private fun depthIn(e: Expression, part: Expression): Int {
        if (e == part) return 0
        val depths = e.children.map { depthIn(it, part) }.filter { it >= 0 }
        return if (depths.isEmpty()) -1 else depths.min() + 1
    }

    /** n for [e] = vⁿ with a whole number n ≥ 2, otherwise null. */
    private fun integerExponentOf(e: Expression, v: Variable): Int? {
        if (e !is Power || e.base != v) return null
        val n = (e.exponent as? Value)?.rational ?: return null
        if (n.isFractional || n.isNegative || n.numerator.value.bitLength() > 16) return null
        return n.numerator.value.toInt().takeIf { it >= 2 }
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
            if (g is Logarithm || (g is TrigonometricFunction && g.type.isInverse) || (g is HyperbolicFunction && g.type.isInverse)) {
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
        is HyperbolicFunction -> (g.type == HyperbolicType.SINH || g.type == HyperbolicType.COSH) && linearSlope(g.operand, v) != null
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
        // Some well outside of (−2, 2): 1/√(x² − 4) and the like are only defined there
        private val SAMPLE_POINTS = listOf(0.37, 1.13, 2.71, -0.83, 0.52, -1.9, 3.3, 0.071, 4.4, -2.6, 6.1, -5.2)
        private val OTHER_VALUES = listOf(1.7, 0.6, 2.3, -1.1)
    }
}
