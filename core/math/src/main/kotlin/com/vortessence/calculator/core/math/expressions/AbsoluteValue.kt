package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import kotlin.math.abs

/** |operand|, e.g. in the antiderivative ln|x| of 1/x. */
class AbsoluteValue(val operand: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression = AbsoluteValue(children.single())

    override fun canonicalize(context: MathContext): Expression {
        val cOperand = operand.canonicalize(context)
        if (context.shouldStop) return this
        return when (cOperand) {
            is Value -> cOperand.abs()
            is AbsoluteValue -> cOperand
            is MatrixExpression -> {
                context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
                this
            }
            else -> AbsoluteValue(cOperand)
        }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = abs(operand.evaluateNumerically(bindings))

    override fun equals(other: Any?): Boolean = other is AbsoluteValue && operand == other.operand

    override fun hashCode(): Int = operand.hashCode() * 31 + 7

    override fun toString(): String = "abs($operand)"
}
