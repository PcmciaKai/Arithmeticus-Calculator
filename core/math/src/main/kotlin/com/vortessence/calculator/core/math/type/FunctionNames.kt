package com.vortessence.calculator.core.math.type

import com.vortessence.calculator.core.math.expressions.NamedFunctionKind
import com.vortessence.calculator.core.math.expressions.RandomKind
import com.vortessence.calculator.core.math.expressions.VectorKind

/**
 * The names of the functions that can be typed (the name, then "("), e.g. sin(, mean( or nCr(.
 * Names are matched ignoring case; [endingWord] gives the function as it is written in a
 * [FunctionNode] (or "sqrt", which is a root). Names that start with i (im, isprime) cannot be
 * typed: a typed i is refused at once as the imaginary unit, and waiting to see whether a name
 * follows would leave the key looking dead. They are in the formula picker.
 */
object FunctionNames {
    private val names: List<String> = (
        listOf(
            "sin", "cos", "tan", "sec", "csc", "cot",
            "asin", "acos", "atan", "asec", "acsc", "acot",
            "sinh", "cosh", "tanh", "sech", "csch", "coth",
            "asinh", "acosh", "atanh", "asech", "acsch", "acoth",
            "arcsin", "arccos", "arctan",
            "ln", "log", "abs", "sqrt", "cbrt", "exp", "deg", "rad", "gamma", "lngamma", "digamma", "erf", "erfc", "lambertw", "sinint", "cosint", "expint", "det", "rref", "eig"
        ) + NamedFunctionKind.entries.map { it.functionName } + RandomKind.entries.map { it.functionName } + VectorKind.entries.map { it.functionName }
        ).filterNot { it.startsWith('i') }.sortedByDescending { it.length }

    /**
     * The symbols that stand for some special functions, as results write them and as the keys and the function picker insert
     * them: Γ for gamma, and so on. The parser takes either one; the names are what can be typed and pasted.
     */
    val symbols: Map<String, String> = mapOf(
        "gamma" to "Γ", "lngamma" to "lnΓ", "digamma" to "ψ", "lambertw" to "W", "sinint" to "Si", "cosint" to "Ci", "expint" to "Ei"
    )

    /** How a function called [name] is written in the expression: its symbol if it has one (Γ for gamma), else [name]. */
    fun written(name: String): String = symbols[name] ?: name

    /** The longest function name that [word] ends with (so "asin" and not "sin", "xsin" has "sin"), or null. */
    fun endingWord(word: String): String? = names.firstOrNull { word.endsWith(it, ignoreCase = true) }

    /**
     * Whether the end of [letters] could be the start of a function name, e.g. "xsi" (for "sin"):
     * more letters may still turn it into a function.
     */
    fun endsWithNameStart(letters: String): Boolean =
        letters.indices.any { start ->
            val tail = letters.substring(start)
            names.any { it.length > tail.length && it.startsWith(tail, ignoreCase = true) }
        }
}
