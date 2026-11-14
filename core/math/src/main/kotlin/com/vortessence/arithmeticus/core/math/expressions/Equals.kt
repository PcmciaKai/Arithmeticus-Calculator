package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.numbers.Rational
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.solvers.Function1D
import com.vortessence.arithmeticus.core.math.solvers.NumericRootFinder
import kotlin.math.abs
import kotlin.math.roundToLong

class Equals(
    val left: Expression,
    val right: Expression
) : Expression() {

    override val children: List<Expression> get() = listOf(left, right)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 2)
        return Equals(children[0], children[1])
    }

    override fun canonicalize(context: MathContext): Expression {
        val cLeft = left.canonicalize(context)
        if (context.shouldStop) return this
        val cRight = right.canonicalize(context)
        if (context.shouldStop) return this
        return Equals(cLeft, cRight)
    }

    /**
     * For an equation without variables: whether it holds. `null` if it cannot be decided.
     */
    fun truthValue(context: MathContext = MathContext.current()): Boolean? {
        val diff = Additions(left, right.negate()).canonicalize(context)
        if (context.shouldStop || diff.variables().isNotEmpty()) return null
        if (diff.isZero) return true
        val numeric = diff.evaluateNumerically()
        if (numeric.isNaN()) return null
        val scale = maxOf(1.0, abs(left.evaluateNumerically()), abs(right.evaluateNumerically()))
        return abs(numeric) <= 1e-12 * scale
    }

    /**
     * Solves for [variable]. Returns `setOf(variable)` if the equation is an identity (true for
     * every value) and an empty set (with an issue reported) if there is no solution.
     */
    fun solveForVariable(variable: Variable, context: MathContext = MathContext.current()): Set<Expression> {
        val solutions = solveCandidates(variable, context)
        if (context.shouldStop || solutions == setOf(variable) || context.complexNumbers) return solutions
        // Simplifying cancels factors: (x² − 4)/(x − 2) = 4 became x + 2 = 4, but x = 2 is no solution of the
        // equation as it was typed, where the denominator is 0
        if (variables() != setOf(variable)) return solutions
        val defined = solutions.filter { solution ->
            if (solution.containsImaginaryUnit()) return@filter true
            // A value beyond what a double holds (x = 10⁶⁰⁰) cannot be tried this way
            if (!solution.evaluateNumerically().isFinite()) return@filter true
            val bindings = mapOf(variable to solution.evaluateNumerically())
            left.evaluateNumerically(bindings).isFinite() && right.evaluateNumerically(bindings).isFinite()
        }.toSet()
        if (defined.isEmpty() && solutions.isNotEmpty()) {
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptySet()
        }
        return defined
    }

    private fun solveCandidates(variable: Variable, context: MathContext): Set<Expression> {
        val diff = Additions(left, right.negate()).canonicalize(context)
        if (context.shouldStop) return emptySet()
        // Multiplied out, (x+1)² = x² + 2x + 1 is recognized as an identity and (x+1)² = 4 as a
        // quadratic equation. The numeric solver keeps the original form, which is more accurate.
        val polynomial = if (hasExpandableProducts(diff)) expandProducts(diff).canonicalize(context) else diff
        if (context.shouldStop) return emptySet()

        if (!polynomial.containsVariable(variable)) {
            if (polynomial.isZero) {
                // Infinite solutions (identity)
                return setOf(variable)
            } else {
                context.stopWithError(MathIssue.CONTRADICTION)
                return emptySet()
            }
        }

        // A function with jumps (floor, sign, …) has its sign changes at the jumps, not where it is zero
        if (polynomial.containsExpressionMatching { it is NamedFunction && it.jumpsIn(variable) }) {
            context.stopWithError(MathIssue.CANT_SOLVE_GENERIC)
            return emptySet()
        }

        // Equal expressions written differently, e.g. a derivative and its simplified form or
        // sin²x + cos²x = 1: true for every value, not a list of numeric "solutions"
        if (sidesAgreeNumerically()) return setOf(variable)

        // Check if diff is a polynomial in variable
        // For linear: a * x + b = 0 => x = -b / a
        // For quadratic: a * x^2 + b * x + c = 0
        val linearSol = trySolveLinear(polynomial, variable, context)
        if (linearSol != null) return setOf(linearSol)
        if (context.shouldStop) return emptySet()

        val quadraticSols = trySolveQuadratic(polynomial, variable, context)
        if (quadraticSols != null) return quadraticSols.toSet()
        if (context.shouldStop) return emptySet()

        trySolvePolynomial(polynomial, variable, context)?.let { return it }
        if (context.shouldStop) return emptySet()

        complexPolynomialRoots(polynomial, variable, context)?.let { return it }
        if (context.shouldStop) return emptySet()

        trySolveProduct(diff, variable, context)?.let { return it }
        if (context.shouldStop) return emptySet()

        trySolveAbsolute(variable, context)?.let { return it }
        if (context.shouldStop) return emptySet()

        // A polynomial has its roots where the search looks (all magnitudes): none found is none there
        val isPolynomial = PartialFractions.polynomial(polynomial, variable, context.copy()) != null
        return solveNumerically(diff, variable, context, exhaustive = isPolynomial)
    }

    /**
     * A product is 0 where one of its factors is: (x − 1)¹⁰ = 0 where x − 1 = 0, and x·sin(x) = 0 where x = 0 or sin(x) = 0. Each
     * factor is solved on its own, which finds what the search by numbers over the whole product does not: a root where the curve
     * only touches 0 over a wide stretch, more roots than the search lists, roots far from 0. Factors that divide (their zeros are
     * where the product is not defined) are left out. Null if [diff] is no such product, or a factor cannot be solved.
     */
    private fun trySolveProduct(diff: Expression, variable: Variable, context: MathContext): Set<Expression>? {
        if (context.complexNumbers || diff.variables() != setOf(variable)) return null
        // The product as it was typed, where one side is 0: simplified, (x − 1)(x − 2)(x − 3) is multiplied out
        val product = when {
            diff is Multiplications || diff is Power -> diff
            right.isZero -> left
            left.isZero -> right
            else -> return null
        }
        fun factorsOf(e: Expression): List<Expression> = if (e is Multiplications) e.operands.flatMap { factorsOf(it) } else listOf(e)
        if (product !is Multiplications && product !is Power) return null
        val factors = factorsOf(product).filter { it.containsVariable(variable) }
        val parts = factors.mapNotNull { factor ->
            if (factor !is Power) return@mapNotNull factor
            val exponent = factor.exponent as? Value ?: return null
            when {
                !factor.base.containsVariable(variable) -> return null
                exponent.isNegative -> null
                else -> factor.base
            }
        }
        if (parts.isEmpty()) return null
        val roots = mutableListOf<Expression>()
        var notFound = false
        for (part in parts) {
            val scratch = context.copy()
            val found = Equals(part, Value.ZERO).solveCandidates(variable, scratch)
            if (context.shouldStop) return emptySet()
            if (scratch.issues.any { it != MathIssue.NO_REAL_SOLUTION && it != MathIssue.CONTRADICTION } || found == setOf(variable)) return null
            if (scratch.issues.isNotEmpty()) {
                notFound = notFound || scratch.solutionNotFound
                continue
            }
            if (scratch.moreSolutions) context.moreSolutions = true
            for (root in found) {
                val x = root.evaluateNumerically()
                if (!x.isFinite()) return null
                if (roots.none { abs(it.evaluateNumerically() - x) <= 1e-9 * maxOf(1.0, abs(x)) }) roots.add(root)
            }
        }
        if (roots.isEmpty()) {
            context.solutionNotFound = notFound
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptySet()
        }
        return roots.sortedBy { it.evaluateNumerically() }.toCollection(LinkedHashSet())
    }

    /**
     * |A| = c for a number c: A = c or A = −c, exactly. The search by numbers cannot tell |x| = 10⁻³⁰ from |x| = 0. Null if
     * the equation is of another kind, or a side cannot be solved.
     */
    private fun trySolveAbsolute(variable: Variable, context: MathContext): Set<Expression>? {
        if (context.complexNumbers) return null
        val (absolute, other) = when {
            left is AbsoluteValue && !right.containsVariable(variable) -> left to right
            right is AbsoluteValue && !left.containsVariable(variable) -> right to left
            else -> return null
        }
        val c = other.canonicalize(context)
        if (context.shouldStop || c.variables().isNotEmpty()) return null
        val value = c.evaluateNumerically()
        if (!value.isFinite()) return null
        if (value < 0.0) {
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptySet()
        }
        val roots = mutableListOf<Expression>()
        var notFound = false
        for (target in if (c.isZero) listOf(c) else listOf(c, c.negate().canonicalize(context))) {
            val scratch = context.copy()
            val found = Equals(absolute.operand, target).solveForVariable(variable, scratch)
            if (context.shouldStop) return emptySet()
            if (scratch.issues.any { it != MathIssue.NO_REAL_SOLUTION && it != MathIssue.CONTRADICTION } || found == setOf(variable)) return null
            if (scratch.issues.isNotEmpty()) {
                notFound = notFound || scratch.solutionNotFound
                continue
            }
            if (scratch.moreSolutions) context.moreSolutions = true
            for (root in found) {
                val x = root.evaluateNumerically()
                if (!x.isFinite()) return null
                if (roots.none { abs(it.evaluateNumerically() - x) <= 1e-9 * maxOf(abs(x), abs(it.evaluateNumerically())) || it == root }) roots.add(root)
            }
        }
        if (roots.isEmpty()) {
            context.solutionNotFound = notFound
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptySet()
        }
        return roots.sortedBy { it.evaluateNumerically() }.toCollection(LinkedHashSet())
    }

    /**
     * Where the equation holds on whole stretches and not only at single points, e.g. |x| = x for every x ≥ 0 and
     * |x − 1| + |x + 1| = 2 between −1 and 1: those stretches, found by trying numbers. Null if there are none (or too many, as
     * for something that repeats), or the equation has other variables.
     */
    internal fun solutionIntervals(variable: Variable, context: MathContext): List<Interval>? {
        if (variables() != setOf(variable) || containsExpressionMatching { it is TrigonometricFunction }) return null
        fun holds(x: Double): Boolean {
            val bindings = mapOf(variable to x)
            val l = left.evaluateNumerically(bindings)
            val r = right.evaluateNumerically(bindings)
            // Relative to the values alone: e^x = 10⁻¹⁰⁰ does not hold where e^x is merely small as well
            return l.isFinite() && r.isFinite() && abs(l - r) <= 1e-12 * maxOf(abs(l), abs(r))
        }
        fun x(i: Int) = -INTERVAL_SEARCH_RANGE + i * INTERVAL_SEARCH_STEP
        val count = (2 * INTERVAL_SEARCH_RANGE / INTERVAL_SEARCH_STEP).roundToLong().toInt()
        /**
         * The end of a stretch between [outside], where the equation does not hold, and [inside], where it does; and whether it holds
         * there. Null if the stretch is none: both sides are merely too small to tell apart from there on (eˣ = 0 below −745).
         */
        fun end(outside: Double, inside: Double): Pair<Expression, Boolean>? {
            var a = outside
            var b = inside
            repeat(60) {
                val mid = (a + b) / 2.0
                if (holds(mid)) b = mid else a = mid
            }
            val l = left.evaluateNumerically(mapOf(variable to a))
            val r = right.evaluateNumerically(mapOf(variable to a))
            if (l.isFinite() && r.isFinite() && maxOf(abs(l), abs(r)) < 1e-290) return null
            // A true stretch ends sharply (|x| = x is clearly false just below 0); where the sides only drift together until
            // rounding makes them equal (cosh x and sinh x from 18 on), they are still nearly equal next to it
            val lo = left.evaluateNumerically(mapOf(variable to outside))
            val ro = right.evaluateNumerically(mapOf(variable to outside))
            if (lo.isFinite() && ro.isFinite() && abs(lo - ro) <= 1e-6 * maxOf(abs(lo), abs(ro))) return null
            val nearest = b.roundToLong()
            val value: Expression = if (abs(b - nearest) < 1e-9 * maxOf(1.0, abs(b))) Value(nearest)
            else Rational.approximateFromDouble(b, 1000L)?.takeIf { abs(it.toDouble() - b) <= 1e-9 * maxOf(1.0, abs(b)) }?.let { Value(it) } ?: Value(b)
            return value to holds(value.evaluateNumerically())
        }
        val pieces = mutableListOf<Interval>()
        var start = -1
        for (i in 0..count + 1) {
            if (i % 1000 == 0 && context.shouldStop) return null
            val inside = i <= count && holds(x(i))
            if (inside && start < 0) start = i
            if (!inside && start >= 0) {
                if (i - start >= MIN_INTERVAL_SAMPLES) {
                    val lower = if (start == 0) null else end(x(start - 1), x(start)) ?: return null
                    val upper = if (i > count) null else end(x(i), x(i - 1)) ?: return null
                    pieces.add(Interval(lower?.first, lower?.second == true, upper?.first, upper?.second == true))
                    if (pieces.size > MAX_INTERVALS) return null
                }
                start = -1
            }
        }
        return pieces.takeIf { it.isNotEmpty() }
    }

    /**
     * A polynomial of degree 3 or more with a rational root, e.g. x³ − 14x² + 35x − 22: its rational roots by the rational root
     * theorem and what is left of degree 2 by the formula, exactly. (The numeric search only looks for roots close to 0 once it has
     * found some, so 11 would be missed.) Null if there is no rational root to start with.
     */
    private fun trySolvePolynomial(polynomial: Expression, variable: Variable, context: MathContext): Set<Expression>? {
        if (context.complexNumbers) return null
        var rest = PartialFractions.polynomial(polynomial, variable, context) ?: return null
        if (rest.size < 4) return null
        val roots = mutableListOf<Expression>()
        while (rest.size > 3) {
            val root = PartialFractions.rationalRoot(rest) ?: return null
            roots.add(Value(root))
            while (rest.size > 1 && PartialFractions.value(rest, root).isZero) rest = PartialFractions.divideByLinear(rest, root)
        }
        when (rest.size) {
            2 -> roots.add(Value(-rest[0] / rest[1]))
            3 -> {
                val discriminant = rest[1] * rest[1] - Rational.of(4L) * rest[2] * rest[0]
                if (discriminant.isZero) {
                    roots.add(Value(-rest[1] / (Rational.of(2L) * rest[2])))
                } else if (!discriminant.isNegative) {
                    val sqrtD = Power.squareRoot(Value(discriminant)).canonicalize(context)
                    val twoA = Value(Rational.of(2L) * rest[2])
                    roots.add(Multiplications.of(Additions(Value(-rest[1]), sqrtD), twoA.invert()).canonicalize(context))
                    roots.add(Multiplications.of(Additions(Value(-rest[1]), sqrtD.negate()), twoA.invert()).canonicalize(context))
                }
            }
        }
        return roots.sortedBy { it.evaluateNumerically() }.toCollection(LinkedHashSet())
    }

    /**
     * All roots of a polynomial of degree 3 or more, with complex numbers on: x³ = 1 has three, x⁴ = −1 four. The rational roots
     * first, exactly; what is left of degree 2 by the formula; xⁿ = w by the n-th roots of w (|w|^(1/n)·(cos θ + i·sin θ), exact
     * where the angles are); anything else by Durand–Kerner, in decimals. Null without complex numbers, or for no polynomial.
     */
    private fun complexPolynomialRoots(polynomial: Expression, variable: Variable, context: MathContext): Set<Expression>? {
        if (!context.complexNumbers) return null
        var rest = PartialFractions.polynomial(polynomial, variable, context.copy()) ?: return null
        if (rest.size < 4 || rest.size > MAX_COMPLEX_DEGREE + 1) return null
        val roots = mutableListOf<Expression>()
        // x⁶ = 64 as it is (the n-th roots, ±1 ± i√3 among them), not with ±2 taken out first and a rest of degree 4
        val isPurePower = rest.drop(1).dropLast(1).all { it.isZero }
        while (rest.size > 3 && !isPurePower) {
            val root = PartialFractions.rationalRoot(rest) ?: break
            roots.add(Value(root))
            while (rest.size > 1 && PartialFractions.value(rest, root).isZero) rest = PartialFractions.divideByLinear(rest, root)
        }
        val scratch = context.copy()
        fun exact(e: Expression): Expression = e.canonicalize(scratch)
        when {
            rest.size <= 1 -> {}
            rest.size == 2 -> roots.add(Value(-rest[0] / rest[1]))
            rest.size == 3 -> {
                // (−b ± √D)/2a, with √D = i·√(−D) below 0
                val (c, b, a) = rest
                val discriminant = b * b - Rational.of(4L) * a * c
                val root = Power.squareRoot(Value(discriminant.abs()))
                val sqrtD: Expression = if (discriminant.isNegative) Multiplications.of(root, ImaginaryUnit) else root
                for (sign in listOf(Value.ONE, Value.MINUS_ONE)) {
                    roots.add(exact(Multiplications.of(Additions(Value(-b), Multiplications.of(sign, sqrtD)), Power(Value(Rational.of(2L) * a), Value.MINUS_ONE))))
                }
            }
            rest.drop(1).dropLast(1).all { it.isZero } -> {
                // xⁿ = w: the n roots on the circle of radius |w|^(1/n)
                val n = rest.size - 1
                val w = -rest[0] / rest[n]
                val radius = Power(Value(w.abs()), Value(Rational.of(1L, n.toLong())))
                for (k in 0 until n) {
                    val angle = Multiplications.of(Value(Rational.of(2L * k + (if (w.isNegative) 1 else 0), n.toLong())), Constant.PI)
                    val unit = Additions(
                        TrigonometricFunction(TrigType.COS, angle, AngleUnit.RADIANS),
                        Multiplications.of(ImaginaryUnit, TrigonometricFunction(TrigType.SIN, angle, AngleUnit.RADIANS))
                    )
                    roots.add(exact(Multiplications.of(radius, unit)))
                }
            }
            else -> roots.addAll(durandKerner(rest) ?: return null)
        }
        if (scratch.shouldStop) return null
        for (k in roots.indices) roots[k] = tidyComplexRoot(roots[k], scratch) ?: return null
        // Each once, from the left of the plane to the right and from below to above
        val distinct = mutableListOf<Expression>()
        for (root in roots) {
            val z = numericComplex(root) ?: return null
            if (distinct.none { other -> numericComplex(other)!!.let { abs(it.re - z.re) + abs(it.im - z.im) <= 1e-9 * maxOf(1.0, z.abs) } }) distinct.add(root)
        }
        return distinct.sortedWith(compareBy({ numericComplex(it)!!.re }, { numericComplex(it)!!.im })).toCollection(LinkedHashSet())
    }

    /** The complex roots of the polynomial with coefficients [p] (lowest power first) in decimals, by Durand–Kerner; null if they do not settle. */
    private fun durandKerner(p: List<Rational>): List<Expression>? {
        val n = p.size - 1
        val lead = p[n].toDouble()
        val c = DoubleArray(n + 1) { p[it].toDouble() / lead }
        // Starting points spread on a circle that holds all roots, turned off the axes
        val bound = 1.0 + (0 until n).maxOf { abs(c[it]) }
        val re = DoubleArray(n) { bound * kotlin.math.cos(2 * Math.PI * it / n + 0.4) }
        val im = DoubleArray(n) { bound * kotlin.math.sin(2 * Math.PI * it / n + 0.4) }
        repeat(DURAND_KERNER_STEPS) {
            var change = 0.0
            for (k in 0 until n) {
                // p(z) by Horner
                var pr = 1.0
                var pi = 0.0
                for (j in n - 1 downTo 0) {
                    val r = pr * re[k] - pi * im[k] + c[j]
                    pi = pr * im[k] + pi * re[k]
                    pr = r
                }
                // ∏ (zₖ − zⱼ)
                var qr = 1.0
                var qi = 0.0
                for (j in 0 until n) {
                    if (j == k) continue
                    val dr = re[k] - re[j]
                    val di = im[k] - im[j]
                    val r = qr * dr - qi * di
                    qi = qr * di + qi * dr
                    qr = r
                }
                val q = qr * qr + qi * qi
                if (q == 0.0) return null
                val stepRe = (pr * qr + pi * qi) / q
                val stepIm = (pi * qr - pr * qi) / q
                re[k] -= stepRe
                im[k] -= stepIm
                change = maxOf(change, abs(stepRe) + abs(stepIm))
            }
            if (change < 1e-15 * bound) return (0 until n).map { k -> complexRoot(re[k], im[k]) }
        }
        return null
    }

    /**
     * A root as it is best written: with simple fractions as its parts where it has them (x⁴ + 4 = 0 has 1 + i, not ⁴√4·√2/2 + …,
     * and Durand–Kerner gives 2.0000000001i for 2i), exact where that needs no cosine of an angle that has no value of its own
     * (cos(2π/5)), in decimals otherwise.
     */
    private fun tidyComplexRoot(root: Expression, context: MathContext): Expression? {
        val z = numericComplex(root) ?: return null
        fun simple(x: Double): Rational? = Rational.approximateFromDouble(x, 1000L)?.takeIf { abs(it.toDouble() - x) <= 1e-11 * maxOf(1.0, abs(x)) }
        val re = simple(z.re)
        val im = simple(z.im)
        if (re != null && im != null) return complexOf(Value(re), Value(im), context)
        if (root.containsExpressionMatching { it is TrigonometricFunction } || root.containsExpressionMatching { it is Value && it.rational == null }) {
            return complexRoot(z.re, z.im)
        }
        return root
    }

    /** re + im·i, a real number where the imaginary part is only rounding. */
    private fun complexRoot(re: Double, im: Double): Expression {
        val scale = maxOf(1.0, abs(re), abs(im))
        val realPart = if (abs(re) < 1e-13 * scale) Value.ZERO else Value(re)
        if (abs(im) < 1e-12 * scale) return realPart
        val imaginaryPart = Multiplications.of(Value(im), ImaginaryUnit)
        return if (realPart.isZero) imaginaryPart else Additions(realPart, imaginaryPart)
    }

    /**
     * Whether both sides have the same value at a set of irregular sample points (all variables
     * set to them), i.e. the equation is an identity. Points where a side is undefined are
     * skipped; agreement is relative to the values, so 10⁻²⁰·x = 0 is not an identity.
     */
    private fun sidesAgreeNumerically(): Boolean {
        val vars = variables().toList()
        var agreeing = 0
        for (i in IDENTITY_SAMPLES.indices) {
            val bindings = vars.withIndex().associate { (j, v) -> v to IDENTITY_SAMPLES[(i + 3 * j) % IDENTITY_SAMPLES.size] }
            val l = left.evaluateNumerically(bindings)
            val r = right.evaluateNumerically(bindings)
            if (!l.isFinite() || !r.isFinite()) continue
            if (abs(l - r) > IDENTITY_TOLERANCE * maxOf(abs(l), abs(r))) return false
            agreeing++
        }
        return agreeing >= MIN_IDENTITY_SAMPLES
    }

    /**
     * Fallback for equations the symbolic solvers cannot handle, e.g. sin(x) = x / 2.
     * Searches near the origin first and widens the range only if nothing was found.
     */
    /** A few Newton steps from a root found by bisection, kept only where they make it better: 90.00000059 → 90. */
    private fun polish(function: Function1D, root: Double): Double {
        var x = root
        var fx = abs(function.evaluateAt(x))
        repeat(6) {
            val h = 1e-6 * maxOf(1.0, abs(x))
            val slope = (function.evaluateAt(x + h) - function.evaluateAt(x - h)) / (2 * h)
            if (!slope.isFinite() || slope == 0.0) return x
            val next = x - function.evaluateAt(x) / slope
            val fNext = abs(function.evaluateAt(next))
            if (!fNext.isFinite() || fNext >= fx || abs(next - root) > 1e-4 * maxOf(1.0, abs(root))) return x
            x = next
            fx = fNext
        }
        return x
    }

    private fun solveNumerically(diff: Expression, variable: Variable, context: MathContext, exhaustive: Boolean = false): Set<Expression> {
        if (diff.variables() != setOf(variable)) {
            context.stopWithError(MathIssue.CANT_SOLVE_GENERIC)
            return emptySet()
        }
        val function = Function1D(diff, variable, context.angleUnit)
        var roots = emptyList<Double>()
        // In degrees a period is 360, not 2π: the same number of periods is searched
        val scale = if (context.angleUnit == com.vortessence.arithmeticus.core.math.context.AngleUnit.DEGREES &&
            diff.containsExpressionMatching { it is TrigonometricFunction }) 180.0 / Math.PI else 1.0
        // Sine and cosine repeat: the nearest roots are what is wanted. Others (1/x + 1/(x − 30) = 1/4, with a root at 34.5 as well as 3.5)
        // have roots wherever they are, so the wider search is made as well, not only when the narrow one found nothing.
        val repeats = diff.containsExpressionMatching { it is TrigonometricFunction }
        for ((range, steps) in NUMERIC_SEARCH_RANGES) {
            val found = NumericRootFinder.findRoots(function, -range * scale, range * scale, steps, context)
            if (context.shouldStop) return emptySet()
            if (repeats) {
                if (found.isNotEmpty()) { roots = found; break }
            } else {
                roots = roots + found.filter { r -> roots.none { abs(it - r) <= 1e-6 * maxOf(1.0, abs(r)) } }
            }
        }
        // Nothing there: closer to 0 (1/x² = 10¹⁰, sin(1000x) = 0), and for what does not repeat far away from it (x³ = 10⁶⁰)
        for (range in FINE_SEARCH_RANGES) {
            if (roots.isNotEmpty()) break
            roots = NumericRootFinder.findRoots(function, -range * scale, range * scale, FINE_SEARCH_STEPS, context)
            if (context.shouldStop) return emptySet()
        }
        if (roots.isEmpty() && !repeats) {
            roots = NumericRootFinder.findRootsByMagnitude(function, context)
            if (context.shouldStop) return emptySet()
        }
        if (roots.isEmpty()) {
            // None was found, which does not say that there is none
            context.solutionNotFound = !exhaustive
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptySet()
        }
        // More than are listed: too many to list, or (where it repeats and only the nearest were looked for) more further out,
        // as for tan(x) = x; sin(x) = x/10 has its last beyond 10 at none
        val near = NUMERIC_SEARCH_RANGES.first().first * scale
        if (roots.size > MAX_NUMERIC_SOLUTIONS ||
            (repeats && roots.all { abs(it) <= near } && NumericRootFinder.findRoots(function, -FAR_SEARCH * scale, FAR_SEARCH * scale, FAR_SEARCH_STEPS, context).any { abs(it) > near * 1.0001 })
        ) {
            context.moreSolutions = true
        }
        if (context.shouldStop) return emptySet()
        return roots
            .map { polish(function, it) }
            .sortedBy { abs(it) }
            .take(MAX_NUMERIC_SOLUTIONS)
            .sorted()
            .map { root ->
                val nearest = root.roundToLong()
                // 360.0000002 in degrees is 360, and a root where the curve only touches 0 (found less exactly)
                // is the whole number next to it if that is at least as good a root
                // Only where the whole number is a root as well: 10⁻²⁰ (1/x = 10²⁰) is not 0, where 1/x is not even defined
                val atNearest = abs(function.evaluateAt(nearest.toDouble()))
                val close = atNearest.isFinite() && (
                    (abs(root - nearest) < 1e-9 * maxOf(1.0, abs(root)) && atNearest <= abs(function.evaluateAt(root)) + 1e-9) ||
                        (abs(root - nearest) < 1e-5 * maxOf(1.0, abs(root)) && atNearest <= abs(function.evaluateAt(root))))
                if (close) Value(nearest) else simpleFraction(function, root) ?: Value(root)
            }
            .toCollection(LinkedHashSet())
    }

    /** 1.5 is 3/2 where that is as good a root: a simple fraction (denominator up to 100) next to the number found. */
    private fun simpleFraction(function: Function1D, root: Double): Expression? {
        val candidate = Rational.approximateFromDouble(root, 100L) ?: return null
        if (abs(candidate.toDouble() - root) > 1e-9 * maxOf(1.0, abs(root))) return null
        return if (abs(function.evaluateAt(candidate.toDouble())) <= abs(function.evaluateAt(root)) + 1e-14) Value(candidate) else null
    }

    private fun trySolveLinear(expr: Expression, variable: Variable, context: MathContext): Expression? {
        // Collect terms in Additions
        val terms = if (expr is Additions) expr.operands else listOf(expr)
        var a: Expression = Value.ZERO
        var b: Expression = Value.ZERO

        for (term in terms) {
            if (term.containsVariable(variable)) {
                if (term == variable) {
                    a = Additions(listOf(a, Value.ONE)).canonicalize(context)
                } else if (term is Multiplications) {
                    val nonVar = term.operands.filter { it != variable }
                    // The coefficient must not depend on the variable: x * sin(x) is not linear
                    if (term.operands.count { it == variable } == 1 && nonVar.none { it.containsVariable(variable) }) {
                        a = Additions(listOf(a, Multiplications.of(*nonVar.toTypedArray()))).canonicalize(context)
                    } else {
                        return null // higher degree
                    }
                } else {
                    return null
                }
            } else {
                b = Additions(listOf(b, term)).canonicalize(context)
            }
        }

        if (a.isZero) return null
        // x = -b / a
        return Multiplications.of(b.negate(), a.invert()).canonicalize(context)
    }

    private fun trySolveQuadratic(expr: Expression, variable: Variable, context: MathContext): List<Expression>? {
        val terms = if (expr is Additions) expr.operands else listOf(expr)
        var a: Expression = Value.ZERO
        var b: Expression = Value.ZERO
        var c: Expression = Value.ZERO

        for (term in terms) {
            if (term.containsVariable(variable)) {
                if (term == variable) {
                    b = Additions(listOf(b, Value.ONE)).canonicalize(context)
                } else if (term is Power && term.base == variable && term.exponent == Value.TWO) {
                    a = Additions(listOf(a, Value.ONE)).canonicalize(context)
                } else if (term is Multiplications) {
                    val varFactors = term.operands.filter { it.containsVariable(variable) }
                    val coeffFactors = term.operands.filter { !it.containsVariable(variable) }
                    val coeff = Multiplications(coeffFactors).canonicalize(context)

                    if (varFactors.size == 1 && varFactors[0] == variable) {
                        b = Additions(listOf(b, coeff)).canonicalize(context)
                    } else if (varFactors.size == 1 && varFactors[0] is Power) {
                        val p = varFactors[0] as Power
                        if (p.base == variable && p.exponent == Value.TWO) {
                            a = Additions(listOf(a, coeff)).canonicalize(context)
                        } else {
                            return null
                        }
                    } else if (varFactors.size == 2 && varFactors[0] == variable && varFactors[1] == variable) {
                        a = Additions(listOf(a, coeff)).canonicalize(context)
                    } else {
                        return null
                    }
                } else {
                    return null
                }
            } else {
                c = Additions(listOf(c, term)).canonicalize(context)
            }
        }

        if (a.isZero) return null

        // Discriminant D = b^2 - 4ac
        val bSq = Power(b, Value.TWO).canonicalize(context)
        val fourAC = Multiplications.of(Value(4L), a, c).canonicalize(context)
        val discriminant = Additions(bSq, fourAC.negate()).canonicalize(context)
        val discriminantValue = if (discriminant.variables().isEmpty()) discriminant.evaluateNumerically() else Double.NaN
        // With complex numbers the roots are a ± b·i
        if (discriminantValue < 0.0 && !context.complexNumbers) {
            context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            return emptyList()
        }
        val sqrtD = Power.squareRoot(discriminant).canonicalize(context)
        val twoA = Multiplications.of(Value.TWO, a).canonicalize(context)

        val sol1 = Multiplications.of(Additions(b.negate(), sqrtD), twoA.invert()).canonicalize(context)
        val sol2 = Multiplications.of(Additions(b.negate(), sqrtD.negate()), twoA.invert()).canonicalize(context)

        return listOf(sol1, sol2)
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        val l = left.evaluateNumerically(bindings)
        val r = right.evaluateNumerically(bindings)
        return l - r
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Equals) return false
        return left == other.left && right == other.right
    }

    override fun hashCode(): Int = left.hashCode() * 31 + right.hashCode()

    override fun toString(): String = "$left = $right"

    companion object {
        private const val MAX_NUMERIC_SOLUTIONS = 10

        /** The highest degree whose complex roots are all found (and listed). */
        private const val MAX_COMPLEX_DEGREE = 12
        private const val DURAND_KERNER_STEPS = 2000

        /** (half-width of the search interval, number of sampling steps) */
        private val NUMERIC_SEARCH_RANGES = listOf(10.0 to 2_000, 1_000.0 to 20_000)

        /** Half-widths of the searches close to 0, made when the others found nothing, each with [FINE_SEARCH_STEPS] steps. */
        private val FINE_SEARCH_RANGES = listOf(0.1, 0.001)

        /** How far, and how closely, an equation that repeats is looked at for roots beyond the ones listed. */
        private const val FAR_SEARCH = 100.0
        private const val FAR_SEARCH_STEPS = 4_000
        private const val FINE_SEARCH_STEPS = 2_000

        /** Where and how closely [solutionIntervals] looks; a stretch shorter than [MIN_INTERVAL_SAMPLES] steps is not taken for one. */
        private const val INTERVAL_SEARCH_RANGE = 1_000.0
        private const val INTERVAL_SEARCH_STEP = 0.05
        private const val MIN_INTERVAL_SAMPLES = 6
        private const val MAX_INTERVALS = 6

        private val IDENTITY_SAMPLES = listOf(0.37, 1.13, 2.71, -0.83, 0.52, -1.9, 3.3, 0.071, -2.6, 1.61)
        private const val IDENTITY_TOLERANCE = 1e-9
        private const val MIN_IDENTITY_SAMPLES = 6
    }
}
