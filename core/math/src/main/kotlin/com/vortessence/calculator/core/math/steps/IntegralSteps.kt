package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.AbsoluteValue
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Antiderivative
import com.vortessence.calculator.core.math.expressions.Constant
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.Differentiator
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Integral
import com.vortessence.calculator.core.math.expressions.Integrator
import com.vortessence.calculator.core.math.expressions.Logarithm
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.TrigType
import com.vortessence.calculator.core.math.expressions.TrigonometricFunction
import com.vortessence.calculator.core.math.expressions.UnitExpression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.expandProducts
import com.vortessence.calculator.core.math.expressions.substituteVariables
import kotlin.math.abs
import kotlin.math.max

/**
 * The steps of integrating, as in a textbook, in the order the calculator ([Integrator]) tries
 * the methods: each step applies one rule to the first integral that is still open, e.g.
 * ∫(3x² + cos(x)) dx = ∫3x² dx + ∫cos(x) dx = 3∫x² dx + ∫cos(x) dx = x³ + ∫cos(x) dx = …
 * - sums and constant factors,
 * - table integrals (powers, 1/x, exponentials, sin, cos, tan, ln, of a linear argument),
 * - multiplying out the integrand,
 * - substitution (u = g(x), du = g'(x) dx, and back at the end),
 * - integration by parts (u, dv, du, v).
 * A definite integral is the antiderivative F in brackets, [F]ₐᵇ, then F(b) − F(a).
 *
 * The steps end with the calculated result. An antiderivative found by the steps may differ
 * from the calculated one by a constant (which "+ C" covers); then the steps end with their own.
 */
internal object IntegralSteps {

    private const val MAX_STEPS = 120

    fun steps(e: Expression, context: MathContext): ExpressionSolution? = when (e) {
        is Antiderivative -> indefinite(e, context)
        is Integral -> definite(e, context)
        else -> null
    }

    private fun indefinite(problem: Antiderivative, context: MathContext): ExpressionSolution? {
        val v = problem.variable
        val integrand = normalize(problem.integrand)
        if (!isSupported(integrand)) return null
        val calculated = calculated(Antiderivative(integrand, v), context) ?: return null

        val solution = Solver(context)
        solution.start(Antiderivative(integrand, v))
        if (!integrate(solution, context)) return null
        val target = finalForm(unmarked(solution.current), calculated, v, context) ?: return null
        if (!solution.simplifyToward(target, MAX_STEPS)) return null
        return solution.finish(target)
    }

    private fun definite(problem: Integral, context: MathContext): ExpressionSolution? {
        val v = problem.variable
        val integrand = normalize(problem.integrand)
        val (lower, upper) = problem.lowerBound to problem.upperBound
        // Numbers as bounds (at ∞, F(∞) would be a limit)
        val isNumber = { bound: Expression -> bound.variables().isEmpty() && bound.evaluateNumerically().isFinite() }
        if (!isSupported(integrand) || !isNumber(lower) || !isNumber(upper) || integrand.variables().any { it != v }) return null
        val value = calculated(Integral(lower, upper, integrand, v), context) ?: return null
        val antiderivative = calculated(Antiderivative(integrand, v), context) ?: return null

        val solution = Solver(context)
        solution.start(Integral(lower, upper, integrand, v))
        val bracket = EvaluateBetween(Antiderivative(integrand, v), v, lower, upper)
        solution.apply(Match(StepRule.ANTIDERIVATIVE, mapOf(emptyList<Int>() to bracket), problem, bracket))
        if (!integrate(solution, context)) return null

        // The antiderivative simplified, then F(b) − F(a) calculated
        val own = (unmarked(solution.current) as? EvaluateBetween)?.antiderivative ?: return null
        val f = finalForm(own, antiderivative, v, context) ?: return null
        if (!solution.simplifyToward(EvaluateBetween(f, v, lower, upper), MAX_STEPS)) return null
        val simplified = unmarked(solution.current) as? EvaluateBetween ?: return null
        val difference = simplified.difference()
        // F(b) − F(a) is wrong if the integrand has a pole between the bounds
        val expected = value.evaluateNumerically()
        if (abs(difference.evaluateNumerically() - expected) > 1e-7 * max(1.0, abs(expected))) return null
        solution.apply(Match(StepRule.BOUNDS, mapOf(emptyList<Int>() to difference), simplified, difference))
        if (!solution.simplifyToward(value, MAX_STEPS)) return null
        return solution.finish(value)
    }

    /** Functions of the variable the rules know about; no units, matrices or other calculus. */
    private fun isSupported(integrand: Expression): Boolean = !integrand.containsExpressionMatching {
        it is MatrixExpression || it is UnitExpression || it is Derivative || it is Antiderivative || it is Integral
    }

    /** What the calculator shows for [e], or null if it cannot calculate it. */
    private fun calculated(e: Expression, context: MathContext): Expression? {
        val scratch = context.copy()
        val result = e.canonicalize(scratch)
        if (scratch.shouldStop || result.containsExpressionMatching { it is Antiderivative || it is Integral }) return null
        return displayForm(result, context)
    }

    /**
     * The antiderivative to end with: the [calculated] one if [own] (found by the steps) is the
     * same function, [own] if they differ by a constant, null if [own] is wrong.
     */
    private fun finalForm(own: Expression, calculated: Expression, v: Variable, context: MathContext): Expression? {
        val scratch = context.copy()
        val difference = Additions(own, Multiplications(Value.MINUS_ONE, calculated)).canonicalize(scratch)
        if (scratch.shouldStop) return null
        val constant = constantValue(difference, v) ?: return null
        if (abs(constant) < 1e-9) return calculated
        return displayForm(own.canonicalize(context.copy()), context)
    }

    /** The value of [e] if it does not depend on [v] (checked at sample points), else null. */
    private fun constantValue(e: Expression, v: Variable): Double? {
        if (!e.containsVariable(v)) return e.evaluateNumerically().takeIf { it.isFinite() } ?: 0.0
        val others = (e.variables() - v).associateWith { 1.37 }
        val values = SAMPLE_POINTS.map { e.evaluateNumerically(others + (v to it)) }.filter { it.isFinite() }
        if (values.size < 3) return null
        return values.first().takeIf { first -> values.all { abs(it - first) < 1e-7 * max(1.0, abs(first)) } }
    }

    /** Applies rules to the open integrals (and substitutes back) until none is left. */
    private fun integrate(solution: Solver, context: MathContext): Boolean {
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return false
            val e = solution.current
            val path = pathsOf(e) { it is Antiderivative || (it is BackSubstitution && !it.inner.containsExpressionMatching { inner -> inner is Antiderivative }) }
                .firstOrNull() ?: break
            val match = when (val part = at(e, path)) {
                is Antiderivative -> Rules(part, path, context).match() ?: return false
                is BackSubstitution -> {
                    val result = normalize(unmarked(part.inner).substituteVariables(mapOf(part.u to part.value)))
                    Match(StepRule.BACK_SUBSTITUTION, mapOf(path to result), part.u, part.value)
                }
                else -> return false
            }
            solution.apply(match)
        }
        return !solution.current.containsExpressionMatching { it is Antiderivative || it is BackSubstitution }
    }

    /** The rules for the open integral [open] at [path]. */
    private class Rules(open: Antiderivative, val path: Path, val context: MathContext) {
        // The integrand may be highlighted, after it was written as a power
        val f = unmarked(open.integrand)
        val v = open.variable
        val before = unmarked(open)
        val integrator = Integrator(context.copy())

        fun match(): Match? {
            if (!f.containsVariable(v)) return rewrite(StepRule.INTEGRAL_CONSTANT, product(f, v))
            if (f == v) return rewrite(StepRule.INTEGRAL_POWER, product(Value.HALF, Power(v, Value.TWO)))
            if (f is Additions) return sum(f)
            if (f is Multiplications) {
                val (variableFactors, constants) = f.operands.partition { it.containsVariable(v) }
                if (constants.isNotEmpty()) {
                    val rest = variableFactors.singleOrNull() ?: Multiplications(variableFactors)
                    return rewrite(StepRule.INTEGRAL_CONSTANT_FACTOR, product(*constants.toTypedArray(), Antiderivative(rest, v)))
                }
            }
            rewriteAsPower()?.let { return it }
            return table(f) ?: expand() ?: substitution() ?: byParts()
        }

        /**
         * √x = x^(1/2), 1/x² = x⁻²: a power of a linear argument written as a root or fraction is
         * written as a power for the power rule (1/x stays, it has a rule of its own).
         */
        private fun rewriteAsPower(): Match? {
            val power = f as? Power ?: return null
            val exponent = power.exponent as? Value ?: return null
            val inner = power.base as? Power
            val rewritten = if (inner != null && inner.exponent is Value) {
                Power(inner.base, canonical(Multiplications(inner.exponent, exponent)) ?: return null)
            } else {
                if (exponent == Value.MINUS_ONE || !(exponent.isNegative || exponent.rational?.isFractional == true)) return null
                power
            }
            if (integrator.linearSlope(rewritten.base, v) == null || rewritten.exponent == Value.MINUS_ONE) return null
            val literal = LiteralPower(rewritten)
            return Match(StepRule.REWRITE_AS_POWER, mapOf(path + 0 to literal), power, literal)
        }

        private fun rewrite(rule: StepRule, result: Expression, chainRule: Boolean = false, notes: List<Note> = emptyList()) =
            Match(rule, mapOf(path to result), before, result, chainRule = chainRule, notes = notes)

        private fun integral(g: Expression) = Antiderivative(g, v)

        private fun sum(f: Additions): Match {
            // Subtracted terms keep their minus sign in front
            val terms = f.operands.map { term ->
                if (term.isNegative) product(Value.MINUS_ONE, integral(negated(term))) else integral(term)
            }
            return rewrite(StepRule.INTEGRAL_SUM, Additions(terms))
        }

        private fun canonical(e: Expression): Expression? {
            val scratch = context.copy()
            val result = e.canonicalize(scratch)
            return if (scratch.shouldStop) null else result
        }

        /** [e] divided by [a]; 1/a calculated if it is a number. */
        private fun divide(e: Expression, a: Expression): Expression = when {
            a == Value.ONE -> e
            a is Value -> product(e, canonical(Power(a, Value.MINUS_ONE)) ?: Power(a, Value.MINUS_ONE))
            else -> product(e, Power(a, Value.MINUS_ONE))
        }

        private fun lnAbs(u: Expression) = Logarithm.ln(AbsoluteValue(u))

        private fun trig(type: TrigType, u: Expression) = TrigonometricFunction(type, u, AngleUnit.RADIANS)

        /** The table integral of [g] (of a linear argument u = a·x + b: divided by a), or null. */
        fun tableIntegral(g: Expression): Pair<Expression, StepRule>? {
            if (g == v) return product(Value.HALF, Power(v, Value.TWO)) to StepRule.INTEGRAL_POWER
            if (g is LiteralPower) {
                // x^(1/2): x^(3/2)/(3/2), shown as powers until the end
                val power = g.inner as? Power ?: return null
                val a = integrator.linearSlope(power.base, v) ?: return null
                val next = canonical(Additions(power.exponent, Value.ONE)) as? Value ?: return null
                val divisor = canonical(Multiplications(next, a)) ?: return null
                val raised = Power(power.base, next).let { if (next.isNegative || next.rational?.isFractional == true) LiteralPower(it) else it }
                val result = if ((divisor as? Value)?.rational?.isFractional == true) product(raised, Power(divisor, Value.MINUS_ONE)) else divide(raised, divisor)
                return result to StepRule.INTEGRAL_POWER
            }
            when {
                g is Power && !g.exponent.containsVariable(v) -> {
                    val u = g.base
                    // sin², cos² and the quadratic forms come from the calculator's table, simplified
                    val a = integrator.linearSlope(u, v)
                        ?: return integrator.basic(g, v)?.let { canonical(it) }?.let { it to StepRule.INTEGRAL_TABLE }
                    if (g.exponent == Value.MINUS_ONE) return divide(lnAbs(u), a) to StepRule.INTEGRAL_RECIPROCAL
                    val next = if (g.exponent is Value) canonical(Additions(g.exponent, Value.ONE)) ?: return null else Additions(g.exponent, Value.ONE)
                    val divisor = if (next is Value && a is Value) canonical(Multiplications(next, a)) ?: return null else product(next, a)
                    return divide(Power(u, next), divisor) to StepRule.INTEGRAL_POWER
                }
                g is Power && !g.base.containsVariable(v) -> {
                    val a = integrator.linearSlope(g.exponent, v) ?: return null
                    val divisor = if (g.base == Constant.E) a else product(a, Logarithm.ln(g.base))
                    return divide(g, divisor) to StepRule.INTEGRAL_EXPONENTIAL
                }
                g is TrigonometricFunction && !g.type.isInverse && (g.angleUnit ?: context.angleUnit) == AngleUnit.RADIANS -> {
                    val u = g.operand
                    val a = integrator.linearSlope(u, v) ?: return null
                    return when (g.type) {
                        TrigType.SIN -> divide(product(Value.MINUS_ONE, trig(TrigType.COS, u)), a) to StepRule.INTEGRAL_SINE
                        TrigType.COS -> divide(trig(TrigType.SIN, u), a) to StepRule.INTEGRAL_COSINE
                        else -> divide(product(Value.MINUS_ONE, lnAbs(trig(TrigType.COS, u))), a) to StepRule.INTEGRAL_TANGENT
                    }
                }
                g is Logarithm && !g.base.containsVariable(v) -> {
                    val u = g.operand
                    val a = integrator.linearSlope(u, v) ?: return null
                    val divisor = if (g.base == Constant.E) a else product(a, Logarithm.ln(g.base))
                    return divide(Additions(product(u, Logarithm.ln(u)), product(Value.MINUS_ONE, u)), divisor) to StepRule.INTEGRAL_LOGARITHM
                }
            }
            return null
        }

        private fun table(g: Expression): Match? {
            val (result, rule) = tableIntegral(g) ?: return null
            val inner = when (g) {
                is LiteralPower -> (g.inner as? Power)?.base ?: v
                is Power -> if (g.base.containsVariable(v)) g.base else g.exponent
                is TrigonometricFunction -> g.operand
                is Logarithm -> g.operand
                else -> v
            }
            return rewrite(rule, result, chainRule = rule != StepRule.INTEGRAL_TABLE && inner != v)
        }

        /** Products and powers of sums multiplied out, e.g. ∫x(x + 1)² dx = ∫(x³ + 2x² + x) dx. */
        private fun expand(): Match? {
            if (f !is Multiplications && f !is Power) return null
            val expanded = canonical(expandProducts(f)) ?: return null
            if (expanded == f || expanded !is Additions) return null
            return Match(StepRule.EXPAND_INTEGRAND, mapOf(path to integral(expanded)), f, expanded)
        }

        /** ∫ f(g(x))·g'(x) dx = ∫ f(u) du with u = g(x), up to a constant factor. */
        private fun substitution(): Match? {
            val fc = canonical(f) ?: return null
            val name = listOf("u", "t", "w", "z").firstOrNull { Variable(it) != v && Variable(it) !in fc.variables() } ?: return null
            val w = Variable(name)
            for (u in innerExpressions(fc)) {
                val du = canonical(Differentiator.derivative(u, v) ?: continue) ?: continue
                if (du.isZero) continue
                val quotient = canonical(Multiplications(fc, Power(du, Value.MINUS_ONE))) ?: continue
                val inW = quotient.substitute(u, w)
                if (inW.containsVariable(v) || integrator.antiderivative(inW, w) == null) continue
                val substituted = Antiderivative(inW, w)
                return Match(
                    StepRule.SUBSTITUTION,
                    mapOf(path to BackSubstitution(substituted, w, u)),
                    before,
                    substituted,
                    notes = listOf(Note(w, u), Note(Variable("d$name"), du, v))
                )
            }
            return null
        }

        /** The candidates for u: arguments of functions, bases and exponents of powers. */
        private fun innerExpressions(e: Expression): List<Expression> {
            val result = LinkedHashSet<Expression>()
            val queue = ArrayDeque(listOf(e))
            while (queue.isNotEmpty()) {
                val part = queue.removeFirst()
                if (part != e && part != v && part.containsVariable(v) && part !is Value) result.add(part)
                queue.addAll(part.children)
            }
            return result.toList()
        }

        /**
         * ∫ u dv = u·v − ∫ v du: a polynomial times eˣ, sin or cos (u is the polynomial), or a
         * polynomial times ln, atan, ... (u is the function).
         */
        private fun byParts(): Match? {
            val factors = if (f is Multiplications) f.operands else listOf(f)
            for ((index, g) in factors.withIndex()) {
                val p = Multiplications.of(*factors.filterIndexed { i, _ -> i != index }.toTypedArray())
                if (!integrator.isPolynomial(p, v)) continue
                val (u, dv, du, vOfDv) = if (integrator.isRepeatedlyIntegrable(g, v) && p.containsVariable(v)) {
                    val integratedG = canonical(tableIntegral(g)?.first ?: continue) ?: continue
                    Parts(p, g, canonical(Differentiator.derivative(p, v) ?: continue) ?: continue, integratedG)
                } else if (g is Logarithm || (g is TrigonometricFunction && g.type.isInverse)) {
                    val integratedP = integrator.antiderivative(p, v) ?: continue
                    Parts(g, p, canonical(Differentiator.derivative(g, v) ?: continue) ?: continue, integratedP)
                } else {
                    continue
                }
                val remainder = canonical(Multiplications(vOfDv, du)) ?: continue
                if (integrator.antiderivative(remainder, v) == null) continue
                val result = Additions(product(u, vOfDv), product(Value.MINUS_ONE, integral(remainder)))
                return rewrite(
                    StepRule.BY_PARTS,
                    result,
                    notes = listOf(Note(Variable("u"), u), Note(Variable("dv"), dv, v), Note(Variable("du"), du, v), Note(Variable("v"), vOfDv))
                )
            }
            return null
        }
    }

    /** u, dv/dx, du/dx and v of an integration by parts. */
    private data class Parts(val u: Expression, val dv: Expression, val du: Expression, val v: Expression)

    private fun product(vararg factors: Expression): Expression = normalize(Multiplications(factors.toList()))

    /** −[term] with the sign taken off its coefficient: 2x for −2x, x for −1·x. */
    private fun negated(term: Expression): Expression {
        if (term is Value) return term.negate()
        if (term !is Multiplications) return product(Value.MINUS_ONE, term)
        val index = term.operands.indexOfFirst { it is Value && it.isNegative }
        if (index < 0) return product(Value.MINUS_ONE, term)
        val coefficient = (term.operands[index] as Value).negate()
        return product(*term.operands.mapIndexed { i, op -> if (i == index) coefficient else op }.toTypedArray())
    }

    private val SAMPLE_POINTS = listOf(0.37, 1.13, 2.71, -0.83, 0.52, -1.9, 3.3)
}
