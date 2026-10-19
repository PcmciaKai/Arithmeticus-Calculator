package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.AbsoluteValue
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Inequality
import com.vortessence.arithmeticus.core.math.expressions.Relation
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.expressions.substituteVariables
import kotlin.math.abs
import kotlin.math.max

/**
 * An equation with |g| that cannot be isolated (x·|x| = 4, |x|·x + x = 6): split by the sign of g. Where g ≥ 0, |g| is g; where g < 0 it
 * is −g. Each case is an equation without |…|, solved on its own, and a solution counts only if it is in its case.
 * The solutions must be the calculator's.
 */
internal object AbsoluteSplitSteps {

    private const val TOLERANCE = 1e-9

    fun steps(equation: Equals, v: Variable, context: MathContext): ExpressionSolution? {
        val absolutes = LinkedHashSet<Expression>()
        fun collect(e: Expression) {
            if (e is AbsoluteValue && e.operand.containsVariable(v)) absolutes.add(e.operand) else e.children.forEach { collect(it) }
        }
        collect(equation.left)
        collect(equation.right)
        // One inside only: with several the cases are intervals between their zeros
        val inside = absolutes.singleOrNull() ?: return null
        if (inside.variables() != setOf(v)) return null
        val scratch = context.copy()
        val calculated = equation.solveForVariable(v, scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || calculated.isEmpty() || calculated == setOf(v)) return null

        class Case(val condition: Inequality, val equation: Equals)
        fun replaced(sign: Int) = equation.transform { e ->
            if (e is AbsoluteValue && e.operand == inside) (if (sign > 0) inside else inside.negate()) else e
        } as Equals
        val cases = listOf(
            Case(Inequality(inside, com.vortessence.arithmeticus.core.math.expressions.Value.ZERO, Relation.GREATER_EQUAL), replaced(1)),
            Case(Inequality(inside, com.vortessence.arithmeticus.core.math.expressions.Value.ZERO, Relation.LESS), replaced(-1))
        )
        val lines = mutableListOf(ExpressionStep(normalize(equation), null))
        val shown = Alternatives(cases.map { Alternatives(listOf(it.condition, it.equation), inline = true, separator = ",   ", group = true) })
        lines.add(ExpressionStep(shown, StepRule.ABSOLUTE_VALUE_SPLIT))

        val kept = mutableListOf<Expression>()
        for (case in cases) {
            val solved = EquationSteps.steps(case.equation, v, context)
            val roots: List<Expression>
            if (solved != null) {
                roots = solutionValues(solved.result, v) ?: emptyList()
                val first = solved.steps.first()
                lines.add(ExpressionStep(first.expression, StepRule.ABSOLUTE_VALUE_CASE, first.chainRule, first.change, listOf(Note(case.condition, TextExpression(""), relation = "")), first.operation))
                lines.addAll(solved.steps.drop(1))
            } else {
                val found = context.copy().let { s -> case.equation.solveForVariable(v, s).takeIf { !s.shouldStop && s.issues.isEmpty() && it != setOf(v) } } ?: emptySet()
                roots = found.sortedBy { it.evaluateNumerically() }
                lines.add(ExpressionStep(case.equation, StepRule.ABSOLUTE_VALUE_CASE, notes = listOf(Note(case.condition, TextExpression(""), relation = ""))))
            }
            // Only the solutions inside the case
            val checks = mutableListOf<Note>()
            for (root in roots) {
                val value = inside.evaluateNumerically(mapOf(v to root.evaluateNumerically()))
                val holds = if (case.condition.relation == Relation.GREATER_EQUAL) value >= -TOLERANCE else value < -TOLERANCE
                val sign = if (case.condition.relation == Relation.GREATER_EQUAL) "≥" else "<"
                val shownValue = writtenForm(inside.substituteVariables(mapOf(v to root)))
                checks.add(Note(TextExpression("${writtenForm(Equals(v, root))}:  $shownValue $sign 0"), TextExpression(if (holds) "✓" else "✗"), relation = ""))
                if (holds) kept.add(root)
            }
            if (roots.isNotEmpty()) {
                val last = lines.removeAt(lines.lastIndex)
                lines.add(ExpressionStep(last.expression, last.rule, last.chainRule, last.change, last.notes + checks, last.operation))
            }
        }
        // They are the calculator's solutions, and there is at least one
        val values = kept.sortedBy { it.evaluateNumerically() }
        val expected = calculated.sortedBy { it.evaluateNumerically() }
        if (values.size != expected.size || values.indices.any { abs(values[it].evaluateNumerically() - expected[it].evaluateNumerically()) > TOLERANCE * max(1.0, abs(expected[it].evaluateNumerically())) }) return null
        val result: Expression = if (values.size == 1) Equals(v, values.single()) else Alternatives(values.map { Equals(v, it) }, inline = true)
        lines.add(ExpressionStep(result, StepRule.COLLECT_SOLUTION))
        return ExpressionSolution(lines, result)
    }

    private fun solutionValues(result: Expression, v: Variable): List<Expression>? = when (result) {
        is Equals -> if (result.left == v) listOf(result.right) else null
        is Alternatives -> result.equations.map { (it as? Equals)?.takeIf { e -> e.left == v }?.right ?: return null }
        else -> null
    }
}
