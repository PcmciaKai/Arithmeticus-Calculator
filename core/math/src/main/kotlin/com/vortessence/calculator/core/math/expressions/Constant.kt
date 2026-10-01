package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext

/**
 * A named constant. Mathematical constants (π, e) stay symbolic in exact mode. Physical
 * constants have a [unit] and always evaluate to their value with that unit, e.g.
 * R = 8.31446261815324 J/(mol·K), so that unit calculations with them are correct.
 *
 * @param unitSymbols The constant's unit as (unit symbol, exponent) pairs, e.g. J·mol⁻¹·K⁻¹.
 */
class Constant(
    val name: String,
    val value: Value,
    private val unitSymbols: List<Pair<String, Int>> = emptyList()
) : Expression() {

    /** Resolved lazily: the unit registry itself refers to constants (π for angle units). */
    val unit: Expression? by lazy {
        if (unitSymbols.isEmpty()) return@lazy null
        val factors = unitSymbols.map { (symbol, exponent) ->
            val unit = requireNotNull(UnitRegistry.findBySymbol(symbol)) { "Unknown unit $symbol" }
            if (exponent == 1) unit else Power(unit, Value(exponent.toLong()))
        }
        Multiplications.of(*factors.toTypedArray())
    }

    override fun canonicalize(context: MathContext): Expression {
        val unit = unit
        return when {
            unit != null -> Multiplications.of(value, unit).canonicalize(context)
            context.mode == CalculationMode.NUMERIC -> value.canonicalize(context)
            else -> this
        }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        return this.value.evaluateNumerically(bindings)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Constant) return false
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        private fun exact(decimal: String) = Value(exactDecimal(decimal))

        val PI = Constant("π", Value.PI)
        val E = Constant("e", Value.E)
        val PHI = Constant("φ", Value(1.618033988749895))
        val SQRT2 = Constant("√2", Value(1.414213562373095))

        // CODATA 2018; c, h, e, k_B and N_A are exact by definition of the SI (2019)
        val C = Constant("c", exact("299792458"), listOf("m/s" to 1))
        val H = Constant("h", exact("6.62607015e-34"), listOf("J" to 1, "s" to 1))
        val H_BAR = Constant("ħ", exact("1.054571817e-34"), listOf("J" to 1, "s" to 1))
        val G = Constant("G", exact("6.67430e-11"), listOf("m" to 3, "kg" to -1, "s" to -2))
        val GRAVITY = Constant("g", exact("9.80665"), listOf("m/s²" to 1))
        val ELEMENTARY_CHARGE = Constant("q_e", exact("1.602176634e-19"), listOf("C" to 1))
        val BOLTZMANN = Constant("k_B", exact("1.380649e-23"), listOf("J" to 1, "K" to -1))
        val AVOGADRO = Constant("N_A", exact("6.02214076e23"), listOf("mol" to -1))
        // R = N_A · k_B, exact
        val GAS_CONSTANT = Constant("R", exact("8.31446261815324"), listOf("J" to 1, "mol" to -1, "K" to -1))
        val ELECTRON_MASS = Constant("m_e", exact("9.1093837015e-31"), listOf("kg" to 1))
        val PROTON_MASS = Constant("m_p", exact("1.67262192369e-27"), listOf("kg" to 1))

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
