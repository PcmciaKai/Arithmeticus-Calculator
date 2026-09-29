package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext

class Constant(
    val name: String,
    val value: Value
) : Expression() {

    override fun canonicalize(context: MathContext): Expression {
        return if (context.mode == CalculationMode.NUMERIC) {
            value.canonicalize(context)
        } else {
            this
        }
    }

    override fun evaluateNumerically(value: Double, variable: Variable?): Double {
        return this.value.evaluateNumerically(value, variable)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Constant) return false
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        val PI = Constant("π", Value.PI)
        val E = Constant("e", Value.E)
        val PHI = Constant("φ", Value(1.618033988749895))
        val SQRT2 = Constant("√2", Value(1.414213562373095))
        val C = Constant("c", Value(299792458.0))
        val H = Constant("h", Value(6.62607015e-34))
        val H_BAR = Constant("ħ", Value(1.054571817e-34))
        val G = Constant("G", Value(6.67430e-11))
        val GRAVITY = Constant("g", Value(9.80665))
        val ELEMENTARY_CHARGE = Constant("q_e", Value(1.602176634e-19))
        val BOLTZMANN = Constant("k_B", Value(1.380649e-23))
        val AVOGADRO = Constant("N_A", Value(6.02214076e23))
        val GAS_CONSTANT = Constant("R", Value(8.314462618))
        val ELECTRON_MASS = Constant("m_e", Value(9.1093837015e-31))
        val PROTON_MASS = Constant("m_p", Value(1.67262192369e-27))

        private val allConstants = listOf(
            PI, E, PHI, SQRT2, C, H, H_BAR, G, GRAVITY,
            ELEMENTARY_CHARGE, BOLTZMANN, AVOGADRO, GAS_CONSTANT,
            ELECTRON_MASS, PROTON_MASS
        )

        fun findByName(name: String): Constant? {
            return when (name.lowercase()) {
                "π", "pi" -> PI
                "e" -> E
                "φ", "phi" -> PHI
                "√2", "sqrt2" -> SQRT2
                "c" -> C
                "h" -> H
                "ħ", "hbar" -> H_BAR
                "g" -> if (name == "G") G else GRAVITY
                "k_b", "kb" -> BOLTZMANN
                "n_a", "na" -> AVOGADRO
                "r" -> GAS_CONSTANT
                "m_e", "me" -> ELECTRON_MASS
                "m_p", "mp" -> PROTON_MASS
                else -> allConstants.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
        }
    }
}
