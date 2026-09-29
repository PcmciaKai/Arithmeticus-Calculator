package com.vortessence.calculator.model.constants

data class MathConstant(
    val symbol: String,
    val name: String,
    val category: String,
    val valueString: String,
    val unit: String? = null
)

object ConstantsDb {
    val items = listOf(
        // Mathematical
        MathConstant("π", "Pi (Archimedes' constant)", "Mathematical", "3.141592653589793"),
        MathConstant("e", "Euler's number", "Mathematical", "2.718281828459045"),
        MathConstant("φ", "Golden ratio", "Mathematical", "1.618033988749895"),
        MathConstant("√2", "Pythagoras' constant", "Mathematical", "1.414213562373095"),

        // Universal Physical
        MathConstant("c", "Speed of light in vacuum", "Physics", "299792458", "m/s"),
        MathConstant("h", "Planck constant", "Physics", "6.62607015e-34", "J·s"),
        MathConstant("ħ", "Reduced Planck constant", "Physics", "1.054571817e-34", "J·s"),
        MathConstant("G", "Newtonian constant of gravitation", "Physics", "6.67430e-11", "m³/(kg·s²)"),
        MathConstant("g", "Standard acceleration of gravity", "Physics", "9.80665", "m/s²"),

        // Electromagnetic & Atomic
        MathConstant("q_e", "Elementary charge", "Electromagnetism", "1.602176634e-19", "C"),
        MathConstant("k_B", "Boltzmann constant", "Thermodynamics", "1.380649e-23", "J/K"),
        MathConstant("N_A", "Avogadro constant", "Chemistry", "6.02214076e23", "mol⁻¹"),
        MathConstant("R", "Molar gas constant", "Thermodynamics", "8.314462618", "J/(mol·K)"),
        MathConstant("m_e", "Electron mass", "Atomic", "9.1093837015e-31", "kg"),
        MathConstant("m_p", "Proton mass", "Atomic", "1.67262192369e-27", "kg")
    )
}
