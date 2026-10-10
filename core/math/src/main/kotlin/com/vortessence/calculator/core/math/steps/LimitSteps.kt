package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.Differentiator
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Limit
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.UnitExpression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.containsImaginaryUnit
import com.vortessence.calculator.core.math.expressions.substituteVariables
import kotlin.math.abs
import kotlin.math.max

/**
 * The steps of a limit at a finite point, in the order the calculator ([Limit]) tries: the point
 * substituted where the function is defined there, and otherwise, for 0/0, l'Hôpital's rule
 * (lim f/g = lim f′/g′) as often as needed, the derivatives worked out step by step
 * ([DerivativeSteps]). Limits at ∞ and ones the calculator only approximates have no steps.
 */
internal object LimitSteps {

    private const val MAX_STEPS = 120
    private const val MAX_LHOPITAL = 4

    fun steps(limit: Limit, context: MathContext): ExpressionSolution? {
        val v = limit.variable
        if (limit.variables().isNotEmpty() || (limit.point as? Value)?.real?.isInfinite == true) return null
        if (limit.containsExpressionMatching { it is MatrixExpression || it is UnitExpression || it is Derivative } || limit.containsImaginaryUnit()) return null
        val scratch = context.copy()
        val calculated = limit.canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || calculated.containsExpressionMatching { it is Limit }) return null
        val target = displayForm(calculated, context)
        val expected = calculated.evaluateNumerically()

        val point = limit.point.canonicalize(context.copy())
        val solution = Solver(context)
        solution.start(normalize(limit))
        repeat(MAX_LHOPITAL + 1) { round ->
            if (context.shouldStop || solution.size > MAX_STEPS) return null
            val body = normalize(unmarked(at(solution.current, listOf(0))))
            if (defined(body, v, point, context)) {
                // The point written in, then calculated
                val inserted = body.transform { if (it == v) Marked(point, PENDING_COLOR, boxed = true, keep = true) else it }
                val written = normalize(unmarked(inserted))
                solution.apply(Match(StepRule.LIMIT_SUBSTITUTE, mapOf(emptyList<Int>() to inserted), solution.current, written, showChange = false, boxReplacements = false))
                val value = unmarked(solution.current).evaluateNumerically()
                if (abs(value - expected) > 1e-7 * max(1.0, abs(expected)) && !(value.isNaN() && expected.isNaN())) return null
                if (!solution.simplifyToward(target, MAX_STEPS)) return null
                return solution.finish(target)
            }
            if (round == MAX_LHOPITAL) return null
            if (!lHopital(solution, body, v, point, context)) return null
        }
        return null
    }

    /** lim f/g with f(a) = g(a) = 0 becomes lim f′/g′, the derivatives then worked out. */
    private fun lHopital(solution: Solver, body: Expression, v: Variable, point: Expression, context: MathContext): Boolean {
        val (numerator, denominator) = splitFraction(body, v) ?: return false
        val atNumerator = canonical(numerator.substituteVariables(mapOf(v to point)), context) ?: return false
        val atDenominator = canonical(denominator.substituteVariables(mapOf(v to point)), context) ?: return false
        if (!atNumerator.isZero || !atDenominator.isZero) return false
        val quotient = Multiplications(Derivative(numerator, v), Power(Derivative(denominator, v), Value.MINUS_ONE))
        val notes = listOf(
            Note(numerator.substituteVariables(mapOf(v to point)), Value.ZERO),
            Note(denominator.substituteVariables(mapOf(v to point)), Value.ZERO)
        )
        solution.apply(Match(StepRule.LHOPITAL, mapOf(listOf(0) to quotient), solution.current, quotient, notes = notes, showChange = false))
        // The derivatives, then the quotient simplified
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return false
            solution.apply(DerivativeSteps.derivativeMatch(solution.current, context) ?: break)
        }
        if (solution.current.containsExpressionMatching { it is Derivative || it is BackSubstitution }) return false
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return false
            val match = SimplificationSteps.match(at(solution.current, listOf(0)), Value.ZERO, context) ?: break
            solution.apply(EquationSteps.prefixed(match, listOf(0)))
        }
        return true
    }

    /** Whether [e] has a finite value at [v] = [point] (a division by 0 is no value). */
    private fun defined(e: Expression, v: Variable, point: Expression, context: MathContext): Boolean {
        val scratch = context.copy()
        val result = e.substituteVariables(mapOf(v to point)).canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return false
        if (result.containsExpressionMatching { it is Value && !it.real.isFinite }) return false
        return result.variables().isNotEmpty() || result.evaluateNumerically().isFinite()
    }

    /** (numerator, denominator) of a product with factors such as (x − 1)⁻¹; null without a denominator. */
    private fun splitFraction(e: Expression, v: Variable): Pair<Expression, Expression>? {
        val factors = if (e is Multiplications) e.operands else listOf(e)
        val numerator = mutableListOf<Expression>()
        val denominator = mutableListOf<Expression>()
        for (factor in factors) {
            val exponent = (factor as? Power)?.exponent as? Value
            if (factor is Power && exponent != null && exponent.isNegative && factor.containsVariable(v)) {
                val positive = exponent.negate()
                denominator.add(if (positive == Value.ONE) factor.base else Power(factor.base, positive))
            } else {
                numerator.add(factor)
            }
        }
        if (denominator.isEmpty()) return null
        return Multiplications.of(*numerator.toTypedArray()) to Multiplications.of(*denominator.toTypedArray())
    }

    private fun canonical(e: Expression, context: MathContext): Expression? {
        val scratch = context.copy()
        val result = e.canonicalize(scratch)
        return if (scratch.shouldStop || scratch.issues.isNotEmpty()) null else result
    }
}
