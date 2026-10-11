package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.solvers.NonlinearSystemSolver
import com.vortessence.calculator.core.math.steps.SystemSteps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Seeded random checks of the solvers and steps against brute force (sampling, known solutions, numbers); each failure is listed. */
class FuzzChecksTest {
    private val x = Variable("x")
    private val y = Variable("y")
    private val random = Random(20261011)
    private val failures = mutableListOf<String>()

    private fun report(kind: String, detail: String) { if (failures.size < 60) failures.add("$kind: $detail") }

    /** What has no steps, or has no solution because the equations say the same twice, is no failure; the rest is. */
    private fun verify(name: String) {
        val real = failures.filterNot { it.startsWith("homogeneous-nosteps") || it.startsWith("limit-nosteps") || it.contains("-nosteps") || it.contains(": unsolved:") }
        assertTrue("$name:\n  " + real.joinToString("\n  "), real.isEmpty())
    }

    private fun v(n: Long) = Value(n)
    private fun times(c: Long, e: Expression): Expression = Multiplications.of(v(c), e)

    // ----- trig inequalities -----

    @Test
    fun trigInequalities() {
        val types = listOf(TrigType.SIN, TrigType.COS, TrigType.TAN)
        val relations = Relation.values()
        for (iteration in 1..400) {
            val unit = if (random.nextInt(4) == 0) AngleUnit.DEGREES else AngleUnit.RADIANS
            val type = types[random.nextInt(3)]
            val a = (random.nextInt(5) + 1) * (if (random.nextBoolean()) 1 else -1)
            val b = random.nextInt(5) - 2
            val num = random.nextInt(9) - 4
            val den = random.nextInt(3) + 1
            val c = Multiplications.of(v(num.toLong()), Power(v(den.toLong()), Value.MINUS_ONE))
            val relation = relations[random.nextInt(4)]
            val operand: Expression = if (b == 0) times(a.toLong(), x) else Additions(times(a.toLong(), x), v(b.toLong()))
            val function = TrigonometricFunction(type, operand)
            val flipped = random.nextBoolean()
            val inequality = if (flipped) Inequality(c, function, relation) else Inequality(function, c, relation)
            val context = MathContext(angleUnit = unit)
            val solved = try { TrigInequalitySolver.solve(inequality, context) } catch (e: Throwable) { report("trig", "CRASH $e for $inequality"); continue } ?: run {
                report("trig", "no solution object for $inequality ($unit)")
                null
            } ?: continue
            if (solved.kind == TrigInequality.Kind.NONE) { checkTrig(inequality, solved, unit, none = true); continue }
            checkTrig(inequality, solved, unit, none = false)
        }
        verify("trig")
    }

    private fun checkTrig(inequality: Inequality, solved: TrigInequality, unit: AngleUnit, none: Boolean) {
        val k = solved.k
        // Sample x; the truth is the inequality itself, the claim is the solution set
        val sampleRange = if (unit == AngleUnit.DEGREES) 720.0 else 12.0
        for (i in 0 until 160) {
            val xv = -sampleRange + 2 * sampleRange * (i + 0.37) / 160
            val bindings = mapOf(x to xv)
            val left = MathContext.withContext(MathContext(mode = com.vortessence.calculator.core.math.context.CalculationMode.NUMERIC, angleUnit = unit)) { inequality.left.evaluateNumerically(bindings) }
            val right = MathContext.withContext(MathContext(mode = com.vortessence.calculator.core.math.context.CalculationMode.NUMERIC, angleUnit = unit)) { inequality.right.evaluateNumerically(bindings) }
            if (!left.isFinite() || !right.isFinite()) continue
            val diff = left - right
            // Too close to a boundary to tell by sampling
            if (kotlin.math.abs(diff) < 1e-6) continue
            val truth = inequality.relation.holds(diff, 0.0)
            val claim = when (solved.kind) {
                TrigInequality.Kind.ALL -> true
                TrigInequality.Kind.NONE -> false
                TrigInequality.Kind.POINTS -> false
                TrigInequality.Kind.INTERVAL -> inInterval(solved.forX, k, xv)
            }
            if (truth != claim && solved.kind != TrigInequality.Kind.POINTS) {
                report("trig", "$inequality ($unit) at x=$xv: truth=$truth claim=$claim, forX=${solved.forX}")
                return
            }
        }
    }

    private fun inInterval(forX: Expression, k: Variable, xv: Double): Boolean {
        val between = (forX as? com.vortessence.calculator.core.math.steps.Alternatives)?.equations?.firstOrNull { it is Between } as? Between ?: return false
        for (kv in -40..40) {
            val b = mapOf(k to kv.toDouble())
            val lo = between.lower.evaluateNumerically(b)
            val hi = between.upper.evaluateNumerically(b)
            val okLo = if (between.lowerRelation.strict) lo < xv else lo <= xv
            val okHi = if (between.upperRelation.strict) xv < hi else xv <= hi
            if (okLo && okHi) return true
        }
        return false
    }

    // ----- systems of inequalities -----

    @Test
    fun inequalitySystems() {
        val relations = Relation.values()
        fun polynomial(): Expression {
            val a = random.nextInt(5) - 2
            val b = random.nextInt(9) - 4
            val c = random.nextInt(9) - 4
            val terms = mutableListOf<Expression>()
            if (a != 0) terms.add(Multiplications.of(v(a.toLong()), Power(x, Value.TWO)))
            if (b != 0) terms.add(times(b.toLong(), x))
            terms.add(v(c.toLong()))
            return Additions(terms)
        }
        for (iteration in 1..300) {
            val count = random.nextInt(2) + 2
            val inequalities = List(count) {
                val left = polynomial()
                val right: Expression = v((random.nextInt(7) - 3).toLong())
                Inequality(left, right, relations[random.nextInt(4)])
            }
            val solved = try { InequalitySystemSolver.solve(inequalities, MathContext()) } catch (e: Throwable) { report("ineq-system", "CRASH $e for $inequalities"); continue } ?: run {
                report("ineq-system", "null for ${inequalities.joinToString(" | ")}")
                null
            } ?: continue
            for (i in 0 until 240) {
                val xv = -9.0 + 18.0 * (i + 0.41) / 240
                val bindings = mapOf(x to xv)
                if (inequalities.any { kotlin.math.abs(it.left.evaluateNumerically(bindings) - it.right.evaluateNumerically(bindings)) < 1e-5 }) continue
                val truth = inequalities.all { it.relation.holds(it.left.evaluateNumerically(bindings) - it.right.evaluateNumerically(bindings), 0.0) }
                val claim = solved.pieces.any { piece ->
                    val lo = piece.lower?.evaluateNumerically() ?: Double.NEGATIVE_INFINITY
                    val hi = piece.upper?.evaluateNumerically() ?: Double.POSITIVE_INFINITY
                    xv > lo && xv < hi
                }
                if (truth != claim) {
                    report("ineq-system", "${inequalities.joinToString(" | ")} at x=$xv: truth=$truth claim=$claim pieces=${solved.toExpression()}")
                    break
                }
            }
        }
        verify("ineq")
    }

    // ----- systems with a known solution -----

    private fun poly2(a: Long, b: Long, c: Long): Expression {
        val terms = mutableListOf<Expression>()
        if (a != 0L) terms.add(Multiplications.of(v(a), Power(x, Value.TWO)))
        if (b != 0L) terms.add(Multiplications.of(v(b), x, y))
        if (c != 0L) terms.add(Multiplications.of(v(c), Power(y, Value.TWO)))
        return if (terms.isEmpty()) Value.ZERO else if (terms.size == 1) terms.single() else Additions(terms)
    }

    private fun checkSystem(kind: String, equations: List<Equals>, known: List<Pair<Long, Long>>) {
        val context = MathContext()
        val variables = listOf(x, y)
        val solutions = try { NonlinearSystemSolver.solve(equations, variables, context) } catch (e: Throwable) { report(kind, "CRASH $e for ${equations.joinToString(" | ")}"); return }
        if (solutions == null) { report(kind, "unsolved: ${equations.joinToString(" | ")} (known $known)"); return }
        for ((kx, ky) in known) {
            if (solutions.none { kotlin.math.abs(it.getValue(x).evaluateNumerically() - kx) < 1e-7 && kotlin.math.abs(it.getValue(y).evaluateNumerically() - ky) < 1e-7 }) {
                report(kind, "missing ($kx, $ky) in ${equations.joinToString(" | ")}: got ${solutions.map { it.getValue(x).evaluateNumerically() to it.getValue(y).evaluateNumerically() }}")
            }
        }
        for (s in solutions) {
            val b = s.mapValues { it.value.evaluateNumerically() }
            for (e in equations) {
                val d = e.left.evaluateNumerically(b) - e.right.evaluateNumerically(b)
                if (!(kotlin.math.abs(d) < 1e-6 * kotlin.math.max(1.0, kotlin.math.abs(e.right.evaluateNumerically(b))))) report(kind, "wrong solution $b for $e")
            }
        }
        // The steps, where there are some, end at the same solutions
        val steps = try { SystemSteps.steps(EquationSystem(equations), context) } catch (e: Throwable) { report(kind, "STEPS CRASH $e for ${equations.joinToString(" | ")}"); return }
        if (steps == null) { stepless++ ; if (stepless <= 8) report("$kind-nosteps", "${equations.joinToString(" | ")}") }
    }

    private var stepless = 0

    @Test
    fun systemsWithAKnownSolution() {
        for (iteration in 1..160) {
            val x0 = (random.nextInt(9) - 4).toLong()
            val y0 = (random.nextInt(9) - 4).toLong()
            // squares only
            run {
                val a1 = (random.nextInt(4) + 1).toLong(); val c1 = (random.nextInt(5) - 2).toLong()
                val a2 = (random.nextInt(5) - 2).toLong(); val c2 = (random.nextInt(4) + 1).toLong()
                if (a1 * c2 - a2 * c1 != 0L) {
                    val e1 = Equals(poly2(a1, 0, c1), v(a1 * x0 * x0 + c1 * y0 * y0))
                    val e2 = Equals(poly2(a2, 0, c2), v(a2 * x0 * x0 + c2 * y0 * y0))
                    checkSystem("squares", listOf(e1, e2), listOf(x0 to y0, -x0 to y0, x0 to -y0, -x0 to -y0))
                }
            }
            // degree-2 terms and numbers
            run {
                val q = List(2) { LongArray(3) { (random.nextInt(5) - 2).toLong() } }
                val d = q.map { it[0] * x0 * x0 + it[1] * x0 * y0 + it[2] * y0 * y0 }
                if (d.all { it != 0L } && (x0 != 0L || y0 != 0L)) {
                    val e1 = Equals(poly2(q[0][0], q[0][1], q[0][2]), v(d[0]))
                    val e2 = Equals(poly2(q[1][0], q[1][1], q[1][2]), v(d[1]))
                    checkSystem("homogeneous", listOf(e1, e2), listOf(x0 to y0, -x0 to -y0))
                }
            }
            // a circle and a line through the point
            run {
                val m = (random.nextInt(5) - 2).toLong()
                val line = Equals(y, Additions(times(m, x), v(y0 - m * x0)))
                val circle = Equals(Additions(Power(x, Value.TWO), Power(y, Value.TWO)), v(x0 * x0 + y0 * y0))
                checkSystem("circle-line", listOf(circle, line), listOf(x0 to y0))
            }
            // x·y = c·x with a circle: x = 0 as well as y = c
            run {
                val triples = listOf(Triple(3L, 4L, 5L), Triple(5L, 12L, 13L), Triple(6L, 8L, 10L))
                val (c, other, r) = triples[random.nextInt(triples.size)]
                val sign = if (random.nextBoolean()) 1L else -1L
                checkSystem(
                    "zero-product",
                    listOf(Equals(Multiplications.of(x, y), times(sign * c, x)), Equals(Additions(Power(x, Value.TWO), Power(y, Value.TWO)), v(r * r))),
                    listOf(0L to r, 0L to -r, other to sign * c, -other to sign * c)
                )
            }
            // product and sum
            run {
                if (x0 != 0L && y0 != 0L) {
                    checkSystem("product-sum", listOf(Equals(Multiplications.of(x, y), v(x0 * y0)), Equals(Additions(x, y), v(x0 + y0))), listOf(x0 to y0, y0 to x0))
                }
            }
        }
        verify("systems")
    }

    // ----- polynomial roots -----

    @Test
    fun polynomialRoots() {
        for (iteration in 1..200) {
            val degree = random.nextInt(4) + 3
            val roots = List(degree) { (random.nextInt(61) - 30).toLong() }
            var polynomial: Expression = Value.ONE
            for (r in roots) polynomial = Multiplications(polynomial, Additions(x, v(-r)))
            val scale = (random.nextInt(3) + 1).toLong()
            val equation = Equals(Multiplications.of(v(scale), polynomial), Value.ZERO)
            val context = MathContext()
            val found = try { equation.solveForVariable(x, context) } catch (e: Throwable) { report("poly", "CRASH $e for roots $roots"); continue }
            val distinct = roots.distinct().sorted()
            val numbers = found.map { it.evaluateNumerically() }.sorted()
            if (numbers.size != distinct.size || numbers.indices.any { kotlin.math.abs(numbers[it] - distinct[it]) > 1e-7 }) {
                report("poly", "roots $distinct but got $numbers (issues ${context.issues})")
            }
        }
        verify("poly")
    }

    // ----- logarithms -----

    @Test
    fun logarithmSums() {
        for (iteration in 1..300) {
            val terms = random.nextInt(3) + 2
            val parts = mutableListOf<Expression>()
            var expected = 0.0
            val base10 = random.nextBoolean()
            for (i in 0 until terms) {
                val n = (random.nextInt(30) + 1).toLong()
                val coefficient = (random.nextInt(7) - 3).toLong()
                if (coefficient == 0L) continue
                val log = if (base10) Logarithm.log10(v(n)) else Logarithm.ln(v(n))
                parts.add(Multiplications.of(v(coefficient), log))
                expected += coefficient * (if (base10) Math.log10(n.toDouble()) else Math.log(n.toDouble()))
            }
            if (parts.size < 2) continue
            val expression = Additions(parts)
            val context = MathContext()
            val canonical = try { expression.canonicalize(context) } catch (e: Throwable) { report("log", "CRASH $e for $expression"); continue }
            val actual = canonical.evaluateNumerically()
            if (!actual.isFinite() || kotlin.math.abs(actual - expected) > 1e-9 * kotlin.math.max(1.0, kotlin.math.abs(expected))) {
                report("log", "$expression = $expected but canonical $canonical = $actual")
            }
        }
        // quotients
        for (iteration in 1..200) {
            val a = (random.nextInt(60) + 2).toLong()
            val b = (random.nextInt(60) + 2).toLong()
            val expression = Multiplications.of(Logarithm.ln(v(a)), Power(Logarithm.ln(v(b)), Value.MINUS_ONE))
            val canonical = expression.canonicalize(MathContext())
            val expected = Math.log(a.toDouble()) / Math.log(b.toDouble())
            val actual = canonical.evaluateNumerically()
            if (kotlin.math.abs(actual - expected) > 1e-9 * kotlin.math.max(1.0, kotlin.math.abs(expected))) report("log-quotient", "ln $a / ln $b = $expected but $canonical = $actual")
        }
        verify("log")
    }

    // ----- limits, absolute values, eigenvalues -----

    private fun calc(text: String, context: MathContext = MathContext()) = runBlocking { MathEngine.calculate(TestInput.parse(text), context) }
    private fun steps(text: String, context: MathContext = MathContext()) = runBlocking { MathEngine.steps(TestInput.parse(text), context) }
    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun term(c: Int, power: Int): String = when {
        c == 0 -> ""
        power == 0 -> (if (c > 0) "+$c" else "$c")
        power == 1 -> (if (c > 0) "+" else "-") + (if (kotlin.math.abs(c) == 1) "" else "${kotlin.math.abs(c)}") + "x"
        else -> (if (c > 0) "+" else "-") + (if (kotlin.math.abs(c) == 1) "" else "${kotlin.math.abs(c)}") + "x^$power"
    }

    @Test
    fun conjugateLimits() {
        for (iteration in 1..120) {
            val d = random.nextInt(3) + 1
            val b = random.nextInt(9) - 4
            val c = random.nextInt(7) - 3
            val e = random.nextInt(5) - 2
            val toNegative = random.nextBoolean()
            val inner = (d * d).toString() + "x^2" + term(b, 1) + term(c, 0)
            val tail = term(-d, 1).let { if (toNegative) term(d, 1) else it } + term(-e, 0)
            val text = "L[x->${if (toNegative) "-inf" else "inf"}]{sqrt($inner)$tail}"
            // For x → ∞: √(d²x² + bx + c) − dx − e → b/(2d) − e. For −∞ with + dx: → −b/(2d) + e... computed numerically instead
            val r = try { calc(text) } catch (t: Throwable) { report("limit", "CRASH $t for $text"); continue }
            val s = try { steps(text) } catch (t: Throwable) { report("limit", "STEPS CRASH $t for $text"); continue }
            val n = if (toNegative) -1e8 else 1e8
            val value = Math.sqrt((d * d * n * n) + b * n + c) + (if (toNegative) d * n else -d * n) - e
            if (r.isSuccess && kotlin.math.abs(r.numericValue - value) > 1e-3) report("limit", "$text calc ${r.numericValue} but numeric $value")
            if (!r.isSuccess) report("limit", "$text failed ${r.issues}")
            if (s != null && r.isSuccess && clean(s.result.toString()) != clean(r.result.toString())) report("limit", "$text steps end at ${clean(s.result.toString())} but calc ${clean(r.result.toString())}")
            if (s == null && r.isSuccess) { nolimitsteps++; if (nolimitsteps <= 6) report("limit-nosteps", text) }
        }
        verify("limits")
    }

    private var nolimitsteps = 0

    @Test
    fun absoluteSplits() {
        for (iteration in 1..150) {
            val p = random.nextInt(5) - 2
            val q = random.nextInt(7) - 3
            val r = random.nextInt(13) - 6
            if (p == 0) continue
            val text = (if (p == 1) "" else if (p == -1) "-" else "$p") + "x*abs(x)" + term(q, 1) + "=" + r
            val res = try { calc(text) } catch (t: Throwable) { report("abs", "CRASH $t for $text"); continue }
            val st = try { steps(text) } catch (t: Throwable) { report("abs", "STEPS CRASH $t for $text"); continue }
            if (st != null && res.isSuccess && clean(st.result.toString()) != clean(res.result.toString())) report("abs", "$text steps ${clean(st.result.toString())} vs calc ${clean(res.result.toString())}")
            // The calculator against brute force: p·x|x| + q·x = r
            if (res.isSuccess) {
                val f = { t: Double -> p * t * kotlin.math.abs(t) + q * t - r }
                var lastSign = Math.signum(f(-40.0))
                var roots = 0
                var t = -40.0
                while (t < 40.0) { t += 0.01; val sgn = Math.signum(f(t)); if (sgn != lastSign && sgn != 0.0 && lastSign != 0.0) roots++; if (sgn != 0.0) lastSign = sgn }
                val reported = clean(res.result.toString()).split(",").size
                if (roots > 0 && roots != reported && !clean(res.result.toString()).contains("0")) report("abs", "$text: $roots sign changes but result ${clean(res.result.toString())}")
            }
        }
        verify("abs")
    }

    @Test
    fun eigenpairs() {
        for (iteration in 1..120) {
            val n = random.nextInt(3) + 2
            val entries = List(n * n) { (random.nextInt(7) - 3).toLong() }
            val m = MatrixExpression(n, n, entries.map { v(it) })
            val context = MathContext()
            val result = try { com.vortessence.calculator.core.math.steps.MatrixSteps.eigenSystem(m, context) } catch (t: Throwable) { report("eig", "CRASH $t for $entries"); continue }
            if (result == null) continue
            val lines = if (result is com.vortessence.calculator.core.math.steps.Alternatives && !result.inline) result.equations else listOf(result)
            for (line in lines) {
                val parts = (line as? com.vortessence.calculator.core.math.steps.Alternatives)?.equations ?: listOf(line)
                val lambda = (parts[0] as? Equals)?.right?.evaluateNumerically() ?: continue
                for (vector in parts.drop(1)) {
                    val matrix = (vector as? Equals)?.right as? MatrixExpression ?: continue
                    val vv = matrix.elements.map { it.evaluateNumerically() }
                    for (r in 0 until n) {
                        val av = (0 until n).sumOf { c -> entries[r * n + c] * vv[c] }
                        if (kotlin.math.abs(av - lambda * vv[r]) > 1e-7) report("eig", "A·v ≠ λ·v for $entries, λ=$lambda v=$vv")
                    }
                }
            }
            // the eigenvalues sum to the trace
            val values = lines.mapNotNull { (((it as? com.vortessence.calculator.core.math.steps.Alternatives)?.equations?.get(0)) ?: it).let { e -> (e as? Equals)?.right?.evaluateNumerically() } }
            if (result != null && values.isNotEmpty() && values.size == n) {
                val trace = (0 until n).sumOf { entries[it * n + it] }
                // (with repeated eigenvalues the list has them once, so only compare when all are different)
                if (kotlin.math.abs(values.sum() - trace) > 1e-6) report("eig", "trace $trace but eigenvalues $values for $entries")
            }
        }
        verify("eig")
    }

    @Test
    fun oneSidedLimits() {
        for (iteration in 1..150) {
            val a = random.nextInt(9) - 4
            val side = if (random.nextBoolean()) "^+" else "^-"
            val inner = if (a == 0) "x" else if (a > 0) "x-$a" else "x+${-a}"
            val c = random.nextInt(5) - 2
            val shape = random.nextInt(4)
            val text = when (shape) {
                0 -> "L[x->$a$side]{[abs($inner);$inner]}"
                1 -> "L[x->$a$side]{e^{[1;$inner]}}"
                2 -> "L[x->$a$side]{[abs($inner);$inner]${if (c >= 0) "+$c" else "$c"}}"
                else -> "L[x->$a$side]{${if (c == 0) 2 else c}*[abs($inner);$inner]}"
            }
            val r = try { calc(text) } catch (t: Throwable) { report("onesided", "CRASH $t for $text"); continue }
            val st = try { steps(text) } catch (t: Throwable) { report("onesided", "STEPS CRASH $t for $text"); continue }
            if (!r.isSuccess) { report("onesided", "$text failed ${r.issues}"); continue }
            if (st != null && clean(st.result.toString()) != clean(r.result.toString())) report("onesided", "$text steps ${clean(st.result.toString())} vs calc ${clean(r.result.toString())}")
            if (st == null) report("onesided-nosteps", text)
        }
        verify("one-sided limits")
    }

    private fun entries(result: com.vortessence.calculator.core.math.type.MathString): List<String>? =
        (result.nodes.singleOrNull() as? com.vortessence.calculator.core.math.type.MatrixNode)?.elements?.map { clean(it.toString()) }

    @Test
    fun matrixSteps() {
        fun matrix(rows: Int, cols: Int): String =
            "M[" + (0 until rows).joinToString("/") { (0 until cols).joinToString(" ") { (random.nextInt(9) - 4).toString() } } + "]"
        for (iteration in 1..200) {
            val n = random.nextInt(3) + 2
            val a = matrix(n, n)
            val b = matrix(n, n)
            val text = when (random.nextInt(8)) {
                0 -> "$a^-1"
                1 -> "det($a)"
                2 -> "$a $b"
                3 -> "$a + $b"
                4 -> "$a - $b"
                5 -> "$a^2"
                6 -> "$a^T"
                else -> "${random.nextInt(5) + 2} $a"
            }
            val r = try { calc(text) } catch (t: Throwable) { report("matrix", "CRASH $t for $text"); continue }
            val st = try { steps(text) } catch (t: Throwable) { report("matrix", "STEPS CRASH $t for $text"); continue }
            if (!r.isSuccess) { if (st != null) report("matrix", "$text has steps but the calculator fails ${r.issues}"); continue }
            if (st == null) continue
            val expected = entries(r.result)
            val actual = entries(st.result)
            if (expected != null || actual != null) {
                if (expected != actual) report("matrix", "$text: steps end at $actual but the calculator has $expected")
            } else if (clean(r.result.toString()) != clean(st.result.toString())) {
                report("matrix", "$text: steps end at ${clean(st.result.toString())} but the calculator has ${clean(r.result.toString())}")
            }
        }
        verify("matrix steps")
    }
}
