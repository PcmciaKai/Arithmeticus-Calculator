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
    SECANT,
    COSECANT,
    COTANGENT,
    ARCSECANT,
    ARCCOSECANT,
    ARCCOTANGENT,
    HYPERBOLIC_SINE,
    HYPERBOLIC_COSINE,
    HYPERBOLIC_TANGENT,
    HYPERBOLIC_SECANT,
    HYPERBOLIC_COSECANT,
    HYPERBOLIC_COTANGENT,
    INVERSE_HYPERBOLIC_SINE,
    INVERSE_HYPERBOLIC_COSINE,
    INVERSE_HYPERBOLIC_TANGENT,
    INVERSE_HYPERBOLIC_SECANT,
    INVERSE_HYPERBOLIC_COSECANT,
    INVERSE_HYPERBOLIC_COTANGENT,
    /** gamma(u)' = gamma(u)·digamma(u)·u' */
    GAMMA_FUNCTION,
    /** lngamma(u)' = digamma(u)·u' */
    LOG_GAMMA_FUNCTION,
    /** erf(u)' = 2/√π·e^(−u²)·u' */
    ERROR_FUNCTION,
    /** erfc(u)' = −2/√π·e^(−u²)·u' */
    COMPLEMENTARY_ERROR_FUNCTION,
    /** lambertw(u)' = 1/(e^W(u)·(1 + W(u)))·u' */
    LAMBERT_W_FUNCTION,
    /** sinint(u)' = sin(u)/u·u' */
    SINE_INTEGRAL_FUNCTION,
    /** cosint(u)' = cos(u)/u·u' */
    COSINE_INTEGRAL_FUNCTION,
    /** expint(u)' = e^u/u·u' */
    EXPONENTIAL_INTEGRAL_FUNCTION,
    /** a·b = a₁b₁ + a₂b₂ + … */
    DOT_PRODUCT,
    /** a × b = (a₂b₃ − a₃b₂, a₃b₁ − a₁b₃, a₁b₂ − a₂b₁) */
    CROSS_PRODUCT,
    /** |a| = √(a₁² + a₂² + …) */
    VECTOR_NORM,
    /** θ = acos(a·b/(|a||b|)) */
    VECTOR_ANGLE,
    /** proj_b(a) = (a·b)/(b·b)·b */
    VECTOR_PROJECTION,
    /** a/|a| */
    UNIT_VECTOR,
    /** a = b ⇔ a₁ = b₁ and a₂ = b₂ and …: two vectors are equal when their components are */
    COMPARE_COMPONENTS,
    /** The parameter found written into its line: S = p + r·u */
    INSERT_PARAMETER,
    /** φ(x) = e^(−(x − μ)²/(2σ²)) / (σ√(2π)) */
    NORMAL_DENSITY,
    /** Φ(x) = erfc(−(x − μ)/(σ√2))/2 */
    NORMAL_PROBABILITY,
    /** x = μ + σ·z with Φ(z) = p */
    NORMAL_QUANTILE,
    /** The density of Student's t distribution: Γ((ν + 1)/2)/(√(νπ)·Γ(ν/2))·(1 + x²/ν)^(−(ν + 1)/2) */
    STUDENT_DENSITY,
    /** The density of the chi-square distribution: x^(k/2 − 1)·e^(−x/2)/(2^(k/2)·Γ(k/2)) */
    CHI_SQUARE_DENSITY,
    /** P(X = k) = C(n, k)·pᵏ·(1 − p)ⁿ⁻ᵏ */
    BINOMIAL_PROBABILITY,
    /** P(X ≤ k) = P(0) + P(1) + … + P(k) for a binomial X */
    BINOMIAL_CUMULATIVE,
    /** P(X = k) = e^(−λ)·λᵏ/k! */
    POISSON_PROBABILITY,
    /** P(X ≤ k) = P(0) + P(1) + … + P(k) for a Poisson X */
    POISSON_CUMULATIVE,
    /** C(n, k) = n!/(k!·(n − k)!) */
    BINOMIAL_COEFFICIENT,
    /** n! = 1·2·…·n */
    FACTORIAL,
    /** Σ f(i) = f(0) + f(1) + … + f(k) */
    WRITE_OUT_SUM,
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
    INTEGRAL_SECANT,
    INTEGRAL_COSECANT,
    INTEGRAL_COTANGENT,
    INTEGRAL_HYPERBOLIC_SINE,
    INTEGRAL_HYPERBOLIC_COSINE,
    INTEGRAL_HYPERBOLIC_TANGENT,
    INTEGRAL_HYPERBOLIC_COTANGENT,
    INTEGRAL_HYPERBOLIC_SECANT,
    INTEGRAL_HYPERBOLIC_COSECANT,
    /** ∫ e^(−a·x²) dx = √π/(2√a)·erf(√a·x) */
    INTEGRAL_GAUSSIAN,
    /** ∫ sin(x)/x dx = Si(x) */
    INTEGRAL_SINE_OVER_X,
    /** ∫ cos(x)/x dx = Ci(x) */
    INTEGRAL_COSINE_OVER_X,
    /** ∫ eˣ/x dx = Ei(x) */
    INTEGRAL_EXPONENTIAL_OVER_X,
    /** ∫ ln(x) dx = x·ln(x) − x */
    INTEGRAL_LOGARITHM,
    /** sin², cos², 1/(p + qx²), 1/√(p − qx²) */
    INTEGRAL_TABLE,
    /** ∫1/√(a² + x²) dx = asinh(x/a) */
    INTEGRAL_INVERSE_HYPERBOLIC_SINE,
    /** ∫1/√(x² − a²) dx = ln|x + √(x² − a²)| */
    INTEGRAL_LOG_ROOT,
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
    /** det(a b; c d) = ad − bc */
    DETERMINANT_2X2,
    /** a determinant expanded along a row or column into smaller ones */
    DETERMINANT_COFACTOR,
    /** A⁻¹ = 1/(ad − bc)·(d −b; −c a) */
    INVERSE_2X2,
    /** (A | I) */
    INVERSE_AUGMENT,
    /** swap two rows */
    ROW_SWAP,
    /** divide a row by its pivot */
    ROW_SCALE,
    /** subtract a multiple of a row from another */
    ROW_ADD,
    /** (I | A⁻¹): the right half is the inverse */
    INVERSE_RESULT,
    /** rref(A): the matrix is brought to reduced row echelon form by row operations */
    ROW_REDUCE_BEGIN,
    /** det(A − λI) = 0 */
    EIGEN_CHARACTERISTIC,
    /** solve (A − λI)·v = 0 for an eigenvalue */
    EIGEN_VECTOR,
    /** λ with its eigenvector */
    EIGEN_VECTOR_RESULT,
    /** the eigenvalues with their eigenvectors */
    EIGEN_RESULT,
    /** every entry is a row times a column */
    MATRIX_MULTIPLY,
    /** a number times every entry */
    MATRIX_SCALAR,
    /** entry by entry */
    MATRIX_ADD,
    MATRIX_SUBTRACT,
    /** rows and columns swapped */
    MATRIX_TRANSPOSE,
    /** Aⁿ = A·A·…·A */
    MATRIX_POWER,
    /** x² − 5x + 6 = (x − 2)(x − 3) */
    FACTOR_POLYNOMIAL,
    /** ∫ₐ^∞ f = lim F(t) − F(a) */
    IMPROPER_INTEGRAL,
    /** 1^∞: f^g = e^(g·ln f) */
    LIMIT_EXPONENTIAL_FORM,
    /** …the limit is e to the limit of g·ln f */
    LIMIT_EXPONENTIAL_RESULT,
    /** 1/x as x → 0⁺: the denominator goes to 0, the numerator does not: ±∞ */
    LIMIT_POLE,
    /** ln(g) with g → 0⁺: −∞ */
    LIMIT_LOGARITHM_ZERO,
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
    /** sin³(x) = sin(x)(1 − cos²(x)) */
    TRIG_ODD_POWER,
    /** sin²(x) + cos²(x) = 1 */
    PYTHAGOREAN_IDENTITY,
    /** sec²(x) − tan²(x) = 1 */
    SECANT_IDENTITY,
    /** csc²(x) − cot²(x) = 1 */
    COSECANT_IDENTITY,
    /** cosh²(x) − sinh²(x) = 1 */
    HYPERBOLIC_IDENTITY,
    /** sec(x)·cos(x) = 1 */
    RECIPROCAL_FUNCTIONS,
    /** sinh(0) = 0, sinh(ln 2) = 3/4 */
    EXACT_HYPERBOLIC_VALUE,
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
    /** sec(u) = c ⇔ cos(u) = 1/c */
    RECIPROCAL_EQUATION,
    /** sec(u) = 1/cos(u), csc(u) = 1/sin(u), cot(u) = cos(u)/sin(u) */
    RECIPROCAL_TRIGONOMETRIC,
    /** sinh(u) = c ⇔ u = asinh(c), cosh(u) = c ⇔ u = ±acosh(c), and so on */
    HYPERBOLIC_SOLUTION,
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
    /** √a − b = (a − b²)/(√a + b): multiplied by the conjugate */
    LIMIT_CONJUGATE,
    /** (a + b)(a − b) = a² − b² */
    LIMIT_DIFFERENCE_OF_SQUARES,
    /** eᵍ with g → ±∞: ∞ or 0 */
    LIMIT_EXPONENT_INFINITE,
    /** |g| = g or −g, where g is positive or negative next to the point */
    LIMIT_ABSOLUTE_VALUE,
    /** a/b = 0 ⇔ a = 0 */
    ZERO_FRACTION,
    /** divide by x² and write t = y/x */
    SUBSTITUTE_RATIO,
    /** several inequalities: each is solved on its own */
    INEQUALITY_SYSTEM_EACH,
    /** all of them must hold: what the solutions have in common */
    INEQUALITY_SYSTEM_INTERSECT,
    /** log a + log b = log(ab), log a − log b = log(a/b) */
    LOG_SUM,
    /** ln a/ln b = log_b(a) */
    LOG_QUOTIENT,
    /** |f| = g ⇔ f = g or f = −g */
    ABSOLUTE_VALUE_CASES,
    /** x·|x| = 4: by the sign of the inside, |x| = x or −x */
    ABSOLUTE_VALUE_SPLIT,
    /** one case of the split: the equation without |…| and where it counts */
    ABSOLUTE_VALUE_CASE,
    /** 2 sin x > 1 ⇔ sin x > 1/2 */
    TRIG_INEQUALITY_ISOLATE,
    /** sin u > c ⇔ asin c + 2kπ < u < π − asin c + 2kπ */
    TRIG_INEQUALITY_PERIOD,
    /** u = ax + b: solved for x */
    TRIG_INEQUALITY_SOLVE_X,
    /** sin and cos are between −1 and 1 */
    TRIG_INEQUALITY_BOUNDED,
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

/**
 * The [steps] of a calculation and its [result] (as calculated, without highlighting). For an inequality also the
 * solution on a [numberLine] and in [intervalNotation]: (−∞, −2) ∪ (2, ∞).
 */
data class Solution(
    val steps: List<SolutionStep>,
    val result: MathString,
    val numberLine: NumberLine? = null,
    val intervalNotation: MathString? = null
)

/** The points where the solution of an inequality changes, and the [pieces] between them where it holds. */
data class NumberLine(val points: List<NumberLinePoint>, val pieces: List<NumberLinePiece>)

data class NumberLinePoint(val label: MathString, val value: Double)

/** From the point with index [lower] to [upper] (null: goes on forever); closed ends belong to it. */
data class NumberLinePiece(val lower: Int?, val lowerClosed: Boolean, val upper: Int?, val upperClosed: Boolean)
