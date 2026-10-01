package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
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
        val diff = Additions(left, right.negate()).canonicalize(context)
        if (context.shouldStop) return emptySet()

        if (!diff.containsVariable(variable)) {
            if (diff.isZero) {
                // Infinite solutions (identity)
                return setOf(variable)
            } else {
                context.stopWithError(MathIssue.CONTRADICTION)
                return emptySet()
            }
        }

        // Check if diff is a polynomial in variable
        // For linear: a * x + b = 0 => x = -b / a
        // For quadratic: a * x^2 + b * x + c = 0
        val linearSol = trySolveLinear(diff, variable, context)
        if (linearSol != null) return setOf(linearSol)
        if (context.shouldStop) return emptySet()

        val quadraticSols = trySolveQuadratic(diff, variable, context)
        if (quadraticSols != null) return quadraticSols.toSet()
        if (context.shouldStop) return emptySet()

        return solveNumerically(diff, variable, context)
    }

    /**
     * Fallback for equations the symbolic solvers cannot handle, e.g. sin(x) = x / 2.
     * Searches near the origin first and widens the range only if nothing was found.
     */
    private fun solveNumerically(diff: Expression, variable: Variable, context: MathContext): Set<Expression> {
        if (diff.variables() != setOf(variable)) {
            context.stopWithError(MathIssue.CANT_SOLVE_GENERIC)
            return emptySet()
        }
        val function = Function1D(diff, variable, context.angleUnit)
        var roots = emptyList<Double>()
        for ((range, steps) in NUMERIC_SEARCH_RANGES) {
            roots = NumericRootFinder.findRoots(function, -range, range, steps, context)
            if (context.shouldStop) return emptySet()
            if (roots.isNotEmpty()) break
        }
        if (roots.isEmpty()) {
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptySet()
        }
        return roots
            .sortedBy { abs(it) }
            .take(MAX_NUMERIC_SOLUTIONS)
            .sorted()
            .map { root ->
                val nearest = root.roundToLong()
                if (abs(root - nearest) < 1e-9) Value(nearest) else Value(root)
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
        if (discriminantValue < 0.0) {
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
    }
}
