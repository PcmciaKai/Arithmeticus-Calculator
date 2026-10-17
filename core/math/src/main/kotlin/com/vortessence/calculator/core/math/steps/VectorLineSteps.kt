package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.substituteVariables
import com.vortessence.calculator.core.math.solvers.LinearSystemSolver
import com.vortessence.calculator.core.math.solvers.VectorEquation

/**
 * The steps of two vectors that are equal, e.g. the lines (1, 2) + r·(2, 1) = (−1, 5) + s·(1, −1) meeting: the components are
 * equal, which gives an equation for each ([SystemSteps] solves them for r and s), then r is put into its line, which gives the
 * point where the lines meet.
 */
internal object VectorLineSteps {

    /** The name of the point where the lines meet: S for "Schnittpunkt", and the intersection. */
    const val POINT_NAME = "S"

    fun steps(e: Expression, context: MathContext): ExpressionSolution? {
        // (what is wrong with the vectors is for the calculation to say)
        val equation = VectorEquation.of(e, context.copy()) ?: return null
        val system = equation.system
        val nested = SystemSteps.steps(system, context) ?: return null
        val point = meetingPoint(equation, context)
        val parameters = (nested.result as? Alternatives)?.equations

        // 1. The vectors are equal if their components are: the equations, solved as any system
        val first = nested.steps.first()
        val lines = mutableListOf(
            ExpressionStep(normalize(e), null),
            ExpressionStep(first.expression, StepRule.COMPARE_COMPONENTS, first.chainRule, first.change, first.notes, first.operation)
        )
        lines.addAll(nested.steps.drop(1))
        // Lines that do not meet, or are the same, have no point
        if (point == null || parameters == null) return ExpressionSolution(lines, nested.result)

        // 2. The parameters put into the line, which gives the point
        val values = solution(equation, context) ?: return null
        val name = Variable(POINT_NAME)
        lines.add(ExpressionStep(Equals(name, equation.typedLeft.substituteVariables(values)), StepRule.INSERT_PARAMETER))
        lines.add(ExpressionStep(Equals(name, point), StepRule.SIMPLIFY))
        return ExpressionSolution(lines, Alternatives(parameters + Equals(name, point), inline = true))
    }

    /** The value of every parameter that makes the vectors equal; null if they never are, or not for one value of each. */
    private fun solution(equation: VectorEquation, context: MathContext): Map<Variable, Expression>? {
        val variables = equation.system.variables().toList()
        val scratch = context.copy()
        val values = LinearSystemSolver.solve(equation.equations, variables, scratch) ?: return null
        return values.takeIf { !scratch.shouldStop && scratch.issues.isEmpty() && it.keys.size == variables.size }
    }

    /**
     * The point where the two lines (vectors with parameters) meet: the left one with the parameters put in. Null if there is none,
     * or if it is not one point (the lines are the same), or if a side has no parameter (it is a point already).
     */
    fun meetingPoint(equation: VectorEquation, context: MathContext): MatrixExpression? {
        if (equation.left.variables().isEmpty() || equation.right.variables().isEmpty()) return null
        val values = solution(equation, context) ?: return null
        val point = equation.left.substituteVariables(values).canonicalize(context) as? MatrixExpression ?: return null
        return point.takeIf { !context.shouldStop && it.variables().isEmpty() }
    }
}
