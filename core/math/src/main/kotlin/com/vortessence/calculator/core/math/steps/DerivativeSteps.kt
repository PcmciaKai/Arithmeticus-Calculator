package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.AbsoluteValue
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Constant
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Integral
import com.vortessence.calculator.core.math.expressions.Logarithm
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.TrigType
import com.vortessence.calculator.core.math.expressions.HyperbolicFunction
import com.vortessence.calculator.core.math.expressions.SpecialFunction
import com.vortessence.calculator.core.math.expressions.SpecialType
import com.vortessence.calculator.core.math.expressions.HyperbolicType
import com.vortessence.calculator.core.math.expressions.TrigonometricFunction
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.substituteVariables

/**
 * The steps of differentiating, as in a textbook: each step applies one rule to the first
 * derivative that is still open, leaving derivatives of inner functions open for later steps,
 * e.g. d/dx(x²·sin(x)) = d/dx(x²)·sin(x) + x²·d/dx(sin(x)) = 2x·sin(x) + x²·d/dx(sin(x)) = …
 * Then numbers are multiplied, like terms combined and so on, one at a time
 * ([SimplificationSteps]); whatever is left to reach the calculated result is a last step.
 * The rules work on the expression as it was typed, so the steps stay close to the input.
 */
internal object DerivativeSteps {

    /** Guards against rules that would keep rewriting each other. */
    private const val MAX_STEPS = 100

    /** The steps for [derivative], or null if one of its parts has no rule here. */
    fun steps(derivative: Derivative, context: MathContext): ExpressionSolution? {
        // The parser nests sums ((3x² − 2x) + 1), which would take two steps of the sum rule
        val body = normalize(derivative.body)
        val variable = derivative.variable
        if (body.containsExpressionMatching { it is Derivative || it is MatrixExpression }) return null

        val simplified = Derivative(body, variable).canonicalize(context)
        if (context.shouldStop) return null
        val compact = displayForm(simplified, context)

        val solution = Solver(context)
        solution.start(Derivative(body, variable, derivative.point))
        // Differentiate, then simplify step by step
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return null
            solution.apply(derivativeMatch(solution.current, context) ?: break)
        }
        if (solution.current.containsExpressionMatching { it is Derivative || it is BackSubstitution }) return null
        if (!solution.simplifyToward(compact, MAX_STEPS)) return null

        val point = derivative.point ?: return solution.finish(compact)
        val variablePaths = pathsOf(solution.current) { it == variable }
        val substituted = unmarked(solution.current).substituteVariables(mapOf(variable to point))
        solution.apply(Match(StepRule.SUBSTITUTE, variablePaths.associateWith { point }, variable, point))
        val value = derivative.canonicalize(context)
        if (context.shouldStop) return null
        solution.apply(Match(StepRule.EVALUATE, mapOf(emptyList<Int>() to value), substituted, value))
        return solution.finish(value)
    }

    /** The first derivative that is still open, with one rule applied to it. */
    internal fun derivativeMatch(e: Expression, context: MathContext): Match? {
        // An inner function substituted for the chain rule goes back in as soon as its outer
        // derivative is done
        val path = pathsOf(e) {
            it is Derivative || (it is BackSubstitution && !it.inner.containsExpressionMatching { inner -> inner is Derivative })
        }.firstOrNull() ?: return null
        val part = at(e, path)
        if (part is BackSubstitution) {
            val result = normalize(unmarked(part.inner).substituteVariables(mapOf(part.u to part.value)))
            return Match(StepRule.BACK_SUBSTITUTION, mapOf(path to result), part.u, part.value)
        }
        val open = part as Derivative
        val taken = pathsOf(e) { it is Variable }.map { at(e, it) as Variable }.toSet()
        // The body may be highlighted, after it was written as a power
        val rewrite = Rules(open.variable, taken, context).apply(unmarked(open.body)) ?: return null
        val before = Derivative(unmarked(open.body), open.variable)
        return if (rewrite.ofBody) {
            // Only the expression is written differently, the derivative stays open
            Match(rewrite.rule, mapOf(path + 0 to rewrite.expression), unmarked(open.body), rewrite.expression, notes = rewrite.notes)
        } else {
            Match(rewrite.rule, mapOf(path to rewrite.expression), before, rewrite.expression, notes = rewrite.notes)
        }
    }

    /**
     * Whether [steps] has a rule for every part of [derivative], without working them out
     * (fast enough to decide whether to offer the steps at all).
     */
    fun isSupported(derivative: Derivative): Boolean =
        !derivative.body.containsExpressionMatching { it is Derivative || it is MatrixExpression } &&
            hasRules(derivative.body, derivative.variable)

    /** Mirrors [Rules.apply]: each rule only needs derivatives of the children of [e]. */
    private fun hasRules(e: Expression, v: Variable): Boolean {
        if (!e.containsVariable(v)) return true
        return when (e) {
            v -> true
            is Additions, is Multiplications, is Power, is Logarithm, is TrigonometricFunction, is HyperbolicFunction, is AbsoluteValue ->
                e.children.all { hasRules(it, v) }
            // The digamma would need the trigamma, and the normal quantile has no rule of its own
            is SpecialFunction -> specialRule(e.type) != null && e.type.outerDerivative(e.operand) != null && hasRules(e.operand, v)
            is Integral -> (e.variable == v || !e.integrand.containsVariable(v)) &&
                hasRules(e.lowerBound, v) && hasRules(e.upperBound, v)
            else -> false
        }
    }

    /**
     * The derivative rewritten by [rule]; with [ofBody] only the expression in it, which is
     * written differently (√x as x^(1/2)) and still to be differentiated.
     */
    private data class Rewrite(
        val expression: Expression,
        val rule: StepRule,
        val ofBody: Boolean = false,
        val notes: List<Note> = emptyList()
    )

    /** The rules for derivatives with respect to [v]; [taken] are the variables already in use. */
    private class Rules(val v: Variable, val taken: Set<Variable>, val context: MathContext) {

        fun apply(u: Expression): Rewrite? {
            if (!u.containsVariable(v)) return Rewrite(Value.ZERO, StepRule.CONSTANT)
            // A function of an inner function: the chain rule first, with the inner function named
            chainRule(u)?.let { return it }
            return when (u) {
                v -> Rewrite(Value.ONE, StepRule.VARIABLE)
                is Additions -> sum(u)
                is Multiplications -> product(u)
                is LiteralPower -> power(u.inner as? Power ?: return null, literal = true)
                is Power -> rewriteAsPower(u) ?: power(u, literal = false)
                is Logarithm -> logarithm(u)
                is TrigonometricFunction -> trigonometric(u)
                is HyperbolicFunction -> hyperbolic(u)
                is SpecialFunction -> special(u)
                is AbsoluteValue -> Rewrite(product(u.operand, Power(u, Value.MINUS_ONE)), StepRule.ABSOLUTE_VALUE)
                is Integral -> integral(u)
                else -> null
            }
        }

        private fun d(u: Expression) = Derivative(u, v)

        /**
         * (f(g(x)))' = f'(u)·g'(x) with u = g(x): the outer derivative (with respect to u, put
         * back in afterwards) and the inner one, for a power, exponential, logarithm,
         * trigonometric function or absolute value of an inner function g(x) other than x.
         */
        private fun chainRule(e: Expression): Rewrite? {
            val power = (e as? LiteralPower)?.inner as? Power ?: e as? Power
            val wrap = { outer: Expression -> if (e is LiteralPower) LiteralPower(outer) else outer }
            val (inner, outerOf) = when {
                power != null && !power.exponent.containsVariable(v) && !needsRewrite(power, e is LiteralPower) ->
                    power.base to { w: Expression -> wrap(Power(w, power.exponent)) }
                power != null && !power.base.containsVariable(v) -> power.exponent to { w: Expression -> Power(power.base, w) }
                e is Logarithm && !e.base.containsVariable(v) -> e.operand to { w: Expression -> Logarithm(e.base, w) }
                e is TrigonometricFunction -> e.operand to { w: Expression -> TrigonometricFunction(e.type, w, e.angleUnit ?: context.angleUnit) }
                e is HyperbolicFunction -> e.operand to { w: Expression -> HyperbolicFunction(e.type, w) }
                e is SpecialFunction -> e.operand to { w: Expression -> SpecialFunction(e.type, w) }
                e is AbsoluteValue -> e.operand to { w: Expression -> AbsoluteValue(w) }
                else -> return null
            }
            if (unmarked(inner) == v) return null
            val u = listOf("u", "t", "w", "z").map { Variable(it) }.firstOrNull { it != v && it !in taken } ?: return null
            return Rewrite(
                product(BackSubstitution(Derivative(outerOf(u), u), u, inner), d(inner)),
                StepRule.CHAIN_RULE,
                notes = listOf(Note(u, inner))
            )
        }

        /**
         * Whether [power] is written as a root or a fraction (√x, 1/x, 1/x²) and has to be
         * written as a power for the power rule.
         */
        private fun needsRewrite(power: Power, isLiteral: Boolean): Boolean {
            if (isLiteral || power.exponent.containsVariable(v)) return false
            val exponent = power.exponent as? Value ?: return false
            return power.base is Power || exponent.isNegative || exponent.rational?.isFractional == true
        }

        /** √x = x^(1/2), 1/x² = x⁻²: written as a power, with powers of powers combined. */
        private fun rewriteAsPower(power: Power): Rewrite? {
            if (!needsRewrite(power, isLiteral = false)) return null
            val inner = power.base as? Power
            val rewritten = if (inner != null && inner.exponent is Value) {
                Power(inner.base, Multiplications(inner.exponent, power.exponent).canonicalize(context.copy()))
            } else {
                power
            }
            return Rewrite(LiteralPower(rewritten), StepRule.REWRITE_AS_POWER, ofBody = true)
        }

        private fun sum(u: Additions): Rewrite {
            // Constant terms drop out; subtracted terms keep their minus sign in front
            val terms = u.operands.filter { it.containsVariable(v) }.map { term ->
                if (term.isNegative) product(Value.MINUS_ONE, d(negated(term))) else d(term)
            }
            return Rewrite(Additions.of(*terms.toTypedArray()), StepRule.SUM)
        }

        private fun product(u: Multiplications): Rewrite {
            val (variableFactors, constantFactors) = u.operands.partition { it.containsVariable(v) }
            if (constantFactors.isNotEmpty()) {
                val rest = variableFactors.singleOrNull() ?: Multiplications(variableFactors)
                // c·x is common enough to go straight to c
                val derivative = if (rest == v) Value.ONE else d(rest)
                return Rewrite(product(*constantFactors.toTypedArray(), derivative), StepRule.CONSTANT_FACTOR)
            }
            val (denominators, numerators) = variableFactors.partition { isReciprocal(it) }
            if (denominators.isNotEmpty() && numerators.isNotEmpty()) {
                val numerator = numerators.singleOrNull() ?: Multiplications(numerators)
                val denominatorFactors = denominators.map { reciprocal(it as Power) }
                val denominator = denominatorFactors.singleOrNull() ?: Multiplications(denominatorFactors)
                return Rewrite(product(
                    Additions(
                        product(d(numerator), denominator),
                        product(Value.MINUS_ONE, numerator, d(denominator))
                    ),
                    Power(denominator, Value(-2L))
                ), StepRule.QUOTIENT)
            }
            val terms = variableFactors.indices.map { i ->
                product(*variableFactors.mapIndexed { j, factor -> if (j == i) d(factor) else factor }.toTypedArray())
            }
            return Rewrite(Additions(terms), StepRule.PRODUCT)
        }

        /** xⁿ, aˣ and x^x (the inner functions are x itself, see [chainRule]). */
        private fun power(u: Power, literal: Boolean): Rewrite {
            val base = u.base
            val exponent = u.exponent
            return when {
                !exponent.containsVariable(v) -> {
                    val lowered = Additions(exponent, Value.MINUS_ONE)
                    val newExponent = if (exponent is Value) lowered.canonicalize(context) else lowered
                    val outer = when (newExponent) {
                        Value.ONE -> base
                        Value.ZERO -> Value.ONE
                        else -> Power(base, newExponent).let { if (literal) LiteralPower(it) else it }
                    }
                    // ½·x^(−½) with the ½ in front, not x^(−½)/2 (a fraction with a fraction in its exponent)
                    val coefficient = if (literal && (exponent as? Value)?.rational?.isFractional == true) LiteralPower(exponent) else exponent
                    Rewrite(product(coefficient, outer), StepRule.POWER)
                }
                !base.containsVariable(v) ->
                    if (base == Constant.E) Rewrite(u, StepRule.EXPONENTIAL)
                    else Rewrite(product(u, Logarithm.ln(base)), StepRule.EXPONENTIAL)
                else -> Rewrite(product(u, d(product(exponent, Logarithm.ln(base)))), StepRule.GENERAL_POWER)
            }
        }

        private fun logarithm(u: Logarithm): Rewrite {
            if (u.base.containsVariable(v)) {
                val quotient = product(Logarithm.ln(u.operand), Power(Logarithm.ln(u.base), Value.MINUS_ONE))
                return Rewrite(d(quotient), StepRule.CHANGE_OF_BASE)
            }
            val denominator = if (u.base == Constant.E) u.operand else product(u.operand, Logarithm.ln(u.base))
            return Rewrite(Power(denominator, Value.MINUS_ONE), StepRule.LOGARITHM)
        }

        private fun trigonometric(u: TrigonometricFunction): Rewrite {
            val x = u.operand
            val unit = u.angleUnit ?: context.angleUnit
            val rule = when (u.type) {
                TrigType.SIN -> StepRule.SINE
                TrigType.COS -> StepRule.COSINE
                TrigType.TAN -> StepRule.TANGENT
                TrigType.SEC -> StepRule.SECANT
                TrigType.CSC -> StepRule.COSECANT
                TrigType.COT -> StepRule.COTANGENT
                TrigType.ASIN -> StepRule.ARCSINE
                TrigType.ACOS -> StepRule.ARCCOSINE
                TrigType.ATAN -> StepRule.ARCTANGENT
                TrigType.ASEC -> StepRule.ARCSECANT
                TrigType.ACSC -> StepRule.ARCCOSECANT
                TrigType.ACOT -> StepRule.ARCCOTANGENT
            }
            val outer = u.type.outerDerivative(x, unit)
            // With angles in degrees: sin's operand and the inverse functions' results are scaled
            val degreeFactor: Expression = when {
                unit != AngleUnit.DEGREES -> Value.ONE
                u.type.isInverse -> product(Value(180L), Power(Constant.PI, Value.MINUS_ONE))
                else -> product(Constant.PI, Value(1L, 180L))
            }
            return Rewrite(product(outer, degreeFactor), rule)
        }

        private fun hyperbolic(u: HyperbolicFunction): Rewrite {
            val rule = when (u.type) {
                HyperbolicType.SINH -> StepRule.HYPERBOLIC_SINE
                HyperbolicType.COSH -> StepRule.HYPERBOLIC_COSINE
                HyperbolicType.TANH -> StepRule.HYPERBOLIC_TANGENT
                HyperbolicType.SECH -> StepRule.HYPERBOLIC_SECANT
                HyperbolicType.CSCH -> StepRule.HYPERBOLIC_COSECANT
                HyperbolicType.COTH -> StepRule.HYPERBOLIC_COTANGENT
                HyperbolicType.ASINH -> StepRule.INVERSE_HYPERBOLIC_SINE
                HyperbolicType.ACOSH -> StepRule.INVERSE_HYPERBOLIC_COSINE
                HyperbolicType.ATANH -> StepRule.INVERSE_HYPERBOLIC_TANGENT
                HyperbolicType.ASECH -> StepRule.INVERSE_HYPERBOLIC_SECANT
                HyperbolicType.ACSCH -> StepRule.INVERSE_HYPERBOLIC_COSECANT
                HyperbolicType.ACOTH -> StepRule.INVERSE_HYPERBOLIC_COTANGENT
            }
            return Rewrite(u.type.outerDerivative(u.operand), rule)
        }

        private fun special(u: SpecialFunction): Rewrite? =
            Rewrite(u.type.outerDerivative(u.operand) ?: return null, specialRule(u.type) ?: return null)

        private fun integral(u: Integral): Rewrite? {
            // Differentiating under the integral sign is not supported
            if (u.variable != v && u.integrand.containsVariable(v)) return null
            fun boundTerm(bound: Expression): Expression? {
                if (!bound.containsVariable(v)) return null
                val integrandAt = u.integrand.substituteVariables(mapOf(u.variable to bound))
                return if (bound == v) integrandAt else product(integrandAt, d(bound))
            }
            val terms = listOfNotNull(
                boundTerm(u.upperBound),
                boundTerm(u.lowerBound)?.let { product(Value.MINUS_ONE, it) }
            )
            return Rewrite(Additions.of(*terms.toTypedArray()), StepRule.FUNDAMENTAL_THEOREM)
        }
    }

    /** The rule for the derivative of a special function, null for those that have none. */
    private fun specialRule(type: SpecialType): StepRule? = when (type) {
        SpecialType.GAMMA -> StepRule.GAMMA_FUNCTION
        SpecialType.LNGAMMA -> StepRule.LOG_GAMMA_FUNCTION
        SpecialType.ERF -> StepRule.ERROR_FUNCTION
        SpecialType.ERFC -> StepRule.COMPLEMENTARY_ERROR_FUNCTION
        SpecialType.LAMBERTW -> StepRule.LAMBERT_W_FUNCTION
        SpecialType.SININT -> StepRule.SINE_INTEGRAL_FUNCTION
        SpecialType.COSINT -> StepRule.COSINE_INTEGRAL_FUNCTION
        SpecialType.EXPINT -> StepRule.EXPONENTIAL_INTEGRAL_FUNCTION
        SpecialType.DIGAMMA, SpecialType.NORMALINV -> null
    }

    /** A factor u⁻ⁿ, i.e. one that is written in a denominator. */
    private fun isReciprocal(factor: Expression): Boolean {
        val exponent = (factor as? Power)?.exponent as? Value ?: return false
        return exponent.isNegative
    }

    /** uⁿ for the factor u⁻ⁿ. */
    private fun reciprocal(factor: Power): Expression {
        val exponent = (factor.exponent as Value).negate()
        return if (exponent == Value.ONE) factor.base else Power(factor.base, exponent)
    }

    /** −[term] with the sign taken off its coefficient: 2x for −2x, x for −1·x. */
    private fun negated(term: Expression): Expression {
        if (term is Value) return term.negate()
        if (term !is Multiplications) return product(Value.MINUS_ONE, term)
        val index = term.operands.indexOfFirst { it is Value && it.isNegative }
        if (index < 0) return product(Value.MINUS_ONE, term)
        val coefficient = (term.operands[index] as Value).negate()
        return product(*term.operands.mapIndexed { i, op -> if (i == index) coefficient else op }.toTypedArray())
    }

    private fun product(vararg factors: Expression): Expression = normalize(Multiplications(factors.toList()))
}
