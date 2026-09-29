package com.vortessence.calculator.core.math.expressions

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

        if (cExp.isZero) return Value.ONE
        if (cExp == Value.ONE) return cBase
        if (cBase == Value.ONE) return Value.ONE
        if (cBase.isZero && cExp.isNegative) {
            context.stopWithError(MathIssue.DIVISION_BY_ZERO)
            return this
        }

        if (cBase is Value && cExp is Value) {
            return canonicalizeValues(cBase, cExp, context)
        }

        // (x^a)^b = x^(a * b)
        if (cBase is Power) {
            val newExp = Multiplications.of(cBase.exponent, cExp).canonicalize(context)
            return Power(cBase.base, newExp).canonicalize(context)
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

    override fun evaluateNumerically(value: Double, variable: Variable?): Double {
        val b = base.evaluateNumerically(value, variable)
        val e = exponent.evaluateNumerically(value, variable)
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
