package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext

class Multiplications(val operands: List<Expression>) : Expression() {

    constructor(vararg exprs: Expression) : this(exprs.toList())

    override val children: List<Expression> get() = operands

    override fun copyWithChildren(children: List<Expression>): Expression = Multiplications(children)

    override fun coefficient(): Value {
        var coeff = Value.ONE
        for (op in operands) {
            coeff = coeff.times(op.coefficient())
        }
        return coeff
    }

    override fun partWithoutCoefficient(): Expression? {
        val nonCoeffs = operands.mapNotNull { it.partWithoutCoefficient() }
        return when {
            nonCoeffs.isEmpty() -> null
            nonCoeffs.size == 1 -> nonCoeffs[0]
            else -> Multiplications(nonCoeffs)
        }
    }

    override fun partWithUnit(): Expression? {
        val withUnit = operands.mapNotNull { it.partWithUnit() }
        return when {
            withUnit.isEmpty() -> null
            withUnit.size == 1 -> withUnit[0]
            else -> Multiplications(withUnit)
        }
    }

    override val isNegative: Boolean
        get() = operands.count { it.isNegative } % 2 != 0

    override fun canonicalize(context: MathContext): Expression {
        val flattened = mutableListOf<Expression>()
        var hasZeroFactor = false
        for (op in operands) {
            val cOp = op.canonicalize(context)
            if (context.shouldStop) return this
            if (cOp.isZero) {
                hasZeroFactor = true
            } else if (cOp is Multiplications) {
                flattened.addAll(cOp.operands)
            } else {
                flattened.add(cOp)
            }
        }
        if (hasZeroFactor) {
            // 0 * anything = 0, but keep units: "0 °C" is a meaningful quantity
            val unitFactors = flattened.filter { it is UnitExpression || (it is Power && it.base is UnitExpression) }
            return if (unitFactors.isEmpty()) Value.ZERO else Multiplications(listOf(Value.ZERO) + unitFactors)
        }

        if (flattened.isEmpty()) return Value.ONE
        if (flattened.size == 1) return flattened[0]

        // Merge Value constants
        var valueProduct = Value.ONE
        val nonValueTerms = mutableListOf<Expression>()
        for (term in flattened) {
            if (term is Value) {
                valueProduct = valueProduct.times(term)
            } else {
                nonValueTerms.add(term)
            }
        }

        if (valueProduct.isZero) return Value.ZERO

        // Merge like bases in powers / factors
        val mergedTerms = mutableListOf<Expression>()
        val used = BooleanArray(nonValueTerms.size)

        for (i in nonValueTerms.indices) {
            if (used[i]) continue
            val termI = nonValueTerms[i]
            val baseI = if (termI is Power) termI.base else termI
            var expI = if (termI is Power) termI.exponent else Value.ONE

            for (j in i + 1 until nonValueTerms.size) {
                if (used[j]) continue
                val termJ = nonValueTerms[j]
                val baseJ = if (termJ is Power) termJ.base else termJ
                val expJ = if (termJ is Power) termJ.exponent else Value.ONE

                if (baseI == baseJ) {
                    expI = Additions(listOf(expI, expJ)).canonicalize(context)
                    used[j] = true
                }
            }

            if (!expI.isZero) {
                if (expI == Value.ONE) {
                    mergedTerms.add(baseI)
                } else {
                    mergedTerms.add(Power(baseI, expI).canonicalize(context))
                }
            }
            used[i] = true
        }

        // Add back coefficient if not 1
        val finalTerms = mutableListOf<Expression>()
        if (valueProduct != Value.ONE || mergedTerms.isEmpty()) {
            finalTerms.add(valueProduct)
        }
        finalTerms.addAll(mergedTerms)

        if (finalTerms.isEmpty()) return Value.ONE
        if (finalTerms.size == 1) return finalTerms[0]

        // Check if we should distribute over additions: a * (b + c)
        val additionTerm = finalTerms.firstOrNull { it is Additions } as? Additions
        if (additionTerm != null && finalTerms.size == 2) {
            val other = finalTerms.first { it != additionTerm }
            val distributed = additionTerm.operands.map { of(other, it).canonicalize(context) }
            return Additions(distributed).canonicalize(context)
        }

        return Multiplications(finalTerms)
    }

    override fun evaluateNumerically(value: Double, variable: Variable?): Double {
        var prod = 1.0
        for (op in operands) {
            prod *= op.evaluateNumerically(value, variable)
        }
        return prod
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Multiplications) return false
        return sameOperandsIgnoringOrder(operands, other.operands)
    }

    override fun hashCode(): Int = 31 * operandMultisetHash(operands) + 1

    override fun toString(): String {
        return operands.joinToString(" * ") {
            if (it is Additions) "($it)" else it.toString()
        }
    }

    companion object {
        fun of(vararg exprs: Expression): Expression {
            val list = exprs.toList()
            return when {
                list.isEmpty() -> Value.ONE
                list.size == 1 -> list[0]
                else -> Multiplications(list)
            }
        }
    }
}
