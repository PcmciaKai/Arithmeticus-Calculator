package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Functions written by name with their arguments separated by commas, e.g. stdev(2, 4, 4, 5)
 * or nCr(5, 2). [minArguments]..[maxArguments] arguments are allowed.
 */
enum class NamedFunctionKind(val functionName: String, val minArguments: Int, val maxArguments: Int = Int.MAX_VALUE) {
    MEAN("mean", 1),
    MEDIAN("median", 1),
    /** Sample standard deviation s, dividing by n − 1. */
    STDEV("stdev", 2),
    /** Population standard deviation σ, dividing by n. */
    STDEVP("stdevp", 1),
    /** Sample variance s². */
    VAR("var", 2),
    /** Population variance σ². */
    VARP("varp", 1),
    /** Standard error of the mean, s/√n. */
    SEM("sem", 2),
    MIN("min", 1),
    MAX("max", 1),
    /** Binomial coefficient: ways to choose k of n. */
    NCR("nCr", 2, 2),
    /** Permutations: ordered ways to pick k of n. */
    NPR("nPr", 2, 2),
    GCD("gcd", 1),
    LCM("lcm", 1),
    /** Round half away from zero, optionally to a number of decimal places: round(x, 2). */
    ROUND("round", 1, 2),
    FLOOR("floor", 1, 1),
    CEIL("ceil", 1, 1);

    companion object {
        fun byName(name: String): NamedFunctionKind? = entries.firstOrNull { it.functionName == name }
    }
}

/**
 * A [NamedFunctionKind] applied to [arguments]. It is calculated as soon as all arguments are
 * known (without free variables) and stays as it is until then. Statistics are exact and keep
 * units: mean(1, 2) = 3/2, stdev(1 m, 3 m) = √2 m.
 */
class NamedFunction(val kind: NamedFunctionKind, val arguments: List<Expression>) : Expression() {

    override val children: List<Expression> get() = arguments

    override fun copyWithChildren(children: List<Expression>): Expression = NamedFunction(kind, children)

    override fun canonicalize(context: MathContext): Expression {
        val args = arguments.map { it.canonicalize(context) }
        if (context.shouldStop) return this
        if (args.size !in kind.minArguments..kind.maxArguments) return fail(context, MathIssue.ARGUMENT_COUNT)
        if (args.any { arg -> arg.containsExpressionMatching { it is MatrixExpression } }) {
            return fail(context, MathIssue.MATRIX_UNSUPPORTED)
        }
        if (args.any { it.variables().isNotEmpty() }) return NamedFunction(kind, args)
        // Only the mean is meaningful for complex numbers
        if (kind != NamedFunctionKind.MEAN && args.any { it.containsImaginaryUnit() }) return fail(context, MathIssue.UNDEFINED)

        val n = args.size.toLong()
        return when (kind) {
            NamedFunctionKind.MEAN -> mean(args)
            NamedFunctionKind.VAR -> variance(args, n - 1)
            NamedFunctionKind.VARP -> variance(args, n)
            NamedFunctionKind.STDEV -> Power.squareRoot(variance(args, n - 1))
            NamedFunctionKind.STDEVP -> Power.squareRoot(variance(args, n))
            NamedFunctionKind.SEM -> Power.squareRoot(Multiplications.of(variance(args, n - 1), Value(1L, n)))
            NamedFunctionKind.MEDIAN -> {
                val sorted = sortedByValue(args, context) ?: return this
                val middle = sorted.size / 2
                if (sorted.size % 2 == 1) sorted[middle] else mean(listOf(sorted[middle - 1], sorted[middle]))
            }
            NamedFunctionKind.MIN -> sortedByValue(args, context)?.first() ?: return this
            NamedFunctionKind.MAX -> sortedByValue(args, context)?.last() ?: return this
            NamedFunctionKind.GCD, NamedFunctionKind.LCM -> {
                val values = args.map { wholeNumber(it) ?: return fail(context, MathIssue.WHOLE_NUMBERS_ONLY) }
                Value(values.reduce { a, b -> if (kind == NamedFunctionKind.GCD) a.gcd(b) else lcm(a, b) }.abs())
            }
            NamedFunctionKind.NCR, NamedFunctionKind.NPR -> {
                val (total, chosen) = args.map { wholeNumber(it) ?: return fail(context, MathIssue.WHOLE_NUMBERS_ONLY) }
                if (total.isNegative || chosen.isNegative) return fail(context, MathIssue.UNDEFINED)
                if (total > BigInt(MAX_COMBINATORICS)) return fail(context, MathIssue.VALUES_TOO_LARGE)
                val k = chosen.toLong()
                when {
                    chosen > total -> Value.ZERO
                    kind == NamedFunctionKind.NCR -> Value(BigInt.binomial(total.toLong(), k))
                    else -> Value((0 until k).fold(BigInt.ONE) { product, i -> product * (total - i) })
                }
            }
            NamedFunctionKind.ROUND, NamedFunctionKind.FLOOR, NamedFunctionKind.CEIL -> {
                val places = if (args.size == 2) {
                    wholeNumber(args[1])?.takeIf { it.compareTo(MAX_PLACES) <= 0 && it.compareTo(-MAX_PLACES) >= 0 }?.toLong()?.toInt()
                        ?: return fail(context, MathIssue.WHOLE_NUMBERS_ONLY)
                } else {
                    0
                }
                round(args[0], places, context) ?: return this
            }
        }.canonicalize(context)
    }

    private fun fail(context: MathContext, issue: MathIssue): Expression {
        context.stopWithError(issue)
        return this
    }

    private fun mean(args: List<Expression>): Expression =
        Multiplications.of(Additions(args), Value(1L, args.size.toLong()))

    /** The sum of the squared deviations from the mean, divided by [divisor] (n or n − 1). */
    private fun variance(args: List<Expression>, divisor: Long): Expression {
        val mean = mean(args).canonicalize()
        val squares = args.map { Power(Additions(it, mean.negate()), Value.TWO) }
        return Multiplications.of(Additions(squares), Value(1L, divisor))
    }

    /** [args] in increasing order, compared in SI units; null (with an error) if they cannot be ordered. */
    private fun sortedByValue(args: List<Expression>, context: MathContext): List<Expression>? {
        val dimensions = args.map { dimensionOf(it) }.toSet()
        if (dimensions.size > 1) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return null
        }
        val keyed = args.map { it to it.withUnitsAsSiFactors().canonicalize(context).evaluateNumerically(emptyMap()) }
        if (keyed.any { it.second.isNaN() }) {
            context.stopWithError(MathIssue.UNDEFINED)
            return null
        }
        return keyed.sortedBy { it.second }.map { it.first }
    }

    private fun wholeNumber(expr: Expression): BigInt? =
        (expr as? Value)?.rational?.takeIf { !it.isFractional }?.numerator

    private fun lcm(a: BigInt, b: BigInt): BigInt =
        if (a == BigInt.ZERO || b == BigInt.ZERO) BigInt.ZERO else (a * b).abs() / a.gcd(b)

    /** [value] rounded to [places] decimal places as [kind] says; exact for fractions. */
    private fun round(value: Expression, places: Int, context: MathContext): Expression? {
        if (value.containsUnit()) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return null
        }
        val mode = when (kind) {
            NamedFunctionKind.FLOOR -> RoundingMode.FLOOR
            NamedFunctionKind.CEIL -> RoundingMode.CEILING
            else -> RoundingMode.HALF_UP
        }
        val rational = (value as? Value)?.rational
        val rounded = if (rational != null) {
            // Rounding the exact quotient, so 1/3 or 2.5 are rounded correctly
            BigDecimal(rational.numerator.value).divide(BigDecimal(rational.denominator.value), places, mode)
        } else {
            val d = value.evaluateNumerically(emptyMap())
            if (d.isNaN() || d.isInfinite()) {
                context.stopWithError(MathIssue.UNDEFINED)
                return null
            }
            BigDecimal(d).setScale(places, mode)
        }
        val unscaled = BigInt(rounded.unscaledValue())
        val power = BigInt(BigInteger.TEN.pow(abs(rounded.scale())))
        return Value(if (rounded.scale() >= 0) Rational.of(unscaled, power) else Rational.of(unscaled * power))
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val values = arguments.map { it.evaluateNumerically(bindings) }
        if (values.any { it.isNaN() } || values.size !in kind.minArguments..kind.maxArguments) return Double.NaN
        val mean = values.average()
        fun squares() = values.sumOf { (it - mean) * (it - mean) }
        val n = values.size
        return when (kind) {
            NamedFunctionKind.MEAN -> mean
            NamedFunctionKind.VAR -> squares() / (n - 1)
            NamedFunctionKind.VARP -> squares() / n
            NamedFunctionKind.STDEV -> sqrt(squares() / (n - 1))
            NamedFunctionKind.STDEVP -> sqrt(squares() / n)
            NamedFunctionKind.SEM -> sqrt(squares() / (n - 1) / n)
            NamedFunctionKind.MEDIAN -> values.sorted().let { if (n % 2 == 1) it[n / 2] else (it[n / 2 - 1] + it[n / 2]) / 2 }
            NamedFunctionKind.MIN -> values.min()
            NamedFunctionKind.MAX -> values.max()
            NamedFunctionKind.FLOOR -> floor(values[0])
            NamedFunctionKind.CEIL -> ceil(values[0])
            NamedFunctionKind.ROUND -> {
                val places = values.getOrElse(1) { 0.0 }
                if (places != floor(places)) Double.NaN
                else BigDecimal(values[0]).setScale(places.toInt(), RoundingMode.HALF_UP).toDouble()
            }
            // Whole-number functions are only defined for whole numbers
            else -> if (values.any { it != floor(it) }) Double.NaN else when (kind) {
                NamedFunctionKind.NCR -> BigInt.binomial(values[0].toLong(), values[1].toLong()).toDouble()
                NamedFunctionKind.NPR -> (0 until values[1].toLong()).fold(1.0) { product, i -> product * (values[0] - i) }
                NamedFunctionKind.GCD -> values.map { BigInt(it.toLong()) }.reduce { a, b -> a.gcd(b) }.toDouble()
                else -> values.map { BigInt(it.toLong()) }.reduce(::lcm).abs().toDouble()
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is NamedFunction && kind == other.kind && arguments == other.arguments

    override fun hashCode(): Int = kind.hashCode() * 31 + arguments.hashCode()

    override fun toString(): String = "${kind.functionName}(${arguments.joinToString(", ")})"

    private companion object {
        const val MAX_COMBINATORICS = 100_000L
        val MAX_PLACES = BigInt(100L)
    }
}
