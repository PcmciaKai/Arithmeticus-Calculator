package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.type.MathNode
import com.vortessence.calculator.core.math.type.MathString

/** A node written as text, for tests that read a result line by line. */
object MathStringText {
    fun of(node: MathNode): String = MathString(listOf(node)).toString()
}
