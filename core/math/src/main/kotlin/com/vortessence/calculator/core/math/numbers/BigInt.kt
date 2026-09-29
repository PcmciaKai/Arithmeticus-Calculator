package com.vortessence.calculator.core.math.numbers

import java.math.BigInteger

/**
 * Arbitrary precision integer wrapping [BigInteger] with mathematical utilities
 * ported and modernized from legacy MEInteger.
 */
class BigInt(val value: BigInteger) : Comparable<BigInt> {

    constructor(longVal: Long) : this(BigInteger.valueOf(longVal))
    constructor(str: String) : this(BigInteger(str))

    val isZero: Boolean get() = value == BigInteger.ZERO
    val isOne: Boolean get() = value == BigInteger.ONE
    val isPositive: Boolean get() = value.signum() > 0
    val isNegative: Boolean get() = value.signum() < 0
    val isEven: Boolean get() = !value.testBit(0)
    val isOdd: Boolean get() = value.testBit(0)
    val signum: Int get() = value.signum()

    operator fun plus(other: BigInt): BigInt = BigInt(value.add(other.value))
    operator fun plus(other: Long): BigInt = this + BigInt(other)

    operator fun minus(other: BigInt): BigInt = BigInt(value.subtract(other.value))
    operator fun minus(other: Long): BigInt = this - BigInt(other)

    operator fun times(other: BigInt): BigInt = BigInt(value.multiply(other.value))
    operator fun times(other: Long): BigInt = this * BigInt(other)

    operator fun div(other: BigInt): BigInt {
        require(!other.isZero) { "Division by zero" }
        return BigInt(value.divide(other.value))
    }
    operator fun div(other: Long): BigInt = this / BigInt(other)

    operator fun rem(other: BigInt): BigInt {
        require(!other.isZero) { "Division by zero" }
        return BigInt(value.remainder(other.value))
    }
    operator fun rem(other: Long): BigInt = this % BigInt(other)

    operator fun unaryMinus(): BigInt = BigInt(value.negate())

    fun abs(): BigInt = if (isNegative) -this else this

    fun pow(exponent: Long): BigInt {
        require(exponent >= 0) { "Exponent must be non-negative" }
        if (exponent == 0L) return ONE
        if (isZero) return ZERO
        if (exponent <= Int.MAX_VALUE) {
            return BigInt(value.pow(exponent.toInt()))
        }
        var base = this
        var exp = exponent
        var result = ONE
        while (exp > 0) {
            if ((exp and 1L) == 1L) {
                result = result * base
            }
            base = base * base
            exp = exp shr 1
        }
        return result
    }

    fun gcd(other: BigInt): BigInt = BigInt(value.gcd(other.value))

    fun lcm(other: BigInt): BigInt {
        if (isZero || other.isZero) return ZERO
        return (this * other).abs() / gcd(other)
    }

    override fun compareTo(other: BigInt): Int = value.compareTo(other.value)
    fun compareTo(other: Long): Int = value.compareTo(BigInteger.valueOf(other))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BigInt) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value.toString()
    fun toLong(): Long = value.toLong()
    fun toDouble(): Double = value.toDouble()

    companion object {
        val ZERO = BigInt(BigInteger.ZERO)
        val ONE = BigInt(BigInteger.ONE)
        val TWO = BigInt(2L)
        val MINUS_ONE = BigInt(-1L)

        fun factorial(n: Long): BigInt {
            if (n < 0) throw IllegalArgumentException("Factorial undefined for negative numbers")
            if (n < 2) return ONE
            var result = ONE
            for (i in 2..n) {
                result = result * i
            }
            return result
        }

        fun binomial(n: Long, k: Long): BigInt {
            if (k < 0 || k > n) return ZERO
            if (k == 0L || k == n) return ONE
            val effectiveK = if (k > n - k) n - k else k
            var result = ONE
            for (i in 1..effectiveK) {
                result = (result * (n - i + 1)) / i
            }
            return result
        }

        /**
         * Calculates the integer floor of the nth root: floor(x^(1/n))
         */
        fun truncatedNthRoot(x: BigInt, n: Long): BigInt {
            if (x.isZero) return ZERO
            if (x.isNegative) {
                if (n % 2 == 0L) throw IllegalArgumentException("Even root of negative number")
                return -truncatedNthRoot(-x, n)
            }
            require(n >= 1) { "Root degree must be positive" }
            if (n == 1L) return x
            val bitLength = x.value.bitLength().toLong()
            if (n >= bitLength) return ONE // 1 <= x < 2^n, so the root is 1

            // Integer Newton iteration started above the true root: 2^ceil(bits / n) > x^(1/n).
            // From above, the iterates decrease monotonically and stop at floor(x^(1/n)).
            // Purely BigInteger-based, so it never saturates like a double-based first guess.
            val bigN = BigInt(n)
            val bigNMinus1 = BigInt(n - 1)
            var r0 = BigInt(BigInteger.ONE.shiftLeft(((bitLength + n - 1) / n).toInt()))
            while (true) {
                val r1 = (bigNMinus1 * r0 + x / r0.pow(n - 1)) / bigN
                if (r1 >= r0) return r0
                r0 = r1
            }
        }

        /**
         * Finds the highest integer k such that k^rootDegree divides x.
         * Used for exact radical simplification: sqrt(18) -> 3*sqrt(2).
         */
        fun highestPerfectPowerFactor(x: BigInt, rootDegree: Long): BigInt {
            var n = x.abs()
            if (n <= ONE || rootDegree < 2) return ONE
            // 2^rootDegree > n: no perfect power factor other than 1 (also avoids huge pow calls)
            if (rootDegree >= n.value.bitLength()) return ONE
            var factor = ONE
            var d = 2L

            while (BigInt(d).pow(rootDegree) <= n) {
                val dPow = BigInt(d).pow(rootDegree)
                while ((n % dPow).isZero) {
                    factor = factor * d
                    n = n / dPow
                }
                d++
                if (d > 100000L) {
                    // Safety break for large prime searches
                    break
                }
            }
            return factor.pow(rootDegree)
        }
    }
}
