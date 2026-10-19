package com.vortessence.arithmeticus.core.math.solvers

import com.vortessence.arithmeticus.core.math.context.MathContext
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
        // The sample before the last one, to find where |f| has a minimum of 0 without a sign change
        var xBefore = Double.NaN
        var yBefore = Double.NaN

        fun addRoot(root: Double) {
            if (roots.none { abs(it - root) < dedupeDistance }) roots.add(root)
        }

        for (i in 1..steps) {
            if (i % 100 == 0 && context?.shouldStop == true) break
            val x1 = minX + i * stepSize
            val y1 = function.evaluateAt(x1)

            // A root where f only touches 0 (sin²x + sin x − 2 at π/2): |f| has a minimum there
            if (yBefore.isFinite() && y0.isFinite() && y1.isFinite() && yBefore * y0 > 0.0 && y0 * y1 > 0.0 &&
                abs(y0) < abs(yBefore) && abs(y0) <= abs(y1)
            ) {
                val touch = minimizeAbs(function, xBefore, x1)?.let { refineByDerivative(function, xBefore, x1, it) }
                val scale = max(1.0, max(abs(yBefore), abs(y1)))
                // At a touching root the slope is 0: the values on both sides are alike (not so for rounding noise)
                if (touch != null && abs(function.evaluateAt(touch)) <= 1e-12 * scale && isSmoothAt(function, touch, 0.0, yBefore, y1)) addRoot(touch)
            }
            val yPrevious = yBefore
            xBefore = x0
            yBefore = y0

            if (y0.isFinite() && y1.isFinite()) {
                if (abs(y0) < 1e-12 && isZeroAmongNeighbors(y0, yPrevious, y1) &&
                    isSmoothAt(function, x0, if (yPrevious.isFinite()) (y1 - yPrevious) / (2.0 * stepSize) else (y1 - y0) / stepSize, y1, yPrevious)
                ) {
                    addRoot(x0)
                } else if (y0 * y1 < 0.0) {
                    val root = bisection(function, x0, x1)
                    // A true root makes |f| small; at a pole |f| grows instead of shrinking
                    if (root != null && abs(function.evaluateAt(root)) <= 1e-6 * max(1.0, min(abs(y0), abs(y1))) &&
                        crossesOnce(function, x0, x1, y0, y1) && isSmoothAt(function, root, (y1 - y0) / (x1 - x0), y0, y1)
                    ) {
                        addRoot(root)
                    }
                }
                if (i == steps && abs(y1) < 1e-12 && isZeroAmongNeighbors(y1, y0, Double.NaN) &&
                    isSmoothAt(function, x1, (y1 - y0) / stepSize, y1, y0)) addRoot(x1)
            }

            x0 = x1
            y0 = y1
        }
        return roots
    }

    /**
     * Whether a sample [y] that is nearly 0 is a zero of the function and not just a value on the way to 0, or a stretch of 0
     * (e^(−x), sech x and cosh x − sinh x are below 1e-12 from some x on, but never reach it): next to a zero the function is
     * clearly different from 0 on both sides, and far bigger than at the zero. A neighbor of 1e-9 or less is hardly different
     * from 0 itself.
     */
    private fun isZeroAmongNeighbors(y: Double, before: Double, after: Double): Boolean {
        val neighbors = listOf(before, after).filter { it.isFinite() }.map { abs(it) }
        if (neighbors.isEmpty()) return true
        return neighbors.all { it >= 1e-9 } && (y == 0.0 || abs(y) <= 1e-3 * neighbors.min())
    }

    /**
     * Whether f near the root [x] changes the way the slope [bracketSlope] across the bracket says. Next to a root the values of
     * a smooth function agree with it; rounding noise (cosh x − sinh x is 1e-8 noise for x = 19) differs between points that
     * are close together.
     */
    private fun isSmoothAt(function: Function1D, x: Double, bracketSlope: Double, ya: Double, yb: Double): Boolean {
        val delta = 1e-6 * max(1.0, abs(x))
        val left = function.evaluateAt(x - delta)
        val right = function.evaluateAt(x + delta)
        if (!left.isFinite() || !right.isFinite() || !bracketSlope.isFinite()) return true
        return abs(right - left) <= 10.0 * abs(2.0 * delta * bracketSlope) + 1e-12 * max(1.0, max(abs(ya), abs(yb)))
    }

    /**
     * Whether f changes sign exactly once between [a] and [b] (f(a) and f(b) have different signs): rounding noise, as in
     * cosh x − sinh x for large x, changes sign back and forth instead.
     */
    private fun crossesOnce(function: Function1D, a: Double, b: Double, ya: Double, yb: Double): Boolean {
        var changes = 0
        var previous = ya
        for (i in 1..8) {
            val y = function.evaluateAt(a + (b - a) * i / 9.0)
            if (!y.isFinite()) return true
            if (y != 0.0 && previous != 0.0 && y * previous < 0.0) changes++
            if (y != 0.0) previous = y
        }
        if (yb != 0.0 && previous != 0.0 && yb * previous < 0.0) changes++
        return changes <= 1
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

    /**
     * Where the derivative changes sign in [a, b], which is much sharper than where |f| is smallest
     * (|f| is flat there, and rounding blurs it to about 1e-8); [approximate] if it does not.
     */
    private fun refineByDerivative(function: Function1D, aIn: Double, bIn: Double, approximate: Double): Double {
        var a = aIn
        var b = bIn
        var da = function.derivativeAt(a)
        val db = function.derivativeAt(b)
        if (!da.isFinite() || !db.isFinite() || da * db >= 0.0) return approximate
        repeat(80) {
            val mid = (a + b) / 2.0
            val dMid = function.derivativeAt(mid)
            if (!dMid.isFinite()) return approximate
            if (da * dMid < 0.0) b = mid else { a = mid; da = dMid }
        }
        val refined = (a + b) / 2.0
        return if (abs(function.evaluateAt(refined)) <= abs(function.evaluateAt(approximate)) * 4 + 1e-15) refined else approximate
    }

    /** The x in [a, b] where |f| is smallest (ternary search; |f| has one minimum in the interval), or null. */
    private fun minimizeAbs(function: Function1D, aIn: Double, bIn: Double): Double? {
        var a = aIn
        var b = bIn
        repeat(100) {
            val m1 = a + (b - a) / 3.0
            val m2 = b - (b - a) / 3.0
            val f1 = abs(function.evaluateAt(m1))
            val f2 = abs(function.evaluateAt(m2))
            if (!f1.isFinite() || !f2.isFinite()) return null
            if (f1 < f2) b = m2 else a = m1
        }
        return (a + b) / 2.0
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
