package com.vortessence.calculator.core.math.numbers

import java.math.BigInteger

/** Prime factorization of whole numbers: trial division for the small factors, then Pollard's rho for the rest. */
internal object Factoring {

    /** Numbers with more bits are not tried (about 300 digits). */
    const val MAX_BITS = 1024

    private const val TRIAL_LIMIT = 10_000
    /** How many modular multiplications pass between two looks at [stop]. */
    private const val CHECK_EVERY = 512

    private class Stopped : RuntimeException(null, null, false, false)
    private val SMALL_PRIMES: List<Int> = (2..TRIAL_LIMIT).filter { n -> (2..Math.sqrt(n.toDouble()).toInt()).none { n % it == 0 } }

    /**
     * The prime factors of [n] ≥ 2 as (prime, exponent) pairs, ascending; null if [stop] says so before it is done
     * (a number with only large prime factors takes long) or [n] has more than [MAX_BITS] bits.
     */
    fun factor(n: BigInteger, stop: () -> Boolean): List<Pair<BigInteger, Int>>? {
        if (n < BigInteger.TWO || n.bitLength() > MAX_BITS) return null
        val exponents = sortedMapOf<BigInteger, Int>()
        var rest = n
        for (p in SMALL_PRIMES) {
            val prime = BigInteger.valueOf(p.toLong())
            if (prime.multiply(prime) > rest) break
            while (rest.mod(prime).signum() == 0) {
                rest = rest.divide(prime)
                exponents.merge(prime, 1, Int::plus)
            }
        }
        try {
            split(rest, exponents, stop)
        } catch (e: Stopped) {
            return null
        }
        return exponents.map { it.key to it.value }
    }

    /** Adds the prime factors of [n] to [exponents]; throws [Stopped] if [stop] says so. */
    private fun split(n: BigInteger, exponents: MutableMap<BigInteger, Int>, stop: () -> Boolean) {
        if (n == BigInteger.ONE) return
        if (n.isProbablePrime(40)) {
            exponents.merge(n, 1, Int::plus)
            return
        }
        val divisor = rho(n, stop)
        split(divisor, exponents, stop)
        split(n.divide(divisor), exponents, stop)
    }

    /** A proper divisor of the composite [n] (Brent's variant of Pollard's rho); throws [Stopped] if [stop] says so. */
    private fun rho(n: BigInteger, stop: () -> Boolean): BigInteger {
        // A perfect square has the root as a divisor, which rho can miss
        val root = n.sqrt()
        if (root.multiply(root) == n) return root
        var ticks = 0
        var c = BigInteger.ONE
        while (true) {
            fun next(v: BigInteger): BigInteger {
                if (++ticks % CHECK_EVERY == 0 && stop()) throw Stopped()
                return v.multiply(v).add(c).mod(n)
            }
            var y = BigInteger.TWO
            var x = y
            var q = BigInteger.ONE
            var g = BigInteger.ONE
            var r = 1
            while (g == BigInteger.ONE) {
                x = y
                repeat(r) { y = next(y) }
                var k = 0
                while (k < r && g == BigInteger.ONE) {
                    var ys = y
                    val block = minOf(128, r - k)
                    repeat(block) {
                        y = next(y)
                        q = q.multiply(x.subtract(y).abs()).mod(n)
                    }
                    g = q.gcd(n)
                    if (g == n) {
                        // Overshot: go through the block one step at a time
                        g = BigInteger.ONE
                        repeat(block) {
                            if (g == BigInteger.ONE) {
                                ys = next(ys)
                                g = x.subtract(ys).abs().gcd(n)
                            }
                        }
                        if (g == BigInteger.ONE) g = n
                        break
                    }
                    k += block
                }
                r *= 2
            }
            if (g != n && g != BigInteger.ONE) return g
            // No luck with this polynomial: try the next
            c = c.add(BigInteger.ONE)
        }
    }
}
