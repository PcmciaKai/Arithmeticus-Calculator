package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.numbers.Real

class Value(
    val rational: Rational?,
    val real: Real
) : Expression() {

    constructor(rational: Rational) : this(rational, Real(rational))
    constructor(real: Real) : this(null, real)
    constructor(bigInt: BigInt) : this(Rational.of(bigInt))
    constructor(longVal: Long) : this(Rational.of(longVal))
    constructor(doubleVal: Double) : this(null, Real(doubleVal))
    constructor(num: Long, den: Long) : this(Rational.of(num, den))
    constructor(num: BigInt, den: BigInt) : this(Rational.of(num, den))

    override val isNegative: Boolean get() = real.isNegative
    override val isZero: Boolean get() = real.isZero

    override fun coefficient(): Value = this
    override fun partWithoutCoefficient(): Expression? = null

    override fun canonicalize(context: MathContext): Expression {
        return if (context.mode == CalculationMode.NUMERIC && rational != null) {
            Value(null, real)
        } else {
            this
        }
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = real.value

    fun plus(other: Value): Value {
        return if (rational != null && other.rational != null) {
            Value(rational + other.rational)
        } else {
            Value(real + other.real)
        }
    }

    fun minus(other: Value): Value {
        return if (rational != null && other.rational != null) {
            Value(rational - other.rational)
        } else {
            Value(real - other.real)
        }
    }

    fun times(other: Value): Value {
        return if (rational != null && other.rational != null) {
            Value(rational * other.rational)
        } else {
            Value(real * other.real)
        }
    }

    fun div(other: Value): Value {
        return if (rational != null && other.rational != null) {
            Value(rational / other.rational)
        } else {
            Value(real / other.real)
        }
    }

    override fun negate(): Value {
        return if (rational != null) {
            Value(-rational)
        } else {
            Value(-real)
        }
    }

    override fun invert(): Value {
        return if (rational != null) {
            Value(rational.inv())
        } else {
            Value(real.inv())
        }
    }

    fun abs(): Value {
        return if (rational != null) {
            Value(rational.abs())
        } else {
            Value(real.abs())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Value) return false
        return if (rational != null && other.rational != null) {
            rational == other.rational
        } else {
            real == other.real
        }
    }

    // Must agree with equals(), which falls back to comparing the real parts when either side
    // is inexact. Equal rationals always convert to the same double, so hashing the real part
    // is consistent for both branches.
    override fun hashCode(): Int = real.hashCode()

    override fun toString(): String = rational?.toString() ?: real.toString()

    companion object {
        val ZERO = Value(Rational.ZERO, Real.ZERO)
        val ONE = Value(Rational.ONE, Real.ONE)
        val MINUS_ONE = Value(Rational.MINUS_ONE, Real.MINUS_ONE)
        val TWO = Value(2L)
        val HALF = Value(Rational.HALF, Real(0.5))
        val PI = Value(null, Real.PI)
        val E = Value(null, Real.E)
    }
}
