package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.numbers.Rational

data class Quantity(val name: String) : Expression() {
    override fun canonicalize(context: MathContext): Expression = this
    override fun evaluateNumerically(value: Double, variable: Variable?): Double = Double.NaN
    override fun toString(): String = "[$name]"

    companion object {
        val ANGLE = Quantity("Angle")
        val LENGTH = Quantity("Length")
        val MASS = Quantity("Mass")
        val TIME = Quantity("Time")
        val VELOCITY = Quantity("Velocity")
        val TEMPERATURE = Quantity("Temperature")
    }
}

class UnitExpression(
    val symbol: String,
    val quantity: Quantity,
    baseUnitSupplier: (() -> UnitExpression)? = null,
    unitExpressedInBaseUnitSupplier: (() -> Expression)? = null,
    baseUnitExpressedInUnitSupplier: (() -> Expression)? = null
) : Expression() {

    private val _baseUnitSupplier = baseUnitSupplier
    val baseUnit: UnitExpression by lazy {
        _baseUnitSupplier?.invoke() ?: this
    }

    private val _unitInBaseSupplier = unitExpressedInBaseUnitSupplier
    val unitExpressedInBaseUnit: Expression by lazy {
        _unitInBaseSupplier?.invoke() ?: this
    }

    private val _baseInUnitSupplier = baseUnitExpressedInUnitSupplier
    val baseUnitExpressedInUnit: Expression by lazy {
        _baseInUnitSupplier?.invoke() ?: this
    }

    override fun partWithUnit(): Expression = this
    override fun quantityOfUnit(): Expression = quantity

    override fun canonicalize(context: MathContext): Expression = this

    override fun evaluateNumerically(value: Double, variable: Variable?): Double = 1.0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnitExpression) return false
        return symbol == other.symbol && quantity == other.quantity
    }

    override fun hashCode(): Int = symbol.hashCode() xor quantity.hashCode()

    override fun toString(): String = symbol
}

object UnitRegistry {
    // Angle
    val RAD: UnitExpression = UnitExpression("rad", Quantity.ANGLE)
    val DEG: UnitExpression = UnitExpression(
        "°", Quantity.ANGLE,
        baseUnitSupplier = { RAD },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Constant.PI, Power.of(Value(180L), Value.MINUS_ONE), RAD) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(180L), Power.of(Constant.PI, Value.MINUS_ONE), DEG) }
    )

    // Length
    val M: UnitExpression = UnitExpression("m", Quantity.LENGTH)
    val KM: UnitExpression = UnitExpression(
        "km", Quantity.LENGTH,
        baseUnitSupplier = { M },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(1000L), M) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(1L, 1000L), KM) }
    )
    val CM: UnitExpression = UnitExpression(
        "cm", Quantity.LENGTH,
        baseUnitSupplier = { M },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(1L, 100L), M) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(100L), CM) }
    )
    val MM: UnitExpression = UnitExpression(
        "mm", Quantity.LENGTH,
        baseUnitSupplier = { M },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(1L, 1000L), M) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(1000L), MM) }
    )
    val INCH: UnitExpression = UnitExpression(
        "in", Quantity.LENGTH,
        baseUnitSupplier = { M },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(254L, 10000L), M) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(10000L, 254L), INCH) }
    )
    val FT: UnitExpression = UnitExpression(
        "ft", Quantity.LENGTH,
        baseUnitSupplier = { M },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(3048L, 10000L), M) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(10000L, 3048L), FT) }
    )
    val MILE: UnitExpression = UnitExpression(
        "mi", Quantity.LENGTH,
        baseUnitSupplier = { M },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(1609344L, 1000L), M) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(1000L, 1609344L), MILE) }
    )

    // Mass
    val KG: UnitExpression = UnitExpression("kg", Quantity.MASS)
    val G: UnitExpression = UnitExpression(
        "g", Quantity.MASS,
        baseUnitSupplier = { KG },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(1L, 1000L), KG) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(1000L), G) }
    )
    val MG: UnitExpression = UnitExpression(
        "mg", Quantity.MASS,
        baseUnitSupplier = { KG },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(1L, 1000000L), KG) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(1000000L), MG) }
    )
    val LB: UnitExpression = UnitExpression(
        "lb", Quantity.MASS,
        baseUnitSupplier = { KG },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(45359237L, 100000000L), KG) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(100000000L, 45359237L), LB) }
    )

    // Time
    val SEC: UnitExpression = UnitExpression("s", Quantity.TIME)
    val MIN: UnitExpression = UnitExpression(
        "min", Quantity.TIME,
        baseUnitSupplier = { SEC },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(60L), SEC) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(1L, 60L), MIN) }
    )
    val HR: UnitExpression = UnitExpression(
        "h", Quantity.TIME,
        baseUnitSupplier = { SEC },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(3600L), SEC) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(1L, 3600L), HR) }
    )

    // Velocity
    val MPS: UnitExpression = UnitExpression("m/s", Quantity.VELOCITY)
    val KMH: UnitExpression = UnitExpression(
        "km/h", Quantity.VELOCITY,
        baseUnitSupplier = { MPS },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(1000L, 3600L), MPS) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(3600L, 1000L), KMH) }
    )
    val MPH: UnitExpression = UnitExpression(
        "mph", Quantity.VELOCITY,
        baseUnitSupplier = { MPS },
        unitExpressedInBaseUnitSupplier = { Multiplications.of(Value(1609344L, 3600000L), MPS) },
        baseUnitExpressedInUnitSupplier = { Multiplications.of(Value(3600000L, 1609344L), MPH) }
    )

    // Temperature. Conversions between temperature scales are affine (they have an offset),
    // so they cannot be expressed by the multiplicative substitution used for the other units.
    // They are handled via [temperatureToKelvin] in Expression.convertToUnit instead.
    val K: UnitExpression = UnitExpression("K", Quantity.TEMPERATURE)
    val CELSIUS: UnitExpression = UnitExpression("°C", Quantity.TEMPERATURE)
    val FAHRENHEIT: UnitExpression = UnitExpression("°F", Quantity.TEMPERATURE)

    /** kelvin = value * first + second */
    val temperatureToKelvin: Map<UnitExpression, Pair<Rational, Rational>> = mapOf(
        K to (Rational.ONE to Rational.ZERO),
        CELSIUS to (Rational.ONE to Rational.of(27315L, 100L)),
        FAHRENHEIT to (Rational.of(5L, 9L) to Rational.of(45967L, 180L))
    )

    val allUnits = listOf(
        RAD, DEG,
        M, KM, CM, MM, INCH, FT, MILE,
        KG, G, MG, LB,
        SEC, MIN, HR,
        MPS, KMH, MPH,
        K, CELSIUS, FAHRENHEIT
    )

    val defaultUnits: Map<Expression, UnitExpression> = mapOf(
        Quantity.ANGLE to RAD,
        Quantity.LENGTH to M,
        Quantity.MASS to KG,
        Quantity.TIME to SEC,
        Quantity.VELOCITY to MPS,
        Quantity.TEMPERATURE to CELSIUS
    )

    fun findBySymbol(sym: String): UnitExpression? {
        return allUnits.firstOrNull { it.symbol.equals(sym, ignoreCase = true) }
    }
}
