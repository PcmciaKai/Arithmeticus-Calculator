package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.AbsoluteValue
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Constant
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
        exponentialForm(limit, point, expected, target, context)?.let { return it }
        val solution = Solver(context)
        solution.start(normalize(limit))
        if ((point as? Value)?.real?.isInfinite == true) {
            // x·sin(1/x) as x → ∞: u = 1/x goes to 0, where sin(u)/u is a limit of the finite kind
            val u = reciprocalSubstitution(solution, limit, point.real.value > 0, expected, context)
            return if (u != null) finitePoint(solution, u, Value.ZERO, target, expected, context)
            else atInfinity(solution, v, point.real.value > 0, target, expected, context)
        }
        return finitePoint(solution, v, point, target, expected, context, limit.side)
    }

    /**
     * The form 1^∞, e.g. (1 + 1/x)ˣ as x → ∞: f^g = e^(g·ln f), so the limit is e to the limit of g·ln(f), which has its own steps.
     * The steps are those of the limit of g·ln(f) between the first line and the result e^L.
     */
    private fun exponentialForm(limit: Limit, point: Expression, expected: Double, target: Expression, context: MathContext): ExpressionSolution? {
        val v = limit.variable
        val body = canonical(limit.body, context) as? Power ?: return null
        if (!body.base.containsVariable(v) || !body.exponent.containsVariable(v)) return null
        if (!expected.isFinite() || expected <= 0.0) return null
        // The base goes to 1, the exponent grows without bound
        val at = point.evaluateNumerically()
        val x = if (at.isInfinite()) at.let { Math.signum(it) * 1e7 } else at + (if (limit.side < 0) -1e-7 else 1e-7)
        val baseValue = body.base.evaluateNumerically(mapOf(v to x))
        val exponentValue = body.exponent.evaluateNumerically(mapOf(v to x))
        if (!baseValue.isFinite() || !exponentValue.isFinite() || abs(baseValue - 1.0) > 1e-3 || abs(exponentValue) < 1e3) return null

        val product = Multiplications.of(body.exponent, Logarithm.ln(body.base))
        val inner = Limit(product, v, limit.point, limit.side)
        val innerSolution = steps(inner, context) ?: return null
        val exponent = innerSolution.result
        if (abs(Power(Constant.E, exponent).evaluateNumerically() - expected) > 1e-7 * max(1.0, abs(expected))) return null

        val first = Solver(context)
        first.start(normalize(limit))
        first.apply(
            Match(
                StepRule.LIMIT_EXPONENTIAL_FORM, mapOf(emptyList<Int>() to inner), normalize(limit), inner,
                notes = listOf(Note(TextExpression("f(x)"), TextExpression("→ 1"), relation = ":"), Note(TextExpression("g(x)"), TextExpression("→ ∞"), relation = ":")),
                showChange = false, boxReplacements = false
            )
        )
        val lines = first.finish(inner).steps + innerSolution.steps.drop(1) +
            ExpressionStep(target, StepRule.LIMIT_EXPONENTIAL_RESULT, notes = listOf(Note(TextExpression("L"), exponent)))
        return ExpressionSolution(lines, target)
    }

    /** x·sin(1/x), x(e^(1/x) − 1) as x → ±∞: [limit] with u = 1/x and u → 0, the new variable; null if that is no help. */
    private fun reciprocalSubstitution(solution: Solver, limit: Limit, positive: Boolean, expected: Double, context: MathContext): Variable? {
        val v = limit.variable
        val body = canonical(limit.body, context) ?: return null
        fun isReciprocal(e: Expression) = e is Power && e.base == v && e.exponent == Value.MINUS_ONE
        // A function of 1/x, with x also outside of it
        val hasReciprocalArgument = body.containsExpressionMatching { f ->
            (f is TrigonometricFunction || f is Logarithm || (f is Power && f.exponent.containsVariable(v))) && f.children.any { child -> child.containsExpressionMatching { isReciprocal(it) } }
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

    /** The side of [point] the limit is taken from, written for a note: 0⁺, 0⁻ or just 0. */
    private fun approach(point: Expression, side: Int): String =
        "${writtenForm(point)}${if (side > 0) "⁺" else if (side < 0) "⁻" else ""}"

    /** The value of [e] a tiny bit to the right (side 1) or left (−1) of the point. */
    private fun nearPoint(e: Expression, v: Variable, point: Expression, side: Int): Double =
        e.evaluateNumerically(mapOf(v to point.evaluateNumerically() + side * 1e-7))

    /**
     * Where the limit is ∞ or −∞ and a rule says so: a quotient whose denominator goes to 0 and numerator does not
     * (1/x), ln(g) where g goes to 0⁺, and e^g or a^g where g goes to ±∞. Applies the step and returns true.
     */
    private fun infiniteValue(solution: Solver, body: Expression, v: Variable, point: Expression, expected: Double, side: Int, context: MathContext): Boolean? {
        if (!expected.isInfinite()) return null
        val infinity = Value(expected)
        val root = emptyList<Int>()
        // c/g with g → 0
        splitFraction(body, v)?.let { (numerator, denominator) ->
            val atNumerator = canonical(numerator.substituteVariables(mapOf(v to point)), context) ?: return@let
            val atDenominator = canonical(denominator.substituteVariables(mapOf(v to point)), context) ?: return@let
            if (!atDenominator.isZero || atNumerator.isZero || !atNumerator.evaluateNumerically().isFinite()) return@let
            val sides = if (side == 0) listOf(1, -1) else listOf(side)
            val notes = listOf(
                Note(TextExpression("denominator"), TextExpression("→ 0${describeSides(denominator, v, point, sides)}")),
                Note(TextExpression("numerator"), atNumerator)
            )
            solution.apply(Match(StepRule.LIMIT_POLE, mapOf(root to infinity), solution.current, infinity, notes = notes, showChange = false))
            return true
        }
        // ln(g), g → 0⁺
        val log = body as? Logarithm
        if (log != null && !log.base.containsVariable(v)) {
            val atInside = canonical(log.operand.substituteVariables(mapOf(v to point)), context)
            if (atInside?.isZero == true) {
                val notes = listOf(Note(TextExpression("${writtenForm(log.operand)}"), TextExpression("→ 0⁺"), relation = ":"))
                solution.apply(Match(StepRule.LIMIT_LOGARITHM_ZERO, mapOf(root to infinity), solution.current, infinity, notes = notes, showChange = false))
                return true
            }
        }
        return null
    }

    /** "⁺", "⁻" or "": the sign g has next to the point on each side, written for a note about g → 0. */
    private fun describeSides(g: Expression, v: Variable, point: Expression, sides: List<Int>): String {
        val signs = sides.map { Math.signum(nearPoint(g, v, point, it)) }
        return if (signs.all { it > 0 }) "⁺" else if (signs.all { it < 0 }) "⁻" else ""
    }

    /** The limit for x → [point], a number: substituted, or after cancelling and l'Hôpital's rule. */
    private fun finitePoint(solution: Solver, v: Variable, point: Expression, target: Expression, expected: Double, context: MathContext, side: Int = 0): ExpressionSolution? {
        // |x|/x for x → 0⁺: |x| is x there
        absoluteValues(solution, v, point, side)
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
            // e^(1/x) for x → 0⁺: the exponent goes to ∞
            exponentToInfinity(solution, body, v, point, expected, side)?.let { return solution.finish(target) }
            // 1/x at 0: the denominator goes to 0, the numerator does not
            infiniteValue(solution, body, v, point, expected, side, context)?.let { return solution.finish(target) }
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
            // √(x² + x) − x: multiplied by the conjugate
            conjugateParts(body, v, positive, context)?.let { return conjugate(solution, it, v, positive, target, expected, context) }
            // A sum with terms that have a limit of their own, e^(−t) → 0 in 1 − e^(−t), atan(t) → π/2: those are replaced by it
            if (body is Additions && body.operands.any { it.containsVariable(v) }) {
                fun known(term: Expression): Expression? {
                    if (!term.containsVariable(v)) return null
                    if (abs(numericLimit(term, v, positive)) < 1e-12) return Value.ZERO
                    // atan(g) with g growing: ±π/2
                    val (coefficient, atan) = when {
                        term is TrigonometricFunction -> 1L to term
                        term is Multiplications && term.operands.size == 2 && term.operands[0] is Value && term.operands[1] is TrigonometricFunction ->
                            ((term.operands[0] as Value).rational?.takeIf { !it.isFractional }?.numerator?.value?.toLong() ?: return null) to (term.operands[1] as TrigonometricFunction)
                        else -> return null
                    }
                    if (atan.type != com.vortessence.calculator.core.math.expressions.TrigType.ATAN) return null
                    val inner = numericLimit(atan.operand, v, positive)
                    if (!inner.isInfinite()) return null
                    val sign = if (inner > 0) coefficient else -coefficient
                    return Multiplications.of(Value(sign), Value.HALF, Constant.PI)
                }
                val replaced = body.operands.mapIndexedNotNull { i, term -> known(term)?.let { i to it } }
                if (replaced.isNotEmpty() && replaced.size < body.operands.size + 0 && replaced.size <= body.operands.size) {
                    val replacedBody = Additions(body.operands.mapIndexed { i, term -> replaced.firstOrNull { it.first == i }?.second ?: term })
                    solution.apply(
                        Match(
                            StepRule.LIMIT_VANISH,
                            replaced.associate { (listOf(0) + it.first) to Marked(it.second, PENDING_COLOR, boxed = true, keep = true) },
                            body, replacedBody, showChange = false, boxReplacements = false
                        )
                    )
                    while (true) {
                        if (context.shouldStop || solution.size > MAX_STEPS) return null
                        solution.apply(EquationSteps.prefixed(SimplificationSteps.match(at(solution.current, listOf(0)), Value.ZERO, context) ?: break, listOf(0)))
                    }
                    return@repeat
                }
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

    // ----- |g| next to the point, e^g with g → ±∞ -----

    /**
     * |g| where g is 0 at the point and has one sign next to it on the side of the limit: g or −g (|x|/x for x → 0⁺ is x/x).
     */
    private fun absoluteValues(solution: Solver, v: Variable, point: Expression, side: Int) {
        if (side == 0) return
        val body = normalize(unmarked(at(solution.current, listOf(0))))
        val all = pathsOf(body) { it is AbsoluteValue && it.operand.containsVariable(v) }
        // Only the outermost: the paths of the inner ones change with the outer replaced
        val paths = all.filter { path -> all.none { other -> other.size < path.size && path.subList(0, other.size) == other } }
        if (paths.isEmpty()) return
        val replacements = linkedMapOf<Path, Expression>()
        var positive: Boolean? = null
        var shown: Expression? = null
        var after = body
        for (path in paths) {
            val operand = (at(body, path) as AbsoluteValue).operand
            val near = nearPoint(operand, v, point, side)
            if (near == 0.0 || near.isNaN()) return
            replacements[listOf(0) + path] = Marked(if (near > 0) operand else operand.negate(), PENDING_COLOR, boxed = true, keep = true)
            if (shown == null) { shown = operand; positive = near > 0 }
        }
        after = body.transform { e -> if (e is AbsoluteValue && e.operand.containsVariable(v)) (if (nearPoint(e.operand, v, point, side) > 0) e.operand else e.operand.negate()) else e }
        val text = "${writtenForm(shown!!)} is ${if (positive == true) "positive" else "negative"} for ${v.identifier} → ${writtenForm(point)}${if (side > 0) "⁺" else "⁻"}"
        solution.apply(Match(StepRule.LIMIT_ABSOLUTE_VALUE, replacements, body, after, notes = listOf(Note(TextExpression(text), TextExpression(""), relation = "")), showChange = false, boxReplacements = false))
    }

    /** a^g (a a constant above 0, not 1) where g goes to ±∞ next to the point: ∞ or 0. */
    private fun exponentToInfinity(solution: Solver, body: Expression, v: Variable, point: Expression, expected: Double, side: Int): Boolean? {
        val power = body as? Power ?: return null
        if (power.base.containsVariable(v) || !power.exponent.containsVariable(v)) return null
        val base = power.base.evaluateNumerically()
        if (!(base > 0) || base == 1.0 || !base.isFinite()) return null
        val sides = if (side == 0) listOf(1, -1) else listOf(side)
        val signs = sides.map { s ->
            val near = nearPoint(power.exponent, v, point, s)
            val nearer = power.exponent.evaluateNumerically(mapOf(v to point.evaluateNumerically() + s * 1e-9))
            if (abs(near) > 1e3 && abs(nearer) > abs(near) && (near > 0) == (nearer > 0)) Math.signum(near) else return null
        }
        if (signs.distinct().size != 1) return null
        val grows = (signs.first() > 0) == (base > 1)
        val result = if (grows) Value(Double.POSITIVE_INFINITY) else Value.ZERO
        if ((result.real.isInfinite && !expected.isInfinite()) || (!result.real.isInfinite && abs(expected) > 1e-12)) return null
        val goes = if (signs.first() > 0) "∞" else "−∞"
        val where = "${v.identifier} → ${writtenForm(point)}${if (side > 0) "⁺" else if (side < 0) "⁻" else ""}"
        val note = Note(power.exponent, TextExpression("$goes  as  $where"), relation = "→")
        solution.apply(Match(StepRule.LIMIT_EXPONENT_INFINITE, mapOf(emptyList<Int>() to result), solution.current, result, notes = listOf(note), showChange = false))
        return true
    }

    // ----- √a − b at ±∞ -----

    /** A term of the sum: its sign (1 or −1) and what is left. */
    private class Term(val sign: Int, val core: Expression, val original: Expression)

    private class ConjugateParts(val first: Term, val second: Term, val degree: Int)

    private fun term(e: Expression): Term {
        val product = e as? Multiplications
        return if (product != null && product.operands.size == 2 && product.operands[0] == Value.MINUS_ONE) Term(-1, product.operands[1], e) else Term(1, e, e)
    }

    private fun radicand(e: Expression): Expression? = (e as? Power)?.takeIf { (it.exponent as? Value)?.rational == com.vortessence.calculator.core.math.numbers.Rational.of(1L, 2L) }?.base

    /** The two terms if the sum is √a ± b or √a ± √c with polynomials of the degrees 2k and k (or 2k and 2k), as at x → ±∞ they are ∞ − ∞. */
    private fun conjugateParts(body: Expression, v: Variable, positive: Boolean, context: MathContext): ConjugateParts? {
        val sum = body as? Additions ?: return null
        if (sum.operands.size != 2) return null
        val terms = sum.operands.map { term(it) }
        if (terms.none { radicand(it.core) != null }) return null
        var k = -1
        for (t in terms) {
            val inside = radicand(t.core)
            val polynomial = PartialFractions.polynomial(inside ?: t.core, v, context) ?: return null
            val degree = polynomial.size - 1
            if (inside != null) {
                if (degree < 2 || degree % 2 != 0) return null
                if (k == -1) k = degree / 2 else if (k != degree / 2) return null
            } else if (degree < 1) return null
        }
        // The polynomial term has the degree k as well
        for (t in terms) {
            if (radicand(t.core) == null && (PartialFractions.polynomial(t.core, v, context)!!.size - 1) != k) return null
        }
        return if (k >= 1) ConjugateParts(terms[0], terms[1], k) else null
    }

    private fun conjugate(
        solution: Solver, parts: ConjugateParts, v: Variable, positive: Boolean, target: Expression, expected: Double, context: MathContext
    ): ExpressionSolution? {
        val body = normalize(unmarked(at(solution.current, listOf(0))))
        val t1 = parts.first.original
        val t2 = parts.second.original
        val conjugate = Additions(t1, t2.negate())
        // (t₁ + t₂)(t₁ − t₂)/(t₁ − t₂)
        val product = Multiplications(Additions(t1, t2), conjugate)
        val multiplied = LiteralFraction(product, conjugate)
        solution.apply(Match(StepRule.LIMIT_CONJUGATE, mapOf(listOf(0) to multiplied), body, multiplied, showChange = false, boxReplacements = false))
        // a² − b²: the numerator
        // (the signs do not matter in a square: x², not (−x)²)
        val squares = Additions(Power(parts.first.core, Value.TWO), Power(parts.second.core, Value.TWO).negate())
        val numeratorPath = listOf(0, 0)
        solution.apply(Match(StepRule.LIMIT_DIFFERENCE_OF_SQUARES, mapOf(numeratorPath to squares), product, squares, showChange = false, boxReplacements = false))
        val scratch = context.copy()
        val numerator = squares.canonicalize(scratch)
        if (scratch.shouldStop) return null
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return null
            val match = SimplificationSteps.match(at(solution.current, numeratorPath), numerator, context) ?: break
            solution.apply(EquationSteps.prefixed(match, numeratorPath))
        }
        if (writtenForm(at(solution.current, numeratorPath)) != writtenForm(numerator)) {
            solution.apply(Match(StepRule.SIMPLIFY, mapOf(numeratorPath to numerator), normalize(unmarked(at(solution.current, numeratorPath))), numerator))
        }
        // Divided by the highest power x^k, the terms with x in a denominator vanish
        val k = parts.degree
        val n = PartialFractions.polynomial(numerator, v, context) ?: return null
        if (n.size - 1 > k) return null
        // √a/xᵏ is √(a/x²ᵏ) for x > 0 and −√(a/x²ᵏ) for x < 0 with k odd
        val rootSign = if (!positive && k % 2 == 1) -1L else 1L
        fun divide(t: Term): Expression {
            val inside = radicand(t.core)
            val divided = if (inside != null) {
                val p = PartialFractions.polynomial(inside, v, context) ?: return Value.ZERO
                val root = Power(dividedBy(p, 2 * k, v), Value.HALF)
                if (rootSign < 0) Multiplications(Value.MINUS_ONE, root) else root
            } else dividedBy(PartialFractions.polynomial(t.core, v, context) ?: return Value.ZERO, k, v)
            return if (t.sign < 0) Multiplications(Value.MINUS_ONE, divided) else divided
        }
        // The terms of the denominator t₁ − t₂: t₁ and −t₂
        val d1 = divide(parts.first)
        val d2 = divide(Term(-parts.second.sign, parts.second.core, parts.second.original))
        val denominator = Additions(d1, d2)
        val divided = LiteralFraction(dividedBy(n, k, v), denominator)
        val before = normalize(unmarked(at(solution.current, listOf(0))))
        solution.apply(Match(StepRule.DIVIDE_BY_HIGHEST_POWER, mapOf(listOf(0) to divided), before, divided, showChange = false, boxReplacements = false))
        val vanishing = pathsOf(at(solution.current, listOf(0))) { isVanishing(it, v) }
            .let { all -> all.filter { path -> all.none { other -> other.size < path.size && path.subList(0, other.size) == other } } }
            .map { listOf(0) + it }
        if (vanishing.isEmpty()) return null
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
        val last = normalize(unmarked(at(solution.current, listOf(0)))).evaluateNumerically()
        if (abs(last - expected) > 1e-7 * max(1.0, abs(expected))) return null
        return solution.finish(target)
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
        // e^x/(2x) − 1/(2x), as the simplified derivatives come out: one fraction over the common denominator
        if (e is Additions) {
            val parts = e.operands.map { splitFraction(it, v) ?: return null }
            val denominator = parts.first().second
            if (parts.any { it.second != denominator }) return null
            return Additions(parts.map { it.first }) to denominator
        }
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
