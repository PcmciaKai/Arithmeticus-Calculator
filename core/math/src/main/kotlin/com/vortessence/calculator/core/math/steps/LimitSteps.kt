package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.Differentiator
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Limit
import com.vortessence.calculator.core.math.expressions.Logarithm
import com.vortessence.calculator.core.math.expressions.TrigonometricFunction
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.PartialFractions
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.UnitExpression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.containsImaginaryUnit
import com.vortessence.calculator.core.math.expressions.substituteVariables
import kotlin.math.abs
import kotlin.math.max

/**
 * The steps of a limit at a finite point, in the order the calculator ([Limit]) tries: the point
 * substituted where the function is defined there, and otherwise, for 0/0, l'Hôpital's rule
 * (lim f/g = lim f′/g′) as often as needed, after cancelling common factors (x² − 4)/(x − 2), the derivatives worked out step by step
 * ([DerivativeSteps]). At ±∞: a polynomial over a polynomial is divided by the highest power of x
 * (terms 1/xⁿ → 0), or the highest power wins; other quotients by l'Hôpital's rule for ∞/∞ (x/eˣ).
 * Limits the calculator only approximates have no steps.
 */
internal object LimitSteps {

    private const val MAX_STEPS = 120
    private const val MAX_LHOPITAL = 4

    fun steps(limit: Limit, context: MathContext): ExpressionSolution? {
        val v = limit.variable
        if (limit.variables().isNotEmpty()) return null
        if (limit.containsExpressionMatching { it is MatrixExpression || it is UnitExpression || it is Derivative } || limit.containsImaginaryUnit()) return null
        val scratch = context.copy()
        val calculated = limit.canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || calculated.containsExpressionMatching { it is Limit }) return null
        val target = displayForm(calculated, context)
        val expected = calculated.evaluateNumerically()

        val point = limit.point.canonicalize(context.copy())
        val solution = Solver(context)
        solution.start(normalize(limit))
        if ((point as? Value)?.real?.isInfinite == true) {
            // x·sin(1/x) as x → ∞: u = 1/x goes to 0, where sin(u)/u is a limit of the finite kind
            val u = reciprocalSubstitution(solution, limit, point.real.value > 0, expected, context)
            return if (u != null) finitePoint(solution, u, Value.ZERO, target, expected, context)
            else atInfinity(solution, v, point.real.value > 0, target, expected, context)
        }
        return finitePoint(solution, v, point, target, expected, context)
    }

    /** x·sin(1/x), x(e^(1/x) − 1) as x → ±∞: [limit] with u = 1/x and u → 0, the new variable; null if that is no help. */
    private fun reciprocalSubstitution(solution: Solver, limit: Limit, positive: Boolean, expected: Double, context: MathContext): Variable? {
        val v = limit.variable
        val body = canonical(limit.body, context) ?: return null
        fun isReciprocal(e: Expression) = e is Power && e.base == v && e.exponent == Value.MINUS_ONE
        // A function of 1/x, with x also outside of it
        val hasReciprocalArgument = body.containsExpressionMatching { f ->
            (f is TrigonometricFunction || f is Logarithm || (f is Power && f.exponent.containsVariable(v))) && f.children.any { isReciprocal(it) }
        }
        if (!hasReciprocalArgument || !body.containsExpressionMatching { it == v }) return null
        // Not when x only occurs as 1/x: then the limit is just the value at 0
        val u = listOf("u", "t", "w").map { Variable(it) }.firstOrNull { it != v && it !in body.variables() } ?: return null
        val substituted = body.transform { if (it == v) Power(u, Value.MINUS_ONE) else it }
        val new = Limit(canonicalBody(substituted, context) ?: return null, u, Value.ZERO)
        val scratch = context.copy()
        val value = new.canonicalize(scratch).evaluateNumerically()
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || abs(value - expected) > 1e-7 * max(1.0, abs(expected))) return null
        val sign = if (positive) "+" else "−"
        solution.apply(
            Match(
                StepRule.LIMIT_RECIPROCAL_SUBSTITUTION, mapOf(emptyList<Int>() to new), solution.current, new,
                notes = listOf(Note(u, Power(v, Value.MINUS_ONE)), Note(TextExpression("${v.identifier} → ${if (positive) "" else "−"}∞"), TextExpression("${u.identifier} → 0$sign"))),
                showChange = false, boxReplacements = false
            )
        )
        return u
    }

    private fun canonicalBody(e: Expression, context: MathContext): Expression? = canonical(e, context)

    /** The limit for x → [point], a number: substituted, or after cancelling and l'Hôpital's rule. */
    private fun finitePoint(solution: Solver, v: Variable, point: Expression, target: Expression, expected: Double, context: MathContext): ExpressionSolution? {
        repeat(MAX_LHOPITAL + 1) { round ->
            if (context.shouldStop || solution.size > MAX_STEPS) return null
            // A common factor of numerator and denominator is cancelled first: (x² − 4)/(x − 2) = x + 2
            while (!context.shouldStop && solution.size <= MAX_STEPS) {
                val cancel = SimplificationSteps.match(at(solution.current, listOf(0)), Value.ZERO, context)
                if (cancel == null || (cancel.rule != StepRule.CANCEL_COMMON_FACTOR && cancel.rule != StepRule.CANCEL)) break
                solution.apply(EquationSteps.prefixed(cancel, listOf(0)))
            }
            val body = normalize(unmarked(at(solution.current, listOf(0))))
            if (defined(body, v, point, context)) {
                // The point written in, then calculated
                val inserted = body.transform { if (it == v) Marked(point, PENDING_COLOR, boxed = true, keep = true) else it }
                val written = normalize(unmarked(inserted))
                solution.apply(Match(StepRule.LIMIT_SUBSTITUTE, mapOf(emptyList<Int>() to inserted), solution.current, written, showChange = false, boxReplacements = false))
                val value = unmarked(solution.current).evaluateNumerically()
                if (abs(value - expected) > 1e-7 * max(1.0, abs(expected)) && !(value.isNaN() && expected.isNaN())) return null
                if (!solution.simplifyToward(target, MAX_STEPS)) return null
                return solution.finish(target)
            }
            if (round == MAX_LHOPITAL) return null
            // 0·∞ is written as a quotient first: x·ln(x) = ln(x)/(1/x)
            val quotient = if (splitFraction(body, v) == null) productAsQuotient(solution, body, v, point, context) else null
            if (!lHopital(solution, body, v, point, context, explicit = quotient)) return null
        }
        return null
    }

    /** The steps of the limit for x → +∞ ([positive]) or x → −∞; null if they are not of a kind that has rules here. */
    private fun atInfinity(solution: Solver, v: Variable, positive: Boolean, target: Expression, expected: Double, context: MathContext): ExpressionSolution? {
        val root = emptyList<Int>()
        fun check(value: Expression): Boolean {
            val actual = value.evaluateNumerically()
            return actual == expected || abs(actual - expected) <= 1e-7 * max(1.0, abs(expected))
        }
        repeat(MAX_LHOPITAL + 3) {
            if (context.shouldStop || solution.size > MAX_STEPS) return null
            val body = normalize(unmarked(at(solution.current, listOf(0))))
            if (!body.containsVariable(v)) {
                if (!check(body)) return null
                solution.apply(Match(StepRule.LIMIT_CONSTANT, mapOf(root to body), solution.current, body, showChange = false))
                return solution.finish(target)
            }
            val (numerator, denominator) = splitFraction(body, v) ?: (body to Value.ONE)
            val n = PartialFractions.polynomial(numerator, v, context)
            val d = PartialFractions.polynomial(denominator, v, context)
            if (n != null && d != null && n.isNotEmpty() && d.isNotEmpty()) {
                val degreeN = n.size - 1
                val degreeD = d.size - 1
                if (degreeN > degreeD) {
                    // The highest power wins: its sign decides
                    val sign = (n.last() / d.last()).signum * (if (!positive && (degreeN - degreeD) % 2 == 1) -1 else 1)
                    val infinity = Value(if (sign > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY)
                    if (!check(infinity)) return null
                    solution.apply(
                        Match(
                            StepRule.LIMIT_GROWS, mapOf(root to infinity), solution.current, infinity,
                            notes = if (degreeD == 0 && d.size == 1 && d[0] == com.vortessence.calculator.core.math.numbers.Rational.ONE) emptyList() else listOf(Note(TextExpression("highest powers"), Multiplications.of(
                                PartialFractions.toExpression(listOf(n.last()), v), Power(v, Value(degreeN.toLong())),
                                Power(Multiplications.of(PartialFractions.toExpression(listOf(d.last()), v), Power(v, Value(degreeD.toLong()))), Value.MINUS_ONE)
                            ))),
                            showChange = false
                        )
                    )
                    return solution.finish(target)
                }
                // Divided by the highest power of the denominator, every term with x in a denominator vanishes
                val divided = Multiplications(dividedBy(n, degreeD, v), Power(dividedBy(d, degreeD, v), Value.MINUS_ONE))
                solution.apply(
                    Match(
                        StepRule.DIVIDE_BY_HIGHEST_POWER, mapOf(listOf(0) to divided), body, divided,
                        showChange = false
                    )
                )
                val vanishing = pathsOf(at(solution.current, listOf(0))) { isVanishing(it, v) }
                    .let { all -> all.filter { path -> all.none { other -> other.size < path.size && path.subList(0, other.size) == other } } }
                    .map { listOf(0) + it }
                val zeroed = divided.transform { if (isVanishing(it, v)) Value.ZERO else it }
                solution.apply(
                    Match(
                        StepRule.LIMIT_VANISH,
                        vanishing.associateWith { Marked(Value.ZERO, PENDING_COLOR, boxed = true, keep = true) },
                        divided, zeroed, showChange = false, boxReplacements = false
                    )
                )
                while (true) {
                    if (context.shouldStop || solution.size > MAX_STEPS) return null
                    solution.apply(EquationSteps.prefixed(SimplificationSteps.match(at(solution.current, listOf(0)), Value.ZERO, context) ?: break, listOf(0)))
                }
                return@repeat
            }
            // A quotient that grows without bound in both parts, or vanishes in both: l'Hôpital
            val limitN = numericLimit(numerator, v, positive)
            val limitD = numericLimit(denominator, v, positive)
            if (denominator != Value.ONE && ((limitN.isInfinite() && limitD.isInfinite()) || (limitN == 0.0 && limitD == 0.0))) {
                if (!lHopital(solution, body, v, Value(if (positive) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY), context, atInfinity = true)) return null
                return@repeat
            }
            // c/g with g growing without bound: 0; c·g: grows without bound
            val factors = if (body is Multiplications) body.operands else listOf(body)
            val constant = factors.filter { !it.containsVariable(v) }
            val rest = factors.filter { it.containsVariable(v) }
            val reciprocal = rest.singleOrNull() as? Power
            if (reciprocal != null && reciprocal.exponent.isNegative && numericLimit(Power(reciprocal.base, reciprocal.exponent.negate()), v, positive).isInfinite()) {
                if (!check(Value.ZERO)) return null
                solution.apply(Match(StepRule.LIMIT_RECIPROCAL, mapOf(root to Value.ZERO), solution.current, Value.ZERO, showChange = false))
                return solution.finish(target)
            }
            if (rest.size == 1 && numericLimit(rest.single(), v, positive).isInfinite() && check(Value(expected))) {
                solution.apply(Match(StepRule.LIMIT_GROWS, mapOf(root to Value(expected)), solution.current, Value(expected), showChange = false))
                return solution.finish(target)
            }
            return null
        }
        return null
    }

    /** The polynomial [p] (constant term first) with every term divided by x^[degree]: c₂ + c₁·x⁻¹ + c₀·x⁻². */
    private fun dividedBy(p: List<com.vortessence.calculator.core.math.numbers.Rational>, degree: Int, v: Variable): Expression {
        val terms = p.indices.reversed().mapNotNull { k ->
            val c = p[k]
            if (c.isZero) null
            else if (k == degree) Value(c)
            else Multiplications.of(Value(c).takeIf { it != Value.ONE } ?: Value.ONE, Power(v, Value((k - degree).toLong())))
        }
        return if (terms.size == 1) terms.single() else Additions(terms)
    }

    /** A term c/xⁿ, which goes to 0 as x grows. */
    private fun isVanishing(e: Expression, v: Variable): Boolean {
        fun isNegativePower(f: Expression) = f is Power && f.base == v && (f.exponent as? Value)?.isNegative == true
        if (isNegativePower(e)) return true
        val factors = (e as? Multiplications)?.operands ?: return false
        return factors.any { isNegativePower(it) } && factors.all { isNegativePower(it) || !it.containsVariable(v) }
    }

    /** Where [e] goes as x grows: 0, ±∞ or a number, found by trying large values (only to decide which rule applies). */
    private fun numericLimit(e: Expression, v: Variable, positive: Boolean): Double {
        val sign = if (positive) 1.0 else -1.0
        val a = e.evaluateNumerically(mapOf(v to sign * 1e4))
        val b = e.evaluateNumerically(mapOf(v to sign * 1e8))
        val c = e.evaluateNumerically(mapOf(v to sign * 1e16))
        fun infinity(positive: Boolean) = if (positive) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        // A slowly growing function such as ln(x) keeps growing by at least about as much each time
        val keepsGrowing = abs(c) > 10 && abs(c) > abs(b) && abs(b) > abs(a) && abs(c) - abs(b) >= 0.5 * (abs(b) - abs(a)) &&
            (c > 0) == (b > 0) && (b > 0) == (a > 0)
        return when {
            c.isInfinite() -> infinity(c > 0)
            b.isInfinite() -> infinity(b > 0)
            !a.isFinite() || !b.isFinite() || !c.isFinite() -> Double.NaN
            keepsGrowing -> infinity(c > 0)
            abs(b) < 1e-9 && abs(c) < 1e-9 -> 0.0
            else -> c
        }
    }

    /** lim f/g with f(a) = g(a) = 0 becomes lim f′/g′, the derivatives then worked out. */
    private fun lHopital(
        solution: Solver, body: Expression, v: Variable, point: Expression, context: MathContext,
        atInfinity: Boolean = false, explicit: Pair<Expression, Expression>? = null
    ): Boolean {
        val (numerator, denominator) = explicit ?: splitFraction(body, v) ?: return false
        val quotient = Multiplications(Derivative(numerator, v), Power(Derivative(denominator, v), Value.MINUS_ONE))
        val notes = if (atInfinity) {
            // Both parts go to 0, or both grow without bound
            val positive = (point as Value).real.value > 0
            listOf(
                Note(TextExpression("f(x)"), TextExpression(describe(numericLimit(numerator, v, positive))), relation = "→"),
                Note(TextExpression("g(x)"), TextExpression(describe(numericLimit(denominator, v, positive))), relation = "→")
            )
        } else {
            // No value at the point (ln(0)) is null here
            val atNumerator = canonical(numerator.substituteVariables(mapOf(v to point)), context)
            val atDenominator = canonical(denominator.substituteVariables(mapOf(v to point)), context)
            // 0/0, or both growing without bound: ln(x)/(1/x) at 0
            val bothZero = atNumerator?.isZero == true && atDenominator?.isZero == true
            if (!bothZero && !(blowsUp(numerator, v, point) && blowsUp(denominator, v, point))) return false
            if (!bothZero) listOf(
                Note(TextExpression("f(x)"), TextExpression("±∞"), relation = "→"),
                Note(TextExpression("g(x)"), TextExpression("±∞"), relation = "→")
            ) else listOf(
                Note(numerator.substituteVariables(mapOf(v to point)), Value.ZERO),
                Note(denominator.substituteVariables(mapOf(v to point)), Value.ZERO)
            )
        }
        solution.apply(Match(StepRule.LHOPITAL, mapOf(listOf(0) to quotient), solution.current, quotient, notes = notes, showChange = false))
        // The derivatives, then the quotient simplified
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return false
            solution.apply(DerivativeSteps.derivativeMatch(solution.current, context) ?: break)
        }
        if (solution.current.containsExpressionMatching { it is Derivative || it is BackSubstitution }) return false
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return false
            val match = SimplificationSteps.match(at(solution.current, listOf(0)), Value.ZERO, context) ?: break
            solution.apply(EquationSteps.prefixed(match, listOf(0)))
        }
        return true
    }

    /** Whether [e] grows without bound (in size) as x approaches [point]; only to decide which rule applies. */
    private fun blowsUp(e: Expression, v: Variable, point: Expression): Boolean {
        val at = point.evaluateNumerically()
        if (!at.isFinite()) return false
        for (side in listOf(1.0, -1.0)) {
            val a = e.evaluateNumerically(mapOf(v to at + side * 1e-4))
            val b = e.evaluateNumerically(mapOf(v to at + side * 1e-8))
            val c = e.evaluateNumerically(mapOf(v to at + side * 1e-12))
            if (a.isFinite() && b.isFinite() && c.isFinite() && abs(c) > 10 && abs(c) > abs(b) && abs(b) > abs(a)) return true
        }
        return false
    }

    /**
     * f·g where one factor goes to 0 and the other grows without bound: written as f/(1/g) so that the
     * quotient is ∞/∞. Returns the numerator and the denominator, or null if the product is not of that kind.
     */
    private fun productAsQuotient(solution: Solver, body: Expression, v: Variable, point: Expression, context: MathContext): Pair<Expression, Expression>? {
        val factors = (body as? Multiplications)?.operands ?: return null
        val vanishing = mutableListOf<Expression>()
        val growing = mutableListOf<Expression>()
        for (factor in factors) {
            when {
                !factor.containsVariable(v) -> growing.add(factor)
                canonical(factor.substituteVariables(mapOf(v to point)), context)?.isZero == true -> vanishing.add(factor)
                blowsUp(factor, v, point) -> growing.add(factor)
                else -> return null
            }
        }
        if (vanishing.isEmpty() || growing.none { it.containsVariable(v) }) return null
        val numerator = Multiplications.of(*growing.toTypedArray())
        val denominator = canonical(Power(Multiplications.of(*vanishing.toTypedArray()), Value.MINUS_ONE), context) ?: return null
        val written = LiteralFraction(numerator, denominator)
        solution.apply(Match(StepRule.LIMIT_PRODUCT_AS_QUOTIENT, mapOf(listOf(0) to written), solution.current, written, showChange = false))
        return numerator to denominator
    }

    private fun describe(limit: Double): String = if (limit == 0.0) "0" else if (limit > 0) "∞" else "−∞"

    /** Whether [e] has a finite value at [v] = [point] (a division by 0 is no value). */
    private fun defined(e: Expression, v: Variable, point: Expression, context: MathContext): Boolean {
        // Each part on its own: 0·ln(0) simplifies to 0, but ln(0) is not defined
        if (e.children.any { it.containsVariable(v) && !defined(it, v, point, context) }) return false
        val scratch = context.copy()
        val result = e.substituteVariables(mapOf(v to point)).canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return false
        if (result.containsExpressionMatching { it is Value && !it.real.isFinite }) return false
        return result.variables().isNotEmpty() || result.evaluateNumerically().isFinite()
    }

    /** (numerator, denominator) of a product with factors such as (x − 1)⁻¹; null without a denominator. */
    private fun splitFraction(e: Expression, v: Variable): Pair<Expression, Expression>? {
        val factors = if (e is Multiplications) e.operands else listOf(e)
        val numerator = mutableListOf<Expression>()
        val denominator = mutableListOf<Expression>()
        for (factor in factors) {
            // x⁻¹ and also e⁻ˣ, written as 1/eˣ
            if (factor is Power && factor.exponent.isNegative && factor.containsVariable(v)) {
                val positive = factor.exponent.negate()
                denominator.add(if (positive == Value.ONE) factor.base else Power(factor.base, positive))
            } else {
                numerator.add(factor)
            }
        }
        if (denominator.isEmpty()) return null
        return Multiplications.of(*numerator.toTypedArray()) to Multiplications.of(*denominator.toTypedArray())
    }

    private fun canonical(e: Expression, context: MathContext): Expression? {
        val scratch = context.copy()
        val result = e.canonicalize(scratch)
        return if (scratch.shouldStop || scratch.issues.isNotEmpty()) null else result
    }
}
