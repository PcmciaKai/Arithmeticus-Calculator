package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Variable

class Function1D(
    val expression: Expression,
    val variable: Variable = Variable("x"),
    val angleUnit: AngleUnit = AngleUnit.RADIANS
) {
    // Only read during evaluation (angle unit for unresolved trig nodes), so one shared
    // instance is safe to use from several threads.
    private val evaluationContext = MathContext(mode = CalculationMode.NUMERIC, angleUnit = angleUnit)

    fun evaluateAt(x: Double): Double {
        return MathContext.withContext(evaluationContext) {
            expression.evaluateNumerically(x, variable)
        }
    }

    fun derivativeAt(x: Double, h: Double = 1e-6): Double {
        val y1 = evaluateAt(x + h)
        val y0 = evaluateAt(x - h)
        return (y1 - y0) / (2.0 * h)
    }

    companion object {
        /**
         * Builds a plottable function from [expr], simplifying it with [context] so that the
         * user's angle unit and calculation mode apply. Returns null if there is no single
         * free variable to plot against.
         */
        fun fromExpression(expr: Expression, context: MathContext = MathContext()): Function1D? {
            val vars = expr.variables()
            val xVar = Variable("x")
            val yVar = Variable("y")
            val (body, variable) = when {
                expr is Equals && expr.left == yVar && expr.right.containsVariable(xVar) -> expr.right to xVar
                expr is Equals && expr.right == yVar && expr.left.containsVariable(xVar) -> expr.left to xVar
                expr is Equals -> {
                    val nonYVars = vars.filter { it != yVar }
                    if (nonYVars.size != 1 || vars.contains(yVar)) return null
                    Additions(expr.left, expr.right.negate()) to nonYVars.first()
                }
                vars.isEmpty() -> expr to xVar
                vars.size == 1 -> expr to vars.first()
                else -> return null
            }
            val scratch = context.copy()
            val canonical = MathContext.withContext(scratch) { body.canonicalize(scratch) }
            // If simplification failed (e.g. a domain error for one constant part), plot the
            // raw expression: numeric evaluation just yields gaps where it is undefined.
            val target = if (scratch.shouldStop) body else canonical
            return Function1D(target, variable, context.angleUnit)
        }
    }
}
