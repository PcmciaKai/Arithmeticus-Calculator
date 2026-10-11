package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Between
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Inequality
import com.vortessence.calculator.core.math.expressions.InequalityAnalysis
import com.vortessence.calculator.core.math.expressions.InequalitySolver
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.Relation
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.substituteVariables
import com.vortessence.calculator.core.math.numbers.Rational
import kotlin.math.abs
import kotlin.math.max

/**
 * The steps of solving an inequality, as by hand:
 * - a linear one like the equation (the steps of [EquationSteps]), with the relation turned around
 *   where both sides are multiplied or divided by a negative number: −3x < 6 is x > −2,
 * - any other (x² − 4 > 0, (x − 1)/(x + 2) ≥ 0, √x < 2): everything to the left, the places where the sign of the
 *   left side can change (its zeros, and where a denominator is 0 or a root or logarithm begins) from the steps of the
 *   equations, then one test value in each interval between them, and the intervals where it holds.
 * The result is the same as the calculator's ([InequalitySolver]).
 */
internal object InequalitySteps {

    private const val MAX_STEPS = 150

    fun steps(inequality: Inequality, given: MathContext): ExpressionSolution? {
        // An inequality is about real numbers: also with complex numbers on, x² = −4 has no zero here
        val context = given.copy().also { it.complexNumbers = false }
        val analysis = InequalitySolver.analyze(inequality, context.copy()) ?: return null
        return linear(inequality, analysis, context) ?: signAnalysis(inequality, analysis, context)
    }

    // ----- linear: the equation's steps -----

    private fun linear(inequality: Inequality, analysis: InequalityAnalysis, context: MathContext): ExpressionSolution? {
        val v = analysis.variable
        val equation = Equals(inequality.left, inequality.right)
        val coefficients = EquationSteps.Moves(normalize(equation), emptyList(), v, context).polynomial() ?: return null
        if (coefficients.keys.maxOrNull() != 1) return null
        val solved = EquationSteps.steps(equation, v, context) ?: return null
        val last = solved.result as? Equals ?: return null

        // The relation after each step: turned around where both sides are multiplied or divided by a negative number
        val relations = mutableListOf(inequality.relation)
        val steps = mutableListOf<ExpressionStep>()
        for ((i, step) in solved.steps.withIndex()) {
            if (i == 0) {
                steps.add(convert(step, relations[0], step.rule))
                continue
            }
            val operation = solved.steps[i - 1].operation
            if (operation?.operand?.containsVariable(v) == true) return null
            val turns = when (step.rule) {
                StepRule.DIVIDE_BOTH_SIDES, StepRule.MULTIPLY_BOTH_SIDES -> operation?.operand?.let { isNegativeNumber(it) } ?: return null
                StepRule.SWAP_SIDES -> true
                else -> false
            }
            val relation = if (turns) relations.last().reversed() else relations.last()
            relations.add(relation)
            val rule = if (!turns) step.rule else when (step.rule) {
                StepRule.DIVIDE_BOTH_SIDES -> StepRule.DIVIDE_BY_NEGATIVE
                StepRule.MULTIPLY_BOTH_SIDES -> StepRule.MULTIPLY_BY_NEGATIVE
                else -> StepRule.INEQUALITY_SWAP
            }
            steps.add(convert(step, relation, rule))
        }
        val relation = relations.last()
        // The result must be what the calculator found: one half-line from the number
        val piece = analysis.pieces.singleOrNull() ?: return null
        val bound = last.right.evaluateNumerically()
        val matches = when {
            piece.lower == null && piece.upper != null -> !relation.greater && sameNumber(piece.upper.evaluateNumerically(), bound) && piece.upperClosed == !relation.strict
            piece.upper == null && piece.lower != null -> relation.greater && sameNumber(piece.lower.evaluateNumerically(), bound) && piece.lowerClosed == !relation.strict
            else -> false
        }
        if (!matches || last.left != v) return null
        return ExpressionSolution(steps, Inequality(v, last.right, relation), analysis)
    }

    private fun isNegativeNumber(e: Expression): Boolean =
        e.variables().isEmpty() && e.evaluateNumerically().let { it.isFinite() && it < 0 }

    /** [step] with its equations written as inequalities with [relation]. */
    private fun convert(step: ExpressionStep, relation: Relation, rule: StepRule?): ExpressionStep {
        val expression = step.expression.transform { if (it is Equals) Inequality(it.left, it.right, relation) else it }
        return ExpressionStep(expression, rule, step.chainRule, step.change, step.notes, step.operation)
    }

    // ----- the sign of left − right in each interval -----

    private fun signAnalysis(inequality: Inequality, analysis: InequalityAnalysis, context: MathContext): ExpressionSolution? {
        val v = analysis.variable
        val lines = mutableListOf<ExpressionStep>()

        // 1. Everything to the left
        val first = Solver(context)
        first.start(normalize(inequality))
        val rightIsZero = (inequality.right as? Value)?.isZero == true
        val hasDenominatorInSides = inequality.containsExpressionMatching { it is Power && (it.exponent as? Value)?.isNegative == true && it.base.containsVariable(v) }
        if (!rightIsZero) {
            val moved = Inequality(Additions(inequality.left, Multiplications(Value.MINUS_ONE, inequality.right)), Value.ZERO, inequality.relation)
            first.apply(
                Match(
                    StepRule.INEQUALITY_ONE_SIDE, mapOf(emptyList<Int>() to moved), inequality, moved,
                    operation = BothSides(BothSides.Kind.SUBTRACT, inequality.right), showChange = false, boxReplacements = false
                )
            )
            // With a letter in a denominator it stays as it is: (7x + 5)/(3x − 5) − 5, not three fractions to test with
            while (!hasDenominatorInSides) {
                if (context.shouldStop || first.size > MAX_STEPS) return null
                val match = SimplificationSteps.match(at(first.current, listOf(0)), Value.ZERO, context) ?: break
                first.apply(EquationSteps.prefixed(match, listOf(0)))
            }
        }
        val f = normalize(unmarked(at(first.current, listOf(0))))
        lines.addAll(first.finish(first.current).steps)

        // 2. Where it is 0
        if (analysis.zeros.isNotEmpty()) {
            // With a denominator the equation as it was typed ((7x + 5)/(3x − 5) = 5) has the better steps than the difference = 0
            val equation = if (rightIsZero || !hasDenominatorInSides) Equals(f, Value.ZERO) else Equals(inequality.left, inequality.right)
            val zeros = equationSteps(equation, v, analysis.zeros, StepRule.INEQUALITY_ZEROS, context) ?: return null
            lines.addAll(zeros)
        }
        // 3. Where it is not defined, or begins to be
        for ((part, roots) in analysis.breakSources) {
            val breaks = equationSteps(Equals(part, Value.ZERO), v, roots, StepRule.INEQUALITY_BREAKS, context) ?: return null
            lines.addAll(breaks)
        }

        // 4. The points in order, then the test value of each interval
        val critical = analysis.critical
        if (critical.isNotEmpty()) {
            val points: Expression = if (critical.size == 1) Equals(v, critical.single()) else Alternatives(critical.map { Equals(v, it) }, inline = true)
            // One point that the steps of the equation ended with already: not said twice
            if (!(critical.size == 1 && lines.isNotEmpty() && writtenForm(lines.last().expression) == writtenForm(points))) {
                lines.add(ExpressionStep(points, StepRule.INEQUALITY_CRITICAL))
            }
        }
        val result = analysis.toExpression()
        lines.add(ExpressionStep(result, StepRule.INEQUALITY_RESULT, notes = tests(analysis, f, context)))
        return ExpressionSolution(lines, result, analysis)
    }

    /**
     * The steps of [equation] (its first line takes [rule], what leads to it), or null if there are none or they do not find
     * the [expected] solutions.
     */
    private fun equationSteps(equation: Equals, v: Variable, expected: List<Expression>, rule: StepRule, context: MathContext): List<ExpressionStep>? {
        val solved = EquationSteps.steps(equation, v, context)
        if (solved == null) {
            // No steps of its own (x = 0 is solved as it is, or the rules do not cover it): the equation, then its solutions
            val values = expected.sortedBy { it.evaluateNumerically() }
            val answer: Expression = if (values.size == 1) Equals(v, values.single()) else Alternatives(values.map { Equals(v, it) }, inline = true)
            if (writtenForm(answer) == writtenForm(equation)) return listOf(ExpressionStep(equation, rule))
            return listOf(ExpressionStep(equation, rule), ExpressionStep(answer, StepRule.COLLECT_SOLUTION))
        }
        val found = solutionValues(solved.result, v) ?: return null
        if (found.size != expected.size) return null
        val sortedFound = found.map { it.evaluateNumerically() }.sorted()
        val sortedExpected = expected.map { it.evaluateNumerically() }.sorted()
        if (sortedFound.indices.any { !sameNumber(sortedFound[it], sortedExpected[it]) }) return null
        val steps = solved.steps.toMutableList()
        val first = steps.first()
        steps[0] = ExpressionStep(first.expression, rule, first.chainRule, first.change, first.notes, first.operation)
        return steps
    }

    /** The values of x = a, x = b in [result]; null if it is anything else. */
    private fun solutionValues(result: Expression, v: Variable): List<Expression>? = when (result) {
        is Equals -> if (result.left == v) listOf(result.right) else null
        is Alternatives -> result.equations.map { (it as? Equals)?.takeIf { e -> e.left == v }?.right ?: return null }
        else -> null
    }

    /** One line for each interval and point: what the left side is there, and whether that satisfies the relation. */
    private fun tests(analysis: InequalityAnalysis, f: Expression, context: MathContext): List<Note> {
        val v = analysis.variable
        val critical = analysis.critical
        val n = critical.size
        val notes = mutableListOf<Note>()
        fun valueOf(x: Double): Pair<Expression, Expression?> {
            val at = number(x)
            val substituted = f.substituteVariables(mapOf(v to at))
            val scratch = context.copy()
            val value = substituted.canonicalize(scratch)
            return substituted to if (scratch.shouldStop || scratch.issues.isNotEmpty() || !value.evaluateNumerically().isFinite()) null else value
        }
        fun verdict(difference: Double, holds: Boolean): String {
            val sign = if (abs(difference) < 1e-9) "= 0" else if (difference > 0) "> 0" else "< 0"
            return "$sign ${if (holds) "✓" else "✗"}"
        }
        fun line(x: Double, difference: Double, holds: Boolean): Expression {
            val (substituted, value) = valueOf(x)
            if (value == null) return TextExpression("not defined ✗")
            return Alternatives(listOf(Equals(substituted, value), TextExpression(verdict(difference, holds))), inline = true, separator = "  ")
        }
        for (k in 0..n) {
            val label: Expression = when {
                n == 0 -> TextExpression("${v.identifier} ∈ ℝ")
                k == 0 -> Inequality(v, critical.first(), Relation.LESS)
                k == n -> Inequality(v, critical.last(), Relation.GREATER)
                else -> Between(critical[k - 1], Relation.LESS, v, Relation.LESS, critical[k])
            }
            val right = if (analysis.sampleValues[k].isFinite()) line(analysis.samples[k], analysis.sampleValues[k], analysis.intervalHolds[k]) else TextExpression("not defined ✗")
            notes.add(Note(label, right, relation = ":"))
            if (k < n) {
                val x = critical[k].evaluateNumerically()
                val right = if (analysis.pointValues[k].isFinite()) line(x, analysis.pointValues[k], analysis.pointHolds[k]) else TextExpression("not defined ✗")
                notes.add(Note(Equals(v, critical[k]), right, relation = ":"))
            }
        }
        return notes
    }

    /** [x] as a number: a whole number or a fraction. */
    private fun number(x: Double): Expression =
        if (x == Math.rint(x)) Value(x.toLong()) else Value(Rational.approximateFromDouble(x) ?: Rational.of(Math.rint(x).toLong()))

    private fun sameNumber(a: Double, b: Double) = abs(a - b) <= 1e-9 * max(1.0, max(abs(a), abs(b)))
}
