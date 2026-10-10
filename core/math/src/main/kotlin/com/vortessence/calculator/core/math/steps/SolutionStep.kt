package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.type.MathString

/** The rule that leads from one step of a solution to the next. */
enum class StepRule {
    /** d/dx c = 0 */
    CONSTANT,
    /** d/dx x = 1 */
    VARIABLE,
    /** (u + v)' = u' + v' (constant terms drop out) */
    SUM,
    /** (c·u)' = c·u' */
    CONSTANT_FACTOR,
    /** (u·v)' = u'·v + u·v' */
    PRODUCT,
    /** (u/v)' = (u'·v − u·v')/v² */
    QUOTIENT,
    /** (uⁿ)' = n·uⁿ⁻¹·u' */
    POWER,
    /** (aᵘ)' = aᵘ·ln(a)·u' */
    EXPONENTIAL,
    /** u^w = e^(w·ln(u)), so (u^w)' = u^w·(w·ln(u))' */
    GENERAL_POWER,
    /** log_b(u)' = u'/(u·ln(b)) */
    LOGARITHM,
    /** log_u(w) = ln(w)/ln(u), for a base that depends on the variable */
    CHANGE_OF_BASE,
    SINE,
    COSINE,
    TANGENT,
    ARCSINE,
    ARCCOSINE,
    ARCTANGENT,
    /** |u|' = u'·u/|u| */
    ABSOLUTE_VALUE,
    /** √x = x^(1/2), 1/x = x⁻¹: a root or fraction written as a power, for the power rule */
    REWRITE_AS_POWER,
    /** (f(g(x)))' = f'(u)·g'(x) with u = g(x): outer and inner derivative */
    CHAIN_RULE,
    /** x^(1/2) = √x: a power written as a root again */
    POWER_AS_ROOT,
    /** x⁻¹ = 1/x: a power written as a fraction again */
    POWER_AS_FRACTION,
    /** (∫_a^b f(t) dt)' = f(b)·b' − f(a)·a' */
    FUNDAMENTAL_THEOREM,
    /** 2·3 = 6 */
    MULTIPLY_NUMBERS,
    /** 4/2 = 2 */
    DIVIDE_NUMBERS,
    /** 4/6 = 2/3 */
    REDUCE_FRACTION,
    /** 2³ = 8 */
    NUMBER_POWER,
    /** 1/(a/b) = b/a */
    DIVIDE_BY_FRACTION,
    /** (xᵃ)ᵇ = xᵃᵇ */
    POWER_OF_POWER,
    /** (a·b)ⁿ = aⁿ·bⁿ */
    POWER_OF_PRODUCT,
    /** (a + b)(c + d) = ac + ad + bc + bd */
    EXPAND,
    /** (a + b)² = a² + 2ab + b² */
    EXPAND_POWER,
    /** a/c + b/c = (a + b)/c */
    COMBINE_FRACTIONS,
    /** 2 + 3 = 5 */
    ADD_NUMBERS,
    /** 5 − 3 = 2 */
    SUBTRACT_NUMBERS,
    /** 5 − 3 + 1 = 3 */
    ADD_AND_SUBTRACT_NUMBERS,
    /** a − a = 0, also for a sum: 2x + 3 − (2x + 3) = 0 */
    SUBTRACT_SAME,
    /** xᵃ·xᵇ = xᵃ⁺ᵇ */
    COMBINE_POWERS,
    /** x·x⁻¹ = 1 */
    CANCEL,
    /** c·(a + b) = c·a + c·b */
    DISTRIBUTE,
    /** 2x + 3x = 5x */
    COMBINE_LIKE_TERMS,
    /** Whatever the rules above leave to do to reach the calculated result, in one step */
    SIMPLIFY,
    /** ∫ c dx = c·x */
    INTEGRAL_CONSTANT,
    /** ∫ (f + g) dx = ∫ f dx + ∫ g dx */
    INTEGRAL_SUM,
    /** ∫ c·f dx = c·∫ f dx */
    INTEGRAL_CONSTANT_FACTOR,
    /** ∫ xⁿ dx = xⁿ⁺¹/(n + 1) */
    INTEGRAL_POWER,
    /** ∫ 1/x dx = ln|x| */
    INTEGRAL_RECIPROCAL,
    /** ∫ aˣ dx = aˣ/ln(a) */
    INTEGRAL_EXPONENTIAL,
    INTEGRAL_SINE,
    INTEGRAL_COSINE,
    INTEGRAL_TANGENT,
    /** ∫ ln(x) dx = x·ln(x) − x */
    INTEGRAL_LOGARITHM,
    /** sin², cos², 1/(p + qx²), 1/√(p − qx²) */
    INTEGRAL_TABLE,
    /** The integrand multiplied out */
    EXPAND_INTEGRAND,
    /** ∫ f(g(x))·g'(x) dx = ∫ f(u) du with u = g(x) */
    SUBSTITUTION,
    /** u replaced by what it stands for again */
    BACK_SUBSTITUTION,
    /** ∫ u dv = u·v − ∫ v du */
    BY_PARTS,
    /** ∫ₐᵇ f dx = [F]ₐᵇ with an antiderivative F */
    ANTIDERIVATIVE,
    /** [F]ₐᵇ = F(b) − F(a) */
    BOUNDS,
    /** a = b ⇔ a + c = b + c */
    ADD_TO_BOTH_SIDES,
    /** a = b ⇔ a − c = b − c */
    SUBTRACT_FROM_BOTH_SIDES,
    /** a = b ⇔ a·c = b·c (c ≠ 0) */
    MULTIPLY_BOTH_SIDES,
    /** a = b ⇔ a/c = b/c (c ≠ 0) */
    DIVIDE_BOTH_SIDES,
    /** a = b ⇔ b = a */
    SWAP_SIDES,
    /** u² = c ⇔ u = √c or u = −√c */
    SQUARE_ROOT_BOTH_SIDES,
    /** ax² + bx = x(ax + b) */
    FACTOR_OUT,
    /** Solving one equation of a system for one of its variables */
    SOLVE_FOR,
    /** The solved variable written into the other equations */
    SUBSTITUTE_SOLUTION,
    /** The equations multiplied so that a variable has the same coefficient in both */
    MULTIPLY_EQUATIONS,
    ADD_EQUATIONS,
    /** The values found for the variables, written together */
    COLLECT_SOLUTION,
    SUBTRACT_EQUATIONS,
    /** A value found written into an equation or a solved variable */
    INSERT_VALUE,
    /** A root r of a polynomial splits off the factor (x − r) */
    FACTOR_BY_ROOT,
    /** The discriminant is negative */
    NO_REAL_SOLUTION,
    /** a·b = 0 ⇔ a = 0 or b = 0 */
    ZERO_PRODUCT,
    /** ax² + bx + c = 0 ⇔ x = (−b ± √(b² − 4ac))/(2a) */
    QUADRATIC_FORMULA,
    /** √9 = 3 */
    ROOT_OF_NUMBER,
    /** Both sides are the same for every value of the variable */
    IDENTITY,
    /** The variable replaced by the point the derivative is taken at */
    SUBSTITUTE,
    /** The value calculated */
    EVALUATE,
}

/**
 * One line of a step-by-step solution: [expression] follows from the previous line by [rule]
 * (null for the first line, the problem itself). With [chainRule], the rule was applied to an
 * inner function, so the result is multiplied by that function's derivative (for an integral:
 * the inner function is linear, a·x + b, and the result is divided by a).
 *
 * [change] is the rule applied to the part it changes, e.g. d/dx(2x²) = 2·d/dx(x²). In
 * [expression], the changed part is boxed in highlight color i (for step i of the solution)
 * and the part the next step changes is colored i + 1, see
 * [com.vortessence.calculator.core.math.type.HighlightNode].
 */
data class SolutionStep(
    val expression: MathString,
    val rule: StepRule?,
    val chainRule: Boolean = false,
    val change: StepChange? = null,
    /** What the rule needs, e.g. u = x² and du = 2x·dx for a substitution. */
    val notes: List<MathString> = emptyList(),
    /**
     * What the next step does to both sides of the equation in [expression], e.g. "− 2x" or
     * "÷ 3", written next to it after a bar: 3x + 5 = 2x − 1 | − 2x.
     */
    val operation: MathString? = null
)

/** [before] = [after]: a rule applied to one part of an expression. */
data class StepChange(val before: MathString, val after: MathString)

/** The [steps] of a calculation and its [result] (as calculated, without highlighting). */
data class Solution(val steps: List<SolutionStep>, val result: MathString)
