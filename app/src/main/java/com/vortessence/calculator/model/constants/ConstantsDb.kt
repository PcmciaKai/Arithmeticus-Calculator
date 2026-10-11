package com.vortessence.calculator.model.constants

import androidx.annotation.StringRes
import com.vortessence.calculator.R

data class MathConstant(
    val symbol: String,
    @StringRes val name: Int,
    @StringRes val category: Int,
    val valueString: String,
    val unit: String? = null
)

object ConstantsDb {
    val items = listOf(
        // Mathematical
        MathConstant("π", R.string.constant_pi, R.string.constant_category_math, "3.141592653589793"),
        MathConstant("e", R.string.constant_e, R.string.constant_category_math, "2.718281828459045"),
        MathConstant("φ", R.string.constant_phi, R.string.constant_category_math, "1.618033988749895"),
        MathConstant("√2", R.string.constant_sqrt2, R.string.constant_category_math, "1.414213562373095"),

        // Physics
        MathConstant("c", R.string.constant_c, R.string.constant_category_physics, "299792458", "m/s"),
        MathConstant("h", R.string.constant_h, R.string.constant_category_physics, "6.62607015e-34", "J·s"),
        MathConstant("ħ", R.string.constant_hbar, R.string.constant_category_physics, "1.054571817e-34", "J·s"),
        MathConstant("G", R.string.constant_gravitation, R.string.constant_category_physics, "6.67430e-11", "m³/(kg·s²)"),
        MathConstant("g", R.string.constant_g, R.string.constant_category_physics, "9.80665", "m/s²"),

        // Electromagnetism
        MathConstant("q_e", R.string.constant_qe, R.string.constant_category_em, "1.602176634e-19", "C"),
        MathConstant("ε₀", R.string.constant_eps0, R.string.constant_category_em, "8.8541878128e-12", "F/m"),
        MathConstant("μ₀", R.string.constant_mu0, R.string.constant_category_em, "1.25663706212e-6", "H/m"),
        MathConstant("k_e", R.string.constant_ke, R.string.constant_category_em, "8.9875517923e9", "N·m²/C²"),

        // Thermodynamics
        MathConstant("k_B", R.string.constant_kb, R.string.constant_category_thermo, "1.380649e-23", "J/K"),
        MathConstant("R", R.string.constant_gasconstant, R.string.constant_category_thermo, "8.314462618", "J/(mol·K)"),
        MathConstant("σ", R.string.constant_sigma, R.string.constant_category_thermo, "5.670374419e-8", "W/(m²·K⁴)"),
        MathConstant("b", R.string.constant_wien, R.string.constant_category_thermo, "2.897771955e-3", "m·K"),

        // Chemistry
        MathConstant("N_A", R.string.constant_na, R.string.constant_category_chemistry, "6.02214076e23", "mol⁻¹"),
        MathConstant("F", R.string.constant_faraday, R.string.constant_category_chemistry, "96485.33212", "C/mol"),
        MathConstant("V_m", R.string.constant_vm, R.string.constant_category_chemistry, "22.41396954", "L/mol"),
        MathConstant("m_u", R.string.constant_mu, R.string.constant_category_chemistry, "1.66053906660e-27", "kg"),

        // Atomic
        MathConstant("m_e", R.string.constant_me, R.string.constant_category_atomic, "9.1093837015e-31", "kg"),
        MathConstant("m_p", R.string.constant_mp, R.string.constant_category_atomic, "1.67262192369e-27", "kg"),
        MathConstant("m_n", R.string.constant_mn, R.string.constant_category_atomic, "1.67492749804e-27", "kg"),
        MathConstant("α", R.string.constant_alpha, R.string.constant_category_atomic, "7.2973525693e-3"),
        MathConstant("a₀", R.string.constant_a0, R.string.constant_category_atomic, "5.29177210903e-11", "m"),
        MathConstant("R_∞", R.string.constant_rinf, R.string.constant_category_atomic, "10973731.568160", "m⁻¹"),
        MathConstant("μ_B", R.string.constant_mub, R.string.constant_category_atomic, "9.2740100783e-24", "J/T")
    )
}
