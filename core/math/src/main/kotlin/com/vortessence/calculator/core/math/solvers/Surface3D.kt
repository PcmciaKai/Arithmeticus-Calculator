package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Variable

/**
 * The surface z = f(x, y) of an expression in x and y (e.g. sin(x)·cos(y)) or of an equation
 * z = f(x, y), drawn in 3D. [expression] is f.
 */
class Surface3D(
    val expression: Expression,
    val angleUnit: AngleUnit = AngleUnit.RADIANS
) : PlotCurve {
    // Only read during evaluation, so one shared instance is safe to use from several threads
    private val evaluationContext = MathContext(mode = CalculationMode.NUMERIC, angleUnit = angleUnit)

    fun evaluateAt(x: Double, y: Double): Double =
        MathContext.withContext(evaluationContext) { expression.evaluateNumerically(mapOf(X to x, Y to y)) }

    companion object {
        val X = Variable("x")
        val Y = Variable("y")
        val Z = Variable("z")

        /**
         * An expression in both x and y, or z = f(x, y) (either side); null for everything else.
         * Equations in x and y stay implicit curves (see [ImplicitCurve]).
         */
        fun fromExpression(expr: Expression, context: MathContext = MathContext()): Surface3D? {
            fun isFunctionOfXY(e: Expression) = e.variables().all { it == X || it == Y }
            val body = when {
                expr is Equals && expr.left == Z && isFunctionOfXY(expr.right) -> expr.right
                expr is Equals && expr.right == Z && isFunctionOfXY(expr.left) -> expr.left
                expr !is Equals && expr.variables() == setOf(X, Y) -> expr
                else -> return null
            }
            return Surface3D(PlotCurve.canonicalForPlotting(body, context), context.angleUnit)
        }
    }
}
