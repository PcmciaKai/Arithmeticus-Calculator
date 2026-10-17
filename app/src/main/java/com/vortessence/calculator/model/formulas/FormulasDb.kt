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
    fun ofValues(name: String) = ms(FunctionNode(name))

    /** name(, ): exactly two values; the cursor starts before the comma. */
    fun ofTwo(name: String) = ms(FunctionNode(name, ms(SeparatorNode)))

    /** name(, , ): exactly three values; the cursor starts before the first comma. */
    private fun ofThree(name: String) = ms(FunctionNode(name, ms(SeparatorNode, SeparatorNode)))

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
        Formula(R.string.formula_mode_name, R.string.formula_mode_desc, R.string.category_statistics, ofValues("mode")),
        Formula(R.string.formula_range_name, R.string.formula_range_desc, R.string.category_statistics, ofValues("range")),
        Formula(R.string.formula_sum_name, R.string.formula_sum_desc, R.string.category_statistics, ofValues("sum")),
        Formula(R.string.formula_sumsq_name, R.string.formula_sumsq_desc, R.string.category_statistics, ofValues("sumsq")),
        Formula(R.string.formula_prod_name, R.string.formula_prod_desc, R.string.category_statistics, ofValues("prod")),
        Formula(R.string.formula_percentile_name, R.string.formula_percentile_desc, R.string.category_statistics, ofTwo("percentile")),
        Formula(R.string.formula_quartile_name, R.string.formula_quartile_desc, R.string.category_statistics, ofTwo("quartile")),
        Formula(R.string.formula_skew_name, R.string.formula_skew_desc, R.string.category_statistics, ofValues("skew")),
        Formula(R.string.formula_tmargin_name, R.string.formula_tmargin_desc, R.string.category_statistics, ofTwo("tmargin")),
        Formula(R.string.formula_tstat_name, R.string.formula_tstat_desc, R.string.category_statistics, ofTwo("tstat")),
        Formula(R.string.formula_ncr_name, R.string.formula_ncr_desc, R.string.category_combinatorics, ofTwo("nCr")),
        Formula(R.string.formula_npr_name, R.string.formula_npr_desc, R.string.category_combinatorics, ofTwo("nPr")),
        Formula(R.string.formula_multinomial_name, R.string.formula_multinomial_desc, R.string.category_combinatorics, ofValues("multinomial")),
        Formula(R.string.formula_fib_name, R.string.formula_fib_desc, R.string.category_combinatorics, ofValues("fib")),
        Formula(R.string.formula_gcd_name, R.string.formula_gcd_desc, R.string.category_numbers, ofValues("gcd")),
        Formula(R.string.formula_lcm_name, R.string.formula_lcm_desc, R.string.category_numbers, ofValues("lcm")),
        Formula(R.string.formula_round_name, R.string.formula_round_desc, R.string.category_numbers, ofValues("round")),
        Formula(R.string.formula_round_places_name, R.string.formula_round_places_desc, R.string.category_numbers, ofTwo("round")),
        Formula(R.string.formula_floor_name, R.string.formula_floor_desc, R.string.category_numbers, ofValues("floor")),
        Formula(R.string.formula_ceil_name, R.string.formula_ceil_desc, R.string.category_numbers, ofValues("ceil")),
        Formula(R.string.formula_nextprime_name, R.string.formula_nextprime_desc, R.string.category_numbers, ofValues("nextprime")),
        Formula(R.string.formula_isprime_name, R.string.formula_isprime_desc, R.string.category_numbers, ofValues("isprime")),
        Formula(R.string.formula_totient_name, R.string.formula_totient_desc, R.string.category_numbers, ofValues("totient")),
        Formula(R.string.formula_factor_name, R.string.formula_factor_desc, R.string.category_numbers, ofValues("factor")),
        Formula(R.string.formula_modpow_name, R.string.formula_modpow_desc, R.string.category_numbers, ofThree("modpow")),
        Formula(R.string.formula_hypot_name, R.string.formula_hypot_desc, R.string.category_numbers, ofTwo("hypot")),
        Formula(R.string.formula_trunc_name, R.string.formula_trunc_desc, R.string.category_numbers, ofValues("trunc")),
        Formula(R.string.formula_frac_name, R.string.formula_frac_desc, R.string.category_numbers, ofValues("frac")),
        Formula(R.string.formula_sign_name, R.string.formula_sign_desc, R.string.category_numbers, ofValues("sign")),
        Formula(R.string.formula_re_name, R.string.formula_re_desc, R.string.category_complex, ofValues("re")),
        Formula(R.string.formula_im_name, R.string.formula_im_desc, R.string.category_complex, ofValues("im")),
        Formula(R.string.formula_arg_name, R.string.formula_arg_desc, R.string.category_complex, ofValues("arg")),
        Formula(R.string.formula_conj_name, R.string.formula_conj_desc, R.string.category_complex, ofValues("conj")),
        Formula(R.string.formula_gamma_name, R.string.formula_gamma_desc, R.string.category_special, ofValues(FunctionNames.written("gamma"))),
        Formula(R.string.formula_lngamma_name, R.string.formula_lngamma_desc, R.string.category_special, ofValues(FunctionNames.written("lngamma"))),
        Formula(R.string.formula_digamma_name, R.string.formula_digamma_desc, R.string.category_special, ofValues(FunctionNames.written("digamma"))),
        Formula(R.string.formula_beta_name, R.string.formula_beta_desc, R.string.category_special, ofTwo("beta")),
        Formula(R.string.formula_erf_name, R.string.formula_erf_desc, R.string.category_special, ofValues("erf")),
        Formula(R.string.formula_erfc_name, R.string.formula_erfc_desc, R.string.category_special, ofValues("erfc")),
        Formula(R.string.formula_gammainc_name, R.string.formula_gammainc_desc, R.string.category_special, ofTwo("gammainc")),
        Formula(R.string.formula_betainc_name, R.string.formula_betainc_desc, R.string.category_special, ofThree("betainc")),
        Formula(R.string.formula_lambertw_name, R.string.formula_lambertw_desc, R.string.category_special, ofValues(FunctionNames.written("lambertw"))),
        Formula(R.string.formula_sinint_name, R.string.formula_sinint_desc, R.string.category_special, ofValues(FunctionNames.written("sinint"))),
        Formula(R.string.formula_cosint_name, R.string.formula_cosint_desc, R.string.category_special, ofValues(FunctionNames.written("cosint"))),
        Formula(R.string.formula_expint_name, R.string.formula_expint_desc, R.string.category_special, ofValues(FunctionNames.written("expint"))),
        Formula(R.string.formula_normalpdf_name, R.string.formula_normalpdf_desc, R.string.category_distributions, ofValues("normalpdf")),
        Formula(R.string.formula_normalcdf_name, R.string.formula_normalcdf_desc, R.string.category_distributions, ofValues("normalcdf")),
        Formula(R.string.formula_normalinv_name, R.string.formula_normalinv_desc, R.string.category_distributions, ofValues("normalinv")),
        Formula(R.string.formula_binompdf_name, R.string.formula_binompdf_desc, R.string.category_distributions, ofThree("binompdf")),
        Formula(R.string.formula_binomcdf_name, R.string.formula_binomcdf_desc, R.string.category_distributions, ofThree("binomcdf")),
        Formula(R.string.formula_poissonpdf_name, R.string.formula_poissonpdf_desc, R.string.category_distributions, ofTwo("poissonpdf")),
        Formula(R.string.formula_poissoncdf_name, R.string.formula_poissoncdf_desc, R.string.category_distributions, ofTwo("poissoncdf")),
        Formula(R.string.formula_tpdf_name, R.string.formula_tpdf_desc, R.string.category_distributions, ofTwo("tpdf")),
        Formula(R.string.formula_tcdf_name, R.string.formula_tcdf_desc, R.string.category_distributions, ofTwo("tcdf")),
        Formula(R.string.formula_tinv_name, R.string.formula_tinv_desc, R.string.category_distributions, ofTwo("tinv")),
        Formula(R.string.formula_chisqpdf_name, R.string.formula_chisqpdf_desc, R.string.category_distributions, ofTwo("chisqpdf")),
        Formula(R.string.formula_chisqcdf_name, R.string.formula_chisqcdf_desc, R.string.category_distributions, ofTwo("chisqcdf")),
        Formula(R.string.formula_chisqinv_name, R.string.formula_chisqinv_desc, R.string.category_distributions, ofTwo("chisqinv")),
        Formula(R.string.formula_rand_name, R.string.formula_rand_desc, R.string.category_random, ofValues("rand")),
        Formula(R.string.formula_randint_name, R.string.formula_randint_desc, R.string.category_random, ofTwo("randint")),
        Formula(R.string.formula_randnorm_name, R.string.formula_randnorm_desc, R.string.category_random, ofValues("randnorm")),
        Formula(R.string.formula_dot_name, R.string.formula_dot_desc, R.string.category_vectors, ofTwo("dot")),
        Formula(R.string.formula_cross_name, R.string.formula_cross_desc, R.string.category_vectors, ofTwo("cross")),
        Formula(R.string.formula_norm_name, R.string.formula_norm_desc, R.string.category_vectors, ofValues("norm")),
        Formula(R.string.formula_angle_name, R.string.formula_angle_desc, R.string.category_vectors, ofTwo("angle")),
        Formula(R.string.formula_proj_name, R.string.formula_proj_desc, R.string.category_vectors, ofTwo("proj")),
        Formula(R.string.formula_unitvec_name, R.string.formula_unitvec_desc, R.string.category_vectors, ofValues("unitvec")),
        Formula(R.string.formula_and_name, R.string.formula_and_desc, R.string.category_bitwise, ofTwo("and")),
        Formula(R.string.formula_or_name, R.string.formula_or_desc, R.string.category_bitwise, ofTwo("or")),
        Formula(R.string.formula_xor_name, R.string.formula_xor_desc, R.string.category_bitwise, ofTwo("xor")),
        Formula(R.string.formula_not_name, R.string.formula_not_desc, R.string.category_bitwise, ofValues("not")),
        Formula(R.string.formula_shl_name, R.string.formula_shl_desc, R.string.category_bitwise, ofTwo("shl")),
        Formula(R.string.formula_shr_name, R.string.formula_shr_desc, R.string.category_bitwise, ofTwo("shr"))
    )
}
