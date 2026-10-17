package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Factoring
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.numbers.Real
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
    /** normalpdf(x) or normalpdf(x, μ, σ): the density of the normal distribution; standard (μ = 0, σ = 1) without μ and σ. */
    NORMALPDF("normalpdf", 1, 3),
    /** normalcdf(x) or normalcdf(x, μ, σ): the probability of a value up to x. */
    NORMALCDF("normalcdf", 1, 3),
    /** normalinv(p) or normalinv(p, μ, σ): the x with a probability p of a value up to it. */
    NORMALINV("normalinv", 1, 3),
    /** binompdf(n, p, k): the probability of exactly k successes in n trials. */
    BINOMPDF("binompdf", 3, 3),
    /** binomcdf(n, p, k): the probability of at most k successes in n trials. */
    BINOMCDF("binomcdf", 3, 3),
    /** poissonpdf(λ, k): the probability of exactly k events when λ are expected. */
    POISSONPDF("poissonpdf", 2, 2),
    /** poissoncdf(λ, k): the probability of at most k events when λ are expected. */
    POISSONCDF("poissoncdf", 2, 2),
    /** tpdf(x, df): the density of Student's t distribution with df degrees of freedom. */
    TPDF("tpdf", 2, 2),
    /** tcdf(x, df): the probability of a value up to x. */
    TCDF("tcdf", 2, 2),
    /** tinv(p, df): the x with a probability p of a value up to it. */
    TINV("tinv", 2, 2),
    /** chisqpdf(x, df): the density of the chi-square distribution with df degrees of freedom. */
    CHISQPDF("chisqpdf", 2, 2),
    /** chisqcdf(x, df): the probability of a value up to x. */
    CHISQCDF("chisqcdf", 2, 2),
    /** chisqinv(p, df): the x with a probability p of a value up to it. */
    CHISQINV("chisqinv", 2, 2),
    /** gammainc(a, x): the regularized lower incomplete gamma function P(a, x) = γ(a, x)/Γ(a), the probability that a gamma variable is up to x. */
    GAMMAINC("gammainc", 2, 2),
    /** betainc(x, a, b): the regularized incomplete beta function I_x(a, b), for 0 ≤ x ≤ 1. */
    BETAINC("betainc", 3, 3),
    /** The beta function Γ(a)·Γ(b)/Γ(a + b). */
    BETA("beta", 2, 2),
    /** tmargin(c, values...): the margin of error of the confidence interval of level c (0.95 for 95%) for the mean, t·s/√n with t = tinv((1 + c)/2, n − 1). */
    TMARGIN("tmargin", 3),
    /** tstat(μ₀, values...): the t statistic (mean − μ₀)/(s/√n) of a one-sample t test against μ₀. */
    TSTAT("tstat", 3),
    /** Euler's totient: how many of 1..n have no common factor with n. */
    TOTIENT("totient", 1, 1),
    /** factor(n): n as a product of prime powers, e.g. 360 = 2³·3²·5 (the number itself in calculations). */
    FACTOR("factor", 1, 1),
    /** modpow(b, e, m): bᵉ mod m for m > 0, worked out without the huge power; a negative e needs b to have an inverse mod m. */
    MODPOW("modpow", 3, 3),
    /** √(a² + b² + ...), the length of the vector (a, b, ...). */
    HYPOT("hypot", 2),
    /** The real part of a complex number. */
    RE("re", 1, 1),
    /** The imaginary part of a complex number. */
    IM("im", 1, 1),
    /** The angle of a complex number to the positive real axis, in radians from −π (excluded) to π. */
    ARG("arg", 1, 1),
    /** The complex conjugate a − b·i of a + b·i. */
    CONJ("conj", 1, 1),
    /** The bits that are set in both whole numbers (also written a AND b). */
    BIT_AND("and", 2, 2),
    /** The bits that are set in one of them (a OR b). */
    BIT_OR("or", 2, 2),
    /** The bits that are set in exactly one of them (a XOR b). */
    BIT_XOR("xor", 2, 2),
    /** All bits flipped: −n − 1 (two's complement, as for numbers of any size). */
    BIT_NOT("not", 1, 1),
    /** shl(n, k) = n·2ᵏ (a negative k shifts right); also written n << k. */
    SHIFT_LEFT("shl", 2, 2),
    /** shr(n, k) = ⌊n/2ᵏ⌋ (a negative k shifts left); also written n >> k. */
    SHIFT_RIGHT("shr", 2, 2);

    /**
     * Whether this function has jumps or takes whole numbers only (floor, sign, nCr, …): searching for the points where an
     * equation or inequality changes sign would find jumps as solutions, or none.
     */
    val isStepwise: Boolean get() = this in STEPWISE

    /**
     * Whether the function jumps in its argument number [index] (counted from 0): the probability p of binompdf and the mean
     * λ of poissonpdf are continuous, the counts are not.
     */
    fun jumpsIn(index: Int): Boolean = this in STEPWISE && when (this) {
        BINOMPDF, BINOMCDF -> index != 1
        POISSONPDF, POISSONCDF -> index != 0
        else -> true
    }

    companion object {
        fun byName(name: String): NamedFunctionKind? = entries.firstOrNull { it.functionName == name }

        private val STEPWISE = setOf(
            ROUND, FLOOR, CEIL, TRUNC, FRAC, SIGN, MODE, GCD, LCM, NCR, NPR, MULTINOMIAL, FIB, NEXTPRIME, ISPRIME, TOTIENT, MODPOW, FACTOR,
            BIT_AND, BIT_OR, BIT_XOR, BIT_NOT, SHIFT_LEFT, SHIFT_RIGHT,
            BINOMPDF, BINOMCDF, POISSONPDF, POISSONCDF
        )
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
            NamedFunctionKind.MULTINOMIAL, NamedFunctionKind.FIB, NamedFunctionKind.NEXTPRIME, NamedFunctionKind.ISPRIME,
            NamedFunctionKind.TOTIENT, NamedFunctionKind.MODPOW, NamedFunctionKind.FACTOR, NamedFunctionKind.BIT_AND, NamedFunctionKind.BIT_OR, NamedFunctionKind.BIT_XOR, NamedFunctionKind.BIT_NOT,
            NamedFunctionKind.SHIFT_LEFT, NamedFunctionKind.SHIFT_RIGHT -> {
                val numbers = args.map { wholeNumber(it) ?: return fail(context, MathIssue.WHOLE_NUMBERS_ONLY) }
                when (val outcome = wholeNumberFunction(numbers)) {
                    is Whole.Result -> Value(outcome.number)
                    is Whole.Failed -> return fail(context, outcome.issue)
                }
            }
            NamedFunctionKind.BINOMPDF, NamedFunctionKind.BINOMCDF, NamedFunctionKind.POISSONPDF, NamedFunctionKind.POISSONCDF ->
                discrete(args, context) ?: return this
            NamedFunctionKind.TCDF, NamedFunctionKind.TINV, NamedFunctionKind.CHISQPDF, NamedFunctionKind.CHISQCDF, NamedFunctionKind.CHISQINV ->
                continuous(args, context) ?: return this
            NamedFunctionKind.GAMMAINC, NamedFunctionKind.BETAINC -> incomplete(args, context) ?: return this
            NamedFunctionKind.TSTAT -> {
                if (args.any { it.containsUnit() }) return fail(context, MathIssue.INVALID_UNIT)
                tStatistic(args, context) ?: return this
            }
            NamedFunctionKind.TMARGIN -> {
                if (args.any { it.containsUnit() }) return fail(context, MathIssue.INVALID_UNIT)
                val level = args[0].evaluateNumerically()
                if (!(level > 0.0 && level < 1.0)) return fail(context, MathIssue.UNDEFINED)
                decimal(numeric(args), context)
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
            NamedFunctionKind.NORMALPDF, NamedFunctionKind.NORMALCDF, NamedFunctionKind.NORMALINV, NamedFunctionKind.TPDF, NamedFunctionKind.RE, NamedFunctionKind.IM, NamedFunctionKind.CONJ -> return this
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
        NamedFunctionKind.NORMALPDF, NamedFunctionKind.NORMALCDF, NamedFunctionKind.NORMALINV -> normal(args, context)
        NamedFunctionKind.TPDF -> studentDensity(args, context)
        NamedFunctionKind.BETA -> beta(args, context)
        NamedFunctionKind.RE, NamedFunctionKind.IM, NamedFunctionKind.CONJ -> {
            val parts = complexParts(args[0])
            when {
                // Not written as a + b·i (a function of i): all that is left is to wait for the variables
                parts == null -> if (args[0].variables().isNotEmpty()) NamedFunction(kind, args) else fail(context, MathIssue.UNDEFINED)
                kind == NamedFunctionKind.RE -> parts.first
                kind == NamedFunctionKind.IM -> parts.second
                // A real number is its own conjugate (and needs no i, so this works with complex numbers off)
                parts.second.isZero -> parts.first
                else -> complexOf(parts.first, parts.second.negate(), context)
            }
        }
        else -> null
    }

    /**
     * The beta function Γ(a)·Γ(b)/Γ(a + b), written out so that it works with variables. For numbers it is exact where Γ is
     * and the result stays short; a decimal (through ln Γ, which does not overflow) for large numbers and in numeric mode.
     */
    private fun beta(args: List<Expression>, context: MathContext): Expression {
        fun gamma(x: Expression) = SpecialFunction(SpecialType.GAMMA, x)
        val formula = Multiplications.of(gamma(args[0]), gamma(args[1]), Power(gamma(Additions(listOf(args[0], args[1]))), Value.MINUS_ONE))
        if (args.any { it.variables().isNotEmpty() }) return formula.canonicalize(context)
        val (a, b) = args.map { it.evaluateNumerically() }
        val large = a > MAX_EXACT_DF || b > MAX_EXACT_DF
        if (context.mode == CalculationMode.NUMERIC || large) return decimal(numeric(args), context)
        return exactOrNull(formula, context, args) ?: decimal(numeric(args), context)
    }

    /** The value of this function for [args] (canonical numbers) as a decimal. */
    private fun numeric(args: List<Expression>): Double = NamedFunction(kind, args).evaluateNumerically(emptyMap())

    /**
     * [expression] canonicalized, if that works out and the result is short enough to show as an exact result; otherwise null
     * (nothing is reported then: the caller gives a decimal instead).
     */
    private fun exactOrNull(expression: Expression, context: MathContext, args: List<Expression>): Expression? {
        val scratch = context.copy()
        // Working it out must not take noticeably long: it is tried for every live result, and thrown away if it grows too big
        scratch.deadlineMillis = minOf(scratch.deadlineMillis, System.currentTimeMillis() + EXACT_BUDGET_MILLIS)
        val result = expression.canonicalize(scratch)
        if (scratch.shouldStop || context.shouldStop || hasHugeNumber(result)) return null
        // The engine drops to decimals inside huge products (and may come out at 0 or infinity): only a result that agrees with the number is exact
        val reference = numeric(args)
        val value = result.evaluateNumerically()
        if (!value.isFinite() || !reference.isFinite() || abs(value - reference) > 1e-9 * abs(reference) + 1e-300) return null
        return result
    }

    private fun hasHugeNumber(e: Expression): Boolean = e.containsExpressionMatching { part ->
        (part as? Value)?.rational?.let { it.numerator.value.bitLength() > MAX_EXACT_BITS || it.denominator.value.bitLength() > MAX_EXACT_BITS } == true
    }

    /** The normal distribution as the formulas it is made of, so it works with variables and can be differentiated. */
    private fun normal(args: List<Expression>, context: MathContext): Expression {
        if (args.size == 2) return fail(context, MathIssue.ARGUMENT_COUNT)
        if (args.any { it.containsUnit() }) return fail(context, MathIssue.INVALID_UNIT)
        val sigma = args.getOrNull(2)
        if (sigma != null && sigma.variables().isEmpty() && !(sigma.evaluateNumerically() > 0.0)) return fail(context, MathIssue.UNDEFINED)
        return normalFormula(kind, args).canonicalize(context)
    }

    private fun mean(args: List<Expression>): Expression =
        Multiplications.of(Additions(args), Value(1L, args.size.toLong()))

    /** The sum of the squared deviations from the mean, divided by [divisor] (n or n − 1). */
    private fun variance(args: List<Expression>, divisor: Long): Expression {
        val mean = mean(args).canonicalize()
        val squares = args.map { Power(Additions(it, mean.negate()), Value.TWO) }
        return Multiplications.of(Additions(squares), Value(1L, divisor))
    }

    /**
     * tpdf as its formula, so it works with variables and can be differentiated; exact for the numbers where Γ is, a decimal
     * where it would be a huge fraction (many degrees of freedom) and in numeric mode.
     */
    private fun studentDensity(args: List<Expression>, context: MathContext): Expression {
        if (args.any { it.containsUnit() }) return fail(context, MathIssue.INVALID_UNIT)
        val (x, df) = args
        val freedom = if (df.variables().isEmpty()) df.evaluateNumerically() else null
        if (freedom != null && !(freedom > 0.0)) return fail(context, MathIssue.UNDEFINED)
        val large = freedom != null && freedom > MAX_EXACT_DF
        val constant = x.variables().isEmpty() && freedom != null
        if (constant && (large || context.mode == CalculationMode.NUMERIC)) return decimal(numeric(args), context)
        // With a variable x the formula would overflow Γ for many degrees of freedom: it stays open and is calculated as a number
        if (large) return NamedFunction(kind, args)
        if (!constant) return studentDensityFormula(x, df).canonicalize(context)
        return exactOrNull(studentDensityFormula(x, df), context, args) ?: decimal(numeric(args), context)
    }

    private fun decimal(value: Double, context: MathContext): Expression {
        if (value.isNaN()) return fail(context, MathIssue.UNDEFINED)
        if (value.isInfinite()) return fail(context, MathIssue.VALUES_TOO_LARGE)
        return Value(Real(value))
    }

    /**
     * The Student t and chi-square distributions beyond the density of t: a decimal, exact only where a closed form is
     * short (chisqpdf, chisqcdf for even degrees of freedom, and the median of t).
     */
    private fun continuous(args: List<Expression>, context: MathContext): Expression? {
        if (args.any { it.containsUnit() }) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return null
        }
        val (first, df) = args
        val x = first.evaluateNumerically()
        val freedom = df.evaluateNumerically()
        val quantile = kind == NamedFunctionKind.TINV || kind == NamedFunctionKind.CHISQINV
        if (x.isNaN() || !(freedom > 0.0) || (quantile && !(x > 0.0 && x < 1.0))) {
            context.stopWithError(MathIssue.UNDEFINED)
            return null
        }
        val exactMode = context.mode != CalculationMode.NUMERIC && freedom <= MAX_EXACT_DF
        when (kind) {
            NamedFunctionKind.TCDF -> if (x == 0.0) return Value.HALF
            NamedFunctionKind.TINV -> if (x == 0.5) return Value.ZERO
            NamedFunctionKind.CHISQPDF -> when {
                x < 0.0 -> return Value.ZERO
                x == 0.0 -> {
                    if (freedom < 2.0) {
                        context.stopWithError(MathIssue.UNDEFINED)
                        return null
                    }
                    return if (freedom == 2.0) Value.HALF else Value.ZERO
                }
                exactMode -> return exactOrNull(chiSquareDensityFormula(first, df), context, args) ?: decimal(numeric(args), context)
            }
            NamedFunctionKind.CHISQCDF -> {
                if (x <= 0.0) return Value.ZERO
                // Even degrees of freedom: 1 − e^(−x/2)·(1 + x/2 + … + (x/2)^(k/2 − 1)/(k/2 − 1)!), like a Poisson probability
                val half = wholeNumber(df)?.takeIf { exactMode && !it.isNegative && it.toLong() % 2 == 0L && it.toLong() <= 2 * MAX_EXACT_TRIALS }?.toLong()?.div(2)
                if (half != null) {
                    val rate = Multiplications(listOf(Value.HALF, first))
                    val terms = (0 until half).map { j -> Multiplications(listOf(Power(rate, Value(j)), Value(Rational.of(BigInt.ONE, BigInt.factorial(j))))) }
                    val decay = Power(Constant.E, Multiplications(listOf(Value.MINUS_ONE, rate)))
                    exactOrNull(Additions(listOf(Value.ONE, Multiplications(listOf(Value.MINUS_ONE, decay, Additions(terms))))), context, args)?.let { return it }
                }
            }
            else -> {}
        }
        return decimal(numeric(args), context)
    }

    /** gammainc and betainc: a decimal, exact only at the ends (0 and 1). Null (with an error) if the values do not fit. */
    private fun incomplete(args: List<Expression>, context: MathContext): Expression? {
        if (args.any { it.containsUnit() }) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return null
        }
        val values = args.map { it.evaluateNumerically() }
        val gamma = kind == NamedFunctionKind.GAMMAINC
        val shapes = if (gamma) listOf(values[0]) else listOf(values[1], values[2])
        val x = if (gamma) values[1] else values[0]
        if (values.any { it.isNaN() } || shapes.any { !(it > 0.0) } || x < 0.0 || (!gamma && x > 1.0)) {
            context.stopWithError(MathIssue.UNDEFINED)
            return null
        }
        if (x == 0.0) return Value.ZERO
        if (!gamma && x == 1.0) return Value.ONE
        return decimal(numeric(args), context)
    }

    /** The t statistic (mean − μ₀)/(s/√n) of the values after the first argument μ₀, exact like the other statistics. */
    private fun tStatistic(args: List<Expression>, context: MathContext): Expression? {
        val data = args.drop(1)
        val sem = Power.squareRoot(Multiplications.of(variance(data, data.size - 1L), Value(1L, data.size.toLong())))
        val statistic = Multiplications.of(Additions(listOf(mean(data), args[0].negate())), Power(sem, Value.MINUS_ONE)).canonicalize(context)
        if (context.shouldStop) return null
        return statistic
    }

    /**
     * binompdf, binomcdf, poissonpdf or poissoncdf: exact (a fraction, or a fraction times e^(−λ)) for a moderate number
     * of trials or events, a decimal otherwise and in numeric mode. Null (with an error) if the values do not fit.
     */
    private fun discrete(args: List<Expression>, context: MathContext): Expression? {
        if (args.any { it.containsUnit() }) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return null
        }
        val binomial = kind == NamedFunctionKind.BINOMPDF || kind == NamedFunctionKind.BINOMCDF
        val cumulative = kind == NamedFunctionKind.BINOMCDF || kind == NamedFunctionKind.POISSONCDF
        val trials = if (binomial) wholeNumber(args[0]) else null
        val rate = args[if (binomial) 1 else 0]
        val count = wholeNumber(args[if (binomial) 2 else 1])
        if ((binomial && trials == null) || count == null) {
            context.stopWithError(MathIssue.WHOLE_NUMBERS_ONLY)
            return null
        }
        val r = rate.evaluateNumerically()
        val validRate = if (binomial) r in 0.0..1.0 else r >= 0.0
        if (!validRate || (trials != null && trials.isNegative)) {
            context.stopWithError(MathIssue.UNDEFINED)
            return null
        }
        // Outside of what can happen
        if (count.isNegative) return Value.ZERO
        if (trials != null && count > trials) return if (cumulative) Value.ONE else Value.ZERO
        val size = (trials ?: count)
        if (cumulative && count > BigInt(MAX_TERMS) || size > BigInt(MAX_TRIALS)) {
            context.stopWithError(MathIssue.VALUES_TOO_LARGE)
            return null
        }
        if (context.mode == CalculationMode.NUMERIC || size > BigInt(MAX_EXACT_TRIALS)) return decimal(numeric(args), context)
        val k = count.toLong()
        if (binomial) {
            val n = trials!!.toLong()
            // The fractions have about n times the digits of p: where they are too long to show, a decimal at once
            val digits = (rate as? Value)?.rational?.let { maxOf(it.numerator.value.bitLength(), it.denominator.value.bitLength()) } ?: 64
            if (n * digits > 4 * MAX_EXACT_BITS) return decimal(numeric(args), context)
            val failure = Additions(listOf(Value.ONE, rate.negate()))
            // C(n, i)·p^i·(1 − p)^(n − i), leaving out a power of 0 (p = 0 and i = 0 would be 0⁰)
            fun term(i: Long): Expression = Multiplications.of(
                Value(BigInt.binomial(n, i)),
                if (i > 0) Power(rate, Value(i)) else Value.ONE,
                if (n - i > 0) Power(failure, Value(n - i)) else Value.ONE
            )
            return exactOrNull(if (cumulative) Additions((0..k).map { term(it) }) else term(k), context, args) ?: decimal(numeric(args), context)
        }
        // e^(−λ)·λ^i/i!
        fun power(i: Long): Expression = Multiplications.of(
            if (i > 0) Power(rate, Value(i)) else Value.ONE,
            Value(Rational.of(BigInt.ONE, BigInt.factorial(i)))
        )
        val decay = Power(Constant.E, Multiplications.of(Value.MINUS_ONE, rate))
        return exactOrNull(Multiplications.of(decay, if (cumulative) Additions((0..k).map { power(it) }) else power(k)), context, args)
            ?: decimal(numeric(args), context)
    }

    /** The decimal number [d] as the fraction it is written as (37.5 is 75/2, 1e-5 is 1/100000). */
    private fun decimalAsRational(d: Double): Rational {
        val decimal = BigDecimal.valueOf(d)
        val unscaled = BigInt(decimal.unscaledValue())
        val power = BigInt(BigInteger.TEN.pow(abs(decimal.scale())))
        return if (decimal.scale() >= 0) Rational.of(unscaled, power) else Rational.of(unscaled * power)
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
        // In numeric mode numbers are decimals: 37.5 is read as the fraction 75/2
        val level = (args[0] as? Value)?.let { value -> value.rational ?: value.real.value.takeIf { it == 0.0 || abs(it) in 1e-12..1e6 }?.let { decimalAsRational(it) } }
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

    /** The whole number [expr] is, or null; in numeric mode numbers are decimals (2.0), which count where they are whole. */
    private fun wholeNumber(expr: Expression): BigInt? {
        val value = expr as? Value ?: return null
        value.rational?.let { return it.takeIf { r -> !r.isFractional }?.numerator }
        val d = value.real.value
        return if (d == floor(d) && abs(d) <= MAX_EXACT_DOUBLE) BigInt(d.toLong()) else null
    }

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
            NamedFunctionKind.TOTIENT -> {
                if (first.value.signum() <= 0) return undefined()
                if (first == BigInt.ONE) return result(BigInt.ONE)
                val factors = Factoring.factor(first.value) { MathContext.current().shouldStop }
                    ?: return if (MathContext.current().shouldStop) Whole.Failed(MathIssue.TIMEOUT) else tooLarge()
                // φ(n) = n·∏(1 − 1/p)
                result(BigInt(factors.fold(first.value) { phi, (p, _) -> phi.divide(p).multiply(p.subtract(BigInteger.ONE)) }))
            }
            // The number itself; as a product of prime powers it is shown when factor(n) is the whole line (see MathEngine)
            NamedFunctionKind.FACTOR -> if (first == BigInt.ZERO) undefined() else result(first)
            NamedFunctionKind.MODPOW -> {
                val modulus = numbers[2].value
                when {
                    modulus.signum() <= 0 -> undefined()
                    numbers.any { it.value.bitLength() > MAX_MODPOW_BITS } -> tooLarge()
                    numbers[1].isNegative && numbers[0].value.gcd(modulus) != BigInteger.ONE -> undefined()
                    else -> result(BigInt(numbers[0].value.modPow(numbers[1].value, modulus)))
                }
            }
            NamedFunctionKind.BIT_AND -> result(BigInt(first.value.and(numbers[1].value)))
            NamedFunctionKind.BIT_OR -> result(BigInt(first.value.or(numbers[1].value)))
            NamedFunctionKind.BIT_XOR -> result(BigInt(first.value.xor(numbers[1].value)))
            NamedFunctionKind.BIT_NOT -> result(BigInt(first.value.not()))
            NamedFunctionKind.SHIFT_LEFT, NamedFunctionKind.SHIFT_RIGHT -> {
                val count = numbers[1]
                val left = (kind == NamedFunctionKind.SHIFT_LEFT) == !count.isNegative
                if (count.abs() > BigInt(MAX_SHIFT)) {
                    // Nothing is left of a number shifted right that far (or all ones, for a negative one); 0 stays 0 either way
                    return when {
                        first == BigInt.ZERO -> result(BigInt.ZERO)
                        left -> tooLarge()
                        else -> result(if (first.isNegative) BigInt(-1L) else BigInt.ZERO)
                    }
                }
                val bits = count.abs().toLong().toInt()
                result(BigInt(if (left) first.value.shiftLeft(bits) else first.value.shiftRight(bits)))
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
            // Through ln Γ, which does not overflow; Γ itself for the arguments where that is not defined (negative ones)
            NamedFunctionKind.BETA ->
                if (values[0] > 0.0 && values[1] > 0.0) exp(SpecialMath.lnBeta(values[0], values[1]))
                else SpecialMath.gamma(values[0]) * SpecialMath.gamma(values[1]) / SpecialMath.gamma(values[0] + values[1])
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
            NamedFunctionKind.NORMALPDF, NamedFunctionKind.NORMALCDF, NamedFunctionKind.NORMALINV -> {
                val mu = values.getOrElse(1) { 0.0 }
                val sigma = values.getOrElse(2) { 1.0 }
                val z = (values[0] - mu) / sigma
                when {
                    n == 2 || !(sigma > 0.0) -> Double.NaN
                    kind == NamedFunctionKind.NORMALPDF -> exp(-z * z / 2) / (sigma * sqrt(2 * PI))
                    kind == NamedFunctionKind.NORMALCDF -> 0.5 * SpecialMath.erfc(-z / sqrt(2.0))
                    else -> mu + sigma * SpecialMath.inverseNormal(values[0])
                }
            }
            NamedFunctionKind.BINOMPDF, NamedFunctionKind.BINOMCDF, NamedFunctionKind.POISSONPDF, NamedFunctionKind.POISSONCDF -> {
                val binomial = kind == NamedFunctionKind.BINOMPDF || kind == NamedFunctionKind.BINOMCDF
                val k = values[if (binomial) 2 else 1]
                val parameter = values[if (binomial) 1 else 0]
                // The trials and the count are whole numbers; the probability or mean is not
                val whole = k == floor(k) && (!binomial || values[0] == floor(values[0]))
                val sizeOk = if (binomial) values[0] in 0.0..MAX_TRIALS.toDouble() else k <= MAX_TRIALS
                val valid = whole && sizeOk && (if (binomial) parameter in 0.0..1.0 else parameter >= 0.0)
                when {
                    !valid -> Double.NaN
                    kind == NamedFunctionKind.BINOMPDF -> SpecialMath.binomialPmf(values[0], parameter, k)
                    kind == NamedFunctionKind.BINOMCDF -> if (k > MAX_TERMS && k < values[0]) Double.NaN else SpecialMath.binomialCdf(values[0], parameter, k)
                    kind == NamedFunctionKind.POISSONPDF -> SpecialMath.poissonPmf(parameter, k)
                    else -> if (k > MAX_TERMS) Double.NaN else SpecialMath.poissonCdf(parameter, k)
                }
            }
            NamedFunctionKind.GAMMAINC -> if (values[1] < 0.0) Double.NaN else SpecialMath.regularizedGammaP(values[0], values[1])
            NamedFunctionKind.BETAINC ->
                if (values[0] !in 0.0..1.0) Double.NaN else SpecialMath.regularizedBeta(values[0], 1.0 - values[0], values[1], values[2])
            NamedFunctionKind.TSTAT, NamedFunctionKind.TMARGIN -> {
                val data = values.drop(1)
                val dataMean = data.average()
                val sem = sqrt(data.sumOf { (it - dataMean) * (it - dataMean) } / (data.size - 1) / data.size)
                if (kind == NamedFunctionKind.TSTAT) (dataMean - values[0]) / sem
                else if (values[0] > 0.0 && values[0] < 1.0) SpecialMath.studentInverse((1.0 + values[0]) / 2.0, data.size - 1.0) * sem
                else Double.NaN
            }
            NamedFunctionKind.TPDF -> SpecialMath.studentPdf(values[0], values[1])
            NamedFunctionKind.TCDF -> SpecialMath.studentCdf(values[0], values[1])
            NamedFunctionKind.TINV -> SpecialMath.studentInverse(values[0], values[1])
            NamedFunctionKind.CHISQPDF -> SpecialMath.chiSquarePdf(values[0], values[1])
            NamedFunctionKind.CHISQCDF -> SpecialMath.chiSquareCdf(values[0], values[1])
            NamedFunctionKind.CHISQINV -> SpecialMath.chiSquareInverse(values[0], values[1])
            NamedFunctionKind.SIGN -> sign(values[0])
            NamedFunctionKind.FLOOR -> floor(values[0])
            NamedFunctionKind.CEIL -> ceil(values[0])
            NamedFunctionKind.FRAC -> values[0] - truncate(values[0])
            NamedFunctionKind.ROUND, NamedFunctionKind.TRUNC -> {
                val places = values.getOrElse(1) { 0.0 }
                // The same limit as for numbers: BigDecimal would work for minutes at 10⁸ places
                if (places != floor(places) || abs(places) > MAX_PLACES.toLong() || values[0].isInfinite()) Double.NaN
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
        const val MAX_MODPOW_BITS = 8192
        const val PRIME_CERTAINTY = 40
        /** Binomial and Poisson probabilities are exact up to here, decimals beyond (the exact fractions get huge). */
        const val MAX_EXACT_TRIALS = DistributionLimits.MAX_EXACT_TRIALS
        /** Student t and chi-square densities are exact formulas up to here, decimals beyond (Γ of large numbers is huge). */
        const val MAX_EXACT_DF = DistributionLimits.MAX_EXACT_DF
        /** The numerator or denominator of an exact result has at most this many bits (about 77 digits): longer ones are shown as decimals. */
        const val MAX_EXACT_BITS = 256
        /** The time the exact form of a distribution may take before the decimal is given instead. */
        const val EXACT_BUDGET_MILLIS = 150L
        /** The most trials a binomial or events a Poisson distribution may have (a double holds whole numbers up to 2^53). */
        const val MAX_TRIALS = 1_000_000_000_000_000L
        /** The most terms of a cumulative probability that are added up. */
        const val MAX_TERMS = 200_000L
        /** Whole numbers up to here (2^53) are exact as a Double. */
        const val MAX_EXACT_DOUBLE = 9.007199254740992E15
        /** The most places a number is shifted by: 2^10000 has about 3000 digits. */
        const val MAX_SHIFT = 10_000L
        val MAX_PLACES = BigInt(100L)
        /** The functions that are meaningful for complex numbers. */
        val COMPLEX_KINDS = setOf(
            NamedFunctionKind.MEAN, NamedFunctionKind.SUM, NamedFunctionKind.PROD,
            NamedFunctionKind.RE, NamedFunctionKind.IM, NamedFunctionKind.ARG, NamedFunctionKind.CONJ
        )
    }
}

/**
 * The formula behind normalpdf, normalcdf or normalinv with the arguments [args] (x, and μ and σ if given), as a textbook
 * writes it: without a μ and σ the standard normal distribution, so no "− 0" or "÷ 1" in it.
 */
internal fun normalFormula(kind: NamedFunctionKind, args: List<Expression>): Expression {
    val x = args[0]
    val mu = args.getOrNull(1)
    val sigma = args.getOrNull(2)
    val deviation = if (mu == null) x else Additions(listOf(x, mu.negate()))
    val rootOfTwoPi = Power(Multiplications(listOf(Value.TWO, Constant.PI)), Value.HALF)
    return when (kind) {
        // e^(−(x − μ)²/(2σ²)) / (σ·√(2π))
        NamedFunctionKind.NORMALPDF -> Multiplications(listOf(
            Power(if (sigma == null) rootOfTwoPi else Multiplications(listOf(sigma, rootOfTwoPi)), Value.MINUS_ONE),
            Power(Constant.E, Multiplications(listOfNotNull(Value(-1L, 2L), Power(deviation, Value.TWO), sigma?.let { Power(it, Value(-2L)) })))
        ))
        // erfc(−z/√2)/2 with z = (x − μ)/σ: no 1 + erf, which loses the small values far to the left
        NamedFunctionKind.NORMALCDF -> Multiplications(listOf(
            Value.HALF,
            SpecialFunction(SpecialType.ERFC, Multiplications(listOfNotNull(Value.MINUS_ONE, deviation, sigma?.let { Power(it, Value.MINUS_ONE) }, Power(Value.TWO, Value(-1L, 2L)))))
        ))
        else -> Additions(listOfNotNull(mu, Multiplications(listOfNotNull(sigma, SpecialFunction(SpecialType.NORMALINV, x)))))
    }
}

/** The density of Student's t distribution at [x] with [df] degrees of freedom: Γ((ν + 1)/2) / (√(νπ)·Γ(ν/2)) · (1 + x²/ν)^(−(ν + 1)/2). */
internal fun studentDensityFormula(x: Expression, df: Expression): Expression {
    fun gamma(argument: Expression) = SpecialFunction(SpecialType.GAMMA, argument)
    val plusOne = Additions(listOf(df, Value.ONE))
    return Multiplications(listOf(
        gamma(Multiplications(listOf(plusOne, Value.HALF))),
        Power(Multiplications(listOf(Power(Multiplications(listOf(df, Constant.PI)), Value.HALF), gamma(Multiplications(listOf(df, Value.HALF))))), Value.MINUS_ONE),
        Power(Additions(listOf(Value.ONE, Multiplications(listOf(Power(x, Value.TWO), Power(df, Value.MINUS_ONE))))), Multiplications(listOf(Value(-1L, 2L), plusOne)))
    ))
}

/** The density of the chi-square distribution at [x] > 0 with [df] degrees of freedom: x^(k/2 − 1)·e^(−x/2) / (2^(k/2)·Γ(k/2)). */
internal fun chiSquareDensityFormula(x: Expression, df: Expression): Expression {
    val half = Multiplications(listOf(df, Value.HALF))
    return Multiplications(listOf(
        Power(x, Additions(listOf(half, Value.MINUS_ONE))),
        Power(Constant.E, Multiplications(listOf(Value(-1L, 2L), x))),
        Power(Power(Value.TWO, half), Value.MINUS_ONE),
        Power(SpecialFunction(SpecialType.GAMMA, half), Value.MINUS_ONE)
    ))
}

/** Where the distribution functions stop giving exact results; shared with their steps. */
internal object DistributionLimits {
    /** Binomial and Poisson probabilities are exact up to here (trials or events), decimals beyond. */
    const val MAX_EXACT_TRIALS = 500L
    /** Student t and chi-square densities are exact formulas up to here (degrees of freedom), decimals beyond. */
    const val MAX_EXACT_DF = 300.0
}

/** Whether this call jumps in [variable] (floor(x), nCr(x, 2), binompdf(10, 0.5, x)), so that it cannot be solved by looking for sign changes. */
internal fun NamedFunction.jumpsIn(variable: Variable): Boolean =
    arguments.withIndex().any { (index, argument) -> kind.jumpsIn(index) && argument.containsVariable(variable) }
