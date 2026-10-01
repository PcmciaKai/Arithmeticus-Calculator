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

        // Physics
        MathConstant("c", "Speed of light in vacuum", "Physics", "299792458", "m/s"),
        MathConstant("h", "Planck constant", "Physics", "6.62607015e-34", "J·s"),
        MathConstant("ħ", "Reduced Planck constant", "Physics", "1.054571817e-34", "J·s"),
        MathConstant("G", "Newtonian constant of gravitation", "Physics", "6.67430e-11", "m³/(kg·s²)"),
        MathConstant("g", "Standard acceleration of gravity", "Physics", "9.80665", "m/s²"),

        // Electromagnetism
        MathConstant("q_e", "Elementary charge", "Electromagnetism", "1.602176634e-19", "C"),
        MathConstant("ε₀", "Vacuum electric permittivity", "Electromagnetism", "8.8541878128e-12", "F/m"),
        MathConstant("μ₀", "Vacuum magnetic permeability", "Electromagnetism", "1.25663706212e-6", "H/m"),
        MathConstant("k_e", "Coulomb constant", "Electromagnetism", "8.9875517923e9", "N·m²/C²"),

        // Thermodynamics
        MathConstant("k_B", "Boltzmann constant", "Thermodynamics", "1.380649e-23", "J/K"),
        MathConstant("R", "Molar gas constant", "Thermodynamics", "8.314462618", "J/(mol·K)"),
        MathConstant("σ", "Stefan–Boltzmann constant", "Thermodynamics", "5.670374419e-8", "W/(m²·K⁴)"),
        MathConstant("b", "Wien displacement constant", "Thermodynamics", "2.897771955e-3", "m·K"),

        // Chemistry
        MathConstant("N_A", "Avogadro constant", "Chemistry", "6.02214076e23", "mol⁻¹"),
        MathConstant("F", "Faraday constant", "Chemistry", "96485.33212", "C/mol"),
        MathConstant("V_m", "Molar volume of ideal gas (0 °C, 1 atm)", "Chemistry", "22.41396954", "L/mol"),
        MathConstant("m_u", "Atomic mass constant", "Chemistry", "1.66053906660e-27", "kg"),

        // Atomic
        MathConstant("m_e", "Electron mass", "Atomic", "9.1093837015e-31", "kg"),
        MathConstant("m_p", "Proton mass", "Atomic", "1.67262192369e-27", "kg"),
        MathConstant("m_n", "Neutron mass", "Atomic", "1.67492749804e-27", "kg"),
        MathConstant("α", "Fine-structure constant", "Atomic", "7.2973525693e-3"),
        MathConstant("a₀", "Bohr radius", "Atomic", "5.29177210903e-11", "m"),
        MathConstant("R_∞", "Rydberg constant", "Atomic", "10973731.568160", "m⁻¹"),
        MathConstant("μ_B", "Bohr magneton", "Atomic", "9.2740100783e-24", "J/T")
    )
}
