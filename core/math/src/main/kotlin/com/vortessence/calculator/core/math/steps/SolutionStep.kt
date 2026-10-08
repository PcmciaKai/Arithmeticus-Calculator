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
    /** (∫_a^b f(t) dt)' = f(b)·b' − f(a)·a' */
    FUNDAMENTAL_THEOREM,
    /** The result multiplied out, combined and written compactly */
    SIMPLIFY,
    /** The variable replaced by the point the derivative is taken at */
    SUBSTITUTE,
    /** The value calculated */
    EVALUATE,
}

/**
 * One line of a step-by-step solution: [expression] follows from the previous line by [rule]
 * (null for the first line, the problem itself). With [chainRule], the rule was applied to an
 * inner function, so the result is multiplied by that function's derivative.
 */
data class SolutionStep(
    val expression: MathString,
    val rule: StepRule?,
    val chainRule: Boolean = false
)
