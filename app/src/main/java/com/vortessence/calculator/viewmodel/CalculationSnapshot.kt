package com.vortessence.calculator.viewmodel

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.UnitSystem
import com.vortessence.calculator.core.math.type.AnsNode
import com.vortessence.calculator.core.math.type.AntiderivativeNode
import com.vortessence.calculator.core.math.type.DerivativeNode
import com.vortessence.calculator.core.math.type.EvaluationNode
import com.vortessence.calculator.core.math.type.IntegralNode
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.VariableNode

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

    /**
     * This snapshot reduced to what calculating [input] can depend on: the variables it uses
     * (and the ones those use) and Ans if it is used. The result is the same, but a saved
     * history does not carry every defined variable along with every entry.
     */
    fun relevantTo(input: MathString): CalculationSnapshot {
        val names = mutableSetOf<String>()
        var usesAns = false
        fun collect(string: MathString) {
            for (node in string.nodes) {
                when (node) {
                    is AnsNode -> usesAns = true
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
        // A variable's value can use other variables
        val used = mutableSetOf<String>()
        val pending = ArrayDeque(names)
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            val value = variables[name] ?: continue
            if (!used.add(name)) continue
            names.clear()
            collect(value)
            pending.addAll(names)
        }
        return copy(ans = ans.takeIf { usesAns }, variables = variables.filterKeys { it in used })
    }
}
