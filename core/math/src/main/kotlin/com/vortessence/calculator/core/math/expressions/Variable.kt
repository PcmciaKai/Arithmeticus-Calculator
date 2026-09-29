package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext

data class Variable(val identifier: String) : Expression() {

    override fun canonicalize(context: MathContext): Expression = this

    override fun variables(): Set<Variable> = setOf(this)

    override fun evaluateNumerically(value: Double, variable: Variable?): Double {
        return if (variable == this) value else Double.NaN
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Variable) return false
        return identifier == other.identifier
    }

    override fun hashCode(): Int = identifier.hashCode()

    override fun toString(): String = identifier
}
