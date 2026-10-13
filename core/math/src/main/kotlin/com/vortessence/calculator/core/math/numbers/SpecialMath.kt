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

/** The gamma and error functions, the incomplete beta and gamma functions and the distributions built on them, in floating point, accurate to about 13 digits. */
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

    /**
     * P(X = k) for X binomial with [n] trials and success probability [p]; k and n are whole numbers. Loader's algorithm
     * (as in R): the factorials are cut into Stirling's formula and a small error term, so no huge numbers cancel.
     */
    fun binomialPmf(n: Double, p: Double, k: Double): Double = when {
        k < 0.0 || k > n -> 0.0
        p == 0.0 -> if (k == 0.0) 1.0 else 0.0
        p == 1.0 -> if (k == n) 1.0 else 0.0
        k == 0.0 -> exp(n * ln1p(-p))
        k == n -> exp(n * ln(p))
        else -> {
            val q = 1.0 - p
            val logProbability = stirlingError(n) - stirlingError(k) - stirlingError(n - k) - deviance(k, n * p) - deviance(n - k, n * q)
            exp(logProbability) * sqrt(n / (2.0 * PI * k * (n - k)))
        }
    }

    /** ln(n!) − ln(√(2πn)·(n/e)ⁿ): the error of Stirling's formula, small and easy to calculate for large n. */
    private fun stirlingError(n: Double): Double {
        if (n <= 15.0) return lnGamma(n + 1.0) - (n + 0.5) * ln(n) + n - 0.5 * ln(2.0 * PI)
        val square = n * n
        return (1.0 / 12.0 - (1.0 / 360.0 - (1.0 / 1260.0 - (1.0 / 1680.0 - 1.0 / (1188.0 * square)) / square) / square) / square) / n
    }

    /** x·ln(x/m) + m − x, calculated without losing digits where x is close to m. */
    private fun deviance(x: Double, m: Double): Double {
        if (abs(x - m) < 0.1 * (x + m)) {
            var v = (x - m) / (x + m)
            var sum = (x - m) * v
            if (abs(sum) < Double.MIN_VALUE) return sum
            var term = 2.0 * x * v
            v *= v
            for (j in 1..1000) {
                term *= v
                val next = sum + term / (2 * j + 1)
                if (next == sum) return next
                sum = next
            }
        }
        return x * ln(x / m) + m - x
    }

    /** P(X ≤ k) for a binomial X: the sum of the (positive) terms, which stays accurate in both tails. */
    fun binomialCdf(n: Double, p: Double, k: Double): Double = when {
        k < 0.0 -> 0.0
        k >= n -> 1.0
        else -> minOf(1.0, (0..k.toInt()).sumOf { binomialPmf(n, p, it.toDouble()) })
    }

    /** P(X = k) for X Poisson with mean [lambda] (Loader's algorithm, like [binomialPmf]). */
    fun poissonPmf(lambda: Double, k: Double): Double = when {
        k < 0.0 -> 0.0
        lambda == 0.0 -> if (k == 0.0) 1.0 else 0.0
        k == 0.0 -> exp(-lambda)
        else -> exp(-stirlingError(k) - deviance(k, lambda)) / sqrt(2.0 * PI * k)
    }

    /** P(X ≤ k) for a Poisson X. */
    fun poissonCdf(lambda: Double, k: Double): Double =
        if (k < 0.0) 0.0 else minOf(1.0, (0..k.toInt()).sumOf { poissonPmf(lambda, it.toDouble()) })

    /** ln B(a, b) = ln Γ(a) + ln Γ(b) − ln Γ(a + b) for a, b > 0, without the huge cancelling terms for large arguments (as R does). */
    fun lnBeta(a: Double, b: Double): Double {
        if (a.isNaN() || b.isNaN() || !(a > 0.0) || !(b > 0.0)) return Double.NaN
        val p = minOf(a, b)
        val q = maxOf(a, b)
        return when {
            p >= 10.0 -> {
                val correction = stirlingError(p) + stirlingError(q) - stirlingError(p + q)
                -0.5 * ln(q) + 0.5 * ln(2.0 * PI) + correction + (p - 0.5) * ln(p / (p + q)) + q * ln1p(-p / (p + q))
            }
            q >= 10.0 -> {
                val correction = stirlingError(q) - stirlingError(p + q)
                lnGamma(p) + correction + p - p * ln(p + q) + (q - 0.5) * ln1p(-p / (p + q))
            }
            else -> lnGamma(a) + lnGamma(b) - lnGamma(a + b)
        }
    }

    private const val CF_EPSILON = 3e-16
    private const val CF_TINY = 1e-300
    private const val CF_MAX_ITERATIONS = 100_000

    /**
     * The regularized incomplete beta function I_x(a, b) for 0 ≤ x ≤ 1, a, b > 0. [complement] is 1 − x, passed along because
     * it is only accurate when it is not calculated from x (x close to 1).
     */
    fun regularizedBeta(
        x: Double, complement: Double, a: Double, b: Double,
        /** ln of x^a·(1 − x)^b/B(a, b), for callers that can work it out without losing digits (large a or b) */
        logFront: Double = lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(complement)
    ): Double {
        if (x.isNaN() || a.isNaN() || b.isNaN() || !(a > 0.0) || !(b > 0.0)) return Double.NaN
        if (x <= 0.0) return 0.0
        if (complement <= 0.0) return 1.0
        val front = exp(logFront)
        // The continued fraction converges fastest below the mean (a + 1)/(a + b + 2); above it by symmetry. But for a large a and an
        // x within a few 1/a of 1, the terms of the direct fraction nearly cancel and cost digits: there the symmetric one is used
        // (and there the probability is not small, so 1 − … loses nothing).
        val lower = x < (a + 1.0) / (a + b + 2.0) && !(a > 1000.0 && a * complement < 10.0)
        if (front == 0.0) return if (lower) 0.0 else 1.0
        return if (lower) front * betaFraction(x, a, b) / a else 1.0 - front * betaFraction(complement, b, a) / b
    }

    /** The continued fraction of the incomplete beta function (modified Lentz's method). */
    private fun betaFraction(x: Double, a: Double, b: Double): Double {
        val sum = a + b
        val plus = a + 1.0
        val minus = a - 1.0
        var c = 1.0
        var d = 1.0 - sum * x / plus
        if (abs(d) < CF_TINY) d = CF_TINY
        d = 1.0 / d
        var h = d
        for (m in 1..CF_MAX_ITERATIONS) {
            val twice = 2.0 * m
            var term = m * (b - m) * x / ((minus + twice) * (a + twice))
            d = 1.0 + term * d
            if (abs(d) < CF_TINY) d = CF_TINY
            c = 1.0 + term / c
            if (abs(c) < CF_TINY) c = CF_TINY
            d = 1.0 / d
            h *= d * c
            term = -(a + m) * (sum + m) * x / ((a + twice) * (plus + twice))
            d = 1.0 + term * d
            if (abs(d) < CF_TINY) d = CF_TINY
            c = 1.0 + term / c
            if (abs(c) < CF_TINY) c = CF_TINY
            d = 1.0 / d
            val change = d * c
            h *= change
            if (abs(change - 1.0) < CF_EPSILON) break
        }
        return h
    }

    /** The regularized lower incomplete gamma function P(a, x) = γ(a, x)/Γ(a). */
    fun regularizedGammaP(a: Double, x: Double): Double {
        if (x.isNaN() || a.isNaN() || !(a > 0.0)) return Double.NaN
        if (x <= 0.0) return 0.0
        if (x.isInfinite()) return 1.0
        return if (x < a + 1.0) gammaSeries(a, x) else 1.0 - gammaFraction(a, x)
    }

    /** The regularized upper incomplete gamma function Q(a, x) = 1 − P(a, x), accurate where P is almost 1. */
    fun regularizedGammaQ(a: Double, x: Double): Double {
        if (x.isNaN() || a.isNaN() || !(a > 0.0)) return Double.NaN
        if (x <= 0.0) return 1.0
        if (x.isInfinite()) return 0.0
        return if (x < a + 1.0) 1.0 - gammaSeries(a, x) else gammaFraction(a, x)
    }

    /** P(a, x) as the series x^a e^(−x)/Γ(a+1) · (1 + x/(a+1) + x²/((a+1)(a+2)) + …), for x < a + 1. */
    private fun gammaSeries(a: Double, x: Double): Double {
        var term = 1.0 / a
        var sum = term
        var ap = a
        for (n in 1..CF_MAX_ITERATIONS) {
            ap += 1.0
            term *= x / ap
            sum += term
            if (abs(term) < abs(sum) * CF_EPSILON) break
        }
        return sum * exp(gammaLogFront(a, x))
    }

    /** Q(a, x) as a continued fraction (modified Lentz's method), for x ≥ a + 1. */
    private fun gammaFraction(a: Double, x: Double): Double {
        var b = x + 1.0 - a
        var c = 1.0 / CF_TINY
        var d = 1.0 / b
        var h = d
        for (i in 1..CF_MAX_ITERATIONS) {
            val an = -i * (i - a)
            b += 2.0
            d = an * d + b
            if (abs(d) < CF_TINY) d = CF_TINY
            c = b + an / c
            if (abs(c) < CF_TINY) c = CF_TINY
            d = 1.0 / d
            val change = d * c
            h *= change
            if (abs(change - 1.0) < CF_EPSILON) break
        }
        return exp(gammaLogFront(a, x)) * h
    }

    /** a·ln x − x − ln Γ(a), the factor of the incomplete gamma function; for large a without subtracting large numbers. */
    private fun gammaLogFront(a: Double, x: Double): Double {
        if (a < 30.0) return a * ln(x) - x - lnGamma(a)
        // With Stirling's series for ln Γ(a): a·(ln(x/a) − x/a + 1) + ½·ln(a) − ½·ln(2π) − 1/(12a) + …
        val u = (x - a) / a
        val shape = if (u > -0.5 && u < 5.0) ln1p(u) - u else ln(x / a) - u
        val inverseSquare = 1.0 / (a * a)
        val correction = (1.0 / 12.0 - inverseSquare * (1.0 / 360.0 - inverseSquare / 1260.0)) / a
        return a * shape + 0.5 * ln(a) - 0.5 * ln(2.0 * PI) - correction
    }

    /** ln Γ(a + ½) − ln Γ(a), without the loss of digits of subtracting two large numbers. */
    private fun lnGammaRatioHalf(a: Double): Double {
        if (a < 30.0) return lnGamma(a + 0.5) - lnGamma(a)
        val inverse = 1.0 / a
        val square = inverse * inverse
        return 0.5 * ln(a) - inverse * (1.0 / 8.0 - square * (1.0 / 192.0 - square * (1.0 / 640.0 - square * 17.0 / 14336.0)))
    }

    /** The density of Student's t distribution with [df] degrees of freedom at [x]. */
    fun studentPdf(x: Double, df: Double): Double {
        if (x.isNaN() || !(df > 0.0)) return Double.NaN
        return exp(lnGammaRatioHalf(df / 2.0) - 0.5 * ln(df * PI) - (df + 1.0) / 2.0 * ln1p(x * x / df))
    }

    /** P(T ≤ x) for Student's t distribution; through the tail beyond |x|, so far-out values keep their digits. */
    fun studentCdf(x: Double, df: Double): Double {
        if (x.isNaN() || !(df > 0.0)) return Double.NaN
        if (x == 0.0) return 0.5
        if (x.isInfinite()) return if (x > 0.0) 1.0 else 0.0
        val square = x * x
        // P(|T| > |x|) = I_{df/(df + x²)}(df/2, 1/2)
        val a = df / 2.0
        val complement = square / (df + square)
        // x^a·(1 − x)^(1/2)/B(a, 1/2) with x = df/(df + t²) = 1/(1 + t²/df), in logarithms that stay exact for huge df
        val logFront = lnGammaRatioHalf(a) - 0.5 * ln(PI) - a * ln1p(square / df) + 0.5 * ln(complement)
        val tail = 0.5 * regularizedBeta(df / (df + square), complement, a, 0.5, logFront)
        return if (x < 0.0) tail else 1.0 - tail
    }

    /** The x with P(T ≤ x) = p for Student's t distribution; NaN unless 0 < p < 1. */
    fun studentInverse(p: Double, df: Double): Double {
        if (p.isNaN() || !(df > 0.0) || p <= 0.0 || p >= 1.0) return Double.NaN
        if (p == 0.5) return 0.0
        if (p > 0.5) return -studentInverse(1.0 - p, df)
        // The lower half: a root of cdf(x) − p below zero, from the normal quantile with the Cornish-Fisher correction
        val z = inverseNormal(p)
        val guess = z + (z * z * z + z) / (4.0 * df) + (5.0 * z.pow(5) + 16.0 * z.pow(3) + 3.0 * z) / (96.0 * df * df)
        return solveMonotone(
            guess = if (guess.isFinite() && guess < 0.0) guess else -1.0,
            lower = Double.NEGATIVE_INFINITY, upper = 0.0, target = p,
            cdf = { studentCdf(it, df) }, pdf = { studentPdf(it, df) }
        )
    }

    /** The density of the chi-square distribution with [df] degrees of freedom at [x] (0 below 0). */
    fun chiSquarePdf(x: Double, df: Double): Double = when {
        x.isNaN() || !(df > 0.0) -> Double.NaN
        x < 0.0 -> 0.0
        x == 0.0 -> if (df < 2.0) Double.POSITIVE_INFINITY else if (df == 2.0) 0.5 else 0.0
        else -> exp((df / 2.0 - 1.0) * ln(x) - x / 2.0 - (df / 2.0) * ln(2.0) - lnGamma(df / 2.0))
    }

    fun chiSquareCdf(x: Double, df: Double): Double = if (x.isNaN() || !(df > 0.0)) Double.NaN else regularizedGammaP(df / 2.0, x / 2.0)

    /** The x with P(X ≤ x) = p for the chi-square distribution; NaN unless 0 < p < 1. */
    fun chiSquareInverse(p: Double, df: Double): Double {
        if (p.isNaN() || !(df > 0.0) || p <= 0.0 || p >= 1.0) return Double.NaN
        // Wilson-Hilferty: (X/k)^(1/3) is nearly normal
        val z = inverseNormal(p)
        val c = 2.0 / (9.0 * df)
        val guess = df * (1.0 - c + z * sqrt(c)).pow(3)
        return solveMonotone(
            guess = if (guess.isFinite() && guess > 0.0) guess else df,
            lower = 0.0, upper = Double.POSITIVE_INFINITY, target = p,
            cdf = { chiSquareCdf(it, df) }, pdf = { chiSquarePdf(it, df) }
        )
    }

    /**
     * The x in ([lower], [upper]) at which the increasing [cdf] is [target]: Newton's method from [guess], with the
     * bracket around the root kept so that a step outside of it (a flat or steep part) is a bisection instead.
     */
    private fun solveMonotone(
        guess: Double, lower: Double, upper: Double, target: Double,
        cdf: (Double) -> Double, pdf: (Double) -> Double
    ): Double {
        var low = lower
        var high = upper
        var x = guess
        // A finite bracket first: step away from the guess until it holds the root
        if (low.isInfinite()) {
            var bound = x
            var tries = 0
            while (cdf(bound) > target && tries++ < 2000) bound *= 2.0
            low = bound
        }
        if (high.isInfinite()) {
            var bound = x
            var tries = 0
            while (cdf(bound) < target && tries++ < 2000) bound *= 2.0
            high = bound
        }
        // A bisection that also gets to a root very close to a bound (1e-240) in a few steps: by factors, not by halves
        fun middle(): Double = when {
            low == 0.0 && high > 0.0 -> high / 8.0
            high == 0.0 && low < 0.0 -> low / 8.0
            low > 0.0 && high / low > 4.0 -> sqrt(low * high)
            high < 0.0 && low / high > 4.0 -> -sqrt(low * high)
            else -> low + (high - low) / 2.0
        }
        if (x <= low || x >= high) x = middle()
        for (i in 0 until 500) {
            val error = cdf(x) - target
            if (error == 0.0) return x
            if (error > 0.0) high = x else low = x
            val density = pdf(x)
            var next = if (density > 0.0 && density.isFinite()) x - error / density else Double.NaN
            if (!next.isFinite() || next <= low || next >= high) next = middle()
            if (abs(next - x) <= 1e-15 * abs(next) || next == x) return next
            x = next
        }
        return x
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
