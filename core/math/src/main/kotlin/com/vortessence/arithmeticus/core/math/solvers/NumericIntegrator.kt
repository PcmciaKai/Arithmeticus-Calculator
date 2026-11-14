package com.vortessence.arithmeticus.core.math.solvers

import com.vortessence.arithmeticus.core.math.context.MathContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Numerical definite integration (for the graph and for ∫ in calculations), using adaptive
 * Simpson's rule.
 */
object NumericIntegrator {
    private const val PIECES = 64
    private const val MAX_DEPTH = 35
    private const val MAX_EVALUATIONS = 200_000
    private const val TAIL_START = 1e-8

    private class Diverged : RuntimeException()

    /** The nodes and weights of 10-point Gauss–Legendre on [−1, 1] (symmetric: the positive half). */
    private val GAUSS_NODES = doubleArrayOf(0.1488743389816312, 0.4333953941292472, 0.6794095682990244, 0.8650633666889845, 0.9739065285171717)
    private val GAUSS_WEIGHTS = doubleArrayOf(0.2955242247147529, 0.2692667193099963, 0.2190863625159820, 0.1494513491505806, 0.0666713443086881)
    private const val MIN_OSCILLATION_PIECES = 1024
    private const val MAX_OSCILLATION_PIECES = 1 shl 20

    /**
     * The integral of a [function] that is defined and bounded on [a, b] but swings up and down too often for the adaptive rule
     * (sin(x²) from 0 to 1000 has some 160 000 periods): 10-point Gauss–Legendre on equal pieces, twice as many each round, until
     * two rounds agree. NaN where they do not, or [context] asks to stop.
     */
    fun integrateOscillating(function: (Double) -> Double, a: Double, b: Double, context: MathContext? = null): Double {
        if (!a.isFinite() || !b.isFinite()) return Double.NaN
        if (a == b) return 0.0
        var pieces = MIN_OSCILLATION_PIECES
        var previous = Double.NaN
        var previousScale = Double.NaN
        while (pieces <= MAX_OSCILLATION_PIECES) {
            val width = (b - a) / pieces
            var sum = 0.0
            var compensation = 0.0
            var scale = 0.0
            for (i in 0 until pieces) {
                if (i % 4096 == 0 && context?.shouldStop == true) return Double.NaN
                val middle = a + (i + 0.5) * width
                val half = width / 2.0
                var piece = 0.0
                for (j in GAUSS_NODES.indices) {
                    val left = function(middle - half * GAUSS_NODES[j])
                    val right = function(middle + half * GAUSS_NODES[j])
                    if (!left.isFinite() || !right.isFinite()) return Double.NaN
                    piece += GAUSS_WEIGHTS[j] * (left + right)
                    scale = max(scale, max(abs(left), abs(right)))
                }
                piece *= half
                val s = sum + piece
                compensation += if (abs(sum) >= abs(piece)) (sum - s) + piece else (piece - s) + sum
                sum = s
            }
            val total = sum + compensation
            // Only for what is bounded: near a pole (1/x on [−1, 1]) the largest value grows with every round, and the halves that
            // cancel would make a divergent integral look like 0
            if (!previousScale.isNaN() && scale > 1.5 * previousScale) return Double.NaN
            if (!previous.isNaN() && abs(total - previous) <= 1e-11 * max(abs(total), scale * abs(b - a) * 1e-3)) return total
            previous = total
            previousScale = scale
            pieces *= 2
        }
        return Double.NaN
    }

    /**
     * Returns the signed integral of [function] from [a] to [b] (negative if b < a).
     * Returns NaN if the function is undefined somewhere in the range, if the integral
     * diverges (e.g. across a pole) or if [context] asks to stop.
     */
    fun integrate(
        function: Function1D,
        a: Double,
        b: Double,
        context: MathContext? = null,
        tolerance: Double = 1e-10
    ): Double = integrate(function::evaluateAt, a, b, context, tolerance)

    /**
     * Like [integrate], but [a] and [b] may also be ±∞ (an improper integral such as
     * ∫₀^∞ e^−x dx = 1). The infinite part is mapped onto a finite interval by x = 1/t.
     */
    fun integrateImproper(
        function: (Double) -> Double,
        a: Double,
        b: Double,
        context: MathContext? = null,
        tolerance: Double = 1e-10
    ): Double {
        if (a.isNaN() || b.isNaN()) return Double.NaN
        if (a == b) return 0.0
        if (b < a) return -integrateImproper(function, b, a, context, tolerance)
        return when {
            a.isFinite() && b.isFinite() -> integrate(function, a, b, context, tolerance)
            a.isInfinite() && b.isInfinite() ->
                integrateImproper(function, a, 0.0, context, tolerance) + integrateImproper(function, 0.0, b, context, tolerance)
            // ∫_−∞^b f(x) dx = ∫_−b^∞ f(−x) dx
            a.isInfinite() -> integrateImproper({ function(-it) }, -b, Double.POSITIVE_INFINITY, context, tolerance)
            else -> {
                // ∫_a^∞ = ∫_a^c f(x) dx + ∫_0^(1/c) f(1/t)/t² dt
                val c = maxOf(a, 0.0) + 1.0
                val tail: (Double) -> Double = { t ->
                    // The value at t = 0 (x = ∞) is the limit, approached closely enough
                    val s = if (t == 0.0) TAIL_START / c else t
                    function(1.0 / s) / (s * s)
                }
                val head = integrate(function, a, c, context, tolerance)
                val mapped = integrate(tail, 0.0, 1.0 / c, context, tolerance)
                // cos(x)/(1 + x²): f(1/t)/t² swings infinitely often at t = 0, where the adaptive rule cannot follow
                head + if (mapped.isNaN() && head.isFinite()) oscillatingTail(function, c, context) else mapped
            }
        }
    }

    private const val TAIL_STEP = 0.05
    private const val TAIL_REACH = 20_000.0
    private const val TAIL_WAVES = 24

    /**
     * ∫_c^∞ of a [function] that swings about 0 and dies off (cos(x)/(1 + x²)): the integrals between its sign changes, added up,
     * and the partial sums taken to their limit by Wynn's ε-algorithm. NaN where it does not swing, the swings do not die off
     * (∫₀^∞ sin x dx, where the sums go 2, 0, 2, 0, …), or the limit does not settle.
     */
    private fun oscillatingTail(function: (Double) -> Double, c: Double, context: MathContext?): Double {
        val zeros = mutableListOf(c)
        var x = c
        var y = function(x)
        while (zeros.size <= TAIL_WAVES && x < c + TAIL_REACH) {
            if (context?.shouldStop == true) return Double.NaN
            val next = x + TAIL_STEP
            val yNext = function(next)
            if (!y.isFinite() || !yNext.isFinite()) return Double.NaN
            if (y * yNext < 0) {
                var lo = x
                var hi = next
                repeat(60) {
                    val mid = (lo + hi) / 2
                    if (function(lo) * function(mid) <= 0) hi = mid else lo = mid
                }
                zeros.add((lo + hi) / 2)
            }
            x = next
            y = yNext
        }
        if (zeros.size <= TAIL_WAVES) return Double.NaN
        val pieces = (0 until TAIL_WAVES).map { integrate(function, zeros[it], zeros[it + 1], context) }
        if (pieces.any { !it.isFinite() }) return Double.NaN
        // The swings must die off, so that the sums settle
        if (abs(pieces.last()) > 0.3 * abs(pieces[1])) return Double.NaN
        val sums = pieces.runningReduce { s, p -> s + p }
        val full = wynn(sums)
        val fewer = wynn(sums.dropLast(4))
        if (!full.isFinite() || abs(full - fewer) > 1e-9 * max(1.0, abs(full))) return Double.NaN
        return full
    }

    /** The limit of the partial sums [s] by Wynn's ε-algorithm: the last entry of the deepest even column. */
    private fun wynn(s: List<Double>): Double {
        var previous = DoubleArray(s.size + 1)
        var current = s.toDoubleArray()
        var best = current.last()
        for (k in 1 until s.size) {
            val next = DoubleArray(current.size - 1)
            for (i in next.indices) {
                val d = current[i + 1] - current[i]
                if (d == 0.0) return if (k % 2 == 1) current[i + 1] else best
                next[i] = previous[i + 1] + 1.0 / d
            }
            previous = current
            current = next
            if (k % 2 == 0 && current.isNotEmpty()) best = current.last()
        }
        return best
    }

    /** Like the [Function1D] variant, for any function of one variable. */
    fun integrate(
        function: (Double) -> Double,
        a: Double,
        b: Double,
        context: MathContext? = null,
        tolerance: Double = 1e-10
    ): Double {
        if (!a.isFinite() || !b.isFinite()) return Double.NaN
        if (a == b) return 0.0
        if (b < a) return -integrate(function, b, a, context, tolerance)

        var evaluations = 0
        fun f(x: Double): Double {
            if (++evaluations > MAX_EVALUATIONS) throw Diverged()
            if (evaluations % 1000 == 0 && context?.shouldStop == true) throw Diverged()
            val y = function(x)
            if (!y.isFinite()) throw Diverged()
            return y
        }

        fun simpson(a: Double, b: Double, fa: Double, fm: Double, fb: Double) = (b - a) / 6.0 * (fa + 4.0 * fm + fb)

        var minEps = 0.0

        fun adaptive(a: Double, b: Double, fa: Double, fm: Double, fb: Double, whole: Double, eps: Double, depth: Int): Double {
            val m = (a + b) / 2.0
            val flm = f((a + m) / 2.0)
            val frm = f((m + b) / 2.0)
            val left = simpson(a, m, fa, flm, fm)
            val right = simpson(m, b, fm, frm, fb)
            val delta = left + right - whole
            // Also accept once the difference is down to rounding noise
            if (abs(delta) <= 15.0 * eps || abs(delta) <= 1e-13 * (abs(left) + abs(right))) return left + right + delta / 15.0
            // Still not converged on a tiny interval: the integrand is unbounded here
            if (depth <= 0) {
                if (abs(delta) > 1e-6 * max(1.0, abs(left + right))) throw Diverged()
                return left + right + delta / 15.0
            }
            val halfEps = max(eps / 2.0, minEps)
            return adaptive(a, m, fa, flm, fm, left, halfEps, depth - 1) +
                adaptive(m, b, fm, frm, fb, right, halfEps, depth - 1)
        }

        return try {
            // Start from several pieces so narrow features are not missed by a single coarse estimate
            val width = (b - a) / PIECES
            val xs = DoubleArray(PIECES + 1) { if (it == PIECES) b else a + it * width }
            val ys = DoubleArray(PIECES + 1) { f(xs[it]) }
            val mids = DoubleArray(PIECES) { f((xs[it] + xs[it + 1]) / 2.0) }
            val wholes = DoubleArray(PIECES) { simpson(xs[it], xs[it + 1], ys[it], mids[it], ys[it + 1]) }
            // Relative to ∫|f|, not to the result: ∫₀¹⁰⁰⁰ sin x is 0.44 out of pieces of size 2, and asking for 10⁻¹⁰ of 0.44 in each
            // of them ran out of evaluations (and was taken for a divergent integral)
            val eps = tolerance * max(1.0, wholes.sumOf { abs(it) }) / PIECES
            minEps = eps * 1e-4
            var sum = 0.0
            for (i in 0 until PIECES) {
                sum += adaptive(xs[i], xs[i + 1], ys[i], mids[i], ys[i + 1], wholes[i], eps, MAX_DEPTH)
            }
            sum
        } catch (e: Diverged) {
            Double.NaN
        }
    }
}
