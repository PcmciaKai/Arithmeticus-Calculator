package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.steps.Alternatives
import com.vortessence.calculator.core.math.steps.EquationSteps
import com.vortessence.calculator.core.math.steps.TextExpression
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/**
 * Seeded random checks of the reciprocal and hyperbolic functions, and of what was built on them (general solutions,
 * inequalities, identities), against brute force: sampling, difference quotients and numeric integration.
 */
class ReciprocalFuzzTest {
    private val x = Variable("x")
    private val random = Random(20261012)
    private val failures = mutableListOf<String>()

    private fun report(kind: String, detail: String) { if (failures.size < 60) failures.add("$kind: $detail") }

    private fun verify(name: String) = assertTrue("$name:\n  " + failures.joinToString("\n  "), failures.isEmpty())

    private fun v(n: Long) = Value(n)

    private fun numeric(e: Expression, unit: AngleUnit, bindings: Map<Variable, Double>): Double =
        MathContext.withContext(MathContext(mode = CalculationMode.NUMERIC, angleUnit = unit)) { e.evaluateNumerically(bindings) }

    private val trigTypes = listOf(TrigType.SIN, TrigType.COS, TrigType.TAN, TrigType.SEC, TrigType.CSC, TrigType.COT)

    /** a·x + b, with a ≠ 0. */
    private fun linear(a: Int, b: Int): Expression =
        if (b == 0) Multiplications.of(v(a.toLong()), x) else Additions(Multiplications.of(v(a.toLong()), x), v(b.toLong()))

    private fun nonZero(range: Int): Int {
        var a = 0
        while (a == 0) a = random.nextInt(2 * range + 1) - range
        return a
    }

    private fun fraction(num: Int, den: Int): Expression = Multiplications.of(v(num.toLong()), Power(v(den.toLong()), Value.MINUS_ONE))

    // ----- inequalities of all six trigonometric functions -----

    /** The pieces of a solution set: intervals Between(…) and single points x = …, whatever the nesting of the alternatives. */
    private fun piecesOf(e: Expression): List<Expression> = when (e) {
        is Alternatives -> e.equations.flatMap { piecesOf(it) }
        is TextExpression -> emptyList()
        else -> listOf(e)
    }

    private fun contains(pieces: List<Expression>, k: Variable, xv: Double): Boolean {
        for (piece in pieces) {
            for (kv in -60..60) {
                val b = mapOf(k to kv.toDouble())
                when (piece) {
                    is Between -> {
                        val lo = piece.lower.evaluateNumerically(b)
                        val hi = piece.upper.evaluateNumerically(b)
                        val okLo = if (piece.lowerRelation.strict) lo < xv else lo <= xv
                        val okHi = if (piece.upperRelation.strict) xv < hi else xv <= hi
                        if (okLo && okHi) return true
                    }
                    is Equals -> if (abs(piece.right.evaluateNumerically(b) - xv) < 1e-9) return true
                    else -> {}
                }
            }
        }
        return false
    }

    @Test
    fun inequalitiesOfAllSixFunctions() {
        val relations = Relation.values()
        var solved = 0
        for (iteration in 1..900) {
            val unit = if (random.nextInt(4) == 0) AngleUnit.DEGREES else AngleUnit.RADIANS
            val type = trigTypes[random.nextInt(trigTypes.size)]
            val function = TrigonometricFunction(type, linear(nonZero(4), random.nextInt(5) - 2))
            // Constants of both signs, also 0 and ones between −1 and 1 (nothing there for sec and csc)
            val c = fraction(random.nextInt(13) - 6, random.nextInt(3) + 1)
            val relation = relations[random.nextInt(4)]
            val inequality = if (random.nextBoolean()) Inequality(c, function, relation) else Inequality(function, c, relation)
            val result = try {
                TrigInequalitySolver.solve(inequality, MathContext(angleUnit = unit))
            } catch (e: Throwable) {
                report("inequality", "CRASH $e for $inequality ($unit)")
                continue
            }
            if (result == null) { report("inequality", "no solution object for $inequality ($unit)"); continue }
            solved++
            val pieces = piecesOf(result.forX)
            val range = if (unit == AngleUnit.DEGREES) 720.0 else 12.0
            for (i in 0 until 240) {
                val xv = -range + 2 * range * (i + 0.37) / 240
                val left = numeric(inequality.left, unit, mapOf(x to xv))
                val right = numeric(inequality.right, unit, mapOf(x to xv))
                if (!left.isFinite() || !right.isFinite()) continue
                val diff = left - right
                // Too close to where it is decided, or at a pole where it is huge, to tell by sampling
                if (abs(diff) < 1e-5 || abs(left) > 1e6 || abs(right) > 1e6) continue
                val truth = inequality.relation.holds(diff, 0.0)
                val claim = when (result.kind) {
                    TrigInequality.Kind.ALL -> true
                    TrigInequality.Kind.NONE -> false
                    else -> contains(pieces, result.k, xv)
                }
                if (truth != claim) {
                    report("inequality", "$inequality ($unit) at x=$xv: truth=$truth claim=$claim, ${result.forX}")
                    break
                }
            }
            // A bound where the two sides are equal belongs to the solution exactly if the relation is ≤ or ≥; a pole never does
            if (result.kind == TrigInequality.Kind.INTERVAL || result.kind == TrigInequality.Kind.POINTS) {
                fun checkBound(bound: Expression, closed: Boolean) {
                    for (kv in -2..2) {
                        val b = mapOf(result.k to kv.toDouble())
                        val xv = bound.evaluateNumerically(b)
                        val left = numeric(inequality.left, unit, mapOf(x to xv))
                        val right = numeric(inequality.right, unit, mapOf(x to xv))
                        if (!left.isFinite() || !right.isFinite() || abs(left) > 1e6 || abs(right) > 1e6) continue
                        // Only where the sides are equal: that is a bound of the solution, not a period boundary
                        if (abs(left - right) > 1e-9 * max(1.0, abs(left))) continue
                        if (closed == inequality.relation.strict) {
                            report("inequality", "$inequality ($unit): bound $bound (k=$kv, x=$xv) is ${if (closed) "closed" else "open"} for ${inequality.relation}, ${result.forX}")
                            return
                        }
                    }
                }
                for (piece in pieces) when (piece) {
                    is Between -> {
                        checkBound(piece.lower, !piece.lowerRelation.strict)
                        checkBound(piece.upper, !piece.upperRelation.strict)
                    }
                    is Equals -> checkBound(piece.right, true)
                    else -> {}
                }
            }
        }
        assertTrue("only $solved inequalities were solved", solved > 500)
        verify("inequalities")
    }

    // ----- general solutions of equations -----

    @Test
    fun generalSolutionsAreSolutionsAndAreAllOfThem() {
        var periodic = 0
        for (iteration in 1..500) {
            val unit = if (random.nextInt(4) == 0) AngleUnit.DEGREES else AngleUnit.RADIANS
            val type = trigTypes[random.nextInt(trigTypes.size)]
            val function = TrigonometricFunction(type, linear(nonZero(3), random.nextInt(5) - 2))
            val c = fraction(random.nextInt(13) - 6, random.nextInt(3) + 1)
            val equation = Equals(function, c)
            val context = MathContext(angleUnit = unit)
            val result = try {
                EquationSteps.periodicSolutions(equation, x, context)
            } catch (e: Throwable) {
                report("general", "CRASH $e for $equation ($unit)")
                continue
            } as? Alternatives ?: continue
            periodic++
            val branches = result.equations.filterIsInstance<Equals>()
            val kName = (result.equations.last() as TextExpression).text.substringBefore(" ")
            val kVariable = Variable(kName)
            // Every member of the family solves the equation
            for (branch in branches) for (kv in -3..3) {
                val xv = branch.right.evaluateNumerically(mapOf(kVariable to kv.toDouble()))
                val left = numeric(function, unit, mapOf(x to xv))
                val right = numeric(c, unit, emptyMap())
                if (!left.isFinite() || abs(left) > 1e8) { report("general", "$equation ($unit): x=$xv from ${branch.right} is a pole"); continue }
                if (abs(left - right) > 1e-6 * max(1.0, abs(right))) report("general", "$equation ($unit): x=$xv from ${branch.right} gives $left, not $right")
            }
            // …and every root that brute force finds is in the family
            val range = if (unit == AngleUnit.DEGREES) 540.0 else 10.0
            val steps = 4000
            var previousX = -range
            var previous = numeric(function, unit, mapOf(x to previousX)) - numeric(c, unit, emptyMap())
            for (i in 1..steps) {
                val xv = -range + 2 * range * i / steps
                val current = numeric(function, unit, mapOf(x to xv)) - numeric(c, unit, emptyMap())
                if (previous.isFinite() && current.isFinite() && previous * current < 0 && abs(previous) < 50 && abs(current) < 50) {
                    var lo = previousX
                    var hi = xv
                    var fLo = previous
                    repeat(60) {
                        val mid = (lo + hi) / 2
                        val f = numeric(function, unit, mapOf(x to mid)) - numeric(c, unit, emptyMap())
                        if (fLo * f <= 0) hi = mid else { lo = mid; fLo = f }
                    }
                    val root = (lo + hi) / 2
                    val inFamily = branches.any { branch ->
                        (-60..60).any { kv -> abs(branch.right.evaluateNumerically(mapOf(kVariable to kv.toDouble())) - root) < 1e-5 * max(1.0, abs(root)) }
                    }
                    if (!inFamily) report("general", "$equation ($unit): root $root is not in ${result.equations}")
                }
                previousX = xv
                previous = current
            }
        }
        assertTrue("only $periodic equations had a general solution", periodic > 250)
        verify("general solutions")
    }

    // ----- identities and cancellation never change the value -----

    private val pool: List<(Expression) -> Expression> = buildList {
        for (t in trigTypes) add { u -> TrigonometricFunction(t, u) }
        for (t in listOf(HyperbolicType.SINH, HyperbolicType.COSH, HyperbolicType.TANH, HyperbolicType.SECH, HyperbolicType.CSCH, HyperbolicType.COTH)) {
            add { u -> HyperbolicFunction(t, u) }
        }
    }

    private fun randomBlock(): Expression {
        val u = listOf<Expression>(x, Multiplications.of(v(2), x), Additions(x, v(1)))[random.nextInt(3)]
        val function = pool[random.nextInt(pool.size)](u)
        val exponent = listOf(1L, 2L, 2L, -1L, -2L)[random.nextInt(5)]
        val power = if (exponent == 1L) function else Power(function, v(exponent))
        val coefficient = random.nextInt(5) - 2
        return if (coefficient == 0 || coefficient == 1) power else Multiplications.of(v(coefficient.toLong()), power)
    }

    private fun randomExpression(): Expression {
        fun product(): Expression = if (random.nextInt(3) == 0) Multiplications.of(randomBlock(), randomBlock()) else randomBlock()
        // Sums of the same two functions are more likely than chance, so that the identities are met
        return when (random.nextInt(3)) {
            0 -> Additions(product(), product())
            1 -> {
                val u = listOf<Expression>(x, Multiplications.of(v(2), x))[random.nextInt(2)]
                val pair = listOf(
                    TrigType.SEC to TrigType.TAN, TrigType.CSC to TrigType.COT, TrigType.SIN to TrigType.COS
                )[random.nextInt(3)]
                val c = (random.nextInt(4) + 1).toLong()
                val a = Multiplications.of(v(c), Power(TrigonometricFunction(pair.first, u), Value.TWO))
                val b = Multiplications.of(v(if (pair.first == TrigType.SIN) c else -c), Power(TrigonometricFunction(pair.second, u), Value.TWO))
                if (random.nextBoolean()) Additions(a, b, randomBlock()) else Additions(b, a)
            }
            else -> Multiplications.of(randomBlock(), randomBlock(), randomBlock())
        }
    }

    @Test
    fun simplifyingNeverChangesTheValue() {
        val points = listOf(0.31, 0.77, 1.19, -0.63, 0.42, 2.4, -1.3)
        var compared = 0
        for (iteration in 1..3000) {
            val expression = randomExpression()
            val context = MathContext(mode = CalculationMode.EXACT)
            val canonical = try { expression.canonicalize(context) } catch (e: Throwable) {
                report("identity", "CRASH $e for $expression"); continue
            }
            if (context.shouldStop) continue
            for (xv in points) {
                val before = numeric(expression, AngleUnit.RADIANS, mapOf(x to xv))
                val after = numeric(canonical, AngleUnit.RADIANS, mapOf(x to xv))
                if (!before.isFinite() || !after.isFinite() || abs(before) > 1e6) continue
                compared++
                if (abs(before - after) > 1e-7 * max(1.0, abs(before))) {
                    report("identity", "$expression = $before at x=$xv, but simplified $canonical = $after")
                    break
                }
            }
        }
        assertTrue("only $compared values compared", compared > 5000)
        verify("simplification")
    }

    // ----- derivatives of compositions -----

    @Test
    fun derivativesOfCompositionsAgreeWithDifferenceQuotients() {
        val inner = listOf<Pair<String, Expression>>(
            "2x+0.3" to Additions(Multiplications.of(v(2), x), Value(3L, 10L)),
            "x²" to Power(x, Value.TWO),
            "x/3+1" to Additions(Multiplications.of(Value(1L, 3L), x), v(1)),
            "1/x" to Power(x, Value.MINUS_ONE)
        )
        val functions: List<Pair<String, (Expression) -> Expression>> =
            trigTypes.map { t -> t.symbol to { u: Expression -> TrigonometricFunction(t, u) as Expression } } +
                TrigType.entries.filter { it.isInverse }.map { t -> t.symbol to { u: Expression -> TrigonometricFunction(t, u) as Expression } } +
                HyperbolicType.entries.map { t -> t.symbol to { u: Expression -> HyperbolicFunction(t, u) as Expression } }
        var checked = 0
        for ((functionName, make) in functions) for ((innerName, g) in inner) {
            val body = make(g)
            val context = MathContext(mode = CalculationMode.EXACT)
            val derivative = Derivative(body, x).canonicalize(context)
            if (context.shouldStop && context.issues.any { it.name == "CANT_DIFFERENTIATE" }) {
                report("derivative", "$functionName($innerName) cannot be differentiated")
                continue
            }
            if (context.shouldStop) continue
            for (xv in listOf(0.45, 0.9, 1.35, -0.7, -1.6, 2.2)) {
                val h = 1e-6
                val f = { t: Double -> numeric(body, AngleUnit.RADIANS, mapOf(x to t)) }
                val y0 = f(xv - h)
                val y1 = f(xv + h)
                val center = f(xv)
                if (!y0.isFinite() || !y1.isFinite() || !center.isFinite() || abs(center) > 1e3) continue
                val expected = (y1 - y0) / (2 * h)
                val actual = numeric(derivative, AngleUnit.RADIANS, mapOf(x to xv))
                if (!actual.isFinite() || abs(expected) > 1e4) continue
                checked++
                if (abs(expected - actual) > 1e-4 * max(1.0, abs(expected))) {
                    report("derivative", "d/dx $functionName($innerName) at x=$xv: difference quotient $expected, derivative $derivative = $actual")
                    break
                }
            }
        }
        assertTrue("only $checked values compared", checked > 300)
        verify("derivatives")
    }

    // ----- definite integrals -----

    private fun simpson(f: (Double) -> Double, lo: Double, hi: Double, n: Int = 2000): Double {
        val h = (hi - lo) / n
        var sum = f(lo) + f(hi)
        for (i in 1 until n) sum += f(lo + i * h) * (if (i % 2 == 1) 4 else 2)
        return sum * h / 3
    }

    @Test
    fun definiteIntegralsAgreeWithNumericIntegration() {
        fun trig(t: TrigType) = { u: Expression -> TrigonometricFunction(t, u, AngleUnit.RADIANS) as Expression }
        fun hyp(t: HyperbolicType) = { u: Expression -> HyperbolicFunction(t, u) as Expression }
        fun squared(make: (Expression) -> Expression) = { u: Expression -> Power(make(u), Value.TWO) as Expression }
        fun product(first: (Expression) -> Expression, second: (Expression) -> Expression) = { u: Expression -> Multiplications.of(first(u), second(u)) }
        val integrands: List<Pair<String, (Expression) -> Expression>> = listOf(
            "sec" to trig(TrigType.SEC), "csc" to trig(TrigType.CSC), "cot" to trig(TrigType.COT),
            "sec²" to squared(trig(TrigType.SEC)), "csc²" to squared(trig(TrigType.CSC)), "cot²" to squared(trig(TrigType.COT)),
            "sec·tan" to product(trig(TrigType.SEC), trig(TrigType.TAN)), "csc·cot" to product(trig(TrigType.CSC), trig(TrigType.COT)),
            "sinh" to hyp(HyperbolicType.SINH), "cosh" to hyp(HyperbolicType.COSH), "tanh" to hyp(HyperbolicType.TANH),
            "coth" to hyp(HyperbolicType.COTH), "sech" to hyp(HyperbolicType.SECH), "csch" to hyp(HyperbolicType.CSCH),
            "sinh²" to squared(hyp(HyperbolicType.SINH)), "cosh²" to squared(hyp(HyperbolicType.COSH)), "tanh²" to squared(hyp(HyperbolicType.TANH)),
            "sech²" to squared(hyp(HyperbolicType.SECH)), "csch²" to squared(hyp(HyperbolicType.CSCH)), "coth²" to squared(hyp(HyperbolicType.COTH)),
            "sech·tanh" to product(hyp(HyperbolicType.SECH), hyp(HyperbolicType.TANH)), "csch·coth" to product(hyp(HyperbolicType.CSCH), hyp(HyperbolicType.COTH))
        )
        var checked = 0
        for ((name, make) in integrands) for (iteration in 1..12) {
            val a = random.nextInt(3) + 1
            val b = random.nextInt(7) - 3
            val lo = 0.2 + random.nextInt(4) * 0.1
            val hi = lo + 0.3 + random.nextInt(4) * 0.2
            val body = make(linear(a, b))
            val f = { t: Double -> numeric(body, AngleUnit.RADIANS, mapOf(x to t)) }
            // No poles inside: the values stay moderate over the whole interval
            val samples = (0..400).map { f(lo + (hi - lo) * it / 400) }
            if (samples.any { !it.isFinite() || abs(it) > 200 }) continue
            val context = MathContext(mode = CalculationMode.EXACT)
            val integral = Integral(Value(Math.round(lo * 10), 10L), Value(Math.round(hi * 10), 10L), body, x)
            // lo and hi are tenths: the same numbers as in the integral
            val lower = Math.round(lo * 10) / 10.0
            val upper = Math.round(hi * 10) / 10.0
            val result = integral.canonicalize(context)
            if (context.shouldStop) { report("integral", "$name(${linear(a, b)}) over [$lower, $upper]: ${context.issues}"); continue }
            val actual = numeric(result, AngleUnit.RADIANS, emptyMap())
            val expected = simpson(f, lower, upper)
            checked++
            if (abs(actual - expected) > 1e-6 * max(1.0, abs(expected))) {
                report("integral", "∫ $name(${linear(a, b)}) over [$lower, $upper]: exact $result = $actual, Simpson $expected")
            }
        }
        assertTrue("only $checked integrals compared", checked > 100)
        verify("integrals")
    }
}
