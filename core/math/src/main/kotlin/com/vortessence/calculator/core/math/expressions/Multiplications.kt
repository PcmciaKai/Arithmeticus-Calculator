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
        val canonicalOperands = operands.map { op ->
            op.canonicalize(context).also { if (context.shouldStop) return this }
        }
        // Matrix products depend on the order of the factors, so they are computed here
        // before like factors are merged
        if (canonicalOperands.any { it is MatrixExpression }) {
            val ordered = canonicalOperands.flatMap { if (it is Multiplications) it.operands else listOf(it) }
            return multiplyWithMatrices(ordered, context) ?: this
        }
        val flattened = mutableListOf<Expression>()
        var hasZeroFactor = false
        for (cOp in absoluteTemperaturesInKelvin(canonicalOperands, context) ?: return this) {
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

        // Merging can leave numbers (i·i = −1, √2·√2 = 2) or products (i³ = −i): combine them again
        if (mergedTerms.any { it is Value || it is Multiplications }) {
            return Multiplications(listOf(valueProduct) + mergedTerms).canonicalize(context)
        }

        // Add back coefficient if not 1
        val finalTerms = mutableListOf<Expression>()
        if (valueProduct != Value.ONE || mergedTerms.isEmpty()) {
            finalTerms.add(valueProduct)
        }
        finalTerms.addAll(mergedTerms)

        if (finalTerms.isEmpty()) return Value.ONE
        if (finalTerms.size == 1) return finalTerms[0]

        // Check if we should distribute over additions: a * (b + c). Complex numbers are always
        // multiplied out, so they take the form a + b·i: 2i·(1 + i) = −2 + 2i
        val additionTerm = finalTerms.firstOrNull { it is Additions } as? Additions
        if (additionTerm != null && (finalTerms.size == 2 || finalTerms.any { it.containsImaginaryUnit() })) {
            val others = finalTerms.toMutableList().apply { remove(additionTerm) }
            val distributed = additionTerm.operands.map { of(*(others + it).toTypedArray()).canonicalize(context) }
            return Additions(distributed).canonicalize(context)
        }

        return Multiplications(finalTerms)
    }

    /**
     * In a product, an absolute temperature on a shifted scale is used as absolute
     * temperature in kelvin, e.g. n·R·(25 °C) uses 298.15 K. The only exception is the
     * quantity itself, "25 × °C" (a number times the bare unit), which stays in °C.
     */
    private fun absoluteTemperaturesInKelvin(ops: List<Expression>, context: MathContext): List<Expression>? {
        return ops.mapIndexed { index, op ->
            val unit = op.absoluteTemperatureUnit()
            val isBareUnitOfQuantity = op is UnitExpression &&
                ops.withIndex().none { (i, other) -> i != index && other.containsUnit() }
            if (unit == null || unit == UnitRegistry.K || isBareUnitOfQuantity) {
                op
            } else {
                op.convertToUnit(UnitRegistry.K, includeUnitInResult = true, context = context) ?: return null
            }
        }
    }

    /** Negates the numeric coefficient, so -(25 °C) is -25 °C rather than -1 × (25 °C). */
    override fun negate(): Expression {
        val first = operands.firstOrNull()
        return if (first is Value) {
            val negated = first.negate()
            if (negated == Value.ONE) Multiplications.of(*operands.drop(1).toTypedArray())
            else Multiplications(listOf(negated) + operands.drop(1))
        } else {
            super.negate()
        }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        var prod = 1.0
        for (op in operands) {
            prod *= op.evaluateNumerically(bindings)
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
