package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.context.UnitSystem
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Exponents of the SI base dimensions. Plane angle is tracked as its own dimension (SI treats
 * it as dimensionless) so that angles convert between radians and degrees.
 */
data class Dimension(
    val length: Int = 0,
    val mass: Int = 0,
    val time: Int = 0,
    val current: Int = 0,
    val temperature: Int = 0,
    val amount: Int = 0,
    val luminosity: Int = 0,
    val angle: Int = 0
) {
    private val exponents get() = intArrayOf(length, mass, time, current, temperature, amount, luminosity, angle)

    operator fun plus(other: Dimension) = Dimension(
        length + other.length, mass + other.mass, time + other.time, current + other.current,
        temperature + other.temperature, amount + other.amount, luminosity + other.luminosity, angle + other.angle
    )

    operator fun times(n: Int) = Dimension(
        length * n, mass * n, time * n, current * n, temperature * n, amount * n, luminosity * n, angle * n
    )

    /** This dimension divided by [n], or null if an exponent is not divisible (e.g. sqrt(m)). */
    fun dividedBy(n: Int): Dimension? {
        if (exponents.any { it % n != 0 }) return null
        return Dimension(
            length / n, mass / n, time / n, current / n, temperature / n, amount / n, luminosity / n, angle / n
        )
    }

    val isNone: Boolean get() = this == NONE

    companion object {
        val NONE = Dimension()
        val LENGTH = Dimension(length = 1)
        val MASS = Dimension(mass = 1)
        val TIME = Dimension(time = 1)
        val CURRENT = Dimension(current = 1)
        val TEMPERATURE = Dimension(temperature = 1)
        val AMOUNT = Dimension(amount = 1)
        val LUMINOSITY = Dimension(luminosity = 1)
        val ANGLE = Dimension(angle = 1)

        val AREA = LENGTH * 2
        val VOLUME = LENGTH * 3
        val FREQUENCY = TIME * -1
        val SPEED = LENGTH + TIME * -1
        val ACCELERATION = LENGTH + TIME * -2
        val FORCE = MASS + ACCELERATION
        val PRESSURE = FORCE + AREA * -1
        val ENERGY = FORCE + LENGTH
        val POWER = ENERGY + TIME * -1
        val CHARGE = CURRENT + TIME
        val VOLTAGE = POWER + CURRENT * -1
        val RESISTANCE = VOLTAGE + CURRENT * -1
        val CONDUCTANCE = RESISTANCE * -1
        val CAPACITANCE = CHARGE + VOLTAGE * -1
        val MAGNETIC_FLUX = VOLTAGE + TIME
        val INDUCTANCE = MAGNETIC_FLUX + CURRENT * -1
        val MAGNETIC_FLUX_DENSITY = MAGNETIC_FLUX + AREA * -1
        val CONCENTRATION = AMOUNT + VOLUME * -1
        val MOLAR_MASS = MASS + AMOUNT * -1
        val DENSITY = MASS + VOLUME * -1
        val ILLUMINANCE = LUMINOSITY + AREA * -1
    }
}

/**
 * A unit of measurement. Its value in coherent SI units is [factor] (e.g. km: 1000, meaning
 * 1 km = 1000 m); temperatures on scales with a shifted zero also have an [offset]
 * (kelvin = value * factor + offset).
 */
class UnitExpression(
    val symbol: String,
    val name: String,
    val dimension: Dimension,
    val factor: Expression,
    val offset: Rational? = null
) : Expression() {

    override fun partWithUnit(): Expression = this

    override fun canonicalize(context: MathContext): Expression = this

    override fun evaluateNumerically(value: Double, variable: Variable?): Double = 1.0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnitExpression) return false
        return symbol == other.symbol
    }

    override fun hashCode(): Int = symbol.hashCode()

    override fun toString(): String = symbol
}

/** Related units shown together in the unit picker, e.g. nm, µm, mm, cm, m, km. */
data class UnitFamily(val name: String, val units: List<UnitExpression>)

/** A group of families measuring the same kind of quantity, e.g. "Length". */
data class UnitCategory(val name: String, val families: List<UnitFamily>) {
    val units: List<UnitExpression> get() = families.flatMap { it.units }
}

fun Expression.containsUnit(): Boolean = containsExpressionMatching { it is UnitExpression }

/**
 * The physical dimension of an expression's units, [Dimension.NONE] if it has none, or
 * null if the units are inconsistent (e.g. m + s, or the square root of m).
 */
fun dimensionOf(expr: Expression): Dimension? = when (expr) {
    is UnitExpression -> expr.dimension
    is Multiplications -> expr.operands.fold(Dimension.NONE) { acc, op -> acc + (dimensionOf(op) ?: return null) }
    is Power -> {
        val base = dimensionOf(expr.base)
        when {
            base == null -> null
            base.isNone -> Dimension.NONE
            else -> {
                val exponent = (expr.exponent as? Value)?.rational
                if (exponent == null || !fitsInt(exponent.numerator) || !fitsInt(exponent.denominator)) {
                    null
                } else {
                    (base * exponent.numerator.toLong().toInt()).dividedBy(exponent.denominator.toLong().toInt())
                }
            }
        }
    }
    is Additions -> {
        val dims = expr.operands.map { dimensionOf(it) ?: return null }.filterNot { it.isNone }.distinct()
        if (dims.size > 1) null else dims.firstOrNull() ?: Dimension.NONE
    }
    else -> Dimension.NONE
}

private fun fitsInt(n: BigInt): Boolean = n.value.bitLength() < 31

/** Replaces every unit by its SI factor, i.e. the magnitude in coherent SI units. */
internal fun Expression.withUnitsAsSiFactors(): Expression =
    transform { (it as? UnitExpression)?.factor ?: it }

/**
 * Expresses a result in the preferred units of the context's [UnitSystem], e.g. 5 km + 300 m
 * as 5300 m (metric) or 17388.45 ft (imperial). Results without units are returned unchanged;
 * units that cancel out (e.g. m / km) give a plain number.
 */
fun Expression.toPreferredUnits(context: MathContext): Expression {
    if (!containsUnit()) return this
    val dimension = dimensionOf(this)
    if (dimension == null) {
        context.stopWithError(MathIssue.INVALID_UNIT)
        return this
    }
    if (dimension.isNone) return withUnitsAsSiFactors().canonicalize(context)
    val target = UnitRegistry.preferredUnit(dimension, context.unitSystem, context.angleUnit, context.temperatureInKelvin)
    return convertToUnit(target, includeUnitInResult = true, context = context) ?: this
}

/** Whether [expr] consists of units only (e.g. km, m², km/h), i.e. is a valid conversion target. */
fun isUnitOnly(expr: Expression): Boolean {
    fun isUnitFactor(e: Expression) = e is UnitExpression || (e is Power && e.base is UnitExpression)
    return isUnitFactor(expr) || (expr is Multiplications && expr.operands.all { isUnitFactor(it) })
}

/**
 * An explicit conversion "value → target", e.g. 5 km → mi. The result is expressed in the
 * target unit(s) instead of the unit system's preferred units.
 */
class UnitConversion(val value: Expression, val target: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(value, target)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 2)
        return UnitConversion(children[0], children[1])
    }

    override fun canonicalize(context: MathContext): Expression {
        val cTarget = target.canonicalize(context)
        if (context.shouldStop) return this
        if (!isUnitOnly(cTarget)) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return this
        }
        var cValue = value.canonicalize(context)
        if (context.shouldStop) return this
        // A plain number converted to an angle unit is an angle in radians (π/2 → ° = 90°)
        if (!cValue.containsUnit() && dimensionOf(cTarget) == Dimension.ANGLE) {
            cValue = Multiplications.of(cValue, UnitRegistry.RAD).canonicalize(context)
        }
        return cValue.convertToUnit(cTarget, includeUnitInResult = true, context = context) ?: this
    }

    override fun evaluateNumerically(value: Double, variable: Variable?): Double =
        this.value.evaluateNumerically(value, variable)

    override fun equals(other: Any?): Boolean =
        other is UnitConversion && value == other.value && target == other.target

    override fun hashCode(): Int = value.hashCode() * 31 + target.hashCode()

    override fun toString(): String = "$value → $target"
}

object UnitRegistry {

    // --- Catalog helpers ---------------------------------------------------------------------

    /** Parses an exact decimal such as "0.45359237" or "1.602176634e-19" into a rational. */
    private fun exact(decimal: String): Rational {
        val d = BigDecimal(decimal)
        val unscaled = BigInt(d.unscaledValue())
        return if (d.scale() >= 0) {
            Rational.of(unscaled, BigInt(BigInteger.TEN.pow(d.scale())))
        } else {
            Rational.of(unscaled * BigInt(BigInteger.TEN.pow(-d.scale())))
        }
    }

    private fun unit(symbol: String, name: String, dimension: Dimension, factor: Rational, offset: Rational? = null) =
        UnitExpression(symbol, name, dimension, Value(factor), offset)

    private fun unit(symbol: String, name: String, dimension: Dimension, factor: String = "1") =
        unit(symbol, name, dimension, exact(factor))

    private class Prefix(val symbol: String, val name: String, val exponent: Int)

    private val prefixes = listOf(
        Prefix("p", "pico", -12), Prefix("n", "nano", -9), Prefix("µ", "micro", -6),
        Prefix("m", "milli", -3), Prefix("c", "centi", -2), Prefix("d", "deci", -1),
        Prefix("", "", 0), Prefix("h", "hecto", 2), Prefix("k", "kilo", 3),
        Prefix("M", "mega", 6), Prefix("G", "giga", 9)
    ).associateBy { it.symbol }

    private fun superscript(power: Int) = when (power) {
        2 -> "²"
        3 -> "³"
        else -> ""
    }

    /**
     * A family of SI-prefixed units, e.g. `metric("Metre", "m", "metre", LENGTH, "n µ m c _ k")`.
     * "_" stands for the unprefixed unit. [power] builds square/cubic units (km², cm³).
     */
    private fun metric(
        familyName: String,
        symbol: String,
        name: String,
        dimension: Dimension,
        prefixList: String,
        power: Int = 1,
        baseFactor: Rational = Rational.ONE
    ): UnitFamily {
        val units = prefixList.split(" ").map { key ->
            val prefix = prefixes.getValue(if (key == "_") "" else key)
            val scale = if (prefix.exponent >= 0) {
                Rational.of(BigInt(BigInteger.TEN.pow(prefix.exponent * power)))
            } else {
                Rational.of(BigInt.ONE, BigInt(BigInteger.TEN.pow(-prefix.exponent * power)))
            }
            val qualifier = when (power) {
                2 -> "square "
                3 -> "cubic "
                else -> ""
            }
            unit(prefix.symbol + symbol + superscript(power), qualifier + prefix.name + name, dimension * power, baseFactor * scale)
        }
        return UnitFamily(familyName, units)
    }

    private fun family(name: String, vararg units: UnitExpression) = UnitFamily(name, units.toList())

    // Exact definitions of customary units in SI
    private val INCH = exact("0.0254")
    private val FOOT = exact("0.3048")
    private val POUND = exact("0.45359237")
    private val POUND_FORCE = exact("4.4482216152605")
    private val STANDARD_GRAVITY = exact("9.80665")
    private val ATMOSPHERE = exact("101325")
    private val FOOT_POUND_FORCE = FOOT * POUND_FORCE
    private val PI_OVER = { n: Long -> Multiplications.of(Value(1L, n), Constant.PI) }

    // --- Catalog --------------------------------------------------------------------------------

    val categories: List<UnitCategory> = listOf(
        UnitCategory("Length", listOf(
            metric("Metre", "m", "metre", Dimension.LENGTH, "p n µ m c d _ k"),
            family("Imperial / US",
                unit("in", "inch", Dimension.LENGTH, INCH),
                unit("ft", "foot", Dimension.LENGTH, FOOT),
                unit("yd", "yard", Dimension.LENGTH, "0.9144"),
                unit("mi", "mile", Dimension.LENGTH, "1609.344")),
            family("Nautical", unit("nmi", "nautical mile", Dimension.LENGTH, "1852")),
            family("Atomic & astronomical",
                unit("Å", "ångström", Dimension.LENGTH, "1e-10"),
                unit("au", "astronomical unit", Dimension.LENGTH, "149597870700"),
                unit("ly", "light-year", Dimension.LENGTH, "9460730472580800"),
                unit("pc", "parsec", Dimension.LENGTH, "30856775814913673"))
        )),
        UnitCategory("Area", listOf(
            metric("Square metre", "m", "metre", Dimension.LENGTH, "m c _ k", power = 2),
            family("Land", unit("ha", "hectare", Dimension.AREA, "10000"),
                unit("ac", "acre", Dimension.AREA, "4046.8564224")),
            family("Imperial / US",
                unit("in²", "square inch", Dimension.AREA, INCH * INCH),
                unit("ft²", "square foot", Dimension.AREA, FOOT * FOOT),
                unit("yd²", "square yard", Dimension.AREA, "0.83612736"),
                unit("mi²", "square mile", Dimension.AREA, "2589988.110336"))
        )),
        UnitCategory("Volume", listOf(
            metric("Litre", "L", "litre", Dimension.VOLUME, "µ m c d _", baseFactor = exact("0.001")),
            metric("Cubic metre", "m", "metre", Dimension.LENGTH, "m c _", power = 3),
            family("US customary",
                unit("tsp", "US teaspoon", Dimension.VOLUME, "0.00000492892159375"),
                unit("tbsp", "US tablespoon", Dimension.VOLUME, "0.00001478676478125"),
                unit("fl oz", "US fluid ounce", Dimension.VOLUME, "0.0000295735295625"),
                unit("cup", "US cup", Dimension.VOLUME, "0.0002365882365"),
                unit("pt", "US pint", Dimension.VOLUME, "0.000473176473"),
                unit("qt", "US quart", Dimension.VOLUME, "0.000946352946"),
                unit("gal", "US gallon", Dimension.VOLUME, "0.003785411784")),
            family("Imperial",
                unit("imp fl oz", "imperial fluid ounce", Dimension.VOLUME, "0.0000284130625"),
                unit("imp pt", "imperial pint", Dimension.VOLUME, "0.00056826125"),
                unit("imp qt", "imperial quart", Dimension.VOLUME, "0.0011365225"),
                unit("imp gal", "imperial gallon", Dimension.VOLUME, "0.00454609")),
            family("Cubic imperial / US",
                unit("in³", "cubic inch", Dimension.VOLUME, INCH * INCH * INCH),
                unit("ft³", "cubic foot", Dimension.VOLUME, FOOT * FOOT * FOOT),
                unit("yd³", "cubic yard", Dimension.VOLUME, "0.764554857984"))
        )),
        UnitCategory("Mass", listOf(
            metric("Gram", "g", "gram", Dimension.MASS, "µ m _ k", baseFactor = exact("0.001")),
            family("Tonne", unit("t", "tonne", Dimension.MASS, "1000")),
            family("Imperial / US",
                unit("gr", "grain", Dimension.MASS, "0.00006479891"),
                unit("oz", "ounce", Dimension.MASS, "0.028349523125"),
                unit("lb", "pound", Dimension.MASS, POUND),
                unit("st", "stone", Dimension.MASS, "6.35029318"),
                unit("ton", "US short ton", Dimension.MASS, "907.18474"),
                unit("long ton", "imperial long ton", Dimension.MASS, "1016.0469088")),
            family("Atomic", unit("Da", "dalton", Dimension.MASS, "1.66053906660e-27"))
        )),
        UnitCategory("Time", listOf(
            metric("Second", "s", "second", Dimension.TIME, "n µ m _"),
            family("Calendar",
                unit("min", "minute", Dimension.TIME, "60"),
                unit("h", "hour", Dimension.TIME, "3600"),
                unit("d", "day", Dimension.TIME, "86400"),
                unit("wk", "week", Dimension.TIME, "604800"),
                unit("yr", "year (365.25 days)", Dimension.TIME, "31557600"))
        )),
        UnitCategory("Speed", listOf(
            family("Metric",
                unit("m/s", "metre per second", Dimension.SPEED),
                unit("km/h", "kilometre per hour", Dimension.SPEED, Rational.of(5L, 18L))),
            family("Imperial / US",
                unit("ft/s", "foot per second", Dimension.SPEED, FOOT),
                unit("mph", "mile per hour", Dimension.SPEED, "0.44704")),
            family("Nautical", unit("kn", "knot", Dimension.SPEED, Rational.of(1852L, 3600L)))
        )),
        UnitCategory("Acceleration", listOf(
            family("Metric", unit("m/s²", "metre per second squared", Dimension.ACCELERATION)),
            family("Imperial / US", unit("ft/s²", "foot per second squared", Dimension.ACCELERATION, FOOT)),
            family("Standard gravity", unit("gₙ", "standard gravity", Dimension.ACCELERATION, STANDARD_GRAVITY))
        )),
        UnitCategory("Force", listOf(
            metric("Newton", "N", "newton", Dimension.FORCE, "m _ k M"),
            family("Other",
                unit("dyn", "dyne", Dimension.FORCE, "0.00001"),
                unit("kgf", "kilogram-force", Dimension.FORCE, STANDARD_GRAVITY),
                unit("lbf", "pound-force", Dimension.FORCE, POUND_FORCE))
        )),
        UnitCategory("Pressure", listOf(
            metric("Pascal", "Pa", "pascal", Dimension.PRESSURE, "_ h k M G"),
            metric("Bar", "bar", "bar", Dimension.PRESSURE, "m _", baseFactor = exact("100000")),
            family("Atmosphere & mercury",
                unit("atm", "standard atmosphere", Dimension.PRESSURE, ATMOSPHERE),
                unit("Torr", "torr", Dimension.PRESSURE, ATMOSPHERE / 760L),
                unit("mmHg", "millimetre of mercury", Dimension.PRESSURE, "133.322387415"),
                unit("inHg", "inch of mercury", Dimension.PRESSURE, "3386.389")),
            family("Imperial / US", unit("psi", "pound per square inch", Dimension.PRESSURE, POUND_FORCE / (INCH * INCH)))
        )),
        UnitCategory("Energy", listOf(
            metric("Joule", "J", "joule", Dimension.ENERGY, "m _ k M"),
            family("Watt-hour",
                unit("Wh", "watt-hour", Dimension.ENERGY, "3600"),
                unit("kWh", "kilowatt-hour", Dimension.ENERGY, "3600000")),
            family("Calorie",
                unit("cal", "calorie", Dimension.ENERGY, "4.184"),
                unit("kcal", "kilocalorie", Dimension.ENERGY, "4184")),
            metric("Electronvolt", "eV", "electronvolt", Dimension.ENERGY, "_ k M", baseFactor = exact("1.602176634e-19")),
            family("Imperial / US",
                unit("ft·lbf", "foot-pound", Dimension.ENERGY, FOOT_POUND_FORCE),
                unit("BTU", "British thermal unit", Dimension.ENERGY, "1055.05585262"))
        )),
        UnitCategory("Power", listOf(
            metric("Watt", "W", "watt", Dimension.POWER, "m _ k M G"),
            family("Imperial / US",
                unit("hp", "horsepower", Dimension.POWER, FOOT_POUND_FORCE * 550L),
                unit("BTU/h", "BTU per hour", Dimension.POWER, exact("1055.05585262") / 3600L))
        )),
        UnitCategory("Temperature", listOf(
            family("Temperature",
                unit("K", "kelvin", Dimension.TEMPERATURE, Rational.ONE, Rational.ZERO),
                unit("°C", "degree Celsius", Dimension.TEMPERATURE, Rational.ONE, exact("273.15")),
                unit("°F", "degree Fahrenheit", Dimension.TEMPERATURE, Rational.of(5L, 9L), Rational.of(45967L, 180L)),
                unit("°R", "degree Rankine", Dimension.TEMPERATURE, Rational.of(5L, 9L), Rational.ZERO))
        )),
        UnitCategory("Angle", listOf(
            family("Angle",
                UnitExpression("rad", "radian", Dimension.ANGLE, Value.ONE),
                UnitExpression("°", "degree", Dimension.ANGLE, PI_OVER(180L)),
                UnitExpression("′", "arcminute", Dimension.ANGLE, PI_OVER(10800L)),
                UnitExpression("″", "arcsecond", Dimension.ANGLE, PI_OVER(648000L)),
                UnitExpression("gon", "gradian", Dimension.ANGLE, PI_OVER(200L)))
        )),
        UnitCategory("Frequency", listOf(
            metric("Hertz", "Hz", "hertz", Dimension.FREQUENCY, "_ k M G"),
            family("Rotation", unit("rpm", "revolutions per minute", Dimension.FREQUENCY, Rational.of(1L, 60L)))
        )),
        UnitCategory("Electricity", listOf(
            metric("Current", "A", "ampere", Dimension.CURRENT, "µ m _ k"),
            family("Charge",
                unit("C", "coulomb", Dimension.CHARGE),
                unit("mAh", "milliampere-hour", Dimension.CHARGE, "3.6"),
                unit("Ah", "ampere-hour", Dimension.CHARGE, "3600")),
            metric("Voltage", "V", "volt", Dimension.VOLTAGE, "m _ k"),
            metric("Resistance", "Ω", "ohm", Dimension.RESISTANCE, "m _ k M"),
            metric("Conductance", "S", "siemens", Dimension.CONDUCTANCE, "µ m _"),
            metric("Capacitance", "F", "farad", Dimension.CAPACITANCE, "p n µ m _"),
            metric("Inductance", "H", "henry", Dimension.INDUCTANCE, "µ m _"),
            family("Magnetism",
                unit("T", "tesla", Dimension.MAGNETIC_FLUX_DENSITY),
                unit("G", "gauss", Dimension.MAGNETIC_FLUX_DENSITY, "0.0001"),
                unit("Wb", "weber", Dimension.MAGNETIC_FLUX))
        )),
        UnitCategory("Chemistry", listOf(
            metric("Amount of substance", "mol", "mole", Dimension.AMOUNT, "µ m _ k"),
            family("Concentration",
                unit("mol/L", "mole per litre", Dimension.CONCENTRATION, "1000"),
                unit("M", "molar", Dimension.CONCENTRATION, "1000"),
                unit("mM", "millimolar", Dimension.CONCENTRATION, "1"),
                unit("µM", "micromolar", Dimension.CONCENTRATION, "0.001")),
            family("Molar mass",
                unit("g/mol", "gram per mole", Dimension.MOLAR_MASS, "0.001"),
                unit("kg/mol", "kilogram per mole", Dimension.MOLAR_MASS))
        )),
        UnitCategory("Density", listOf(
            family("Metric",
                unit("kg/m³", "kilogram per cubic metre", Dimension.DENSITY),
                unit("g/cm³", "gram per cubic centimetre", Dimension.DENSITY, "1000"),
                unit("g/mL", "gram per millilitre", Dimension.DENSITY, "1000"),
                unit("g/L", "gram per litre", Dimension.DENSITY)),
            family("Imperial / US", unit("lb/ft³", "pound per cubic foot", Dimension.DENSITY, POUND / (FOOT * FOOT * FOOT)))
        )),
        UnitCategory("Light", listOf(
            family("Photometry",
                unit("cd", "candela", Dimension.LUMINOSITY),
                unit("lm", "lumen", Dimension.LUMINOSITY),
                unit("lx", "lux", Dimension.ILLUMINANCE))
        ))
    )

    val allUnits: List<UnitExpression> = categories.flatMap { it.units }

    private val bySymbol: Map<String, UnitExpression> = allUnits.associateBy { it.symbol }

    init {
        require(bySymbol.size == allUnits.size) { "Unit symbols must be unique" }
    }

    /** Exact, case-sensitive lookup (mm and Mm, t and T are different units). */
    fun findBySymbol(symbol: String): UnitExpression? = bySymbol[symbol]

    private fun get(symbol: String) = bySymbol.getValue(symbol)

    val RAD = get("rad")
    val DEG = get("°")
    val M = get("m")
    val KM = get("km")
    val CM = get("cm")
    val KG = get("kg")
    val SEC = get("s")
    val K = get("K")
    val CELSIUS = get("°C")
    val FAHRENHEIT = get("°F")

    // --- Output units per unit system -------------------------------------------------------

    private fun preferred(vararg symbols: String): Map<Dimension, UnitExpression> =
        symbols.map { get(it) }.associateBy { it.dimension }

    private val metricPreferred = preferred(
        "m", "m²", "L", "kg", "s", "m/s", "m/s²", "N", "Pa", "J", "W", "°C", "Hz",
        "A", "C", "V", "Ω", "S", "F", "H", "T", "Wb", "mol", "mol/L", "g/mol", "kg/m³", "cd", "lx"
    )

    private val imperialPreferred = metricPreferred + preferred(
        "ft", "ft²", "imp gal", "lb", "mph", "ft/s²", "lbf", "psi", "ft·lbf", "hp", "°F", "lb/ft³"
    )

    private val usCustomaryPreferred = imperialPreferred + preferred("gal")

    /**
     * The unit results of [dimension] are shown in: a named unit if the system has one
     * (e.g. N, psi), otherwise a product of the system's base units (e.g. kg·m²/(s·K)).
     */
    fun preferredUnit(
        dimension: Dimension,
        system: UnitSystem,
        angleUnit: AngleUnit,
        temperatureInKelvin: Boolean = false
    ): Expression {
        val angleBase = if (angleUnit == AngleUnit.DEGREES) DEG else RAD
        if (dimension == Dimension.ANGLE) return angleBase
        if (dimension == Dimension.TEMPERATURE && temperatureInKelvin) return K
        val named = when (system) {
            UnitSystem.METRIC -> metricPreferred
            UnitSystem.IMPERIAL -> imperialPreferred
            UnitSystem.US_CUSTOMARY -> usCustomaryPreferred
        }[dimension]
        if (named != null) return named

        val isMetric = system == UnitSystem.METRIC
        // Temperatures inside compound units are temperature differences: kelvin
        val bases = listOf(
            dimension.length to (if (isMetric) M else get("ft")),
            dimension.mass to (if (isMetric) KG else get("lb")),
            dimension.time to SEC,
            dimension.current to get("A"),
            dimension.temperature to K,
            dimension.amount to get("mol"),
            dimension.luminosity to get("cd"),
            dimension.angle to angleBase
        ).filter { it.first != 0 }.sortedByDescending { it.first > 0 }
        val factors = bases.map { (exponent, unit) -> if (exponent == 1) unit else Power(unit, Value(exponent.toLong())) }
        return Multiplications.of(*factors.toTypedArray())
    }
}
