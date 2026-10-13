package com.vortessence.calculator.core.math.numbers

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
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

    /** ψ(x) = Γ'(x)/Γ(x); NaN at 0 and the negative whole numbers. */
    fun digamma(x: Double): Double {
        if (x.isNaN() || (x <= 0.0 && x == floor(x))) return Double.NaN
        // ψ(1 − x) − ψ(x) = π·cot(πx)
        if (x < 0.5) return digamma(1.0 - x) - PI / tan(PI * x)
        // ψ(x) = ψ(x + 1) − 1/x up to where the asymptotic series is accurate
        var shifted = x
        var result = 0.0
        while (shifted < 10.0) {
            result -= 1.0 / shifted
            shifted += 1.0
        }
        val inverse = 1.0 / shifted
        val square = inverse * inverse
        return result + ln(shifted) - 0.5 * inverse -
            square * (1.0 / 12 - square * (1.0 / 120 - square * (1.0 / 252 - square * (1.0 / 240 - square * (1.0 / 132 - square * (691.0 / 32760 - square / 12))))))
    }

    /** The x with Φ(x) = p for the standard normal distribution; NaN unless 0 < p < 1. */
    fun inverseNormal(p: Double): Double {
        if (p.isNaN() || p <= 0.0 || p >= 1.0) return Double.NaN
        if (p == 0.5) return 0.0
        // The upper half by symmetry, so the tails keep their relative accuracy
        if (p > 0.5) return -inverseNormal(1.0 - p)
        // Acklam's rational approximation (relative error about 1e-9) ...
        val low = 0.02425
        var x = if (p < low) {
            val q = sqrt(-2.0 * ln(p))
            (((((ACKLAM_C[0] * q + ACKLAM_C[1]) * q + ACKLAM_C[2]) * q + ACKLAM_C[3]) * q + ACKLAM_C[4]) * q + ACKLAM_C[5]) /
                ((((ACKLAM_D[0] * q + ACKLAM_D[1]) * q + ACKLAM_D[2]) * q + ACKLAM_D[3]) * q + 1.0)
        } else {
            val q = p - 0.5
            val r = q * q
            (((((ACKLAM_A[0] * r + ACKLAM_A[1]) * r + ACKLAM_A[2]) * r + ACKLAM_A[3]) * r + ACKLAM_A[4]) * r + ACKLAM_A[5]) * q /
                (((((ACKLAM_B[0] * r + ACKLAM_B[1]) * r + ACKLAM_B[2]) * r + ACKLAM_B[3]) * r + ACKLAM_B[4]) * r + 1.0)
        }
        // ... and one step of Halley's method on Φ(x) − p
        val error = 0.5 * erfc(-x / sqrt(2.0)) - p
        val u = error * sqrt(2.0 * PI) * exp(x * x / 2.0)
        x -= u / (1.0 + x * u / 2.0)
        return x
    }

    private val ACKLAM_A = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
    private val ACKLAM_B = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
    private val ACKLAM_C = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
    private val ACKLAM_D = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)

    /** P(X = k) for X binomial with [n] trials and success probability [p]; k and n are whole numbers. */
    fun binomialPmf(n: Double, p: Double, k: Double): Double = when {
        k < 0.0 || k > n -> 0.0
        p == 0.0 -> if (k == 0.0) 1.0 else 0.0
        p == 1.0 -> if (k == n) 1.0 else 0.0
        else -> exp(lnGamma(n + 1.0) - lnGamma(k + 1.0) - lnGamma(n - k + 1.0) + k * ln(p) + (n - k) * ln1p(-p))
    }

    /** P(X ≤ k) for a binomial X: the sum of the (positive) terms, which stays accurate in both tails. */
    fun binomialCdf(n: Double, p: Double, k: Double): Double = when {
        k < 0.0 -> 0.0
        k >= n -> 1.0
        else -> minOf(1.0, (0..k.toInt()).sumOf { binomialPmf(n, p, it.toDouble()) })
    }

    /** P(X = k) for X Poisson with mean [lambda]. */
    fun poissonPmf(lambda: Double, k: Double): Double = when {
        k < 0.0 -> 0.0
        lambda == 0.0 -> if (k == 0.0) 1.0 else 0.0
        else -> exp(-lambda + k * ln(lambda) - lnGamma(k + 1.0))
    }

    /** P(X ≤ k) for a Poisson X. */
    fun poissonCdf(lambda: Double, k: Double): Double =
        if (k < 0.0) 0.0 else minOf(1.0, (0..k.toInt()).sumOf { poissonPmf(lambda, it.toDouble()) })

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
