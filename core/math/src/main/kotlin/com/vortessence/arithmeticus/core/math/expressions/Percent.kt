package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext

/**
 * A percent as it was typed, 15%. It is [operand]/100 as soon as it is canonicalized; the steps of
 * a calculation keep it to show how it is converted (15% = 15/100 = 3/20).
 */
internal class Percent(val operand: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression = Percent(children.single())

    override fun canonicalize(context: MathContext): Expression =
        Multiplications.of(operand, Value(1L, 100L)).canonicalize(context)

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = operand.evaluateNumerically(bindings) / 100.0

    override val isNegative: Boolean get() = operand.isNegative

    override fun equals(other: Any?): Boolean = other is Percent && operand == other.operand

    override fun hashCode(): Int = operand.hashCode() * 31 + 100

    override fun toString(): String = "$operand%"

    companion object {
        /** [e] with every percent written as the fraction it is, 15% as 15·(1/100). */
        fun withoutPercents(e: Expression): Expression = e.transform {
            if (it is Percent) Multiplications.of(withoutPercents(it.operand), Value(1L, 100L)) else it
        }
    }
}
