package com.vortessence.arithmeticus.core.math.solvers

import com.vortessence.arithmeticus.core.math.context.MathContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Limits in floating point: g(h) for h → 0⁺ is sampled at h₀, h₀/2, h₀/4, ... and extrapolated
 * to h = 0 (Richardson). A limit x → a is g(h) = f(a ± h), a limit x → ∞ is g(h) = f(1/h).
 */
object NumericLimit {
    /** Richardson columns used; higher orders only amplify rounding errors. */
    private const val MAX_ORDER = 8
    private const val MIN_SAMPLES = 5

    /**
     * lim h → 0⁺ of [g], from [steps] samples starting at [h0]. Null if [g] is undefined at
     * every sample, i.e. not defined on this side.
     */
    fun oneSided(g: (Double) -> Double, h0: Double, steps: Int, context: MathContext?): NumericOutcome? {
        val raw = DoubleArray(steps)
        for (k in 0 until steps) {
            if (context?.shouldStop == true) return NumericOutcome.Undefined
            raw[k] = g(h0 / (1L shl k))
        }
        val first = raw.indexOfFirst { it.isFinite() }
        if (first < 0) return if (raw.any { it.isInfinite() }) divergenceAt(raw) else null
        // Stop at the first sample without a value: an overflow (e^x for large x) or a
        // cancellation such as ∞/∞ close to the limit
        var end = first
        while (end < steps && raw[end].isFinite()) end++
        val values = raw.copyOfRange(first, end)
        if (end < steps && raw[end].isInfinite() && values.size >= 2) {
            val last = values.last()
            if (sign(last) == sign(raw[end]) && abs(last) >= abs(values[values.size - 2])) {
                return NumericOutcome.Diverges(sign(raw[end]).toInt())
            }
        }
        if (values.size < MIN_SAMPLES) return NumericOutcome.Undefined

        richardson(values)?.let { return it }

        // Settled (e.g. sin(x)/x for large x, where the extrapolation fails on the wiggles)
        val lastFew = values.takeLast(4)
        val spread = lastFew.maxOf { it } - lastFew.minOf { it }
        if (spread <= 1e-8 * max(1.0, abs(values.last()))) return NumericOutcome.Converges(values.last(), spread)

        // Steps that do not shrink: grows without bound (also logarithmically, like ln x at 0)
        val differences = (1 until values.size).map { values[it] - values[it - 1] }.takeLast(5)
        if (differences.size == 5 && differences.all { sign(it) == sign(differences[0]) && it != 0.0 } &&
            (1 until differences.size).all { abs(differences[it]) >= 0.98 * abs(differences[it - 1]) }
        ) {
            return NumericOutcome.Diverges(sign(differences[0]).toInt())
        }
        return NumericOutcome.Undefined
    }

    private fun divergenceAt(raw: DoubleArray): NumericOutcome {
        val signs = raw.filter { it.isInfinite() }.map { sign(it) }.distinct()
        return if (signs.size == 1) NumericOutcome.Diverges(signs[0].toInt()) else NumericOutcome.Undefined
    }

    /**
     * Polynomial extrapolation to h = 0 of samples at halving steps; the entry of the tableau
     * with the smallest error estimate, if that is small enough.
     */
    private fun richardson(values: DoubleArray): NumericOutcome.Converges? {
        val n = values.size
        var previousRow = DoubleArray(0)
        var best: NumericOutcome.Converges? = null
        for (i in 0 until n) {
            val row = DoubleArray(min(i, MAX_ORDER) + 1)
            row[0] = values[i]
            for (j in 1 until row.size) {
                val factor = (1L shl j) - 1.0
                row[j] = row[j - 1] + (row[j - 1] - previousRow[j - 1]) / factor
                val error = max(abs(row[j] - row[j - 1]), abs(row[j] - previousRow[j - 1]))
                if (best == null || error < best.error) best = NumericOutcome.Converges(row[j], error)
            }
            previousRow = row
        }
        return best?.takeIf { it.value.isFinite() && it.error <= 1e-7 * max(1.0, abs(it.value)) }
    }
}
