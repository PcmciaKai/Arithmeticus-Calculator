package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
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
            // |v| of a vector is its length; a matrix has no absolute value
            is MatrixExpression -> if (cOperand.isVector) VectorFunction.norm(cOperand).canonicalize(context) else {
                context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
                this
            }
            // The modulus of a complex number: |a + bi| = √(a² + b²)
            else -> when {
                cOperand.containsImaginaryUnit() -> modulus(cOperand, context)
                // |e| = e, |−π| = π: a number without variables has a known sign
                cOperand.variables().isEmpty() && cOperand.evaluateNumerically().let { it.isFinite() && it > 0 } -> cOperand
                cOperand.variables().isEmpty() && cOperand.evaluateNumerically().let { it.isFinite() && it < 0 } -> cOperand.negate().canonicalize(context)
                else -> AbsoluteValue(cOperand)
            }
        }
    }

    private fun modulus(z: Expression, context: MathContext): Expression {
        val (re, im) = complexParts(z) ?: return numericComplex(z)?.let { Value(it.abs) } ?: AbsoluteValue(z)
        return Power(Additions(listOf(Power(re, Value.TWO), Power(im, Value.TWO))), Value.HALF).canonicalize(context)
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = abs(operand.evaluateNumerically(bindings))

    override fun equals(other: Any?): Boolean = other is AbsoluteValue && operand == other.operand

    override fun hashCode(): Int = operand.hashCode() * 31 + 7

    override fun toString(): String = "abs($operand)"
}
