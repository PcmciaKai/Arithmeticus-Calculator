package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.solvers.NumericRootFinder
import kotlin.math.abs
import kotlin.math.roundToLong

class Equals(
    val left: Expression,
    val right: Expression
) : Expression() {

    override val children: List<Expression> get() = listOf(left, right)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 2)
        return Equals(children[0], children[1])
    }

    override fun canonicalize(context: MathContext): Expression {
        val cLeft = left.canonicalize(context)
        if (context.shouldStop) return this
        val cRight = right.canonicalize(context)
        if (context.shouldStop) return this
        return Equals(cLeft, cRight)
    }

    /**
     * For an equation without variables: whether it holds. `null` if it cannot be decided.
     */
    fun truthValue(context: MathContext = MathContext.current()): Boolean? {
        val diff = Additions(left, right.negate()).canonicalize(context)
        if (context.shouldStop || diff.variables().isNotEmpty()) return null
        if (diff.isZero) return true
        val numeric = diff.evaluateNumerically()
        if (numeric.isNaN()) return null
        val scale = maxOf(1.0, abs(left.evaluateNumerically()), abs(right.evaluateNumerically()))
        return abs(numeric) <= 1e-12 * scale
    }

    /**
     * Solves for [variable]. Returns `setOf(variable)` if the equation is an identity (true for
     * every value) and an empty set (with an issue reported) if there is no solution.
     */
    fun solveForVariable(variable: Variable, context: MathContext = MathContext.current()): Set<Expression> {
        val solutions = solveCandidates(variable, context)
        if (context.shouldStop || solutions == setOf(variable) || context.complexNumbers) return solutions
        // Simplifying cancels factors: (x² − 4)/(x − 2) = 4 became x + 2 = 4, but x = 2 is no solution of the
        // equation as it was typed, where the denominator is 0
        if (variables() != setOf(variable)) return solutions
        val defined = solutions.filter { solution ->
            if (solution.containsImaginaryUnit()) return@filter true
            val bindings = mapOf(variable to solution.evaluateNumerically())
            left.evaluateNumerically(bindings).isFinite() && right.evaluateNumerically(bindings).isFinite()
        }.toSet()
        if (defined.isEmpty() && solutions.isNotEmpty()) {
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptySet()
        }
        return defined
    }

    private fun solveCandidates(variable: Variable, context: MathContext): Set<Expression> {
        val diff = Additions(left, right.negate()).canonicalize(context)
        if (context.shouldStop) return emptySet()
        // Multiplied out, (x+1)² = x² + 2x + 1 is recognized as an identity and (x+1)² = 4 as a
        // quadratic equation. The numeric solver keeps the original form, which is more accurate.
        val polynomial = if (hasExpandableProducts(diff)) expandProducts(diff).canonicalize(context) else diff
        if (context.shouldStop) return emptySet()

        if (!polynomial.containsVariable(variable)) {
            if (polynomial.isZero) {
                // Infinite solutions (identity)
                return setOf(variable)
            } else {
                context.stopWithError(MathIssue.CONTRADICTION)
                return emptySet()
            }
        }

        // Equal expressions written differently, e.g. a derivative and its simplified form or
        // sin²x + cos²x = 1: true for every value, not a list of numeric "solutions"
        if (sidesAgreeNumerically()) return setOf(variable)

        // Check if diff is a polynomial in variable
        // For linear: a * x + b = 0 => x = -b / a
        // For quadratic: a * x^2 + b * x + c = 0
        val linearSol = trySolveLinear(polynomial, variable, context)
        if (linearSol != null) return setOf(linearSol)
        if (context.shouldStop) return emptySet()

        val quadraticSols = trySolveQuadratic(polynomial, variable, context)
        if (quadraticSols != null) return quadraticSols.toSet()
        if (context.shouldStop) return emptySet()

        trySolvePolynomial(polynomial, variable, context)?.let { return it }
        if (context.shouldStop) return emptySet()

        return solveNumerically(diff, variable, context)
    }

    /**
     * A polynomial of degree 3 or more with a rational root, e.g. x³ − 14x² + 35x − 22: its rational roots by the rational root
     * theorem and what is left of degree 2 by the formula, exactly. (The numeric search only looks for roots close to 0 once it has
     * found some, so 11 would be missed.) Null if there is no rational root to start with.
     */
    private fun trySolvePolynomial(polynomial: Expression, variable: Variable, context: MathContext): Set<Expression>? {
        if (context.complexNumbers) return null
        var rest = PartialFractions.polynomial(polynomial, variable, context) ?: return null
        if (rest.size < 4) return null
        val roots = mutableListOf<Expression>()
        while (rest.size > 3) {
            val root = PartialFractions.rationalRoot(rest) ?: return null
            roots.add(Value(root))
            while (rest.size > 1 && PartialFractions.value(rest, root).isZero) rest = PartialFractions.divideByLinear(rest, root)
        }
        when (rest.size) {
            2 -> roots.add(Value(-rest[0] / rest[1]))
            3 -> {
                val discriminant = rest[1] * rest[1] - Rational.of(4L) * rest[2] * rest[0]
                if (discriminant.isZero) {
                    roots.add(Value(-rest[1] / (Rational.of(2L) * rest[2])))
                } else if (!discriminant.isNegative) {
                    val sqrtD = Power.squareRoot(Value(discriminant)).canonicalize(context)
                    val twoA = Value(Rational.of(2L) * rest[2])
                    roots.add(Multiplications.of(Additions(Value(-rest[1]), sqrtD), twoA.invert()).canonicalize(context))
                    roots.add(Multiplications.of(Additions(Value(-rest[1]), sqrtD.negate()), twoA.invert()).canonicalize(context))
                }
            }
        }
        return roots.sortedBy { it.evaluateNumerically() }.toCollection(LinkedHashSet())
    }

    /**
     * Whether both sides have the same value at a set of irregular sample points (all variables
     * set to them), i.e. the equation is an identity. Points where a side is undefined are
     * skipped; agreement is relative to the values, so 10⁻²⁰·x = 0 is not an identity.
     */
    private fun sidesAgreeNumerically(): Boolean {
        val vars = variables().toList()
        var agreeing = 0
        for (i in IDENTITY_SAMPLES.indices) {
            val bindings = vars.withIndex().associate { (j, v) -> v to IDENTITY_SAMPLES[(i + 3 * j) % IDENTITY_SAMPLES.size] }
            val l = left.evaluateNumerically(bindings)
            val r = right.evaluateNumerically(bindings)
            if (!l.isFinite() || !r.isFinite()) continue
            if (abs(l - r) > IDENTITY_TOLERANCE * maxOf(abs(l), abs(r))) return false
            agreeing++
        }
        return agreeing >= MIN_IDENTITY_SAMPLES
    }

    /**
     * Fallback for equations the symbolic solvers cannot handle, e.g. sin(x) = x / 2.
     * Searches near the origin first and widens the range only if nothing was found.
     */
    /** A few Newton steps from a root found by bisection, kept only where they make it better: 90.00000059 → 90. */
    private fun polish(function: Function1D, root: Double): Double {
        var x = root
        var fx = abs(function.evaluateAt(x))
        repeat(6) {
            val h = 1e-6 * maxOf(1.0, abs(x))
            val slope = (function.evaluateAt(x + h) - function.evaluateAt(x - h)) / (2 * h)
            if (!slope.isFinite() || slope == 0.0) return x
            val next = x - function.evaluateAt(x) / slope
            val fNext = abs(function.evaluateAt(next))
            if (!fNext.isFinite() || fNext >= fx || abs(next - root) > 1e-4 * maxOf(1.0, abs(root))) return x
            x = next
            fx = fNext
        }
        return x
    }

    private fun solveNumerically(diff: Expression, variable: Variable, context: MathContext): Set<Expression> {
        if (diff.variables() != setOf(variable)) {
            context.stopWithError(MathIssue.CANT_SOLVE_GENERIC)
            return emptySet()
        }
        val function = Function1D(diff, variable, context.angleUnit)
        var roots = emptyList<Double>()
        // In degrees a period is 360, not 2π: the same number of periods is searched
        val scale = if (context.angleUnit == com.vortessence.calculator.core.math.context.AngleUnit.DEGREES &&
            diff.containsExpressionMatching { it is TrigonometricFunction }) 180.0 / Math.PI else 1.0
        for ((range, steps) in NUMERIC_SEARCH_RANGES) {
            roots = NumericRootFinder.findRoots(function, -range * scale, range * scale, steps, context)
            if (context.shouldStop) return emptySet()
            if (roots.isNotEmpty()) break
        }
        if (roots.isEmpty()) {
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptySet()
        }
        return roots
            .map { polish(function, it) }
            .sortedBy { abs(it) }
            .take(MAX_NUMERIC_SOLUTIONS)
            .sorted()
            .map { root ->
                val nearest = root.roundToLong()
                // 360.0000002 in degrees is 360, and a root where the curve only touches 0 (found less exactly)
                // is the whole number next to it if that is at least as good a root
                val close = abs(root - nearest) < 1e-9 * maxOf(1.0, abs(root)) ||
                    (abs(root - nearest) < 1e-5 * maxOf(1.0, abs(root)) && abs(function.evaluateAt(nearest.toDouble())) <= abs(function.evaluateAt(root)))
                if (close) Value(nearest) else Value(root)
            }
            .toCollection(LinkedHashSet())
    }

    private fun trySolveLinear(expr: Expression, variable: Variable, context: MathContext): Expression? {
        // Collect terms in Additions
        val terms = if (expr is Additions) expr.operands else listOf(expr)
        var a: Expression = Value.ZERO
        var b: Expression = Value.ZERO

        for (term in terms) {
            if (term.containsVariable(variable)) {
                if (term == variable) {
                    a = Additions(listOf(a, Value.ONE)).canonicalize(context)
                } else if (term is Multiplications) {
                    val nonVar = term.operands.filter { it != variable }
                    // The coefficient must not depend on the variable: x * sin(x) is not linear
                    if (term.operands.count { it == variable } == 1 && nonVar.none { it.containsVariable(variable) }) {
                        a = Additions(listOf(a, Multiplications.of(*nonVar.toTypedArray()))).canonicalize(context)
                    } else {
                        return null // higher degree
                    }
                } else {
                    return null
                }
            } else {
                b = Additions(listOf(b, term)).canonicalize(context)
            }
        }

        if (a.isZero) return null
        // x = -b / a
        return Multiplications.of(b.negate(), a.invert()).canonicalize(context)
    }

    private fun trySolveQuadratic(expr: Expression, variable: Variable, context: MathContext): List<Expression>? {
        val terms = if (expr is Additions) expr.operands else listOf(expr)
        var a: Expression = Value.ZERO
        var b: Expression = Value.ZERO
        var c: Expression = Value.ZERO

        for (term in terms) {
            if (term.containsVariable(variable)) {
                if (term == variable) {
                    b = Additions(listOf(b, Value.ONE)).canonicalize(context)
                } else if (term is Power && term.base == variable && term.exponent == Value.TWO) {
                    a = Additions(listOf(a, Value.ONE)).canonicalize(context)
                } else if (term is Multiplications) {
                    val varFactors = term.operands.filter { it.containsVariable(variable) }
                    val coeffFactors = term.operands.filter { !it.containsVariable(variable) }
                    val coeff = Multiplications(coeffFactors).canonicalize(context)

                    if (varFactors.size == 1 && varFactors[0] == variable) {
                        b = Additions(listOf(b, coeff)).canonicalize(context)
                    } else if (varFactors.size == 1 && varFactors[0] is Power) {
                        val p = varFactors[0] as Power
                        if (p.base == variable && p.exponent == Value.TWO) {
                            a = Additions(listOf(a, coeff)).canonicalize(context)
                        } else {
                            return null
                        }
                    } else if (varFactors.size == 2 && varFactors[0] == variable && varFactors[1] == variable) {
                        a = Additions(listOf(a, coeff)).canonicalize(context)
                    } else {
                        return null
                    }
                } else {
                    return null
                }
            } else {
                c = Additions(listOf(c, term)).canonicalize(context)
            }
        }

        if (a.isZero) return null

        // Discriminant D = b^2 - 4ac
        val bSq = Power(b, Value.TWO).canonicalize(context)
        val fourAC = Multiplications.of(Value(4L), a, c).canonicalize(context)
        val discriminant = Additions(bSq, fourAC.negate()).canonicalize(context)
        val discriminantValue = if (discriminant.variables().isEmpty()) discriminant.evaluateNumerically() else Double.NaN
        // With complex numbers the roots are a ± b·i
        if (discriminantValue < 0.0 && !context.complexNumbers) {
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptyList()
        }
        val sqrtD = Power.squareRoot(discriminant).canonicalize(context)
        val twoA = Multiplications.of(Value.TWO, a).canonicalize(context)

        val sol1 = Multiplications.of(Additions(b.negate(), sqrtD), twoA.invert()).canonicalize(context)
        val sol2 = Multiplications.of(Additions(b.negate(), sqrtD.negate()), twoA.invert()).canonicalize(context)

        return listOf(sol1, sol2)
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val l = left.evaluateNumerically(bindings)
        val r = right.evaluateNumerically(bindings)
        return l - r
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Equals) return false
        return left == other.left && right == other.right
    }

    override fun hashCode(): Int = left.hashCode() * 31 + right.hashCode()

    override fun toString(): String = "$left = $right"

    companion object {
        private const val MAX_NUMERIC_SOLUTIONS = 10

        /** (half-width of the search interval, number of sampling steps) */
        private val NUMERIC_SEARCH_RANGES = listOf(10.0 to 2_000, 1_000.0 to 20_000)

        private val IDENTITY_SAMPLES = listOf(0.37, 1.13, 2.71, -0.83, 0.52, -1.9, 3.3, 0.071, -2.6, 1.61)
        private const val IDENTITY_TOLERANCE = 1e-9
        private const val MIN_IDENTITY_SAMPLES = 6
    }
}
