package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.numbers.Real
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

class Power(
    val base: Expression,
    val exponent: Expression
) : Expression() {

    override val children: List<Expression> get() = listOf(base, exponent)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 2)
        return Power(children[0], children[1])
    }

    override fun canonicalize(context: MathContext): Expression {
        val cBase = base.canonicalize(context)
        if (context.shouldStop) return this
        val cExp = exponent.canonicalize(context)
        if (context.shouldStop) return this

        if (cExp is MatrixExpression) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }
        if (cBase is MatrixExpression) return matrixPower(cBase, cExp, context) ?: this

        if (cExp.isZero) return Value.ONE
        if (cExp == Value.ONE) return cBase
        // Powers of an absolute temperature use kelvin: 1/(25 °C) is 1/(298.15 K)
        val baseTemperatureUnit = cBase.absoluteTemperatureUnit()
        if (baseTemperatureUnit != null && baseTemperatureUnit != UnitRegistry.K) {
            val inKelvin = cBase.convertToUnit(UnitRegistry.K, includeUnitInResult = true, context = context) ?: return this
            return Power(inKelvin, cExp).canonicalize(context)
        }
        if (cBase == Value.ONE) return Value.ONE
        if (cBase.isZero && cExp.isNegative) {
            context.stopWithError(MathIssue.DIVISION_BY_ZERO)
            return this
        }

        if (context.complexNumbers) {
            val complex = complexPower(cBase, cExp, context)
            if (complex != null || context.shouldStop) return complex ?: this
        }

        if (cBase is Value && cExp is Value) {
            return canonicalizeValues(cBase, cExp, context)
        }

        // (x^a)^b = x^(a * b)
        if (cBase is Power) {
            val newExp = Multiplications.of(cBase.exponent, cExp).canonicalize(context)
            // (x²)^(1/2) = |x|, not x: with an even inner and a fractional outer exponent the sign of the base is lost
            val inner = (cBase.exponent as? Value)?.rational
            val outer = (cExp as? Value)?.rational
            val signLost = inner != null && !inner.isFractional && inner.numerator.isEven && outer != null && outer.isFractional
            return Power(if (signLost) AbsoluteValue(cBase.base) else cBase.base, newExp).canonicalize(context)
        }

        // |x|ⁿ = xⁿ for an even whole number n
        if (cBase is AbsoluteValue && cExp is Value && cExp.rational?.let { !it.isFractional && it.numerator.isEven } == true) {
            return Power(cBase.operand, cExp).canonicalize(context)
        }

        // (a * b)^n = a^n * b^n
        if (cBase is Multiplications) {
            val terms = cBase.operands.map { Power(it, cExp).canonicalize(context) }
            return Multiplications(terms).canonicalize(context)
        }

        return Power(cBase, cExp)
    }

    private fun canonicalizeValues(baseVal: Value, expVal: Value, context: MathContext): Expression {
        if (context.mode == CalculationMode.NUMERIC || baseVal.rational == null || expVal.rational == null) {
            val baseD = baseVal.real.value
            val expD = expVal.real.value
            val result = if (baseD < 0.0 && expD % 1.0 != 0.0) {
                // Numeric mode strips exact rationals, so recover the exact exponent (if any)
                // to decide whether this is an odd root, e.g. (-8)^(1/3) = -2.
                realPow(baseD, expD, expVal.rational ?: exactExponent(context))
            } else {
                baseD.pow(expD)
            }
            if (result.isNaN()) {
                context.stopWithError(MathIssue.NEGATIVE_ROOT)
                return this
            }
            if (result.isInfinite()) {
                context.stopWithError(MathIssue.VALUES_TOO_LARGE)
                return this
            }
            return Value(Real(result))
        }

        val rBase = baseVal.rational
        val rExp = expVal.rational

        if (rBase.isZero) return Value.ZERO // exponent is known to be positive here
        if (rBase == Rational.ONE) return Value.ONE
        if (rBase == Rational.MINUS_ONE) {
            // (-1)^(p/q) is real only for odd q; handled here so huge exponents stay cheap
            if (rExp.denominator.isEven) {
                context.stopWithError(MathIssue.NEGATIVE_ROOT)
                return this
            }
            return if (rExp.numerator.isOdd) Value.MINUS_ONE else Value.ONE
        }

        if (!fitsInLong(rExp.numerator) || !fitsInLong(rExp.denominator)) {
            context.stopWithError(MathIssue.VALUES_TOO_LARGE)
            return this
        }
        val num = rExp.numerator.toLong()
        val rootDegree = rExp.denominator.toLong()

        if (exceedsSizeLimit(rBase, num)) {
            context.stopWithError(MathIssue.VALUES_TOO_LARGE)
            return this
        }

        if (!rExp.isFractional) {
            // Integer power
            return Value(rBase.pow(num))
        }

        // Fractional power: rBase^(num/den)
        if (rBase.isNegative && rootDegree % 2L == 0L) {
            context.stopWithError(MathIssue.NEGATIVE_ROOT)
            return this
        }

        val poweredBase = rBase.pow(num)
        // Odd root of a negative number: pull the sign out, e.g. cbrt(-16) = -2 * cbrt(2)
        val isNegativeResult = poweredBase.isNegative
        val numBig = poweredBase.numerator.abs()
        val denBig = poweredBase.denominator

        // Simplification of root: extract perfect powers
        val numFactor = BigInt.highestPerfectPowerFactor(numBig, rootDegree)
        val denFactor = BigInt.highestPerfectPowerFactor(denBig, rootDegree)

        var outsideRational = Rational.of(
            BigInt.truncatedNthRoot(numFactor, rootDegree),
            BigInt.truncatedNthRoot(denFactor, rootDegree)
        )
        var insideNum = numBig / numFactor
        val insideDen = denBig / denFactor

        // Rationalize the denominator: root(a/b) = root(a * b^(k-1)) / b
        if (insideDen != BigInt.ONE && rootDegree <= MAX_RATIONALIZED_ROOT_DEGREE &&
            !exceedsSizeLimit(Rational.of(insideDen), rootDegree)
        ) {
            val radicand = insideNum * insideDen.pow(rootDegree - 1)
            val extracted = BigInt.highestPerfectPowerFactor(radicand, rootDegree)
            outsideRational = outsideRational * Rational.of(BigInt.truncatedNthRoot(extracted, rootDegree), insideDen)
            insideNum = radicand / extracted
            if (isNegativeResult) outsideRational = -outsideRational
            return rootResult(outsideRational, Rational.of(insideNum), rootDegree)
        }

        if (isNegativeResult) outsideRational = -outsideRational
        return rootResult(outsideRational, Rational.of(insideNum, insideDen), rootDegree)
    }

    /**
     * Powers with a complex result: of i (i² = −1), of complex numbers ((1 + i)² = 2i), with a
     * complex exponent (e^(iπ) = −1) and even roots of negative numbers (√−4 = 2i). Null if
     * the power is real or stays as it is.
     */
    private fun complexPower(base: Expression, exp: Expression, context: MathContext): Expression? {
        val baseIsComplex = base.containsImaginaryUnit()
        val expIsComplex = exp.containsImaginaryUnit()
        if (!baseIsComplex && !expIsComplex) {
            if (base !is Value || !base.isNegative || exp !is Value) return null
            // (−a)^w = a^w·e^(iπw); odd roots stay real, (−8)^(1/3) = −2
            val r = exp.rational ?: exactExponent(context)
            val isRealPower = if (r != null) !r.isFractional || r.denominator.isOdd else exp.real.value % 1.0 == 0.0
            if (isRealPower) return null
            return Multiplications.of(Power(base.negate(), exp), unitCircle(Multiplications.of(exp, Constant.PI))).canonicalize(context)
        }
        if (exp.variables().isNotEmpty()) return null

        if (!expIsComplex) {
            val n = integerValue(exp)
            if (base === ImaginaryUnit && n != null) {
                return when (n.mod(4L)) {
                    0L -> Value.ONE
                    1L -> ImaginaryUnit
                    2L -> Value.MINUS_ONE
                    else -> Multiplications(listOf(Value.MINUS_ONE, ImaginaryUnit))
                }
            }
            val parts = complexParts(base)
            if (n != null && parts != null && abs(n) <= MAX_EXACT_COMPLEX_POWER) {
                return exactComplexPower(parts, n, context)
            }
            // Half powers via the exact square root: z^(p/2) = (√z)^p
            val r = (exp as? Value)?.rational
            if (parts != null && r != null && r.denominator == BigInt.TWO && fitsInLong(r.numerator) &&
                abs(r.numerator.toLong()) <= MAX_EXACT_COMPLEX_POWER
            ) {
                val root = exactComplexSquareRoot(parts, context) ?: return numericComplexPower(base, exp, context)
                if (context.shouldStop) return this
                return if (r.numerator == BigInt.ONE) complexOf(root.first, root.second, context)
                else exactComplexPower(root, r.numerator.toLong(), context)
            }
        } else if (base.variables().isEmpty() && !baseIsComplex) {
            val (re, im) = complexParts(exp) ?: return numericComplexPower(base, exp, context)
            // e^(a + bi) = e^a·(cos b + i·sin b)
            // (In numeric mode e is already a number)
            if (base == Constant.E || (base is Value && base.real.value == kotlin.math.E)) return Multiplications.of(Power(base, re), unitCircle(im)).canonicalize(context)
            // a^w = e^(w·ln a) for a > 0
            if (base.evaluateNumerically() > 0.0) {
                return Power(Constant.E, Multiplications.of(exp, Logarithm.ln(base))).canonicalize(context)
            }
        }
        return numericComplexPower(base, exp, context)
    }

    /**
     * The principal square root of re + im·i (im ≠ 0), exactly:
     * √z = √((|z| + re)/2) ± i·√((|z| − re)/2), with the sign of im; e.g. √(3i) = (√6/2)(1 + i).
     * Null if the sign of im is not known (it contains variables).
     */
    private fun exactComplexSquareRoot(parts: Pair<Expression, Expression>, context: MathContext): Pair<Expression, Expression>? {
        val (re, im) = parts
        if (re.variables().isNotEmpty() || im.variables().isNotEmpty()) return null
        val imSign = im.evaluateNumerically()
        if (imSign.isNaN() || imSign == 0.0) return null
        val modulus = Power(Additions(listOf(Power(re, Value.TWO), Power(im, Value.TWO))), Value.HALF)
        fun halfRoot(sign: Value) = Power(
            Multiplications.of(Value.HALF, Additions(listOf(modulus, Multiplications.of(sign, re)))),
            Value.HALF
        ).canonicalize(context)
        val realPart = halfRoot(Value.ONE)
        val imaginaryPart = halfRoot(Value.MINUS_ONE)
        if (context.shouldStop) return null
        return realPart to (if (imSign < 0.0) Multiplications.of(Value.MINUS_ONE, imaginaryPart).canonicalize(context) else imaginaryPart)
    }

    /** cos(angle) + i·sin(angle), with the angle in radians whatever the angle unit. */
    private fun unitCircle(angle: Expression): Expression = Additions(listOf(
        TrigonometricFunction(TrigType.COS, angle, AngleUnit.RADIANS),
        Multiplications(listOf(ImaginaryUnit, TrigonometricFunction(TrigType.SIN, angle, AngleUnit.RADIANS)))
    ))

    /** (re + im·i)^n by repeated squaring, exactly; n < 0 via 1/z = (re − im·i)/(re² + im²). */
    private fun exactComplexPower(parts: Pair<Expression, Expression>, n: Long, context: MathContext): Expression {
        fun times(x: Pair<Expression, Expression>, y: Pair<Expression, Expression>): Pair<Expression, Expression> {
            val re = Additions(listOf(Multiplications.of(x.first, y.first), Multiplications.of(Value.MINUS_ONE, x.second, y.second)))
            val im = Additions(listOf(Multiplications.of(x.first, y.second), Multiplications.of(x.second, y.first)))
            return re.canonicalize(context) to im.canonicalize(context)
        }
        var z = parts
        if (n < 0) {
            val norm = Additions(listOf(Power(z.first, Value.TWO), Power(z.second, Value.TWO))).canonicalize(context)
            if (norm.isZero) {
                context.stopWithError(MathIssue.DIVISION_BY_ZERO)
                return this
            }
            val inverseNorm = Power(norm, Value.MINUS_ONE)
            z = Multiplications.of(z.first, inverseNorm).canonicalize(context) to
                Multiplications.of(Value.MINUS_ONE, z.second, inverseNorm).canonicalize(context)
        }
        var result: Pair<Expression, Expression> = Value.ONE to Value.ZERO
        var remaining = abs(n)
        while (remaining > 0 && !context.shouldStop) {
            if (remaining and 1L == 1L) result = times(result, z)
            remaining = remaining shr 1
            if (remaining > 0) z = times(z, z)
        }
        if (context.shouldStop) return this
        return complexOf(result.first, result.second, context)
    }

    /** z^w = e^(w·ln z) in floating point, for complex powers without an exact form. */
    private fun numericComplexPower(base: Expression, exp: Expression, context: MathContext): Expression? {
        val z = numericComplex(base) ?: return null
        val w = numericComplex(exp) ?: return null
        if (z.re == 0.0 && z.im == 0.0 && w.re <= 0.0) {
            context.stopWithError(MathIssue.DIVISION_BY_ZERO)
            return this
        }
        val result = z.pow(w)
        if (!result.isFinite) {
            context.stopWithError(MathIssue.VALUES_TOO_LARGE)
            return this
        }
        return result.toExpression(context)
    }

    /** The whole number [e] stands for, or null. */
    private fun integerValue(e: Expression): Long? {
        val value = e as? Value ?: return null
        val r = value.rational
        if (r != null) return if (r.isFractional || !fitsInLong(r.numerator)) null else r.numerator.toLong()
        val d = value.real.value
        return if (d % 1.0 == 0.0 && abs(d) < 1e15) d.toLong() else null
    }

    private fun rootResult(outside: Rational, inside: Rational, rootDegree: Long): Expression {
        if (inside == Rational.ONE) return Value(outside)
        val rootExpr = Power(Value(inside), Value(Rational.of(1L, rootDegree)))
        return if (outside == Rational.ONE) rootExpr else Multiplications.of(Value(outside), rootExpr)
    }

    /** The exponent's exact rational value, computed in a scratch exact-mode context. */
    private fun exactExponent(context: MathContext): Rational? {
        val scratch = context.copy().apply { mode = CalculationMode.EXACT }
        return (exponent.canonicalize(scratch) as? Value)?.rational
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val b = base.evaluateNumerically(bindings)
        val e = exponent.evaluateNumerically(bindings)
        return realPow(b, e, (exponent as? Value)?.rational)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Power) return false
        return base == other.base && exponent == other.exponent
    }

    override fun hashCode(): Int = base.hashCode() * 31 + exponent.hashCode()

    override fun toString(): String = "($base^$exponent)"

    companion object {
        /** Largest exact result (in bits) we are willing to compute, ~60,000 decimal digits. */
        private const val MAX_RESULT_BITS = 200_000.0
        private const val MAX_RATIONALIZED_ROOT_DEGREE = 12L
        private const val MAX_APPROXIMATED_ROOT_DENOMINATOR = 99L
        private const val MAX_EXACT_COMPLEX_POWER = 1024L

        fun of(base: Expression, exponent: Expression): Expression = Power(base, exponent)
        fun squareRoot(base: Expression): Expression = Power(base, Value.HALF)
        // Not degree.invert(): inverting an exact zero would throw instead of reporting an issue
        fun nthRoot(degree: Expression, base: Expression): Expression = Power(base, Power(degree, Value.MINUS_ONE))

        private fun fitsInLong(n: BigInt): Boolean = n.value.bitLength() < 63

        private fun exceedsSizeLimit(base: Rational, exponent: Long): Boolean {
            val bits = maxOf(base.numerator.value.bitLength(), base.denominator.value.bitLength())
            return bits.toDouble() * abs(exponent.toDouble()) > MAX_RESULT_BITS
        }

        /**
         * Real-valued power. Unlike [Math.pow], negative bases with an odd-denominator rational
         * exponent give the real root, e.g. (-8)^(1/3) = -2. Returns NaN when no real result exists.
         * [exactExponent] is used when known; otherwise a small odd denominator is searched for.
         */
        fun realPow(b: Double, e: Double, exactExponent: Rational? = null): Double {
            if (b >= 0.0 || e.isNaN() || e.isInfinite() || e % 1.0 == 0.0) return b.pow(e)
            val numeratorIsOdd: Boolean
            val denominatorIsOdd: Boolean
            if (exactExponent != null) {
                numeratorIsOdd = exactExponent.numerator.isOdd
                denominatorIsOdd = exactExponent.denominator.isOdd
            } else {
                val q = (1L..MAX_APPROXIMATED_ROOT_DENOMINATOR).firstOrNull { q ->
                    val scaled = e * q
                    abs(scaled - scaled.roundToLong()) < 1e-9
                } ?: return Double.NaN
                numeratorIsOdd = (e * q).roundToLong() % 2L != 0L
                denominatorIsOdd = q % 2L != 0L
            }
            if (!denominatorIsOdd) return Double.NaN
            val magnitude = (-b).pow(e)
            return if (numeratorIsOdd) -magnitude else magnitude
        }
    }
}
