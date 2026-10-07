package com.vortessence.calculator.core.math.type

import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.math.MathContext as DecimalContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * Converts an [Expression] into a [MathString] for display. The output only uses nodes the
 * parser understands (apart from [TextNode]), so results can be reused as input.
 *
 * @param decimalPlaces Decimal numbers are shown with exactly this many decimal places
 * (e.g. 3.50 for 2); `null` means automatic: up to 10 significant digits, no trailing zeros.
 * @param lossless Write numbers without any rounding (exact fractions, all digits of floating
 * point values), for results that are reused as input, e.g. via "Ans".
 */
class MathWriter(
    private val decimalPlaces: Int? = null,
    private val lossless: Boolean = false
) {

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
            is ImaginaryUnit -> result.append(ConstantNode("i"))
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
            is EquationSystem -> {
                for ((idx, line) in expr.lines.withIndex()) {
                    if (idx > 0) result.append(LineBreakNode)
                    result.appendAll(write(line))
                }
            }
            is Integral -> {
                result.append(IntegralNode(
                    writeExpr(expr.lowerBound),
                    writeExpr(expr.upperBound),
                    writeExpr(expr.integrand),
                    expr.variable.identifier
                ))
            }
            is AbsoluteValue -> result.append(FunctionNode("abs", writeExpr(expr.operand)))
            is Antiderivative -> result.append(AntiderivativeNode(writeExpr(expr.integrand), expr.variable.identifier))
            is Derivative -> {
                result.append(DerivativeNode(
                    writeExpr(expr.body),
                    expr.point?.let { writeExpr(it) },
                    expr.variable.identifier
                ))
            }
            is Series -> {
                result.append(BigOperatorNode(
                    if (expr.isProduct) BigOperator.PRODUCT else BigOperator.SUM,
                    binding(expr.index, MathOp.EQUALS, expr.lower),
                    writeExpr(expr.upper),
                    writeExpr(expr.body)
                ))
            }
            is Limit -> result.append(LimitNode(binding(expr.variable, MathOp.CONVERT, expr.point), writeExpr(expr.body)))
            is Factorial -> {
                val operand = writeExpr(expr.operand)
                val isSimple = expr.operand is Variable || (expr.operand is Value && !expr.operand.isNegative && expr.operand.rational?.isFractional == false)
                result.appendAll(if (isSimple) operand else MathString(listOf(ParenthesesNode(operand))))
                result.append(OperatorNode(MathOp.FACTORIAL))
            }
            is Modulo -> {
                // mod binds like ×, so sums (and, on the right, products) need parentheses
                result.appendAll(parenthesizedIf(expr.dividend) { it is Additions || it is Equals })
                result.append(OperatorNode(MathOp.MODULO))
                result.appendAll(parenthesizedIf(expr.divisor) { it is Additions || it is Multiplications || it is Modulo || it is Equals })
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

    /** "n=1" below a sum, "x→0" below a limit. */
    private fun binding(variable: Variable, op: MathOp, value: Expression): MathString {
        val result = MathString(listOf(VariableNode(variable.identifier), OperatorNode(op)))
        result.appendAll(writeExpr(value))
        return result
    }

    private fun parenthesizedIf(expr: Expression, condition: (Expression) -> Boolean): MathString {
        val nodes = writeExpr(expr)
        return if (condition(expr)) MathString(listOf(ParenthesesNode(nodes))) else nodes
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
     * Writes a floating point number (see [decimalPlaces] and [lossless]). Very large or small
     * magnitudes, and with [scientific] all numbers, are written as mantissa ×10ⁿ.
     */
    fun writeNumber(d: Double, scientific: Boolean = false): MathString =
        MathString().also { writeReal(d, it, scientific) }

    private fun writeReal(d: Double, result: MathString, scientific: Boolean = false) {
        when {
            d.isNaN() -> return result.append(TextNode("undefined"))
            d.isInfinite() -> {
                if (d < 0) result.append(OperatorNode(MathOp.MINUS))
                return result.append(ConstantNode("∞"))
            }
        }
        if (d < 0) result.append(OperatorNode(MathOp.MINUS))
        val magnitude = abs(d)
        val places = decimalPlaces
        val (digits, exponent) = when {
            magnitude == 0.0 -> (if (places != null && !lossless) BigDecimal.ZERO.setScale(places).toPlainString() else "0") to null
            lossless -> losslessDigits(magnitude, scientific)
            places != null -> fixedDigits(magnitude, places, scientific)
            else -> automaticDigits(magnitude, scientific)
        }
        result.append(NumberNode(digits))
        if (exponent != null) {
            val exponentString = MathString()
            if (exponent < 0) exponentString.append(OperatorNode(MathOp.MINUS))
            exponentString.append(NumberNode(abs(exponent).toString()))
            result.append(SciExponentNode(exponentString))
        }
    }

    private fun isPlainExponent(exponent: Int) = exponent in MIN_PLAIN_EXPONENT until MAX_PLAIN_EXPONENT

    /** Up to 10 significant digits, no trailing zeros. Returns (digits, ×10 exponent or null). */
    private fun automaticDigits(magnitude: Double, scientific: Boolean): Pair<String, Int?> {
        if (!scientific && magnitude % 1.0 == 0.0 && magnitude < 1e15) {
            return BigDecimal(magnitude).toPlainString() to null
        }
        val rounded = BigDecimal(magnitude).round(DecimalContext(SIGNIFICANT_DIGITS))
        val exponent = floor(log10(rounded.toDouble())).toInt()
        if (!scientific && isPlainExponent(exponent)) return rounded.stripTrailingZeros().toPlainString() to null
        return rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString() to exponent
    }

    /** Exactly [places] decimal places (in the mantissa for scientific notation). */
    private fun fixedDigits(magnitude: Double, places: Int, scientific: Boolean): Pair<String, Int?> {
        val value = BigDecimal(magnitude)
        var exponent = floor(log10(magnitude)).toInt()
        if (!scientific && isPlainExponent(exponent)) {
            val rounded = value.setScale(places, RoundingMode.HALF_UP)
            // Would read as 0.00: show it in scientific notation instead of hiding the value
            if (rounded.signum() != 0) return rounded.toPlainString() to null
        }
        var mantissa = value.movePointLeft(exponent).setScale(places, RoundingMode.HALF_UP)
        if (mantissa >= BigDecimal.TEN) {
            exponent++ // e.g. 9.999 rounded to 10.00
            mantissa = value.movePointLeft(exponent).setScale(places, RoundingMode.HALF_UP)
        }
        return mantissa.toPlainString() to exponent
    }

    /** All digits needed to reproduce the double exactly (shortest round-trip form). */
    private fun losslessDigits(magnitude: Double, scientific: Boolean): Pair<String, Int?> {
        val value = BigDecimal(magnitude.toString()).stripTrailingZeros()
        val exponent = value.precision() - value.scale() - 1
        if (!scientific && isPlainExponent(exponent)) return value.toPlainString() to null
        return value.movePointLeft(exponent).toPlainString() to exponent
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
        is Additions, is Multiplications, is Power, is Equals, is Modulo -> true
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
            val decimal = rational?.let { terminatingDecimal(it, maxLength = if (lossless) null else MAX_EXACT_DECIMAL_LENGTH) }
            when {
                // Reusable results stay exact: exact decimals, otherwise fractions
                lossless && decimal == null && rational != null -> result.appendAll(writeExpr(single))
                // A fixed number of decimal places applies to quantities too
                !lossless && decimalPlaces != null && rational != null && rational.isFractional ->
                    writeReal(rational.toDouble(), result)
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
        // The usual order: 6x(x² + 1)² rather than 6(x² + 1)²x, 2xy rather than 2yx
        val ordered = factors.sortedWith(compareBy({ factorRank(it) }, { variableName(it) }))
        for ((idx, op) in ordered.withIndex()) {
            var nodes = writeExpr(op)
            if (op is Additions || op is Equals || op is Modulo) {
                nodes = MathString(listOf(ParenthesesNode(nodes)))
            }
            if (idx > 0 && needsExplicitTimes(nodes)) {
                result.append(OperatorNode(MathOp.MULTIPLY))
            }
            result.appendAll(nodes)
        }
    }

    /** Numbers, then constants (π), i, variables and their powers, functions, and sums last: 2πix. */
    private fun factorRank(factor: Expression): Int = when {
        factor is Value -> 0
        factor is Constant -> 1
        factor === ImaginaryUnit -> 2
        variableName(factor) != null -> 3
        factor is Additions || (factor is Power && factor.base is Additions) -> 5
        else -> 4
    }

    /** The variable of a factor x or xⁿ, which orders such factors alphabetically. */
    private fun variableName(factor: Expression): String? =
        ((if (factor is Power) factor.base else factor) as? Variable)?.identifier

    /** Juxtaposition would be ambiguous if the factor starts with digits (2·3, 2·10², 2·−x). */
    private fun needsExplicitTimes(factor: MathString): Boolean {
        return when (val first = factor.nodes.firstOrNull()) {
            is NumberNode, is OperatorNode, is FractionNode -> true
            is ExponentNode -> first.base?.nodes?.firstOrNull() is NumberNode
            else -> false
        }
    }

    private fun writeAdditions(add: Additions, result: MathString) {
        // Complex numbers in the usual form a + bi: real terms first
        val operands = add.operands.sortedBy { it.containsImaginaryUnit() }
        for ((idx, op) in operands.withIndex()) {
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
    private fun terminatingDecimal(r: Rational, maxLength: Int?): String? {
        if (!r.isFractional || r.isNegative) return null
        var den = r.denominator.value
        val two = BigInteger.valueOf(2)
        val five = BigInteger.valueOf(5)
        while (den.mod(two).signum() == 0) den = den.divide(two)
        while (den.mod(five).signum() == 0) den = den.divide(five)
        if (den != BigInteger.ONE) return null
        val text = BigDecimal(r.numerator.value).divide(BigDecimal(r.denominator.value)).stripTrailingZeros().toPlainString()
        return text.takeIf { maxLength == null || it.length <= maxLength }
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
