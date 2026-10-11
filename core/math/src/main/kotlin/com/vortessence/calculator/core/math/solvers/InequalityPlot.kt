package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.EquationSystem
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Inequality
import com.vortessence.calculator.core.math.expressions.Relation
import com.vortessence.calculator.core.math.expressions.Variable

/**
 * One or several inequalities (one under the other) to shade in the graph: where they hold all together. In one variable (x² − 4 > 0) the
 * x-axis is shaded in vertical bands; in x and y (y > x², x² + y² < 1) the plane. [primary] is the boundary curve of a single inequality
 * (the function, or the implicit curve), which the tools work on; none for several.
 */
class InequalityPlot(
    val constraints: List<Constraint>,
    val primary: PlotCurve?,
    val twoVariables: Boolean,
    val horizontal: Variable,
    val angleUnit: AngleUnit
) : PlotCurve {

    /** left − right [relation] 0. */
    class Constraint(val difference: Expression, val relation: Relation)

    /** Strict inequalities do not include their boundary: it is drawn dashed. */
    val strict: Boolean get() = constraints.size == 1 && constraints.single().relation.strict

    private val evaluationContext = MathContext(mode = CalculationMode.NUMERIC, angleUnit = angleUnit)

    /** Whether every inequality holds at the point; for one variable only [x] counts. */
    fun holds(x: Double, y: Double): Boolean = MathContext.withContext(evaluationContext) {
        val bindings = if (twoVariables) mapOf(horizontal to x, Y to y) else mapOf(horizontal to x)
        constraints.all { c ->
            val d = c.difference.evaluateNumerically(bindings)
            d.isFinite() && c.relation.holds(d, 0.0)
        }
    }

    /** Whether the cells of a grid over the range hold: row by row from the top (maxY), [rows] is 1 in one variable. */
    fun shade(minX: Double, maxX: Double, minY: Double, maxY: Double, cols: Int, rows: Int): BooleanArray {
        val cells = BooleanArray(cols * rows)
        val dx = (maxX - minX) / cols
        val dy = (maxY - minY) / rows
        for (r in 0 until rows) {
            val y = maxY - (r + 0.5) * dy
            for (c in 0 until cols) cells[r * cols + c] = holds(minX + (c + 0.5) * dx, y)
        }
        return cells
    }

    companion object {
        private val X = Variable("x")
        private val Y = Variable("y")

        fun fromExpression(expr: Expression, context: MathContext = MathContext()): InequalityPlot? {
            val inequalities: List<Inequality> = when {
                expr is Inequality -> listOf(expr)
                expr is EquationSystem && expr.lines.isNotEmpty() && expr.lines.all { it is Inequality } -> expr.lines.map { it as Inequality }
                else -> return null
            }
            val variables = inequalities.flatMap { it.variables() }.toSet()
            val two = when {
                variables.size == 1 -> false
                variables == setOf(X, Y) -> true
                else -> return null
            }
            val horizontal = if (two) X else variables.single()
            val constraints = inequalities.map {
                Constraint(PlotCurve.canonicalForPlotting(Additions(it.left, it.right.negate()), context), it.relation)
            }
            val primary: PlotCurve? = inequalities.singleOrNull()?.let { inequality ->
                val difference = constraints.single().difference
                when {
                    !two -> Function1D(difference, horizontal, context.angleUnit)
                    else -> {
                        fun ofX(side: Expression) = side.containsVariable(X) && side.variables().all { it == X }
                        val g = when {
                            inequality.left == Y && ofX(inequality.right) -> inequality.right
                            inequality.right == Y && ofX(inequality.left) -> inequality.left
                            else -> null
                        }
                        if (g != null) Function1D(PlotCurve.canonicalForPlotting(g, context), X, context.angleUnit)
                        else ImplicitCurve(difference, context.angleUnit)
                    }
                }
            }
            return InequalityPlot(constraints, primary, two, horizontal, context.angleUnit)
        }
    }
}
