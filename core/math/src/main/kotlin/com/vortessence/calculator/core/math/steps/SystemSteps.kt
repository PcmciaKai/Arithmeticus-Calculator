package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Antiderivative
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.EquationSystem
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Integral
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.UnitExpression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.solvers.LinearSystemSolver
import kotlin.math.abs

/**
 * The steps of solving a linear system with as many equations as variables, as by hand:
 * - by substitution: one equation is solved for a variable (preferably one that is alone or has
 *   the coefficient 1), the result is written into the other equations, and so on until a
 *   single variable is left; its value is then written into the equations solved before,
 * - by elimination, for two equations where no variable has the coefficient 1: the equations are
 *   multiplied until a variable has the same coefficient in both, then added or subtracted.
 *
 * Solving one equation for a variable is [EquationSteps]; the values must agree with the
 * calculator's ([LinearSystemSolver]), otherwise there are no steps.
 */
internal object SystemSteps {

    private const val MAX_STEPS = 200

    private val PREFERRED_VARIABLES = listOf(Variable("x"), Variable("y"), Variable("z"))

    fun steps(system: EquationSystem, context: MathContext): ExpressionSolution? {
        // x > 1 and x < 5: each solved, then what they have in common
        if (system.lines.isNotEmpty() && system.lines.all { it is com.vortessence.calculator.core.math.expressions.Inequality }) return InequalitySystemSteps.steps(system, context)
        val equations = system.lines.map { it as? Equals ?: return null }
        if (system.containsExpressionMatching {
                it is MatrixExpression || it is UnitExpression || it is Derivative || it is Antiderivative || it is Integral
            }
        ) return null
        val found = system.variables()
        val variables = PREFERRED_VARIABLES.filter { it in found } + (found - PREFERRED_VARIABLES.toSet()).sortedBy { it.identifier }
        if (variables.size < 2 || variables.size != equations.size) return null
        val scratch = context.copy()
        val calculated = LinearSystemSolver.solve(equations, variables, scratch)
        // Two equations that contradict each other, or say the same: one is written into the other
        if (variables.size == 2 && equations.size == 2) {
            if (calculated == null && scratch.issues.singleOrNull() == MathIssue.CONTRADICTION) return Run(equations, variables, context).solveSingular(null)
            if (calculated != null && scratch.issues.isEmpty() && calculated.size == 1) return Run(equations, variables, context).solveSingular(calculated)
        }
        // x² + y² = 25 with x + y = 7: by substitution, solving an equation of one variable
        if (calculated == null && scratch.issues.contains(MathIssue.CANT_SOLVE_GENERIC)) return QuadraticSystemSteps.steps(equations, variables, context) ?: NonlinearSystemSteps.steps(equations, variables, context)
        if (calculated == null || scratch.shouldStop || scratch.issues.isNotEmpty() || calculated.keys != variables.toSet()) return null

        val run = Run(equations, variables, context)
        val known = run.solve() ?: return null
        // The steps must come to the values the calculator found
        if (variables.any { v -> !EquationSteps.sameValue(known[v] ?: return null, calculated.getValue(v), v) }) return null
        val values = variables.map { calculated.getValue(it) }
        return run.finish(Alternatives(variables.zip(values).map { (v, value) -> Equals(v, value) }, inline = true))
    }

    private class Run(val equations: List<Equals>, val variables: List<Variable>, val context: MathContext) {
        private val solution = Solver(context)
        private val root: Path = emptyList()

        /** The equations as they are now (without highlighting), one per line of the system. */
        private var lines: List<Equals> = equations

        /** The values found so far. */
        private val known = mutableMapOf<Variable, Expression>()

        /** The equations solved for a variable, in the order they were solved: line, variable. */
        private val isolated = mutableListOf<Pair<Int, Variable>>()

        fun finish(result: Expression) = solution.finish(result)

        fun solve(): Map<Variable, Expression>? {
            solution.start(normalize(EquationSystem(equations)))
            val eliminated = eliminateIfBetter()
            if (!(eliminated ?: substituteAll())) return null
            // Elimination ends with the last variable on its own: the values are put together
            if (unmarked(solution.current) !is EquationSystem) {
                val all = Alternatives(variables.map { Equals(it, known[it] ?: return null) }, inline = true)
                solution.apply(Match(StepRule.COLLECT_SOLUTION, mapOf(root to all), solution.current, all, showChange = false, boxReplacements = false))
            }
            return known
        }

        /**
         * Two equations in two variables that are dependent (the calculator found one variable in terms of the
         * other, [calculated]) or contradict each other (null): one is solved for a variable and written into
         * the other, which then reads 2 = 2 or 2 = 3.
         */
        fun solveSingular(calculated: Map<Variable, Expression>?): ExpressionSolution? {
            solution.start(normalize(EquationSystem(equations)))
            val wanted = calculated?.keys?.single()
            val all = lines.indices.toList()
            val pick = (if (wanted != null) all.map { Pick(it, wanted, 0.0) }.filter { coefficient(lines[it.line], wanted)?.isZero == false } else emptyList())
                .firstOrNull() ?: choose(all, variables) ?: return null
            if (!isolate(pick.line, pick.variable)) return null
            val other = all.single { it != pick.line }
            if (!substituteInto(pick.line, pick.variable, listOf(other))) return null
            val left = (lines[other].left as? Value)?.real?.value ?: return null
            val right = (lines[other].right as? Value)?.real?.value ?: return null
            if (!left.isFinite() || !right.isFinite()) return null
            if (abs(left - right) > 1e-9 * maxOf(1.0, abs(left), abs(right))) {
                if (calculated != null) return null
                val none = TextExpression("no solution")
                solution.apply(Match(StepRule.CONTRADICTION, mapOf(root to none), solution.current, none, showChange = false, boxReplacements = false))
                return solution.finish(none)
            }
            if (calculated == null) return null
            // x = 2 − y, for any y: what the calculator found
            val free = variables.single { it != pick.variable }
            val expression = lines[pick.line]
            val zeros = mapOf(free to 0.37)
            if (abs(expression.right.evaluateNumerically(zeros) - calculated.getValue(pick.variable).evaluateNumerically(zeros)) > 1e-9) return null
            val result = Alternatives(listOf(expression, TextExpression("${free.identifier} ∈ ℝ")), inline = true)
            solution.apply(Match(StepRule.COLLECT_SOLUTION, mapOf(root to result), solution.current, result, showChange = false, boxReplacements = false))
            return solution.finish(result)
        }

        // ----- substitution -----

        private fun substituteAll(): Boolean {
            val active = lines.indices.toMutableList()
            val unknown = variables.toMutableList()
            while (active.size > 1) {
                val pick = choose(active, unknown) ?: return false
                if (!isolate(pick.line, pick.variable)) return false
                // Written into the other equations, each simplified
                active.remove(pick.line)
                unknown.remove(pick.variable)
                if (!substituteInto(pick.line, pick.variable, active)) return false
                isolated.add(pick.line to pick.variable)
            }
            val last = active.single()
            val variable = unknown.single()
            if (!isolate(last, variable)) return false
            known[variable] = lines[last].right
            return backSubstitute()
        }

        /** Solves the equation at [line] for [v]; it is shown on its own while it is done. */
        private fun isolate(line: Int, v: Variable): Boolean {
            val equation = lines[line]
            if (EquationSteps.isSolved(equation, v)) return true
            solution.apply(
                Match(
                    StepRule.SOLVE_FOR, mapOf(root to equation), systemExpression(), equation,
                    showChange = false, boxReplacements = false, highlight = setOf(listOf(line))
                )
            )
            if (!EquationSteps.solveEquation(solution, root, v, context, MAX_STEPS)) return false
            lines = lines.toMutableList().also { it[line] = normalize(unmarked(solution.current)) as? Equals ?: return false }
            return true
        }

        private fun systemExpression() = EquationSystem(lines)

        /** Writes the solved [line] (for [v]) into the [active] equations and simplifies them. */
        private fun substituteInto(line: Int, v: Variable, active: List<Int>): Boolean {
            val value = Marked(lines[line].right, PENDING_COLOR, boxed = true, keep = true)
            val changed = lines.mapIndexed { j, l ->
                if (j in active && l.containsVariable(v)) l.transform { if (it == v) value else it } as Equals else l
            }
            val system = EquationSystem(changed)
            val onItsOwn = unmarked(solution.current) !is EquationSystem
            if (onItsOwn || changed != lines) {
                solution.apply(
                    Match(
                        StepRule.SUBSTITUTE_SOLUTION, mapOf(root to system), solution.current, system,
                        showChange = false, boxReplacements = false
                    )
                )
            }
            for (j in active) if (!simplifyLine(j)) return false
            lines = currentLines() ?: return false
            return true
        }

        /** Simplifies the equation at [line] of the system step by step. */
        private fun simplifyLine(line: Int): Boolean {
            while (true) {
                if (context.shouldStop || solution.size > MAX_STEPS) return false
                val match = SimplificationSteps.match(at(solution.current, listOf(line)), Value.ZERO, context) ?: return true
                solution.apply(EquationSteps.prefixed(match, listOf(line)))
            }
        }

        private fun currentLines(): List<Equals>? {
            val system = unmarked(solution.current) as? EquationSystem ?: return null
            return system.lines.map { normalize(it) as? Equals ?: return null }
        }

        /** The values found so far written into the equations solved before, last first. */
        private fun backSubstitute(): Boolean {
            for ((line, v) in isolated.reversed()) {
                val equation = lines[line]
                val values = known.filterKeys { equation.right.containsVariable(it) }
                if (values.isNotEmpty()) {
                    val inserted = values.mapValues { Marked(it.value, PENDING_COLOR, boxed = true, keep = true) }
                    val changed = lines.mapIndexed { j, l ->
                        if (j == line) l.transform { if (it is Variable && it in inserted && it != l.left) inserted.getValue(it) else it } as Equals else l
                    }
                    val system = EquationSystem(changed)
                    solution.apply(
                        Match(
                            StepRule.INSERT_VALUE, mapOf(root to system), solution.current, system,
                            showChange = false, boxReplacements = false
                        )
                    )
                    if (!simplifyLine(line)) return false
                    lines = currentLines() ?: return false
                }
                known[v] = lines[line].right
            }
            return true
        }

        private class Pick(val line: Int, val variable: Variable, val score: Double)

        /** The equation and variable that are easiest to solve for: alone, then coefficient ±1. */
        private fun choose(active: List<Int>, unknown: List<Variable>): Pick? {
            var best: Pick? = null
            for (line in active) for (v in unknown) {
                val equation = lines[line]
                val coefficient = coefficient(equation, v) ?: continue
                if (coefficient.isZero) continue
                val score = when {
                    EquationSteps.isSolved(equation, v) -> 0.0
                    !coefficient.isFractional && abs(coefficient.toDouble()) == 1.0 -> 1.0
                    else -> 2.0 + abs(coefficient.toDouble()) + if (coefficient.isFractional) 5.0 else 0.0
                }
                if (best == null || score < best.score) best = Pick(line, v, score)
            }
            return best
        }

        // ----- elimination -----

        /** Two equations without a coefficient 1 (or a variable alone): multiplied, then added or subtracted. */
        private fun eliminateIfBetter(): Boolean? {
            if (lines.size != 2) return null
            val easy = choose(lines.indices.toList(), variables)?.score
            if (easy == null || easy < 2.0) return null
            // The variable whose coefficients have the smallest common multiple
            var best: Triple<Variable, Long, Long>? = null
            for (v in variables) {
                val p = integerCoefficient(lines[0], v) ?: continue
                val q = integerCoefficient(lines[1], v) ?: continue
                if (p == 0L || q == 0L) continue
                val m = lcm(abs(p), abs(q))
                if (best == null || m < lcm(abs(best.second), abs(best.third))) best = Triple(v, p, q)
            }
            val (eliminated, p, q) = best ?: return null
            return eliminate(eliminated, p, q)
        }

        private fun eliminate(eliminated: Variable, p: Long, q: Long): Boolean {
            val remaining = variables.single { it != eliminated }
            val m = lcm(abs(p), abs(q))
            val factors = listOf(m / abs(p), m / abs(q))
            if (factors.any { it != 1L }) {
                val scaled = lines.mapIndexed { j, equation ->
                    if (factors[j] == 1L) equation else {
                        val by = Marked(Value(factors[j]), PENDING_COLOR, boxed = true)
                        Equals(Multiplications(by, equation.left), Multiplications(by, equation.right))
                    }
                }
                val system = EquationSystem(scaled)
                solution.apply(
                    Match(StepRule.MULTIPLY_EQUATIONS, mapOf(root to system), solution.current, system, showChange = false, boxReplacements = false)
                )
                for (j in lines.indices) if (!simplifyLine(j)) return false
                lines = currentLines() ?: return false
            }
            // The same coefficient with the same sign is subtracted, opposite ones are added
            val subtract = (p > 0) == (q > 0)
            fun term(e: Expression) = Marked(if (subtract) Multiplications(Value.MINUS_ONE, e) else e, PENDING_COLOR, boxed = true)
            val combined = Equals(Additions(lines[0].left, term(lines[1].left)), Additions(lines[0].right, term(lines[1].right)))
            solution.apply(
                Match(
                    if (subtract) StepRule.SUBTRACT_EQUATIONS else StepRule.ADD_EQUATIONS,
                    mapOf(root to combined), solution.current, unmarked(combined),
                    showChange = false, boxReplacements = false
                )
            )
            if (!EquationSteps.solveEquation(solution, root, remaining, context, MAX_STEPS)) return false
            val value = (normalize(unmarked(solution.current)) as? Equals)?.right ?: return false
            known[remaining] = value

            // The value written into the first equation, solved for the other variable
            val inserted = Marked(value, PENDING_COLOR, boxed = true, keep = true)
            val equation = equations[0].transform { if (it == remaining) inserted else it } as Equals
            solution.apply(Match(StepRule.INSERT_VALUE, mapOf(root to equation), solution.current, unmarked(equation), showChange = false, boxReplacements = false))
            if (!EquationSteps.solveEquation(solution, root, eliminated, context, MAX_STEPS)) return false
            known[eliminated] = (normalize(unmarked(solution.current)) as? Equals)?.right ?: return false
            return true
        }

        private fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b

        private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        private fun integerCoefficient(equation: Equals, v: Variable): Long? {
            val c = coefficient(equation, v) ?: return null
            return if (c.isFractional) null else c.toDouble().toLong()
        }

        /** The number the variable [v] is multiplied by in [equation] (left − right), found by trying values. */
        private fun coefficient(equation: Equals, v: Variable): Rational? {
            val zeros = equation.variables().associateWith { 0.0 }
            fun difference(value: Double): Double {
                val bindings = zeros + (v to value)
                return equation.left.evaluateNumerically(bindings) - equation.right.evaluateNumerically(bindings)
            }
            val c = difference(1.0) - difference(0.0)
            return if (c.isFinite()) Rational.approximateFromDouble(c) else null
        }
    }
}
