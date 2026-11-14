package com.vortessence.arithmeticus.viewmodel

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.CalculationMode
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.UnitSystem
import com.vortessence.arithmeticus.core.math.i18n.Language
import com.vortessence.arithmeticus.core.math.type.AnsNode
import com.vortessence.arithmeticus.core.math.type.AntiderivativeNode
import com.vortessence.arithmeticus.core.math.type.DerivativeNode
import com.vortessence.arithmeticus.core.math.type.EvaluationNode
import com.vortessence.arithmeticus.core.math.type.FunctionDefinitions
import com.vortessence.arithmeticus.core.math.type.IntegralNode
import com.vortessence.arithmeticus.core.math.type.MathString
import com.vortessence.arithmeticus.core.math.type.UnitNode
import com.vortessence.arithmeticus.core.math.type.VariableNode

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
    val complexNumbers: Boolean,
    /** The language of the texts the calculation produces; not part of a saved entry: those are calculated in the language of the day. */
    val language: Language = Language.ENGLISH,
    /**
     * The seed of the line: its random numbers are a function of it, so a history entry that is calculated again (after the
     * app was closed, or after a setting changed) has the numbers it had.
     */
    val randomSeed: Long = 0L,
    /** Temperatures in °C and °F as points on their scale instead of in kelvin (see MathContext.physicalTemperatures). */
    val physicalTemperatures: Boolean = false
) {
    // Always exact; decimal display is a matter of the result form
    fun toMathContext() = MathContext(
        mode = CalculationMode.EXACT,
        angleUnit = angleUnit,
        unitSystem = unitSystem,
        decimalPlaces = decimalPlaces,
        ans = ans,
        variables = variables,
        complexNumbers = complexNumbers,
        language = language,
        randomSeed = randomSeed,
        physicalTemperatures = physicalTemperatures
    )

    /**
     * This snapshot reduced to what calculating [input] can depend on: the variables it uses
     * (and the ones those use) and Ans if it is used. The result is the same, but a saved
     * history does not carry every defined variable along with every entry.
     */
    fun relevantTo(input: MathString): CalculationSnapshot {
        val names = mutableSetOf<String>()
        var usesAns = false
        var usesShiftedScale = false
        fun collect(string: MathString) {
            for (node in string.nodes) {
                when (node) {
                    is AnsNode -> usesAns = true
                    is UnitNode -> if (node.symbol in SHIFTED_TEMPERATURE_SCALES) usesShiftedScale = true
                    is VariableNode -> names.add(node.name)
                    // The variable of an integral, ... is a name too: a defined variable of that name would change it
                    is IntegralNode -> names.add(node.variable)
                    is AntiderivativeNode -> names.add(node.variable)
                    is DerivativeNode -> names.add(node.variable)
                    is EvaluationNode -> names.add(node.variable)
                    else -> {}
                }
                node.subStrings.forEach(::collect)
            }
        }
        collect(input)
        if (usesAns) ans?.let(::collect)
        // A variable's value can use other variables; a function f is kept as "f(x)"
        val keys = variables.keys.associateBy { FunctionDefinitions.nameOf(it) }
        val used = mutableSetOf<String>()
        val pending = ArrayDeque(names)
        while (pending.isNotEmpty()) {
            val key = keys[pending.removeFirst()] ?: continue
            val value = variables[key] ?: continue
            if (!used.add(key)) continue
            names.clear()
            collect(value)
            pending.addAll(names)
        }
        return copy(
            ans = ans.takeIf { usesAns }, variables = variables.filterKeys { it in used },
            // Only where there is a °C or °F that it is about
            physicalTemperatures = physicalTemperatures && usesShiftedScale
        )
    }

    private companion object {
        val SHIFTED_TEMPERATURE_SCALES = setOf("°C", "°F")
    }
}
