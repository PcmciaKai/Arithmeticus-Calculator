package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Constant
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Factorial
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.NamedFunction
import com.vortessence.calculator.core.math.expressions.NamedFunctionKind
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.Series
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.containsUnit
import com.vortessence.calculator.core.math.expressions.normalFormula
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational

/**
 * The steps of normalpdf, normalcdf, normalinv, binompdf, binomcdf, poissonpdf and poissoncdf for numbers: the
 * formula with the values put in, the coefficient or factorial calculated, then the arithmetic. A cumulative
 * probability is written as the sum of the probabilities of 0 up to k, and written out where that is short.
 */
internal object DistributionSteps {

    private const val MAX_STEPS = 80
    /** The most terms of a cumulative probability that are written out. */
    private const val MAX_WRITTEN_TERMS = 6L
    /** As far as the calculator works with exact fractions. */
    private const val MAX_EXACT = 500L

    private val SUPPORTED = setOf(
        NamedFunctionKind.NORMALPDF, NamedFunctionKind.NORMALCDF, NamedFunctionKind.NORMALINV,
        NamedFunctionKind.BINOMPDF, NamedFunctionKind.BINOMCDF, NamedFunctionKind.POISSONPDF, NamedFunctionKind.POISSONCDF
    )

    fun steps(e: Expression, context: MathContext): ExpressionSolution? {
        val call = e as? NamedFunction ?: return null
        if (call.kind !in SUPPORTED || call.arguments.any { it.variables().isNotEmpty() || it.containsUnit() }) return null
        val scratch = context.copy()
        val canonical = call.canonicalize(scratch)
        if (scratch.shouldStop) return null
        val target = displayForm(canonical, context)
        // The rules do not all get to the result; then it is one last step
        return attempt(call, target, context, simplify = true) ?: attempt(call, target, context, simplify = false)
    }

    private fun attempt(call: NamedFunction, target: Expression, context: MathContext, simplify: Boolean): ExpressionSolution? {
        val solution = Solver(context)
        solution.start(call)
        val done = when (call.kind) {
            NamedFunctionKind.NORMALPDF, NamedFunctionKind.NORMALCDF, NamedFunctionKind.NORMALINV -> normal(call, solution)
            NamedFunctionKind.BINOMPDF, NamedFunctionKind.BINOMCDF -> binomial(call, solution, context)
            else -> poisson(call, solution, context)
        }
        if (!done) return null
        // A sum that is not written out is not worked on inside: the simplifying rules would only shuffle its terms
        if (simplify && !solution.current.containsExpressionMatching { it is Series }) {
            if (!solution.simplifyToward(target, MAX_STEPS)) return null
        } else if (writtenForm(solution.current) != writtenForm(target)) {
            solution.apply(Match(StepRule.SIMPLIFY, mapOf(emptyList<Int>() to target), normalize(unmarked(solution.current)), target))
        }
        return solution.finish(target)
    }

    private fun replaceAll(solution: Solver, rule: StepRule, formula: Expression) {
        solution.apply(Match(rule, mapOf(emptyList<Int>() to formula), unmarked(solution.current), formula))
    }

    private fun normal(call: NamedFunction, solution: Solver): Boolean {
        val rule = when (call.kind) {
            NamedFunctionKind.NORMALPDF -> StepRule.NORMAL_DENSITY
            NamedFunctionKind.NORMALCDF -> StepRule.NORMAL_PROBABILITY
            // Without μ and σ the quantile is the value itself
            else -> if (call.arguments.size < 3) return false else StepRule.NORMAL_QUANTILE
        }
        replaceAll(solution, rule, normalFormula(call.kind, call.arguments))
        return true
    }

    /** [e] as a whole number within [range], or null. */
    private fun whole(e: Expression, range: LongRange, context: MathContext): Long? {
        val scratch = context.copy()
        val rational = (e.canonicalize(scratch) as? Value)?.rational?.takeIf { !it.isFractional } ?: return null
        return rational.numerator.toLong().takeIf { it in range && !scratch.shouldStop }
    }

    private fun binomial(call: NamedFunction, solution: Solver, context: MathContext): Boolean {
        val n = whole(call.arguments[0], 1..MAX_EXACT, context) ?: return false
        val k = whole(call.arguments[2], 0..n, context) ?: return false
        val p = call.arguments[1]
        // At 0 or 1 the probability is trivial
        val chance = p.evaluateNumerically()
        if (!(chance > 0.0 && chance < 1.0)) return false
        val trials = Value(n)
        val failure = Additions(listOf(Value.ONE, p.negate()))
        fun term(i: Expression): Expression = Multiplications(listOf(
            NamedFunction(NamedFunctionKind.NCR, listOf(trials, i)),
            Power(p, i),
            Power(failure, Additions(listOf(trials, i.negate())))
        ))
        if (call.kind == NamedFunctionKind.BINOMPDF) {
            replaceAll(solution, StepRule.BINOMIAL_PROBABILITY, term(Value(k)))
            val path = pathsOf(solution.current) { it is NamedFunction && it.kind == NamedFunctionKind.NCR }.firstOrNull() ?: return false
            val coefficient = Value(BigInt.binomial(n, k))
            solution.apply(Match(StepRule.BINOMIAL_COEFFICIENT, mapOf(path to coefficient), unmarked(at(solution.current, path)), coefficient))
            return true
        }
        val index = Variable("i")
        replaceAll(solution, StepRule.BINOMIAL_CUMULATIVE, Series(false, index, Value.ZERO, Value(k), term(index)))
        if (k <= MAX_WRITTEN_TERMS) {
            val terms = (0..k).map { i ->
                Multiplications(listOf(Value(BigInt.binomial(n, i)), Power(p, Value(i)), Power(failure, Value(n - i))))
            }
            replaceAll(solution, StepRule.WRITE_OUT_SUM, Additions(terms))
        }
        return true
    }

    private fun poisson(call: NamedFunction, solution: Solver, context: MathContext): Boolean {
        val k = whole(call.arguments[1], 0..MAX_EXACT, context) ?: return false
        val mean = call.arguments[0]
        if (!(mean.evaluateNumerically() > 0.0)) return false
        val decay = Power(Constant.E, mean.negate())
        if (call.kind == NamedFunctionKind.POISSONPDF) {
            val formula = Multiplications(listOf(decay, Power(mean, Value(k)), Power(Factorial(Value(k)), Value.MINUS_ONE)))
            replaceAll(solution, StepRule.POISSON_PROBABILITY, formula)
            val path = pathsOf(solution.current) { it is Factorial }.firstOrNull() ?: return false
            val value = Value(BigInt.factorial(k))
            solution.apply(Match(StepRule.FACTORIAL, mapOf(path to value), unmarked(at(solution.current, path)), value))
            return true
        }
        val index = Variable("i")
        val general = Multiplications(listOf(Power(mean, index), Power(Factorial(index), Value.MINUS_ONE)))
        replaceAll(solution, StepRule.POISSON_CUMULATIVE, Multiplications(listOf(decay, Series(false, index, Value.ZERO, Value(k), general))))
        if (k <= MAX_WRITTEN_TERMS) {
            val terms = (0..k).map { i -> Multiplications(listOf(Power(mean, Value(i)), Value(Rational.of(BigInt.ONE, BigInt.factorial(i))))) }
            replaceAll(solution, StepRule.WRITE_OUT_SUM, Multiplications(listOf(decay, Additions(terms))))
        }
        return true
    }
}
