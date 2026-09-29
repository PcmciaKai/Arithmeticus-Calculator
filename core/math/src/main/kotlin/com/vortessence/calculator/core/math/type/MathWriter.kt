package com.vortessence.calculator.core.math.type

import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext as DecimalContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * Converts an [Expression] into a [MathString] for display. The output only uses nodes the
 * parser understands (apart from [TextNode]), so results can be reused as input.
 */
class MathWriter {

    /** Writes [expr]; a bare unit is written as a quantity of 1, e.g. "1 psi". */
    fun write(expr: Expression): MathString {
        if (!isPureUnit(expr)) return writeExpr(expr)
        val result = MathString(listOf(NumberNode("1")))
        result.appendAll(writeExpr(expr))
        return result
    }

    /** Writes only a unit, e.g. "m/s²" (without a magnitude). */
    fun writeUnit(unit: Expression): MathString = writeExpr(unit)

    private fun isPureUnit(expr: Expression): Boolean = isUnitOnly(expr)

    private fun writeExpr(expr: Expression): MathString {
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
                result.append(FunctionNode(expr.type.symbol, writeExpr(expr.operand)))
            }
            is Logarithm -> {
                val base = expr.base
                when {
                    base == Constant.E -> result.append(FunctionNode("ln", writeExpr(expr.operand)))
                    base is Value && base.rational == Rational.of(10L) ->
                        result.append(FunctionNode("log", writeExpr(expr.operand)))
                    else -> result.append(LogBaseNode(writeExpr(base), writeExpr(expr.operand)))
                }
            }
            is UnitConversion -> {
                result.appendAll(write(expr.value))
                result.append(OperatorNode(MathOp.CONVERT))
                result.appendAll(writeExpr(expr.target))
            }
            is Equals -> {
                result.appendAll(write(expr.left))
                result.append(OperatorNode(MathOp.EQUALS))
                result.appendAll(write(expr.right))
            }
            is Integral -> {
                result.append(IntegralNode(
                    writeExpr(expr.lowerBound),
                    writeExpr(expr.upperBound),
                    writeExpr(expr.integrand),
                    expr.variable.identifier
                ))
            }
            is MatrixExpression -> {
                result.append(MatrixNode(
                    expr.rows,
                    expr.cols,
                    expr.elements.map { writeExpr(it) }
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
    /**
     * Writes a floating point number with up to 10 significant digits and no trailing zeros.
     * With [scientific] it is always written as mantissa ×10ⁿ, e.g. 4.894371097×10¹.
     */
    fun writeNumber(d: Double, scientific: Boolean = false): MathString =
        MathString().also { writeReal(d, it, scientific) }

    private fun writeReal(d: Double, result: MathString, scientific: Boolean = false) {
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
        if (!scientific && magnitude % 1.0 == 0.0 && magnitude < 1e15) {
            result.append(NumberNode(BigDecimal(magnitude).toPlainString()))
            return
        }
        val rounded = BigDecimal(magnitude).round(DecimalContext(SIGNIFICANT_DIGITS))
        val exponent = floor(log10(rounded.toDouble())).toInt()
        if (!scientific && exponent in MIN_PLAIN_EXPONENT until MAX_PLAIN_EXPONENT) {
            result.append(NumberNode(rounded.stripTrailingZeros().toPlainString()))
            return
        }
        val mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
        val exponentString = MathString()
        if (exponent < 0) exponentString.append(OperatorNode(MathOp.MINUS))
        exponentString.append(NumberNode(abs(exponent).toString()))
        result.append(NumberNode(mantissa))
        result.append(SciExponentNode(exponentString))
    }

    private fun writePower(power: Power, result: MathString) {
        val exp = power.exponent
        if (exp is Value && exp.rational != null) {
            val r = exp.rational
            if (r == Rational.HALF) {
                result.append(RadicalNode(null, writeExpr(power.base)))
                return
            }
            if (r.numerator == BigInt.ONE && r.denominator > BigInt.ONE) {
                val degStr = MathString(listOf(NumberNode(r.denominator.toString())))
                result.append(RadicalNode(degStr, writeExpr(power.base)))
                return
            }
        }
        val baseStr = writeExpr(power.base)
        val base = if (needsParenthesesAsBase(power.base)) MathString(listOf(ParenthesesNode(baseStr))) else baseStr
        result.append(ExponentNode(base, writeExpr(power.exponent)))
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
            result.appendAll(writeExpr(negatedForDisplay(mult)))
            return
        }

        // Quantities: write the magnitude, then the unit (e.g. "1.5 m", "1/3 km/h")
        val unitFactors = mult.operands.filter { isUnitFactor(it) }
        if (unitFactors.isNotEmpty() && unitFactors.size < mult.operands.size) {
            val magnitude = mult.operands.filterNot { isUnitFactor(it) }
            val single = magnitude.singleOrNull()
            val rational = (single as? Value)?.rational
            val decimal = rational?.let { terminatingDecimal(it) }
            when {
                decimal != null -> result.append(NumberNode(decimal))
                // Quantities like 6625000/381 ft read better as a decimal; 1/3 m stays exact
                rational != null && rational.denominator > BigInt(MAX_QUANTITY_DENOMINATOR) ->
                    writeReal(rational.toDouble(), result)
                single is Additions -> result.append(ParenthesesNode(writeExpr(single)))
                else -> result.appendAll(writeExpr(Multiplications.of(*magnitude.toTypedArray())))
            }
            result.appendAll(writeExpr(Multiplications.of(*unitFactors.toTypedArray())))
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
            result.append(FractionNode(writeExpr(numExpr), writeExpr(denExpr)))
        } else if (numerators.isEmpty()) {
            result.append(NumberNode("1"))
        } else {
            writeFactors(numerators, result)
        }
    }

    private fun writeFactors(factors: List<Expression>, result: MathString) {
        for ((idx, op) in factors.withIndex()) {
            var nodes = writeExpr(op)
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
            val nodes = writeExpr(term)
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

    private fun isUnitFactor(expr: Expression): Boolean =
        expr is UnitExpression || (expr is Power && expr.base is UnitExpression)

    /**
     * The exact decimal form of a (non-negative) rational whose decimal expansion terminates,
     * e.g. 1143/1250 -> "0.9144"; null for 1/3 or if it would be unreasonably long.
     */
    private fun terminatingDecimal(r: Rational): String? {
        if (!r.isFractional || r.isNegative) return null
        var den = r.denominator.value
        val two = BigInteger.valueOf(2)
        val five = BigInteger.valueOf(5)
        while (den.mod(two).signum() == 0) den = den.divide(two)
        while (den.mod(five).signum() == 0) den = den.divide(five)
        if (den != BigInteger.ONE) return null
        val text = BigDecimal(r.numerator.value).divide(BigDecimal(r.denominator.value)).stripTrailingZeros().toPlainString()
        return text.takeIf { it.length <= MAX_EXACT_DECIMAL_LENGTH }
    }

    private fun MathString.append(node: MathNode) = insert(length, node)
    private fun MathString.appendAll(other: MathString) = insertAll(length, other.nodes)

    private companion object {
        const val SIGNIFICANT_DIGITS = 10
        const val MAX_EXACT_DECIMAL_LENGTH = 16
        const val MAX_QUANTITY_DENOMINATOR = 100L
        const val MIN_PLAIN_EXPONENT = -6
        const val MAX_PLAIN_EXPONENT = 12
    }
}
