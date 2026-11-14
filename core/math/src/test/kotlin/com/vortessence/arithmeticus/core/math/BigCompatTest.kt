package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.numbers.floorSqrt
import com.vortessence.arithmeticus.core.math.numbers.sqrtTo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import kotlin.random.Random

/** The stand-ins for BigInteger.sqrt and BigDecimal.sqrt (Android has them only from API 33) agree with Java's. */
class BigCompatTest {

    @Test
    fun integerRootIsJavas() {
        val random = Random(7)
        val numbers = (0L..300L).map { BigInteger.valueOf(it) } +
            (1..300).map { BigInteger(random.nextInt(1, 700), java.util.Random(random.nextLong())) } +
            listOf(BigInteger.TEN.pow(400), BigInteger.TEN.pow(400).subtract(BigInteger.ONE), BigInteger.valueOf(Long.MAX_VALUE))
        for (n in numbers) assertEquals(n.toString(), n.sqrt(), n.floorSqrt())
    }

    @Test
    fun decimalRootIsJavas() {
        val values = listOf("2", "5", "0.5", "1e-30", "123456789.987654321", "1e400", "3e401", "7e-401", "0.0004", "16", "1")
        for (precision in listOf(10, 34, 50)) {
            val mc = MathContext(precision)
            for (v in values) {
                val x = BigDecimal(v)
                val expected = x.sqrt(mc)
                val actual = x.sqrtTo(mc)
                // Equal to the last digit, but for at most one unit there (the double rounding)
                val ulp = expected.ulp()
                assertEquals("$v to $precision digits", 0, expected.subtract(actual).abs().compareTo(ulp).coerceAtLeast(0))
            }
        }
        assertEquals(0, BigDecimal.ZERO.sqrtTo(MathContext(20)).signum())
    }
}
