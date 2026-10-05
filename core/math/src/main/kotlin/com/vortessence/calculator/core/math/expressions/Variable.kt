package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext

data class Variable(val identifier: String) : Expression() {

    override fun canonicalize(context: MathContext): Expression = this

    override fun variables(): Set<Variable> = setOf(this)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        return bindings[this] ?: Double.NaN
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Variable) return false
        return identifier == other.identifier
    }

    override fun hashCode(): Int = identifier.hashCode()

    override fun toString(): String = identifier
}

/**
 * Replaces the free variables in [values] by their values, e.g. a by 2x² after "a := 2x²".
 * Values may use other variables, which are replaced as well; a variable is not replaced
 * within its own value, so definitions such as "x := 2x" cannot expand forever.
 * Integration and differentiation variables are bound and keep their meaning inside the
 * integrand or the differentiated expression.
 */
fun Expression.substituteVariables(values: Map<Variable, Expression>): Expression {
    if (values.isEmpty()) return this
    return when (this) {
        is Variable -> values[this]?.substituteVariables(values - this) ?: this
        is Integral -> Integral(
            lowerBound.substituteVariables(values),
            upperBound.substituteVariables(values),
            integrand.substituteVariables(values - variable),
            variable
        )
        is Antiderivative -> Antiderivative(integrand.substituteVariables(values - variable), variable)
        is Derivative -> Derivative(body.substituteVariables(values - variable), variable, point?.substituteVariables(values))
        else -> {
            val oldChildren = children
            val newChildren = oldChildren.map { it.substituteVariables(values) }
            if (newChildren.indices.all { newChildren[it] === oldChildren[it] }) this else copyWithChildren(newChildren)
        }
    }
}
