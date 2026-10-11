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

        if (flattened.any { it is MatrixExpression }) return addMatrices(flattened, context) ?: this

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

        // ln 6 − ln 2 = ln 3, log 25 + log 4 = 2
        LogarithmRules.combine(merged, context)?.let { return Additions(it).canonicalize(context) }

        // sin²u + cos²u = 1 (also times the same factors)
        var pythagorean = false
        outer@ while (true) {
            for (i in merged.indices) for (j in i + 1 until merged.size) {
                val combined = pythagoreanSum(merged[i], merged[j]) ?: continue
                val replacement = combined.canonicalize(context)
                merged.removeAt(j)
                merged.removeAt(i)
                if (!replacement.isZero) merged.add(i, replacement)
                pythagorean = true
                continue@outer
            }
            break
        }
        if (pythagorean) return Additions(merged).canonicalize(context)

        if (merged.isEmpty()) return Value.ZERO
        if (merged.size == 1) return merged[0]
        val sum = Additions(merged)
        return multipliedOutIfShorter(sum, context) ?: sum
    }

    /**
     * [sum] with its products and powers of sums multiplied out, if that makes it shorter
     * because terms cancel: (x+1)² − x² = 2x + 1, but (x+1)² + 1 stays as it is.
     */
    private fun multipliedOutIfShorter(sum: Additions, context: MathContext): Expression? {
        // Multiplying out creates sums that are canonicalized again; they must not recurse
        if (isMultiplyingOut.get() || !hasExpandableProducts(sum)) return null
        isMultiplyingOut.set(true)
        try {
            val expanded = expandProducts(sum).canonicalize(context)
            if (context.shouldStop) return null
            return expanded.takeIf { expressionSize(it) < expressionSize(sum) }
        } finally {
            isMultiplyingOut.set(false)
        }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        return operands.sumOf { it.evaluateNumerically(bindings) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Additions) return false
        return sameOperandsIgnoringOrder(operands, other.operands)
    }

    override fun hashCode(): Int = operandMultisetHash(operands)

    override fun toString(): String {
        return operands.joinToString(" + ") {
            if (it.isNegative) "($it)" else it.toString()
        }
    }

    companion object {
        private val isMultiplyingOut = ThreadLocal.withInitial { false }

        fun of(vararg exprs: Expression): Expression {
            return if (exprs.size == 1) exprs[0] else Additions(exprs.toList())
        }
    }
}
