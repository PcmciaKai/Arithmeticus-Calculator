package com.vortessence.arithmeticus.core.math.numbers

import kotlin.math.*

/**
 * IEEE 754 double precision real number, mirroring legacy MEReal.
 */
class Real(val value: Double) : Comparable<Real> {

    constructor(longVal: Long) : this(longVal.toDouble())
    constructor(bigInt: BigInt) : this(bigInt.toDouble())
    constructor(rational: Rational) : this(rational.toDouble())

    val isNaN: Boolean get() = value.isNaN()
    val isInfinite: Boolean get() = value.isInfinite()
    val isFinite: Boolean get() = !value.isNaN() && !value.isInfinite()
    val isNegative: Boolean get() = value < 0.0
    val isZero: Boolean get() = value == 0.0

    operator fun plus(other: Real): Real = Real(value + other.value)
    operator fun plus(other: Double): Real = Real(value + other)

    operator fun minus(other: Real): Real = Real(value - other.value)
    operator fun minus(other: Double): Real = Real(value - other)

    operator fun times(other: Real): Real = Real(value * other.value)
    operator fun times(other: Double): Real = Real(value * other)

    operator fun div(other: Real): Real = Real(value / other.value)
    operator fun div(other: Double): Real = Real(value / other)

    operator fun unaryMinus(): Real = Real(-value)

    fun abs(): Real = Real(abs(value))
    fun inv(): Real = Real(1.0 / value)
    fun sqrt(): Real = Real(sqrt(value))
    fun pow(exponent: Real): Real = Real(value.pow(exponent.value))
    fun pow(exponent: Double): Real = Real(value.pow(exponent))
    fun nthRoot(n: Double): Real = Real(value.pow(1.0 / n))

    fun sin(): Real = Real(sin(value))
    fun cos(): Real = Real(cos(value))
    fun tan(): Real = Real(tan(value))
    fun asin(): Real = Real(asin(value))
    fun acos(): Real = Real(acos(value))
    fun atan(): Real = Real(atan(value))
    fun ln(): Real = Real(ln(value))
    fun log10(): Real = Real(log10(value))
    fun log(base: Real): Real = Real(ln(value) / ln(base.value))

    fun ceil(): Real = Real(ceil(value))
    fun floor(): Real = Real(floor(value))
    fun round(): Real = Real(round(value))

    override fun compareTo(other: Real): Int = value.compareTo(other.value)
    fun compareTo(other: Double): Int = value.compareTo(other)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Real) return false
        return value.toRawBits() == other.value.toRawBits()
    }

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toString()

    companion object {
        val ZERO = Real(0.0)
        val ONE = Real(1.0)
        val MINUS_ONE = Real(-1.0)
        val PI = Real(kotlin.math.PI)
        val E = Real(kotlin.math.E)
        val POSITIVE_INFINITY = Real(Double.POSITIVE_INFINITY)
    }
}
