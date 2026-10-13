package com.vortessence.calculator.core.math.numbers

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The gamma function and the error function in floating point, accurate to about 14 digits. */
internal object SpecialMath {

    private const val LANCZOS_G = 7.0
    private val LANCZOS = doubleArrayOf(
        0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7
    )

    /** Γ(171) = 170! is the last gamma value that fits into a double. */
    private const val LARGEST_FACTORIAL = 171

    /** Γ(x); NaN at 0 and the negative whole numbers (where it has poles), infinity where it is too large. */
    fun gamma(x: Double): Double {
        if (x.isNaN()) return Double.NaN
        if (x == floor(x)) {
            if (x <= 0.0) return Double.NaN
            if (x > LARGEST_FACTORIAL) return Double.POSITIVE_INFINITY
            // Exact product, so Γ(5) is 24 and not 23.999999999999996
            var product = 1.0
            for (k in 2 until x.toInt()) product *= k
            return product
        }
        if (x < 0.5) return PI / (sin(PI * x) * gamma(1.0 - x))
        if (x > 172.0) return Double.POSITIVE_INFINITY
        val shifted = x - 1.0
        val t = shifted + LANCZOS_G + 0.5
        val series = lanczosSeries(shifted)
        // t^(x - 1/2) is too large for a double next to the end of the range, so it is applied in two halves
        val half = t.pow((shifted + 0.5) / 2.0)
        return sqrt(2.0 * PI) * half * exp(-t) * half * series
    }

    /** ln Γ(x) for x > 0; NaN otherwise. */
    fun lnGamma(x: Double): Double {
        if (x.isNaN() || x <= 0.0) return Double.NaN
        if (x == 1.0 || x == 2.0) return 0.0
        if (x < 0.5) return ln(PI / sin(PI * x)) - lnGamma(1.0 - x)
        val shifted = x - 1.0
        val t = shifted + LANCZOS_G + 0.5
        return 0.5 * ln(2.0 * PI) + (shifted + 0.5) * ln(t) - t + ln(lanczosSeries(shifted))
    }

    private fun lanczosSeries(shifted: Double): Double {
        var sum = LANCZOS[0]
        for (i in 1 until LANCZOS.size) sum += LANCZOS[i] / (shifted + i)
        return sum
    }

    /** The error function 2/√π · ∫₀ˣ e^(−t²) dt. */
    fun erf(x: Double): Double = when {
        x.isNaN() -> Double.NaN
        x < 0.0 -> -erf(-x)
        x < CONTINUED_FRACTION_FROM -> erfSeries(x)
        else -> 1.0 - erfcLarge(x)
    }

    /** The complementary error function 1 − erf(x), accurate also where erf(x) is almost 1. */
    fun erfc(x: Double): Double = when {
        x.isNaN() -> Double.NaN
        x < 0.0 -> 2.0 - erfc(-x)
        x < CONTINUED_FRACTION_FROM -> 1.0 - erfSeries(x)
        else -> erfcLarge(x)
    }

    private const val CONTINUED_FRACTION_FROM = 2.0

    /** erf(x) = 2/√π · e^(−x²) · Σ 2ⁿ x^(2n+1) / (1·3·5···(2n+1)), a sum of positive terms. */
    private fun erfSeries(x: Double): Double {
        var term = x
        var sum = x
        var n = 0
        while (abs(term) > 1e-17 * abs(sum) && n < 500) {
            term *= 2.0 * x * x / (2 * n + 3)
            sum += term
            n++
        }
        return 2.0 / sqrt(PI) * exp(-x * x) * sum
    }

    /** erfc(x) = e^(−x²)/√π · 1/(x + (1/2)/(x + 1/(x + (3/2)/(x + …)))) for x ≥ 2. */
    private fun erfcLarge(x: Double): Double {
        if (x > 27.3) return 0.0
        var fraction = x
        for (k in 300 downTo 1) fraction = x + (k / 2.0) / fraction
        return exp(-x * x) / (sqrt(PI) * fraction)
    }
}
