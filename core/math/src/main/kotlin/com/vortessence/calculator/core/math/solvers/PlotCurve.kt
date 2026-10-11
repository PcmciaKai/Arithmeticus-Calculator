package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Expression

/**
 * Something the graph can draw: a function y = f(x), an implicit curve F(x, y) = 0 or a
 * surface z = f(x, y).
 */
sealed interface PlotCurve {
    companion object {
        /** Builds a function if possible, otherwise a surface or an implicit curve; null if none fits. */
        fun fromExpression(expr: Expression, context: MathContext = MathContext()): PlotCurve? =
            InequalityPlot.fromExpression(expr, context)
                ?: Function1D.fromExpression(expr, context)
                ?: Surface3D.fromExpression(expr, context)
                ?: ImplicitCurve.fromExpression(expr, context)

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
