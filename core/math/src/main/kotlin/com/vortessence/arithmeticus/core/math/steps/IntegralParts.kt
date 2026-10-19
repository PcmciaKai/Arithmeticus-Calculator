package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.expressions.substituteVariables

/**
 * [inner], an integral after the substitution [u] = [value], whose result still has to be
 * written in the original variable again ([StepRule.BACK_SUBSTITUTION]). Shown as [inner].
 */
internal class BackSubstitution(val inner: Expression, val u: Variable, val value: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(inner)

    override fun variables(): Set<Variable> = inner.variables() - u + value.variables()

    override fun copyWithChildren(children: List<Expression>): Expression = BackSubstitution(children[0], u, value)

    override fun canonicalize(context: MathContext): Expression =
        inner.substituteVariables(mapOf(u to value)).canonicalize(context)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double =
        inner.substituteVariables(mapOf(u to value)).evaluateNumerically(bindings)

    override fun equals(other: Any?): Boolean =
        other is BackSubstitution && inner == other.inner && u == other.u && value == other.value

    override fun hashCode(): Int = (inner.hashCode() * 31 + u.hashCode()) * 31 + value.hashCode()

    override fun toString(): String = "($inner with $u = $value)"
}

/**
 * [antiderivative] F evaluated between the bounds, [F]ₐᵇ = F(b) − F(a) with a = [lower] and
 * b = [upper], for a definite integral in [variable].
 */
internal class EvaluateBetween(
    val antiderivative: Expression,
    val variable: Variable,
    val lower: Expression,
    val upper: Expression
) : Expression() {

    override val children: List<Expression> get() = listOf(antiderivative)

    override fun variables(): Set<Variable> = antiderivative.variables() - variable + lower.variables() + upper.variables()

    override fun copyWithChildren(children: List<Expression>): Expression = EvaluateBetween(children[0], variable, lower, upper)

    /** F(b) − F(a), not yet calculated. */
    fun difference(): Expression {
        val f = unmarked(antiderivative)
        return Additions(
            f.substituteVariables(mapOf(variable to upper)),
            Multiplications(Value.MINUS_ONE, f.substituteVariables(mapOf(variable to lower)))
        )
    }

    override fun canonicalize(context: MathContext): Expression = difference().canonicalize(context)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = difference().evaluateNumerically(bindings)

    override fun equals(other: Any?): Boolean = other is EvaluateBetween && antiderivative == other.antiderivative &&
        variable == other.variable && lower == other.lower && upper == other.upper

    override fun hashCode(): Int = ((antiderivative.hashCode() * 31 + variable.hashCode()) * 31 + lower.hashCode()) * 31 + upper.hashCode()

    override fun toString(): String = "[$antiderivative]_$lower^$upper"
}
