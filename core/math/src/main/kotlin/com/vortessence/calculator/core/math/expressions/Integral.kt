package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.solvers.NumericIntegrator
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
            if (context.mode == CalculationMode.EXACT) {
                exactValue(cLower, cUpper, cIntegrand, numVal, context)?.let { return it }
                if (context.shouldStop) return this
                // Otherwise only recognize results that are (numerically) exact simple fractions,
                // e.g. 1/3. A loose tolerance would turn every irrational result into a fake fraction.
                val rat = Rational.approximateFromDouble(numVal, maxDenominator = MAX_SNAP_DENOMINATOR)
                if (rat != null && abs(rat.toDouble() - numVal) < SNAP_TOLERANCE * max(1.0, abs(numVal))) {
                    return Value(rat)
                }
            }
            return Value(numVal)
        }
        return intExpr
    }

    /**
     * F(b) − F(a) with the symbolic antiderivative F, e.g. π/4 for ∫₀¹ 1/(1+x²) dx, or null if
     * there is none. F(b) − F(a) is wrong if the integrand has a pole between the bounds (it
     * gives −2 for ∫₋₁¹ 1/x² dx), so the result must agree with the [numeric] value.
     */
    private fun exactValue(a: Expression, b: Expression, f: Expression, numeric: Double, context: MathContext): Expression? {
        val antiderivative = Integrator(context).antiderivative(f, variable) ?: return null
        val scratch = context.copy()
        // At an infinite bound F(±∞) is a limit
        fun at(bound: Expression) = if ((bound as? Value)?.real?.isInfinite == true) {
            Limit(antiderivative, variable, bound)
        } else {
            antiderivative.substituteVariables(mapOf(variable to bound))
        }
        val difference = Additions(listOf(at(b), Multiplications(listOf(Value.MINUS_ONE, at(a))))).canonicalize(scratch)
        if (scratch.shouldStop || difference.variables().isNotEmpty()) return null
        val value = difference.evaluateNumerically()
        if (!value.isFinite() || abs(value - numeric) > EXACT_AGREEMENT * max(1.0, abs(numeric))) return null
        return difference
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val a = lowerBound.evaluateNumerically(bindings)
        val b = upperBound.evaluateNumerically(bindings)
        if (a.isNaN() || b.isNaN()) return Double.NaN

        // The integrand sees the outer variables too, with the integration variable bound on top
        val inner = HashMap(bindings)
        fun integrandAt(x: Double): Double {
            inner[this.variable] = x
            return integrand.evaluateNumerically(inner)
        }
        // Adaptive, so it also notices a pole between the bounds (the integral diverges: NaN)
        return NumericIntegrator.integrateImproper(::integrandAt, a, b, MathContext.current())
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
        /**
         * How closely an exact result must match the numeric value. Loose, because the numeric
         * value is less accurate where the integrand is steep; a wrong F(b) − F(a) is far off.
         */
        private const val EXACT_AGREEMENT = 1e-4
    }
}
