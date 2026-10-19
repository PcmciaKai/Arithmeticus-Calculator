package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.numbers.BigInt
import com.vortessence.arithmeticus.core.math.numbers.Rational

/**
 * Logarithms of numbers to one base, written with prime numbers: ln(12) = 2·ln 2 + ln 3. Sums and quotients of them then
 * combine where that is simpler: ln 6 − ln 2 = ln 3, log 25 + log 4 = log 100 = 2, ln 8/ln 2 = 3.
 */
internal object LogarithmRules {

    /** c·log_b(a) for numbers c and a. */
    class Term(val coefficient: Rational, val base: Expression, val argument: Rational)

    fun term(e: Expression): Term? {
        val (coefficient, log) = when {
            e is Logarithm -> Rational.ONE to e
            e is Multiplications && e.operands.size == 2 && (e.operands[0] as? Value)?.rational != null && e.operands[1] is Logarithm ->
                (e.operands[0] as Value).rational!! to (e.operands[1] as Logarithm)
            else -> return null
        }
        val argument = (log.operand as? Value)?.rational ?: return null
        if (argument.signum <= 0 || !isPositiveBase(log.base)) return null
        return Term(coefficient, log.base, argument)
    }

    private fun isPositiveBase(base: Expression): Boolean {
        if (base == Constant.E) return true
        val value = (base as? Value)?.rational ?: return false
        return value.signum > 0 && value != Rational.ONE
    }

    /** The exponents of the primes in [a] (negative in the denominator); null if it has factors too big to find. */
    fun primeVector(a: Rational): Map<Long, Rational>? {
        val result = sortedMapOf<Long, Rational>()
        fun factor(n: BigInt, sign: Int): Boolean {
            if (n.value.bitLength() > MAX_BITS) return false
            var m = n.value.toLong()
            var p = 2L
            while (p * p <= m) {
                while (m % p == 0L) {
                    result[p] = (result[p] ?: Rational.ZERO) + Rational.of(sign.toLong())
                    m /= p
                }
                p += if (p == 2L) 1 else 2
            }
            if (m > 1) result[m] = (result[m] ?: Rational.ZERO) + Rational.of(sign.toLong())
            return true
        }
        if (!factor(a.numerator, 1) || !factor(a.denominator, -1)) return null
        return result.filterValues { !it.isZero }
    }

    /**
     * The sum [terms] with the logarithms of numbers to one base combined where that gives one term or a number: ln 6 − ln 2 = ln 3,
     * log 25 + log 4 = 2. Null if nothing is combined.
     */
    fun combine(terms: List<Expression>, context: MathContext): List<Expression>? {
        val parsed = terms.map { term(it) }
        for (base in parsed.filterNotNull().map { it.base }.distinct()) {
            val indices = terms.indices.filter { parsed[it]?.base == base }
            if (indices.size < 2) continue
            val total = sortedMapOf<Long, Rational>()
            for (i in indices) {
                val t = parsed[i]!!
                val vector = primeVector(t.argument) ?: return null
                for ((p, e) in vector) total[p] = (total[p] ?: Rational.ZERO) + e * t.coefficient
            }
            val sum = total.filterValues { !it.isZero }
            val replacement: Expression = when {
                sum.isEmpty() -> Value.ZERO
                sum.size == 1 -> Multiplications.of(Value(sum.values.single()), Logarithm(base, Value(sum.keys.single().toLong()))).canonicalize(context)
                sum.values.all { !it.isFractional } -> {
                    var product = Rational.ONE
                    for ((p, e) in sum) product *= Rational.of(p).pow(e.numerator.value.toLong())
                    Logarithm(base, Value(product)).canonicalize(context)
                }
                else -> continue
            }
            if (context.shouldStop) return null
            val result = terms.toMutableList()
            result[indices.first()] = replacement
            for (i in indices.drop(1).reversed()) result.removeAt(i)
            return result
        }
        return null
    }

    /** ln 8/ln 2 = 3: a logarithm over one of the same base whose argument is a power of the other's. Null if there is none. */
    fun quotient(factors: List<Expression>): List<Expression>? {
        for (i in factors.indices) {
            val top = term(factors[i])?.takeIf { it.coefficient == Rational.ONE } ?: continue
            for (j in factors.indices) {
                if (i == j) continue
                val power = factors[j] as? Power ?: continue
                if (power.exponent != Value.MINUS_ONE) continue
                val bottom = term(power.base)?.takeIf { it.coefficient == Rational.ONE && it.base == top.base } ?: continue
                val ratio = proportion(primeVector(top.argument) ?: continue, primeVector(bottom.argument) ?: continue) ?: continue
                val result = factors.toMutableList()
                result[i] = Value(ratio)
                result.removeAt(j)
                return result
            }
        }
        return null
    }

    /** r with a = r·b for the exponent vectors a and b (both not empty); null if there is none. */
    private fun proportion(a: Map<Long, Rational>, b: Map<Long, Rational>): Rational? {
        if (a.isEmpty() || b.isEmpty() || a.keys != b.keys) return null
        val first = b.keys.first()
        val ratio = a.getValue(first) / b.getValue(first)
        return ratio.takeIf { b.keys.all { p -> a.getValue(p) == ratio * b.getValue(p) } }
    }

    private const val MAX_BITS = 40
}
