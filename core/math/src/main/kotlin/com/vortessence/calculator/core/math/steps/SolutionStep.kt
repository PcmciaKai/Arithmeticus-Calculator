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
    /** √(−4) = i·√4 */
    IMAGINARY_ROOT,
    /** 2·3 = 6 */
    MULTIPLY_NUMBERS,
    /** 4/2 = 2 */
    DIVIDE_NUMBERS,
    /** 1/2 + 1/3 = 3/6 + 2/6: a common denominator */
    EXTEND_FRACTIONS,
    /** 2 1/3 = (2·3 + 1)/3 = 7/3 */
    IMPROPER_FRACTION,
    /** 15% = 15/100 = 3/20 */
    PERCENT_AS_FRACTION,
    /** a/b·c/d = (a·c)/(b·d) */
    MULTIPLY_FRACTIONS,
    /** √12 = 2√3 */
    SIMPLIFY_ROOT,
    /** 4/6 = 2/3 */
    REDUCE_FRACTION,
    /** 2³ = 8 */
    NUMBER_POWER,
    /** 1/(a/b) = b/a */
    DIVIDE_BY_FRACTION,
    /** (xᵃ)ᵇ = xᵃᵇ */
    POWER_OF_POWER,
    /** √(x²) = |x| */
    ROOT_OF_SQUARE,
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
    /** sin(x)/cos(x) = tan(x) */
    TANGENT_AS_QUOTIENT,
    /** (x² − 1)/(x − 1) = (x − 1)(x + 1)/(x − 1) = x + 1 */
    CANCEL_COMMON_FACTOR,
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
    /** P/((x − a)(x − b)) = A/(x − a) + B/(x − b) */
    PARTIAL_FRACTIONS,
    /** (px + q)/D = (p/2a)·D′/D + (q − pb/2a)/D */
    SPLIT_NUMERATOR,
    /** ax² + bx + c = a((x + h)² + k) */
    COMPLETE_SQUARE,
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
    /** √f = g ⇔ f = g² (with a check) */
    SQUARE_BOTH_SIDES,
    /** −3x < 6 ⇔ x > −2: both sides divided by a negative number, the relation turns around */
    DIVIDE_BY_NEGATIVE,
    /** both sides multiplied by a negative number, the relation turns around */
    MULTIPLY_BY_NEGATIVE,
    /** a < b ⇔ b > a */
    INEQUALITY_SWAP,
    /** f < g ⇔ f − g < 0 */
    INEQUALITY_ONE_SIDE,
    /** the sign of f can only change where f = 0 */
    INEQUALITY_ZEROS,
    /** …or where it is not defined: a denominator is 0, a root or logarithm begins */
    INEQUALITY_BREAKS,
    /** the points in order, they divide the line into intervals */
    INEQUALITY_CRITICAL,
    /** one test value in each interval, the solution */
    INEQUALITY_RESULT,
    /** x·sin(1/x) as x → ∞: u = 1/x, u → 0 */
    LIMIT_RECIPROCAL_SUBSTITUTION,
    /** x³/(x² + 1) = x − x/(x² + 1) */
    POLYNOMIAL_DIVISION,
    /** ∫ eˣ sin x: by parts twice, the integral comes back */
    CYCLIC_BY_PARTS,
    /** sin²(u) = (1 − cos 2u)/2 */
    HALF_ANGLE,
    /** √(4/9) = √4/√9 */
    ROOT_OF_FRACTION,
    /** 1/√2 = √2/2 */
    RATIONALIZE,
    /** sin(π/6) = 1/2 */
    EXACT_TRIGONOMETRIC_VALUE,
    /** log(100) = 2, ln(eˣ) = x */
    LOGARITHM_OF_POWER,
    /** e^ln(x) = x */
    EXPONENTIAL_OF_LOGARITHM,
    /** ln(a) + ln(b) = ln(ab) */
    LOG_COMBINE,
    /** ln(a) = ln(b) ⇔ a = b */
    LOG_EQUAL,
    /** tan(u) = sin(u)/cos(u) */
    TANGENT_AS_FRACTION,
    /** sin²(u) = 1 − cos²(u) */
    PYTHAGOREAN,
    /** a·c + b·c = c·(a + b) */
    FACTOR_COMMON,
    /** sin(2u) = 2sin(u)cos(u), cos(2u) = cos²(u) − sin²(u) */
    DOUBLE_ANGLE,
    /** a·sin(u) + b·cos(u) = R·sin(u + φ) */
    AMPLITUDE_FORM,
    /** sin(u) = c ⇔ u = asin(c) + 2kπ or … */
    TRIG_GENERAL_SOLUTION,
    /** lim (a₂x² + a₁x)/(b₂x² + b₁x) = lim (a₂ + a₁/x)/(b₂ + b₁/x) */
    DIVIDE_BY_HIGHEST_POWER,
    /** c/xⁿ → 0 */
    LIMIT_VANISH,
    /** lim c = c */
    LIMIT_CONSTANT,
    /** The highest power grows without bound */
    LIMIT_GROWS,
    /** c/g with g growing without bound → 0 */
    LIMIT_RECIPROCAL,
    /** lim f(x) = f(a) where f is defined at a */
    LIMIT_SUBSTITUTE,
    /** 0/0: lim f/g = lim f′/g′ */
    LHOPITAL,
    /** f·g = f/(1/g) */
    LIMIT_PRODUCT_AS_QUOTIENT,
    /** a/b = 0 ⇔ a = 0 */
    ZERO_FRACTION,
    /** |f| = g ⇔ f = g or f = −g */
    ABSOLUTE_VALUE_CASES,
    /** |a| = |b| ⇔ a = b or a = −b */
    ABSOLUTE_VALUES_EQUAL,
    /** log_b(f) = c ⇔ f = bᶜ */
    EXPONENTIATE_BOTH_SIDES,
    /** The solutions tried in the original equation */
    CHECK_SOLUTIONS,
    /** Only even powers: u = x² */
    SUBSTITUTE_POWER,
    /** u = x² written back */
    BACK_SUBSTITUTE_POWER,
    /** Everything to one side: f(x) = 0 */
    ONE_SIDE,
    /** x → x − f(x)/f′(x), repeated */
    NEWTON_METHOD,
    /** bᶠ = c ⇔ f = log_b(c) */
    LOGARITHM_BOTH_SIDES,
    /** uⁿ = c ⇔ u = ⁿ√c */
    NTH_ROOT_BOTH_SIDES,
    /** The discriminant is negative */
    NO_REAL_SOLUTION,
    /** 1 = 0: the two sides can never be equal */
    CONTRADICTION,
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
