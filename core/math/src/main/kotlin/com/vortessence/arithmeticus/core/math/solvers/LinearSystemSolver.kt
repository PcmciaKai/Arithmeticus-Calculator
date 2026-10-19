package com.vortessence.arithmeticus.core.math.solvers

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.Variable
import kotlin.math.abs

/**
 * Solves systems of linear equations by Gauss-Jordan elimination on the canonical coefficients,
 * so the solution is exact: fractions stay fractions and √2 stays √2.
 */
object LinearSystemSolver {

    /**
     * Solves [equations] for [variables]. Every variable the system determines maps to its value;
     * with infinitely many solutions the values contain the remaining (free) variables, which
     * are not in the map. An empty map means that every assignment is a solution.
     *
     * Returns null, with an issue reported, if the system is not linear or has no solution.
     */
    fun solve(equations: List<Equals>, variables: List<Variable>, context: MathContext): Map<Variable, Expression>? {
        val n = variables.size
        // One row per equation: the coefficients of the variables, then the right-hand side
        val a = equations.map { equation ->
            val row = linearRow(equation, variables, context) ?: run {
                if (!context.shouldStop) context.stopWithError(MathIssue.CANT_SOLVE_GENERIC)
                return null
            }
            row.toTypedArray()
        }.toTypedArray()

        val pivotCols = mutableListOf<Int>()
        for (col in 0 until n) {
            val pivotRow = pivotCols.size
            if (pivotRow == a.size) break
            // Partial pivoting: the largest entry keeps decimal coefficients accurate
            val best = (pivotRow until a.size)
                .filter { !isZero(a[it][col]) }
                .maxByOrNull { magnitude(a[it][col]) } ?: continue
            a[best] = a[pivotRow].also { a[pivotRow] = a[best] }

            val pivotInverse = a[pivotRow][col].invert()
            for (c in col..n) a[pivotRow][c] = Multiplications.of(a[pivotRow][c], pivotInverse).canonicalize(context)
            a[pivotRow][col] = Value.ONE
            for (r in a.indices) {
                if (r == pivotRow) continue
                val factor = a[r][col]
                if (!isZero(factor)) {
                    for (c in col + 1..n) {
                        val eliminated = Multiplications.of(factor, a[pivotRow][c]).negate()
                        a[r][c] = Additions(a[r][c], eliminated).canonicalize(context)
                    }
                }
                a[r][col] = Value.ZERO
            }
            if (context.shouldStop) return null
            pivotCols.add(col)
        }

        // The rows below the pivots have no variables left: they read 0 = right-hand side
        if ((pivotCols.size until a.size).any { !isZero(a[it][n]) }) {
            context.stopWithError(MathIssue.CONTRADICTION)
            return null
        }
        val freeCols = (0 until n) - pivotCols.toSet()
        return pivotCols.withIndex().associate { (row, col) ->
            // x = rhs − (coefficients of the free variables)·(free variables)
            val terms = listOf(a[row][n]) + freeCols.map { f -> Multiplications.of(a[row][f], variables[f]).negate() }
            variables[col] to Additions(terms).canonicalize(context)
        }
    }

    /**
     * The coefficients of [variables] in [equation] followed by its right-hand side, i.e. the
     * a₁ … aₙ and b of a₁x₁ + … + aₙxₙ = b; null if the equation is not linear.
     */
    private fun linearRow(equation: Equals, variables: List<Variable>, context: MathContext): List<Expression>? {
        val diff = Additions(equation.left, equation.right.negate()).canonicalize(context)
        if (context.shouldStop) return null
        val coefficients = List(variables.size) { mutableListOf<Expression>() }
        val constants = mutableListOf<Expression>()
        for (term in if (diff is Additions) diff.operands else listOf(diff)) {
            val termVariables = term.variables()
            if (termVariables.isEmpty()) {
                constants.add(term)
                continue
            }
            // A product of variables (x·y) is not linear
            val variable = termVariables.singleOrNull() ?: return null
            val index = variables.indexOf(variable).takeIf { it >= 0 } ?: return null
            coefficients[index].add(coefficientOf(term, variable) ?: return null)
        }
        // diff = a₁x₁ + … + aₙxₙ + c = 0, so b = −c
        return coefficients.map { sum(it, context) } + sum(constants, context).negate().canonicalize(context)
    }

    /** The factor a of a term a·x, or null if the term is not of that form (x², sin(x), ...). */
    private fun coefficientOf(term: Expression, variable: Variable): Expression? {
        if (term == variable) return Value.ONE
        if (term !is Multiplications || term.operands.count { it == variable } != 1) return null
        val others = term.operands.filter { it != variable }
        if (others.any { it.variables().isNotEmpty() }) return null
        return Multiplications.of(*others.toTypedArray())
    }

    private fun sum(terms: List<Expression>, context: MathContext): Expression =
        if (terms.isEmpty()) Value.ZERO else Additions(terms).canonicalize(context)

    /**
     * Whether a (canonical) coefficient is zero. Symbolic ones that did not simplify to 0, such
     * as √2·√8 − 4, are checked numerically.
     */
    private fun isZero(expr: Expression): Boolean {
        if (expr.isZero) return true
        if (expr is Value || expr.variables().isNotEmpty()) return false
        val numeric = expr.evaluateNumerically()
        return numeric.isFinite() && abs(numeric) < ZERO_TOLERANCE
    }

    private fun magnitude(expr: Expression): Double =
        abs(expr.evaluateNumerically()).takeIf { it.isFinite() } ?: 0.0

    private const val ZERO_TOLERANCE = 1e-12
}
