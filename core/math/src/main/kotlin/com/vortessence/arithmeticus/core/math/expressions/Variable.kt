package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext

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
 * Integration and differentiation variables, the index of a sum and the variable of a limit
 * are bound and keep their meaning inside the integrand, sum, ...
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
        is Series -> Series(isProduct, index, lower.substituteVariables(values), upper.substituteVariables(values), body.substituteVariables(values - index))
        is Limit -> Limit(body.substituteVariables(values - variable), variable, point.substituteVariables(values))
        is Derivative -> Derivative(body.substituteVariables(values - variable), variable, point?.substituteVariables(values))
        is NamedFunction -> {
            // taylor(f, x, …) and apart(f, x) name their variable: a definition of it is not used inside f, nor in place of the name
            val named = if ((kind == NamedFunctionKind.TAYLOR && arguments.size >= 3) || (kind == NamedFunctionKind.APART && arguments.size == 2)) arguments[1] as? Variable else null
            if (kind == NamedFunctionKind.DSOLVE) {
                // The unknown function and the variable it depends on are letters of the equation, whatever they are defined as
                val own = mutableSetOf<Variable>()
                fun scan(e: Expression) {
                    if (e is Derivative) { own.add(e.variable); var body = e.body; while (body is Derivative) body = body.body; (body as? Variable)?.let { own.add(it) } }
                    e.children.forEach { scan(it) }
                }
                scan(arguments[0])
                NamedFunction(kind, arguments.mapIndexed { i, a -> a.substituteVariables(if (i == 0) values - own else values) })
            } else if (named == null) substituteChildren(values)
            else NamedFunction(kind, arguments.mapIndexed { i, a -> if (i == 1) a else a.substituteVariables(if (i == 0) values - named else values) })
        }
        else -> substituteChildren(values)
    }
}

private fun Expression.substituteChildren(values: Map<Variable, Expression>): Expression {
    val oldChildren = children
    val newChildren = oldChildren.map { it.substituteVariables(values) }
    return if (newChildren.indices.all { newChildren[it] === oldChildren[it] }) this else copyWithChildren(newChildren)
}
