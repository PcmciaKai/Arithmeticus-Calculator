package com.vortessence.arithmeticus.ui.math

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether numbers are shown with a decimal comma (1,5) instead of a point (1.5). Display only: the
 * text of the expression, the engine and the saved history always use the point. It is its own
 * setting and does not follow the language, since the point is common in scientific work everywhere.
 *
 * Compose state, so every view that reads it updates when the setting changes, and the graph
 * labels, which are drawn outside of composition, can read it as well.
 */
object DecimalMark {
    var comma by mutableStateOf(false)

    /** The text of a number as it is shown: "1.5" is "1,5" with a decimal comma. */
    fun display(number: String): String = if (comma) number.replace('.', ',') else number

    /** What separates the arguments of a function. A comma would be ambiguous next to decimal commas. */
    val argumentSeparator: String get() = if (comma) "; " else ", "
}
