package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.EquationSystem
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.substituteVariables
import com.vortessence.calculator.core.math.solvers.NonlinearSystemSolver
import kotlin.math.abs
import kotlin.math.max

/**
 * The steps of a system with a nonlinear equation, by substitution: one equation is solved for a variable, that is written
 * into the other equation, which is then solved like any equation of one variable ([EquationSteps]), and each solution is
 * written into the first equation for the other variable. The solutions must be those of [NonlinearSystemSolver].
 */
internal object NonlinearSystemSteps {

    private const val MAX_STEPS = 160

    fun steps(equations: List<Equals>, variables: List<Variable>, context: MathContext): ExpressionSolution? {
        val plan = NonlinearSystemSolver.plan(equations, variables, context) ?: return null
        val expected = NonlinearSystemSolver.solve(equations, variables, context.copy())?.takeIf { it.isNotEmpty() } ?: return null
        val root: Path = emptyList()
        val lines = mutableListOf<ExpressionStep>()

        // 1. One equation for one variable, written into the other
        val first = Solver(context)
        first.start(normalize(EquationSystem(equations)))
        val equation = equations[plan.line]
        val solvedAlready = EquationSteps.isSolved(equation, plan.variable)
        if (!solvedAlready) {
            first.apply(
                Match(
                    StepRule.SOLVE_FOR, mapOf(root to equation), first.current, equation,
                    showChange = false, boxReplacements = false, highlight = setOf(listOf(plan.line))
                )
            )
            if (!EquationSteps.solveEquation(first, root, plan.variable, context, MAX_STEPS)) return null
        }
        val isolated = (if (solvedAlready) equation else normalize(unmarked(first.current)) as? Equals) ?: return null
        val value = Marked(isolated.right, PENDING_COLOR, boxed = true, keep = true)
        val changedOther = equations[plan.other].transform { if (it == plan.variable) value else it } as Equals
        val system = EquationSystem(List(2) { if (it == plan.line) isolated else changedOther })
        first.apply(Match(StepRule.SUBSTITUTE_SOLUTION, mapOf(root to system), first.current, system, showChange = false, boxReplacements = false))
        val otherPath = listOf(plan.other)
        while (true) {
            if (context.shouldStop || first.size > MAX_STEPS) return null
            val match = SimplificationSteps.match(at(first.current, otherPath), Value.ZERO, context) ?: break
            first.apply(EquationSteps.prefixed(match, otherPath))
        }
        val remainingEquation = normalize(unmarked(at(first.current, otherPath))) as? Equals ?: return null
        lines.addAll(first.finish(first.current).steps)

        // 2. The equation of one variable, on its own
        val solved = EquationSteps.steps(remainingEquation, plan.remaining, context)
        val rootsLine: Expression
        val roots: List<Expression>
        if (solved != null) {
            roots = solutionValues(solved.result, plan.remaining) ?: return null
            val steps = solved.steps
            lines.add(ExpressionStep(steps.first().expression, StepRule.SOLVE_FOR, steps.first().chainRule, steps.first().change, steps.first().notes, steps.first().operation))
            lines.addAll(steps.drop(1))
            rootsLine = solved.result
        } else {
            // x = 2 on its own has no steps of its own
            val scratch = context.copy()
            val found = remainingEquation.solveForVariable(plan.remaining, scratch)
            if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
            roots = found.sortedBy { it.evaluateNumerically() }
            rootsLine = if (roots.size == 1) Equals(plan.remaining, roots.single()) else Alternatives(roots.map { Equals(plan.remaining, it) }, inline = true)
            lines.add(ExpressionStep(remainingEquation, StepRule.SOLVE_FOR))
            lines.add(ExpressionStep(rootsLine, StepRule.COLLECT_SOLUTION))
        }
        // The solutions of the calculator, and no others
        val rootNumbers = roots.map { it.evaluateNumerically() }
        val expectedRoots = expected.map { it.getValue(plan.remaining).evaluateNumerically() }
        val usable = roots.filter { r -> expectedRoots.any { abs(it - r.evaluateNumerically()) <= 1e-9 * max(1.0, abs(it)) } }
        if (usable.size != expected.size || usable.map { it.evaluateNumerically() }.distinctBy { Math.round(it * 1e9) }.size != expected.size) return null
        if (rootNumbers.isEmpty()) return null

        // 3. Each solution written into the first equation, for the other variable
        val ordered = expected.map { s -> usable.first { abs(it.evaluateNumerically() - s.getValue(plan.remaining).evaluateNumerically()) <= 1e-9 * max(1.0, abs(s.getValue(plan.remaining).evaluateNumerically())) } to s }
        val index = variables.indexOf(plan.variable)
        fun pair(r: Expression, y: Expression): Expression {
            val given = Equals(plan.remaining, r)
            val found = Equals(plan.variable, y)
            return Alternatives(if (index == 1) listOf(given, found) else listOf(found, given), inline = true, separator = ",   ", group = true)
        }
        val shown = ordered.map { (r, _) ->
            pair(r, isolated.right.substituteVariables(mapOf(plan.remaining to Marked(r, PENDING_COLOR, boxed = true, keep = true))))
        }
        val last = Solver(context)
        last.start(normalize(rootsLine))
        val inserted: Expression = if (shown.size == 1) shown.single() else Alternatives(shown)
        last.apply(Match(StepRule.INSERT_VALUE, mapOf(root to inserted), last.current, inserted, showChange = false, boxReplacements = false))
        // Each value of the other variable is calculated
        val valuePosition = if (index == 1) 1 else 0
        for (i in ordered.indices) {
            val path = if (shown.size == 1) listOf(valuePosition, 1) else listOf(i, valuePosition, 1)
            while (true) {
                if (context.shouldStop || last.size > MAX_STEPS) return null
                val match = SimplificationSteps.match(at(last.current, path), Value.ZERO, context) ?: break
                last.apply(EquationSteps.prefixed(match, path))
            }
        }
        val finalPairs = ordered.map { (r, s) -> pair(r, s.getValue(plan.variable)) }
        val result: Expression = if (finalPairs.size == 1) finalPairs.single() else Alternatives(finalPairs)
        if (writtenForm(last.current) != writtenForm(result)) {
            last.apply(Match(StepRule.SIMPLIFY, mapOf(root to result), normalize(unmarked(last.current)), result))
        }
        lines.addAll(last.finish(result).steps.drop(1))
        return ExpressionSolution(lines, result)
    }

    private fun solutionValues(result: Expression, v: Variable): List<Expression>? = when (result) {
        is Equals -> if (result.left == v) listOf(result.right) else null
        is Alternatives -> result.equations.map { (it as? Equals)?.takeIf { e -> e.left == v }?.right ?: return null }
        else -> null
    }
}
