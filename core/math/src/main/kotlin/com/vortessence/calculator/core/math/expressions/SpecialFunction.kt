package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.numbers.Real
import com.vortessence.calculator.core.math.numbers.SpecialMath

enum class SpecialType(val functionName: String) {
    /** Γ(x), which continues the factorial: Γ(n + 1) = n!. */
    GAMMA("gamma"),
    /** ln Γ(x) for x > 0, which stays small where Γ(x) overflows. */
    LNGAMMA("lngamma"),
    /** The error function, 2/√π · ∫₀ˣ e^(−t²) dt. */
    ERF("erf"),
    /** 1 − erf(x). */
    ERFC("erfc");

    fun evaluate(v: Double): Double = when (this) {
        GAMMA -> SpecialMath.gamma(v)
        LNGAMMA -> SpecialMath.lnGamma(v)
        ERF -> SpecialMath.erf(v)
        ERFC -> SpecialMath.erfc(v)
    }

    /** The derivative with respect to the operand [u], or null where it would need a function we do not have (digamma). */
    fun outerDerivative(u: Expression): Expression? = when (this) {
        ERF, ERFC -> {
            // erf'(u) = 2/√π · e^(−u²)
            val gauss = Multiplications.of(Value.TWO, Power(Constant.PI, Value(-1L, 2L)), Power(Constant.E, Multiplications.of(Value.MINUS_ONE, Power(u, Value.TWO))))
            if (this == ERF) gauss else Multiplications.of(Value.MINUS_ONE, gauss)
        }
        GAMMA, LNGAMMA -> null
    }
}

/**
 * gamma, lngamma, erf and erfc of a plain number. They have exact values only in a few places
 * (Γ of whole and half-integers, erf(0)); everywhere else a number stays as the function in exact
 * mode and is calculated in numeric mode.
 */
class SpecialFunction(val type: SpecialType, val operand: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 1)
        return SpecialFunction(type, children[0])
    }

    override fun canonicalize(context: MathContext): Expression {
        val op = operand.canonicalize(context)
        if (context.shouldStop) return this
        if (op.containsExpressionMatching { it is MatrixExpression }) return fail(context, MathIssue.MATRIX_UNSUPPORTED)
        if (op.partWithUnit() != null) return fail(context, MathIssue.INVALID_UNIT)
        if (op.variables().isNotEmpty()) return SpecialFunction(type, op)
        if (op.containsImaginaryUnit()) return fail(context, MathIssue.UNDEFINED)

        val v = op.evaluateNumerically()
        if (v.isNaN()) return fail(context, MathIssue.UNDEFINED)
        val rational = (op as? Value)?.rational
        // Poles and the domain of ln Γ
        if (type == SpecialType.GAMMA && v <= 0.0 && v == Math.floor(v)) return fail(context, MathIssue.UNDEFINED)
        if (type == SpecialType.LNGAMMA && v <= 0.0) return fail(context, MathIssue.UNDEFINED)

        if (context.mode == CalculationMode.NUMERIC) {
            val result = type.evaluate(v)
            if (!result.isFinite()) return fail(context, if (result.isNaN()) MathIssue.UNDEFINED else MathIssue.VALUES_TOO_LARGE)
            return Value(Real(result))
        }
        exact(rational, context)?.let { return it }
        if (context.shouldStop) return this
        // An erf of a negative number is the negative of the erf of a positive one
        if (type == SpecialType.ERF && v < 0.0 && rational != null) {
            return Multiplications.of(Value.MINUS_ONE, SpecialFunction(type, op.negate())).canonicalize(context)
        }
        return SpecialFunction(type, op)
    }

    private fun fail(context: MathContext, issue: MathIssue): Expression {
        context.stopWithError(issue)
        return this
    }

    /** The exact value for a rational operand where there is one, otherwise null. */
    private fun exact(x: Rational?, context: MathContext): Expression? {
        x ?: return null
        return when (type) {
            SpecialType.ERF -> if (x.isZero) Value.ZERO else null
            SpecialType.ERFC -> if (x.isZero) Value.ONE else null
            SpecialType.LNGAMMA -> if (x == Rational.ONE || x == Rational.of(2L)) Value.ZERO else null
            SpecialType.GAMMA -> gamma(x, context)
        }
    }

    /** Γ(n) = (n − 1)! for a whole number n and Γ(m + 1/2) = c·√π for a half of an odd number, with c rational. */
    private fun gamma(x: Rational, context: MathContext): Expression? {
        if (!x.isFractional) {
            if (x > Rational.of(MAX_ARGUMENT)) return fail(context, MathIssue.VALUES_TOO_LARGE)
            return Value(BigInt.factorial(x.numerator.toLong() - 1))
        }
        if (x.denominator != BigInt(2L)) return null
        val steps = x - Rational.HALF
        if (steps.abs() > Rational.of(MAX_ARGUMENT)) return fail(context, MathIssue.VALUES_TOO_LARGE)
        // Γ(1/2) = √π and Γ(z + 1) = z·Γ(z), up or down from 1/2
        val count = steps.numerator.toLong().toInt()
        var factor = Rational.ONE
        if (count > 0) {
            for (j in 0 until count) factor *= Rational.HALF + j.toLong()
        } else {
            for (j in 1..-count) factor /= Rational.HALF - j.toLong()
        }
        return Multiplications.of(Value(factor), Power.squareRoot(Constant.PI))
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = type.evaluate(operand.evaluateNumerically(bindings))

    override fun equals(other: Any?): Boolean = other is SpecialFunction && type == other.type && operand == other.operand

    override fun hashCode(): Int = type.hashCode() * 31 + operand.hashCode() + 7

    override fun toString(): String = "${type.functionName}($operand)"

    private companion object {
        /** Γ(3000) = 2999!, as large as a factorial may be. */
        const val MAX_ARGUMENT = 3000L
    }
}
