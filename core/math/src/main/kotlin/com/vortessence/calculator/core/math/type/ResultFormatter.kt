package com.vortessence.calculator.core.math.type

import com.vortessence.calculator.core.math.CalculationResult
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Value
import java.math.BigInteger
import kotlin.math.abs

/** The ways a result can be shown. */
enum class ResultForm {
    /** Exact form: fractions, roots, π, ... */
    EXACT,
    /** Decimal number with up to 10 significant digits. */
    DECIMAL,
    /** Scientific notation, mantissa ×10ⁿ. */
    SCIENTIFIC,
    /** Mixed number such as 3 ½ (only for fractions greater than one). */
    MIXED,
    /** Polar form r·e^(iθ), exact (only for complex numbers). */
    POLAR,
    /** A whole number in hexadecimal, 0xFF (see [NumberBase]). */
    HEX,
    /** A whole number in octal, 0o377. */
    OCT,
    /** A whole number in binary, 0b11111111. */
    BIN;

    /** The base this form writes whole numbers in, or null for the other forms. */
    val numberBase: NumberBase? get() = when (this) {
        HEX -> NumberBase.HEXADECIMAL
        OCT -> NumberBase.OCTAL
        BIN -> NumberBase.BINARY
        else -> null
    }
}

/** Formats a [CalculationResult] in a [ResultForm], e.g. for the live result and the history. */
object ResultFormatter {
    /** The forms that make sense for [result]; [ResultForm.EXACT] is always included. */
    fun availableForms(result: CalculationResult): List<ResultForm> {
        if (!result.isSuccess) return emptyList()
        if (isComplex(result)) {
            return listOfNotNull(ResultForm.EXACT, ResultForm.DECIMAL, ResultForm.SCIENTIFIC, ResultForm.POLAR.takeIf { result.polar != null })
        }
        // Equations (possibly with several solutions) and true/false are only shown exactly
        val isSingleValue = result.expression !is Equals && result.numericValue.isFinite()
        if (!isSingleValue) return listOf(ResultForm.EXACT)
        return buildList {
            add(ResultForm.EXACT)
            add(ResultForm.DECIMAL)
            add(ResultForm.SCIENTIFIC)
            if (mixedParts(result) != null) add(ResultForm.MIXED)
            if (wholeNumber(result) != null) addAll(listOf(ResultForm.HEX, ResultForm.OCT, ResultForm.BIN))
        }
    }

    /**
     * [result] shown in [form], or in its exact form if [form] does not apply to it.
     * Quantities keep their unit, e.g. 48.94371097 L.
     */
    fun format(result: CalculationResult, form: ResultForm): MathString {
        val effective = if (form in availableForms(result)) form else ResultForm.EXACT
        if (effective == ResultForm.POLAR) return result.polar!!
        if (effective != ResultForm.EXACT && isComplex(result)) {
            return writeComplex(MathWriter(result.decimalPlaces), result.numericValue, result.numericImaginary, effective == ResultForm.SCIENTIFIC)
        }
        effective.numberBase?.let { base -> wholeNumber(result)?.let { return writeInBase(it, base) } }
        val formatted = when (effective) {
            ResultForm.EXACT -> return result.result
            ResultForm.DECIMAL -> MathWriter(result.decimalPlaces).writeNumber(result.numericValue)
            ResultForm.SCIENTIFIC -> MathWriter(result.decimalPlaces).writeNumber(result.numericValue, scientific = true)
            ResultForm.POLAR, ResultForm.HEX, ResultForm.OCT, ResultForm.BIN -> error("handled above")
            ResultForm.MIXED -> {
                // The same node as typed with the mixed number key, so it reads back as one value
                val (whole, numerator, denominator) = mixedParts(result)!!
                val mixed = MixedFractionNode(
                    MathString(listOf(NumberNode(whole.removePrefix("-")))),
                    MathString(listOf(NumberNode(numerator))),
                    MathString(listOf(NumberNode(denominator)))
                )
                MathString(if (whole.startsWith("-")) listOf(OperatorNode(MathOp.MINUS), mixed) else listOf(mixed))
            }
        }
        result.unit?.let { formatted.insertAll(formatted.length, it.copy().nodes) }
        return formatted
    }

    /** The whole number [result] is (without a unit), or null: only those can be written in another base. */
    private fun wholeNumber(result: CalculationResult): BigInteger? {
        if (result.unit != null) return null
        val rational = (result.expression as? Value)?.rational ?: return null
        return rational.takeIf { !it.isFractional }?.numerator?.value
    }

    /** [number] as 0xFF: the sign, the prefix and the digits in capitals. */
    private fun writeInBase(number: BigInteger, base: NumberBase): MathString {
        val out = MathString()
        if (number.signum() < 0) out.insert(out.length, OperatorNode(MathOp.MINUS))
        out.insert(out.length, NumberNode(base.prefix + number.abs().toString(base.radix).uppercase()))
        return out
    }

    private fun isComplex(result: CalculationResult): Boolean =
        result.expression !is Equals && result.numericImaginary != 0.0 &&
            result.numericImaginary.isFinite() && result.numericValue.isFinite()

    /** re + im·i as decimals, e.g. 0.5 − 1.732050808i. */
    private fun writeComplex(writer: MathWriter, re: Double, im: Double, scientific: Boolean): MathString {
        val out = MathString()
        fun append(nodes: MathString) = out.insertAll(out.length, nodes.nodes)
        if (re != 0.0) append(writer.writeNumber(re, scientific))
        if (re != 0.0 || im < 0.0) out.insert(out.length, OperatorNode(if (im < 0.0) MathOp.MINUS else MathOp.PLUS))
        if (abs(im) != 1.0) append(writer.writeNumber(abs(im), scientific))
        out.insert(out.length, ConstantNode("i"))
        return out
    }

    /** (whole part with sign, numerator, denominator) of an improper fraction, else null. */
    private fun mixedParts(result: CalculationResult): Triple<String, String, String>? {
        val r = (result.expression as? Value)?.rational ?: return null
        if (!r.isFractional || r.numerator.abs() <= r.denominator) return null
        val whole = r.numerator / r.denominator
        val remainder = (r.numerator % r.denominator).abs()
        return Triple(whole.toString(), remainder.toString(), r.denominator.toString())
    }
}
