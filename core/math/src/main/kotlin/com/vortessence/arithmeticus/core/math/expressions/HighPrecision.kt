package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.numbers.sqrtTo
import java.math.BigDecimal
import java.math.RoundingMode
import java.math.MathContext as Precision

/**
 * The decimal value of an expression without variables, worked out with as many digits as it takes for the first 17 to be right:
 * √(10²⁰ + 1) − 10¹⁰ is 5·10⁻¹¹ (in doubles 0), sin(10³⁰) needs π to 50 digits to find where in its period 10³⁰ is. The digits are
 * doubled until two rounds agree. Null for what it does not know (complex numbers, special functions, units), where the double
 * value stays.
 */
internal object HighPrecision {

    private const val START_DIGITS = 40
    private const val MAX_DIGITS = 1280
    /** From this many digits on, a value that is still only rounding is taken for 0. */
    private const val ZERO_DIGITS = 320

    private class Unsupported : RuntimeException()

    /** The value of [e] as a double, right to the last digit; null if it cannot be worked out here. */
    fun value(e: Expression, shouldStop: () -> Boolean = { false }): Double? = decimal(e, shouldStop)?.toDouble()

    /**
     * The value of [e] with (at least) its first 17 digits right, also where a double has no room for it (e¹⁰⁰⁰ is 1.97·10⁴³⁴);
     * null if it cannot be worked out here.
     */
    fun decimal(e: Expression, shouldStop: () -> Boolean = { false }): BigDecimal? {
        var digits = START_DIGITS
        var previous: BigDecimal? = null
        while (digits <= MAX_DIGITS) {
            if (shouldStop()) return null
            val current = try {
                Evaluator(Precision(digits, RoundingMode.HALF_EVEN), shouldStop).value(e)
            } catch (_: Unsupported) {
                return null
            } catch (_: ArithmeticException) {
                return null
            }
            if (previous != null) {
                val difference = current.subtract(previous).abs()
                // Agreed to 17 digits (two zeros do not count: √(10¹⁰⁰ + 1) − 10⁵⁰ is 0 to 80 digits, 5·10⁻⁵¹ with 160)
                if (current.signum() != 0 && difference <= current.abs().movePointLeft(17)) return current
                // Still no more than rounding with hundreds of digits: a value that is 0
                val noise = BigDecimal.ONE.movePointLeft(digits / 2 - 5)
                if (digits >= ZERO_DIGITS && current.abs() < noise && previous.abs() < noise) return BigDecimal.ZERO
            }
            previous = current
            digits *= 2
        }
        return null
    }

    private class Evaluator(private val mc: Precision, private val shouldStop: () -> Boolean) {

        fun value(e: Expression): BigDecimal {
            if (shouldStop()) throw Unsupported()
            return when (e) {
                is Value -> {
                    val r = e.rational
                    when {
                        r != null -> BigDecimal(r.numerator.value).divide(BigDecimal(r.denominator.value), mc)
                        e.real.value.isFinite() -> BigDecimal(e.real.value)
                        else -> throw Unsupported()
                    }
                }
                is Constant -> when (e) {
                    Constant.PI -> pi(mc)
                    Constant.E -> exp(BigDecimal.ONE)
                    Constant.PHI -> BigDecimal.ONE.add(BigDecimal(5).sqrtTo(mc)).divide(BigDecimal(2), mc)
                    Constant.SQRT2 -> BigDecimal(2).sqrtTo(mc)
                    else -> if (e.unit == null) value(e.value) else throw Unsupported()
                }
                is Additions -> e.operands.fold(BigDecimal.ZERO) { sum, term -> sum.add(value(term), mc) }
                is Multiplications -> e.operands.fold(BigDecimal.ONE) { product, factor -> product.multiply(value(factor), mc) }
                is Power -> power(e)
                is AbsoluteValue -> value(e.operand).abs()
                is Logarithm -> {
                    val base = if (e.base == Constant.E) null else value(e.base)
                    val ln = ln(value(e.operand))
                    if (base == null) ln else ln.divide(ln(base), mc)
                }
                is TrigonometricFunction -> trigonometric(e)
                is HyperbolicFunction -> hyperbolic(e)
                else -> throw Unsupported()
            }
        }

        private fun power(p: Power): BigDecimal {
            if (p.base == Constant.E) return exp(value(p.exponent))
            val base = value(p.base)
            val exponent = (p.exponent as? Value)?.rational
            if (exponent != null && exponent.denominator.value.bitLength() < 31 && exponent.numerator.value.bitLength() < 31) {
                val n = exponent.numerator.value.toInt()
                val q = exponent.denominator.value.toInt()
                val root = when {
                    q == 1 -> base
                    q == 2 -> if (base.signum() < 0) throw Unsupported() else base.sqrtTo(mc)
                    base.signum() < 0 && q % 2 == 1 -> root(base.negate(), q).negate()
                    base.signum() < 0 -> throw Unsupported()
                    else -> root(base, q)
                }
                if (n == 0) return BigDecimal.ONE
                if (root.signum() == 0) return if (n > 0) BigDecimal.ZERO else throw Unsupported()
                val magnitude = root.pow(kotlin.math.abs(n), mc)
                return if (n > 0) magnitude else BigDecimal.ONE.divide(magnitude, mc)
            }
            if (base.signum() <= 0) throw Unsupported()
            return exp(value(p.exponent).multiply(ln(base), mc))
        }

        /** The positive q-th root of [x] > 0, by Newton from the double estimate. */
        private fun root(x: BigDecimal, q: Int): BigDecimal {
            if (x.signum() == 0) return BigDecimal.ZERO
            var y = exp(ln(x).divide(BigDecimal(q), mc))
            val qq = BigDecimal(q)
            repeat(6) {
                // y ← y − (yᵠ − x)/(q·yᵠ⁻¹)
                val yq1 = y.pow(q - 1, mc)
                y = y.subtract(yq1.multiply(y, mc).subtract(x, mc).divide(qq.multiply(yq1, mc), mc), mc)
            }
            return y
        }

        private fun trigonometric(f: TrigonometricFunction): BigDecimal {
            if (f.type.isInverse) {
                val radians = when (f.type) {
                    TrigType.ATAN -> atan(value(f.operand))
                    TrigType.ASIN -> asin(value(f.operand))
                    TrigType.ACOS -> pi(mc).divide(BigDecimal(2), mc).subtract(asin(value(f.operand)), mc)
                    TrigType.ACOT -> {
                        // In (0, π), like the calculator
                        val x = value(f.operand)
                        if (x.signum() == 0) pi(mc).divide(BigDecimal(2), mc)
                        else atan(BigDecimal.ONE.divide(x, mc)).let { if (x.signum() < 0) it.add(pi(mc), mc) else it }
                    }
                    TrigType.ASEC -> pi(mc).divide(BigDecimal(2), mc).subtract(asin(BigDecimal.ONE.divide(value(f.operand), mc)), mc)
                    TrigType.ACSC -> asin(BigDecimal.ONE.divide(value(f.operand), mc))
                    else -> throw Unsupported()
                }
                return if (f.angleUnit == AngleUnit.DEGREES) radians.multiply(BigDecimal(180), mc).divide(pi(mc), mc) else radians
            }
            // After canonicalizing, the operand of sin, cos, tan is in radians
            if (f.angleUnit != AngleUnit.RADIANS) throw Unsupported()
            val x = value(f.operand)
            val sin = sin(x)
            val cos = cos(x)
            return when (f.type) {
                TrigType.SIN -> sin
                TrigType.COS -> cos
                TrigType.TAN -> sin.divide(cos, mc)
                TrigType.SEC -> BigDecimal.ONE.divide(cos, mc)
                TrigType.CSC -> BigDecimal.ONE.divide(sin, mc)
                TrigType.COT -> cos.divide(sin, mc)
                else -> throw Unsupported()
            }
        }

        private fun hyperbolic(f: HyperbolicFunction): BigDecimal {
            val x = value(f.operand)
            val two = BigDecimal(2)
            fun sinh() = exp(x).subtract(exp(x.negate()), mc).divide(two, mc)
            fun cosh() = exp(x).add(exp(x.negate()), mc).divide(two, mc)
            fun asinh(u: BigDecimal) = ln(u.add(u.multiply(u, mc).add(BigDecimal.ONE, mc).sqrtTo(mc), mc))
            fun acosh(u: BigDecimal) = ln(u.add(u.multiply(u, mc).subtract(BigDecimal.ONE, mc).sqrtTo(mc), mc))
            fun atanh(u: BigDecimal) = ln(BigDecimal.ONE.add(u, mc).divide(BigDecimal.ONE.subtract(u, mc), mc)).divide(two, mc)
            val one = BigDecimal.ONE
            return when (f.type) {
                HyperbolicType.SINH -> sinh()
                HyperbolicType.COSH -> cosh()
                HyperbolicType.TANH -> sinh().divide(cosh(), mc)
                HyperbolicType.SECH -> one.divide(cosh(), mc)
                HyperbolicType.CSCH -> one.divide(sinh(), mc)
                HyperbolicType.COTH -> cosh().divide(sinh(), mc)
                HyperbolicType.ASINH -> asinh(x)
                HyperbolicType.ACOSH -> acosh(x)
                HyperbolicType.ATANH -> atanh(x)
                HyperbolicType.ASECH -> acosh(one.divide(x, mc))
                HyperbolicType.ACSCH -> asinh(one.divide(x, mc))
                HyperbolicType.ACOTH -> atanh(one.divide(x, mc))
            }
        }

        // ----- the elementary functions -----

        /** eˣ: x = n·ln 2 + r, eʳ by its series after halving r eight times, then squared back. */
        fun exp(x: BigDecimal): BigDecimal {
            val ln2 = ln2(mc)
            val n = x.divide(ln2, mc).setScale(0, RoundingMode.HALF_EVEN)
            if (n.abs() > BigDecimal(1_000_000)) throw Unsupported()
            val wide = Precision(mc.precision + 10, RoundingMode.HALF_EVEN)
            val r = x.subtract(n.multiply(ln2, wide), wide).divide(BigDecimal(256), wide)
            var sum = BigDecimal.ONE
            var term = BigDecimal.ONE
            var k = 1
            val epsilon = BigDecimal.ONE.movePointLeft(wide.precision + 2)
            while (true) {
                term = term.multiply(r, wide).divide(BigDecimal(k), wide)
                if (term.abs() < epsilon) break
                sum = sum.add(term, wide)
                k++
            }
            repeat(8) { sum = sum.multiply(sum, wide) }
            val power = n.toInt()
            val twoToN = if (power >= 0) BigDecimal(2).pow(power) else BigDecimal.ONE.divide(BigDecimal(2).pow(-power), wide)
            return sum.multiply(twoToN, mc)
        }

        /** ln x for x > 0: x = m·2ᵏ with m near 1, ln m = 2·atanh((m − 1)/(m + 1)). */
        fun ln(x: BigDecimal): BigDecimal {
            if (x.signum() <= 0) throw Unsupported()
            val wide = Precision(mc.precision + 10, RoundingMode.HALF_EVEN)
            // log₂ x from the number of digits
            val decimalExponent = x.precision() - x.scale() - 1
            val leading = x.movePointLeft(decimalExponent).toDouble()
            val k = Math.floor((decimalExponent * Math.log(10.0) + Math.log(leading)) / Math.log(2.0)).toLong()
            if (kotlin.math.abs(k) > 5_000_000) throw Unsupported()
            val twoToK = BigDecimal(2).pow(kotlin.math.abs(k).toInt())
            val m = if (k >= 0) x.divide(twoToK, wide) else x.multiply(twoToK, wide)
            return atanhSeries(m.subtract(BigDecimal.ONE, wide).divide(m.add(BigDecimal.ONE, wide), wide), wide).multiply(BigDecimal(2), wide)
                .add(ln2(wide).multiply(BigDecimal(k), wide), mc)
        }

        /** atan x: for |x| > 1 from atan(1/x), then halved (atan x = 2·atan(x/(1 + √(1 + x²)))) until the series is quick. */
        fun atan(x: BigDecimal): BigDecimal {
            val wide = Precision(mc.precision + 10, RoundingMode.HALF_EVEN)
            if (x.abs() > BigDecimal.ONE) {
                val halfPi = pi(wide).divide(BigDecimal(2), wide)
                val rest = atan(BigDecimal.ONE.divide(x, wide))
                return (if (x.signum() > 0) halfPi.subtract(rest, wide) else halfPi.negate().subtract(rest, wide)).round(mc)
            }
            var y = x
            var doublings = 0
            while (y.abs() > BigDecimal("0.1")) {
                y = y.divide(BigDecimal.ONE.add(BigDecimal.ONE.add(y.multiply(y, wide), wide).sqrtTo(wide), wide), wide)
                doublings++
            }
            // atan y = y − y³/3 + y⁵/5 − …
            var sum = BigDecimal.ZERO
            var power = y
            val y2 = y.multiply(y, wide)
            var k = 0
            val epsilon = BigDecimal.ONE.movePointLeft(wide.precision + 2)
            while (power.abs() > epsilon) {
                val term = power.divide(BigDecimal(2 * k + 1), wide)
                sum = if (k % 2 == 0) sum.add(term, wide) else sum.subtract(term, wide)
                power = power.multiply(y2, wide)
                k++
            }
            return sum.multiply(BigDecimal(2).pow(doublings), mc)
        }

        fun asin(x: BigDecimal): BigDecimal {
            val wide = Precision(mc.precision + 10, RoundingMode.HALF_EVEN)
            if (x.abs() > BigDecimal.ONE) throw Unsupported()
            if (x.abs().compareTo(BigDecimal.ONE) == 0) return pi(wide).divide(BigDecimal(2 * x.signum()), mc)
            return atan(x.divide(BigDecimal.ONE.subtract(x.multiply(x, wide), wide).sqrtTo(wide), wide))
        }

        /** sin x, with x first brought into [−π, π] using π to as many digits as x has before its point. */
        fun sin(x: BigDecimal): BigDecimal {
            val r = reduced(x)
            return series(r, start = r, firstIndex = 1)
        }

        fun cos(x: BigDecimal): BigDecimal {
            val r = reduced(x)
            return series(r, start = BigDecimal.ONE, firstIndex = 0)
        }

        private fun reduced(x: BigDecimal): BigDecimal {
            val integerDigits = maxOf(0, x.precision() - x.scale())
            if (integerDigits > 2000) throw Unsupported()
            val wide = Precision(mc.precision + integerDigits + 10, RoundingMode.HALF_EVEN)
            val twoPi = pi(wide).multiply(BigDecimal(2), wide)
            val turns = x.divide(twoPi, wide).setScale(0, RoundingMode.HALF_EVEN)
            return x.subtract(turns.multiply(twoPi, wide), wide).round(Precision(mc.precision + 10, RoundingMode.HALF_EVEN))
        }

        /** Σ (−1)ᵏ x^(2k+i)/(2k+i)!, the series of sin (i = 1) and cos (i = 0). */
        private fun series(x: BigDecimal, start: BigDecimal, firstIndex: Int): BigDecimal {
            val wide = Precision(mc.precision + 10, RoundingMode.HALF_EVEN)
            val x2 = x.multiply(x, wide)
            var term = start
            var sum = start
            var n = firstIndex
            val epsilon = BigDecimal.ONE.movePointLeft(wide.precision + 2)
            while (term.abs() > epsilon) {
                term = term.multiply(x2, wide).negate().divide(BigDecimal((n + 1).toLong() * (n + 2)), wide)
                sum = sum.add(term, wide)
                n += 2
            }
            return sum.round(mc)
        }
    }

    /** atanh z = z + z³/3 + z⁵/5 + … for |z| < 1. */
    private fun atanhSeries(z: BigDecimal, mc: Precision): BigDecimal {
        val z2 = z.multiply(z, mc)
        var power = z
        var sum = BigDecimal.ZERO
        var k = 0
        val epsilon = BigDecimal.ONE.movePointLeft(mc.precision + 2)
        while (power.abs() > epsilon) {
            sum = sum.add(power.divide(BigDecimal(2 * k + 1), mc), mc)
            power = power.multiply(z2, mc)
            k++
        }
        return sum
    }

    private val piCache = HashMap<Int, BigDecimal>()
    private val ln2Cache = HashMap<Int, BigDecimal>()

    /** π by Machin: 16·atan(1/5) − 4·atan(1/239). */
    @Synchronized
    fun pi(mc: Precision): BigDecimal = piCache.getOrPut(mc.precision) {
        val wide = Precision(mc.precision + 10, RoundingMode.HALF_EVEN)
        fun arctanOfInverse(n: Int): BigDecimal {
            val x = BigDecimal.ONE.divide(BigDecimal(n), wide)
            val x2 = x.multiply(x, wide)
            var power = x
            var sum = BigDecimal.ZERO
            var k = 0
            val epsilon = BigDecimal.ONE.movePointLeft(wide.precision + 2)
            while (power.abs() > epsilon) {
                val term = power.divide(BigDecimal(2 * k + 1), wide)
                sum = if (k % 2 == 0) sum.add(term, wide) else sum.subtract(term, wide)
                power = power.multiply(x2, wide)
                k++
            }
            return sum
        }
        arctanOfInverse(5).multiply(BigDecimal(16), wide).subtract(arctanOfInverse(239).multiply(BigDecimal(4), wide), wide).round(mc)
    }

    /** ln 2 = 2·atanh(1/3). */
    @Synchronized
    fun ln2(mc: Precision): BigDecimal = ln2Cache.getOrPut(mc.precision) {
        val wide = Precision(mc.precision + 10, RoundingMode.HALF_EVEN)
        atanhSeries(BigDecimal.ONE.divide(BigDecimal(3), wide), wide).multiply(BigDecimal(2), wide).round(mc)
    }

}
