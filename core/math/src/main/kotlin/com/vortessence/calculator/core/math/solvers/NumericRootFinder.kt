package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.context.MathContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Numerical root and extrema finder for 2D graphing and equation solving.
 */
object NumericRootFinder {

    /**
     * Finds roots of f(x) = 0 in [minX, maxX] by sampling and bisection.
     * Sign changes caused by poles (e.g. 1/x at 0) are rejected.
     */
    fun findRoots(
        function: Function1D,
        minX: Double,
        maxX: Double,
        steps: Int = 200,
        context: MathContext? = null
    ): List<Double> {
        val roots = mutableListOf<Double>()
        val stepSize = (maxX - minX) / steps
        val dedupeDistance = max(1e-4, stepSize / 10.0)
        var x0 = minX
        var y0 = function.evaluateAt(x0)

        fun addRoot(root: Double) {
            if (roots.none { abs(it - root) < dedupeDistance }) roots.add(root)
        }

        for (i in 1..steps) {
            if (i % 100 == 0 && context?.shouldStop == true) break
            val x1 = minX + i * stepSize
            val y1 = function.evaluateAt(x1)

            if (y0.isFinite() && y1.isFinite()) {
                if (abs(y0) < 1e-12) {
                    addRoot(x0)
                } else if (y0 * y1 < 0.0) {
                    val root = bisection(function, x0, x1)
                    // A true root makes |f| small; at a pole |f| grows instead of shrinking
                    if (root != null && abs(function.evaluateAt(root)) <= 1e-6 * max(1.0, min(abs(y0), abs(y1)))) {
                        addRoot(root)
                    }
                }
                if (i == steps && abs(y1) < 1e-12) addRoot(x1)
            }

            x0 = x1
            y0 = y1
        }
        return roots
    }

    /**
     * Finds local extrema (minima and maxima) by finding roots of the numerical derivative.
     * Candidates at poles or inflection points (e.g. x^3 at 0) are rejected by checking that
     * the function value really is a local minimum or maximum.
     */
    fun findExtrema(
        function: Function1D,
        minX: Double,
        maxX: Double,
        steps: Int = 200,
        context: MathContext? = null
    ): List<Double> {
        val extrema = mutableListOf<Double>()
        val stepSize = (maxX - minX) / steps
        val probe = stepSize / 4.0

        fun addIfExtremum(x: Double) {
            if (extrema.any { abs(it - x) < 1e-4 }) return
            val y = function.evaluateAt(x)
            val left = function.evaluateAt(x - probe)
            val right = function.evaluateAt(x + probe)
            if (!y.isFinite() || !left.isFinite() || !right.isFinite()) return
            val isMax = y >= left && y >= right
            val isMin = y <= left && y <= right
            if (isMax != isMin) extrema.add(x) // both true only for a flat line
        }

        var x0 = minX
        var d0 = function.derivativeAt(x0)

        for (i in 1..steps) {
            if (i % 100 == 0 && context?.shouldStop == true) break
            val x1 = minX + i * stepSize
            val d1 = function.derivativeAt(x1)

            if (d0.isFinite() && d1.isFinite()) {
                if (abs(d0) < 1e-9) {
                    addIfExtremum(x0)
                } else if (d0 * d1 < 0.0) {
                    var a = x0
                    var b = x1
                    var da = d0
                    for (iter in 0..60) {
                        val mid = (a + b) / 2.0
                        val dMid = function.derivativeAt(mid)
                        if (abs(dMid) < 1e-10 || (b - a) < 1e-9) {
                            addIfExtremum(mid)
                            break
                        }
                        if (da * dMid < 0.0) {
                            b = mid
                        } else {
                            a = mid
                            da = dMid
                        }
                    }
                }
            }
            x0 = x1
            d0 = d1
        }
        return extrema
    }

    /**
     * Finds inflection points, where the second derivative changes sign. Samples where f'' is
     * within rounding noise of zero are skipped, so straight segments yield no points, and
     * sign changes at poles (e.g. 1/x at 0) are rejected because |f''| grows there.
     */
    fun findInflectionPoints(
        function: Function1D,
        minX: Double,
        maxX: Double,
        steps: Int = 200,
        context: MathContext? = null
    ): List<Double> {
        val points = mutableListOf<Double>()
        val stepSize = (maxX - minX) / steps

        // Last sample whose second derivative was clearly non-zero
        var lastX = Double.NaN
        var lastD = Double.NaN

        for (i in 0..steps) {
            if (i % 100 == 0 && context?.shouldStop == true) break
            val x = minX + i * stepSize
            val y = function.evaluateAt(x)
            val d = function.secondDerivativeAt(x)
            if (!y.isFinite() || !d.isFinite()) {
                lastX = Double.NaN
                lastD = Double.NaN
                continue
            }
            if (abs(d) < 1e-5 * max(1.0, abs(y))) continue

            if (!lastD.isNaN() && lastD * d < 0.0) {
                var a = lastX
                var b = x
                var da = lastD
                for (iter in 0..60) {
                    if ((b - a) < 1e-10 * max(1.0, abs(a))) break
                    val mid = (a + b) / 2.0
                    val dMid = function.secondDerivativeAt(mid)
                    if (!dMid.isFinite()) break
                    if (dMid == 0.0) {
                        a = mid
                        b = mid
                        break
                    }
                    if (da * dMid < 0.0) {
                        b = mid
                    } else {
                        a = mid
                        da = dMid
                    }
                }
                val candidate = (a + b) / 2.0
                val dCandidate = function.secondDerivativeAt(candidate)
                if (function.evaluateAt(candidate).isFinite() && abs(dCandidate) <= max(abs(lastD), abs(d)) &&
                    points.none { abs(it - candidate) < 1e-4 }
                ) {
                    points.add(candidate)
                }
            }
            lastX = x
            lastD = d
        }
        return points
    }

    private fun bisection(function: Function1D, aIn: Double, bIn: Double, maxIter: Int = 60): Double? {
        var a = aIn
        var b = bIn
        var fa = function.evaluateAt(a)
        val fb = function.evaluateAt(b)

        if (fa * fb > 0.0) return null

        for (iter in 0 until maxIter) {
            val mid = (a + b) / 2.0
            val fmid = function.evaluateAt(mid)

            if (fmid == 0.0 || (b - a) / 2.0 < 1e-12 * max(1.0, abs(mid))) {
                return mid
            }

            if (fa * fmid < 0.0) {
                b = mid
            } else {
                a = mid
                fa = fmid
            }
        }
        return (a + b) / 2.0
    }
}
