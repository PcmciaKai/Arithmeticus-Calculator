package com.vortessence.calculator.core.math.type

import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import java.math.BigDecimal
import java.math.MathContext as DecimalContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * Converts an [Expression] into a [MathString] for display. The output only uses nodes the
 * parser understands (apart from [TextNode]), so results can be reused as input.
 */
class MathWriter {

    fun write(expr: Expression): MathString {
        val result = MathString()
        when (expr) {
            is Value -> writeValue(expr, result)
            is Constant -> result.append(ConstantNode(expr.name))
            is Variable -> result.append(VariableNode(expr.identifier))
            is UnitExpression -> result.append(UnitNode(expr.symbol))
            is Power -> {
                val exp = expr.exponent
                if (exp is Value && exp.isNegative) {
                    // x^-n is shown as the fraction 1/x^n
                    writeMultiplications(Multiplications(listOf(expr)), result)
                } else {
                    writePower(expr, result)
                }
            }
            is Multiplications -> writeMultiplications(expr, result)
            is Additions -> writeAdditions(expr, result)
            is TrigonometricFunction -> {
                result.append(FunctionNode(expr.type.symbol, write(expr.operand)))
            }
            is Logarithm -> {
                val base = expr.base
                when {
                    base == Constant.E -> result.append(FunctionNode("ln", write(expr.operand)))
                    base is Value && base.rational == Rational.of(10L) ->
                        result.append(FunctionNode("log", write(expr.operand)))
                    else -> result.append(LogBaseNode(write(base), write(expr.operand)))
                }
            }
            is Equals -> {
                result.appendAll(write(expr.left))
                result.append(OperatorNode(MathOp.EQUALS))
                result.appendAll(write(expr.right))
            }
            is Integral -> {
                result.append(IntegralNode(
                    write(expr.lowerBound),
                    write(expr.upperBound),
                    write(expr.integrand),
                    expr.variable.identifier
                ))
            }
            is MatrixExpression -> {
                result.append(MatrixNode(
                    expr.rows,
                    expr.cols,
                    expr.elements.map { write(it) }
                ))
            }
            else -> result.append(TextNode(expr.toString()))
        }
        return result
    }

    private fun writeValue(value: Value, result: MathString) {
        val rational = value.rational
        if (rational != null) {
            if (rational.isNegative) result.append(OperatorNode(MathOp.MINUS))
            val numerator = rational.numerator.abs().toString()
            if (rational.isFractional) {
                val numStr = MathString(listOf(NumberNode(numerator)))
                val denStr = MathString(listOf(NumberNode(rational.denominator.toString())))
                result.append(FractionNode(numStr, denStr))
            } else {
                result.append(NumberNode(numerator))
            }
        } else {
            writeReal(value.real.value, result)
        }
    }

    /**
     * Writes a floating point number with up to [SIGNIFICANT_DIGITS] significant digits.
     * Very large or small magnitudes use scientific notation as "m × 10^e".
     */
    private fun writeReal(d: Double, result: MathString) {
        when {
            d.isNaN() -> return result.append(TextNode("undefined"))
            d.isInfinite() -> {
                if (d < 0) result.append(OperatorNode(MathOp.MINUS))
                return result.append(TextNode("∞"))
            }
            d == 0.0 -> return result.append(NumberNode("0"))
        }
        if (d < 0) result.append(OperatorNode(MathOp.MINUS))
        val magnitude = abs(d)
        if (magnitude % 1.0 == 0.0 && magnitude < 1e15) {
            result.append(NumberNode(BigDecimal(magnitude).toPlainString()))
            return
        }
        val rounded = BigDecimal(magnitude).round(DecimalContext(SIGNIFICANT_DIGITS))
        val exponent = floor(log10(rounded.toDouble())).toInt()
        if (exponent in MIN_PLAIN_EXPONENT until MAX_PLAIN_EXPONENT) {
            result.append(NumberNode(rounded.stripTrailingZeros().toPlainString()))
            return
        }
        val mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
        val exponentString = MathString()
        if (exponent < 0) exponentString.append(OperatorNode(MathOp.MINUS))
        exponentString.append(NumberNode(abs(exponent).toString()))
        result.append(NumberNode(mantissa))
        result.append(OperatorNode(MathOp.MULTIPLY))
        result.append(ExponentNode(MathString(listOf(NumberNode("10"))), exponentString))
    }

    private fun writePower(power: Power, result: MathString) {
        val exp = power.exponent
        if (exp is Value && exp.rational != null) {
            val r = exp.rational
            if (r == Rational.HALF) {
                result.append(RadicalNode(null, write(power.base)))
                return
            }
            if (r.numerator == BigInt.ONE && r.denominator > BigInt.ONE) {
                val degStr = MathString(listOf(NumberNode(r.denominator.toString())))
                result.append(RadicalNode(degStr, write(power.base)))
                return
            }
        }
        val baseStr = write(power.base)
        val base = if (needsParenthesesAsBase(power.base)) MathString(listOf(ParenthesesNode(baseStr))) else baseStr
        result.append(ExponentNode(base, write(power.exponent)))
    }

    private fun needsParenthesesAsBase(base: Expression): Boolean = when (base) {
        is Additions, is Multiplications, is Power, is Equals -> true
        is Value -> base.isNegative || base.rational?.isFractional == true || base.rational == null
        else -> false
    }

    private fun writeMultiplications(mult: Multiplications, result: MathString) {
        // Pull the sign to the front: -2x is written "−2x", not as a "-2" factor mid-product
        val negativeIndex = mult.operands.indexOfFirst { it is Value && it.isNegative }
        if (negativeIndex >= 0) {
            result.append(OperatorNode(MathOp.MINUS))
            result.appendAll(write(negatedForDisplay(mult)))
            return
        }

        // Partition into numerators and denominators (factors with negative exponent)
        val numerators = mutableListOf<Expression>()
        val denominators = mutableListOf<Expression>()

        for (op in mult.operands) {
            val exponent = (op as? Power)?.exponent as? Value
            if (op is Power && exponent != null && exponent.isNegative) {
                val posExp = exponent.negate()
                denominators.add(if (posExp == Value.ONE) op.base else Power(op.base, posExp))
            } else if (op is Value && op.rational != null && op.rational.isFractional) {
                if (op.rational.numerator != BigInt.ONE) {
                    numerators.add(Value(op.rational.numerator))
                }
                denominators.add(Value(op.rational.denominator))
            } else if (op != Value.ONE) {
                numerators.add(op)
            }
        }

        if (denominators.isNotEmpty()) {
            val numExpr = Multiplications.of(*numerators.toTypedArray())
            val denExpr = Multiplications.of(*denominators.toTypedArray())
            result.append(FractionNode(write(numExpr), write(denExpr)))
        } else if (numerators.isEmpty()) {
            result.append(NumberNode("1"))
        } else {
            writeFactors(numerators, result)
        }
    }

    private fun writeFactors(factors: List<Expression>, result: MathString) {
        for ((idx, op) in factors.withIndex()) {
            var nodes = write(op)
            if (op is Additions || op is Equals) {
                nodes = MathString(listOf(ParenthesesNode(nodes)))
            }
            if (idx > 0 && needsExplicitTimes(nodes)) {
                result.append(OperatorNode(MathOp.MULTIPLY))
            }
            result.appendAll(nodes)
        }
    }

    /** Juxtaposition would be ambiguous if the factor starts with digits (2·3, 2·10², 2·−x). */
    private fun needsExplicitTimes(factor: MathString): Boolean {
        return when (val first = factor.nodes.firstOrNull()) {
            is NumberNode, is OperatorNode, is FractionNode -> true
            is ExponentNode -> first.base?.nodes?.firstOrNull() is NumberNode
            else -> false
        }
    }

    private fun writeAdditions(add: Additions, result: MathString) {
        for ((idx, op) in add.operands.withIndex()) {
            val term: Expression
            if (idx > 0) {
                if (op.isNegative) {
                    result.append(OperatorNode(MathOp.MINUS))
                    term = negatedForDisplay(op)
                } else {
                    result.append(OperatorNode(MathOp.PLUS))
                    term = op
                }
            } else {
                term = op
            }
            val nodes = write(term)
            if (term is Additions) {
                result.append(ParenthesesNode(nodes))
            } else {
                result.appendAll(nodes)
            }
        }
    }

    /**
     * The negation of a negative term in a readable form: flips the sign of its numeric
     * coefficient (dropping a resulting factor 1) instead of wrapping it in (−1)·(…).
     */
    private fun negatedForDisplay(expr: Expression): Expression {
        if (expr is Value) return expr.negate()
        if (expr is Multiplications) {
            val index = expr.operands.indexOfFirst { it is Value && it.isNegative }
                .takeIf { it >= 0 } ?: expr.operands.indexOfFirst { it.isNegative }
            if (index >= 0) {
                val flipped = negatedForDisplay(expr.operands[index])
                val operands = expr.operands.toMutableList()
                if (flipped == Value.ONE) operands.removeAt(index) else operands[index] = flipped
                return Multiplications.of(*operands.toTypedArray())
            }
        }
        return expr.negate()
    }

    private fun MathString.append(node: MathNode) = insert(length, node)
    private fun MathString.appendAll(other: MathString) = insertAll(length, other.nodes)

    private companion object {
        const val SIGNIFICANT_DIGITS = 10
        const val MIN_PLAIN_EXPONENT = -6
        const val MAX_PLAIN_EXPONENT = 12
    }
}
