package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Variable
import kotlin.math.abs
import kotlin.math.max

class Function1D(
    val expression: Expression,
    val variable: Variable = Variable("x"),
    val angleUnit: AngleUnit = AngleUnit.RADIANS
) : PlotCurve {
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

    fun secondDerivativeAt(x: Double, h: Double = 1e-4 * max(1.0, abs(x))): Double {
        return (evaluateAt(x + h) - 2.0 * evaluateAt(x) + evaluateAt(x - h)) / (h * h)
    }

    companion object {
        /**
         * Builds a plottable function from [expr], simplifying it with [context] so that the
         * user's angle unit and calculation mode apply. Returns null if there is no single
         * free variable to plot against, e.g. for equations like x² + y² = 1 (see
         * [ImplicitCurve]).
         */
        fun fromExpression(expr: Expression, context: MathContext = MathContext()): Function1D? {
            val vars = expr.variables()
            val xVar = Variable("x")
            val yVar = Variable("y")
            fun isFunctionOfX(side: Expression) = side.containsVariable(xVar) && side.variables().all { it == xVar }
            val (body, variable) = when {
                expr is Equals && expr.left == yVar && isFunctionOfX(expr.right) -> expr.right to xVar
                expr is Equals && expr.right == yVar && isFunctionOfX(expr.left) -> expr.left to xVar
                expr is Equals -> {
                    val nonYVars = vars.filter { it != yVar }
                    if (nonYVars.size != 1 || vars.contains(yVar)) return null
                    Additions(expr.left, expr.right.negate()) to nonYVars.first()
                }
                vars.isEmpty() -> expr to xVar
                vars.size == 1 -> expr to vars.first()
                else -> return null
            }
            return Function1D(PlotCurve.canonicalForPlotting(body, context), variable, context.angleUnit)
        }
    }
}
