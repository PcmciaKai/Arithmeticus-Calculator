package com.vortessence.calculator.viewmodel

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.UnitSystem
import com.vortessence.calculator.core.math.type.MathString

/**
 * Everything a calculation depends on besides its input: the calculation settings, Ans and the
 * defined variables. A history entry keeps the snapshot it was calculated with, so that it can
 * be calculated again identically after the app was closed.
 */
data class CalculationSnapshot(
    val angleUnit: AngleUnit,
    val unitSystem: UnitSystem,
    val decimalPlaces: Int?,
    val ans: MathString?,
    val variables: Map<String, MathString>,
    val complexNumbers: Boolean
) {
    // Always exact; decimal display is a matter of the result form
    fun toMathContext() = MathContext(
        mode = CalculationMode.EXACT,
        angleUnit = angleUnit,
        unitSystem = unitSystem,
        decimalPlaces = decimalPlaces,
        ans = ans,
        variables = variables,
        complexNumbers = complexNumbers
    )
}
