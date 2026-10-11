package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational

/**
 * A rational function P(x)/Q(x) split into a polynomial and fractions A/(x − r)ᵏ, for a
 * denominator Q that factors into linear factors with rational roots, a·(x − r₁)^m₁·(x − r₂)^m₂…
 * (x²−1 = (x − 1)(x + 1)); anything else, e.g. an irreducible x² + 1, has no decomposition here.
 * Polynomials are lists of coefficients, constant term first.
 */
internal object PartialFractions {

    /** A·(x − [root])^−[power] */
    class Term(val coefficient: Rational, val root: Rational, val power: Int)

    class Decomposition(
        /** The denominator's roots with their multiplicities, in increasing order. */
        val roots: List<Pair<Rational, Int>>,
        /** a in a·(x − r₁)^m₁… */
        val leading: Rational,
        /** The quotient of the division of the numerator by the denominator (empty if the degree is lower). */
        val quotient: List<Rational>,
        /** What is left of that division, of a lower degree than the denominator. */
        val remainder: List<Rational>,
        /** The fractions, by root and power. */
        val terms: List<Term>
    ) {
        /** quotient + Σ A/(x − r)ᵏ as an expression in [v]. */
        fun toExpression(v: Variable): Expression {
            val polynomialTerms = quotient.mapIndexedNotNull { k, c ->
                if (c.isZero) null else Multiplications.of(Value(c), if (k == 0) Value.ONE else if (k == 1) v else Power(v, Value(k.toLong())))
            }
            val fractionTerms = terms.map { term(it, v) }
            val all = polynomialTerms + fractionTerms
            return if (all.size == 1) all.single() else Additions(all)
        }

        /** A/(x − r)ᵏ */
        fun term(t: Term, v: Variable): Expression {
            val linear: Expression = if (t.root.isZero) v else Additions(v, Value(-t.root))
            return Multiplications.of(Value(t.coefficient), Power(linear, Value(-t.power.toLong())))
        }
    }

    private const val MAX_DEGREE = 8
    private const val MAX_ROOT_SEARCH = 100000L

    /** The coefficients of [e] as a polynomial in [v] with rational coefficients, or null. */
    fun polynomial(e: Expression, v: Variable, context: MathContext): List<Rational>? {
        val scratch = context.copy()
        val expanded = expandProducts(e).canonicalize(scratch)
        if (scratch.shouldStop) return null
        val coefficients = mutableMapOf<Int, Rational>()
        for (term in if (expanded is Additions) expanded.operands else listOf(expanded)) {
            var degree = 0
            var coefficient = Rational.ONE
            for (factor in if (term is Multiplications) term.operands else listOf(term)) {
                when {
                    factor is Value && factor.rational != null -> coefficient *= factor.rational
                    factor == v -> degree += 1
                    factor is Power && factor.base == v -> {
                        val n = (factor.exponent as? Value)?.rational?.takeIf { !it.isFractional && !it.isNegative } ?: return null
                        degree += n.toDouble().toInt()
                    }
                    else -> return null
                }
            }
            coefficients[degree] = (coefficients[degree] ?: Rational.ZERO) + coefficient
        }
        val degree = coefficients.filterValues { !it.isZero }.keys.maxOrNull() ?: return emptyList()
        if (degree > MAX_DEGREE * 2) return null
        return List(degree + 1) { coefficients[it] ?: Rational.ZERO }
    }

    /** [numerator]/[denominator] decomposed; null if the denominator does not split into linear factors. */
    fun decompose(numerator: List<Rational>, denominator: List<Rational>): Decomposition? {
        val d = denominator.size - 1
        if (d < 2 || d > MAX_DEGREE) return null
        val (quotient, remainder) = divide(numerator, denominator)

        // Q = a·Π(x − r)^m
        var rest = denominator
        val roots = mutableListOf<Pair<Rational, Int>>()
        while (rest.size - 1 > 0) {
            val root = rationalRoot(rest) ?: return null
            var multiplicity = 0
            while (rest.size - 1 > 0 && value(rest, root).isZero) {
                rest = divideByLinear(rest, root)
                multiplicity++
            }
            roots.add(root to multiplicity)
        }
        val leading = rest.single()
        roots.sortBy { it.first }

        // R/a = Σ A_ik · Π(x − r_j)^m_j / (x − r_i)^k, solved for the A_ik coefficient by coefficient
        val unknowns = roots.flatMap { (root, m) -> (1..m).map { root to it } }
        val columns = unknowns.map { (root, k) ->
            var product = listOf(Rational.ONE)
            for ((other, m) in roots) {
                val power = if (other == root) m - k else m
                repeat(power) { product = multiply(product, listOf(-other, Rational.ONE)) }
            }
            product
        }
        val matrix = List(d) { row -> columns.map { it.getOrElse(row) { Rational.ZERO } } }
        val right = List(d) { row -> remainder.getOrElse(row) { Rational.ZERO } / leading }
        val solved = solve(matrix, right) ?: return null
        val terms = unknowns.mapIndexed { i, (root, k) -> Term(solved[i], root, k) }.filter { !it.coefficient.isZero }
        return Decomposition(roots, leading, quotient, remainder, terms)
    }

    // ----- polynomials with rational coefficients -----

    /** The monic greatest common divisor of [a] and [b] (a constant 1 if they have no common factor). */
    fun gcd(a: List<Rational>, b: List<Rational>): List<Rational> {
        var x = trim(a)
        var y = trim(b)
        while (y.isNotEmpty()) {
            val remainder = divide(x, y).second
            x = y
            y = trim(remainder)
        }
        if (x.isEmpty()) return listOf(Rational.ONE)
        val lead = x.last()
        return x.map { it / lead }
    }

    /** c₀ + c₁x + c₂x² … as an expression in [v], written from the highest power down. */
    fun toExpression(c: List<Rational>, v: Variable): Expression {
        // Highest power first, as it is written: x² − 1
        val terms = c.indices.reversed().mapNotNull { k ->
            val coefficient = c[k]
            if (coefficient.isZero) null
            else if (k == 0) Value(coefficient)
            else Multiplications.of(Value(coefficient), if (k == 1) v else Power(v, Value(k.toLong())))
        }
        return if (terms.isEmpty()) Value.ZERO else if (terms.size == 1) terms.single() else Additions(terms)
    }

    internal fun trim(p: List<Rational>): List<Rational> = p.dropLastWhile { it.isZero }

    internal fun value(p: List<Rational>, x: Rational): Rational = p.foldRight(Rational.ZERO) { c, acc -> acc * x + c }

    internal fun multiply(a: List<Rational>, b: List<Rational>): List<Rational> {
        val result = MutableList(a.size + b.size - 1) { Rational.ZERO }
        for (i in a.indices) for (j in b.indices) result[i + j] = result[i + j] + a[i] * b[j]
        return result
    }

    /** p / (x − r), for a root r. */
    internal fun divideByLinear(p: List<Rational>, r: Rational): List<Rational> {
        val result = MutableList(p.size - 1) { Rational.ZERO }
        var carry = Rational.ZERO
        for (k in p.size - 1 downTo 1) {
            carry = p[k] + carry * r
            result[k - 1] = carry
        }
        return result
    }

    /** Polynomial long division: (quotient, remainder). */
    internal fun divide(numerator: List<Rational>, denominator: List<Rational>): Pair<List<Rational>, List<Rational>> {
        var remainder = trim(numerator).toMutableList()
        val d = denominator.size - 1
        if (remainder.size - 1 < d) return emptyList<Rational>() to remainder
        val quotient = MutableList(remainder.size - d) { Rational.ZERO }
        while (remainder.isNotEmpty() && remainder.size - 1 >= d) {
            val shift = remainder.size - 1 - d
            val factor = remainder.last() / denominator.last()
            quotient[shift] = factor
            for (i in denominator.indices) remainder[i + shift] = remainder[i + shift] - factor * denominator[i]
            remainder = trim(remainder).toMutableList()
        }
        return trim(quotient) to remainder
    }

    /** A rational root of [p] (by the rational root theorem on the polynomial with whole number coefficients). */
    internal fun rationalRoot(p: List<Rational>): Rational? {
        if (p.first().isZero) return Rational.ZERO
        val scale = p.fold(BigInt.ONE) { l, c -> l * c.denominator / l.gcd(c.denominator) }
        val whole = p.map { (it * scale).numerator.value }
        val constant = whole.first().abs()
        val leading = whole.last().abs()
        if (constant.bitLength() > 40 || leading.bitLength() > 40) return null
        val c = constant.toLong()
        val l = leading.toLong()
        if (c > MAX_ROOT_SEARCH || l > MAX_ROOT_SEARCH) return null
        fun divisors(n: Long) = (1..n).filter { n % it == 0L }
        val candidates = divisors(c).flatMap { a -> divisors(l).map { b -> Rational.of(a, b) } }
            .distinct().sortedBy { it.toDouble() }.flatMap { listOf(it, -it) }
        return candidates.firstOrNull { value(p, it).isZero }
    }

    /** Gaussian elimination over the rationals; null if the system has no unique solution. */
    private fun solve(matrix: List<List<Rational>>, right: List<Rational>): List<Rational>? {
        val n = matrix.size
        if (matrix.any { it.size != n }) return null
        val m = matrix.mapIndexed { i, row -> (row + right[i]).toMutableList() }.toMutableList()
        for (col in 0 until n) {
            val pivot = (col until n).firstOrNull { !m[it][col].isZero } ?: return null
            val swap = m[col]; m[col] = m[pivot]; m[pivot] = swap
            val scale = m[col][col]
            for (j in col..n) m[col][j] = m[col][j] / scale
            for (row in 0 until n) {
                if (row == col || m[row][col].isZero) continue
                val factor = m[row][col]
                for (j in col..n) m[row][j] = m[row][j] - factor * m[col][j]
            }
        }
        return List(n) { m[it][n] }
    }
}
