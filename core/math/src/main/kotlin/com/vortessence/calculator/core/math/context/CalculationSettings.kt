package com.vortessence.calculator.core.math.context

enum class CalculationMode {
    EXACT,
    NUMERIC
}

enum class AngleUnit {
    RADIANS,
    DEGREES
}

/** Which units results are expressed in. */
enum class UnitSystem {
    /** SI units (metre, kilogram, pascal, ...), with litre and °C for everyday quantities. */
    METRIC,
    /** British imperial units (foot, pound, imperial gallon, °F, ...). */
    IMPERIAL,
    /** US customary units (like imperial, but with US gallons). */
    US_CUSTOMARY
}
