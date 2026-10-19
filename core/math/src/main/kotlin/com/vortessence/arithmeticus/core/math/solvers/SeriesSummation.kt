package com.vortessence.arithmeticus.core.math.solvers

import com.vortessence.arithmeticus.core.math.context.MathContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The outcome of an infinite sum or a limit computed in floating point. */
sealed interface NumericOutcome {
    /** Converges to [value], which is off by about [error]. */
    data class Converges(val value: Double, val error: Double) : NumericOutcome
    /** Grows without bound towards +∞ ([sign] 1) or −∞ ([sign] −1). */
    data class Diverges(val sign: Int) : NumericOutcome
    /** Has no value: oscillates, is undefined, or could not be decided. */
    data object Undefined : NumericOutcome
}

/**
 * Infinite series a(from) + a(from + 1) + ... in floating point. Slowly converging series such
 * as Σ 1/n² are accelerated with Levin's u-transformation (checked against an extrapolation of
 * the partial sums), divergent ones such as Σ 1/n are recognised by their partial sums.
 */
object SeriesSummation {
    private const val MAX_TERMS = 1 shl 17
    private const val MIN_TERMS = 32
    private const val LEVIN_TERMS = 40
    /** Partial sums that grow by this factor of the previous block (or more) do not converge. */
    private const val DIVERGENT_BLOCK_RATIO = 0.97

    fun sumToInfinity(term: (Long) -> Double, from: Long, context: MathContext?): NumericOutcome {
        val terms = DoubleArray(MAX_TERMS)
        var count = 0
        while (count < MAX_TERMS) {
            if (count % 1024 == 0 && context?.shouldStop == true) return NumericOutcome.Undefined
            val t = term(from + count)
            if (!t.isFinite()) {
                // A pole among the first terms; later on usually an overflow of e.g. n!, so the
                // terms so far are used
                if (count < MIN_TERMS) return NumericOutcome.Undefined
                break
            }
            terms[count++] = t
        }

        // Partial sums, compensated (Neumaier) to keep rounding errors from adding up
        val partial = DoubleArray(count)
        var sum = 0.0
        var compensation = 0.0
        for (k in 0 until count) {
            val t = terms[k]
            val s = sum + t
            compensation += if (abs(sum) >= abs(t)) (sum - s) + t else (t - s) + sum
            sum = s
            partial[k] = sum + compensation
        }
        val total = partial[count - 1]
        val scale = max(1.0, abs(total))

        // Terms that do not get smaller: no convergence
        val quarter = count / 4
        val tail = (count - quarter until count).maxOf { abs(terms[it]) }
        val before = (quarter until 2 * quarter).maxOf { abs(terms[it]) }
        if (tail > 0.0 && tail >= 0.5 * before) return divergence(terms, count)

        val n = Integer.highestOneBit(count)
        fun s(k: Int) = partial[k - 1]
        val b0 = s(n / 4) - s(n / 8)
        val b1 = s(n / 2) - s(n / 4)
        val b2 = s(n) - s(n / 2)
        // Converged within the terms computed (e.g. a geometric series)
        if (abs(b2) <= 1e-15 * scale) return NumericOutcome.Converges(total, abs(b2))
        if (b1 == 0.0 || abs(b2 / b1) >= DIVERGENT_BLOCK_RATIO) return divergence(terms, count)

        // The blocks between the partial sums at n/4, n/2, n shrink geometrically for power-law
        // tails; their geometric continuation estimates the rest of the series
        fun extrapolate(prev: Double, last: Double, at: Double): Double {
            val r = last / prev
            return at + last * r / (1.0 - r)
        }
        val blockEstimate = extrapolate(b1, b2, s(n))
        val blockError = if (b0 != 0.0) abs(blockEstimate - extrapolate(b0, b1, s(n / 2))) else abs(b2)

        val levin = levinU(terms, min(count, LEVIN_TERMS))
        if (levin != null && abs(levin.value - blockEstimate) <= max(10.0 * blockError, 1e-9 * scale)) {
            return levin
        }
        if (blockError <= 1e-6 * scale) return NumericOutcome.Converges(blockEstimate, blockError)
        return NumericOutcome.Undefined
    }

    /** Diverges to ±∞ if the terms eventually have one sign, otherwise it has no value. */
    private fun divergence(terms: DoubleArray, count: Int): NumericOutcome {
        val tail = (count / 2 until count).map { terms[it] }.filter { it != 0.0 }
        return when {
            tail.isEmpty() -> NumericOutcome.Undefined
            tail.all { it > 0.0 } -> NumericOutcome.Diverges(1)
            tail.all { it < 0.0 } -> NumericOutcome.Diverges(-1)
            else -> NumericOutcome.Undefined
        }
    }

    /**
     * Levin's u-transformation of the first [count] terms (remainder estimates (n + 1)·aₙ),
     * taking the order whose estimate changes least from the order before.
     */
    private fun levinU(terms: DoubleArray, count: Int): NumericOutcome.Converges? {
        if ((0 until count).any { terms[it] == 0.0 }) return null
        val partial = DoubleArray(count)
        var s = 0.0
        for (j in 0 until count) {
            s += terms[j]
            partial[j] = s
        }
        var best: NumericOutcome.Converges? = null
        var previous = Double.NaN
        for (k in 2 until count) {
            var numerator = 0.0
            var denominator = 0.0
            var binomial = 1.0
            for (j in 0..k) {
                if (j > 0) binomial = binomial * (k - j + 1) / j
                val weight = (if (j % 2 == 0) 1.0 else -1.0) * binomial *
                    ((1.0 + j) / (1.0 + k)).pow(k - 1) / ((j + 1) * terms[j])
                numerator += weight * partial[j]
                denominator += weight
            }
            val estimate = numerator / denominator
            if (estimate.isFinite() && previous.isFinite()) {
                val change = abs(estimate - previous)
                if (best == null || change < best.error) best = NumericOutcome.Converges(estimate, change)
            }
            previous = estimate
        }
        return best?.takeIf { it.error <= 1e-10 * max(1.0, abs(it.value)) }
    }
}
