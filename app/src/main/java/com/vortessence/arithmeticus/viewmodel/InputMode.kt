package com.vortessence.arithmeticus.viewmodel

import com.vortessence.arithmeticus.core.math.type.NumberBase
import com.vortessence.arithmeticus.core.math.type.ResultForm

/**
 * How the keypad is set up: the usual keys with decimal numbers, or the programmer keys (A to F, AND, OR, ...) with numbers
 * typed in [base] and results shown in it. Whole numbers are written with their prefix in either, 0xFF, so the mode only
 * decides which keys there are and what the digit keys type.
 */
enum class InputMode(val base: NumberBase, val programmer: Boolean, val label: String) {
    DECIMAL(NumberBase.DECIMAL, false, "DEC"),
    HEXADECIMAL(NumberBase.HEXADECIMAL, true, "HEX"),
    OCTAL(NumberBase.OCTAL, true, "OCT"),
    BINARY(NumberBase.BINARY, true, "BIN");

    /** The form results are shown in while this mode is on. */
    val resultForm: ResultForm get() = when (base) {
        NumberBase.HEXADECIMAL -> ResultForm.HEX
        NumberBase.OCTAL -> ResultForm.OCT
        NumberBase.BINARY -> ResultForm.BIN
        NumberBase.DECIMAL -> ResultForm.EXACT
    }

    /** Whether the digit key [digit] (0 to 9, A to F) types something in this mode; the others are dimmed. */
    fun hasDigit(digit: String): Boolean = base.isDigit(digit.single())
}
