package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.EquationSystem
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Inequality
import com.vortessence.arithmeticus.core.math.expressions.Relation
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.expressions.substituteVariables
import com.vortessence.arithmeticus.core.math.i18n.Text
import com.vortessence.arithmeticus.core.math.i18n.Texts

/**
 * The steps of equations with conditions on the lines after them (x² = 4 and x > 0; x + y = 3, x − y = 1 and x > 0): the input as a
 * whole, the equation or the system solved, then each solution put into the conditions, and the ones that do not meet them crossed
 * out (−2 ≯ 0). Not for a solution with a family (x = πk), where the calculator searches the range by numbers instead.
 */
internal object ConditionSteps {

    fun steps(system: EquationSystem, context: MathContext): ExpressionSolution? {
        val equations = system.lines.filterIsInstance<Equals>()
        val conditions = system.lines.filterIsInstance<Inequality>()
        if (equations.isEmpty() || conditions.isEmpty() || equations.size + conditions.size != system.lines.size) return null
        val variables = system.variables()
        // The solutions, each a set of x = …, y = … (one equation in one variable: one x = … each)
        val solved: ExpressionSolution
        val solutionSets: List<List<Equals>>
        if (variables.size == 1) {
            val v = variables.single()
            solved = EquationSteps.steps(equations.singleOrNull() ?: return null, v, context) ?: return null
            solutionSets = ((solved.result as? Alternatives)?.equations ?: listOf(solved.result)).map { listOf(it as? Equals ?: return null) }
        } else {
            solved = SystemSteps.steps(EquationSystem(equations), context) ?: return null
            val result = solved.result as? Alternatives ?: return null
            solutionSets = if (result.equations.all { it is Equals }) listOf(result.equations.map { it as Equals })
            else result.equations.map { set -> (set as? Alternatives)?.equations?.map { it as? Equals ?: return null } ?: return null }
        }
        if (solutionSets.any { set -> set.any { it.left !is Variable || it.right.variables().isNotEmpty() } }) return null

        val notes = mutableListOf<Note>()
        val kept = solutionSets.filter { set ->
            val values = set.associate { (it.left as Variable) to it.right }
            conditions.all { condition ->
                val inserted = condition.substituteVariables(values) as? Inequality ?: return null
                val holds = inserted.truthValue(context.copy()) ?: return null
                notes.add(Note(inserted.left, inserted.right, relation = if (holds) inserted.relation.op.symbol else negated(inserted.relation)))
                holds
            }
        }
        fun written(set: List<Equals>): Expression = set.singleOrNull() ?: Alternatives(set, inline = true)
        val checked = Alternatives(solutionSets.map { set ->
            if (set in kept) written(set)
            else TextExpression(Texts.format(Text.NOT_A_SOLUTION, set.joinToString(", ") { "${writtenForm(it.left)} = ${writtenForm(it.right)}" }))
        })
        val result: Expression = when {
            kept.isEmpty() -> TextExpression(Texts.get(Text.NO_REAL_SOLUTION_TEXT))
            kept.size == 1 -> written(kept.single())
            // In one variable from the smallest up, as the calculator writes them
            variables.size == 1 -> Alternatives(kept.sortedBy { it.single().right.evaluateNumerically() }.map { written(it) }, inline = true)
            else -> Alternatives(kept.map { written(it) }, inline = false)
        }
        // The whole input first, with its conditions; then the equations' own steps
        val steps = listOf(ExpressionStep(system, null)) + solved.steps +
            ExpressionStep(checked, StepRule.CHECK_CONDITIONS, notes = notes) + ExpressionStep(result, StepRule.COLLECT_SOLUTION)
        return ExpressionSolution(steps, result)
    }

    /** The sign that says a relation does not hold: −2 ≯ 0. */
    private fun negated(relation: Relation): String = when (relation) {
        Relation.LESS -> "≮"
        Relation.GREATER -> "≯"
        Relation.LESS_EQUAL -> "≰"
        Relation.GREATER_EQUAL -> "≱"
    }
}
