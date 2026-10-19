package com.vortessence.arithmeticus.core.math.solvers

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.MatrixExpression

/**
 * Something the graph can draw: a function y = f(x), an implicit curve F(x, y) = 0 or a
 * surface z = f(x, y).
 */
sealed interface PlotCurve {
    companion object {
        /**
         * Builds a line if the expression is a vector with a parameter, (1, 2) + r·(2, 1); otherwise a function if possible, then a
         * surface or an implicit curve; null if none fits.
         */
        fun fromExpression(expr: Expression, context: MathContext = MathContext()): PlotCurve? {
            // A vector that is no line in the plane (a point, a line in space) is nothing to draw: not a function of its parameter.
            // (An expression that only contains a matrix, such as det([x 1; 1 2]), is a function like any other.)
            if (expr.containsExpressionMatching { it is MatrixExpression }) {
                VectorLine.fromExpression(expr, context)?.let { return it }
                if (VectorLine.isMatrixValued(expr, context)) return null
            }
            return InequalityPlot.fromExpression(expr, context)
                ?: Function1D.fromExpression(expr, context)
                ?: Surface3D.fromExpression(expr, context)
                ?: ImplicitCurve.fromExpression(expr, context)
        }

        /**
         * Simplifies [body] with [context] so that the user's angle unit and calculation mode
         * apply. If simplification fails (e.g. a domain error for one constant part), the raw
         * expression is kept: numeric evaluation just yields gaps where it is undefined.
         */
        internal fun canonicalForPlotting(body: Expression, context: MathContext): Expression {
            val scratch = context.copy()
            val canonical = MathContext.withContext(scratch) { body.canonicalize(scratch) }
            return if (scratch.shouldStop) body else canonical
        }
    }
}
