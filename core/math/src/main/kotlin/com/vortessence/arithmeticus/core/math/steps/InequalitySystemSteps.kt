package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.EquationSystem
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Inequality
import com.vortessence.arithmeticus.core.math.expressions.InequalityAnalysis
import com.vortessence.arithmeticus.core.math.expressions.InequalitySystemSolver

/**
 * The steps of several inequalities that must all hold: each is solved on its own (its steps, [InequalitySteps]), then the solutions
 * are put together: what is in all of them is left. The number line shows the result.
 */
internal object InequalitySystemSteps {

    fun steps(system: EquationSystem, given: MathContext): ExpressionSolution? {
        val context = given.copy().also { it.complexNumbers = false }
        val inequalities = system.lines.map { it as? Inequality ?: return null }
        // (a line without the variable has no steps of its own)
        if (inequalities.any { it.variables().isEmpty() }) return null
        val solved = InequalitySystemSolver.solve(inequalities, context.copy()) ?: return null
        if (solved.pieces.isEmpty()) return null

        val lines = mutableListOf(ExpressionStep(normalize(system), null))
        val results = mutableListOf<Expression>()
        for ((i, inequality) in inequalities.withIndex()) {
            val own = solved.parts[i].toExpression()
            // x > 1 is its own solution
            if (writtenForm(inequality) == writtenForm(own)) {
                results.add(inequality)
                continue
            }
            val nested = InequalitySteps.steps(inequality, context)
            if (nested != null) {
                val first = nested.steps.first()
                lines.add(ExpressionStep(first.expression, StepRule.INEQUALITY_SYSTEM_EACH, first.chainRule, first.change, first.notes, first.operation))
                lines.addAll(nested.steps.drop(1))
                results.add(nested.result)
            } else {
                lines.add(ExpressionStep(inequality, StepRule.INEQUALITY_SYSTEM_EACH))
                lines.add(ExpressionStep(own, StepRule.INEQUALITY_RESULT))
                results.add(own)
            }
        }
        val result = solved.toExpression()
        // The solutions one under the other, then what they have in common
        val together = EquationSystem(results)
        val separate = writtenForm(together) != writtenForm(lines.last().expression)
        if (separate) lines.add(ExpressionStep(together, StepRule.COLLECT_SOLUTION))
        lines.add(ExpressionStep(result, StepRule.INEQUALITY_SYSTEM_INTERSECT))
        val critical = solved.critical
        val analysis = InequalityAnalysis(
            solved.variable, inequalities.first(), critical, emptyList(), emptyList(), critical,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), solved.pieces
        )
        return ExpressionSolution(lines, result, analysis)
    }
}
