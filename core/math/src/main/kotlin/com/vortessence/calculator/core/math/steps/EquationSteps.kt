package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Antiderivative
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Integral
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.UnitExpression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.expandProducts
import com.vortessence.calculator.core.math.numbers.Rational
import kotlin.math.abs
import kotlin.math.max

/**
 * The steps of solving an equation for a variable, as by hand:
 * - linear equations by doing the same on both sides, 3x + 5 = 2x − 1 | − 2x, then | − 5, and
 *   finally dividing by the coefficient,
 * - fractions with the variable in the denominator by multiplying both sides by it,
 * - u² = c by taking the square root of both sides (u = √c or u = −√c),
 * - a product that is zero by setting each factor to zero, after factoring out x where there is
 *   no constant term (x² − 3x = 0 ⇔ x(x − 3) = 0),
 * - other quadratic equations ax² + bx + c = 0 with the quadratic formula,
 * each side simplified step by step in between ([SimplificationSteps]). Equations that are true
 * for every value end with "true".
 *
 * The solutions found are checked against the calculator's ([Equals.solveForVariable]):
 * equations it only solves numerically, or ones the steps solve differently, have no steps.
 */
internal object EquationSteps {

    private const val MAX_STEPS = 150

    private const val MAX_ROOT_SEARCH = 1000.0

    fun steps(equation: Equals, v: Variable, context: MathContext): ExpressionSolution? {
        if (equation.containsExpressionMatching {
                it is MatrixExpression || it is UnitExpression || it is Derivative || it is Antiderivative || it is Integral
            }
        ) return null
        val scratch = context.copy()
        val solutions = equation.solveForVariable(v, scratch).toList()
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || solutions.isEmpty()) return null
        val isIdentity = solutions == listOf(v)

        val solution = Solver(context)
        solution.start(normalize(equation))
        while (true) {
            if (context.shouldStop || solution.size > MAX_STEPS) return null
            val e = solution.current
            // One equation after the other: a solved one (x = 2 − 1) is still simplified first
            val match = pathsOf(e) { it is Equals }.firstNotNullOfOrNull { path ->
                if (isSolved(unmarked(at(e, path)) as Equals, v)) {
                    SimplificationSteps.match(at(e, path), Value.ZERO, context)?.let { prefixed(it, path) }
                } else {
                    Moves(e, path, v, context).match() ?: return null
                }
            } ?: break
            solution.apply(match)
            if (match.rule == StepRule.IDENTITY) break
        }
        if (isIdentity) {
            if (unmarked(solution.current) != TextExpression(TRUE)) return null
            return solution.finish(TextExpression(TRUE))
        }

        // Each branch x = value is one of the calculated solutions; it ends written as that one
        val branches = (unmarked(solution.current).let { if (it is Alternatives) it.equations else listOf(it) })
            .filterNot { it == TextExpression(NO_REAL_SOLUTION) }
            .map { it as? Equals ?: return null }
        val matched = branches.map { branch -> solutions.firstOrNull { sameValue(branch.right, it, v) } ?: return null }
        // A double root is found twice; every calculated solution must be found
        if (matched.toSet().size != solutions.size) return null
        // What the rules did not do, for each value on its own. Where the calculator only found a
        // decimal (solving numerically), the exact value of the steps stays.
        val finalValues = branches.map { normalize(unmarked(it.right)) }
        val values = finalValues.zip(matched).map { (own, calculated) -> if (calculated is Value && calculated.rational == null) own else calculated }
        for ((i, value) in values.withIndex()) {
            if (writtenForm(finalValues[i]) == writtenForm(value)) continue
            val valuePath = if (values.size == 1) listOf(1) else listOf(i, 1)
            solution.apply(Match(StepRule.SIMPLIFY, mapOf(valuePath to value), finalValues[i], value))
        }
        // In the order of the calculated solutions, each once (a double root was found twice)
        val ordered = solutions.map { values[matched.indexOf(it)] }
        return solution.finish(if (ordered.size == 1) Equals(v, ordered.single()) else Alternatives(ordered.map { Equals(v, it) }, inline = true))
    }

    private const val TRUE = "true"

    private const val NO_REAL_SOLUTION = "no real solution"

    /**
     * Solves the equation at [path] of the current expression of [solution] for [v], step by
     * step. False if the steps do not get there.
     */
    fun solveEquation(solution: Solver, path: Path, v: Variable, context: MathContext, maxSteps: Int): Boolean {
        while (true) {
            if (context.shouldStop || solution.size > maxSteps) return false
            val e = solution.current
            val equation = unmarked(at(e, path)) as? Equals ?: return false
            val match = if (isSolved(equation, v)) {
                SimplificationSteps.match(at(e, path), Value.ZERO, context)?.let { prefixed(it, path) } ?: return true
            } else {
                Moves(e, path, v, context).match() ?: return false
            }
            solution.apply(match)
            if (match.rule == StepRule.IDENTITY) return false
        }
    }

    /** [match] found in the equation at [path], with paths from the root. */
    internal fun prefixed(match: Match, path: Path) = Match(
        match.rule,
        match.replacements.mapKeys { path + it.key },
        match.before,
        match.after,
        match.removals.mapKeys { path + it.key },
        match.chainRule,
        match.notes
    )

    /** x = value, with the value not depending on x. */
    internal fun isSolved(equation: Equals, v: Variable) = equation.left == v && !equation.right.containsVariable(v)

    /** Whether [a] and [b] are the same number (for any values of other variables). */
    internal fun sameValue(a: Expression, b: Expression, v: Variable): Boolean {
        val others = (a.variables() + b.variables() - v).toList()
        return listOf(1.37, 0.61, 2.9).all { sample ->
            val bindings = others.withIndex().associate { (i, other) -> other to sample + i * 0.29 }
            val x = unmarked(a).evaluateNumerically(bindings)
            val y = b.evaluateNumerically(bindings)
            x.isFinite() && y.isFinite() && abs(x - y) <= 1e-9 * max(1.0, abs(y))
        }
    }

    /** The next step for the equation at [path] in [e]. */
    internal class Moves(val e: Expression, val path: Path, val v: Variable, val context: MathContext) {
        val equation = unmarked(at(e, path)) as Equals
        val left = equation.left
        val right = equation.right

        fun match(): Match? {
            if (!equation.containsVariable(v)) return identity()
            // (x + 1)² = 4 and (x − 2)(x + 3) = 0 as they are, before they would be multiplied out
            squareRoot(alone = true)?.let { return it }
            zeroProduct()?.let { return it }
            // Each side simplified first, e.g. 3x − 2x combined
            SimplificationSteps.match(at(e, path), Value.ZERO, context)?.let { return prefixed(it, path) }
            squareRoot(alone = false)?.let { return it }
            clearDenominator()?.let { return it }
            val coefficients = polynomial() ?: return null
            return when (coefficients.keys.maxOrNull()) {
                // x + 1 = 1 + x: the variable drops out
                null, 1 -> linear()
                2 -> quadratic(coefficients)
                else -> higherDegree(coefficients)
            }
        }

        private fun canonical(e: Expression): Expression? {
            val scratch = context.copy()
            val result = e.canonicalize(scratch)
            return if (scratch.shouldStop) null else result
        }

        /** 0 = 0: the equation holds for every value (only for the whole problem). */
        private fun identity(): Match? {
            if (path.isNotEmpty()) return null
            val difference = canonical(Additions(left, Multiplications(Value.MINUS_ONE, right))) ?: return null
            if (!difference.isZero) return null
            val result = TextExpression(TRUE)
            return Match(StepRule.IDENTITY, mapOf(path to result), equation, result, showChange = false)
        }


        private fun terms(side: Expression): List<Expression> = if (side is Additions) side.operands else listOf(side)

        private fun sum(terms: List<Expression>): Expression = if (terms.size == 1) terms.single() else Additions(terms)

        /** Adds [term] to both sides (to take it away from a side, add its negation). */
        private fun addToBothSides(term: Expression): Match {
            // − (2x + 3) rather than + (−2x − 3)
            val subtract = term.isNegative || (term is Additions && term.operands.first().isNegative)
            val operand = if (subtract) negated(term) else term
            val added = Marked(if (subtract && operand is Additions) Multiplications(Value.MINUS_ONE, operand) else term, PENDING_COLOR, boxed = true)
            val result = Equals(Additions(left, added), Additions(right, added))
            return Match(
                if (subtract) StepRule.SUBTRACT_FROM_BOTH_SIDES else StepRule.ADD_TO_BOTH_SIDES,
                mapOf(path to result),
                equation,
                unmarked(result),
                operation = BothSides(if (subtract) BothSides.Kind.SUBTRACT else BothSides.Kind.ADD, operand),
                showChange = false,
                boxReplacements = false
            )
        }

        /** Multiplies both sides by [factor], or with [divide] divides them by it. */
        private fun multiplyBothSides(factor: Expression, divide: Boolean = false): Match {
            val by = Marked(if (divide) Power(factor, Value.MINUS_ONE) else factor, PENDING_COLOR, boxed = true)
            val result = Equals(Multiplications(left, by), Multiplications(right, by))
            return Match(
                if (divide) StepRule.DIVIDE_BOTH_SIDES else StepRule.MULTIPLY_BOTH_SIDES,
                mapOf(path to result),
                equation,
                unmarked(result),
                operation = BothSides(if (divide) BothSides.Kind.DIVIDE else BothSides.Kind.MULTIPLY, factor),
                showChange = false,
                boxReplacements = false
            )
        }

        /** −[term] with the sign taken off its coefficient: 2x for −2x. */
        private fun negated(term: Expression): Expression = canonical(Multiplications(Value.MINUS_ONE, term)) ?: term

        /**
         * u² = c ⇔ u = √c or u = −√c (u = 0 for c = 0); unless [alone], a·u² = c is divided by a
         * first (once the sides are simplified, so that a is a single number).
         */
        private fun squareRoot(alone: Boolean): Match? {
            if (right.containsVariable(v)) return null
            val factors = if (left is Multiplications) left.operands else listOf(left)
            val square = factors.singleOrNull { it is Power && it.exponent == Value.TWO && it.base.containsVariable(v) } as? Power ?: return null
            val others = factors.filter { it !== square }
            if (others.any { it.containsVariable(v) }) return null
            if (others.isNotEmpty()) {
                if (alone) return null
                return multiplyBothSides(Multiplications.of(*others.toTypedArray()), divide = true)
            }
            val value = canonical(right) ?: return null
            if (value.isNegative) {
                // A square is never negative; only one case among several can be left out
                if (path.isEmpty()) return null
                val none = TextExpression(NO_REAL_SOLUTION)
                return Match(StepRule.NO_REAL_SOLUTION, mapOf(path to none), equation, none, showChange = false)
            }
            val result: Expression = if (value.isZero) {
                Equals(square.base, Value.ZERO)
            } else {
                val root = Power(right, Value(1L, 2L))
                Alternatives(listOf(Equals(square.base, root), Equals(square.base, Multiplications(Value.MINUS_ONE, root))))
            }
            return Match(
                StepRule.SQUARE_ROOT_BOTH_SIDES,
                mapOf(path to result),
                equation,
                result,
                operation = BothSides(BothSides.Kind.SQUARE_ROOT),
                showChange = false
            )
        }

        /** a·b = 0 ⇔ a = 0 or b = 0, for the factors that contain the variable. */
        private fun zeroProduct(): Match? {
            if (!right.isZero || left !is Multiplications) return null
            val factors = left.operands.filter { it.containsVariable(v) }
            if (factors.size < 2) return null
            val result = Alternatives(factors.map { Equals(it, Value.ZERO) })
            return Match(StepRule.ZERO_PRODUCT, mapOf(path to result), equation, result, showChange = false)
        }

        /** 1/x + … = …: both sides multiplied by the denominator that contains the variable. */
        private fun clearDenominator(): Match? {
            for (term in terms(left) + terms(right)) {
                val factors = if (term is Multiplications) term.operands else listOf(term)
                for (factor in factors) {
                    val exponent = (factor as? Power)?.exponent as? Value ?: continue
                    if (!exponent.isNegative || !factor.base.containsVariable(v)) continue
                    val positive = exponent.negate()
                    return multiplyBothSides(if (positive == Value.ONE) factor.base else Power(factor.base, positive))
                }
            }
            return null
        }

        /** The coefficients of the equation moved to one side, by power of the variable. */
        fun polynomial(): Map<Int, Expression>? {
            val difference = canonical(expandProducts(Additions(left, Multiplications(Value.MINUS_ONE, right)))) ?: return null
            val result = mutableMapOf<Int, Expression>()
            for (term in terms(difference)) {
                val factors = if (term is Multiplications) term.operands else listOf(term)
                var degree = 0
                val coefficient = mutableListOf<Expression>()
                for (factor in factors) {
                    when {
                        factor == v -> degree += 1
                        factor is Power && factor.base == v -> {
                            val n = (factor.exponent as? Value)?.rational ?: return null
                            if (n.isFractional || n.isNegative) return null
                            degree += n.numerator.value.toInt()
                        }
                        factor.containsVariable(v) -> return null
                        else -> coefficient.add(factor)
                    }
                }
                val c = Multiplications.of(*coefficient.toTypedArray())
                result[degree] = result[degree]?.let { Additions(it, c) } ?: c
            }
            return result.mapValues { canonical(it.value) ?: return null }.filterValues { !it.isZero }
        }

        /** The variable to the left, the numbers to the right, then divided by the coefficient. */
        private fun linear(): Match? {
            val leftTerms = terms(left)
            val rightTerms = terms(right)
            val (leftWith, leftWithout) = leftTerms.partition { it.containsVariable(v) }
            val rightWith = rightTerms.filter { it.containsVariable(v) }
            if (leftWith.isEmpty() && rightWith.isNotEmpty()) {
                val result = Equals(right, left)
                return Match(StepRule.SWAP_SIDES, mapOf(path to result), equation, result, showChange = false)
            }
            if (rightWith.isNotEmpty()) return addToBothSides(negated(sum(rightWith)))
            if (leftWithout.isNotEmpty()) return addToBothSides(negated(sum(leftWithout)))
            val term = leftWith.singleOrNull() ?: return null
            val coefficient = canonical(Multiplications(term, Power(v, Value.MINUS_ONE))) ?: return null
            if (coefficient.containsVariable(v) || coefficient.isZero) return null
            val rational = (coefficient as? Value)?.rational
            // 2/3·x: multiplied by 3/2 rather than divided by 2/3
            if (rational != null && rational.isFractional) return multiplyBothSides(Value(rational.denominator, rational.numerator))
            return multiplyBothSides(coefficient, divide = true)
        }

        private fun quadratic(coefficients: Map<Int, Expression>): Match? {
            val leftTerms = terms(left)
            val rightTerms = terms(right)
            if (coefficients[1] == null) {
                // No linear term: x² on its own, then the square root
                val rightWith = rightTerms.filter { it.containsVariable(v) }
                if (rightWith.isNotEmpty()) return addToBothSides(negated(sum(rightWith)))
                val leftWithout = leftTerms.filterNot { it.containsVariable(v) }
                if (leftWithout.isNotEmpty()) return addToBothSides(negated(sum(leftWithout)))
                return null
            }
            // Everything to the left: ax² + bx + c = 0
            if (!right.isZero) return addToBothSides(negated(right))
            if (coefficients[0] == null) return factorOut()
            return formula(coefficients)
        }

        /**
         * Polynomials of degree 3 and up with whole number coefficients: everything to the left,
         * then a rational root r (found by trying ±p/q, p dividing the constant term and q the
         * leading coefficient) splits off the factor (x − r), see [ZERO_PRODUCT].
         */
        private fun higherDegree(coefficients: Map<Int, Expression>): Match? {
            if (!right.isZero) return addToBothSides(negated(right))
            val degree = coefficients.keys.max()
            val numbers = (0..degree).map { k ->
                val c = coefficients[k] ?: return@map Rational.ZERO
                (c as? Value)?.rational?.takeIf { !it.isFractional } ?: return null
            }
            val root = rationalRoot(numbers) ?: return null
            val p = root.numerator.toDouble().toLong()
            val q = root.denominator.toDouble().toLong()
            // Q with P = (x − r)·Q' and (qx − p) = q·(x − r): Q = Q'/q
            val quotient = ArrayList<Rational>()
            var carry = Rational.ZERO
            for (k in degree downTo 1) {
                carry = numbers[k] + carry * root
                quotient.add(carry)
            }
            val scaled = quotient.map { it / Rational.of(q) }
            if (scaled.any { it.isFractional }) return null
            val quotientExpression = canonical(
                Additions(scaled.mapIndexed { i, c -> Multiplications(Value(c), Power(v, Value((degree - 1 - i).toLong()))) })
            ) ?: return null
            val factor: Expression = when {
                p == 0L -> v
                q == 1L -> Additions(v, Value(-p))
                else -> Additions(Multiplications(Value(q), v), Value(-p))
            }
            val factored = Multiplications(factor, quotientExpression)
            // P(r) written out, highest power first: 1³ − 6·1² + 11·1 − 6
            val check = Additions((degree downTo 0).mapNotNull { k ->
                val c = numbers[k]
                when {
                    c.isZero -> null
                    k == 0 -> Value(c)
                    k == 1 -> WrittenOut(listOf(Value(c), Value(root)))
                    else -> Multiplications(Value(c), Power(Value(root), Value(k.toLong())))
                }
            })
            val shown = if (root.isNegative) "−${root.abs()}" else "$root"
            val note = Note(TextExpression("P($shown)"), Equals(check, Value.ZERO))
            return Match(StepRule.FACTOR_BY_ROOT, mapOf(path + 0 to factored), left, factored, notes = listOf(note))
        }

        /** The first rational root of the polynomial with whole number [coefficients] (constant term first). */
        private fun rationalRoot(coefficients: List<Rational>): Rational? {
            if (coefficients.first().isZero) return Rational.ZERO
            val constant = coefficients.first().numerator.abs().toDouble()
            val leading = coefficients.last().numerator.abs().toDouble()
            if (constant > MAX_ROOT_SEARCH || leading > MAX_ROOT_SEARCH) return null
            fun divisors(n: Long) = (1..n).filter { n % it == 0L }
            val candidates = divisors(constant.toLong()).flatMap { p -> divisors(leading.toLong()).map { q -> Rational.of(p, q) } }
                .distinct().sortedBy { it.toDouble() }.flatMap { listOf(it, -it) }
            return candidates.firstOrNull { r ->
                coefficients.foldIndexed(Rational.ZERO) { k, sum, c -> sum + c * r.pow(k.toLong()) }.isZero
            }
        }

        /** ax² + bx = x(ax + b) */
        private fun factorOut(): Match? {
            val inner = terms(left).map { canonical(Multiplications(it, Power(v, Value.MINUS_ONE))) ?: return null }
            if (inner.any { it.containsVariable(v) && it != v && !(it is Multiplications && it.operands.contains(v)) }) return null
            val factored = Multiplications(v, Additions(inner))
            return Match(StepRule.FACTOR_OUT, mapOf(path + 0 to factored), left, factored)
        }

        /** x = (−b ± √D)/(2a) with D = b² − 4ac, for numbers a, b and c. */
        private fun formula(coefficients: Map<Int, Expression>): Match? {
            val a = coefficients[2] as? Value ?: return null
            val b = coefficients[1] as? Value ?: return null
            val c = coefficients[0] as? Value ?: return null
            if (a.rational == null || b.rational == null || c.rational == null) return null
            val discriminant = Additions(Power(b, Value.TWO), Multiplications(Value(-4L), a, c))
            val d = canonical(discriminant) as? Value ?: return null
            // b² − 4·a·c with the numbers as they are: 1² − 4·1·(−1)
            val writtenOut = Additions(Power(b, Value.TWO), Multiplications(Value.MINUS_ONE, WrittenOut(listOf(Value(4L), a, c))))
            val notes = listOf(
                Note(Variable("a"), a),
                Note(Variable("b"), b),
                Note(Variable("c"), c),
                Note(Variable("D"), Equals(writtenOut, d))
            )
            // A negative discriminant: no real solution, which only matters as one of several cases
            if (d.isNegative) {
                if (path.isEmpty()) return null
                val none = TextExpression(NO_REAL_SOLUTION)
                return Match(StepRule.NO_REAL_SOLUTION, mapOf(path to none), equation, none, notes = notes, showChange = false)
            }
            val minusB = b.negate()
            val twoA = Multiplications(Value.TWO, a)
            val result: Expression = if (d.isZero) {
                Equals(v, Multiplications(minusB, Power(twoA, Value.MINUS_ONE)))
            } else {
                val root = Power(d, Value(1L, 2L))
                Alternatives(listOf(
                    Equals(v, Multiplications(Additions(minusB, root), Power(twoA, Value.MINUS_ONE))),
                    Equals(v, Multiplications(Additions(minusB, Multiplications(Value.MINUS_ONE, root)), Power(twoA, Value.MINUS_ONE)))
                ))
            }
            return Match(StepRule.QUADRATIC_FORMULA, mapOf(path to result), equation, result, notes = notes, showChange = false)
        }
    }
}
