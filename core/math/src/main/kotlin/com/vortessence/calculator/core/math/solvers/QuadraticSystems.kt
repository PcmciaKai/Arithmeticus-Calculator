package com.vortessence.calculator.core.math.solvers

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.expandProducts
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import kotlin.math.abs
import kotlin.math.max

/**
 * a·x² + b·xy + c·y² + d·x + e·y + f = 0 with numbers as coefficients: what an equation in x and y is, if it is of the second degree
 * and written with numbers only.
 */
internal class Quad(val a: Rational, val b: Rational, val c: Rational, val d: Rational, val e: Rational, val f: Rational) {
    /** Only x², y² and a constant. */
    val onlySquares: Boolean get() = b.isZero && d.isZero && e.isZero
    /** Only the terms of degree 2 and a constant: ax² + bxy + cy² = D. */
    val homogeneous: Boolean get() = d.isZero && e.isZero

    companion object {
        fun of(equation: Equals, x: Variable, y: Variable, context: MathContext): Quad? {
            val scratch = context.copy()
            val difference = expandProducts(Additions(equation.left, equation.right.negate()).canonicalize(scratch)).canonicalize(scratch)
            if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
            val sums = mutableMapOf<Pair<Int, Int>, Rational>()
            for (term in if (difference is Additions) difference.operands else listOf(difference)) {
                var coefficient = Rational.ONE
                var i = 0
                var j = 0
                for (factor in if (term is Multiplications) term.operands else listOf(term)) {
                    when {
                        factor is Value -> coefficient *= factor.rational ?: return null
                        factor == x -> i++
                        factor == y -> j++
                        factor is Power && (factor.base == x || factor.base == y) -> {
                            val n = (factor.exponent as? Value)?.rational?.takeIf { !it.isFractional && !it.isNegative } ?: return null
                            if (n.numerator.value.bitLength() > 3) return null
                            if (factor.base == x) i += n.numerator.value.toInt() else j += n.numerator.value.toInt()
                        }
                        else -> return null
                    }
                }
                if (i + j > 2) return null
                sums[i to j] = (sums[i to j] ?: Rational.ZERO) + coefficient
            }
            fun at(i: Int, j: Int) = sums[i to j] ?: Rational.ZERO
            return Quad(at(2, 0), at(1, 1), at(0, 2), at(1, 0), at(0, 1), at(0, 0))
        }
    }
}

/**
 * Systems of two equations in x and y that are of the second degree and have a simple structure, solved exactly:
 * - only x², y² and numbers: linear in u = x² and v = y² (x² + y² = 25 and x² − y² = 7),
 * - only the terms of degree 2 and numbers (x² + xy = 10, y² + xy = 15): the constants are eliminated, which leaves
 *   P·x² + Q·xy + R·y² = 0, an equation of t = y/x; each t then gives x² and y = t·x.
 */
internal object QuadraticSystems {

    /** u = x², v = y² from a₁u + c₁v = D₁ and a₂u + c₂v = D₂; null if the determinant is 0. */
    class Squares(val u: Rational, val v: Rational)

    fun squares(q1: Quad, q2: Quad): Squares? {
        if (!q1.onlySquares || !q2.onlySquares) return null
        val determinant = q1.a * q2.c - q2.a * q1.c
        if (determinant.isZero) return null
        val d1 = -q1.f
        val d2 = -q2.f
        return Squares((d1 * q2.c - d2 * q1.c) / determinant, (q1.a * d2 - q2.a * d1) / determinant)
    }

    /** The equation P + Q·t + R·t² = 0 for t = y/x, and the t that are rational solutions. */
    class Ratio(val p: Rational, val q: Rational, val r: Rational, val ts: List<Rational>) {
        /** The equation in which the constants cancel: P·x² + Q·xy + R·y² = 0. */
        val coefficients: List<Rational> get() = listOf(p, q, r)
    }

    fun ratio(q1: Quad, q2: Quad): Ratio? {
        if (!q1.homogeneous || !q2.homogeneous) return null
        val d1 = -q1.f
        val d2 = -q2.f
        if (d1.isZero || d2.isZero) return null
        // d₂·(first) − d₁·(second): the constants cancel
        val p = q1.a * d2 - q2.a * d1
        val q = q1.b * d2 - q2.b * d1
        val r = q1.c * d2 - q2.c * d1
        if (p.isZero && q.isZero && r.isZero) return null
        val ts = mutableListOf<Rational>()
        when {
            r.isZero && q.isZero -> {}
            r.isZero -> ts.add(-p / q)
            else -> {
                val discriminant = q * q - Rational.of(4L) * r * p
                if (discriminant.isNegative) return Ratio(p, q, r, emptyList())
                val root = exactRoot(discriminant) ?: return null
                ts.add((-q + root) / (Rational.of(2L) * r))
                if (!root.isZero) ts.add((-q - root) / (Rational.of(2L) * r))
            }
        }
        return Ratio(p, q, r, ts.sortedBy { it.toDouble() })
    }

    /** √[r] if it is a rational number (the numerator and the denominator are squares). */
    fun exactRoot(r: Rational): Rational? {
        if (r.isNegative) return null
        fun root(n: BigInt): BigInt? {
            val s = n.value.sqrt()
            return if (s.multiply(s) == n.value) BigInt(s) else null
        }
        val top = root(r.numerator) ?: return null
        val bottom = root(r.denominator) ?: return null
        return Rational.of(top, bottom)
    }

    /** x = ±√value as expressions, one if the value is 0; empty if it is negative. */
    fun roots(value: Rational, context: MathContext): List<Expression> {
        if (value.isNegative) return emptyList()
        if (value.isZero) return listOf(Value.ZERO)
        val root = Power.squareRoot(Value(value)).canonicalize(context.copy())
        return listOf(root.negate().canonicalize(context.copy()), root)
    }

    /** The solutions as pairs (x, y), checked in both equations. */
    fun solve(equations: List<Equals>, variables: List<Variable>, context: MathContext): List<Map<Variable, Expression>>? {
        if (equations.size != 2 || variables.size != 2) return null
        val (vx, vy) = variables
        val q1 = Quad.of(equations[0], vx, vy, context) ?: return null
        val q2 = Quad.of(equations[1], vx, vy, context) ?: return null
        val pairs = mutableListOf<Pair<Expression, Expression>>()
        val squares = squares(q1, q2)
        if (squares != null) {
            for (x in roots(squares.u, context)) for (y in roots(squares.v, context)) pairs.add(x to y)
        } else {
            val ratio = ratio(q1, q2) ?: return null
            // The ratio y/x: x² from the first equation with a constant, y = t·x
            val q = if (!q1.f.isZero) q1 else q2
            for (t in ratio.ts) {
                val s = q.a + q.b * t + q.c * t * t
                if (s.isZero) continue
                for (x in roots(-q.f / s, context)) pairs.add(x to Multiplications.of(Value(t), x).canonicalize(context.copy()))
            }
            // x = 0, which the ratio leaves out: c·y² = D in both
            if (!q1.c.isZero && !(-q1.f / q1.c).isNegative) {
                for (y in roots(-q1.f / q1.c, context)) pairs.add(Value.ZERO to y)
            }
        }
        val solutions = pairs.map { (x, y) -> mapOf(vx to x, vy to y) }.filter { holds(equations, it) }
        val ordered = solutions.sortedWith(compareBy({ it.getValue(vx).evaluateNumerically() }, { it.getValue(vy).evaluateNumerically() }))
        return ordered.filterIndexed { i, s -> ordered.indices.none { j -> j < i && same(ordered[j], s, variables) } }
    }

    private fun holds(equations: List<Equals>, values: Map<Variable, Expression>): Boolean {
        val bindings = values.mapValues { it.value.evaluateNumerically() }
        return equations.all { equation ->
            val l = equation.left.evaluateNumerically(bindings)
            val r = equation.right.evaluateNumerically(bindings)
            l.isFinite() && r.isFinite() && abs(l - r) <= 1e-7 * max(1.0, max(abs(l), abs(r)))
        }
    }

    private fun same(a: Map<Variable, Expression>, b: Map<Variable, Expression>, variables: List<Variable>) =
        variables.all { v -> abs(a.getValue(v).evaluateNumerically() - b.getValue(v).evaluateNumerically()) <= 1e-9 }
}
