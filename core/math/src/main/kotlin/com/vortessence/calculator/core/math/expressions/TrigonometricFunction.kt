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
    ATAN("atan", true),
    SEC("sec", false),
    CSC("csc", false),
    COT("cot", false),
    ASEC("asec", true),
    ACSC("acsc", true),
    ACOT("acot", true);

    /** Whether [v] is inside the real domain of this inverse function (always true for the others). */
    fun acceptsArgument(v: Double): Boolean = when (this) {
        ASIN, ACOS -> abs(v) <= 1.0
        ASEC, ACSC -> abs(v) >= 1.0
        else -> true
    }

    /** The derivative of this function applied to [u] with respect to [u], before the chain rule and the degree factor. */
    fun outerDerivative(u: Expression, unit: AngleUnit): Expression {
        fun trig(type: TrigType) = TrigonometricFunction(type, u, unit)
        fun minus(e: Expression): Expression = Multiplications(listOf(Value.MINUS_ONE, e))
        val oneMinusUSquared = Additions(listOf(Value.ONE, Multiplications(listOf(Value.MINUS_ONE, Power(u, Value.TWO)))))
        // 1/(|u|·√(u² − 1))
        val secantSlope = Multiplications(listOf(
            Power(AbsoluteValue(u), Value.MINUS_ONE),
            Power(Additions(listOf(Power(u, Value.TWO), Value.MINUS_ONE)), Value(-1L, 2L))
        ))
        return when (this) {
            SIN -> trig(COS)
            COS -> minus(trig(SIN))
            TAN -> Power(trig(COS), Value(-2L))
            SEC -> Multiplications(listOf(trig(SEC), trig(TAN)))
            CSC -> minus(Multiplications(listOf(trig(CSC), trig(COT))))
            COT -> minus(Power(trig(CSC), Value.TWO))
            ASIN -> Power(oneMinusUSquared, Value(-1L, 2L))
            ACOS -> minus(Power(oneMinusUSquared, Value(-1L, 2L)))
            ATAN -> Power(Additions(listOf(Value.ONE, Power(u, Value.TWO))), Value.MINUS_ONE)
            ASEC -> secantSlope
            ACSC -> minus(secantSlope)
            ACOT -> minus(Power(Additions(listOf(Value.ONE, Power(u, Value.TWO))), Value.MINUS_ONE))
        }
    }

    /** Whether this function has a pole at the angle (in radians) [v]: where its denominator vanishes. */
    fun hasPoleAt(v: Double): Boolean = when (this) {
        TAN, SEC -> abs(cos(v)) < 1e-15
        CSC, COT -> abs(sin(v)) < 1e-15
        else -> false
    }
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
            if (type.isInverse && !type.acceptsArgument(v)) {
                context.stopWithError(MathIssue.TRIGONOMETRIC_DOMAIN)
                return this
            }
            if (type.hasPoleAt(v)) {
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
     * The circular functions of atan(x) ± kπ (in radians), e.g. from a polar form 5·e^(i·atan(4/3)):
     * cos(atan x) = 1/√(1 + x²), sin(atan x) = x/√(1 + x²), tan(atan x) = x, and their reciprocals;
     * adding kπ flips the sign of everything but tan and cot for odd k. Null for other operands.
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
        val root = Power(Additions(listOf(Value.ONE, Power(x, Value.TWO))), Value.HALF)
        return when (type) {
            TrigType.SIN -> Multiplications.of(sign, x, inverseRoot)
            TrigType.COS -> Multiplications.of(sign, inverseRoot)
            TrigType.SEC -> Multiplications.of(sign, root)
            TrigType.CSC -> Multiplications.of(sign, root, Power(x, Value.MINUS_ONE))
            TrigType.COT -> Power(x, Value.MINUS_ONE)
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
            TrigType.SEC -> exactCsc((k + 6L) % 24L, context) ?: return null
            TrigType.CSC -> exactCsc(k, context) ?: return null
            TrigType.COT -> exactCot(k % 12L, context) ?: return null
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

    /** csc(k * pi / 12) for k in 0..23, which is also sec((k − 6) * pi / 12). */
    private fun exactCsc(k: Long, context: MathContext): Expression? {
        val sqrt2 = Power(Value(2L), Value.HALF)
        val sqrt3 = Power(Value(3L), Value.HALF)
        val sqrt6 = Power(Value(6L), Value.HALF)
        return when (k) {
            0L, 12L -> {
                context.stopWithError(MathIssue.DIVISION_BY_ZERO)
                null
            }
            1L -> Additions(sqrt6, sqrt2) // √6 + √2
            2L -> Value.TWO
            3L -> sqrt2
            4L -> Multiplications.of(Value(2L, 3L), sqrt3) // 2/√3
            5L -> Additions(sqrt6, sqrt2.negate()) // √6 − √2
            6L -> Value.ONE
            in 7L..11L -> exactCsc(12L - k, context)
            in 12L..23L -> exactCsc(k - 12L, context)?.negate()
            else -> null
        }
    }

    /** cot(k * pi / 12) for k in 0..11 (cot has period pi). */
    private fun exactCot(k: Long, context: MathContext): Expression? {
        val sqrt3 = Power(Value(3L), Value.HALF)
        return when (k) {
            0L -> {
                context.stopWithError(MathIssue.DIVISION_BY_ZERO)
                null
            }
            1L -> Additions(Value(2L), sqrt3) // 2 + √3
            2L -> sqrt3
            3L -> Value.ONE
            4L -> Multiplications.of(Value(1L, 3L), sqrt3) // 1/√3 = √3/3
            5L -> Additions(Value(2L), sqrt3.negate()) // 2 − √3
            6L -> Value.ZERO
            in 7L..11L -> exactCot(12L - k, context)?.negate() // cot(pi - x) = -cot(x)
            else -> null
        }
    }

    /**
     * asin/acos/atan (and asec, acsc, acot) of values whose result is a multiple of pi/12 (15°), e.g. asin(1/2) = pi/6.
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
        val one = ComplexDouble(1.0, 0.0)
        val result = when (type) {
            TrigType.SIN -> z.sin()
            TrigType.COS -> z.cos()
            TrigType.TAN -> z.sin() / z.cos()
            TrigType.SEC -> one / z.cos()
            TrigType.CSC -> one / z.sin()
            else -> z.cos() / z.sin()
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
            TrigType.SEC -> 1.0 / cos(v * toRadians)
            TrigType.CSC -> 1.0 / sin(v * toRadians)
            // cos/sin rather than 1/tan, so that cot(pi/2) is 0 instead of a division by a huge tan
            TrigType.COT -> cos(v * toRadians) / sin(v * toRadians)
            TrigType.ASEC -> acos(1.0 / v) / toRadians
            TrigType.ACSC -> asin(1.0 / v) / toRadians
            // The principal value of acot lies in (0, pi), so acot(0) = pi/2 and acot(−1) = 3pi/4
            TrigType.ACOT -> atan2(1.0, v) / toRadians
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


/**
 * The two terms of a sum that are squares of functions of the same angle whose squares make 1: c·R·sin²(u) + c·R·cos²(u) = c·R,
 * and c·R·sec²(u) − c·R·tan²(u), c·R·csc²(u) − c·R·cot²(u), c·R·cosh²(u) − c·R·sinh²(u), with the same factors R besides.
 * Null if [a] and [b] are not such a pair.
 */
internal fun pythagoreanSum(a: Expression, b: Expression): Expression? {
    // (the function that comes first in the identity, the other one, whether the squares are added: otherwise subtracted)
    val identities = listOf<Triple<Any, Any, Boolean>>(
        Triple(TrigType.SIN, TrigType.COS, true),
        Triple(TrigType.SEC, TrigType.TAN, false),
        Triple(TrigType.CSC, TrigType.COT, false),
        Triple(HyperbolicType.COSH, HyperbolicType.SINH, false)
    )
    fun typeOf(f: Expression): Any? = (f as? TrigonometricFunction)?.type ?: (f as? HyperbolicFunction)?.type
    fun angleUnitOf(f: Expression): Any? = (f as? TrigonometricFunction)?.angleUnit
    fun operandOf(f: Expression): Expression? = (f as? TrigonometricFunction)?.operand ?: (f as? HyperbolicFunction)?.operand
    /** (the function, the number factor, the other factors) of c·R·f², or null. */
    fun split(term: Expression): Triple<Expression, Value, List<Expression>>? {
        val factors = if (term is Multiplications) term.operands else listOf(term)
        val index = factors.indexOfFirst { factor ->
            factor is Power && (factor.exponent as? Value)?.rational == com.vortessence.calculator.core.math.numbers.Rational.of(2L) &&
                typeOf(factor.base).let { type -> type != null && identities.any { type == it.first || type == it.second } }
        }
        if (index < 0) return null
        val function = (factors[index] as Power).base
        val rest = factors.filterIndexed { i, _ -> i != index }
        val coefficient = rest.filterIsInstance<Value>().fold(Value.ONE) { product, value -> product.times(value) }
        return Triple(function, coefficient, rest.filter { it !is Value })
    }
    val first = split(a) ?: return null
    val second = split(b) ?: return null
    val others = first.third
    if (others.size != second.third.size || !sameOperandsIgnoringOrder(others, second.third)) return null
    if (operandOf(first.first) != operandOf(second.first) || angleUnitOf(first.first) != angleUnitOf(second.first)) return null
    val firstType = typeOf(first.first)
    val secondType = typeOf(second.first)
    for ((leading, other, sum) in identities) {
        val firstLeads = firstType == leading && secondType == other
        if (!firstLeads && !(firstType == other && secondType == leading)) continue
        // c·f² ± c·g²: the numbers are the same, or opposite for a difference (the leading term is the positive one)
        val (lead, trail) = if (firstLeads) first to second else second to first
        if (if (sum) lead.second != trail.second else lead.second != trail.second.negate()) continue
        val parts = (if (lead.second == Value.ONE) emptyList() else listOf<Expression>(lead.second)) + others
        return if (parts.isEmpty()) Value.ONE else Multiplications.of(*parts.toTypedArray())
    }
    return null
}

/** sec ↔ cos, csc ↔ sin, cot ↔ tan, sech ↔ cosh, csch ↔ sinh, coth ↔ tanh of the same operand: the function whose reciprocal [e] is. */
internal fun reciprocalPartner(e: Expression): Expression? = when (e) {
    is TrigonometricFunction -> when (e.type) {
        TrigType.SEC -> TrigType.COS
        TrigType.COS -> TrigType.SEC
        TrigType.CSC -> TrigType.SIN
        TrigType.SIN -> TrigType.CSC
        TrigType.COT -> TrigType.TAN
        TrigType.TAN -> TrigType.COT
        else -> null
    }?.let { TrigonometricFunction(it, e.operand, e.angleUnit) }
    is HyperbolicFunction -> when (e.type) {
        HyperbolicType.SECH -> HyperbolicType.COSH
        HyperbolicType.COSH -> HyperbolicType.SECH
        HyperbolicType.CSCH -> HyperbolicType.SINH
        HyperbolicType.SINH -> HyperbolicType.CSCH
        HyperbolicType.COTH -> HyperbolicType.TANH
        HyperbolicType.TANH -> HyperbolicType.COTH
        else -> null
    }?.let { HyperbolicFunction(it, e.operand) }
    else -> null
}
