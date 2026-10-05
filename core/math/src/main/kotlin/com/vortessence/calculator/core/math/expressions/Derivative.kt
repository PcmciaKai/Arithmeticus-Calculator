package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue

/**
 * The derivative of [body] with respect to [variable], computed symbolically, e.g.
 * d/dx x³ = 3x². With a [point] it is the derivative's value at variable = point, and the
 * variable is bound (like an integration variable) instead of staying free in the result.
 */
class Derivative(
    val body: Expression,
    val variable: Variable,
    val point: Expression? = null
) : Expression() {

    override val children: List<Expression> get() = listOfNotNull(body, point)

    override fun variables(): Set<Variable> =
        if (point == null) body.variables() else body.variables() - variable + point.variables()

    override fun copyWithChildren(children: List<Expression>): Expression =
        Derivative(children[0], variable, children.getOrNull(1))

    override fun canonicalize(context: MathContext): Expression {
        val cBody = body.canonicalize(context)
        if (context.shouldStop) return this
        val cPoint = point?.canonicalize(context)
        if (context.shouldStop) return this
        if (cBody.containsExpressionMatching { it is MatrixExpression } ||
            cPoint?.containsExpressionMatching { it is MatrixExpression } == true
        ) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }

        val derivative = Differentiator.derivative(cBody, variable) ?: run {
            context.stopWithError(MathIssue.CANT_DIFFERENTIATE)
            return this
        }
        val result = derivative.canonicalize(context)
        if (context.shouldStop) return this
        if (cPoint == null) return result
        return result.substituteVariables(mapOf(variable to cPoint)).canonicalize(context)
    }

    /** The symbolic derivative, for numeric evaluation (e.g. plotting); null if there is none. */
    private val symbolic: Expression? by lazy {
        val context = MathContext.current().copy()
        val cBody = body.canonicalize(context)
        val derivative = if (context.shouldStop) null else Differentiator.derivative(cBody, variable)
        derivative?.canonicalize(context)?.takeUnless { context.shouldStop }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val derivative = symbolic ?: return Double.NaN
        if (point == null) return derivative.evaluateNumerically(bindings)
        return derivative.evaluateNumerically(bindings + (variable to point.evaluateNumerically(bindings)))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Derivative) return false
        return body == other.body && variable == other.variable && point == other.point
    }

    override fun hashCode(): Int = (body.hashCode() * 31 + variable.hashCode()) * 31 + (point?.hashCode() ?: 0)

    override fun toString(): String =
        "d/d$variable($body)" + if (point != null) "|$variable=$point" else ""
}

/**
 * Symbolic differentiation of canonical expressions (sums, products, powers, logarithms,
 * trigonometric functions, integrals with variable bounds). The results are not simplified;
 * canonicalize them.
 */
internal object Differentiator {

    /** d/d[v] of [e], or null if [e] contains something that cannot be differentiated. */
    fun derivative(e: Expression, v: Variable): Expression? {
        if (!e.containsVariable(v)) return Value.ZERO
        return when (e) {
            is Variable -> Value.ONE
            is Additions -> Additions(e.operands.map { derivative(it, v) ?: return null })
            is Multiplications -> {
                // Product rule: one factor differentiated at a time
                val terms = e.operands.indices.map { i ->
                    val factor = derivative(e.operands[i], v) ?: return null
                    Multiplications(e.operands.mapIndexed { j, op -> if (j == i) factor else op })
                }
                Additions(terms)
            }
            is Power -> power(e, v)
            is Logarithm -> logarithm(e, v)
            is TrigonometricFunction -> trigonometric(e, v)
            is Integral -> integral(e, v)
            else -> null
        }
    }

    private fun power(e: Power, v: Variable): Expression? {
        val base = e.base
        val exponent = e.exponent
        return when {
            // (u^n)' = n·u^(n−1)·u'
            !exponent.containsVariable(v) -> Multiplications(listOf(
                exponent,
                Power(base, Additions(listOf(exponent, Value.MINUS_ONE))),
                derivative(base, v) ?: return null
            ))
            // (a^u)' = a^u·ln(a)·u'
            !base.containsVariable(v) -> Multiplications(listOf(
                e,
                Logarithm.ln(base),
                derivative(exponent, v) ?: return null
            ))
            // (u^w)' = u^w·(w'·ln(u) + w·u'/u)
            else -> Multiplications(listOf(
                e,
                Additions(listOf(
                    Multiplications(listOf(derivative(exponent, v) ?: return null, Logarithm.ln(base))),
                    Multiplications(listOf(exponent, derivative(base, v) ?: return null, Power(base, Value.MINUS_ONE)))
                ))
            ))
        }
    }

    private fun logarithm(e: Logarithm, v: Variable): Expression? {
        if (e.base.containsVariable(v)) {
            // log_b(u) = ln(u) / ln(b)
            return derivative(Multiplications(listOf(Logarithm.ln(e.operand), Power(Logarithm.ln(e.base), Value.MINUS_ONE))), v)
        }
        // log_b(u)' = u' / (u·ln(b))
        return Multiplications(listOf(
            derivative(e.operand, v) ?: return null,
            Power(Multiplications(listOf(e.operand, Logarithm.ln(e.base))), Value.MINUS_ONE)
        ))
    }

    private fun trigonometric(e: TrigonometricFunction, v: Variable): Expression? {
        val u = e.operand
        val du = derivative(u, v) ?: return null
        val unit = e.angleUnit ?: MathContext.current().angleUnit
        fun trig(type: TrigType) = TrigonometricFunction(type, u, unit)
        val oneMinusUSquared = Additions(listOf(Value.ONE, Multiplications(listOf(Value.MINUS_ONE, Power(u, Value.TWO)))))
        val outer: Expression = when (e.type) {
            TrigType.SIN -> trig(TrigType.COS)
            TrigType.COS -> Multiplications(listOf(Value.MINUS_ONE, trig(TrigType.SIN)))
            TrigType.TAN -> Power(trig(TrigType.COS), Value(-2L))
            TrigType.ASIN -> Power(oneMinusUSquared, Value(-1L, 2L))
            TrigType.ACOS -> Multiplications(listOf(Value.MINUS_ONE, Power(oneMinusUSquared, Value(-1L, 2L))))
            TrigType.ATAN -> Power(Additions(listOf(Value.ONE, Power(u, Value.TWO))), Value.MINUS_ONE)
        }
        // With angles in degrees: sin's operand and the inverse functions' results are scaled
        val degreeFactor: Expression = when {
            unit != AngleUnit.DEGREES -> Value.ONE
            e.type.isInverse -> Multiplications(listOf(Value(180L), Power(Constant.PI, Value.MINUS_ONE)))
            else -> Multiplications(listOf(Constant.PI, Value(1L, 180L)))
        }
        // The inner derivative first, so it reads 2x·cos(x²) rather than cos(x²)·2x
        return Multiplications(listOf(du, outer, degreeFactor))
    }

    /** Fundamental theorem of calculus: (∫_a^b f(t) dt)' = f(b)·b' − f(a)·a'. */
    private fun integral(e: Integral, v: Variable): Expression? {
        // Differentiating under the integral sign is not supported
        if (e.variable != v && e.integrand.containsVariable(v)) return null
        fun integrandAt(bound: Expression) = e.integrand.substituteVariables(mapOf(e.variable to bound))
        return Additions(listOf(
            Multiplications(listOf(integrandAt(e.upperBound), derivative(e.upperBound, v) ?: return null)),
            Multiplications(listOf(Value.MINUS_ONE, integrandAt(e.lowerBound), derivative(e.lowerBound, v) ?: return null))
        ))
    }
}
