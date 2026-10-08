package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.numbers.Real
import java.math.BigInteger
import kotlin.math.*

enum class TrigType(val symbol: String, val isInverse: Boolean) {
    SIN("sin", false),
    COS("cos", false),
    TAN("tan", false),
    ASIN("asin", true),
    ACOS("acos", true),
    ATAN("atan", true)
}

/**
 * @param angleUnit For sin/cos/tan: the unit the operand is expressed in. For the inverse
 * functions: the unit of the result. `null` means "not resolved yet, use the context's unit".
 * [canonicalize] always resolves it, so canonicalizing twice never converts degrees twice.
 */
class TrigonometricFunction(
    val type: TrigType,
    val operand: Expression,
    val angleUnit: AngleUnit? = null
) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 1)
        return TrigonometricFunction(type, children[0], angleUnit)
    }

    override fun canonicalize(context: MathContext): Expression {
        var op = operand.canonicalize(context)
        if (context.shouldStop) return this
        if (op is MatrixExpression) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }

        val resolvedUnit: AngleUnit
        if (!type.isInverse) {
            // Bring the operand into radians exactly once
            if (op.partWithUnit() != null) {
                val inRad = op.convertToUnit(UnitRegistry.RAD, false, context)
                if (inRad == null) {
                    if (!context.shouldStop) context.stopWithError(MathIssue.INVALID_UNIT)
                    return this
                }
                op = inRad
            } else if ((angleUnit ?: context.angleUnit) == AngleUnit.DEGREES) {
                op = Multiplications.of(op, Constant.PI, Value(1L, 180L)).canonicalize(context)
            }
            resolvedUnit = AngleUnit.RADIANS
        } else {
            resolvedUnit = angleUnit ?: context.angleUnit
        }
        if (context.shouldStop) return this

        val isConstant = op.variables().isEmpty()
        if (isConstant && op.containsImaginaryUnit()) return complexValue(op, context)
        if (isConstant) {
            val v = op.evaluateNumerically()
            if (type.isInverse && type != TrigType.ATAN && abs(v) > 1.0) {
                context.stopWithError(MathIssue.TRIGONOMETRIC_DOMAIN)
                return this
            }
            if (type == TrigType.TAN && abs(cos(v)) < 1e-15) {
                context.stopWithError(MathIssue.DIVISION_BY_ZERO)
                return this
            }
            if (context.mode == CalculationMode.NUMERIC) {
                val res = evaluate(v, resolvedUnit)
                // Snap floating point noise such as sin(pi) = 1.2e-16 to zero
                return Value(Real(if (abs(res) < 1e-14) 0.0 else res))
            }
        }

        if (!type.isInverse) {
            val simplified = ofArctangent(op, context)
            if (simplified != null || context.shouldStop) return simplified ?: this
        }

        val exact = if (!type.isInverse) {
            tryExactEvaluation(op, context)
        } else if (isConstant) {
            tryExactInverse(op, resolvedUnit, context)
        } else {
            null
        }
        if (exact != null || context.shouldStop) return exact ?: this

        return TrigonometricFunction(type, op, resolvedUnit)
    }

    /**
     * sin, cos and tan of atan(x) ± kπ (in radians), e.g. from a polar form 5·e^(i·atan(4/3)):
     * cos(atan x) = 1/√(1 + x²), sin(atan x) = x/√(1 + x²), tan(atan x) = x; adding kπ flips
     * the sign of sin and cos for odd k. Null for other operands.
     */
    private fun ofArctangent(op: Expression, context: MathContext): Expression? {
        val terms = if (op is Additions) op.operands else listOf(op)
        fun arctangentOf(term: Expression): Expression? {
            if (term is TrigonometricFunction && term.type == TrigType.ATAN && term.angleUnit == AngleUnit.RADIANS) return term.operand
            // −atan(x) = atan(−x)
            if (term is Multiplications && term.operands.size == 2 && term.operands[0] == Value.MINUS_ONE) {
                return arctangentOf(term.operands[1])?.let { Multiplications.of(Value.MINUS_ONE, it) }
            }
            return null
        }
        val atanIndex = terms.indexOfFirst { arctangentOf(it) != null }
        if (atanIndex < 0 || terms.count { arctangentOf(it) != null } > 1) return null
        val x = arctangentOf(terms[atanIndex])!!
        val rest = terms.filterIndexed { i, _ -> i != atanIndex }
        var oddShift = false
        if (rest.isNotEmpty()) {
            val k = (Multiplications.of(Additions.of(*rest.toTypedArray()), Power(Constant.PI, Value.MINUS_ONE)).canonicalize(context) as? Value)?.rational
            if (k == null || k.isFractional) return null
            oddShift = k.numerator.isOdd
        }
        val sign = if (oddShift) Value.MINUS_ONE else Value.ONE
        val inverseRoot = Power(Additions(listOf(Value.ONE, Power(x, Value.TWO))), Value(-1L, 2L))
        return when (type) {
            TrigType.SIN -> Multiplications.of(sign, x, inverseRoot)
            TrigType.COS -> Multiplications.of(sign, inverseRoot)
            else -> x
        }.canonicalize(context)
    }

    private fun tryExactEvaluation(op: Expression, context: MathContext): Expression? {
        // op could be k * pi / 12: coeff = op / pi
        val coeffExpr = Multiplications.of(op, Power.of(Constant.PI, Value.MINUS_ONE)).canonicalize(context)
        val coeffVal = (coeffExpr as? Value)?.rational ?: return null

        // twelfths of pi: k = coeffVal * 12
        val twelfthsRational = coeffVal * Rational.of(12L)
        if (twelfthsRational.isFractional) return null

        val k = twelfthsRational.numerator.value.mod(BigInteger.valueOf(24L)).toLong()

        val result = when (type) {
            TrigType.SIN -> exactSin(k)
            TrigType.COS -> exactSin((k + 6L) % 24L)
            TrigType.TAN -> exactTan(k % 12L, context) ?: return null
            else -> return null
        }
        return result.canonicalize(context)
    }

    /** sin(k * pi / 12) for k in 0..23. */
    private fun exactSin(k: Long): Expression {
        val sqrt2 = Power(Value(2L), Value.HALF)
        val sqrt3 = Power(Value(3L), Value.HALF)
        val sqrt6 = Power(Value(6L), Value.HALF)
        val quarter = Value(1L, 4L)
        val half = Value.HALF

        return when (k) {
            0L -> Value.ZERO
            1L -> Multiplications.of(quarter, Additions(sqrt6, sqrt2.negate())) // (√6 − √2)/4
            2L -> half
            3L -> Multiplications.of(half, sqrt2)
            4L -> Multiplications.of(half, sqrt3)
            5L -> Multiplications.of(quarter, Additions(sqrt6, sqrt2)) // (√6 + √2)/4
            6L -> Value.ONE
            in 7L..11L -> exactSin(12L - k) // sin(pi - x) = sin(x)
            in 12L..23L -> exactSin(k - 12L).negate() // sin(pi + x) = -sin(x)
            else -> error("k must be in 0..23")
        }
    }

    /** tan(k * pi / 12) for k in 0..11 (tan has period pi). */
    private fun exactTan(k: Long, context: MathContext): Expression? {
        val sqrt3 = Power(Value(3L), Value.HALF)
        return when (k) {
            0L -> Value.ZERO
            1L -> Additions(Value(2L), sqrt3.negate()) // 2 − √3
            2L -> Multiplications.of(Value(1L, 3L), sqrt3) // 1/√3 = √3/3
            3L -> Value.ONE
            4L -> sqrt3
            5L -> Additions(Value(2L), sqrt3) // 2 + √3
            6L -> {
                context.stopWithError(MathIssue.DIVISION_BY_ZERO)
                null
            }
            in 7L..11L -> exactTan(12L - k, context)?.negate() // tan(pi - x) = -tan(x)
            else -> null
        }
    }

    /**
     * asin/acos/atan of values whose result is a multiple of pi/12 (15°), e.g. asin(1/2) = pi/6.
     * Only applied to exact operands so that decimal inputs stay decimal.
     */
    private fun tryExactInverse(op: Expression, unit: AngleUnit, context: MathContext): Expression? {
        if (op.containsExpressionMatching { it is Value && it.rational == null }) return null
        val radians = evaluate(op.evaluateNumerically(), AngleUnit.RADIANS)
        if (radians.isNaN()) return null
        val twelfths = radians / (PI / 12.0)
        val k = twelfths.roundToLong()
        if (abs(twelfths - k) > 1e-9) return null
        return if (unit == AngleUnit.DEGREES) {
            Value(k * 15L)
        } else {
            Multiplications.of(Value(k, 12L), Constant.PI).canonicalize(context)
        }
    }

    /** sin, cos or tan of a complex number (in radians), in floating point. */
    private fun complexValue(op: Expression, context: MathContext): Expression {
        val z = numericComplex(op)
        if (z == null || type.isInverse) {
            context.stopWithError(MathIssue.UNDEFINED)
            return this
        }
        val result = when (type) {
            TrigType.SIN -> z.sin()
            TrigType.COS -> z.cos()
            else -> z.sin() / z.cos()
        }
        if (!result.isFinite) {
            context.stopWithError(MathIssue.VALUES_TOO_LARGE)
            return this
        }
        return result.toExpression(context)
    }

    /** Evaluates this function for a numeric operand, honoring [unit] (see [angleUnit]). */
    private fun evaluate(v: Double, unit: AngleUnit): Double {
        val toRadians = if (unit == AngleUnit.DEGREES) PI / 180.0 else 1.0
        return when (type) {
            TrigType.SIN -> sin(v * toRadians)
            TrigType.COS -> cos(v * toRadians)
            TrigType.TAN -> tan(v * toRadians)
            TrigType.ASIN -> asin(v) / toRadians
            TrigType.ACOS -> acos(v) / toRadians
            TrigType.ATAN -> atan(v) / toRadians
        }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val v = operand.evaluateNumerically(bindings)
        return evaluate(v, angleUnit ?: MathContext.current().angleUnit)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrigonometricFunction) return false
        return type == other.type && operand == other.operand && angleUnit == other.angleUnit
    }

    override fun hashCode(): Int = (type.hashCode() * 31 + operand.hashCode()) * 31 + (angleUnit?.hashCode() ?: 0)

    override fun toString(): String = "${type.symbol}($operand)"
}
