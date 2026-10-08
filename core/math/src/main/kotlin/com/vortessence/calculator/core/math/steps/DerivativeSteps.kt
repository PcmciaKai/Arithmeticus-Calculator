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
import com.vortessence.calculator.core.math.expressions.TrigonometricFunction
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.substituteVariables
import com.vortessence.calculator.core.math.expressions.tidyForDisplay
import com.vortessence.calculator.core.math.type.MathWriter

/**
 * A step of a solution before it is written for display: [expression] with the part the next
 * step changes highlighted, and the [change] that led to it (see [SolutionStep]).
 */
internal class ExpressionStep(
    val expression: Expression,
    val rule: StepRule?,
    val chainRule: Boolean = false,
    val change: Pair<Expression, Expression>? = null
)

internal class ExpressionSolution(val steps: List<ExpressionStep>, val result: Expression)

/** The position of a part of an expression: the child indices from the root down to it. */
internal typealias Path = List<Int>

/**
 * A rule applied to one part of an expression: [replacements] replace the parts at their paths
 * (and are highlighted as the result of the step), [removals] replace the parts that merge into
 * them by a neutral 1 or 0 (e.g. the second number in 2·2 → 4). [before] = [after] shows it.
 */
internal class Match(
    val rule: StepRule,
    val replacements: Map<Path, Expression>,
    val before: Expression,
    val after: Expression,
    val removals: Map<Path, Expression> = emptyMap(),
    val chainRule: Boolean = false
)

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
        val compact = if (simplified.variables().isNotEmpty() && simplified.partWithUnit() == null) {
            tidyForDisplay(simplified, context)
        } else {
            simplified
        }

        val solution = Solver(context)
        solution.start(Derivative(body, variable, derivative.point))
        // Differentiate, then simplify step by step
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return null
            val match = derivativeMatch(solution.current, context)
                ?: SimplificationSteps.match(solution.current, context)
                ?: break
            solution.apply(match)
        }
        if (solution.hasOpenDerivative()) return null
        // What the rules did not do, e.g. multiplying out a product of sums
        if (writtenForm(solution.current) != writtenForm(compact)) {
            solution.apply(Match(StepRule.SIMPLIFY, mapOf(emptyList<Int>() to compact), normalize(unmarked(solution.current)), compact))
        }

        val point = derivative.point ?: return solution.finish(compact)
        val variablePaths = pathsOf(solution.current) { it == variable }
        val substituted = unmarked(solution.current).substituteVariables(mapOf(variable to point))
        solution.apply(Match(StepRule.SUBSTITUTE, variablePaths.associateWith { point }, variable, point))
        val value = derivative.canonicalize(context)
        if (context.shouldStop) return null
        solution.apply(Match(StepRule.EVALUATE, mapOf(emptyList<Int>() to value), substituted, value))
        return solution.finish(value)
    }

    /** Builds the steps: each applied [Match] gives the next expression and highlights its parts. */
    private class Solver(val context: MathContext) {
        private class Line(val expression: Expression, val rule: StepRule?, val chainRule: Boolean, val change: Pair<Expression, Expression>?) {
            var next: Set<Path> = emptySet()
        }

        private val lines = mutableListOf<Line>()
        val size: Int get() = lines.size
        val current: Expression get() = lines.last().expression

        fun start(problem: Expression) {
            lines.add(Line(problem, null, false, null))
        }

        fun apply(match: Match) {
            val color = lines.size
            lines.last().next = match.replacements.keys + match.removals.keys
            val result = normalize(rebuild(current, emptyList(), match, color))
            lines.add(Line(result, match.rule, match.chainRule, match.before to match.after))
        }

        fun hasOpenDerivative(): Boolean = current.containsExpressionMatching { it is Derivative }

        fun finish(result: Expression) = ExpressionSolution(
            lines.mapIndexed { i, line ->
                ExpressionStep(highlight(line.expression, emptyList(), line.next, i + 1), line.rule, line.chainRule, line.change)
            },
            result
        )

        /** [e] with the parts of [match] replaced (boxed in [color]) and earlier boxes removed. */
        private fun rebuild(e: Expression, path: Path, match: Match, color: Int): Expression {
            match.replacements[path]?.let { return Marked(it, color, boxed = true) }
            match.removals[path]?.let { return it }
            if (e is Marked) return rebuild(e.inner, path + 0, match, color)
            if (e.children.isEmpty()) return e
            return e.copyWithChildren(e.children.mapIndexed { i, child -> rebuild(child, path + i, match, color) })
        }

        /** [e] with the parts at [paths] colored in [color]. */
        private fun highlight(e: Expression, path: Path, paths: Set<Path>, color: Int): Expression {
            if (path in paths) return Marked(e, color, boxed = false)
            if (e.children.isEmpty() || paths.none { it.size > path.size && it.subList(0, path.size) == path }) return e
            return e.copyWithChildren(e.children.mapIndexed { i, child -> highlight(child, path + i, paths, color) })
        }
    }

    /** The first derivative that is still open, with one rule applied to it. */
    private fun derivativeMatch(e: Expression, context: MathContext): Match? {
        val path = pathsOf(e) { it is Derivative }.firstOrNull() ?: return null
        val open = at(e, path) as Derivative
        val (rewritten, rule, chainRule) = Rules(open.variable, context).apply(open.body) ?: return null
        return Match(rule, mapOf(path to rewritten), Derivative(open.body, open.variable), rewritten, chainRule = chainRule)
    }

    /** How [e] looks without highlighting (a highlighted product inside a product is flattened). */
    private fun writtenForm(e: Expression): String = MathWriter().write(normalize(unmarked(e))).toString()

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
            is Additions, is Multiplications, is Power, is Logarithm, is TrigonometricFunction, is AbsoluteValue ->
                e.children.all { hasRules(it, v) }
            is Integral -> (e.variable == v || !e.integrand.containsVariable(v)) &&
                hasRules(e.lowerBound, v) && hasRules(e.upperBound, v)
            else -> false
        }
    }

    private data class Rewrite(val expression: Expression, val rule: StepRule, val chainRule: Boolean = false)

    private class Rules(val v: Variable, val context: MathContext) {

        fun apply(u: Expression): Rewrite? {
            if (!u.containsVariable(v)) return Rewrite(Value.ZERO, StepRule.CONSTANT)
            return when (u) {
                v -> Rewrite(Value.ONE, StepRule.VARIABLE)
                is Additions -> sum(u)
                is Multiplications -> product(u)
                is Power -> power(u)
                is Logarithm -> logarithm(u)
                is TrigonometricFunction -> trigonometric(u)
                is AbsoluteValue -> chain(StepRule.ABSOLUTE_VALUE, u.operand, u.operand, Power(u, Value.MINUS_ONE))
                is Integral -> integral(u)
                else -> null
            }
        }

        private fun d(u: Expression) = Derivative(u, v)

        /** outer(u)·u', where u' is left open (chain rule) unless u is the variable itself. */
        private fun chain(rule: StepRule, u: Expression, vararg outer: Expression): Rewrite =
            if (u == v) Rewrite(product(*outer), rule) else Rewrite(product(*outer, d(u)), rule, chainRule = true)

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

        private fun power(u: Power): Rewrite {
            val base = u.base
            val exponent = u.exponent
            return when {
                !exponent.containsVariable(v) -> {
                    val lowered = Additions(exponent, Value.MINUS_ONE)
                    val newExponent = if (exponent is Value) lowered.canonicalize(context) else lowered
                    val outer = when (newExponent) {
                        Value.ONE -> base
                        Value.ZERO -> Value.ONE
                        else -> Power(base, newExponent)
                    }
                    chain(StepRule.POWER, base, exponent, outer)
                }
                !base.containsVariable(v) ->
                    if (base == Constant.E) chain(StepRule.EXPONENTIAL, exponent, u)
                    else chain(StepRule.EXPONENTIAL, exponent, u, Logarithm.ln(base))
                else -> Rewrite(product(u, d(product(exponent, Logarithm.ln(base)))), StepRule.GENERAL_POWER)
            }
        }

        private fun logarithm(u: Logarithm): Rewrite {
            if (u.base.containsVariable(v)) {
                val quotient = product(Logarithm.ln(u.operand), Power(Logarithm.ln(u.base), Value.MINUS_ONE))
                return Rewrite(d(quotient), StepRule.CHANGE_OF_BASE)
            }
            val denominator = if (u.base == Constant.E) u.operand else product(u.operand, Logarithm.ln(u.base))
            return chain(StepRule.LOGARITHM, u.operand, Power(denominator, Value.MINUS_ONE))
        }

        private fun trigonometric(u: TrigonometricFunction): Rewrite {
            val x = u.operand
            val unit = u.angleUnit ?: context.angleUnit
            fun trig(type: TrigType) = TrigonometricFunction(type, x, unit)
            val oneMinusXSquared = Additions(Value.ONE, product(Value.MINUS_ONE, Power(x, Value.TWO)))
            val (rule, outer) = when (u.type) {
                TrigType.SIN -> StepRule.SINE to trig(TrigType.COS)
                TrigType.COS -> StepRule.COSINE to product(Value.MINUS_ONE, trig(TrigType.SIN))
                TrigType.TAN -> StepRule.TANGENT to Power(trig(TrigType.COS), Value(-2L))
                TrigType.ASIN -> StepRule.ARCSINE to Power(oneMinusXSquared, Value(-1L, 2L))
                TrigType.ACOS -> StepRule.ARCCOSINE to product(Value.MINUS_ONE, Power(oneMinusXSquared, Value(-1L, 2L)))
                TrigType.ATAN -> StepRule.ARCTANGENT to Power(Additions(Value.ONE, Power(x, Value.TWO)), Value.MINUS_ONE)
            }
            // With angles in degrees: sin's operand and the inverse functions' results are scaled
            val degreeFactor: Expression = when {
                unit != AngleUnit.DEGREES -> Value.ONE
                u.type.isInverse -> product(Value(180L), Power(Constant.PI, Value.MINUS_ONE))
                else -> product(Constant.PI, Value(1L, 180L))
            }
            return chain(rule, x, outer, degreeFactor)
        }

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

    /**
     * Flattens nested sums and products and drops factors 1 and terms 0, so that a step does
     * not show leftovers of the rules such as 1·x or + 0. Nothing else is simplified.
     */
    fun normalize(e: Expression): Expression = when (e) {
        is Additions -> {
            val terms = e.operands.map { normalize(it) }
                .flatMap { if (it is Additions) it.operands else listOf(it) }
                .filterNot { it.isZero }
            when (terms.size) {
                0 -> Value.ZERO
                1 -> terms.single()
                else -> Additions(terms)
            }
        }
        is Multiplications -> {
            val factors = e.operands.map { normalize(it) }
                .flatMap { if (it is Multiplications) it.operands else listOf(it) }
            when {
                factors.any { it.isZero } -> Value.ZERO
                // Also a highlighted 1, e.g. from d/dx(x) = 1: the step shows where it went
                else -> Multiplications.of(*factors.filterNot { unmarked(it) == Value.ONE }.toTypedArray())
            }
        }
        else -> if (e.children.isEmpty()) e else e.copyWithChildren(e.children.map { normalize(it) })
    }
}

/** The paths of the parts of [e] that match [predicate], in reading order (parents first). */
internal fun pathsOf(e: Expression, path: Path = emptyList(), predicate: (Expression) -> Boolean): List<Path> {
    val own = if (predicate(e)) listOf(path) else emptyList()
    return own + e.children.flatMapIndexed { i, child -> pathsOf(child, path + i, predicate) }
}

/** The part of [e] at [path]. */
internal fun at(e: Expression, path: Path): Expression = path.fold(e) { part, i -> part.children[i] }
