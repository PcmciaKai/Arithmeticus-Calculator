package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.containsImaginaryUnit
import com.vortessence.calculator.core.math.expressions.expandProducts
import com.vortessence.calculator.core.math.expressions.substituteVariables
import kotlin.math.abs
import kotlin.math.max

/**
 * Two equations in two variables of which one is linear in a variable (x + y = 7, y = x², xy = 6): that equation is solved
 * for the variable, which is written into the other equation, an equation of one variable; its solutions give the others.
 * Every solution is checked in both equations.
 */
object NonlinearSystemSolver {

    /** The equation [line] solved for [variable] (variable = [expression]), written into equation [other], which is then an equation for [remaining]. */
    internal class Plan(
        val line: Int,
        val variable: Variable,
        val expression: Expression,
        val other: Int,
        val remaining: Variable,
        val substituted: Equals
    )

    /** The solutions as values of the variables, sorted; empty if there is no real one; null (with an issue reported if one was found) if this cannot be solved. */
    fun solve(equations: List<Equals>, variables: List<Variable>, context: MathContext): List<Map<Variable, Expression>>? {
        // x² + y² = 25 with x² − y² = 7, or x² + xy = 10 with y² + xy = 15: exact by their own methods, before substitution
        QuadraticSystems.solve(equations, variables, context)?.let { return it }
        val plan = plan(equations, variables, context) ?: return null
        val scratch = context.copy()
        val roots = plan.substituted.solveForVariable(plan.remaining, scratch)
        if (context.shouldStop) return null
        if (scratch.issues.contains(MathIssue.NO_REAL_SOLUTION) || scratch.issues.contains(MathIssue.CONTRADICTION)) return emptyList()
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || roots.isEmpty() || roots == setOf(plan.remaining)) return null
        val solutions = roots.mapNotNull { root -> assignment(equations, plan, root, context) }
        val ordered = solutions.sortedWith(compareBy({ it.getValue(variables[0]).evaluateNumerically() }, { it.getValue(variables[1]).evaluateNumerically() }))
        // The same solution found twice
        return ordered.filterIndexed { i, s -> ordered.indices.none { j -> j < i && same(ordered[j], s, variables) } }
    }

    private fun same(a: Map<Variable, Expression>, b: Map<Variable, Expression>, variables: List<Variable>): Boolean =
        variables.all { v -> abs(a.getValue(v).evaluateNumerically() - b.getValue(v).evaluateNumerically()) <= 1e-9 }

    /** The values for a [root] of the one-variable equation, or null if they are not a solution of both equations. */
    internal fun assignment(equations: List<Equals>, plan: Plan, root: Expression, context: MathContext): Map<Variable, Expression>? {
        val scratch = context.copy()
        val value = plan.expression.substituteVariables(mapOf(plan.remaining to root)).canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        val result = mapOf(plan.remaining to root, plan.variable to value)
        // A complex pair is not checked numerically
        if (root.containsImaginaryUnit() || value.containsImaginaryUnit()) return result
        val bindings = result.mapValues { it.value.evaluateNumerically() }
        for (equation in equations) {
            val l = equation.left.evaluateNumerically(bindings)
            val r = equation.right.evaluateNumerically(bindings)
            if (!l.isFinite() || !r.isFinite() || abs(l - r) > 1e-7 * max(1.0, max(abs(l), abs(r)))) return null
        }
        return result
    }

    internal fun plan(equations: List<Equals>, variables: List<Variable>, context: MathContext): Plan? {
        if (equations.size != 2 || variables.size != 2) return null
        var best: Pair<Plan, Int>? = null
        for (line in equations.indices) for (v in variables) {
            val (coefficient, expression) = linearIn(equations[line], v, context) ?: continue
            val other = 1 - line
            if (!equations[other].containsVariable(v)) continue
            val remaining = variables.single { it != v }
            // Easiest: v alone or with the coefficient 1, then another number, then a coefficient with letters in it
            val score = when {
                coefficient == Value.ONE || coefficient == Value.MINUS_ONE -> 0
                coefficient is Value -> 1
                else -> 2
            }
            if (best == null || score < best.second) {
                val substituted = equations[other].transform { if (it == v) expression else it } as Equals
                best = Plan(line, v, expression, other, remaining, substituted) to score
            }
        }
        return best?.first
    }

    /** (a, −b/a) if [equation] reads a·v + b = 0 with a and b free of v; null if v is not there to the first power only. */
    internal fun linearIn(equation: Equals, v: Variable, context: MathContext): Pair<Expression, Expression>? {
        val scratch = context.copy()
        val difference = expandProducts(Additions(equation.left, equation.right.negate()).canonicalize(scratch)).canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        val terms = if (difference is Additions) difference.operands else listOf(difference)
        val a = mutableListOf<Expression>()
        val b = mutableListOf<Expression>()
        for (term in terms) {
            when {
                !term.containsVariable(v) -> b.add(term)
                term == v -> a.add(Value.ONE)
                term is Multiplications && term.operands.count { it == v } == 1 && term.operands.filter { it != v }.none { it.containsVariable(v) } ->
                    a.add(Multiplications.of(*term.operands.filter { it != v }.toTypedArray()))
                else -> return null
            }
        }
        if (a.isEmpty()) return null
        val coefficient = Additions.of(*a.toTypedArray()).canonicalize(scratch)
        if (coefficient.isZero) return null
        val constant = if (b.isEmpty()) Value.ZERO else Additions.of(*b.toTypedArray()).canonicalize(scratch)
        val solved = Multiplications.of(constant.negate(), coefficient.invert()).canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        return coefficient to solved
    }
}
