package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.numbers.Real
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
        return if (base == Constant.E || (base is Value && base.real.value == Math.E)) {
            "ln($operand)"
        } else if (base is Value && base.real.value == 10.0) {
            "log10($operand)"
        } else {
            "log_$base($operand)"
        }
    }

    companion object {
        private const val MAX_EXACT_DENOMINATOR = 12L
        private const val MAX_VERIFY_BITS = 100_000.0

        fun ln(operand: Expression): Logarithm = Logarithm(Constant.E, operand)
        fun log10(operand: Expression): Logarithm = Logarithm(Value(10L), operand)
    }
}
