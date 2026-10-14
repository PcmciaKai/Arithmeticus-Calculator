package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Real
import java.math.BigInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** The random numbers there are: rand(), randint(a, b) and randnorm(μ, σ). */
enum class RandomKind(val functionName: String, val minArguments: Int, val maxArguments: Int) {
    /** A number from 0 (included) to 1 (excluded), all equally likely. */
    UNIFORM("rand", 0, 0),
    /** A whole number from a to b, both included, all equally likely. */
    INTEGER("randint", 2, 2),
    /** A number of the normal distribution with mean μ and standard deviation σ; the standard one (0 and 1) without them. */
    NORMAL("randnorm", 0, 2);

    companion object {
        fun byName(name: String): RandomKind? = entries.firstOrNull { it.functionName == name }
    }
}

/**
 * A random number: the [index]-th one that is asked for in a line of [seed]. A calculation is meant to give the same result
 * every time it is made (for the live result, the steps, the graph, the history that is calculated again), so the number
 * is not drawn when it is asked for but belongs to the line: it is a function of the seed of the line and of the place of
 * the call in it. A new line has a new seed, and committing a line keeps its seed with it (see [RandomDraws]).
 * With arguments that are not known yet (randint(1, n)) it stays as it is until they are.
 */
class RandomNumber(val kind: RandomKind, val arguments: List<Expression>, val seed: Long, val index: Int) : Expression() {

    override val children: List<Expression> get() = arguments

    override fun copyWithChildren(children: List<Expression>): Expression = RandomNumber(kind, children, seed, index)

    override fun canonicalize(context: MathContext): Expression {
        val args = arguments.map { it.canonicalize(context) }
        if (context.shouldStop) return this
        if (!hasRightCount(args.size)) return fail(context, MathIssue.ARGUMENT_COUNT)
        if (args.any { arg -> arg.containsExpressionMatching { it is MatrixExpression } }) return fail(context, MathIssue.MATRIX_UNSUPPORTED)
        if (args.any { it.containsUnit() }) return fail(context, MathIssue.INVALID_UNIT)
        if (args.any { it.containsImaginaryUnit() }) return fail(context, MathIssue.UNDEFINED)
        if (args.any { it.variables().isNotEmpty() }) return RandomNumber(kind, args, seed, index)
        return when (kind) {
            RandomKind.UNIFORM -> Value(Real(RandomDraws.uniform(seed, index)))
            RandomKind.NORMAL -> {
                val (mean, deviation) = normalParameters(args.map { it.evaluateNumerically() })
                if (!(deviation > 0.0) || !mean.isFinite()) return fail(context, MathIssue.UNDEFINED)
                Value(Real(mean + deviation * RandomDraws.standardNormal(seed, index)))
            }
            RandomKind.INTEGER -> {
                val (low, high) = args.map { wholeNumber(it) ?: return fail(context, MathIssue.WHOLE_NUMBERS_ONLY) }
                if (low > high) return fail(context, MathIssue.UNDEFINED)
                val size = high - low + BigInt.ONE
                if (size.value.bitLength() > MAX_BITS) return fail(context, MathIssue.VALUES_TOO_LARGE)
                Value(low + BigInt(RandomDraws.below(seed, index, size.value)))
            }
        }
    }

    private fun hasRightCount(count: Int) = count in kind.minArguments..kind.maxArguments && !(kind == RandomKind.NORMAL && count == 1)

    private fun fail(context: MathContext, issue: MathIssue): Expression {
        context.stopWithError(issue)
        return this
    }

    /** The mean and the standard deviation of randnorm() or randnorm(μ, σ). */
    private fun normalParameters(values: List<Double>): Pair<Double, Double> =
        if (values.isEmpty()) 0.0 to 1.0 else values[0] to values[1]

    private fun wholeNumber(e: Expression): BigInt? {
        val value = e as? Value ?: return null
        value.rational?.let { return it.takeIf { r -> !r.isFractional }?.numerator }
        val d = value.real.value
        return if (d == floor(d) && abs(d) <= MAX_EXACT_DOUBLE) BigInt(d.toLong()) else null
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val values = arguments.map { it.evaluateNumerically(bindings) }
        if (!hasRightCount(values.size) || values.any { it.isNaN() }) return Double.NaN
        return when (kind) {
            RandomKind.UNIFORM -> RandomDraws.uniform(seed, index)
            RandomKind.NORMAL -> {
                val (mean, deviation) = normalParameters(values)
                if (deviation > 0.0) mean + deviation * RandomDraws.standardNormal(seed, index) else Double.NaN
            }
            RandomKind.INTEGER -> {
                if (values.any { it != floor(it) || abs(it) > MAX_EXACT_DOUBLE } || values[0] > values[1]) return Double.NaN
                val size = BigInteger.valueOf(values[1].toLong() - values[0].toLong() + 1)
                values[0] + RandomDraws.below(seed, index, size).toDouble()
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RandomNumber && kind == other.kind && arguments == other.arguments && seed == other.seed && index == other.index

    override fun hashCode(): Int = ((kind.hashCode() * 31 + arguments.hashCode()) * 31 + seed.hashCode()) * 31 + index

    override fun toString(): String = "${kind.functionName}(${arguments.joinToString(", ")})#$index"

    private companion object {
        /** A range of at most 2^1024 numbers for randint. */
        const val MAX_BITS = 1024
        const val MAX_EXACT_DOUBLE = 9.007199254740992E15
    }
}

/**
 * The numbers behind [RandomNumber]: a function of the seed of the line and the number of the call, without any state, so
 * that asking again gives the same number. SplitMix64 turns the two into 64 well mixed bits.
 */
internal object RandomDraws {

    private const val GOLDEN = 0x9E3779B97F4A7C15uL
    private const val MIX_1 = 0xBF58476D1CE4E5B9uL
    private const val MIX_2 = 0x94D049BB133111EBuL

    private fun mix(value: ULong): ULong {
        var z = value
        z = (z xor (z shr 30)) * MIX_1
        z = (z xor (z shr 27)) * MIX_2
        return z xor (z shr 31)
    }

    /** 64 random bits for call number [index] of [seed]; [part] numbers the successive blocks one call uses. */
    fun bits(seed: Long, index: Int, part: Int): ULong =
        mix(seed.toULong() + (index.toULong() * 1_000_003uL + part.toULong() + 1uL) * GOLDEN)

    /** A number from 0 (included) to 1 (excluded) with 53 random bits. */
    fun uniform(seed: Long, index: Int, part: Int = 0): Double = (bits(seed, index, part) shr 11).toDouble() * (1.0 / (1L shl 53).toDouble())

    /** A number of the standard normal distribution (Box-Muller). */
    fun standardNormal(seed: Long, index: Int): Double {
        val u1 = 1.0 - uniform(seed, index, 0) // from 0 (excluded) to 1 (included): no ln(0)
        val u2 = uniform(seed, index, 1)
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /** A whole number from 0 to [bound] − 1, all equally likely (the bits are drawn again where they would favour small numbers). */
    fun below(seed: Long, index: Int, bound: BigInteger): BigInteger {
        if (bound.signum() <= 0 || bound == BigInteger.ONE) return BigInteger.ZERO
        val bitCount = bound.subtract(BigInteger.ONE).bitLength().coerceAtLeast(1)
        val blocks = (bitCount + 63) / 64
        for (attempt in 0 until MAX_ATTEMPTS) {
            var candidate = BigInteger.ZERO
            for (block in 0 until blocks) {
                // The first two parts of a call belong to the real numbers; blocks start after them
                val part = 2 + attempt * blocks + block
                candidate = candidate.shiftLeft(64).or(BigInteger(java.lang.Long.toUnsignedString(bits(seed, index, part).toLong())))
            }
            candidate = candidate.and(BigInteger.ONE.shiftLeft(bitCount).subtract(BigInteger.ONE))
            if (candidate < bound) return candidate
        }
        return BigInteger.ZERO
    }

    /** Each attempt is accepted with a probability of more than ½: this many in a row is as good as impossible. */
    private const val MAX_ATTEMPTS = 200
}
