package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.expressions.AbsoluteValue
import com.vortessence.calculator.core.math.expressions.Constant
import com.vortessence.calculator.core.math.expressions.TrigType
import com.vortessence.calculator.core.math.expressions.TrigonometricFunction
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Antiderivative
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Integral
import com.vortessence.calculator.core.math.expressions.Logarithm
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.UnitExpression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.containsImaginaryUnit
import com.vortessence.calculator.core.math.expressions.expandProducts
import com.vortessence.calculator.core.math.numbers.Rational
import kotlin.math.abs
import kotlin.math.max

/**
 * The steps of solving an equation for a variable, as by hand:
 * - linear equations by doing the same on both sides, 3x + 5 = 2x − 1 | − 2x, then | − 5, and
 *   finally dividing by the coefficient,
 * - fractions with the variable in the denominator by multiplying both sides by it,
 * - u² = c by taking the square root of both sides (u = √c or u = −√c),
 * - a product that is zero by setting each factor to zero, after factoring out x where there is
 *   no constant term (x² − 3x = 0 ⇔ x(x − 3) = 0),
 * - other quadratic equations ax² + bx + c = 0 with the quadratic formula,
 * each side simplified step by step in between ([SimplificationSteps]). Equations that are true
 * for every value end with "true".
 *
 * The solutions found are checked against the calculator's ([Equals.solveForVariable]):
 * equations it only solves numerically, or ones the steps solve differently, have no steps.
 */
internal object EquationSteps {

    private const val MAX_STEPS = 150

    private const val MAX_ROOT_SEARCH = 1000.0

    fun steps(equation: Equals, v: Variable, context: MathContext): ExpressionSolution? {
        if (equation.containsExpressionMatching {
                it is MatrixExpression || it is UnitExpression || it is Derivative || it is Antiderivative || it is Integral
            }
        ) return null
        val scratch = context.copy()
        val solutions = equation.solveForVariable(v, scratch).toList()
        // x² = −4 and x + 1 = x: the steps show why there is no solution (the calculator stopped with that issue)
        val noSolution = when (scratch.issues.singleOrNull()) {
            MathIssue.NO_REAL_SOLUTION -> NO_REAL_SOLUTION
            MathIssue.CONTRADICTION -> NO_SOLUTION
            else -> null
        }
        if (noSolution != null) return algebraic(equation, v, emptyList(), context, noSolution)
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || solutions.isEmpty()) return null
        return algebraic(equation, v, solutions, context)
            // What no rule solves, e.g. sin(x) = x/2, the calculator solved by approximation
            ?: NewtonSteps.steps(equation, v, solutions, context)
    }

    private fun algebraic(
        equation: Equals, v: Variable, solutions: List<Expression>, context: MathContext,
        /** For an equation without a solution: what the last line says. */
        expectedNone: String? = null
    ): ExpressionSolution? {
        val isIdentity = solutions == listOf(v)
        val target: Expression = Alternatives(solutions.map { Equals(v, it) })

        val solution = Solver(context)
        // Typed sin(x) has no angle unit yet, the sin(x) the rules make has: the same function, so both get it
        solution.start(normalize(withAngleUnits(equation, context.angleUnit)))
        // The variable standing for a power of v (or of e: u = eˣ) while an equation in it is solved
        var stand: Variable? = null
        var standFor: Expression = v
        // The whole numbers k of the general solution of a sine, cosine or tangent equation
        var period: Variable? = null
        // tan(x) written as sin(x)/cos(x)
        var tangentExpanded = false
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return null
            val e = solution.current
            // One equation after the other: a solved one (x = 2 − 1) is still simplified first
            val match = pathsOf(e) { it is Equals }.firstNotNullOfOrNull { path ->
                val current = unmarked(at(e, path)) as Equals
                val variable = stand?.takeIf { current.containsVariable(it) } ?: v
                if (isSolved(current, variable)) {
                    // Sums that are in the calculated solutions as they are are not multiplied out
                    SimplificationSteps.match(at(e, path), target, context)?.let { prefixed(it, path) }
                } else {
                    Moves(e, path, variable, context, allowSubstitution = stand == null, keepTangentExpanded = tangentExpanded).match() ?: return null
                }
            } ?: stand?.let { backSubstitute(e, it, standFor) } ?: break
            solution.apply(match)
            when (match.rule) {
                StepRule.IDENTITY -> break
                StepRule.SUBSTITUTE_POWER -> {
                    stand = match.notes.first().left as Variable
                    standFor = match.notes.first().right
                }
                StepRule.BACK_SUBSTITUTE_POWER -> stand = null
                StepRule.TANGENT_AS_FRACTION -> tangentExpanded = true
                StepRule.TRIG_GENERAL_SOLUTION -> period = match.notes.first().left as Variable
                else -> {}
            }
        }
        if (expectedNone != null) return noSolutionResult(solution, equation, v, expectedNone, context)
        if (isIdentity) {
            if (unmarked(solution.current) != TextExpression(TRUE)) return null
            return solution.finish(TextExpression(TRUE))
        }

        period?.let { return periodicResult(solution, v, it, solutions, context.angleUnit) }

        // Each branch x = value is one of the calculated solutions; it ends written as that one
        var branches = alternativesOf(unmarked(solution.current))
            .filter { it !is TextExpression }
            .map { it as? Equals ?: return null }
        if (pathsOf(solution.current) { it is Equals }.size != branches.size) return null
        // Squaring, fractions and absolute values can give values that are no solutions
        checkSolutions(solution, equation, v, branches, context)?.let { branches = it }
        val matched = branches.map { branch -> solutions.firstOrNull { sameValue(branch.right, it, v, context) } ?: return null }
        // A double root is found twice; every calculated solution must be found
        if (matched.toSet().size != solutions.size) return null
        // What the rules did not do, for each value on its own. Where the calculator only found a
        // decimal (solving numerically), the exact value of the steps stays.
        val finalValues = branches.map { normalize(unmarked(it.right)) }
        val values = finalValues.zip(matched).map { (own, calculated) -> if (calculated is Value && calculated.rational == null) own else calculated }
        for ((i, value) in values.withIndex()) {
            if (writtenForm(finalValues[i]) == writtenForm(value)) continue
            // The branches are the equations of the current expression, in order (earlier steps moved boxes around)
            val branchPath = pathsOf(solution.current) { it is Equals }[i]
            solution.apply(Match(StepRule.SIMPLIFY, mapOf((branchPath + 1) to value), finalValues[i], value))
        }
        // In the order of the calculated solutions, each once (a double root was found twice)
        val ordered = solutions.map { values[matched.indexOf(it)] }
        return solution.finish(if (ordered.size == 1) Equals(v, ordered.single()) else Alternatives(ordered.map { Equals(v, it) }, inline = true))
    }

    /**
     * The end of the steps of an equation without a solution: either a step said so (a square equal to −4), or the
     * only candidates were crossed out by the check (x = 2 with a denominator x − 2).
     */
    private fun noSolutionResult(solution: Solver, equation: Equals, v: Variable, text: String, context: MathContext): ExpressionSolution? {
        val current = unmarked(solution.current)
        if (current is TextExpression && (current.text == NO_REAL_SOLUTION || current.text == NO_SOLUTION)) return solution.finish(current)
        val branches = alternativesOf(current).filter { it !is TextExpression }.map { it as? Equals ?: return null }
        if (branches.isEmpty() || pathsOf(solution.current) { it is Equals }.size != branches.size) return null
        if (checkSolutions(solution, equation, v, branches, context)?.isEmpty() != true) return null
        val none = TextExpression(text)
        solution.apply(Match(StepRule.COLLECT_SOLUTION, mapOf(emptyList<Int>() to none), solution.current, none, showChange = false, boxReplacements = false))
        return solution.finish(none)
    }

    /**
     * Tries the candidates in the original [equation]: roots that were squared, a denominator that
     * became 0 or an absolute value against a negative number leave values that are no solutions.
     * Those are crossed out; returns the remaining branches, or null if the check
     * does not apply (letters in the equation) or there was nothing to show.
     */
    private fun checkSolutions(
        solution: Solver,
        equation: Equals,
        v: Variable,
        branches: List<Equals>,
        context: MathContext
    ): List<Equals>? {
        if (equation.variables() != setOf(v) || branches.isEmpty()) return null
        val hasRoot = equation.containsExpressionMatching { it is Power && (it.exponent as? Value)?.rational == Rational.HALF && it.base.containsVariable(v) }
        val hasAbsolute = equation.containsExpressionMatching { it is AbsoluteValue && it.operand.containsVariable(v) }
        val hasDenominator = equation.containsExpressionMatching { it is Power && (it.exponent as? Value)?.isNegative == true && it.base.containsVariable(v) }
        val hasLogarithm = equation.containsExpressionMatching { it is Logarithm && it.operand.containsVariable(v) }
        if (!hasRoot && !hasAbsolute && !hasDenominator && !hasLogarithm) return null

        class Candidate(val branch: Equals, val ok: Boolean, val note: Note)
        val candidates = branches.map { branch ->
            val value = unmarked(branch.right)
            val x = value.evaluateNumerically()
            val l = equation.left.evaluateNumerically(mapOf(v to x))
            val r = equation.right.evaluateNumerically(mapOf(v to x))
            val defined = l.isFinite() && r.isFinite()
            val ok = defined && abs(l - r) <= 1e-9 * max(1.0, max(abs(l), abs(r)))
            fun inserted(e: Expression) = e.transform { if (it == v) value else it }
            val note = when {
                ok -> Note(inserted(equation.left), inserted(equation.right), relation = "=")
                defined -> Note(inserted(equation.left), inserted(equation.right), relation = "≠")
                else -> Note(TextExpression("x = ${writtenForm(value)}".replace("x", v.identifier)), TextExpression("not defined"), relation = ":")
            }
            Candidate(branch, ok, note)
        }
        val rejected = candidates.indices.filter { !candidates[it].ok }
        // Squared roots and absolute values against an expression always show the check
        val alwaysShown = hasRoot || (hasAbsolute && (equation.left !is AbsoluteValue || equation.right.containsVariable(v)))
        if (rejected.isEmpty() && !alwaysShown) return null
        val paths = pathsOf(solution.current) { it is Equals }
        val crossedOut = rejected.associate { i ->
            paths[i] to TextExpression("${v.identifier} = ${writtenForm(candidates[i].branch.right)} is not a solution")
        }
        solution.apply(
            Match(
                StepRule.CHECK_SOLUTIONS, crossedOut, solution.current, solution.current,
                notes = candidates.map { it.note }, showChange = false
            )
        )
        val kept = candidates.filter { it.ok }.map { it.branch }
        // What is left, so that the last line is the result
        if (rejected.isNotEmpty() && kept.isNotEmpty()) {
            val remaining: Expression = if (kept.size == 1) kept.single() else Alternatives(kept, inline = true)
            solution.apply(Match(StepRule.COLLECT_SOLUTION, mapOf(emptyList<Int>() to remaining), solution.current, remaining, showChange = false, boxReplacements = false))
        }
        return kept
    }

    private const val TRUE = "true"

    private const val NO_REAL_SOLUTION = "no real solution"

    private const val NO_SOLUTION = "no solution"

    /**
     * Solves the equation at [path] of the current expression of [solution] for [v], step by
     * step. False if the steps do not get there.
     */
    fun solveEquation(solution: Solver, path: Path, v: Variable, context: MathContext, maxSteps: Int): Boolean {
        while (true) {
            if (context.shouldStop || solution.size > maxSteps) return false
            val e = solution.current
            val equation = unmarked(at(e, path)) as? Equals ?: return false
            val match = if (isSolved(equation, v)) {
                SimplificationSteps.match(at(e, path), Value.ZERO, context)?.let { prefixed(it, path) } ?: return true
            } else {
                Moves(e, path, v, context).match() ?: return false
            }
            solution.apply(match)
            if (match.rule == StepRule.IDENTITY) return false
        }
    }

    /**
     * The result of a trigonometric equation: the solutions x = a + b·k for all whole numbers k.
     * Each calculated solution (the calculator lists those near 0) must be one of them.
     */
    /**
     * Families c + 2πk that are evenly spread over the period are one family with a smaller step:
     * π/2 + 2πk and −π/2 + 2πk are π/2 + πk, and 0, π/2, π, −π/2 (each + 2πk) are (π/2)k. The families
     * that cannot be merged stay as they are. Null if none could.
     */
    private fun mergedFamilies(k: Variable, branches: List<Equals>, affine: List<Pair<Double, Double>>, unit: AngleUnit): List<Expression>? {
        val halfTurn = if (unit == AngleUnit.DEGREES) 180.0 else Math.PI
        if (affine.size < 2) return null
        val period = abs(affine.first().second)
        if (affine.any { abs(abs(it.second) - period) > 1e-9 }) return null
        // The same family found twice (cos x = 1 inside sin x = sin x·cos x) counts once
        val distinct = affine.indices.filter { i -> affine.indices.none { j -> j < i && abs(((affine[i].first - affine[j].first) % period + period) % period) .let { d -> d < 1e-9 || abs(d - period) < 1e-9 } } }
        if (distinct.size < affine.size) {
            return mergedFamilies(k, distinct.map { branches[it] }, distinct.map { affine[it] }, unit) ?: distinct.map { normalize(unmarked(branches[it].right)) }
        }
        val offsets = affine.map { (a, _) -> ((a % period) + period) % period }
        fun same(a: Double, b: Double) = abs(a - b) < 1e-9 || abs(abs(a - b) - period) < 1e-9
        val remaining = offsets.indices.toMutableList()
        // (index of the first branch, the family)
        val families = mutableListOf<Pair<Int, Expression>>()
        val scratch = MathContext()
        for (parts in affine.size downTo 2) {
            val step = period / parts
            val ratio = Rational.approximateFromDouble(step / halfTurn, 1000L)?.takeIf { abs(it.toDouble() * halfTurn - step) < 1e-9 } ?: continue
            var found = true
            while (found) {
                found = false
                for (start in remaining.sortedBy { offsets[it] }) {
                    val group = (0 until parts).map { i -> remaining.firstOrNull { same(offsets[it], offsets[start] + i * step) } ?: return@map null }
                    if (group.any { it == null } || group.toSet().size != parts) continue
                    val members = group.filterNotNull()
                    val first = members.minByOrNull { offsets[it] }!!
                    val offset = branches[first].right.transform { if (it == k) Value.ZERO else it }.canonicalize(scratch)
                    val stepTerm = if (unit == AngleUnit.DEGREES) Multiplications(Value(ratio * 180L), k) else Multiplications(Value(ratio), Constant.PI, k)
                    val family = (if (offset.isZero) stepTerm else Additions(offset, stepTerm)).canonicalize(scratch)
                    if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
                    families.add(members.min() to family)
                    remaining.removeAll(members)
                    found = true
                    break
                }
            }
        }
        if (families.isEmpty()) return null
        remaining.forEach { families.add(it to normalize(unmarked(branches[it].right))) }
        return families.sortedBy { it.first }.map { it.second }
    }

    private fun periodicResult(solution: Solver, v: Variable, k: Variable, calculated: List<Expression>, unit: AngleUnit): ExpressionSolution? {
        val branches = alternativesOf(unmarked(solution.current)).filter { it !is TextExpression }.map { it as? Equals ?: return null }
        if (branches.isEmpty()) return null
        val affine = branches.map { branch ->
            val at0 = branch.right.evaluateNumerically(mapOf(k to 0.0))
            val at1 = branch.right.evaluateNumerically(mapOf(k to 1.0))
            if (!at0.isFinite() || !at1.isFinite() || abs(at1 - at0) < 1e-12) return null
            at0 to (at1 - at0)
        }
        for (s in calculated) {
            val x = (s as? Value)?.real?.value ?: return null
            val found = affine.any { (a, b) ->
                val steps = (x - a) / b
                abs(steps - Math.rint(steps)) < 1e-6
            }
            if (!found) return null
        }
        val values = mergedFamilies(k, branches, affine, unit)
            ?: branches.map { normalize(unmarked(it.right)) }.distinctBy { writtenForm(it) }
        val equations = values.map { Equals(v, it) }
        val result = Alternatives(equations + TextExpression("${k.identifier} ∈ ℤ"), inline = true)
        // The last line, which is framed as the result, says what k is
        solution.apply(Match(StepRule.COLLECT_SOLUTION, mapOf(emptyList<Int>() to result), solution.current, result, showChange = false, boxReplacements = false))
        return solution.finish(result)
    }

    /** [e] with the angle unit written into every trigonometric function that has none. */
    private fun withAngleUnits(e: Expression, unit: AngleUnit): Expression = e.transform {
        if (it is TrigonometricFunction && it.angleUnit == null) TrigonometricFunction(it.type, withAngleUnits(it.operand, unit), unit) else it
    }

    /** The cases of [e], also those nested in other cases. */
    private fun alternativesOf(e: Expression): List<Expression> =
        if (e is Alternatives) e.equations.flatMap { alternativesOf(unmarked(it)) } else listOf(e)

    /** u = x² written back: every solved equation u = c becomes x² = c ([standFor] is x²). */
    private fun backSubstitute(e: Expression, u: Variable, standFor: Expression): Match? {
        val paths = pathsOf(e) { it is Equals && unmarked(it).let { eq -> (eq as Equals).left == u } }
        if (paths.isEmpty()) return null
        val replacements = paths.associateWith { Equals(standFor, (unmarked(at(e, it)) as Equals).right) }
        return Match(
            StepRule.BACK_SUBSTITUTE_POWER, replacements, e, e,
            notes = listOf(Note(u, standFor)), showChange = false
        )
    }

    /** The variable for a power of the equation's variable: u, or the next letter not in the equation. */
    private fun standInVariable(equation: Equals): Variable =
        listOf("u", "t", "w", "s").map { Variable(it) }.first { it !in equation.variables() }

    /** [match] found in the equation at [path], with paths from the root. */
    internal fun prefixed(match: Match, path: Path) = Match(
        match.rule,
        match.replacements.mapKeys { path + it.key },
        match.before,
        match.after,
        match.removals.mapKeys { path + it.key },
        match.chainRule,
        match.notes
    )

    /** x = value, with the value not depending on x. */
    internal fun isSolved(equation: Equals, v: Variable) = equation.left == v && !equation.right.containsVariable(v)

    /** Whether [a] and [b] are the same number (for any values of other variables). */
    internal fun sameValue(a: Expression, b: Expression, v: Variable, context: MathContext? = null): Boolean {
        // Complex values are compared as the calculator writes them
        if (a.containsImaginaryUnit() || b.containsImaginaryUnit()) {
            val scratch = context?.copy() ?: return false
            return unmarked(a).canonicalize(scratch) == b.canonicalize(scratch)
        }
        val others = (a.variables() + b.variables() - v).toList()
        var compared = 0
        // Irregular values of both signs, so that a root of an expression with letters is real at some
        for (sample in 1..10) {
            val bindings = others.withIndex().associate { (i, other) -> other to 3 * kotlin.math.sin(7.0 * sample + 3.0 * i + 1) }
            val x = unmarked(a).evaluateNumerically(bindings)
            val y = b.evaluateNumerically(bindings)
            // Where both are undefined (a root of a negative number) there is nothing to compare
            if (!x.isFinite() && !y.isFinite()) continue
            if (!x.isFinite() || !y.isFinite() || abs(x - y) > 1e-9 * max(1.0, abs(y))) return false
            compared++
        }
        return compared > 0
    }

    /** The next step for the equation at [path] in [e]. */
    internal class Moves(
        val e: Expression,
        val path: Path,
        val v: Variable,
        val context: MathContext,
        /** Whether x⁴ − 5x² + 4 may be written with u = x². */
        val allowSubstitution: Boolean = false,
        /** Whether sin(x)/cos(x) is kept: tan(x) was written that way to clear the denominator, not to be folded back. */
        val keepTangentExpanded: Boolean = false
    ) {
        val equation = unmarked(at(e, path)) as Equals
        /** Radians or degrees: a half turn is π or 180 */
        private val unit = context.angleUnit
        private val pi: Expression = if (unit == AngleUnit.DEGREES) Value(180L) else Constant.PI
        val left = equation.left
        val right = equation.right

        fun match(): Match? {
            if (!equation.containsVariable(v)) return identity()
            // (x + 1)² = 4 and (x − 2)(x + 3) = 0 as they are, before they would be multiplied out
            powerRoot(alone = true)?.let { return it }
            zeroFraction()?.let { return it }
            zeroProduct()?.let { return it }
            // ln(x(x − 1)) = ln(6) as it is, before the product would be multiplied out
            logarithmsEqual()?.let { return it }
            // Each side simplified first, e.g. 3x − 2x combined
            SimplificationSteps.match(at(e, path), Value.ZERO, context, tangents = !keepTangentExpanded)?.let { return prefixed(it, path) }
            powerRoot(alone = false)?.let { return it }
            clearDenominator()?.let { return it }
            exponential(alone = false)?.let { return it }
            logarithmSum()?.let { return it }
            logarithmic()?.let { return it }
            radical()?.let { return it }
            absoluteValue()?.let { return it }
            homogeneousTrigonometric()?.let { return it }
            amplitudeForm()?.let { return it }
            doubleAngle()?.let { return it }
            commonTrigonometricFactor()?.let { return it }
            tangentAsQuotient()?.let { return it }
            pythagorean()?.let { return it }
            trigonometric()?.let { return it }
            exponentialSubstitution()?.let { return it }
            trigonometricSubstitution()?.let { return it }
            val coefficients = polynomial() ?: return null
            return when (coefficients.keys.maxOrNull()) {
                // x + 1 = 1 + x: the variable drops out
                null, 1 -> linear()
                2 -> quadratic(coefficients)
                else -> higherDegree(coefficients)
            }
        }

        private fun canonical(e: Expression): Expression? {
            val scratch = context.copy()
            val result = e.canonicalize(scratch)
            return if (scratch.shouldStop) null else result
        }

        /** 0 = 0: the equation holds for every value (only for the whole problem). */
        private fun identity(): Match? {
            if (path.isNotEmpty()) return null
            val difference = canonical(Additions(left, Multiplications(Value.MINUS_ONE, right))) ?: return null
            // 1 = 0: the sides can never be equal
            if (!difference.isZero) {
                if (difference.variables().isNotEmpty()) return null
                val none = TextExpression(NO_SOLUTION)
                return Match(StepRule.CONTRADICTION, mapOf(path to none), equation, none, showChange = false)
            }
            val result = TextExpression(TRUE)
            return Match(StepRule.IDENTITY, mapOf(path to result), equation, result, showChange = false)
        }


        private fun terms(side: Expression): List<Expression> = if (side is Additions) side.operands else listOf(side)

        private fun sum(terms: List<Expression>): Expression = if (terms.size == 1) terms.single() else Additions(terms)

        /** Adds [term] to both sides (to take it away from a side, add its negation). */
        private fun addToBothSides(term: Expression): Match {
            // − (2x + 3) rather than + (−2x − 3)
            val subtract = term.isNegative || (term is Additions && term.operands.first().isNegative)
            val operand = if (subtract) negated(term) else term
            val added = Marked(if (subtract && operand is Additions) Multiplications(Value.MINUS_ONE, operand) else term, PENDING_COLOR, boxed = true)
            val result = Equals(Additions(left, added), Additions(right, added))
            return Match(
                if (subtract) StepRule.SUBTRACT_FROM_BOTH_SIDES else StepRule.ADD_TO_BOTH_SIDES,
                mapOf(path to result),
                equation,
                unmarked(result),
                operation = BothSides(if (subtract) BothSides.Kind.SUBTRACT else BothSides.Kind.ADD, operand),
                showChange = false,
                boxReplacements = false
            )
        }

        /** Multiplies both sides by [factor], or with [divide] divides them by it. */
        private fun multiplyBothSides(factor: Expression, divide: Boolean = false, notes: List<Note> = emptyList()): Match {
            val by = Marked(if (divide) Power(factor, Value.MINUS_ONE) else factor, PENDING_COLOR, boxed = true)
            val result = Equals(Multiplications(left, by), Multiplications(right, by))
            return Match(
                if (divide) StepRule.DIVIDE_BOTH_SIDES else StepRule.MULTIPLY_BOTH_SIDES,
                mapOf(path to result),
                equation,
                unmarked(result),
                operation = BothSides(if (divide) BothSides.Kind.DIVIDE else BothSides.Kind.MULTIPLY, factor),
                notes = notes,
                showChange = false,
                boxReplacements = false
            )
        }

        /** −[term] with the sign taken off its coefficient: 2x for −2x. */
        private fun negated(term: Expression): Expression = canonical(Multiplications(Value.MINUS_ONE, term)) ?: term

        /**
         * uⁿ = c ⇔ u = ⁿ√c, for even n also u = −ⁿ√c (u = 0 for c = 0); unless [alone], a·uⁿ = c
         * is divided by a first (once the sides are simplified, so that a is a single number).
         */
        private fun powerRoot(alone: Boolean): Match? {
            if (right.containsVariable(v)) return null
            val factors = if (left is Multiplications) left.operands else listOf(left)
            fun exponentOf(e: Expression): Int? {
                val n = ((e as? Power)?.exponent as? Value)?.rational?.takeIf { !it.isFractional } ?: return null
                return n.toDouble().toInt().takeIf { it >= 2 }
            }
            val power = factors.singleOrNull { exponentOf(it) != null && (it as Power).base.containsVariable(v) } as? Power ?: return null
            val n = exponentOf(power) ?: return null
            val others = factors.filter { it !== power }
            if (others.any { it.containsVariable(v) }) return null
            if (others.isNotEmpty()) {
                if (alone) return null
                return multiplyBothSides(Multiplications.of(*others.toTypedArray()), divide = true)
            }
            val value = canonical(right) ?: return null
            val even = n % 2 == 0
            if (even && value.isNegative && (!context.complexNumbers || n > 2)) {
                // An even power is never negative
                if (path.isEmpty() && context.complexNumbers) return null
                val none = TextExpression(NO_REAL_SOLUTION)
                return Match(StepRule.NO_REAL_SOLUTION, mapOf(path to none), equation, none, showChange = false)
            }
            val exponent = Value(1L, n.toLong())
            val result: Expression = when {
                value.isZero -> Equals(power.base, Value.ZERO)
                even -> {
                    val root = Power(right, exponent)
                    Alternatives(listOf(Equals(power.base, root), Equals(power.base, Multiplications(Value.MINUS_ONE, root))))
                }
                // ³√(−8) = −³√8
                value.isNegative -> Equals(power.base, Multiplications(Value.MINUS_ONE, Power(negated(right), exponent)))
                else -> Equals(power.base, Power(right, exponent))
            }
            return Match(
                if (n == 2) StepRule.SQUARE_ROOT_BOTH_SIDES else StepRule.NTH_ROOT_BOTH_SIDES,
                mapOf(path to result),
                equation,
                result,
                operation = BothSides(if (n == 2) BothSides.Kind.SQUARE_ROOT else BothSides.Kind.ROOT, if (n == 2) null else Value(n.toLong())),
                showChange = false
            )
        }

        /**
         * bᶠ = c ⇔ f = log_b(c), for a base b without the variable (ln for e); unless [alone],
         * a·bᶠ = c is divided by a first.
         */
        private fun exponential(alone: Boolean): Match? {
            if (right.containsVariable(v)) return null
            // 2·3ˣ + 1 = 7: the terms without the variable go to the right first
            val withVariable = terms(left).filter { it.containsVariable(v) }
            val term = withVariable.singleOrNull() ?: return null
            if (terms(left).size > 1) {
                val factorsOfTerm = if (term is Multiplications) term.operands else listOf(term)
                if (factorsOfTerm.count { it is Power && it.exponent.containsVariable(v) && !it.base.containsVariable(v) } != 1) return null
                return addToBothSides(negated(sum(terms(left).filterNot { it === term })))
            }
            val factors = if (left is Multiplications) left.operands else listOf(left)
            val power = factors.singleOrNull { it is Power && it.exponent.containsVariable(v) && !it.base.containsVariable(v) } as? Power
                ?: return null
            val others = factors.filter { it !== power }
            if (others.any { it.containsVariable(v) }) return null
            if (others.isNotEmpty()) {
                if (alone) return null
                return multiplyBothSides(Multiplications.of(*others.toTypedArray()), divide = true)
            }
            // A power of a positive number is positive
            val value = canonical(right)
            if (value is Value && value.rational != null && (value.isNegative || value.isZero)) {
                if (path.isEmpty() && context.complexNumbers) return null
                val none = TextExpression(NO_REAL_SOLUTION)
                return Match(StepRule.NO_REAL_SOLUTION, mapOf(path to none), equation, none, showChange = false)
            }
            val result = Equals(power.exponent, Logarithm(power.base, right))
            return Match(
                StepRule.LOGARITHM_BOTH_SIDES, mapOf(path to result), equation, result,
                operation = BothSides(BothSides.Kind.LOGARITHM, power.base), showChange = false
            )
        }

        /**
         * Moves what stands in the way of the term [isTarget] picks, to have it alone on the left:
         * the equation swapped if it is on the right, other terms to the right, a coefficient
         * divided out. Null if it is alone already, or cannot be isolated.
         */
        private fun isolate(isTarget: (Expression) -> Boolean): Match? {
            fun has(e: Expression) = e.containsExpressionMatching(isTarget)
            val leftTerms = terms(left)
            val rightTerms = terms(right)
            if (leftTerms.none { has(it) } && rightTerms.any { has(it) }) {
                val result = Equals(right, left)
                return Match(StepRule.SWAP_SIDES, mapOf(path to result), equation, result, showChange = false)
            }
            if (rightTerms.any { has(it) }) return null
            val withTarget = leftTerms.filter { has(it) }
            if (withTarget.size != 1) return null
            if (leftTerms.size > 1) return addToBothSides(negated(sum(leftTerms.filterNot { has(it) })))
            val term = withTarget.single()
            if (isTarget(term)) return null
            val factors = (term as? Multiplications)?.operands ?: return null
            val target = factors.singleOrNull { isTarget(it) } ?: return null
            val others = factors.filter { it !== target }
            if (factors.count { has(it) } != 1 || others.any { it.containsVariable(v) }) return null
            return multiplyBothSides(Multiplications.of(*others.toTypedArray()), divide = true)
        }

        /** A match that says there is no real solution, or null for the whole problem (nothing else to show). */
        private fun noRealSolution(): Match? {
            // With complex numbers the whole problem has solutions
            if (path.isEmpty() && context.complexNumbers) return null
            val none = TextExpression(NO_REAL_SOLUTION)
            return Match(StepRule.NO_REAL_SOLUTION, mapOf(path to none), equation, none, showChange = false)
        }

        /**
         * ln(a) + ln(b) = ln(ab) and ln(a) − ln(b) = ln(a/b): two or more logarithms with the variable
         * and the same base, added or subtracted on one side.
         */
        private fun logarithmSum(): Match? {
            for ((side, index) in listOf(left to 0, right to 1)) {
                val parts = terms(side)
                class LogTerm(val log: Logarithm, val negative: Boolean)
                fun logTerm(term: Expression): LogTerm? = when {
                    term is Logarithm && term.operand.containsVariable(v) && !term.base.containsVariable(v) -> LogTerm(term, false)
                    term is Multiplications && term.operands.size == 2 && term.operands[0] == Value.MINUS_ONE ->
                        (term.operands[1] as? Logarithm)?.takeIf { it.operand.containsVariable(v) && !it.base.containsVariable(v) }?.let { LogTerm(it, true) }
                    else -> null
                }
                val logs = parts.mapNotNull { logTerm(it) }
                val group = logs.filter { it.log.base == logs.firstOrNull()?.log?.base }
                if (group.size < 2) continue
                val operand = Multiplications.of(*group.map { if (it.negative) Power(it.log.operand, Value.MINUS_ONE) else it.log.operand }.toTypedArray())
                val combined = Logarithm(group.first().log.base, operand)
                val rest = parts.filter { part -> group.none { it.log === part || (part is Multiplications && part.operands.getOrNull(1) === it.log) } }
                // A first term that is subtracted needs its sign: −ln(a) + ln(b) = ln(b/a)
                val newSide = if (rest.isEmpty()) combined else Additions(listOf(combined) + rest)
                val result = if (index == 0) Equals(newSide, right) else Equals(left, newSide)
                return Match(StepRule.LOG_COMBINE, mapOf(path to result), equation, result, showChange = false)
            }
            return null
        }

        /** ln(a) = ln(b) ⇔ a = b */
        private fun logarithmsEqual(): Match? {
            val a = left as? Logarithm ?: return null
            val b = right as? Logarithm ?: return null
            if (a.base != b.base || a.base.containsVariable(v)) return null
            if (!a.operand.containsVariable(v) && !b.operand.containsVariable(v)) return null
            val result = Equals(a.operand, b.operand)
            return Match(StepRule.LOG_EQUAL, mapOf(path to result), equation, result, showChange = false)
        }

        /** ln(f) = c ⇔ f = eᶜ, log_b(f) = c ⇔ f = bᶜ. */
        private fun logarithmic(): Match? {
            if (right.containsVariable(v)) return null
            val isLog = { e: Expression -> e is Logarithm && !e.base.containsVariable(v) && e.operand.containsVariable(v) }
            isolate(isLog)?.let { return it }
            val log = left as? Logarithm ?: return null
            if (!isLog(log)) return null
            val result = Equals(log.operand, Power(log.base, right))
            return Match(
                StepRule.EXPONENTIATE_BOTH_SIDES, mapOf(path to result), equation, result,
                operation = BothSides(BothSides.Kind.EXPONENTIATE, log.base), showChange = false
            )
        }

        /** √f = g ⇔ f = g² (and the solutions are checked afterwards, see [checkSolutions]). */
        private fun radical(): Match? {
            val isRoot = { e: Expression -> e is Power && (e.exponent as? Value)?.rational == Rational.HALF && e.base.containsVariable(v) }
            isolate(isRoot)?.let { return it }
            val root = left as? Power ?: return null
            if (!isRoot(root)) return null
            if (!right.containsVariable(v) && canonical(right)?.isNegative == true) return noRealSolution()
            val result = Equals(root.base, Power(right, Value.TWO))
            return Match(
                StepRule.SQUARE_BOTH_SIDES, mapOf(path to result), equation, result,
                operation = BothSides(BothSides.Kind.SQUARE), showChange = false
            )
        }

        /** |f| = g ⇔ f = g or f = −g. */
        private fun absoluteValue(): Match? {
            val isAbsolute = { e: Expression -> e is AbsoluteValue && e.operand.containsVariable(v) }
            // |a| = |b| ⇔ a = b or a = −b, without solving each case against the sign of the other side
            val bothSides = left as? AbsoluteValue
            val other = right as? AbsoluteValue
            if (bothSides != null && other != null && (isAbsolute(bothSides) || isAbsolute(other))) {
                val result = Alternatives(listOf(Equals(bothSides.operand, other.operand), Equals(bothSides.operand, Multiplications(Value.MINUS_ONE, other.operand))))
                return Match(StepRule.ABSOLUTE_VALUES_EQUAL, mapOf(path to result), equation, result, showChange = false)
            }
            isolate(isAbsolute)?.let { return it }
            val absolute = left as? AbsoluteValue ?: return null
            if (!isAbsolute(absolute)) return null
            val result: Expression = if (!right.containsVariable(v)) {
                val value = canonical(right) ?: return null
                when {
                    value.isNegative -> return noRealSolution()
                    value.isZero -> Equals(absolute.operand, Value.ZERO)
                    else -> Alternatives(listOf(Equals(absolute.operand, right), Equals(absolute.operand, Multiplications(Value.MINUS_ONE, right))))
                }
            } else {
                Alternatives(listOf(Equals(absolute.operand, right), Equals(absolute.operand, Multiplications(Value.MINUS_ONE, right))))
            }
            return Match(StepRule.ABSOLUTE_VALUE_CASES, mapOf(path to result), equation, result, showChange = false)
        }

        /**
         * e²ˣ − 3eˣ + 2 = 0: powers of the same base b with exponents that are multiples of the
         * variable, so u = bˣ gives a polynomial equation in u.
         */
        private fun exponentialSubstitution(): Match? {
            if (!allowSubstitution || context.complexNumbers) return null
            fun multiple(exponent: Expression): Int? = when {
                exponent == v -> 1
                exponent is Multiplications && exponent.operands.size == 2 && exponent.operands[1] == v ->
                    ((exponent.operands[0] as? Value)?.rational?.takeIf { !it.isFractional && !it.isNegative && !it.isZero })?.toDouble()?.toInt()
                else -> null
            }
            var base: Expression? = null
            val multiples = mutableListOf<Int>()
            for (term in terms(left) + terms(right)) {
                if (!term.containsVariable(v)) continue
                val factors = if (term is Multiplications) term.operands else listOf(term)
                val power = factors.singleOrNull { it is Power && it.exponent.containsVariable(v) } as? Power ?: return null
                if (power.base.containsVariable(v) || factors.any { it !== power && it.containsVariable(v) }) return null
                val k = multiple(power.exponent) ?: return null
                if (base != null && base != power.base) return null
                base = power.base
                multiples.add(k)
            }
            val b = base ?: return null
            if (multiples.distinct().size < 2) return null
            if (!right.isZero) return addToBothSides(negated(right))
            fun gcd(a: Int, c: Int): Int = if (c == 0) a else gcd(c, a % c)
            val g = multiples.reduce { a, c -> gcd(a, c) }
            val u = standInVariable(equation)
            val stand = Power(b, if (g == 1) v else Multiplications(Value(g.toLong()), v))
            val inU = left.transform { e ->
                if (e is Power && e.base == b && e.exponent.containsVariable(v)) Power(u, Value((multiple(e.exponent)!! / g).toLong())) else e
            }
            val result = Equals(canonical(inU) ?: return null, Value.ZERO)
            return Match(
                StepRule.SUBSTITUTE_POWER, mapOf(path to result), equation, result,
                notes = listOf(Note(u, stand)), showChange = false
            )
        }

        /**
         * sin(u) = c ⇔ u = asin(c) + 2kπ or u = π − asin(c) + 2kπ, cos(u) = c ⇔ u = ±acos(c) + 2kπ,
         * tan(u) = c ⇔ u = atan(c) + kπ, for whole numbers k (radians only).
         */
        private fun trigonometric(): Match? {
            if (right.containsVariable(v)) return null
            val isTrig = { e: Expression ->
                e is TrigonometricFunction && e.type in listOf(TrigType.SIN, TrigType.COS, TrigType.TAN) &&
                    e.operand.containsVariable(v) && (e.angleUnit ?: unit) == unit
            }
            isolate(isTrig)?.let { return it }
            val trig = left as? TrigonometricFunction ?: return null
            if (!isTrig(trig)) return null
            val c = canonical(right) ?: return null
            val numeric = c.evaluateNumerically()
            if (!numeric.isFinite()) return null
            if (trig.type != TrigType.TAN && abs(numeric) > 1 + 1e-12) return noRealSolution()
            val k = listOf("k", "n", "m").map { Variable(it) }.firstOrNull { it !in equation.variables() } ?: return null
            val inverseType = when (trig.type) {
                TrigType.SIN -> TrigType.ASIN
                TrigType.COS -> TrigType.ACOS
                else -> TrigType.ATAN
            }
            val inverse = TrigonometricFunction(inverseType, c, unit)
            // asin(1/2) = π/6 where the calculator knows the angle, otherwise asin(…) stays
            val exact = canonical(inverse)?.takeIf { e -> !(e is Value && e.rational == null) } ?: inverse
            val twoKPi = Multiplications(Value.TWO, k, pi)
            val kPi = Multiplications(k, pi)
            val u = trig.operand
            val cases: List<Expression> = when (trig.type) {
                TrigType.SIN -> listOf(Additions(exact, twoKPi)) +
                    (if (abs(abs(numeric) - 1) < 1e-12) emptyList() else listOf(Additions(pi, Multiplications(Value.MINUS_ONE, exact), twoKPi)))
                TrigType.COS -> listOf(Additions(exact, twoKPi)) +
                    (if (abs(abs(numeric) - 1) < 1e-12) emptyList() else listOf(Additions(Multiplications(Value.MINUS_ONE, exact), twoKPi)))
                else -> listOf(Additions(exact, kPi))
            }
            val equations = cases.map { Equals(u, it) }
            val result: Expression = if (equations.size == 1) equations.single() else Alternatives(equations)
            val notes = buildList {
                add(Note(k, TextExpression("ℤ"), relation = "∈"))
                if (writtenForm(exact) != writtenForm(inverse)) add(Note(inverse, exact))
            }
            return Match(StepRule.TRIG_GENERAL_SOLUTION, mapOf(path to result), equation, result, notes = notes, showChange = false)
        }

        /**
         * 2sin²x − sin x − 1 = 0: a polynomial in sin(x) (or cos(x), tan(x)) of degree 2 or more,
         * so u = sin(x) gives an equation in u that is solved first.
         */
        private fun trigonometricSubstitution(): Match? {
            if (!allowSubstitution || context.complexNumbers) return null
            val functions = LinkedHashSet<TrigonometricFunction>()
            fun collect(e: Expression) {
                if (e is TrigonometricFunction && e.type in listOf(TrigType.SIN, TrigType.COS, TrigType.TAN) && e.operand.containsVariable(v)) functions.add(e) else e.children.forEach { collect(it) }
            }
            collect(left)
            collect(right)
            val function = functions.singleOrNull() ?: return null
            // Everything outside the function must be a polynomial in it, with powers of 2 or more somewhere
            val u = standInVariable(equation)
            val inU = (if (right.isZero) left else Additions(left, Multiplications(Value.MINUS_ONE, right))).transform { if (it == function) u else it }
            if (inU.containsVariable(v)) return null
            val coefficients = Moves(Equals(inU, Value.ZERO).let { Marked(it, 0, false) }, emptyList(), u, context).polynomial() ?: return null
            if ((coefficients.keys.maxOrNull() ?: 0) < 2) return null
            if (!right.isZero) return addToBothSides(negated(right))
            val result = Equals(canonical(inU) ?: return null, Value.ZERO)
            return Match(
                StepRule.SUBSTITUTE_POWER, mapOf(path to result), equation, result,
                notes = listOf(Note(u, function)), showChange = false
            )
        }

        /**
         * a·sin(u) = b·cos(u): everything to the left, then divided by cos(u), which gives
         * a·tan(u) − b = 0. (cos(u) = 0 cannot be a solution: sin(u) would be 0 too.)
         */
        private fun homogeneousTrigonometric(): Match? {
            if (context.complexNumbers) return null
            var argument: Expression? = null
            var hasSine = false
            var hasCosine = false
            for (term in terms(left) + terms(right)) {
                if (term.isZero) continue
                val factors = if (term is Multiplications) term.operands else listOf(term)
                val trig = factors.singleOrNull { it is TrigonometricFunction } as? TrigonometricFunction ?: return null
                if (trig.type != TrigType.SIN && trig.type != TrigType.COS) return null
                if (factors.any { it !== trig && it.containsVariable(v) }) return null
                if (argument != null && trig.operand != argument) return null
                argument = trig.operand
                if (trig.type == TrigType.SIN) hasSine = true else hasCosine = true
            }
            if (!hasSine || !hasCosine || argument == null || !argument.containsVariable(v)) return null
            if (!right.isZero) return addToBothSides(negated(right))
            val cosine = TrigonometricFunction(TrigType.COS, argument, unit)
            return multiplyBothSides(cosine, divide = true, notes = listOf(Note(cosine, Value.ZERO, relation = "≠")))
        }

        /**
         * sin(2u) = 2sin(u)cos(u) and cos(2u) = cos²(u) − sin²(u), when the equation has sin(u) or cos(u)
         * as well. cos(2u) is written as 1 − 2sin²(u) or 2cos²(u) − 1 if only one of the two is there.
         */
        private fun doubleAngle(): Match? {
            if (context.complexNumbers) return null
            fun isTrig(e: Expression) = e is TrigonometricFunction && e.type in listOf(TrigType.SIN, TrigType.COS) && (e.angleUnit ?: unit) == unit
            fun halfOf(e: Expression): Expression? {
                val double = (e as? TrigonometricFunction)?.takeIf { isTrig(it) }?.operand as? Multiplications ?: return null
                val ops = double.operands
                if (ops.size < 2 || ops.first() != Value.TWO) return null
                val half = if (ops.size == 2) ops[1] else Multiplications(ops.drop(1))
                return half.takeIf { it.containsVariable(v) }
            }
            val doubles = pathsOf(equation) { halfOf(it) != null }
            if (doubles.isEmpty()) return null
            val half = halfOf(at(equation, doubles.first())) ?: return null
            val singles = pathsOf(equation) { isTrig(it) && (it as TrigonometricFunction).operand == half }.map { (at(equation, it) as TrigonometricFunction).type }.toSet()
            if (singles.isEmpty()) return null
            val sin = TrigonometricFunction(TrigType.SIN, half, unit)
            val cos = TrigonometricFunction(TrigType.COS, half, unit)
            val cosOfDouble: Expression = when (singles) {
                setOf(TrigType.SIN) -> Additions(Value.ONE, Multiplications(Value.MINUS_ONE, Value.TWO, Power(sin, Value.TWO)))
                setOf(TrigType.COS) -> Additions(Multiplications(Value.TWO, Power(cos, Value.TWO)), Value.MINUS_ONE)
                else -> Additions(Power(cos, Value.TWO), Multiplications(Value.MINUS_ONE, Power(sin, Value.TWO)))
            }
            val result = equation.transform {
                val inner = halfOf(it)
                when {
                    inner != half -> it
                    (it as TrigonometricFunction).type == TrigType.SIN -> Multiplications(Value.TWO, sin, cos)
                    else -> cosOfDouble
                }
            }
            return Match(StepRule.DOUBLE_ANGLE, mapOf(path to result), equation, result, showChange = false)
        }

        /** tan(u) = sin(u)/cos(u), where the equation has a sine or cosine as well. */
        private fun tangentAsQuotient(): Match? {
            if (context.complexNumbers) return null
            fun isTan(e: Expression) = e is TrigonometricFunction && e.type == TrigType.TAN && e.operand.containsVariable(v)
            if (!equation.containsExpressionMatching { isTan(it) }) return null
            if (!equation.containsExpressionMatching { it is TrigonometricFunction && (it.type == TrigType.SIN || it.type == TrigType.COS) && it.operand.containsVariable(v) }) return null
            val result = equation.transform {
                if (isTan(it)) {
                    val u = (it as TrigonometricFunction).operand
                    Multiplications(
                        TrigonometricFunction(TrigType.SIN, u, unit),
                        Power(TrigonometricFunction(TrigType.COS, u, unit), Value.MINUS_ONE)
                    )
                } else it
            }
            return Match(StepRule.TANGENT_AS_FRACTION, mapOf(path to result), equation, result, showChange = false)
        }

        /**
         * sin²(u) = 1 − cos²(u) (or the other way round), where the equation has the sine only squared and a
         * cosine of the same angle: it then is a polynomial in cos(u).
         */
        private fun pythagorean(): Match? {
            if (!allowSubstitution || context.complexNumbers) return null
            for ((squared, other) in listOf(TrigType.SIN to TrigType.COS, TrigType.COS to TrigType.SIN)) {
                fun isFunction(e: Expression, type: TrigType) = e is TrigonometricFunction && e.type == type && e.operand.containsVariable(v)
                fun isSquare(e: Expression) = e is Power && isFunction(e.base, squared) && (e.exponent as? Value)?.rational == Rational.of(2L)
                // Every occurrence of the squared function is a square
                var onlySquares = true
                var angle: Expression? = null
                fun scan(e: Expression) {
                    if (isSquare(e)) { angle = angle ?: ((e as Power).base as TrigonometricFunction).operand; return }
                    if (isFunction(e, squared)) { onlySquares = false; return }
                    e.children.forEach { scan(it) }
                }
                scan(equation)
                val u = angle ?: continue
                if (!onlySquares) continue
                if (!equation.containsExpressionMatching { isFunction(it, other) && (it as TrigonometricFunction).operand == u }) continue
                if (equation.containsExpressionMatching { it is TrigonometricFunction && it.type == TrigType.TAN }) continue
                val otherFunction = TrigonometricFunction(other, u, unit)
                val result = equation.transform {
                    if (isSquare(it) && ((it as Power).base as TrigonometricFunction).operand == u) {
                        Additions(Value.ONE, Multiplications(Value.MINUS_ONE, Power(otherFunction, Value.TWO)))
                    } else it
                }
                return Match(StepRule.PYTHAGOREAN, mapOf(path to result), equation, result, showChange = false)
            }
            return null
        }

        /**
         * sin(x)cos(x) = cos(x): everything to the left, then the factor the terms share, cos(x), taken out
         * so that each factor can be set to 0.
         */
        private fun commonTrigonometricFactor(): Match? {
            if (context.complexNumbers) return null
            val all = terms(left) + terms(right).filterNot { it.isZero }
            if (terms(right).count { !it.isZero } + terms(left).size < 2) return null
            fun factorsOf(term: Expression) = if (term is Multiplications) term.operands else listOf(term)
            val common = factorsOf(all.first()).filter {
                it is TrigonometricFunction && it.type in listOf(TrigType.SIN, TrigType.COS) && it.operand.containsVariable(v)
            }.firstOrNull { f -> all.all { t -> factorsOf(t).count { it == f } == 1 } } ?: return null
            // Only where something else is left besides the factor, in each term but at most one
            if (all.count { factorsOf(it).size == 1 } > 1) return null
            if (!right.isZero) return addToBothSides(negated(right))
            val inner = terms(left).map { canonical(Multiplications(it, Power(common, Value.MINUS_ONE))) ?: return null }
            val factored = Multiplications(common, Additions(inner))
            return Match(StepRule.FACTOR_COMMON, mapOf(path + 0 to factored), left, factored)
        }

        /**
         * a·sin(u) + b·cos(u) = c with numbers a, b and c ≠ 0: the sine and cosine terms to the left,
         * the numbers to the right, then a·sin(u) + b·cos(u) = R·sin(u + φ) with R = √(a² + b²) and
         * φ = atan(b/a) (plus π for a < 0). That is a sine equation, sin(u + φ) = c/R.
         */
        private fun amplitudeForm(): Match? {
            if (context.complexNumbers) return null
            var argument: Expression? = null
            val sines = mutableListOf<Expression>()
            val cosines = mutableListOf<Expression>()
            var hasConstant = false
            fun trigTerm(term: Expression): Boolean {
                val factors = if (term is Multiplications) term.operands else listOf(term)
                val trig = factors.singleOrNull { it is TrigonometricFunction } as? TrigonometricFunction ?: return false
                if (trig.type != TrigType.SIN && trig.type != TrigType.COS) return false
                val coefficient = factors.filter { it !== trig }.fold(Value.ONE as Expression) { p, f -> Multiplications.of(p, f) }
                // Numbers, also with roots: √3·sin x
                if (coefficient.variables().isNotEmpty() || !coefficient.evaluateNumerically().isFinite()) return false
                if (argument != null && trig.operand != argument) return false
                argument = trig.operand
                (if (trig.type == TrigType.SIN) sines else cosines).add(coefficient)
                return true
            }
            for (term in terms(left) + terms(right)) {
                when {
                    term.isZero -> {}
                    !term.containsVariable(v) -> hasConstant = true
                    !trigTerm(term) -> return null
                }
            }
            if (sines.isEmpty() || cosines.isEmpty()) return null
            val a = canonical(sum(sines)) ?: return null
            val b = canonical(sum(cosines)) ?: return null
            val u = argument ?: return null
            if (!hasConstant || a.isZero || b.isZero || !u.containsVariable(v)) return null
            // The sine and cosine terms to the left, the numbers to the right
            val rightTrig = terms(right).filter { it.containsVariable(v) }
            if (rightTrig.isNotEmpty()) return addToBothSides(negated(sum(rightTrig)))
            val leftConstants = terms(left).filterNot { it.containsVariable(v) && !it.isZero }.filter { !it.isZero }
            if (leftConstants.isNotEmpty()) return addToBothSides(negated(sum(leftConstants)))
            // One sine and one cosine term: simplification has combined the rest
            if (terms(left).size != 2) return null
            val radius = canonical(Power(canonical(Additions(Power(a, Value.TWO), Power(b, Value.TWO))) ?: return null, Value.HALF)) ?: return null
            val ratio = canonical(Multiplications(b, Power(a, Value.MINUS_ONE))) ?: return null
            val atan = TrigonometricFunction(TrigType.ATAN, ratio, unit)
            val exactAngle = canonical(atan)?.takeIf { e -> !(e is Value && e.rational == null) } ?: atan
            val phase: Expression = if (a.evaluateNumerically() < 0) Additions(exactAngle, pi) else exactAngle
            val rewritten = Multiplications(radius, TrigonometricFunction(TrigType.SIN, Additions(u, phase), unit))
            val result = Equals(rewritten, right)
            return Match(
                StepRule.AMPLITUDE_FORM, mapOf(path to result), equation, result,
                notes = listOf(Note(Variable("a"), a), Note(Variable("b"), b), Note(Variable("R"), radius), Note(Variable("φ"), phase)),
                showChange = false
            )
        }

        /** a/b = 0 ⇔ a = 0, where b ≠ 0 (the solutions are checked afterwards). */
        private fun zeroFraction(): Match? {
            if (!right.isZero) return null
            val factors = (left as? Multiplications)?.operands ?: return null
            // (sin x − cos x)/cos x = 0 comes from dividing by cos x: that is simplified, not undone
            fun isDenominator(e: Expression) = e is Power && (e.exponent as? Value)?.isNegative == true && e.base.containsVariable(v) && e.base !is TrigonometricFunction
            val denominators = factors.filter { isDenominator(it) }
            if (denominators.isEmpty()) return null
            val numerator = Multiplications.of(*factors.filterNot { isDenominator(it) }.toTypedArray())
            if (!numerator.containsVariable(v)) return null
            val result = Equals(numerator, Value.ZERO)
            return Match(
                StepRule.ZERO_FRACTION, mapOf(path to result), equation, result,
                notes = denominators.map { Note((it as Power).base, Value.ZERO, relation = "≠") }, showChange = false
            )
        }

        /** a·b = 0 ⇔ a = 0 or b = 0, for the factors that contain the variable. */
        private fun zeroProduct(): Match? {
            if (!right.isZero || left !is Multiplications) return null
            // A denominator is never 0 itself: a/b = 0 is a = 0, with b ≠ 0 checked afterwards
            val factors = left.operands.filter { it.containsVariable(v) && !((it as? Power)?.exponent as? Value)?.isNegative.let { negative -> negative == true } }
            if (factors.size < 2) return null
            val result = Alternatives(factors.map { Equals(it, Value.ZERO) })
            return Match(StepRule.ZERO_PRODUCT, mapOf(path to result), equation, result, showChange = false)
        }

        /** 1/x + … = …: both sides multiplied by the denominator that contains the variable. */
        private fun clearDenominator(): Match? {
            for (term in terms(left) + terms(right)) {
                val factors = if (term is Multiplications) term.operands else listOf(term)
                for (factor in factors) {
                    val exponent = (factor as? Power)?.exponent as? Value ?: continue
                    if (!exponent.isNegative || !factor.base.containsVariable(v)) continue
                    val positive = exponent.negate()
                    // The denominator must not be 0
                    return multiplyBothSides(
                        if (positive == Value.ONE) factor.base else Power(factor.base, positive),
                        notes = listOf(Note(factor.base, Value.ZERO, relation = "≠"))
                    )
                }
            }
            return null
        }

        /** The coefficients of the equation moved to one side, by power of the variable. */
        fun polynomial(): Map<Int, Expression>? {
            val difference = canonical(expandProducts(Additions(left, Multiplications(Value.MINUS_ONE, right)))) ?: return null
            val result = mutableMapOf<Int, Expression>()
            for (term in terms(difference)) {
                val factors = if (term is Multiplications) term.operands else listOf(term)
                var degree = 0
                val coefficient = mutableListOf<Expression>()
                for (factor in factors) {
                    when {
                        factor == v -> degree += 1
                        factor is Power && factor.base == v -> {
                            val n = (factor.exponent as? Value)?.rational ?: return null
                            if (n.isFractional || n.isNegative) return null
                            degree += n.numerator.value.toInt()
                        }
                        factor.containsVariable(v) -> return null
                        else -> coefficient.add(factor)
                    }
                }
                val c = Multiplications.of(*coefficient.toTypedArray())
                result[degree] = result[degree]?.let { Additions(it, c) } ?: c
            }
            return result.mapValues { canonical(it.value) ?: return null }.filterValues { !it.isZero }
        }

        /** The variable to the left, the numbers to the right, then divided by the coefficient. */
        private fun linear(): Match? {
            val leftTerms = terms(left)
            val rightTerms = terms(right)
            val (leftWith, leftWithout) = leftTerms.partition { it.containsVariable(v) }
            val rightWith = rightTerms.filter { it.containsVariable(v) }
            if (leftWith.isEmpty() && rightWith.isNotEmpty()) {
                val result = Equals(right, left)
                return Match(StepRule.SWAP_SIDES, mapOf(path to result), equation, result, showChange = false)
            }
            if (rightWith.isNotEmpty()) return addToBothSides(negated(sum(rightWith)))
            if (leftWithout.isNotEmpty()) return addToBothSides(negated(sum(leftWithout)))
            val term = leftWith.singleOrNull() ?: return null
            val coefficient = canonical(Multiplications(term, Power(v, Value.MINUS_ONE))) ?: return null
            if (coefficient.containsVariable(v) || coefficient.isZero) return null
            val rational = (coefficient as? Value)?.rational
            // 2/3·x: multiplied by 3/2 rather than divided by 2/3
            if (rational != null && rational.isFractional) return multiplyBothSides(Value(rational.denominator, rational.numerator))
            return multiplyBothSides(coefficient, divide = true)
        }

        private fun quadratic(coefficients: Map<Int, Expression>): Match? {
            val leftTerms = terms(left)
            val rightTerms = terms(right)
            if (coefficients[1] == null) {
                // No linear term: x² on its own, then the square root
                val rightWith = rightTerms.filter { it.containsVariable(v) }
                if (rightWith.isNotEmpty()) return addToBothSides(negated(sum(rightWith)))
                val leftWithout = leftTerms.filterNot { it.containsVariable(v) }
                if (leftWithout.isNotEmpty()) return addToBothSides(negated(sum(leftWithout)))
                return null
            }
            // Everything to the left: ax² + bx + c = 0
            if (!right.isZero) return addToBothSides(negated(right))
            if (coefficients[0] == null) return factorOut()
            return formula(coefficients)
        }

        /**
         * Polynomials of degree 3 and up with whole number coefficients: everything to the left,
         * then a rational root r (found by trying ±p/q, p dividing the constant term and q the
         * leading coefficient) splits off the factor (x − r), see [ZERO_PRODUCT].
         */
        private fun higherDegree(coefficients: Map<Int, Expression>): Match? {
            val degree = coefficients.keys.max()
            // axⁿ + b = 0: the constant to the right, then the n-th root
            if (coefficients.keys.all { it == 0 || it == degree }) {
                terms(right).filter { it.containsVariable(v) }.takeIf { it.isNotEmpty() }?.let { return addToBothSides(negated(sum(it))) }
                terms(left).filterNot { it.containsVariable(v) }.takeIf { it.isNotEmpty() }?.let { return addToBothSides(negated(sum(it))) }
                return null
            }
            // x⁴ − 5x² + 4 = 0: only even powers, so u = x² gives a quadratic equation
            if (allowSubstitution && !context.complexNumbers && degree >= 4 && coefficients.keys.all { it % 2 == 0 }) {
                if (!right.isZero) return addToBothSides(negated(right))
                val u = standInVariable(equation)
                val quadratic = canonical(Additions(coefficients.map { (k, c) -> Multiplications(c, Power(u, Value((k / 2).toLong()))) })) ?: return null
                val result = Equals(quadratic, Value.ZERO)
                return Match(
                    StepRule.SUBSTITUTE_POWER, mapOf(path to result), equation, result,
                    notes = listOf(Note(u, Power(v, Value.TWO))), showChange = false
                )
            }
            if (!right.isZero) return addToBothSides(negated(right))
            val numbers = (0..degree).map { k ->
                val c = coefficients[k] ?: return@map Rational.ZERO
                (c as? Value)?.rational?.takeIf { !it.isFractional } ?: return null
            }
            val root = rationalRoot(numbers) ?: return null
            val p = root.numerator.toDouble().toLong()
            val q = root.denominator.toDouble().toLong()
            // Q with P = (x − r)·Q' and (qx − p) = q·(x − r): Q = Q'/q
            val quotient = ArrayList<Rational>()
            var carry = Rational.ZERO
            for (k in degree downTo 1) {
                carry = numbers[k] + carry * root
                quotient.add(carry)
            }
            val scaled = quotient.map { it / Rational.of(q) }
            if (scaled.any { it.isFractional }) return null
            val quotientExpression = canonical(
                Additions(scaled.mapIndexed { i, c -> Multiplications(Value(c), Power(v, Value((degree - 1 - i).toLong()))) })
            ) ?: return null
            val factor: Expression = when {
                p == 0L -> v
                q == 1L -> Additions(v, Value(-p))
                else -> Additions(Multiplications(Value(q), v), Value(-p))
            }
            val factored = Multiplications(factor, quotientExpression)
            // P(r) written out, highest power first: 1³ − 6·1² + 11·1 − 6
            val check = Additions((degree downTo 0).mapNotNull { k ->
                val c = numbers[k]
                when {
                    c.isZero -> null
                    k == 0 -> Value(c)
                    k == 1 -> WrittenOut(listOf(Value(c), Value(root)))
                    else -> Multiplications(Value(c), Power(Value(root), Value(k.toLong())))
                }
            })
            val shown = if (root.isNegative) "−${root.abs()}" else "$root"
            val note = Note(TextExpression("P($shown)"), Equals(check, Value.ZERO))
            return Match(StepRule.FACTOR_BY_ROOT, mapOf(path + 0 to factored), left, factored, notes = listOf(note))
        }

        /** The first rational root of the polynomial with whole number [coefficients] (constant term first). */
        private fun rationalRoot(coefficients: List<Rational>): Rational? {
            if (coefficients.first().isZero) return Rational.ZERO
            val constant = coefficients.first().numerator.abs().toDouble()
            val leading = coefficients.last().numerator.abs().toDouble()
            if (constant > MAX_ROOT_SEARCH || leading > MAX_ROOT_SEARCH) return null
            fun divisors(n: Long) = (1..n).filter { n % it == 0L }
            val candidates = divisors(constant.toLong()).flatMap { p -> divisors(leading.toLong()).map { q -> Rational.of(p, q) } }
                .distinct().sortedBy { it.toDouble() }.flatMap { listOf(it, -it) }
            return candidates.firstOrNull { r ->
                coefficients.foldIndexed(Rational.ZERO) { k, sum, c -> sum + c * r.pow(k.toLong()) }.isZero
            }
        }

        /** ax² + bx = x(ax + b) */
        private fun factorOut(): Match? {
            val inner = terms(left).map { canonical(Multiplications(it, Power(v, Value.MINUS_ONE))) ?: return null }
            if (inner.any { it.containsVariable(v) && it != v && !(it is Multiplications && it.operands.contains(v)) }) return null
            val factored = Multiplications(v, Additions(inner))
            return Match(StepRule.FACTOR_OUT, mapOf(path + 0 to factored), left, factored)
        }

        /**
         * x = (−b ± √D)/(2a) with D = b² − 4ac. For numbers a, b and c a negative D has no real
         * solution (or, with complex numbers, solutions with i); with letters in the coefficients
         * D stays as it is.
         */
        private fun formula(coefficients: Map<Int, Expression>): Match? {
            val a = coefficients[2] ?: return null
            val b = coefficients[1] ?: return null
            val c = coefficients[0] ?: return null
            val numbers = listOf(a, b, c).all { it is Value && it.rational != null }
            val discriminant = Additions(Power(b, Value.TWO), Multiplications(Value(-4L), a, c))
            val d = canonical(discriminant) ?: return null
            if (numbers && d !is Value) return null
            // b² − 4·a·c with the numbers as they are: 1² − 4·1·(−1)
            val writtenOut = Additions(Power(b, Value.TWO), Multiplications(Value.MINUS_ONE, WrittenOut(listOf(Value(4L), a, c))))
            // a = a says nothing
            val notes = listOf(Variable("a") to a, Variable("b") to b, Variable("c") to c)
                .filter { (name, value) -> value != name }
                .map { (name, value) -> Note(name, value) } +
                Note(Variable("D"), if (writtenForm(writtenOut) == writtenForm(d)) d else Equals(writtenOut, d))
            // A negative discriminant: no real solution
            if (numbers && d.isNegative && !context.complexNumbers) {
                if (path.isEmpty() && context.complexNumbers) return null
                val none = TextExpression(NO_REAL_SOLUTION)
                return Match(StepRule.NO_REAL_SOLUTION, mapOf(path to none), equation, none, notes = notes, showChange = false)
            }
            val minusB = canonical(Multiplications(Value.MINUS_ONE, b)) ?: return null
            val twoA = Multiplications(Value.TWO, a)
            val result: Expression = if (d.isZero) {
                Equals(v, Multiplications(minusB, Power(twoA, Value.MINUS_ONE)))
            } else {
                val root = Power(d, Value(1L, 2L))
                Alternatives(listOf(
                    Equals(v, Multiplications(Additions(minusB, root), Power(twoA, Value.MINUS_ONE))),
                    Equals(v, Multiplications(Additions(minusB, Multiplications(Value.MINUS_ONE, root)), Power(twoA, Value.MINUS_ONE)))
                ))
            }
            return Match(StepRule.QUADRATIC_FORMULA, mapOf(path to result), equation, result, notes = notes, showChange = false)
        }
    }
}
