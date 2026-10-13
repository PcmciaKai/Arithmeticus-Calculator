package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.numbers.SpecialMath
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.truncate

/**
 * Functions written by name with their arguments separated by commas, e.g. stdev(2, 4, 4, 5)
 * or nCr(5, 2). [minArguments]..[maxArguments] arguments are allowed.
 */
enum class NamedFunctionKind(val functionName: String, val minArguments: Int, val maxArguments: Int = Int.MAX_VALUE) {
    MEAN("mean", 1),
    MEDIAN("median", 1),
    /** The value that occurs most often (the first of them if several do); undefined if no value repeats. */
    MODE("mode", 1),
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
    /** Largest value minus smallest value. */
    RANGE("range", 1),
    SUM("sum", 1),
    PROD("prod", 1),
    /** The sum of the squares of the values. */
    SUMSQ("sumsq", 1),
    /** percentile(p, values...): the p-th percentile (0 to 100), interpolating between neighbouring values. */
    PERCENTILE("percentile", 2),
    /** quartile(q, values...): the q-th quartile (0 to 4), the same as the percentile 25·q. */
    QUARTILE("quartile", 2),
    /** Population skewness, the mean cubed deviation divided by σ³; needs at least three values that are not all equal. */
    SKEW("skew", 3),
    /** Binomial coefficient: ways to choose k of n. */
    NCR("nCr", 2, 2),
    /** Permutations: ordered ways to pick k of n. */
    NPR("nPr", 2, 2),
    /** multinomial(k1, k2, ...): ways to split k1 + k2 + ... items into groups of these sizes. */
    MULTINOMIAL("multinomial", 1),
    /** The n-th Fibonacci number, 0, 1, 1, 2, 3, 5, ... */
    FIB("fib", 1, 1),
    /** The smallest prime above n. */
    NEXTPRIME("nextprime", 1, 1),
    /** 1 if n is prime, otherwise 0. */
    ISPRIME("isprime", 1, 1),
    GCD("gcd", 1),
    LCM("lcm", 1),
    /** Round half away from zero, optionally to a number of decimal places: round(x, 2). */
    ROUND("round", 1, 2),
    FLOOR("floor", 1, 1),
    CEIL("ceil", 1, 1),
    /** Cut off the decimals (round towards zero), optionally keeping some places: trunc(x, 2). */
    TRUNC("trunc", 1, 2),
    /** The decimals of x with the sign of x: frac(−2.75) = −0.75. */
    FRAC("frac", 1, 1),
    /** −1, 0 or 1 for a negative, zero or positive number. */
    SIGN("sign", 1, 1),
    /** The beta function Γ(a)·Γ(b)/Γ(a + b). */
    BETA("beta", 2, 2),
    /** √(a² + b² + ...), the length of the vector (a, b, ...). */
    HYPOT("hypot", 2),
    /** The real part of a complex number. */
    RE("re", 1, 1),
    /** The imaginary part of a complex number. */
    IM("im", 1, 1),
    /** The angle of a complex number to the positive real axis, in radians from −π (excluded) to π. */
    ARG("arg", 1, 1),
    /** The complex conjugate a − b·i of a + b·i. */
    CONJ("conj", 1, 1);

    companion object {
        fun byName(name: String): NamedFunctionKind? = entries.firstOrNull { it.functionName == name }
    }
}

/**
 * A [NamedFunctionKind] applied to [arguments]. It is calculated as soon as all arguments are
 * known (without free variables) and stays as it is until then, except for the ones that are
 * plain arithmetic (sum, prod, sumsq, hypot, re, im, conj), which are written out at once.
 * Statistics are exact and keep units: mean(1, 2) = 3/2, stdev(1 m, 3 m) = √2 m.
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
        // Only some are meaningful for complex numbers
        if (kind !in COMPLEX_KINDS && args.any { it.containsImaginaryUnit() }) return fail(context, MathIssue.UNDEFINED)
        arithmetic(args, context)?.let { return it }
        if (args.any { it.variables().isNotEmpty() }) return NamedFunction(kind, args)

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
            NamedFunctionKind.MODE -> {
                val keyed = keyedByValue(args, context) ?: return this
                val groups = keyed.groupBy { it.second }.values
                val most = groups.maxOf { it.size }
                if (most == 1) return fail(context, MathIssue.UNDEFINED)
                groups.first { it.size == most }.first().first
            }
            NamedFunctionKind.MIN -> sortedByValue(args, context)?.first() ?: return this
            NamedFunctionKind.MAX -> sortedByValue(args, context)?.last() ?: return this
            NamedFunctionKind.RANGE -> {
                val sorted = sortedByValue(args, context) ?: return this
                Additions(listOf(sorted.last(), sorted.first().negate()))
            }
            NamedFunctionKind.PERCENTILE, NamedFunctionKind.QUARTILE -> percentile(args, context) ?: return this
            NamedFunctionKind.SKEW -> skewness(args, context) ?: return this
            NamedFunctionKind.SIGN -> {
                val key = keyedByValue(args, context)?.single()?.second ?: return this
                Value(sign(key).toLong())
            }
            NamedFunctionKind.ARG -> argument(args[0], context) ?: return this
            NamedFunctionKind.GCD, NamedFunctionKind.LCM, NamedFunctionKind.NCR, NamedFunctionKind.NPR,
            NamedFunctionKind.MULTINOMIAL, NamedFunctionKind.FIB, NamedFunctionKind.NEXTPRIME, NamedFunctionKind.ISPRIME -> {
                val numbers = args.map { wholeNumber(it) ?: return fail(context, MathIssue.WHOLE_NUMBERS_ONLY) }
                when (val outcome = wholeNumberFunction(numbers)) {
                    is Whole.Result -> Value(outcome.number)
                    is Whole.Failed -> return fail(context, outcome.issue)
                }
            }
            NamedFunctionKind.ROUND, NamedFunctionKind.FLOOR, NamedFunctionKind.CEIL, NamedFunctionKind.TRUNC, NamedFunctionKind.FRAC -> {
                val places = if (args.size == 2) {
                    wholeNumber(args[1])?.takeIf { it.compareTo(MAX_PLACES) <= 0 && it.compareTo(-MAX_PLACES) >= 0 }?.toLong()?.toInt()
                        ?: return fail(context, MathIssue.WHOLE_NUMBERS_ONLY)
                } else {
                    0
                }
                val rounded = round(args[0], places, context) ?: return this
                if (kind == NamedFunctionKind.FRAC) Additions(listOf(args[0], rounded.negate())) else rounded
            }
            // Written out above
            NamedFunctionKind.SUM, NamedFunctionKind.PROD, NamedFunctionKind.SUMSQ, NamedFunctionKind.HYPOT, NamedFunctionKind.BETA,
            NamedFunctionKind.RE, NamedFunctionKind.IM, NamedFunctionKind.CONJ -> this
        }.canonicalize(context)
    }

    private fun fail(context: MathContext, issue: MathIssue): Expression {
        context.stopWithError(issue)
        return this
    }

    /**
     * The functions that are plain arithmetic, as that arithmetic (so they work with variables,
     * and can be differentiated), or null for the others.
     */
    private fun arithmetic(args: List<Expression>, context: MathContext): Expression? = when (kind) {
        NamedFunctionKind.SUM -> Additions(args).canonicalize(context)
        NamedFunctionKind.PROD -> Multiplications.of(*args.toTypedArray()).canonicalize(context)
        NamedFunctionKind.SUMSQ -> Additions(args.map { Power(it, Value.TWO) }).canonicalize(context)
        NamedFunctionKind.HYPOT -> Power.squareRoot(Additions(args.map { Power(it, Value.TWO) })).canonicalize(context)
        NamedFunctionKind.BETA -> {
            fun gamma(x: Expression) = SpecialFunction(SpecialType.GAMMA, x)
            Multiplications.of(gamma(args[0]), gamma(args[1]), Power(gamma(Additions(listOf(args[0], args[1]))), Value.MINUS_ONE)).canonicalize(context)
        }
        NamedFunctionKind.RE, NamedFunctionKind.IM, NamedFunctionKind.CONJ -> {
            val parts = complexParts(args[0])
            when {
                // Not written as a + b·i (a function of i): all that is left is to wait for the variables
                parts == null -> if (args[0].variables().isNotEmpty()) NamedFunction(kind, args) else fail(context, MathIssue.UNDEFINED)
                kind == NamedFunctionKind.RE -> parts.first
                kind == NamedFunctionKind.IM -> parts.second
                else -> complexOf(parts.first, parts.second.negate(), context)
            }
        }
        else -> null
    }

    private fun mean(args: List<Expression>): Expression =
        Multiplications.of(Additions(args), Value(1L, args.size.toLong()))

    /** The sum of the squared deviations from the mean, divided by [divisor] (n or n − 1). */
    private fun variance(args: List<Expression>, divisor: Long): Expression {
        val mean = mean(args).canonicalize()
        val squares = args.map { Power(Additions(it, mean.negate()), Value.TWO) }
        return Multiplications.of(Additions(squares), Value(1L, divisor))
    }

    /** The third standardized moment: the mean cubed deviation over the variance to the power 3/2. */
    private fun skewness(args: List<Expression>, context: MathContext): Expression? {
        val mean = mean(args).canonicalize()
        val deviations = args.map { Additions(it, mean.negate()) }
        val n = args.size.toLong()
        val variance = Multiplications.of(Additions(deviations.map { Power(it, Value.TWO) }), Value(1L, n)).canonicalize(context)
        if (context.shouldStop) return null
        if (variance.isZero) {
            context.stopWithError(MathIssue.UNDEFINED)
            return null
        }
        val thirdMoment = Multiplications.of(Additions(deviations.map { Power(it, Value(3L)) }), Value(1L, n))
        return Multiplications.of(thirdMoment, Power(variance, Value(-3L, 2L)))
    }

    /** percentile(p, values...) or quartile(q, values...): linear interpolation between the sorted values (as spreadsheets do). */
    private fun percentile(args: List<Expression>, context: MathContext): Expression? {
        val level = (args[0] as? Value)?.rational
        if (level == null || args[0].containsUnit()) {
            context.stopWithError(MathIssue.UNDEFINED)
            return null
        }
        val quartile = kind == NamedFunctionKind.QUARTILE
        if (quartile && level.isFractional) {
            context.stopWithError(MathIssue.WHOLE_NUMBERS_ONLY)
            return null
        }
        val fraction = level / (if (quartile) 4L else 100L)
        if (fraction.isNegative || fraction > Rational.ONE) {
            context.stopWithError(MathIssue.UNDEFINED)
            return null
        }
        val sorted = sortedByValue(args.drop(1), context) ?: return null
        val position = fraction * (sorted.size - 1).toLong()
        val below = (position.numerator / position.denominator).toLong().toInt()
        val rest = position - below.toLong()
        if (rest.isZero) return sorted[below]
        val gap = Additions(listOf(sorted[below + 1], sorted[below].negate()))
        return Additions(listOf(sorted[below], Multiplications.of(Value(rest), gap)))
    }

    /** The argument (angle) of the constant complex number [z] in radians; negative reals have π. */
    private fun argument(z: Expression, context: MathContext): Expression? {
        val parts = complexParts(z)
        if (z.containsUnit() || parts == null) {
            context.stopWithError(if (z.containsUnit()) MathIssue.INVALID_UNIT else MathIssue.UNDEFINED)
            return null
        }
        if (!parts.second.isZero) {
            return polarForm(z, context)?.second ?: run {
                if (!context.shouldStop) context.stopWithError(MathIssue.UNDEFINED)
                null
            }
        }
        val real = parts.first.evaluateNumerically(emptyMap())
        if (real.isNaN() || real == 0.0) {
            context.stopWithError(MathIssue.UNDEFINED)
            return null
        }
        return if (real > 0.0) Value.ZERO else Constant.PI
    }

    /** [args] with their values in SI units, for comparing; null (with an error) if they cannot be compared. */
    private fun keyedByValue(args: List<Expression>, context: MathContext): List<Pair<Expression, Double>>? {
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
        return keyed
    }

    /** [args] in increasing order, compared in SI units; null (with an error) if they cannot be ordered. */
    private fun sortedByValue(args: List<Expression>, context: MathContext): List<Expression>? =
        keyedByValue(args, context)?.sortedBy { it.second }?.map { it.first }

    private fun wholeNumber(expr: Expression): BigInt? =
        (expr as? Value)?.rational?.takeIf { !it.isFractional }?.numerator

    private sealed interface Whole {
        class Result(val number: BigInt) : Whole
        class Failed(val issue: MathIssue) : Whole
    }

    /** The value of the whole-number function [kind] for [numbers], or what makes it undefined. */
    private fun wholeNumberFunction(numbers: List<BigInt>): Whole {
        fun result(number: BigInt) = Whole.Result(number)
        fun undefined() = Whole.Failed(MathIssue.UNDEFINED)
        fun tooLarge() = Whole.Failed(MathIssue.VALUES_TOO_LARGE)
        val first = numbers[0]
        return when (kind) {
            NamedFunctionKind.GCD -> result(numbers.reduce { a, b -> a.gcd(b) }.abs())
            NamedFunctionKind.LCM -> result(numbers.reduce(::lcm).abs())
            NamedFunctionKind.NCR, NamedFunctionKind.NPR -> {
                val chosen = numbers[1]
                when {
                    first.isNegative || chosen.isNegative -> undefined()
                    first > BigInt(MAX_COMBINATORICS) -> tooLarge()
                    chosen > first -> result(BigInt.ZERO)
                    kind == NamedFunctionKind.NCR -> result(BigInt.binomial(first.toLong(), chosen.toLong()))
                    else -> result((0 until chosen.toLong()).fold(BigInt.ONE) { product, i -> product * (first - i) })
                }
            }
            NamedFunctionKind.MULTINOMIAL -> {
                val total = numbers.fold(BigInt.ZERO) { sum, k -> sum + k }
                when {
                    numbers.any { it.isNegative } -> undefined()
                    total > BigInt(MAX_COMBINATORICS) -> tooLarge()
                    else -> {
                        var placed = 0L
                        var ways = BigInt.ONE
                        for (group in numbers) {
                            placed += group.toLong()
                            ways = ways * BigInt.binomial(placed, group.toLong())
                        }
                        result(ways)
                    }
                }
            }
            NamedFunctionKind.FIB -> {
                if (first.abs() > BigInt(MAX_FIBONACCI)) return tooLarge()
                var previous = BigInteger.ZERO
                var current = BigInteger.ONE
                repeat(first.abs().toLong().toInt()) {
                    val next = previous.add(current)
                    previous = current
                    current = next
                }
                // F(−n) = (−1)^(n+1) F(n)
                val negative = first.isNegative && first.value.testBit(0).not()
                result(BigInt(if (negative) previous.negate() else previous))
            }
            NamedFunctionKind.NEXTPRIME, NamedFunctionKind.ISPRIME -> {
                if (first.value.bitLength() > MAX_PRIME_BITS) return tooLarge()
                val prime = first.value.signum() > 0 && first.value.isProbablePrime(PRIME_CERTAINTY)
                when {
                    kind == NamedFunctionKind.ISPRIME -> result(if (prime) BigInt.ONE else BigInt.ZERO)
                    first < BigInt(2L) -> result(BigInt(2L))
                    else -> result(BigInt(first.value.nextProbablePrime()))
                }
            }
            else -> undefined()
        }
    }

    private fun lcm(a: BigInt, b: BigInt): BigInt =
        if (a == BigInt.ZERO || b == BigInt.ZERO) BigInt.ZERO else (a * b).abs() / a.gcd(b)

    private fun roundingMode(): RoundingMode = when (kind) {
        NamedFunctionKind.FLOOR -> RoundingMode.FLOOR
        NamedFunctionKind.CEIL -> RoundingMode.CEILING
        NamedFunctionKind.TRUNC, NamedFunctionKind.FRAC -> RoundingMode.DOWN
        else -> RoundingMode.HALF_UP
    }

    /** [value] rounded to [places] decimal places as [kind] says; exact for fractions. */
    private fun round(value: Expression, places: Int, context: MathContext): Expression? {
        if (value.containsUnit()) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return null
        }
        val mode = roundingMode()
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
        if (arguments.size !in kind.minArguments..kind.maxArguments) return Double.NaN
        if (kind == NamedFunctionKind.RE || kind == NamedFunctionKind.IM || kind == NamedFunctionKind.ARG || kind == NamedFunctionKind.CONJ) {
            return complexPartNumerically(bindings)
        }
        val values = arguments.map { it.evaluateNumerically(bindings) }
        if (values.any { it.isNaN() }) return Double.NaN
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
            NamedFunctionKind.MODE -> {
                val groups = values.groupBy { it }.values
                val most = groups.maxOf { it.size }
                if (most == 1) Double.NaN else groups.first { it.size == most }.first()
            }
            NamedFunctionKind.MIN -> values.min()
            NamedFunctionKind.MAX -> values.max()
            NamedFunctionKind.RANGE -> values.max() - values.min()
            NamedFunctionKind.SUM -> values.sum()
            NamedFunctionKind.PROD -> values.fold(1.0) { product, v -> product * v }
            NamedFunctionKind.SUMSQ -> values.sumOf { it * it }
            NamedFunctionKind.HYPOT -> sqrt(values.sumOf { it * it })
            NamedFunctionKind.BETA -> exp(SpecialMath.lnGamma(values[0]) + SpecialMath.lnGamma(values[1]) - SpecialMath.lnGamma(values[0] + values[1]))
            NamedFunctionKind.PERCENTILE, NamedFunctionKind.QUARTILE -> {
                val share = values[0] / (if (kind == NamedFunctionKind.QUARTILE) 4 else 100)
                val sorted = values.drop(1).sorted()
                val position = share * (sorted.size - 1)
                val below = floor(position).toInt()
                if (share < 0 || share > 1 || (kind == NamedFunctionKind.QUARTILE && values[0] != floor(values[0]))) Double.NaN
                else if (below >= sorted.size - 1) sorted.last()
                else sorted[below] + (position - below) * (sorted[below + 1] - sorted[below])
            }
            NamedFunctionKind.SKEW -> {
                val variance = squares() / n
                if (variance == 0.0) Double.NaN else values.sumOf { (it - mean) * (it - mean) * (it - mean) } / n / (variance * sqrt(variance))
            }
            NamedFunctionKind.SIGN -> sign(values[0])
            NamedFunctionKind.FLOOR -> floor(values[0])
            NamedFunctionKind.CEIL -> ceil(values[0])
            NamedFunctionKind.FRAC -> values[0] - truncate(values[0])
            NamedFunctionKind.ROUND, NamedFunctionKind.TRUNC -> {
                val places = values.getOrElse(1) { 0.0 }
                if (places != floor(places) || values[0].isInfinite()) Double.NaN
                else BigDecimal(values[0]).setScale(places.toInt(), roundingMode()).toDouble()
            }
            // Whole-number functions are only defined for whole numbers
            else -> if (values.any { it != floor(it) || abs(it) > MAX_EXACT_DOUBLE }) {
                Double.NaN
            } else {
                (wholeNumberFunction(values.map { BigInt(it.toLong()) }) as? Whole.Result)?.number?.toDouble() ?: Double.NaN
            }
        }
    }

    /** re, im, arg and conj of a number that may contain i (which has no real value of its own). */
    private fun complexPartNumerically(bindings: Map<Variable, Double>): Double {
        val argument = arguments[0]
        if (!argument.containsImaginaryUnit()) {
            val real = argument.evaluateNumerically(bindings)
            return when {
                real.isNaN() -> Double.NaN
                kind == NamedFunctionKind.IM -> 0.0
                kind == NamedFunctionKind.ARG -> if (real > 0.0) 0.0 else if (real < 0.0) PI else Double.NaN
                else -> real
            }
        }
        val z = numericComplex(argument) ?: return Double.NaN
        return when (kind) {
            NamedFunctionKind.RE -> z.re
            NamedFunctionKind.IM -> z.im
            NamedFunctionKind.ARG -> z.arg
            // The conjugate of a complex number is not real
            else -> Double.NaN
        }
    }

    override fun equals(other: Any?): Boolean = other is NamedFunction && kind == other.kind && arguments == other.arguments

    override fun hashCode(): Int = kind.hashCode() * 31 + arguments.hashCode()

    override fun toString(): String = "${kind.functionName}(${arguments.joinToString(", ")})"

    private companion object {
        const val MAX_COMBINATORICS = 100_000L
        const val MAX_FIBONACCI = 10_000L
        const val MAX_PRIME_BITS = 256
        const val PRIME_CERTAINTY = 40
        /** Whole numbers up to here (2^53) are exact as a Double. */
        const val MAX_EXACT_DOUBLE = 9.007199254740992E15
        val MAX_PLACES = BigInt(100L)
        /** The functions that are meaningful for complex numbers. */
        val COMPLEX_KINDS = setOf(
            NamedFunctionKind.MEAN, NamedFunctionKind.SUM, NamedFunctionKind.PROD,
            NamedFunctionKind.RE, NamedFunctionKind.IM, NamedFunctionKind.ARG, NamedFunctionKind.CONJ
        )
    }
}
