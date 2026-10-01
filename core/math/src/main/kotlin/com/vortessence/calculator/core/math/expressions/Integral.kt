package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.Rational
import kotlin.math.*

class Integral(
    val lowerBound: Expression,
    val upperBound: Expression,
    val integrand: Expression,
    val variable: Variable
) : Expression() {

    override val children: List<Expression> get() = listOf(lowerBound, upperBound, integrand)

    override fun variables(): Set<Variable> =
        lowerBound.variables() + upperBound.variables() + (integrand.variables() - variable)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 3)
        return Integral(children[0], children[1], children[2], variable)
    }

    override fun canonicalize(context: MathContext): Expression {
        val cLower = lowerBound.canonicalize(context)
        if (context.shouldStop) return this
        val cUpper = upperBound.canonicalize(context)
        if (context.shouldStop) return this
        val cIntegrand = integrand.canonicalize(context)
        if (context.shouldStop) return this
        val intExpr = Integral(cLower, cUpper, cIntegrand, variable)

        if (cLower.variables().isEmpty() && cUpper.variables().isEmpty() &&
            cIntegrand.variables().all { it == variable }
        ) {
            val numVal = MathContext.withContext(context) { intExpr.evaluateNumerically() }
            if (context.shouldStop) return this // interrupted (timeout/cancel), not undefined
            if (numVal.isNaN() || numVal.isInfinite()) {
                context.stopWithError(MathIssue.UNDEFINED)
                return this
            }
            // Only recognize results that are (numerically) exact simple fractions, e.g. 1/3.
            // A loose tolerance would turn every irrational result into a fake fraction.
            if (context.mode == CalculationMode.EXACT) {
                val rat = Rational.approximateFromDouble(numVal, maxDenominator = MAX_SNAP_DENOMINATOR)
                if (rat != null && abs(rat.toDouble() - numVal) < SNAP_TOLERANCE * max(1.0, abs(numVal))) {
                    return Value(rat)
                }
            }
            return Value(numVal)
        }
        return intExpr
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val a = lowerBound.evaluateNumerically(bindings)
        val b = upperBound.evaluateNumerically(bindings)
        if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) return Double.NaN

        // Simpson's rule for numerical integration
        val n = 1000
        val h = (b - a) / n
        // The integrand sees the outer variables too, with the integration variable bound on top
        val inner = HashMap(bindings)
        fun integrandAt(x: Double): Double {
            inner[this.variable] = x
            return integrand.evaluateNumerically(inner)
        }
        var sum = integrandAt(a) + integrandAt(b)

        val context = MathContext.current()
        for (i in 1 until n) {
            if (i % 100 == 0 && context.shouldStop) return Double.NaN
            val x = a + i * h
            val weight = if (i % 2 == 0) 2.0 else 4.0
            sum += weight * integrandAt(x)
        }

        return (h / 3.0) * sum
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Integral) return false
        return lowerBound == other.lowerBound &&
                upperBound == other.upperBound &&
                integrand == other.integrand &&
                variable == other.variable
    }

    override fun hashCode(): Int {
        var result = lowerBound.hashCode()
        result = 31 * result + upperBound.hashCode()
        result = 31 * result + integrand.hashCode()
        result = 31 * result + variable.hashCode()
        return result
    }

    override fun toString(): String = "int($lowerBound to $upperBound) $integrand d$variable"

    companion object {
        private const val MAX_SNAP_DENOMINATOR = 100L
        private const val SNAP_TOLERANCE = 1e-10
    }
}
