package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.context.UnitSystem
import com.vortessence.calculator.core.math.expressions.Dimension
import com.vortessence.calculator.core.math.expressions.UnitRegistry
import com.vortessence.calculator.core.math.type.*
import kotlin.math.PI
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
        angle: AngleUnit = AngleUnit.RADIANS
    ): CalculationResult = runBlocking {
        MathEngine.calculate(input, MathContext(unitSystem = system, angleUnit = angle))
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
        assertResult("0.007570823568m³", calc(ms(n("2"), u("gal"))))
        // Litres on request
        assertResult("7.570823568L", calc(ms(n("2"), u("gal"), OperatorNode(MathOp.CONVERT), u("L"))))
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
    fun temperaturesAreShownInKelvinInEverySystem() {
        assertResult("293.15K", calc(ms(n("20"), u("°C"))))
        assertResult("293.15K", calc(ms(n("20"), u("°C")), UnitSystem.IMPERIAL))
        assertResult("293.15K", calc(ms(n("68"), u("°F")), UnitSystem.US_CUSTOMARY))
        assertResult("373.15K", calc(ms(n("373.15"), u("K"))))
        // Two absolute temperatures add up in kelvin: 293.15 K + 283.15 K = 576.3 K
        assertResult("576.3K", calc(ms(n("20"), u("°C"), op(MathOp.PLUS), n("50"), u("°F"))))
        // An explicit conversion still gives °C or °F
        assertResult("20°C", calc(ms(n("293.15"), u("K"), to, u("°C"))))
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
        assertEquals(PI, rad.expression.withoutUnitNumeric(), 1e-12)
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
    fun idealGasLawWithCelsiusAndGasConstant() {
        // (2 mol · R · 25 °C) / 1.013 bar ≈ 48.94 L, the example that exposed the bugs
        val input = ms(frac(
            ms(n("2"), u("mol"), op(MathOp.MULTIPLY), ConstantNode("R"), op(MathOp.MULTIPLY), n("25"), u("°C")),
            ms(n("1.013"), u("bar"))
        ))
        val res = calc(input)
        assertTrue(res.issues.toString(), res.isSuccess)
        assertEquals("m³", res.unit.toString())
        assertEquals(2 * 8.31446261815324 * 298.15 / 101300, res.numericValue, 1e-12)
    }

    @Test
    fun physicalConstantsCarryUnits() {
        val r = calc(ms(ConstantNode("R")))
        assertEquals(8.31446261815324, r.numericValue, 1e-15)
        // J/(mol·K); the order of the factors in the denominator does not matter
        assertTrue(r.unit.toString(), r.unit.toString() in setOf("(J)/(molK)", "(J)/(Kmol)"))
        // m_e · c² is an energy
        val energy = calc(ms(ConstantNode("m_e"), op(MathOp.MULTIPLY), ExponentNode(ms(ConstantNode("c")), ms(n("2")))))
        assertEquals("J", energy.unit.toString())
        assertEquals(8.1871057769e-14, energy.numericValue, 1e-22)
    }

    @Test
    fun chemistryAndElectromagneticConstants() {
        // F = N_A · e
        val faraday = calc(ms(ConstantNode("F")))
        assertTrue(faraday.issues.toString(), faraday.isSuccess)
        assertEquals(96485.33212, faraday.numericValue, 1e-6)
        // 1 / (4π ε₀) = k_e
        val coulomb = calc(ms(FractionNode(ms(n("1")), ms(n("4"), ConstantNode("π"), ConstantNode("ε₀")))))
        assertTrue(coulomb.issues.toString(), coulomb.isSuccess)
        assertEquals(8.9875517923e9, coulomb.numericValue, 1e0)
        for (name in listOf("m_n", "m_u", "V_m", "μ₀", "k_e", "α", "a₀", "R_∞", "μ_B", "σ", "b")) {
            val res = calc(ms(ConstantNode(name)))
            assertTrue("$name: ${res.issues}", res.isSuccess)
        }
    }

    @Test
    fun temperatureDifferencesAndOffsets() {
        // A difference of 60 °C is a difference of 60 K, in every unit system
        assertResult("60K", calc(ms(n("80"), u("°C"), op(MathOp.MINUS), n("20"), u("°C"))))
        assertResult("60K", calc(ms(n("80"), u("°C"), op(MathOp.MINUS), n("20"), u("°C")), UnitSystem.IMPERIAL))
        // 50 °F = 10 °C, so the difference is 70 K (the sign is applied after the offset)
        assertResult("70K", calc(ms(n("80"), u("°C"), op(MathOp.MINUS), n("50"), u("°F"))))
        // Temperature plus a difference stays a temperature
        assertResult("298.15K", calc(ms(n("20"), u("°C"), op(MathOp.PLUS), n("5"), u("K"))))
        assertResult("298.15K", calc(ms(n("20"), u("°C"), op(MathOp.PLUS), n("5"), u("Δ°C"))))
        // Averages of temperatures work
        val average = ms(frac(ms(n("20"), u("°C"), op(MathOp.PLUS), n("30"), u("°C")), ms(n("2"))))
        assertResult("298.15K", calc(average))
        // Negative temperatures stay temperatures
        assertResult("268.15K", calc(ms(op(MathOp.MINUS), n("5"), u("°C"))))
        // 1/T uses the absolute temperature
        val inverse = calc(ms(frac(ms(n("1")), ms(n("25"), u("°C")))))
        assertEquals(1 / 298.15, inverse.numericValue, 1e-15)
    }

    @Test
    fun compoundResultsUseANamedUnitWherePossible() {
        // J·s instead of kg·m²/s
        val action = calc(ms(n("3"), u("J"), op(MathOp.MULTIPLY), n("2"), u("s")))
        assertEquals("Js", action.unit.toString())
        // Molar volume in m³/mol
        val molarVolume = calc(ms(frac(ms(n("22.4"), u("L")), ms(n("1"), u("mol")))))
        assertEquals("(m³)/(mol)", molarVolume.unit.toString())
        assertEquals(0.0224, molarVolume.numericValue, 1e-15)
    }

    @Test
    fun unitPowersWorkInNumericMode() {
        // Numeric mode used to reject m², 1/K and therefore R's J/(mol·K)
        val gasLaw = ms(frac(
            ms(n("2"), u("mol"), op(MathOp.MULTIPLY), ConstantNode("R"), op(MathOp.MULTIPLY), n("25"), u("°C")),
            ms(n("1.013"), u("bar"))
        ))
        val res = runBlocking {
            MathEngine.calculate(gasLaw, MathContext(mode = com.vortessence.calculator.core.math.context.CalculationMode.NUMERIC))
        }
        assertTrue(res.issues.toString(), res.isSuccess)
        assertEquals("m³", res.unit.toString())
        assertEquals(2 * 8.31446261815324 * 298.15 / 101300, res.numericValue, 1e-12)
    }

    @Test
    fun testUnitDimensionFindsCategoryForConversion() {
        val categories = UnitRegistry.categories.map { it.name }
        fun locate(input: MathString, ctx: MathContext = MathContext()) =
            MathEngine.unitDimension(input, ctx)?.let { UnitRegistry.locate(it) }

        // 2 m × 3 m × 4 m is a volume, even though no single unit node says so
        val volume = locate(ms(n("2"), u("m"), op(MathOp.MULTIPLY), n("3"), u("m"), op(MathOp.MULTIPLY), n("4"), u("m")))
        assertEquals("Volume", categories[volume!!.category])

        // Several dimensions share a category: voltage is a family further down "Electricity"
        val voltage = locate(ms(n("5"), u("V")))!!
        assertEquals("Electricity", categories[voltage.category])
        assertEquals("Voltage", UnitRegistry.categories[voltage.category].families[voltage.family].name)

        // Ans and defined variables count with the unit of their value
        val ans = MathContext(ans = ms(n("3"), u("km")))
        assertEquals("Length", categories[locate(ms(AnsNode), ans)!!.category])

        // No unit, or units that do not fit together
        assertNull(locate(ms(n("5"))))
        assertNull(locate(ms(n("5"), u("m"), op(MathOp.PLUS), n("2"), u("s"))))
    }
}
