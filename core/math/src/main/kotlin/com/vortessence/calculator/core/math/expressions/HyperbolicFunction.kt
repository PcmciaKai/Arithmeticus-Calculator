package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.Real
import kotlin.math.abs
import kotlin.math.acosh
import kotlin.math.asinh
import kotlin.math.atanh
import kotlin.math.cosh
import kotlin.math.sinh
import kotlin.math.tanh

enum class HyperbolicType(val symbol: String, val isInverse: Boolean) {
    SINH("sinh", false),
    COSH("cosh", false),
    TANH("tanh", false),
    SECH("sech", false),
    CSCH("csch", false),
    COTH("coth", false),
    ASINH("asinh", true),
    ACOSH("acosh", true),
    ATANH("atanh", true),
    ASECH("asech", true),
    ACSCH("acsch", true),
    ACOTH("acoth", true);

    /** Whether [v] is inside the real domain of this inverse function (always true for the others). */
    fun acceptsArgument(v: Double): Boolean = when (this) {
        ACOSH -> v >= 1.0
        ATANH -> abs(v) < 1.0
        ACOTH -> abs(v) > 1.0
        ASECH -> v > 0.0 && v <= 1.0
        ACSCH -> v != 0.0
        else -> true
    }

    /** The derivative of this function applied to [u] with respect to [u], before the chain rule. */
    fun outerDerivative(u: Expression): Expression {
        fun hyperbolic(type: HyperbolicType) = HyperbolicFunction(type, u)
        fun minus(e: Expression): Expression = Multiplications(listOf(Value.MINUS_ONE, e))
        fun squarePlus(c: Value) = Additions(listOf(Power(u, Value.TWO), c))
        val oneMinusUSquared = Additions(listOf(Value.ONE, Multiplications(listOf(Value.MINUS_ONE, Power(u, Value.TWO)))))
        return when (this) {
            SINH -> hyperbolic(COSH)
            COSH -> hyperbolic(SINH)
            TANH -> Power(hyperbolic(SECH), Value.TWO)
            SECH -> minus(Multiplications(listOf(hyperbolic(SECH), hyperbolic(TANH))))
            CSCH -> minus(Multiplications(listOf(hyperbolic(CSCH), hyperbolic(COTH))))
            COTH -> minus(Power(hyperbolic(CSCH), Value.TWO))
            ASINH -> Power(squarePlus(Value.ONE), Value(-1L, 2L))
            ACOSH -> Power(squarePlus(Value.MINUS_ONE), Value(-1L, 2L))
            ATANH, ACOTH -> Power(oneMinusUSquared, Value.MINUS_ONE)
            ASECH -> minus(Multiplications(listOf(Power(u, Value.MINUS_ONE), Power(oneMinusUSquared, Value(-1L, 2L)))))
            ACSCH -> minus(Multiplications(listOf(Power(AbsoluteValue(u), Value.MINUS_ONE), Power(squarePlus(Value.ONE), Value(-1L, 2L)))))
        }
    }

    /** Whether this function has a pole at [v]: csch and coth at 0. */
    fun hasPoleAt(v: Double): Boolean = (this == CSCH || this == COTH) && abs(v) < 1e-15
}

/**
 * sinh, cosh, tanh, sech, csch, coth and their inverses. Unlike the circular functions these take a plain number,
 * never an angle: there are no degrees and no periodicity.
 */
class HyperbolicFunction(
    val type: HyperbolicType,
    val operand: Expression
) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 1)
        return HyperbolicFunction(type, children[0])
    }

    override fun canonicalize(context: MathContext): Expression {
        val op = operand.canonicalize(context)
        if (context.shouldStop) return this
        if (op is MatrixExpression) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }
        if (op.partWithUnit() != null) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return this
        }

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
                val res = evaluate(v)
                if (!res.isFinite()) {
                    context.stopWithError(MathIssue.VALUES_TOO_LARGE)
                    return this
                }
                // Snap floating point noise to zero
                return Value(Real(if (abs(res) < 1e-14) 0.0 else res))
            }
            val exact = if (type.isInverse) exactInverse(op, context) else exactForward(op, context)
            if (exact != null || context.shouldStop) return exact ?: this
        }
        return HyperbolicFunction(type, op)
    }

    /** sinh(0) = 0, cosh(0) = 1 …, and the functions of ln(c), which are rational: sinh(ln 2) = 3/4. */
    private fun exactForward(op: Expression, context: MathContext): Expression? {
        val c: Expression = when {
            op == Value.ZERO -> Value.ONE
            op is Logarithm && op.base == Constant.E && op.operand is Value && (op.operand as Value).rational != null -> op.operand
            else -> return null
        }
        // e^x = c, so sinh = (c − 1/c)/2 and cosh = (c + 1/c)/2
        val inverse = Power(c, Value.MINUS_ONE)
        val sinh = Multiplications.of(Value.HALF, Additions(c, inverse.negate()))
        val cosh = Multiplications.of(Value.HALF, Additions(c, inverse))
        val result = when (type) {
            HyperbolicType.SINH -> sinh
            HyperbolicType.COSH -> cosh
            HyperbolicType.TANH -> Multiplications.of(sinh, Power(cosh, Value.MINUS_ONE))
            HyperbolicType.SECH -> Power(cosh, Value.MINUS_ONE)
            HyperbolicType.CSCH -> Power(sinh, Value.MINUS_ONE)
            else -> Multiplications.of(cosh, Power(sinh, Value.MINUS_ONE))
        }
        return result.canonicalize(context)
    }

    /** atanh(0) = 0, and where the logarithm form is rational: asinh(3/4) = ln 2, acosh(5/3) = ln 3. */
    private fun exactInverse(op: Expression, context: MathContext): Expression? {
        if (op !is Value || op.rational == null) return null
        if (type == HyperbolicType.ATANH && op.isZero) return Value.ZERO
        if (type == HyperbolicType.ATANH || type == HyperbolicType.ACOTH) return null
        // asinh(x) = ln(x + √(x² + 1)) and acosh(x) = ln(x + √(x² − 1)); asech and acsch are these at 1/x
        val reciprocal = type == HyperbolicType.ASECH || type == HyperbolicType.ACSCH
        val x = (if (reciprocal) Power(op, Value.MINUS_ONE).canonicalize(context) else op) as? Value ?: return null
        val plus = type == HyperbolicType.ASINH || type == HyperbolicType.ACSCH
        val root = Power(Additions(Power(x, Value.TWO), if (plus) Value.ONE else Value.MINUS_ONE), Value.HALF).canonicalize(context)
        if (root !is Value || root.rational == null) return null
        val inner = Additions(x, root).canonicalize(context)
        if (inner !is Value || inner.rational == null || inner.real.value <= 0.0) return null
        return Logarithm.ln(inner).canonicalize(context)
    }

    private fun complexValue(op: Expression, context: MathContext): Expression {
        val z = numericComplex(op)
        if (z == null || type.isInverse) {
            context.stopWithError(MathIssue.UNDEFINED)
            return this
        }
        val one = ComplexDouble(1.0, 0.0)
        val result = when (type) {
            HyperbolicType.SINH -> z.sinh()
            HyperbolicType.COSH -> z.cosh()
            HyperbolicType.TANH -> z.sinh() / z.cosh()
            HyperbolicType.SECH -> one / z.cosh()
            HyperbolicType.CSCH -> one / z.sinh()
            else -> z.cosh() / z.sinh()
        }
        if (!result.isFinite) {
            context.stopWithError(MathIssue.VALUES_TOO_LARGE)
            return this
        }
        return result.toExpression(context)
    }

    private fun evaluate(v: Double): Double = when (type) {
        HyperbolicType.SINH -> sinh(v)
        HyperbolicType.COSH -> cosh(v)
        HyperbolicType.TANH -> tanh(v)
        HyperbolicType.SECH -> 1.0 / cosh(v)
        HyperbolicType.CSCH -> 1.0 / sinh(v)
        HyperbolicType.COTH -> 1.0 / tanh(v)
        HyperbolicType.ASINH -> asinh(v)
        HyperbolicType.ACOSH -> acosh(v)
        HyperbolicType.ATANH -> atanh(v)
        HyperbolicType.ASECH -> acosh(1.0 / v)
        HyperbolicType.ACSCH -> asinh(1.0 / v)
        HyperbolicType.ACOTH -> atanh(1.0 / v)
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = evaluate(operand.evaluateNumerically(bindings))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HyperbolicFunction) return false
        return type == other.type && operand == other.operand
    }

    override fun hashCode(): Int = type.hashCode() * 31 + operand.hashCode()

    override fun toString(): String = "${type.symbol}($operand)"
}
