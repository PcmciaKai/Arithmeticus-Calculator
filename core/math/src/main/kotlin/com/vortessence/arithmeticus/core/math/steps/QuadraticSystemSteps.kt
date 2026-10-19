package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.EquationSystem
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.PartialFractions
import com.vortessence.arithmeticus.core.math.expressions.Power
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.expressions.substituteVariables
import com.vortessence.arithmeticus.core.math.solvers.NonlinearSystemSolver
import com.vortessence.arithmeticus.core.math.solvers.Quad
import com.vortessence.arithmeticus.core.math.solvers.QuadraticSystems
import kotlin.math.abs
import kotlin.math.max

/**
 * The steps of the systems [QuadraticSystems] solves:
 * - only x² and y²: u = x² and v = y² give a linear system (its own steps), then x = ±√u and y = ±√v,
 * - only terms of degree 2 and numbers: the constants are eliminated (multiplied, then subtracted), which leaves P·x² + Q·xy + R·y² = 0;
 *   with t = y/x that is P + Q·t + R·t² = 0. For each t, y = t·x goes into an equation of the system, and that is solved for x.
 * The solutions must be those of the calculator.
 */
internal object QuadraticSystemSteps {

    private const val MAX_STEPS = 160

    fun steps(equations: List<Equals>, variables: List<Variable>, context: MathContext): ExpressionSolution? {
        if (equations.size != 2 || variables.size != 2) return null
        val (vx, vy) = variables
        val q1 = Quad.of(equations[0], vx, vy, context) ?: return null
        val q2 = Quad.of(equations[1], vx, vy, context) ?: return null
        val expected = QuadraticSystems.solve(equations, variables, context.copy())?.takeIf { it.isNotEmpty() } ?: return null
        val squares = QuadraticSystems.squares(q1, q2)
        return if (squares != null) squaresSteps(equations, variables, squares, expected, context)
        else ratioSteps(equations, variables, q1, q2, expected, context)
    }

    // ----- x² and y² only -----

    private fun squaresSteps(
        equations: List<Equals>, variables: List<Variable>, squares: QuadraticSystems.Squares,
        expected: List<Map<Variable, Expression>>, context: MathContext
    ): ExpressionSolution? {
        val (vx, vy) = variables
        val u = Variable("u")
        val v = Variable("v")
        val lines = mutableListOf(ExpressionStep(normalize(EquationSystem(equations)), null))
        // u = x², v = y²: a linear system
        val linearEquations = equations.map { equation ->
            val q = Quad.of(equation, vx, vy, context) ?: return null
            fun term(coefficient: com.vortessence.arithmeticus.core.math.numbers.Rational, variable: Variable): Expression? =
                if (coefficient.isZero) null else Multiplications.of(Value(coefficient), variable)
            val left = listOfNotNull(term(q.a, u), term(q.c, v))
            Equals(if (left.size == 1) left.single() else Additions(left), Value(-q.f))
        }
        val linear = EquationSystem(linearEquations)
        val notes = listOf(Note(u, Power(vx, Value.TWO)), Note(v, Power(vy, Value.TWO)))
        lines.add(ExpressionStep(linear, StepRule.SUBSTITUTE_POWER, notes = notes))
        val solved = SystemSteps.steps(linear, context) ?: return null
        lines.addAll(solved.steps.drop(1))
        // x² = 16, y² = 9
        val back = EquationSystem(listOf(Equals(Power(vx, Value.TWO), Value(squares.u)), Equals(Power(vy, Value.TWO), Value(squares.v))))
        lines.add(ExpressionStep(back, StepRule.BACK_SUBSTITUTE_POWER))
        val roots = listOf(vx to Value(squares.u), vy to Value(squares.v)).map { (variable, value) ->
            solveSquare(variable, value, lines, context) ?: return null
        }
        return finish(lines, variables, roots[0], roots[1], expected, context) { x, y -> x to y }
    }

    /** x² = 16 solved for x: its steps added to [lines]; the values. */
    private fun solveSquare(variable: Variable, value: Value, lines: MutableList<ExpressionStep>, context: MathContext): List<Expression>? {
        val equation = Equals(Power(variable, Value.TWO), value)
        val solved = EquationSteps.steps(equation, variable, context)
        if (solved != null) {
            val roots = solutionValues(solved.result, variable) ?: return null
            val first = solved.steps.first()
            lines.add(ExpressionStep(first.expression, StepRule.SOLVE_FOR, first.chainRule, first.change, first.notes, first.operation))
            lines.addAll(solved.steps.drop(1))
            return roots
        }
        // x² = 0 has no steps of its own
        val roots = QuadraticSystems.roots(value.rational ?: return null, context)
        if (roots.isEmpty()) return null
        lines.add(ExpressionStep(equation, StepRule.SOLVE_FOR))
        lines.add(ExpressionStep(if (roots.size == 1) Equals(variable, roots.single()) else Alternatives(roots.map { Equals(variable, it) }, inline = true), StepRule.COLLECT_SOLUTION))
        return roots
    }

    // ----- the terms of degree 2 and numbers -----

    private fun ratioSteps(
        equations: List<Equals>, variables: List<Variable>, q1: Quad, q2: Quad,
        expected: List<Map<Variable, Expression>>, context: MathContext
    ): ExpressionSolution? {
        val (vx, vy) = variables
        val ratio = QuadraticSystems.ratio(q1, q2) ?: return null
        // A solution with x = 0 is not one of a ratio: those systems have no steps (the result is calculated)
        if (!q1.c.isZero && !(-q1.f / q1.c).isNegative && expected.any { it.getValue(vx).isZero }) return null
        if (ratio.ts.isEmpty()) return null
        val root: Path = emptyList()
        val first = Solver(context)
        first.start(normalize(EquationSystem(equations)))
        // The first equation times the constant of the second, the second times the constant of the first: the constants are the same
        val d1 = -q1.f
        val d2 = -q2.f
        val factors = listOf(d2, d1)
        val scaled = equations.mapIndexed { i, equation ->
            if (factors[i] == com.vortessence.arithmeticus.core.math.numbers.Rational.ONE) equation else {
                val by = Marked(Value(factors[i]), PENDING_COLOR, boxed = true)
                Equals(Multiplications(by, equation.left), Multiplications(by, equation.right))
            }
        }
        if (factors.any { it != com.vortessence.arithmeticus.core.math.numbers.Rational.ONE }) {
            first.apply(Match(StepRule.MULTIPLY_EQUATIONS, mapOf(root to EquationSystem(scaled)), first.current, EquationSystem(scaled), showChange = false, boxReplacements = false))
            for (j in 0..1) if (!simplifyLine(first, listOf(j), context)) return null
        }
        // Subtracted: the constants cancel
        val lines0 = (unmarked(first.current) as? EquationSystem)?.lines?.map { normalize(it) as? Equals ?: return null } ?: equations
        fun term(e: Expression) = Marked(Multiplications(Value.MINUS_ONE, e), PENDING_COLOR, boxed = true)
        val combined = Equals(Additions(lines0[0].left, term(lines0[1].left)), Additions(lines0[0].right, term(lines0[1].right)))
        first.apply(
            Match(StepRule.SUBTRACT_EQUATIONS, mapOf(root to combined), first.current, unmarked(combined), showChange = false, boxReplacements = false)
        )
        while (true) {
            if (context.shouldStop || first.size > MAX_STEPS) return null
            val match = SimplificationSteps.match(at(first.current, root), Value.ZERO, context) ?: break
            first.apply(match)
        }
        val lines = first.finish(first.current).steps.toMutableList()

        // P + Q·t + R·t² = 0 with t = y/x (x = 0 is no solution here)
        val t = Variable("t")
        val polynomial = PartialFractions.toExpression(ratio.coefficients, t)
        val tEquation = Equals(polynomial, Value.ZERO)
        val notes = listOf(Note(t, Multiplications.of(vy, Power(vx, Value.MINUS_ONE))), Note(vx, Value.ZERO, relation = "≠"))
        lines.add(ExpressionStep(tEquation, StepRule.SUBSTITUTE_RATIO, notes = notes))
        val solvedT = EquationSteps.steps(tEquation, t, context)
        val ts: List<Expression>
        val tLine: Expression
        if (solvedT != null) {
            ts = solutionValues(solvedT.result, t) ?: return null
            lines.addAll(solvedT.steps.drop(1))
            tLine = solvedT.result
        } else {
            ts = ratio.ts.map { Value(it) }
            tLine = if (ts.size == 1) Equals(t, ts.single()) else Alternatives(ts.map { Equals(t, it) }, inline = true)
            lines.add(ExpressionStep(tLine, StepRule.COLLECT_SOLUTION))
        }
        if (ts.size != ratio.ts.size) return null

        // For each t: y = t·x written into an equation of the system, solved for x, then y = t·x
        val source = if (!q1.f.isZero) equations[0] else equations[1]
        val pairs = mutableListOf<Pair<Expression, Expression>>()
        for (tValue in ts) {
            val inserted = source.transform { if (it == vy) Multiplications(tValue, vx) else it } as Equals
            lines.add(ExpressionStep(inserted, StepRule.INSERT_VALUE, notes = listOf(Note(t, tValue))))
            val simplifier = Solver(context)
            simplifier.start(normalize(inserted))
            while (true) {
                if (context.shouldStop || simplifier.size > MAX_STEPS) return null
                simplifier.apply(SimplificationSteps.match(simplifier.current, Value.ZERO, context) ?: break)
            }
            lines.addAll(simplifier.finish(simplifier.current).steps.drop(1))
            val equation = normalize(unmarked(simplifier.current)) as? Equals ?: return null
            val solved = EquationSteps.steps(equation, vx, context)
            val xs: List<Expression>
            if (solved != null) {
                // 0 = 10 for this t: no x, so no solution with it
                xs = if (solved.result is TextExpression) emptyList() else solutionValues(solved.result, vx) ?: return null
                lines.addAll(solved.steps.drop(1))
            } else {
                val scratch = context.copy()
                xs = equation.solveForVariable(vx, scratch).sortedBy { it.evaluateNumerically() }
                if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
                lines.add(ExpressionStep(if (xs.size == 1) Equals(vx, xs.single()) else Alternatives(xs.map { Equals(vx, it) }, inline = true), StepRule.COLLECT_SOLUTION))
            }
            for (x in xs) pairs.add(x to Multiplications(tValue, x))
        }
        return finishPairs(lines, variables, pairs, expected, context)
    }

    /** The values written as pairs, y = t·x calculated, and the result in the order of the calculator's. */
    private fun finishPairs(
        lines: MutableList<ExpressionStep>, variables: List<Variable>, pairs: List<Pair<Expression, Expression>>,
        expected: List<Map<Variable, Expression>>, context: MathContext, rule: StepRule = StepRule.INSERT_VALUE
    ): ExpressionSolution? {
        val (vx, vy) = variables
        val shown = pairs.map { (x, y) ->
            Alternatives(listOf(Equals(vx, x), Equals(vy, y.substituteVariables(emptyMap()))), inline = true, separator = ",   ", group = true)
        }
        // The calculated values of every pair, in the order of the calculator's solutions
        val numbers = pairs.map { (x, y) -> x.evaluateNumerically() to y.evaluateNumerically() }
        val ordered = expected.map { s ->
            val ex = s.getValue(vx).evaluateNumerically()
            val ey = s.getValue(vy).evaluateNumerically()
            val index = numbers.indexOfFirst { (x, y) -> abs(x - ex) <= 1e-9 * max(1.0, abs(ex)) && abs(y - ey) <= 1e-9 * max(1.0, abs(ey)) }
            if (index < 0) return null
            index
        }
        if (ordered.distinct().size != expected.size || pairs.size != expected.size) return null
        val last = Solver(context)
        last.start(normalize(lines.last().expression))
        val inserted: Expression = if (shown.size == 1) shown.single() else Alternatives(ordered.map { shown[it] })
        last.apply(Match(rule, mapOf(emptyList<Int>() to inserted), last.current, inserted, showChange = false, boxReplacements = false))
        val yPosition = 1
        for (i in ordered.indices) {
            val path = if (shown.size == 1) listOf(yPosition, 1) else listOf(i, yPosition, 1)
            while (true) {
                if (context.shouldStop || last.size > MAX_STEPS) return null
                last.apply(EquationSteps.prefixed(SimplificationSteps.match(at(last.current, path), Value.ZERO, context) ?: break, path))
            }
        }
        val finalPairs = expected.map { s ->
            Alternatives(listOf(Equals(vx, s.getValue(vx)), Equals(vy, s.getValue(vy))), inline = true, separator = ",   ", group = true)
        }
        val result: Expression = if (finalPairs.size == 1) finalPairs.single() else Alternatives(finalPairs)
        if (writtenForm(last.current) != writtenForm(result)) {
            last.apply(Match(StepRule.SIMPLIFY, mapOf(emptyList<Int>() to result), normalize(unmarked(last.current)), result))
        }
        lines.addAll(last.finish(result).steps.drop(1))
        return ExpressionSolution(lines, result)
    }

    /** x ∈ xs and y ∈ ys: every combination, in the calculator's order. */
    private fun finish(
        lines: MutableList<ExpressionStep>, variables: List<Variable>, xs: List<Expression>, ys: List<Expression>,
        expected: List<Map<Variable, Expression>>, context: MathContext, pair: (Expression, Expression) -> Pair<Expression, Expression>
    ): ExpressionSolution? {
        val pairs = xs.flatMap { x -> ys.map { y -> pair(x, y) } }
        return finishPairs(lines, variables, pairs, expected, context, StepRule.COLLECT_SOLUTION)
    }

    private fun simplifyLine(solution: Solver, path: Path, context: MathContext): Boolean {
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return false
            val match = SimplificationSteps.match(at(solution.current, path), Value.ZERO, context) ?: return true
            solution.apply(EquationSteps.prefixed(match, path))
        }
    }

    private fun solutionValues(result: Expression, v: Variable): List<Expression>? = when (result) {
        is Equals -> if (result.left == v) listOf(result.right) else null
        is Alternatives -> result.equations.map { (it as? Equals)?.takeIf { e -> e.left == v }?.right ?: return null }
        else -> null
    }
}
