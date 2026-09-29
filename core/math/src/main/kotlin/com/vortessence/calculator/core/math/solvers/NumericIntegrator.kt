package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.context.MathContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Numerical definite integration for the graph, using adaptive Simpson's rule.
 */
object NumericIntegrator {
    private const val PIECES = 64
    private const val MAX_DEPTH = 35
    private const val MAX_EVALUATIONS = 200_000

    private class Diverged : RuntimeException()

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
    ): Double {
        if (!a.isFinite() || !b.isFinite()) return Double.NaN
        if (a == b) return 0.0
        if (b < a) return -integrate(function, b, a, context, tolerance)

        var evaluations = 0
        fun f(x: Double): Double {
            if (++evaluations > MAX_EVALUATIONS) throw Diverged()
            if (evaluations % 1000 == 0 && context?.shouldStop == true) throw Diverged()
            val y = function.evaluateAt(x)
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
            val eps = tolerance * max(1.0, abs(wholes.sum())) / PIECES
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
