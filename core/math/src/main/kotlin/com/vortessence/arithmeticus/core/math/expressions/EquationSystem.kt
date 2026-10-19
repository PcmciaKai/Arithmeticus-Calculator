package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext

/**
 * Several lines of input that hold together, one equation per line, e.g.
 * −2x + y = −3 and 3x + 2y = 8. Solved by [com.vortessence.arithmeticus.core.math.solvers.LinearSystemSolver].
 */
class EquationSystem(val lines: List<Expression>) : Expression() {

    override val children: List<Expression> get() = lines

    override fun copyWithChildren(children: List<Expression>): Expression = EquationSystem(children)

    override fun canonicalize(context: MathContext): Expression {
        val canonical = lines.map { line ->
            line.canonicalize(context).also { if (context.shouldStop) return this }
        }
        return EquationSystem(canonical)
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = Double.NaN

    override fun equals(other: Any?): Boolean = other is EquationSystem && lines == other.lines

    override fun hashCode(): Int = lines.hashCode() * 31 + 11

    override fun toString(): String = lines.joinToString("; ")
}
