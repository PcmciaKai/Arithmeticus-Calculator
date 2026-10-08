package com.vortessence.calculator.model.formulas

import com.vortessence.calculator.core.math.type.*

/**
 * A ready-made expression for the formula picker. [template] is inserted at the cursor, which
 * then goes to its first empty slot (or before its first comma), ready to type the values.
 */
data class Formula(
    val name: String,
    val description: String,
    val category: String,
    val template: MathString
)

object FormulasDb {
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())

    /** name(), for a list of values separated by commas. */
    private fun ofValues(name: String) = ms(FunctionNode(name))

    /** name(, ): exactly two values; the cursor starts before the comma. */
    private fun ofTwo(name: String) = ms(FunctionNode(name, ms(SeparatorNode)))

    private const val VALUES = "Values separated by commas (long press . for a comma)"

    val items = listOf(
        // Statistics
        Formula("Mean x̄", "Average of the values. $VALUES", "Statistics", ofValues("mean")),
        Formula("Median", "Middle value when sorted; for an even count the mean of the two middle ones", "Statistics", ofValues("median")),
        Formula("Standard deviation s (sample)", "Spread of a sample drawn from a larger population, divides by n − 1", "Statistics", ofValues("stdev")),
        Formula("Standard deviation σ (population)", "Spread of a complete population, divides by n", "Statistics", ofValues("stdevp")),
        Formula("Variance s² (sample)", "Square of the sample standard deviation, divides by n − 1", "Statistics", ofValues("var")),
        Formula("Variance σ² (population)", "Square of the population standard deviation, divides by n", "Statistics", ofValues("varp")),
        Formula("Standard error of the mean", "s/√n: how precisely a sample's mean estimates the population mean", "Statistics", ofValues("sem")),
        Formula("Minimum", "Smallest of the values", "Statistics", ofValues("min")),
        Formula("Maximum", "Largest of the values", "Statistics", ofValues("max")),

        // Combinatorics
        Formula("Combinations nCr", "Ways to choose k of n items, order does not matter: nCr(n, k)", "Combinatorics", ofTwo("nCr")),
        Formula("Permutations nPr", "Ways to arrange k of n items in order: nPr(n, k)", "Combinatorics", ofTwo("nPr")),

        // Divisors, rounding
        Formula("Greatest common divisor", "Largest whole number dividing all values", "Numbers", ofValues("gcd")),
        Formula("Least common multiple", "Smallest positive multiple of all values", "Numbers", ofValues("lcm")),
        Formula("Round", "To the nearest whole number, halves away from zero", "Numbers", ofValues("round")),
        Formula("Round to decimal places", "round(x, n): x with n decimal places", "Numbers", ofTwo("round")),
        Formula("Round down (floor)", "Largest whole number not above the value", "Numbers", ofValues("floor")),
        Formula("Round up (ceiling)", "Smallest whole number not below the value", "Numbers", ofValues("ceil"))
    )
}
