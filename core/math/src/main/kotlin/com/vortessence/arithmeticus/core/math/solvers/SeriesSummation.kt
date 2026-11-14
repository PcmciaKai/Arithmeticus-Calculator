package com.vortessence.arithmeticus.core.math.solvers

import com.vortessence.arithmeticus.core.math.context.MathContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The outcome of an infinite sum or a limit computed in floating point. */
sealed interface NumericOutcome {
    /** Converges to [value], which is off by about [error]. */
    data class Converges(val value: Double, val error: Double) : NumericOutcome
    /** Grows without bound towards +∞ ([sign] 1) or −∞ ([sign] −1). */
    data class Diverges(val sign: Int) : NumericOutcome
    /** Has no value: oscillates without settling, or is undefined. */
    data object Undefined : NumericOutcome
    /** Could not be decided: may converge (too slowly to tell, e.g. Σ 1/(n·ln²n)) or not. */
    data object Undecided : NumericOutcome
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
    /** Partial sums that grow by this factor of the previous block (or more) may not converge: see [powerLaw]. */
    private const val DIVERGENT_BLOCK_RATIO = 0.97
    /** How much the exponent of a power law may differ between early and late terms and still count as the same. */
    private const val POWER_STABILITY = 1e-6

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

        // Terms that hardly get smaller from the 64th part of them to the end. Of one sign, that never converges; with signs
        // that change it does not either if they do not get smaller at all (Σ (−1)ⁿ), but may if they do slowly (Σ (−1)ⁿ/n^0.01)
        val late = (count / 2 until count).maxOf { abs(terms[it]) }
        val early = (count / 64 until count / 32).maxOf { abs(terms[it]) }
        if (late > 0.0 && late >= 0.9 * early) {
            val outcome = divergence(terms, count)
            return if (outcome is NumericOutcome.Undefined && late < 0.999 * early) NumericOutcome.Undecided else outcome
        }

        val n = Integer.highestOneBit(count)
        fun s(k: Int) = partial[k - 1]
        val b0 = s(n / 4) - s(n / 8)
        val b1 = s(n / 2) - s(n / 4)
        val b2 = s(n) - s(n / 2)
        // Converged within the terms computed (e.g. a geometric series)
        if (abs(b2) <= 1e-15 * scale) return NumericOutcome.Converges(total, abs(b2))

        // Terms that change sign again and again (Σ (−1)ⁿ/√n, Σ sin(n)/n): the partial sums swing around the value, the blocks
        // say nothing about it
        val lateTerms = (count / 2 until count).map { terms[it] }.filter { it != 0.0 }
        if (lateTerms.any { it > 0.0 } && lateTerms.any { it < 0.0 }) return oscillating(terms, partial, count, scale)

        // Terms like C·n⁻ᵖ: exact to the last digit, which the extrapolations below are not (Σ 1/n^1.5 was off by 3e-9)
        powerLaw(terms, count, from, scale)?.let { return it }

        if (b1 == 0.0 || abs(b2 / b1) >= DIVERGENT_BLOCK_RATIO) {
            // Slowly: either a power law that is clearly above or at 1 (Σ 1/n^1.01 converges, Σ 1/n does not), or undecided
            return powerLaw(terms, count, from, scale) ?: NumericOutcome.Undecided
        }

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
        return powerLaw(terms, count, from, scale) ?: NumericOutcome.Undecided
    }

    /**
     * The terms of one sign that fall like C·n⁻ᵖ with an exponent p that stays the same (1/n², 1/n^1.01, 1/√n): divergent for p ≤ 1,
     * otherwise the sum with the rest of the series worked out by Euler–Maclaurin, C·(n¹⁻ᵖ/(p − 1) − n⁻ᵖ/2 + p·n⁻ᵖ⁻¹/12).
     * Null where p drifts (Σ ln(n)/n², Σ 1/(n·ln n)): a logarithm in the terms, which a power law cannot tell apart.
     */
    private fun powerLaw(terms: DoubleArray, count: Int, from: Long, scale: Double): NumericOutcome? {
        if (count < 256) return null
        fun index(k: Int) = (from + k).toDouble()
        fun exponent(k: Int): Double {
            val a = terms[k]
            val b = terms[2 * k]
            if (a == 0.0 || b == 0.0 || (a > 0.0) != (b > 0.0)) return Double.NaN
            return -ln(b / a) / ln(index(2 * k) / index(k))
        }
        val pLate = exponent(count / 2 - 1)
        val pEarly = exponent(count / 64)
        if (!pLate.isFinite() || !pEarly.isFinite() || index(count / 64) <= 0.0) return null
        val sign = if (terms[count - 1] > 0.0) 1 else -1
        // An exponent that goes to 1 the way C/n·(1 + d/n) does (ln(1 + 1/n), sin(1/n)): diverges like Σ 1/n. Not one that
        // stays above 1 (1/n^1.0005) or goes to it as slowly as a logarithm (1/(n·ln²n), which converges)
        if (pLate <= 1.0 + POWER_STABILITY || (pLate - 1.0 < 1e-3 && pLate - 1.0 <= 0.1 * (pEarly - 1.0))) return NumericOutcome.Diverges(sign)
        if (abs(pLate - pEarly) > POWER_STABILITY) return null
        // The rest after the k-th term, and the sum with it, at two places: their difference says how good the model is
        fun sumWithRest(k: Int): Double {
            val m = index(k)
            val c = terms[k] * m.pow(pLate)
            val rest = c * (m.pow(1.0 - pLate) / (pLate - 1.0) - m.pow(-pLate) / 2.0 + pLate * m.pow(-pLate - 1.0) / 12.0)
            var sum = 0.0
            var compensation = 0.0
            for (j in 0..k) {
                val t = terms[j]
                val s = sum + t
                compensation += if (abs(sum) >= abs(t)) (sum - s) + t else (t - s) + sum
                sum = s
            }
            return sum + compensation + rest
        }
        val late = sumWithRest(count - 1)
        val earlier = sumWithRest(count / 8)
        val error = abs(late - earlier)
        if (!late.isFinite() || error > 1e-11 * max(scale, abs(late))) return null
        return NumericOutcome.Converges(late, error)
    }

    /**
     * A series whose terms keep changing sign. Its value is the middle the partial sums swing around: their average, weighted
     * with a smooth window (which cancels the swinging), over the last half and over the last quarter of the terms. Where the two
     * agree, that is the value, checked with Levin's transformation where it works (alternating series); otherwise undecided.
     */
    private fun oscillating(terms: DoubleArray, partial: DoubleArray, count: Int, scale: Double): NumericOutcome {
        fun windowed(start: Int): Double {
            val length = count - start
            var sum = 0.0
            var weights = 0.0
            for (k in start until count) {
                // Blackman window: the swinging leaks into the average by less than 1e-4 of its size
                val t = 2.0 * Math.PI * (k - start) / (length - 1)
                val w = 0.42 - 0.5 * kotlin.math.cos(t) + 0.08 * kotlin.math.cos(2.0 * t)
                sum += w * partial[k]
                weights += w
            }
            return sum / weights
        }
        val half = windowed(count / 2)
        val quarter = windowed(count - count / 4)
        val spread = abs(half - quarter)
        val levin = levinU(terms, min(count, LEVIN_TERMS))
        // Levin only where the averages back it to the digits shown (10 of them)
        if (levin != null && abs(levin.value - quarter) <= max(10.0 * spread, 1e-8 * scale) && spread <= 1e-7 * scale) return levin
        if (spread <= 1e-11 * scale) return NumericOutcome.Converges(quarter, spread)
        return NumericOutcome.Undecided
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
