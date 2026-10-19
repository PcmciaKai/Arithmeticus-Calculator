package com.vortessence.arithmeticus.core.math.solvers

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.EquationSystem
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.MatrixExpression
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.expressions.substituteVariables
import kotlin.math.abs

/**
 * An equation between two vectors, e.g. the lines g = (1, 2) + r·(2, 1) and h = (−1, 5) + s·(1, −1) meeting:
 * [left] = [right], which holds when every component is equal. Its [system] has an equation for every component, so that the
 * parameters (r and s) are found like the variables of any linear system.
 */
internal class VectorEquation private constructor(
    val left: MatrixExpression,
    val right: MatrixExpression,
    /** The left side as it was typed (with the parameters renamed like [left]), for the steps to put the parameter into. */
    val typedLeft: Expression
) {

    /** One equation for every component. */
    val equations: List<Equals> = left.elements.zip(right.elements) { a, b -> Equals(a, b) }

    val system: EquationSystem get() = EquationSystem(equations)

    companion object {
        /**
         * [expr] as an equation of two vectors of the same length (2 or more); null if it is something else. Vectors of different
         * lengths are an error of [context].
         */
        fun of(expr: Expression, context: MathContext): VectorEquation? {
            if (expr !is Equals || !expr.containsExpressionMatching { it is MatrixExpression }) return null
            val scratch = context.copy()
            val sides = MathContext.withContext(scratch) { listOf(expr.left, expr.right).map { it.canonicalize(scratch) } }
            if (scratch.shouldStop) {
                // (e.g. two vectors of different lengths added up on one side)
                scratch.issues.firstOrNull()?.let { context.stopWithError(it) }
                return null
            }
            val (left, right) = sides.map { it as? MatrixExpression ?: return null }
            if (!left.isVector || !right.isVector || left.elements.size < 2) return null
            if (left.elements.size != right.elements.size) {
                context.stopWithError(MathIssue.DIMENSION_MISMATCH)
                return null
            }
            // Two lines with the same letter for their parameter, g = p + r·u and h = q + r·v, have a parameter each: r₁ and r₂
            val shared = left.variables().singleOrNull()?.takeIf { right.variables().singleOrNull() == it } ?: return VectorEquation(left, right, expr.left)
            val first = Variable(shared.identifier + "₁")
            val second = Variable(shared.identifier + "₂")
            fun renamed(e: Expression, to: Variable) = e.substituteVariables(mapOf(shared to to))
            return VectorEquation(renamed(left, first) as MatrixExpression, renamed(right, second) as MatrixExpression, renamed(expr.left, first))
        }
    }
}

/**
 * A line in the plane given in the vector form p + t·u, e.g. (1, 2) + r·(2, 1), for the graph: drawn as the implicit curve
 * u₂·(x − p₁) − u₁·(y − p₂) = 0, so that it is crossed with the other curves like any of them.
 */
internal object VectorLine {
    private val X = Variable("x")
    private val Y = Variable("y")

    /** The vector of "g = (1, 2) + r·(2, 1)", the name only labels it; [expr] itself otherwise. */
    private fun vectorOf(expr: Expression): Expression = if (expr is Equals && expr.left is Variable) expr.right else expr

    /** Whether [expr] works out to a matrix (a vector too), which is no function of x even when it is no line. */
    fun isMatrixValued(expr: Expression, context: MathContext): Boolean {
        val scratch = context.copy()
        val canonical = MathContext.withContext(scratch) { vectorOf(expr).canonicalize(scratch) }
        return !scratch.shouldStop && canonical is MatrixExpression
    }

    /**
     * The line of [expr], a vector of 2 components with one parameter; the name in "g = (1, 2) + r·(2, 1)" only labels it. Null for
     * everything else.
     */
    fun fromExpression(expr: Expression, context: MathContext): ImplicitCurve? {
        val vector = vectorOf(expr)
        if (!vector.containsExpressionMatching { it is MatrixExpression }) return null
        val scratch = context.copy()
        val line = MathContext.withContext(scratch) {
            val canonical = vector.canonicalize(scratch) as? MatrixExpression ?: return@withContext null
            if (scratch.shouldStop || !canonical.isVector || canonical.elements.size != 2) return@withContext null
            val parameter = canonical.variables().singleOrNull() ?: return@withContext null
            // p at t = 0, u = (p + u) − p at t = 1; a component that is not of the form p + t·u is no line
            fun at(t: Long) = canonical.elements.map { it.substituteVariables(mapOf(parameter to Value(t))).canonicalize(scratch) }
            val p = at(0)
            val u = at(1).zip(p) { next, start -> Additions(next, start.negate()).canonicalize(scratch) }
            val linear = canonical.elements.indices.all { i ->
                listOf(2.0, -3.0).all { t ->
                    val actual = canonical.elements[i].evaluateNumerically(mapOf(parameter to t))
                    val expected = p[i].evaluateNumerically() + t * u[i].evaluateNumerically()
                    abs(actual - expected) <= 1e-9 * maxOf(1.0, abs(expected))
                }
            }
            if (scratch.shouldStop || !linear || u.all { it.isZero }) return@withContext null
            // u₂·(x − p₁) − u₁·(y − p₂)
            val xPart = Multiplications.of(u[1], Additions(X, p[0].negate()))
            val yPart = Multiplications.of(u[0], Additions(Y, p[1].negate())).negate()
            Additions(xPart, yPart)
        } ?: return null
        return ImplicitCurve(PlotCurve.canonicalForPlotting(line, context), context.angleUnit)
    }
}
