package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.numbers.BigInt
import com.vortessence.arithmeticus.core.math.numbers.Rational
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Splits a polynomial with rational coefficients into factors with whole coefficients that have no rational root:
 * x⁴ + 4 = (x² − 2x + 2)(x² + 2x + 2), x⁶ + 1 = (x² + 1)(x⁴ − x² + 1). Rational roots are found elsewhere (from the divisors
 * of the coefficients); this finds the factors of higher degree. The complex roots are found in decimals, groups of them are
 * multiplied out, and where that gives whole coefficients, the factor is checked by dividing exactly. So a factor that is
 * given is one, whatever the decimals were.
 */
internal object PolynomialSplitting {

    /**
     * The factors of [p] (coefficients from the lowest power up), each with whole coefficients and the lowest degree first,
     * times the number that is left ([Factors.constant]). Null if it has no factors (or too high a degree to look).
     */
    fun split(p: List<Rational>, shouldStop: () -> Boolean = { false }): Factors? {
        val degree = p.indexOfLast { !it.isZero }
        if (degree < 2 || degree > MAX_DEGREE || p[0].isZero) return null
        val (constant, whole) = primitive(p.subList(0, degree + 1))
        val factors = splitWhole(whole, shouldStop) ?: return null
        if (factors.size < 2) return null
        return Factors(constant, factors)
    }

    class Factors(val constant: Rational, val polynomials: List<List<Rational>>)

    /** [p] as c·q with q of whole coefficients without common divisor and a positive leading one. */
    private fun primitive(p: List<Rational>): Pair<Rational, List<Rational>> {
        val denominators = p.fold(BigInteger.ONE) { l, c -> lcm(l, c.denominator.value) }
        val scaled = p.map { (it * BigInt(denominators)).numerator.value }
        var divisor = scaled.fold(BigInteger.ZERO) { g, c -> g.gcd(c) }
        if (scaled.last().signum() < 0) divisor = divisor.negate()
        val q = scaled.map { Rational.of(BigInt(it / divisor)) }
        return Rational.of(BigInt(divisor), BigInt(denominators)) to q
    }

    private fun lcm(a: BigInteger, b: BigInteger) = a.divide(a.gcd(b)).multiply(b)

    /** The factors of the primitive [q], each in turn split as far as it goes; null if [q] has none. */
    private fun splitWhole(q: List<Rational>, shouldStop: () -> Boolean): List<List<Rational>>? {
        val n = q.size - 1
        if (n < 2) return listOf(q)
        val roots = roots(q) ?: return null
        val lead = q[n].numerator.value
        if (lead.bitLength() > 40) return null
        val leadDivisors = (1L..minOf(lead.toLong(), MAX_LEAD)).filter { lead.toLong() % it == 0L }
        // The factor of the lowest degree first: it has none itself
        for (d in 1..n / 2) {
            var found: Pair<List<Rational>, List<Rational>>? = null
            combinations(n, d) { chosen ->
                if (shouldStop()) return@combinations true
                val monic = expand(chosen.map { roots[it] }) ?: return@combinations false
                for (l in leadDivisors) {
                    val candidate = monic.map { c ->
                        val scaled = c * l
                        if (abs(scaled) > 1e12) return@combinations false
                        val nearest = scaled.roundToLong()
                        if (abs(scaled - nearest) > 1e-6 * maxOf(1.0, abs(scaled))) null else Rational.of(nearest)
                    }
                    if (candidate.any { it == null }) continue
                    val factor = candidate.map { it!! }
                    val quotient = divide(q, factor) ?: continue
                    found = factor to quotient
                    return@combinations true
                }
                false
            }
            if (shouldStop()) return null
            val (factor, quotient) = found ?: continue
            val rest = splitWhole(quotient, shouldStop) ?: listOf(quotient)
            return (listOf(factor) + rest).sortedWith(compareBy({ it.size }, { it.joinToString { c -> c.toString() } }))
        }
        return listOf(q)
    }

    /** Calls [visit] with each choice of [k] of the numbers 0 until [n], in order, until it returns true. */
    private fun combinations(n: Int, k: Int, visit: (IntArray) -> Boolean) {
        val chosen = IntArray(k) { it }
        while (true) {
            if (visit(chosen)) return
            var i = k - 1
            while (i >= 0 && chosen[i] == n - k + i) i--
            if (i < 0) return
            chosen[i]++
            for (j in i + 1 until k) chosen[j] = chosen[j - 1] + 1
        }
    }

    /** (x − r₁)(x − r₂)… multiplied out, real coefficients from the lowest power up; null if they are not real. */
    private fun expand(chosen: List<Pair<Double, Double>>): List<Double>? {
        var re = doubleArrayOf(1.0)
        var im = doubleArrayOf(0.0)
        for ((rr, ri) in chosen) {
            val nextRe = DoubleArray(re.size + 1)
            val nextIm = DoubleArray(re.size + 1)
            for (j in re.indices) {
                // · x
                nextRe[j + 1] += re[j]
                nextIm[j + 1] += im[j]
                // · (−r)
                nextRe[j] -= re[j] * rr - im[j] * ri
                nextIm[j] -= re[j] * ri + im[j] * rr
            }
            re = nextRe
            im = nextIm
        }
        val size = re.maxOf { abs(it) }
        if (im.any { abs(it) > 1e-6 * maxOf(1.0, size) }) return null
        return re.toList()
    }

    /** [a] divided by [b] exactly; null if there is a remainder. */
    private fun divide(a: List<Rational>, b: List<Rational>): List<Rational>? {
        val rest = a.toMutableList()
        val db = b.size - 1
        val quotient = MutableList(a.size - db) { Rational.ZERO }
        for (k in a.size - 1 downTo db) {
            val c = rest[k] / b[db]
            quotient[k - db] = c
            for (j in 0..db) rest[k - db + j] = rest[k - db + j] - c * b[j]
        }
        return if (rest.all { it.isZero }) quotient else null
    }

    /** The complex roots of [p], in decimals (Durand–Kerner); null where they do not come out. */
    private fun roots(p: List<Rational>): List<Pair<Double, Double>>? {
        val n = p.size - 1
        val lead = p[n].toDouble()
        val c = DoubleArray(n + 1) { p[it].toDouble() / lead }
        if (c.any { !it.isFinite() }) return null
        val bound = 1.0 + (0 until n).maxOf { abs(c[it]) }
        val re = DoubleArray(n) { bound * cos(2 * Math.PI * it / n + 0.4) }
        val im = DoubleArray(n) { bound * sin(2 * Math.PI * it / n + 0.4) }
        // Repeated roots come out slowly; what is not quite settled is still near enough for the whole coefficients, which are checked
        repeat(ITERATIONS) {
            var change = 0.0
            for (k in 0 until n) {
                var pr = 1.0
                var pi = 0.0
                for (j in n - 1 downTo 0) {
                    val r = pr * re[k] - pi * im[k] + c[j]
                    pi = pr * im[k] + pi * re[k]
                    pr = r
                }
                var qr = 1.0
                var qi = 0.0
                for (j in 0 until n) {
                    if (j == k) continue
                    val dr = re[k] - re[j]
                    val di = im[k] - im[j]
                    val r = qr * dr - qi * di
                    qi = qr * di + qi * dr
                    qr = r
                }
                val q = qr * qr + qi * qi
                if (q == 0.0) return null
                val stepRe = (pr * qr + pi * qi) / q
                val stepIm = (pi * qr - pr * qi) / q
                re[k] -= stepRe
                im[k] -= stepIm
                change = maxOf(change, abs(stepRe) + abs(stepIm))
            }
            if (change < 1e-15 * bound) return (0 until n).map { re[it] to im[it] }
        }
        return if ((0 until n).all { re[it].isFinite() && im[it].isFinite() }) (0 until n).map { re[it] to im[it] } else null
    }

    private const val MAX_DEGREE = 12
    private const val MAX_LEAD = 1000L
    private const val ITERATIONS = 2000
}
