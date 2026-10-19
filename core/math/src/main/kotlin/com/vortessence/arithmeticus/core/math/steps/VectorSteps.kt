package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.AbsoluteValue
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.MatrixExpression
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.Power
import com.vortessence.arithmeticus.core.math.expressions.TrigType
import com.vortessence.arithmeticus.core.math.expressions.TrigonometricFunction
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.VectorFunction
import com.vortessence.arithmeticus.core.math.expressions.VectorKind
import com.vortessence.arithmeticus.core.math.expressions.containsImaginaryUnit
import com.vortessence.arithmeticus.core.math.expressions.containsUnit

/**
 * The steps of the vector functions for vectors: the formula with the components put in, then the arithmetic
 * (the dot product as a₁b₁ + a₂b₂ + …, the cross product component by component, the length as the root of the sum
 * of squares, the angle through the dot product and the lengths).
 */
internal object VectorSteps {

    private const val MAX_STEPS = 80

    fun steps(e: Expression, context: MathContext): ExpressionSolution? {
        // |v| is the length: the same steps as norm(v), starting from what was typed
        val call = e as? VectorFunction ?: (e as? AbsoluteValue)?.takeIf { it.operand is MatrixExpression }?.let { VectorFunction(VectorKind.NORM, listOf(it.operand)) } ?: return null
        val vectors = call.arguments.map { it as? MatrixExpression ?: return null }
        if (vectors.any { !it.isVector }) return null
        val scratch = context.copy()
        val canonical = call.canonicalize(scratch)
        if (scratch.shouldStop || canonical is VectorFunction) return null
        val target = displayForm(canonical, context)
        // Units are combined and converted for the result (m·N is J): the steps would end on something else than the result
        if (vectors.any { vector -> vector.elements.any { it.containsUnit() } }) return null
        // The conjugates and the modulus of complex components are not shown step by step
        if (vectors.any { vector -> vector.elements.any { it.containsImaginaryUnit() } }) return null
        val (rule, formula) = formula(call.kind, vectors, context) ?: return null
        // The rules do not always get to the result (vectors of expressions); then it is one last step
        return attempt(e, rule, formula, target, context, simplify = true) ?: attempt(e, rule, formula, target, context, simplify = false)
    }

    private fun attempt(problem: Expression, rule: StepRule, formula: Expression, target: Expression, context: MathContext, simplify: Boolean): ExpressionSolution? {
        val solution = Solver(context)
        solution.start(problem)
        solution.apply(Match(rule, mapOf(emptyList<Int>() to formula), problem, formula))
        if (simplify) {
            if (!solution.simplifyToward(target, MAX_STEPS)) return null
        } else if (writtenForm(solution.current) != writtenForm(target)) {
            solution.apply(Match(StepRule.SIMPLIFY, mapOf(emptyList<Int>() to target), normalize(unmarked(solution.current)), target))
        }
        return solution.finish(target)
    }

    private fun product(vararg factors: Expression): Expression = Multiplications(factors.toList())

    private fun formula(kind: VectorKind, v: List<MatrixExpression>, context: MathContext): Pair<StepRule, Expression>? = when (kind) {
        VectorKind.DOT -> StepRule.DOT_PRODUCT to VectorFunction.dot(v[0], v[1], context)
        VectorKind.NORM -> StepRule.VECTOR_NORM to VectorFunction.norm(v[0])
        VectorKind.CROSS -> {
            if (v[0].elements.size != 3 || v[1].elements.size != 3) null else {
                val (a1, a2, a3) = v[0].elements
                val (b1, b2, b3) = v[1].elements
                fun component(p: Expression, q: Expression, r: Expression, s: Expression): Expression =
                    Additions(listOf(product(p, q), product(Value.MINUS_ONE, r, s)))
                StepRule.CROSS_PRODUCT to MatrixExpression(v[0].rows, v[0].cols, listOf(component(a2, b3, a3, b2), component(a3, b1, a1, b3), component(a1, b2, a2, b1)))
            }
        }
        VectorKind.ANGLE -> {
            val ratio = product(VectorFunction.dot(v[0], v[1], context), Power(product(VectorFunction.norm(v[0]), VectorFunction.norm(v[1])), Value.MINUS_ONE))
            StepRule.VECTOR_ANGLE to TrigonometricFunction(TrigType.ACOS, ratio)
        }
        VectorKind.PROJECTION -> StepRule.VECTOR_PROJECTION to product(VectorFunction.dot(v[0], v[1], context), Power(VectorFunction.dot(v[1], v[1], context), Value.MINUS_ONE), v[1])
        VectorKind.UNIT -> StepRule.UNIT_VECTOR to product(Power(VectorFunction.norm(v[0]), Value.MINUS_ONE), v[0])
    }
}
