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
import com.vortessence.calculator.core.math.expressions.Limit
import com.vortessence.calculator.core.math.expressions.Integrator
import com.vortessence.calculator.core.math.expressions.Logarithm
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.TrigType
import com.vortessence.calculator.core.math.expressions.HyperbolicFunction
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
        val isInfinite = { bound: Expression -> (bound.canonicalize(context.copy()) as? Value)?.real?.isInfinite == true }
        if (isSupported(integrand) && integrand.variables().all { it == v } && ((isInfinite(upper) && isNumber(lower)) || (isInfinite(lower) && isNumber(upper)))) {
            return improper(problem, integrand, context)
        }
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

    /**
     * ∫ₐ^∞ f dx (or from −∞): the antiderivative F, then the limit of F(t) − F(a) as t → ∞, with the steps of that limit.
     */
    private fun improper(problem: Integral, integrand: Expression, context: MathContext): ExpressionSolution? {
        val v = problem.variable
        val (lower, upper) = problem.lowerBound to problem.upperBound
        val value = calculated(Integral(lower, upper, integrand, v), context) ?: return null
        val antiderivative = calculated(Antiderivative(integrand, v), context) ?: return null

        val solution = Solver(context)
        solution.start(Integral(lower, upper, integrand, v))
        val bracket = EvaluateBetween(Antiderivative(integrand, v), v, lower, upper)
        solution.apply(Match(StepRule.ANTIDERIVATIVE, mapOf(emptyList<Int>() to bracket), problem, bracket))
        if (!integrate(solution, context)) return null
        val own = (unmarked(solution.current) as? EvaluateBetween)?.antiderivative ?: return null
        val f = finalForm(own, antiderivative, v, context) ?: return null
        if (!solution.simplifyToward(EvaluateBetween(f, v, lower, upper), MAX_STEPS)) return null

        // The limit of F(t) − F(a) as t → ∞ (F(b) − F(t) as t → −∞)
        val t = listOf("t", "u", "w").map { Variable(it) }.firstOrNull { it != v && it !in f.variables() } ?: return null
        val atInfinity = (upper.canonicalize(context.copy()) as? Value)?.real?.isInfinite == true
        val atT = f.substituteVariables(mapOf(v to t))
        val atBound = f.substituteVariables(mapOf(v to if (atInfinity) lower else upper))
        val difference = if (atInfinity) Additions(atT, Multiplications(Value.MINUS_ONE, atBound)) else Additions(atBound, Multiplications(Value.MINUS_ONE, atT))
        val limit = Limit(difference, t, if (atInfinity) upper else lower)
        solution.apply(Match(StepRule.IMPROPER_INTEGRAL, mapOf(emptyList<Int>() to limit), unmarked(solution.current), limit, showChange = false, boxReplacements = false))
        val inner = LimitSteps.steps(limit, context) ?: return null
        val expected = value.evaluateNumerically()
        val actual = inner.result.evaluateNumerically()
        if (!(actual == expected || abs(actual - expected) <= 1e-7 * max(1.0, abs(expected)))) return null
        return ExpressionSolution(solution.finish(limit).steps + inner.steps.drop(1), inner.result)
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
            halfAngle()?.let { return it }
            oddTrigonometricPower()?.let { return it }
            return table(f) ?: rationalFunction() ?: expand() ?: substitution() ?: byParts() ?: cyclicByParts()
        }

        /** sin³(u) = sin(u)(1 − cos²(u)): an odd power of the sine or cosine keeps one factor, the rest is a polynomial in the other. */
        private fun oddTrigonometricPower(): Match? {
            val rewritten = integrator.rewrittenOddPower(f, v) ?: return null
            return rewrite(StepRule.TRIG_ODD_POWER, integral(rewritten))
        }

        /** sin²(u) = (1 − cos(2u))/2 and cos²(u) = (1 + cos(2u))/2, for a linear angle u. */
        private fun halfAngle(): Match? {
            val power = f as? Power ?: return null
            if (power.exponent != Value.TWO) return null
            val trig = power.base as? TrigonometricFunction ?: return null
            if (trig.type != TrigType.SIN && trig.type != TrigType.COS) return null
            if ((trig.angleUnit ?: context.angleUnit) != AngleUnit.RADIANS || integrator.linearSlope(trig.operand, v) == null) return null
            val double = canonical(Multiplications(Value.TWO, trig.operand)) ?: return null
            val cosine = trig(TrigType.COS, double)
            val sign = if (trig.type == TrigType.SIN) Value.MINUS_ONE else Value.ONE
            val written = product(Value.HALF, Additions(Value.ONE, product(sign, cosine)))
            return rewrite(StepRule.HALF_ANGLE, integral(written))
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
                        ?: return integrator.basic(canonical(g) ?: g, v)?.let { canonical(it) }?.let { it to StepRule.INTEGRAL_TABLE }
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
                        TrigType.TAN -> divide(product(Value.MINUS_ONE, lnAbs(trig(TrigType.COS, u))), a) to StepRule.INTEGRAL_TANGENT
                        else -> fromTable(g)
                    }
                }
                g is HyperbolicFunction && !g.type.isInverse -> return fromTable(g)
                g is Logarithm && !g.base.containsVariable(v) -> {
                    val u = g.operand
                    val a = integrator.linearSlope(u, v) ?: return null
                    val divisor = if (g.base == Constant.E) a else product(a, Logarithm.ln(g.base))
                    return divide(Additions(product(u, Logarithm.ln(u)), product(Value.MINUS_ONE, u)), divisor) to StepRule.INTEGRAL_LOGARITHM
                }
            }
            return null
        }

        /** The calculator's table integral of [g], without a rule of its own. */
        private fun fromTable(g: Expression): Pair<Expression, StepRule>? =
            integrator.basic(canonical(g) ?: g, v)?.let { canonical(it) }?.let { it to StepRule.INTEGRAL_TABLE }

        private fun table(g: Expression): Match? {
            val (result, rule) = tableIntegral(g) ?: return null
            val inner = when (g) {
                is LiteralPower -> (g.inner as? Power)?.base ?: v
                is Power -> if (g.base.containsVariable(v)) g.base else g.exponent
                is TrigonometricFunction -> g.operand
                is HyperbolicFunction -> g.operand
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
            for (u in integrator.substitutionCandidates(fc, v)) {
                val du = canonical(Differentiator.derivative(u, v) ?: continue) ?: continue
                if (du.isZero) continue
                val quotient = canonical(Multiplications(fc, Power(du, Value.MINUS_ONE))) ?: continue
                val inW = integrator.inTermsOf(quotient, u, w, v)
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

        /**
         * A fraction of polynomials: a substitution if one fits (x/(x² − 1) → ln), otherwise partial
         * fractions, or for a quadratic denominator without real roots a split numerator and the
         * completed square.
         */
        private fun rationalFunction(): Match? {
            completedSquareSubstitution()?.let { return it }
            polynomialDivision()?.let { return it }
            val rational = integrator.splitRational(f, v) != null
            val quadratic = integrator.splitQuadratic(f, v)
            if (!rational && quadratic == null) return null
            return substitution() ?: if (rational) partialFractions() else irreducibleQuadratic(quadratic!!)
        }

        /** x³/(x² + 1) = x − x/(x² + 1): the polynomial part is divided off first, the rest has a lower degree. */
        private fun polynomialDivision(): Match? {
            val division = integrator.splitByDivision(f, v) ?: return null
            val quotient = polynomialExpression(division.quotient, v)
            val remainder = polynomialExpression(division.remainder, v)
            val fraction = product(remainder, Power(division.denominator, Value.MINUS_ONE))
            val result = Additions(integral(quotient), integral(fraction))
            return Match(
                StepRule.POLYNOMIAL_DIVISION, mapOf(path to result), before, result,
                notes = listOf(Note(f, Additions(quotient, fraction)))
            )
        }

        /**
         * ∫ eᵖˣ sin(qx) dx: by parts twice the same integral comes back, I = … − (q²/p²)·I, which is solved for I.
         */
        private fun cyclicByParts(): Match? {
            val parts = integrator.cyclicParts(f, v) ?: return null
            val p = parts.p as? Value ?: return null
            val q = parts.q as? Value ?: return null
            val w = parts.trig.operand
            val sine = trig(TrigType.SIN, w)
            val cosine = trig(TrigType.COS, w)
            val bracket = if (parts.trig.type == TrigType.SIN) {
                Additions(product(p, sine), product(Value.MINUS_ONE, q, cosine))
            } else {
                Additions(product(p, cosine), product(q, sine))
            }
            val pSquared = canonical(Power(p, Value.TWO)) ?: return null
            val ratio = canonical(Multiplications(Power(q, Value.TWO), Power(p, Value(-2L)))) ?: return null
            val i = Variable("I")
            val over = if (pSquared == Value.ONE) product(parts.exponential, bracket) else product(parts.exponential, bracket, Power(pSquared, Value.MINUS_ONE))
            val twice = Additions(over, product(Value.MINUS_ONE, ratio, i))
            val result = parts.antiderivative
            return rewrite(
                StepRule.CYCLIC_BY_PARTS, result,
                notes = listOf(Note(i, Antiderivative(f, v)), Note(i, twice), Note(i, result))
            )
        }

        /** The name of a new variable for a substitution: u, or the next letter not in the integrand. */
        private fun newVariable(): Variable? =
            listOf("u", "t", "w", "z").map { Variable(it) }.firstOrNull { it != v && it !in f.variables() }

        /**
         * (px + q)/(ax² + bx + c) without real roots. If the numerator is a multiple of the
         * derivative 2ax + b: u = ax² + bx + c. If not, but it has an x: split off that part.
         * Otherwise (a constant): complete the square, ax² + bx + c = a((x + h)² + k).
         */
        private fun irreducibleQuadratic(fraction: Integrator.QuadraticFraction): Match? {
            val derivative = fraction.derivative(v)
            val denominator = fraction.denominator
            when {
                // ∫ m·D′/D dx = m·∫ du/u
                fraction.p.isZero.not() && fraction.constantPart.isZero -> {
                    val u = newVariable() ?: return null
                    val substituted = integral(product(Value(fraction.derivativePart), Power(u, Value.MINUS_ONE))).let { Antiderivative(it.integrand, u) }
                    return Match(
                        StepRule.SUBSTITUTION,
                        mapOf(path to BackSubstitution(substituted, u, denominator)),
                        before,
                        substituted,
                        notes = listOf(Note(u, denominator), Note(Variable("d${u.identifier}"), derivative, v))
                    )
                }
                // (px + q)/D = (p/2a)·(2ax + b)/D + (q − pb/2a)/D
                fraction.p.isZero.not() -> {
                    val first = product(Value(fraction.derivativePart), derivative, Power(denominator, Value.MINUS_ONE))
                    val second = product(Value(fraction.constantPart), Power(denominator, Value.MINUS_ONE))
                    val result = Additions(integral(first), integral(second))
                    return Match(StepRule.SPLIT_NUMERATOR, mapOf(path to result), before, result, notes = listOf(Note(f, Additions(first, second))))
                }
                else -> {
                    val completed = fraction.completedSquare(v)
                    val result = integral(product(Value(fraction.constantPart / fraction.a), Power(completed, Value.MINUS_ONE)))
                    return Match(
                        StepRule.COMPLETE_SQUARE, mapOf(path to result), before, result,
                        notes = listOf(Note(denominator, product(Value(fraction.a), completed)))
                    )
                }
            }
        }

        /** 1/((x + h)² + k): u = x + h, then 1/(u² + k) is a table integral (atan). */
        private fun completedSquareSubstitution(): Match? {
            val power = f as? Power ?: return null
            if (power.exponent != Value.MINUS_ONE) return null
            val sum = power.base as? Additions ?: return null
            if (sum.operands.size != 2) return null
            val square = sum.operands.firstOrNull { it is Power && it.exponent == Value.TWO } as? Power ?: return null
            val k = (sum.operands.firstOrNull { it is Value } as? Value)?.takeIf { !it.isNegative && !it.isZero } ?: return null
            if (integrator.linearSlope(square.base, v) != Value.ONE) return null
            val u = newVariable() ?: return null
            val substituted = Antiderivative(Power(Additions(Power(u, Value.TWO), k), Value.MINUS_ONE), u)
            return Match(
                StepRule.SUBSTITUTION,
                mapOf(path to BackSubstitution(substituted, u, square.base)),
                before,
                substituted,
                notes = listOf(Note(u, square.base), Note(Variable("d${u.identifier}"), Value.ONE, v))
            )
        }

        /**
         * P/Q with Q = a(x − r₁)(x − r₂)…: split into A/(x − r₁) + B/(x − r₂) + … (and a polynomial
         * if the degree of P is not lower), the numbers A, B … found by the calculator.
         */
        private fun partialFractions(): Match? {
            val (decomposition, parts) = integrator.splitRational(f, v) ?: return null
            val (numerator, denominator) = parts
            val x = v
            fun linear(r: com.vortessence.calculator.core.math.numbers.Rational): Expression =
                if (r.isZero) x else Additions(x, Value(-r))
            fun powered(base: Expression, k: Int): Expression = if (k == 1) base else Power(base, Value(k.toLong()))

            // Q = a·(x − r₁)(x − r₂)…
            val factored = Multiplications.of(
                *(listOfNotNull(Value(decomposition.leading).takeIf { it != Value.ONE }) +
                    decomposition.roots.map { (r, m) -> powered(linear(r), m) }).toTypedArray()
            )
            val remainder = polynomialExpression(decomposition.remainder, x)
            val fraction = if (decomposition.quotient.isEmpty()) f else product(remainder, Power(denominator, Value.MINUS_ONE))

            // A/(x − r₁) + B/(x − r₂) + …, with letters A, B … for the numbers
            val letters = decomposition.roots.flatMap { (r, m) -> (1..m).map { r to it } }
                .mapIndexed { i, (r, k) -> Triple(Variable(('A' + i).toString()), r, k) }
            val setup = Additions(letters.map { (letter, r, k) -> product(letter, Power(powered(linear(r), k), Value.MINUS_ONE)) })

            val notes = buildList {
                if (decomposition.quotient.isNotEmpty()) {
                    add(Note(f, Additions(polynomialExpression(decomposition.quotient, x), product(remainder, Power(denominator, Value.MINUS_ONE)))))
                }
                // Already factored as it is: nothing to say
                if (writtenForm(denominator) != writtenForm(factored)) add(Note(denominator, factored))
                add(Note(fraction, setup))
                for ((letter, r, k) in letters) {
                    val value = decomposition.terms.firstOrNull { it.root == r && it.power == k }?.coefficient
                    add(Note(letter, Value(value ?: com.vortessence.calculator.core.math.numbers.Rational.ZERO)))
                }
            }
            val result = integral(decomposition.toExpression(x))
            return Match(StepRule.PARTIAL_FRACTIONS, mapOf(path to result), before, result, notes = notes)
        }

        /** c₀ + c₁x + c₂x² … */
        private fun polynomialExpression(c: List<com.vortessence.calculator.core.math.numbers.Rational>, x: Variable): Expression {
            val terms = c.mapIndexedNotNull { k, coefficient ->
                if (coefficient.isZero) null else product(Value(coefficient), if (k == 0) Value.ONE else if (k == 1) x else Power(x, Value(k.toLong())))
            }
            return if (terms.isEmpty()) Value.ZERO else if (terms.size == 1) terms.single() else Additions(terms)
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
                } else if (g is Logarithm || (g is TrigonometricFunction && g.type.isInverse) || (g is HyperbolicFunction && g.type.isInverse)) {
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
