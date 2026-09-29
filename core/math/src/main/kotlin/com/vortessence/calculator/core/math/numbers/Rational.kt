package com.vortessence.calculator.core.math.numbers

/**
 * Exact arbitrary-precision rational number (fraction) representing numerator / denominator.
 * Automatically canonicalized to lowest terms with positive denominator.
 */
class Rational private constructor(
    val numerator: BigInt,
    val denominator: BigInt
) : Comparable<Rational> {

    val isFractional: Boolean get() = denominator != BigInt.ONE
    val isZero: Boolean get() = numerator.isZero
    val isOne: Boolean get() = numerator == BigInt.ONE && denominator == BigInt.ONE
    val isPositive: Boolean get() = numerator.isPositive
    val isNegative: Boolean get() = numerator.isNegative
    val signum: Int get() = numerator.signum

    operator fun plus(other: Rational): Rational =
        of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)

    operator fun plus(other: BigInt): Rational =
        of(numerator + other * denominator, denominator)

    operator fun plus(other: Long): Rational = this + BigInt(other)

    operator fun minus(other: Rational): Rational =
        of(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)

    operator fun minus(other: BigInt): Rational =
        of(numerator - other * denominator, denominator)

    operator fun minus(other: Long): Rational = this - BigInt(other)

    operator fun times(other: Rational): Rational =
        of(numerator * other.numerator, denominator * other.denominator)

    operator fun times(other: BigInt): Rational =
        of(numerator * other, denominator)

    operator fun times(other: Long): Rational = this * BigInt(other)

    operator fun div(other: Rational): Rational {
        require(!other.isZero) { "Division by zero" }
        return of(numerator * other.denominator, denominator * other.numerator)
    }

    operator fun div(other: BigInt): Rational {
        require(!other.isZero) { "Division by zero" }
        return of(numerator, denominator * other)
    }

    operator fun div(other: Long): Rational = this / BigInt(other)

    operator fun unaryMinus(): Rational = Rational(-numerator, denominator)

    fun abs(): Rational = if (isNegative) -this else this

    fun inv(): Rational {
        require(!isZero) { "Division by zero" }
        return of(denominator, numerator)
    }

    fun pow(exponent: Long): Rational {
        return when {
            exponent == 0L -> ONE
            exponent > 0L -> of(numerator.pow(exponent), denominator.pow(exponent))
            else -> of(denominator.pow(-exponent), numerator.pow(-exponent))
        }
    }

    override fun compareTo(other: Rational): Int {
        if (signum != other.signum) return signum.compareTo(other.signum)
        return (numerator * other.denominator).compareTo(other.numerator * denominator)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Rational) return false
        return numerator == other.numerator && denominator == other.denominator
    }

    override fun hashCode(): Int = numerator.hashCode() xor denominator.hashCode()

    override fun toString(): String {
        return if (isFractional) "$numerator/$denominator" else numerator.toString()
    }

    fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()

    companion object {
        val ZERO = Rational(BigInt.ZERO, BigInt.ONE)
        val ONE = Rational(BigInt.ONE, BigInt.ONE)
        val MINUS_ONE = Rational(BigInt.MINUS_ONE, BigInt.ONE)
        val HALF = Rational(BigInt.ONE, BigInt.TWO)

        fun of(n: BigInt, d: BigInt): Rational {
            require(!d.isZero) { "Denominator cannot be zero" }
            if (n.isZero) return ZERO
            var num = n
            var den = d
            if (den.isNegative) {
                num = -num
                den = -den
            }
            val common = num.gcd(den)
            return if (common == BigInt.ONE) {
                Rational(num, den)
            } else {
                Rational(num / common, den / common)
            }
        }

        fun of(n: Long, d: Long = 1L): Rational = of(BigInt(n), BigInt(d))
        fun of(n: BigInt): Rational = of(n, BigInt.ONE)

        fun approximateFromDouble(d: Double, maxDenominator: Long = 10000L): Rational? {
            if (d.isNaN() || d.isInfinite()) return null
            var x = d
            val isNeg = x < 0.0
            if (isNeg) x = -x

            var h1 = 1L
            var h0 = 0L
            var k1 = 0L
            var k0 = 1L

            var b = x
            do {
                val a = b.toLong()
                val h2 = a * h1 + h0
                val k2 = a * k1 + k0
                if (k2 > maxDenominator) break
                h0 = h1
                h1 = h2
                k0 = k1
                k1 = k2
                if (Math.abs(b - a.toDouble()) < 1e-12) break
                b = 1.0 / (b - a.toDouble())
            } while (Math.abs(x - h1.toDouble() / k1.toDouble()) > 1e-9)

            if (k1 == 0L) return null
            val finalH = if (isNeg) -h1 else h1
            return of(finalH, k1)
        }
    }
}
