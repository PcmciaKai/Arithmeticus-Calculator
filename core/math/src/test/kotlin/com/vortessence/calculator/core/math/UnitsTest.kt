package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.context.UnitSystem
import com.vortessence.calculator.core.math.expressions.Dimension
import com.vortessence.calculator.core.math.expressions.UnitRegistry
import com.vortessence.calculator.core.math.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnitsTest {
    private fun n(s: String) = NumberNode(s)
    private fun u(s: String) = UnitNode(s)
    private fun op(o: MathOp) = OperatorNode(o)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())
    private fun frac(num: MathString, den: MathString) = FractionNode(num, den)

    private fun calc(
        input: MathString,
        system: UnitSystem = UnitSystem.METRIC,
        angle: AngleUnit = AngleUnit.RADIANS,
        kelvin: Boolean = false
    ): CalculationResult = runBlocking {
        MathEngine.calculate(input, MathContext(unitSystem = system, angleUnit = angle, temperatureInKelvin = kelvin))
    }

    private val to = OperatorNode(MathOp.CONVERT)

    private fun assertResult(expected: String, res: CalculationResult) {
        assertTrue("Expected success but got ${res.issues}", res.isSuccess)
        assertEquals(expected, res.result.toString())
    }

    @Test
    fun catalogIsConsistent() {
        assertTrue(UnitRegistry.categories.size >= 15)
        for (unit in UnitRegistry.allUnits) {
            assertSame(unit, UnitRegistry.findBySymbol(unit.symbol))
            assertTrue("${unit.symbol} factor", unit.factor.evaluateNumerically() > 0.0)
        }
        // Case matters: tesla vs tonne, siemens vs second
        assertEquals(Dimension.MAGNETIC_FLUX_DENSITY, UnitRegistry.findBySymbol("T")!!.dimension)
        assertEquals(Dimension.MASS, UnitRegistry.findBySymbol("t")!!.dimension)
        assertNotEquals(UnitRegistry.findBySymbol("S"), UnitRegistry.findBySymbol("s"))
    }

    @Test
    fun mixedLengthsAreShownInTheSystemsLengthUnit() {
        val input = ms(n("5"), u("km"), op(MathOp.PLUS), n("300"), u("m"))
        assertResult("5300m", calc(input))
        val imperial = calc(input, UnitSystem.IMPERIAL)
        assertTrue(imperial.result.toString().endsWith("ft"))
        assertEquals(5300 / 0.3048, imperial.numericValue, 1e-6)
        assertEquals("ft", imperial.unit.toString())
    }

    @Test
    fun customaryInputConvertsExactlyToMetric() {
        assertResult("0.9144m", calc(ms(n("3"), u("ft"))))
        assertResult("7.570823568L", calc(ms(n("2"), u("gal"))))
        // Imperial and US gallons differ
        assertResult("2gal", calc(ms(n("2"), u("gal")), UnitSystem.US_CUSTOMARY))
        assertEquals(2 * 3.785411784 / 4.54609, calc(ms(n("2"), u("gal")), UnitSystem.IMPERIAL).numericValue, 1e-9)
    }

    @Test
    fun derivedUnitsAreRecognized() {
        // 3 N · 2 m = 6 J
        assertResult("6J", calc(ms(n("3"), u("N"), op(MathOp.MULTIPLY), n("2"), u("m"))))
        // 1 lbf / 1 in² = 1 psi
        val psi = ms(frac(ms(n("1"), u("lbf")), ms(n("1"), u("in²"))))
        assertResult("1psi", calc(psi, UnitSystem.IMPERIAL))
        assertEquals(6894.757293168361, calc(psi).numericValue, 1e-9)
        // 10 m / 2 s = 5 m/s
        assertResult("5m/s", calc(ms(frac(ms(n("10"), u("m")), ms(n("2"), u("s"))))))
        // 36 km/h in m/s
        assertResult("10m/s", calc(ms(n("36"), u("km/h"))))
        assertEquals("mph", calc(ms(n("36"), u("km/h")), UnitSystem.US_CUSTOMARY).unit.toString())
    }

    @Test
    fun temperaturesUseTheSystemsScale() {
        assertResult("68°F", calc(ms(n("20"), u("°C")), UnitSystem.IMPERIAL))
        assertResult("100°C", calc(ms(n("373.15"), u("K"))))
        assertResult("30°C", calc(ms(n("20"), u("°C"), op(MathOp.PLUS), n("50"), u("°F"))))
    }

    @Test
    fun cancellingUnitsGiveAPlainNumber() {
        val ratio = calc(ms(frac(ms(n("1"), u("m")), ms(n("1"), u("km")))))
        assertTrue(ratio.isSuccess)
        assertEquals(0.001, ratio.numericValue, 1e-15)
        assertNull(ratio.unit)
    }

    @Test
    fun anglesFollowTheAngleSetting() {
        assertResult("30°", calc(ms(n("30"), u("°")), angle = AngleUnit.DEGREES))
        val rad = calc(ms(n("180"), u("°")))
        assertTrue(rad.isSuccess)
        assertEquals("rad", rad.unit.toString())
        assertEquals(Math.PI, rad.expression.withoutUnitNumeric(), 1e-12)
    }

    @Test
    fun uncommonDimensionsAreComposedFromBaseUnits() {
        // Angular-momentum-like kg·m²/s has no named unit
        val res = calc(ms(n("3"), u("kg"), op(MathOp.MULTIPLY), ExponentNode(ms(u("m")), ms(n("2"))),
            op(MathOp.DIVIDE), u("s")))
        assertTrue(res.issues.toString(), res.isSuccess)
        assertEquals(3.0, res.numericValue, 1e-12)
        assertNotNull(res.unit)
    }

    @Test
    fun incompatibleUnitsAreAnError() {
        assertTrue(calc(ms(n("3"), u("m"), op(MathOp.PLUS), n("2"), u("s"))).issues.contains(MathIssue.INVALID_UNIT))
        assertTrue(calc(ms(u("nope"))).issues.contains(MathIssue.INVALID_UNIT))
    }

    private fun com.vortessence.calculator.core.math.expressions.Expression.withoutUnitNumeric() = evaluateNumerically()

    @Test
    fun explicitConversionKeepsTheTargetUnit() {
        val miles = calc(ms(n("5"), u("km"), to, u("mi")))
        assertTrue(miles.issues.toString(), miles.isSuccess)
        assertEquals(5000 / 1609.344, miles.numericValue, 1e-12)
        assertEquals("mi", miles.unit.toString())

        // Not re-converted to the unit system's preferred unit (ft)
        assertEquals("km", calc(ms(n("3"), u("mi"), to, u("km")), UnitSystem.IMPERIAL).unit.toString())

        assertResult("68°F", calc(ms(n("20"), u("°C"), to, u("°F"))))
        // Compound targets built from units: km ÷ h
        val speed = calc(ms(n("10"), u("m/s"), to, frac(ms(u("km")), ms(u("h")))))
        assertTrue(speed.issues.toString(), speed.isSuccess)
        assertEquals(36.0, speed.numericValue, 1e-12)
        // A plain number converted to an angle unit is in radians
        assertResult("90°", calc(ms(ConstantNode("π"), op(MathOp.DIVIDE), n("2"), to, u("°"))))
    }

    @Test
    fun invalidConversionsAreErrors() {
        assertTrue(calc(ms(n("3"), u("m"), to, u("s"))).issues.contains(MathIssue.INVALID_UNIT))
        assertTrue(calc(ms(n("3"), u("m"), to, n("2"), u("km"))).issues.contains(MathIssue.INVALID_UNIT))
        assertTrue(calc(ms(n("3"), u("m"), to, u("km"), to, u("mi"))).issues.contains(MathIssue.SYNTAX_ERROR))
    }

    @Test
    fun kelvinOptionShowsTemperaturesInKelvin() {
        assertResult("293.15K", calc(ms(n("20"), u("°C")), kelvin = true))
        assertResult("293.15K", calc(ms(n("68"), u("°F")), UnitSystem.IMPERIAL, kelvin = true))
        // An explicit conversion still wins
        assertResult("20°C", calc(ms(n("293.15"), u("K"), to, u("°C")), kelvin = true))
    }
}
