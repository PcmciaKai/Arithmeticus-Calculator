package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.CalculationMode
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.numbers.BigInt
import com.vortessence.arithmeticus.core.math.numbers.Rational
import com.vortessence.arithmeticus.core.math.numbers.Real
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

class Logarithm(
    val base: Expression,
    val operand: Expression
) : Expression() {

    override val children: List<Expression> get() = listOf(base, operand)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 2)
        return Logarithm(children[0], children[1])
    }

    override fun canonicalize(context: MathContext): Expression {
        val cBase = base.canonicalize(context)
        if (context.shouldStop) return this
        val cOperand = operand.canonicalize(context)
        if (context.shouldStop) return this
        if (cBase is MatrixExpression || cOperand is MatrixExpression) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }

        // ln(5 m) has no value (it was ln(5), the unit dropped); ln(1 km/1 m) has, the units cancel
        if (listOf(cBase, cOperand).any { it.containsUnit() && dimensionOf(it)?.isNone != true }) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return this
        }

        if (context.complexNumbers) {
            val complex = complexLogarithm(cBase, cOperand, context)
            if (complex != null || context.shouldStop) return complex ?: this
        }

        // A number that is no plain number (e, ln(1/2), √2 − 2) outside of where the logarithm is defined: ln(0), ln(−1), log_e(√2 − 2)
        // were shown as results, as if they had a value. (Complex numbers have dealt with negative operands above; 0 is left.)
        if (cBase.variables().isEmpty() && cOperand.variables().isEmpty() && !cBase.containsImaginaryUnit() && !cOperand.containsImaginaryUnit() &&
            cOperand.partWithUnit() == null
        ) {
            val b = cBase.evaluateNumerically()
            val op = cOperand.evaluateNumerically()
            if (b <= 0.0 || b == 1.0 || op <= 0.0 || cOperand.isZero) {
                context.stopWithError(MathIssue.LOGARITHMIC_DOMAIN)
                return this
            }
            // ln(∞) = ∞ (and −∞ for a base below 1); a whole number too large for floating point (10⁵⁰⁰) is no ∞
            if (cOperand is Value && cOperand.rational == null && cOperand.real.value == Double.POSITIVE_INFINITY) {
                return Value(if (b > 1.0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY)
            }
            // ln(10⁵⁰⁰) = 500·ln(10): the logarithm of a power, which as a number is too large to take it of
            val whole = (cOperand as? Value)?.rational?.takeIf { !it.isFractional }?.numerator?.value
            if (whole != null && whole.bitLength() > LARGE_BITS) {
                perfectPower(whole)?.let { (root, exponent) ->
                    return Multiplications.of(Value(exponent.toLong()), Logarithm(cBase, Value(BigInt(root)))).canonicalize(context)
                }
            }
        }

        if (cOperand == Value.ONE) return Value.ZERO
        if (cBase == cOperand) return Value.ONE

        if (cOperand is Power && cOperand.base == cBase) {
            return cOperand.exponent
        }

        if (cBase is Value && cOperand is Value) {
            val b = cBase.real.value
            val op = cOperand.real.value
            if (b <= 0.0 || b == 1.0 || op <= 0.0) {
                context.stopWithError(MathIssue.LOGARITHMIC_DOMAIN)
                return this
            }
            if (context.mode == CalculationMode.NUMERIC) {
                return Value(Real(ln(op) / ln(b)))
            }
            val exact = exactRationalLog(cBase.rational, cOperand.rational, ln(op) / ln(b))
            if (exact != null) return Value(exact)
        }

        return Logarithm(cBase, cOperand)
    }

    /**
     * The principal logarithm of a negative or complex operand, log_b(z) = (ln|z| + i·arg z)/ln b,
     * e.g. ln(−1) = iπ. Exact for negative and imaginary operands, otherwise in floating point.
     * Null if the operand is not a constant negative or complex number.
     */
    private fun complexLogarithm(base: Expression, operand: Expression, context: MathContext): Expression? {
        if (base.variables().isNotEmpty() || operand.variables().isNotEmpty() || base.containsImaginaryUnit()) return null
        val b = base.evaluateNumerically()
        if (b <= 0.0 || b == 1.0) return null
        val (re, im) = complexParts(operand) ?: run {
            val z = numericComplex(operand) ?: return null
            return (z.ln() / ComplexDouble(ln(b), 0.0)).toExpression(context)
        }
        val modulus: Expression
        val argument: Expression
        when {
            im.isZero -> {
                if (re.evaluateNumerically() >= 0.0) return null
                modulus = re.negate()
                argument = Constant.PI
            }
            re.isZero -> {
                // ±b·i: arg = ±π/2
                val positive = im.evaluateNumerically() > 0.0
                modulus = if (positive) im else im.negate()
                argument = Multiplications.of(Value(if (positive) 1L else -1L, 2L), Constant.PI)
            }
            else -> {
                val z = numericComplex(operand) ?: return null
                return (z.ln() / ComplexDouble(ln(b), 0.0)).toExpression(context)
            }
        }
        val imaginaryPart = Multiplications.of(argument, Power(ln(base), Value.MINUS_ONE))
        return complexOf(Logarithm(base, modulus), imaginaryPart, context)
    }

    /**
     * Returns p/q with base^(p/q) == operand exactly, e.g. log_10(1000) = 3 or log_4(2) = 1/2.
     * The numeric estimate proposes a candidate with a small denominator, which is then
     * verified with exact arithmetic (base^p == operand^q).
     */
    private fun exactRationalLog(base: Rational?, operand: Rational?, estimate: Double): Rational? {
        if (base == null || operand == null || estimate.isNaN() || estimate.isInfinite()) return null
        for (q in 1L..MAX_EXACT_DENOMINATOR) {
            val scaled = estimate * q
            val p = scaled.roundToLong()
            if (abs(scaled - p) > 1e-9) continue
            val baseBits = maxOf(base.numerator.value.bitLength(), base.denominator.value.bitLength())
            val operandBits = maxOf(operand.numerator.value.bitLength(), operand.denominator.value.bitLength())
            if (baseBits.toDouble() * abs(p.toDouble()) > MAX_VERIFY_BITS || operandBits.toDouble() * q > MAX_VERIFY_BITS) {
                return null
            }
            return if (base.pow(p) == operand.pow(q)) Rational.of(p, q) else null
        }
        return null
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val b = base.evaluateNumerically(bindings)
        val op = operand.evaluateNumerically(bindings)
        return ln(op) / ln(b)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Logarithm) return false
        return base == other.base && operand == other.operand
    }

    override fun hashCode(): Int = base.hashCode() * 31 + operand.hashCode()

    override fun toString(): String {
        return if (base == Constant.E || (base is Value && base.real.value == E)) {
            "ln($operand)"
        } else if (base is Value && base.real.value == 10.0) {
            "log10($operand)"
        } else {
            "log_$base($operand)"
        }
    }

    companion object {
        /** From this size on, a whole number is no double, and its logarithm is taken of the power it is. */
        private const val LARGE_BITS = 1000

        /**
         * [n] as mᵉ with the largest exponent e ≥ 2 (10⁵⁰⁰ is 10⁵⁰⁰, 2¹⁰⁰⁰ is 2¹⁰⁰⁰); null if it is no such power. Each
         * exponent is tried with the root its size suggests, which is then checked exactly.
         */
        private fun perfectPower(n: java.math.BigInteger): Pair<java.math.BigInteger, Int>? {
            if (n.signum() <= 0) return null
            // ln(n) from its leading bits, exact enough for the roots below 2⁵²
            val shift = maxOf(0, n.bitLength() - 60)
            val lnN = ln(n.shiftRight(shift).toDouble()) + shift * ln(2.0)
            for (e in n.bitLength() downTo 2) {
                val root = Math.exp(lnN / e)
                if (root < 1.5) continue
                if (root > 4.0e15) break
                val m = java.math.BigInteger.valueOf(Math.round(root))
                if (m.pow(e) == n) return m to e
            }
            return null
        }

        private const val MAX_EXACT_DENOMINATOR = 12L
        private const val MAX_VERIFY_BITS = 100_000.0

        fun ln(operand: Expression): Logarithm = Logarithm(Constant.E, operand)
        fun log10(operand: Expression): Logarithm = Logarithm(Value(10L), operand)
    }
}
