package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext

class Additions(val operands: List<Expression>) : Expression() {

    constructor(vararg exprs: Expression) : this(exprs.toList())

    override val children: List<Expression> get() = operands

    override fun copyWithChildren(children: List<Expression>): Expression = Additions(children)

    override fun canonicalize(context: MathContext): Expression {
        val flattened = mutableListOf<Expression>()
        for (op in operands) {
            val cOp = op.canonicalize(context)
            if (context.shouldStop) return this
            if (cOp is Additions) {
                flattened.addAll(cOp.operands)
            } else if (!cOp.isZero) {
                flattened.add(cOp)
            }
        }

        if (flattened.isEmpty()) return Value.ZERO
        if (flattened.size == 1) return flattened[0]

        // Absolute temperatures need their own rules (offsets, differences)
        if (flattened.any { it.absoluteTemperatureUnit() != null }) {
            return combineTemperatureTerms(flattened, context) ?: this
        }

        // Check for unit unification
        val unitTerms = flattened.mapNotNull { it.partWithUnit() }
        if (unitTerms.isNotEmpty()) {
            val targetUnit = unitTerms.first()
            val convertedTerms = mutableListOf<Expression>()
            for (term in flattened) {
                val converted = term.convertToUnit(targetUnit, true, context)
                if (converted != null) {
                    convertedTerms.add(converted)
                } else {
                    convertedTerms.add(term)
                }
            }
            flattened.clear()
            flattened.addAll(convertedTerms)
        }

        // Merge like terms by comparing partWithoutCoefficient()
        val merged = mutableListOf<Expression>()
        val used = BooleanArray(flattened.size)

        for (i in flattened.indices) {
            if (used[i]) continue
            var currentTerm = flattened[i]
            val bodyI = currentTerm.partWithoutCoefficient()
            var coeffSum = currentTerm.coefficient()

            for (j in i + 1 until flattened.size) {
                if (used[j]) continue
                val bodyJ = flattened[j].partWithoutCoefficient()
                if (bodyI == bodyJ) {
                    coeffSum = coeffSum.plus(flattened[j].coefficient())
                    used[j] = true
                }
            }

            if (!coeffSum.isZero) {
                if (bodyI == null) {
                    merged.add(coeffSum)
                } else {
                    merged.add(Multiplications.of(coeffSum, bodyI).canonicalize(context))
                }
            }
            used[i] = true
        }

        if (merged.isEmpty()) return Value.ZERO
        if (merged.size == 1) return merged[0]
        return Additions(merged)
    }

    override fun evaluateNumerically(value: Double, variable: Variable?): Double {
        return operands.sumOf { it.evaluateNumerically(value, variable) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Additions) return false
        return sameOperandsIgnoringOrder(operands, other.operands)
    }

    override fun hashCode(): Int = operandMultisetHash(operands)

    override fun toString(): String {
        return operands.joinToString(" + ") {
            if (it.isNegative) "(${it})" else it.toString()
        }
    }

    companion object {
        fun of(vararg exprs: Expression): Expression {
            return if (exprs.size == 1) exprs[0] else Additions(exprs.toList())
        }
    }
}
