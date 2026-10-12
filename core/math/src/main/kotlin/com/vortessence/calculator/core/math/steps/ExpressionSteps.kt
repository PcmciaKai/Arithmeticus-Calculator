package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.AbsoluteValue
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Constant
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Logarithm
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.HyperbolicFunction
import com.vortessence.calculator.core.math.expressions.TrigonometricFunction
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable

/**
 * The steps of simplifying an algebraic expression, e.g. (x + 1)² − x² = x² + 2x + 1 − x² = 2x + 1
 * ([SimplificationSteps]). Only for expressions with variables (plain arithmetic has no steps), or with
 * functions that have an exact value, e.g. sin(π/6) = 1/2.
 */
internal object ExpressionSteps {

    private const val MAX_STEPS = 100

    /**
     * The steps for [e], or null if it is not an algebraic expression with variables, or if the
     * rules have nothing to do (it is already simplified, or only the last "simplify" step would
     * remain).
     */
    fun steps(e: Expression, context: MathContext): ExpressionSolution? {
        if (!isSupported(e, context)) return null
        // The parser nests sums ((x + y) + z), which would take extra steps
        val expression = normalize(e)
        val canonical = expression.canonicalize(context)
        if (context.shouldStop || canonical.partWithUnit() != null) return null
        val target = displayForm(canonical, context)

        val solution = Solver(context)
        solution.start(expression)
        if (!solution.simplifyToward(target, MAX_STEPS)) return null
        val result = solution.finish(target)
        if (result.steps.none { it.rule != null && it.rule != StepRule.SIMPLIFY }) return null
        return result
    }

    /**
     * Sums, products, powers and functions of variables and numbers, without units. With angles
     * in degrees, trigonometric functions are left out: their results are converted to radians.
     */
    private fun isSupported(e: Expression, context: MathContext): Boolean {
        // Without variables only for what has an exact value: sin(π/6), log(100)
        if (e.variables().isEmpty() && !e.containsExpressionMatching { it is TrigonometricFunction || it is HyperbolicFunction || it is Logarithm }) return false
        val withVariables = e.variables().isNotEmpty()
        return !e.containsExpressionMatching { part ->
            when (part) {
                is Value, is Variable, is Additions, is Multiplications, is Power, is Logarithm, is AbsoluteValue -> false
                is Constant -> part.unit != null
                is TrigonometricFunction -> withVariables && (part.angleUnit ?: context.angleUnit) == AngleUnit.DEGREES
                is HyperbolicFunction -> false
                else -> true
            }
        }
    }
}
