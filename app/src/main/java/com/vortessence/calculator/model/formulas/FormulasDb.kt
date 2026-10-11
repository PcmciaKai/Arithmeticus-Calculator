package com.vortessence.calculator.model.formulas

import androidx.annotation.StringRes
import com.vortessence.calculator.R
import com.vortessence.calculator.core.math.type.*

/**
 * A ready-made expression for the formula picker. [template] is inserted at the cursor, which
 * then goes to its first empty slot (or before its first comma), ready to type the values.
 */
data class Formula(
    @StringRes val name: Int,
    @StringRes val description: Int,
    @StringRes val category: Int,
    val template: MathString
)

object FormulasDb {
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())

    /** name(), for a list of values separated by commas. */
    private fun ofValues(name: String) = ms(FunctionNode(name))

    /** name(, ): exactly two values; the cursor starts before the comma. */
    private fun ofTwo(name: String) = ms(FunctionNode(name, ms(SeparatorNode)))

    val items = listOf(
        Formula(R.string.formula_mean_name, R.string.formula_mean_desc, R.string.category_statistics, ofValues("mean")),
        Formula(R.string.formula_median_name, R.string.formula_median_desc, R.string.category_statistics, ofValues("median")),
        Formula(R.string.formula_stdev_name, R.string.formula_stdev_desc, R.string.category_statistics, ofValues("stdev")),
        Formula(R.string.formula_stdevp_name, R.string.formula_stdevp_desc, R.string.category_statistics, ofValues("stdevp")),
        Formula(R.string.formula_var_name, R.string.formula_var_desc, R.string.category_statistics, ofValues("var")),
        Formula(R.string.formula_varp_name, R.string.formula_varp_desc, R.string.category_statistics, ofValues("varp")),
        Formula(R.string.formula_sem_name, R.string.formula_sem_desc, R.string.category_statistics, ofValues("sem")),
        Formula(R.string.formula_min_name, R.string.formula_min_desc, R.string.category_statistics, ofValues("min")),
        Formula(R.string.formula_max_name, R.string.formula_max_desc, R.string.category_statistics, ofValues("max")),
        Formula(R.string.formula_ncr_name, R.string.formula_ncr_desc, R.string.category_combinatorics, ofTwo("nCr")),
        Formula(R.string.formula_npr_name, R.string.formula_npr_desc, R.string.category_combinatorics, ofTwo("nPr")),
        Formula(R.string.formula_gcd_name, R.string.formula_gcd_desc, R.string.category_numbers, ofValues("gcd")),
        Formula(R.string.formula_lcm_name, R.string.formula_lcm_desc, R.string.category_numbers, ofValues("lcm")),
        Formula(R.string.formula_round_name, R.string.formula_round_desc, R.string.category_numbers, ofValues("round")),
        Formula(R.string.formula_round_places_name, R.string.formula_round_places_desc, R.string.category_numbers, ofTwo("round")),
        Formula(R.string.formula_floor_name, R.string.formula_floor_desc, R.string.category_numbers, ofValues("floor")),
        Formula(R.string.formula_ceil_name, R.string.formula_ceil_desc, R.string.category_numbers, ofValues("ceil"))
    )
}
