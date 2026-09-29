package com.vortessence.calculator.core.math.type

import com.vortessence.calculator.core.math.CalculationResult
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Value

/** The ways a result can be shown. */
enum class ResultForm {
    /** Exact form: fractions, roots, π, ... */
    EXACT,
    /** Decimal number with up to 10 significant digits. */
    DECIMAL,
    /** Scientific notation, mantissa ×10ⁿ. */
    SCIENTIFIC,
    /** Mixed number such as 3 ½ (only for fractions greater than one). */
    MIXED
}

/** Formats a [CalculationResult] in a [ResultForm], e.g. for the live result and the history. */
object ResultFormatter {
    /** The forms that make sense for [result]; [ResultForm.EXACT] is always included. */
    fun availableForms(result: CalculationResult): List<ResultForm> {
        if (!result.isSuccess) return emptyList()
        // Equations (possibly with several solutions) and true/false are only shown exactly
        val isSingleValue = result.expression !is Equals && result.numericValue.isFinite()
        if (!isSingleValue) return listOf(ResultForm.EXACT)
        return buildList {
            add(ResultForm.EXACT)
            add(ResultForm.DECIMAL)
            add(ResultForm.SCIENTIFIC)
            if (mixedParts(result) != null) add(ResultForm.MIXED)
        }
    }

    /**
     * [result] shown in [form], or in its exact form if [form] does not apply to it.
     * Quantities keep their unit, e.g. 48.94371097 L.
     */
    fun format(result: CalculationResult, form: ResultForm): MathString {
        val effective = if (form in availableForms(result)) form else ResultForm.EXACT
        val formatted = when (effective) {
            ResultForm.EXACT -> return result.result
            ResultForm.DECIMAL -> MathWriter(result.decimalPlaces).writeNumber(result.numericValue)
            ResultForm.SCIENTIFIC -> MathWriter(result.decimalPlaces).writeNumber(result.numericValue, scientific = true)
            ResultForm.MIXED -> {
                val (whole, numerator, denominator) = mixedParts(result)!!
                MathString(listOf(
                    NumberNode(whole),
                    FractionNode(MathString(listOf(NumberNode(numerator))), MathString(listOf(NumberNode(denominator))))
                ))
            }
        }
        result.unit?.let { formatted.insertAll(formatted.length, it.copy().nodes) }
        return formatted
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
