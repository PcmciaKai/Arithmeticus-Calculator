package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.MixedNumber
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Percent
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.Value

/**
 * The steps of plain arithmetic, only for the cases that need working out by hand, as other
 * calculators show them:
 * - sums of fractions (1/2 + 1/3 = 3/6 + 2/6 = 5/6, common denominator and reducing),
 * - products and quotients of fractions (3/4 ÷ 5/6 = 3/4 · 6/5 = 18/20 = 9/10),
 * - roots of whole numbers (√12 = √(4·3) = 2√3),
 * - mixed numbers (2 1/3 = 7/3) and percents (15% = 15/100 = 3/20).
 * Everything else, 2 + 3 or 12·7, has none: there is nothing to explain. The steps are only offered
 * if one of those rules was used.
 */
internal object ArithmeticSteps {

    private const val MAX_STEPS = 40

    /** The rules that make plain arithmetic worth showing. */
    private val WORKED_OUT = setOf(
        StepRule.EXTEND_FRACTIONS, StepRule.MULTIPLY_FRACTIONS, StepRule.DIVIDE_BY_FRACTION,
        StepRule.SIMPLIFY_ROOT, StepRule.REDUCE_FRACTION, StepRule.COMBINE_FRACTIONS, StepRule.IMPROPER_FRACTION, StepRule.PERCENT_AS_FRACTION, StepRule.RATIONALIZE, StepRule.ROOT_OF_FRACTION
    )

    fun steps(e: Expression, context: MathContext): ExpressionSolution? {
        if (e.variables().isNotEmpty() || e.containsExpressionMatching { !isArithmetic(it) }) return null
        val canonical = e.canonicalize(context.copy())
        if (context.shouldStop || canonical.variables().isNotEmpty()) return null
        val target = displayForm(canonical, context)

        val solution = Solver(context)
        solution.start(withFractions(normalize(e)))
        if (!solution.simplifyToward(target, MAX_STEPS, arithmetic = true)) return null
        val result = solution.finish(target)
        // 2 + 3 and 12·7 have no steps, 1/2 + 1/3 and √12 have
        if (result.steps.none { it.rule in WORKED_OUT }) return null
        return result
    }

    /** Numbers, sums, products and powers with a number as the exponent (fractions, roots). */
    private fun isArithmetic(e: Expression): Boolean = when (e) {
        is Value -> e.rational != null
        is MixedNumber -> true
        is Percent -> e.operand is Value && e.operand.rational != null
        is Additions, is Multiplications -> true
        is Power -> e.exponent is Value && e.exponent.rational != null
        else -> false
    }

    /**
     * A whole number with a divisor right after it, 1·2⁻¹, is the fraction 1/2 (shown as typed), and
     * −1 in front of a fraction is its sign: 3/4 − 1/6 is 3/4 + (−1)/6.
     */
    private fun withFractions(e: Expression): Expression = when (e) {
        is Multiplications -> pairFractions(e.operands.map { withFractions(it) })
        else -> if (e.children.isEmpty()) e else e.copyWithChildren(e.children.map { withFractions(it) })
    }

    private fun isWholeNumber(e: Expression) = e is Value && e.rational?.isFractional == false

    private fun divisor(e: Expression): Expression? =
        (e as? Power)?.takeIf { isWholeNumber(it.base) && it.exponent == Value.MINUS_ONE }?.base

    private fun pairFractions(operands: List<Expression>): Expression {
        val pieces = mutableListOf<Expression>()
        var i = 0
        while (i < operands.size) {
            val op = operands[i]
            val next = operands.getOrNull(i + 1)
            when {
                isWholeNumber(op) && next != null && divisor(next) != null -> {
                    pieces.add(LiteralFraction(op, divisor(next)!!))
                    i += 2
                }
                divisor(op) != null -> {
                    pieces.add(LiteralFraction(Value.ONE, divisor(op)!!))
                    i++
                }
                else -> {
                    pieces.add(op)
                    i++
                }
            }
        }
        // The sign of a subtracted fraction
        val signed = mutableListOf<Expression>()
        var j = 0
        while (j < pieces.size) {
            val piece = pieces[j]
            val following = pieces.getOrNull(j + 1)
            if (piece == Value.MINUS_ONE && following is LiteralFraction && following.numerator is Value) {
                signed.add(LiteralFraction(following.numerator.negate(), following.denominator))
                j += 2
            } else {
                signed.add(piece)
                j++
            }
        }
        return Multiplications.of(*signed.toTypedArray())
    }
}
