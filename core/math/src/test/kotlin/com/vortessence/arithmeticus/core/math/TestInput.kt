package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.type.MathNotation
import com.vortessence.arithmeticus.core.math.type.MathString

/** Test input as short text, see [MathNotation]. */
internal object TestInput {

    fun parse(text: String): MathString = MathNotation.parse(text)
}
