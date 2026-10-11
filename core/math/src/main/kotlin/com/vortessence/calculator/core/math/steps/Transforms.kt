package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Antiderivative
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.EquationSystem
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Inequality
import com.vortessence.calculator.core.math.expressions.Integral
import com.vortessence.calculator.core.math.expressions.Limit
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.PartialFractions
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.Series
import com.vortessence.calculator.core.math.expressions.UnitExpression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.expandProducts
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import kotlin.math.abs
import kotlin.math.max

/** What a user can ask of an algebraic expression that the calculator leaves as it is typed. */
enum class Transform { EXPAND, FACTOR, COMBINE }

/**
 * The steps of writing an expression in another form: multiplied out ((x + 1)² = x² + 2x + 1), factored
 * (x² − 5x + 6 = (x − 2)(x − 3)) or as one fraction (1/(x − 1) − 1/(x + 1) = 2/((x − 1)(x + 1))). Null if the expression
 * is already in that form, is not of that kind, or if the result is not the same function.
 */
internal object Transforms {

    private const val MAX_STEPS = 80

    fun steps(e: Expression, kind: Transform, context: MathContext): ExpressionSolution? {
        if (e.variables().isEmpty()) return null
        if (e.containsExpressionMatching {
                it is MatrixExpression || it is UnitExpression || it is Derivative || it is Antiderivative || it is Integral ||
                    it is Limit || it is Series || it is Equals || it is Inequality || it is EquationSystem
            }
        ) return null
        val typed = normalize(e)
        val scratch = context.copy()
        val canonical = typed.canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        val result = when (kind) {
            Transform.EXPAND -> expand(typed, canonical, context)
            Transform.FACTOR -> factor(typed, canonical, context)
            Transform.COMBINE -> combine(typed, canonical, context)
        } ?: return null
        // The same function: compared at a few points
        if (!sameFunction(canonical, result.result)) return null
        // Nothing to show if it is the form the calculator gives anyway
        if (writtenForm(result.result) == writtenForm(displayForm(canonical, context))) return null
        return result
    }

    private fun sameFunction(a: Expression, b: Expression): Boolean {
        val variables = (a.variables() + b.variables()).toList()
        var checked = 0
        for ((i, x) in SAMPLES.withIndex()) {
            val bindings = variables.withIndex().associate { (j, v) -> v to SAMPLES[(i + 2 * j) % SAMPLES.size] }
            val l = a.evaluateNumerically(bindings)
            val r = b.evaluateNumerically(bindings)
            if (!l.isFinite() || !r.isFinite()) continue
            if (abs(l - r) > 1e-9 * max(1.0, max(abs(l), abs(r)))) return false
            checked++
        }
        return checked >= 3
    }

    private val SAMPLES = listOf(0.37, 1.13, 2.71, -0.83, 0.52, -1.9, 3.3)

    // ----- expand -----

    private fun expand(typed: Expression, canonical: Expression, context: MathContext): ExpressionSolution? {
        val scratch = context.copy()
        val expanded = expandProducts(canonical).canonicalize(scratch)
        if (scratch.shouldStop || writtenForm(expanded) == writtenForm(canonical)) return null
        val solution = Solver(context)
        solution.start(typed)
        if (!solution.simplifyToward(expanded, MAX_STEPS)) return null
        if (solution.size < 2) return null
        return solution.finish(expanded)
    }

    // ----- factor -----

    private fun factor(typed: Expression, canonical: Expression, context: MathContext): ExpressionSolution? {
        val variable = canonical.variables().singleOrNull() ?: return null
        val coefficients = PartialFractions.polynomial(canonical, variable, context) ?: return null
        if (coefficients.size < 3) return null
        // P = c·(q₁x − p₁)^m₁ … · R: the rational roots by the rational root theorem, what is left stays
        var rest = coefficients
        val roots = mutableListOf<Pair<Rational, Int>>()
        while (rest.size > 1) {
            val root = PartialFractions.rationalRoot(rest) ?: break
            var multiplicity = 0
            while (rest.size > 1 && PartialFractions.value(rest, root).isZero) {
                rest = PartialFractions.divideByLinear(rest, root)
                multiplicity++
            }
            roots.add(root to multiplicity)
        }
        roots.sortBy { it.first }
        if (roots.isEmpty()) return commonFactor(typed, coefficients, variable, context)

        // (x − 1/2) with the 2 in front of it: (2x − 1), the constant c collects what is left
        var constant = rest.last().takeIf { rest.size == 1 } ?: Rational.ONE
        val factors = mutableListOf<Expression>()
        fun power(base: Expression, m: Int) = if (m == 1) base else Power(base, Value(m.toLong()))
        for ((root, m) in roots) {
            val q = root.denominator
            val linear: Expression = if (q == BigInt.ONE) {
                if (root.isZero) variable else Additions(variable, Value(-root))
            } else {
                // q·x − p
                constant = constant / Rational.of(q).pow(m.toLong())
                Additions(Multiplications(Value(q), variable), Value(-root.numerator))
            }
            factors.add(power(linear, m))
        }
        if (rest.size > 1) {
            // What is left of degree 2 or more has no rational root: x² − 2 or x² + 1
            val scale = rest.last()
            constant = constant * scale
            factors.add(PartialFractions.toExpression(rest.map { it / scale }, variable))
        }
        val product = Multiplications.of(*((if (constant != Rational.ONE) listOf<Expression>(Value(constant)) else emptyList()) + factors).toTypedArray())
        val factored = product
        val notes = roots.map { (root, _) ->
            val atRoot = canonical.transformVariable(variable, Value(root))
            Note(atRoot, Value.ZERO)
        }
        val solution = Solver(context)
        solution.start(typed)
        solution.apply(Match(StepRule.FACTOR_POLYNOMIAL, mapOf(emptyList<Int>() to factored), typed, factored, notes = notes, showChange = false, boxReplacements = false))
        return solution.finish(factored)
    }

    private fun Expression.transformVariable(v: Variable, value: Expression): Expression = transform { if (it == v) value else it }

    /** 2x² + 4x = 2x(x + 2): the common factor of the terms, if there is no rational root to use. */
    private fun commonFactor(typed: Expression, c: List<Rational>, v: Variable, context: MathContext): ExpressionSolution? {
        val lowest = c.indexOfFirst { !it.isZero }
        // The greatest common whole divisor of the numerators, over the common denominator
        val scale = c.fold(BigInt.ONE) { l, x -> l * x.denominator / l.gcd(x.denominator) }
        val whole = c.map { (it * scale).numerator }
        var gcd = BigInt.ZERO
        for (w in whole) gcd = gcd.gcd(w)
        if (gcd.isZero) return null
        val content = Rational.of(gcd, scale)
        val shifted = c.drop(lowest).map { it / content }
        if (content == Rational.ONE && lowest == 0) return null
        val xPower: Expression? = when (lowest) { 0 -> null; 1 -> v; else -> Power(v, Value(lowest.toLong())) }
        val inner = PartialFractions.toExpression(shifted, v)
        val front = listOfNotNull(Value(content).takeIf { content != Rational.ONE }, xPower)
        val factored = Multiplications.of(*(front + inner).toTypedArray())
        val solution = Solver(context)
        solution.start(typed)
        solution.apply(Match(StepRule.FACTOR_POLYNOMIAL, mapOf(emptyList<Int>() to factored), typed, factored, showChange = false, boxReplacements = false))
        return solution.finish(factored)
    }

    // ----- combine -----

    private fun combine(typed: Expression, canonical: Expression, context: MathContext): ExpressionSolution? {
        val terms = if (canonical is Additions) canonical.operands else return null
        class Fraction(val numerator: Expression, val denominators: List<Pair<Expression, Int>>)
        fun split(term: Expression): Fraction {
            val factors = if (term is Multiplications) term.operands else listOf(term)
            val denominators = mutableListOf<Pair<Expression, Int>>()
            val numerator = mutableListOf<Expression>()
            for (f in factors) {
                val n = ((f as? Power)?.exponent as? Value)?.rational
                if (f is Power && n != null && !n.isFractional && n.isNegative && f.base.variables().isNotEmpty()) denominators.add(f.base to -n.numerator.value.toInt())
                else numerator.add(f)
            }
            return Fraction(Multiplications.of(*numerator.toTypedArray()), denominators)
        }
        val parts = terms.map { split(it) }
        if (parts.none { it.denominators.isNotEmpty() }) return null
        // The common denominator: every denominator once, with its highest power
        val common = mutableListOf<Pair<Expression, Int>>()
        for (p in parts) for ((base, power) in p.denominators) {
            val i = common.indexOfFirst { it.first == base }
            if (i < 0) common.add(base to power) else if (power > common[i].second) common[i] = base to power
        }
        fun denominatorOf(list: List<Pair<Expression, Int>>): Expression = Multiplications.of(*list.map { (b, k) -> if (k == 1) b else Power(b, Value(k.toLong())) }.toTypedArray())
        val denominator = denominatorOf(common)
        // Each numerator times what its denominator lacks
        val numerators = parts.map { p ->
            val missing = common.mapNotNull { (base, power) ->
                val have = p.denominators.firstOrNull { it.first == base }?.second ?: 0
                (base to (power - have)).takeIf { it.second > 0 }
            }
            Multiplications.of(p.numerator, *missing.map { (b, k) -> if (k == 1) b else Power(b, Value(k.toLong())) as Expression }.toTypedArray())
        }
        val extended = Additions(numerators.map { LiteralFraction(it, denominator) })
        val together = LiteralFraction(Additions(numerators), denominator)

        val scratch = context.copy()
        val numerator = expandProducts(Additions(numerators)).canonicalize(scratch)
        if (scratch.shouldStop) return null
        // One fraction as it is: the numerator multiplied out, the denominator left in factors (canonical would split it up again)
        val target = LiteralFraction(numerator, denominator)

        val solution = Solver(context)
        solution.start(typed)
        solution.apply(Match(StepRule.EXTEND_FRACTIONS, mapOf(emptyList<Int>() to extended), typed, extended, notes = listOf(Note(TextExpression("common denominator"), denominator)), showChange = false, boxReplacements = false))
        solution.apply(Match(StepRule.COMBINE_FRACTIONS, mapOf(emptyList<Int>() to together), extended, together, showChange = false, boxReplacements = false))
        // The numerator is simplified on its own (the sums of the denominator stay as they are)
        val path = listOf(0)
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return null
            val match = SimplificationSteps.match(at(solution.current, path), numerator, context) ?: break
            solution.apply(EquationSteps.prefixed(match, path))
        }
        if (writtenForm(at(solution.current, path)) != writtenForm(numerator)) {
            solution.apply(Match(StepRule.SIMPLIFY, mapOf(path to numerator), normalize(unmarked(at(solution.current, path))), numerator))
        }
        return solution.finish(target)
    }
}
