package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.type.MathNotation
import com.vortessence.calculator.core.math.type.MathString

/** Test input as short text, see [MathNotation]. */
internal object TestInput {

    fun parse(text: String): MathString = MathNotation.parse(text)
}
