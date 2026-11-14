package com.vortessence.arithmeticus.core.math.numbers

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/*
 * What Java 9 added to BigInteger and BigDecimal (TWO, sqrt) is on Android only from API 33, and the app runs from API 30:
 * there it would crash with NoSuchFieldError / NoSuchMethodError. The engine is a plain JVM module, so lint does not see it;
 * use these instead.
 */

/** 2, in place of BigInteger.TWO. */
val BIG_TWO: BigInteger = BigInteger.valueOf(2)

/** The square root rounded down, in place of BigInteger.sqrt(): Newton's method from above. */
fun BigInteger.floorSqrt(): BigInteger {
    if (signum() < 0) throw ArithmeticException("Square root of a negative number")
    if (this < BIG_TWO) return this
    var x = BigInteger.ONE.shiftLeft((bitLength() + 1) / 2) // at least the root
    while (true) {
        val y = x.add(divide(x)).shiftRight(1)
        if (y >= x) return x
        x = y
    }
}

/**
 * The square root to [mc], in place of BigDecimal.sqrt(MathContext). Also for numbers far beyond the range of a double:
 * the digits come from the integer root of the digits, the exponent is halved.
 */
fun BigDecimal.sqrtTo(mc: MathContext): BigDecimal {
    if (signum() < 0) throw ArithmeticException("Square root of a negative number")
    if (signum() == 0) return BigDecimal.ZERO
    // this = digits · 10^(−scale), with an even scale so that it halves
    var digits = unscaledValue()
    var scale = scale()
    if (scale % 2 != 0) {
        digits = digits.multiply(BigInteger.TEN)
        scale += 1
    }
    // Two more digits than asked for, so that the last one rounds right
    val wanted = (if (mc.precision == 0) 34 else mc.precision) + 2
    val shift = maxOf(0, wanted - digits.toString().length / 2 + 1)
    val root = digits.multiply(BigInteger.TEN.pow(2 * shift)).floorSqrt()
    val result = BigDecimal(root, scale / 2 + shift)
    return if (mc.precision == 0) result.stripTrailingZeros() else result.round(mc)
}
