package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.steps.Alternatives
import kotlin.math.abs

/**
 * Ordinary differential equations of one unknown function y(x), as typed with the derivative key: d/dx(y) = 2xy.
 *
 * - first order, linear (y′ + p·y = q, with the integrating factor) or separable (y′ = f(x)·g(y)),
 * - second order, linear with constant coefficients (a·y″ + b·y′ + c·y = r(x)): the characteristic equation, and for r ≠ 0
 *   variation of parameters,
 * - optionally with initial conditions: x₀, y(x₀) (and y′(x₀) for second order).
 *
 * Every explicit solution is put back into the equation at some points; a solution that does not satisfy it is not given.
 */
internal object DifferentialEquations {

    enum class Kind { STANDARD_FORM, INTEGRATING_FACTOR, LINEAR_SOLUTION, SEPARATE, INTEGRATE_BOTH, SOLVE_FOR_UNKNOWN, CHARACTERISTIC, HOMOGENEOUS, VARIATION_OF_PARAMETERS, UNDETERMINED_COEFFICIENTS, CONSTANTS }

    /** A step of the derivation: what it works out ([notes], each "left = right") and the equation it ends with ([line]). */
    class Stage(val kind: Kind, val notes: List<Pair<Expression, Expression>>, val line: Expression)

    class Derivation(val unknown: Variable, val variable: Variable, val order: Int, val equation: Expression, val solution: Expression, val stages: List<Stage>)

    /** The equation in the letters y, y′, y″ (as symbols), as G(x, y, y′, y″) = 0. */
    private class Problem(val unknown: Variable, val variable: Variable, val order: Int, val y0: Variable, val y1: Variable, val y2: Variable, val g: Expression) {
        /** y′ as written, d/dx(y). */
        val derivative: Expression get() = Derivative(unknown, variable)
        val secondDerivative: Expression get() = Derivative(Derivative(unknown, variable), variable)
    }

    private val CONSTANT = Variable("C")
    private val CONSTANT_1 = Variable("C₁")
    private val CONSTANT_2 = Variable("C₂")

    /**
     * The solution of dsolve([arguments]) (the equation as typed, then the initial conditions), or null with the error reported.
     * It is worked out exactly, whatever the calculation mode: the coefficients of a second order equation must be rational numbers
     * to find the roots; NUMERIC mode shows the result in decimals (see [NamedFunction]).
     */
    fun solve(arguments: List<Expression>, context: MathContext): Derivation? {
        if (context.mode == CalculationMode.EXACT) return solveExactly(arguments, context)
        val exact = context.copy().also { it.mode = CalculationMode.EXACT }
        val result = solveExactly(arguments, exact)
        exact.issues.forEach { context.stopWithError(it) }
        return result
    }

    private fun solveExactly(arguments: List<Expression>, context: MathContext): Derivation? {
        // Out of time or cancelled: that is the error, not "cannot be solved"
        fun refuse(issue: MathIssue = MathIssue.CANT_SOLVE_ODE): Derivation? { if (!context.shouldStop) context.stopWithError(issue); return null }
        val equation = arguments[0] as? Equals ?: return refuse()
        val problem = problemOf(equation, context) ?: return refuse()
        if (context.shouldStop) return null
        // The conditions: x₀, y(x₀) and for a second order equation y′(x₀)
        val conditions = arguments.drop(1).map { it.canonicalize(context) }
        if (context.shouldStop) return null
        if (conditions.isNotEmpty() && conditions.size != problem.order + 1) return refuse(MathIssue.ARGUMENT_COUNT)
        // An initial value may be a letter (y(0) = b), but not depend on the unknown or its variable
        if (conditions.any { problem.unknown in it.variables() || problem.variable in it.variables() }) return refuse()

        val general = (if (problem.order == 1) firstOrder(problem, context) else secondOrder(problem, context)) ?: return refuse()
        if (conditions.isEmpty()) return general
        return withConditions(problem, general, conditions, context) ?: refuse()
    }

    // ----- the equation -----

    /** The letter and variable of a derivative d/dx(d/dx(y)) and how many times it is taken; null for anything else (d/dx(sin(y))). */
    private fun chain(e: Expression): Triple<Variable, Variable, Int>? {
        var depth = 0
        var current = e
        var variable: Variable? = null
        while (current is Derivative && current.point == null) {
            if (variable != null && current.variable != variable) return null
            variable = current.variable
            depth++
            current = current.body
        }
        return if (current is Variable && variable != null) Triple(current, variable, depth) else null
    }

    private fun problemOf(equation: Equals, context: MathContext): Problem? {
        val chains = mutableListOf<Triple<Variable, Variable, Int>>()
        var valid = true
        fun scan(e: Expression) {
            if (e is Derivative) {
                val c = chain(e)
                if (c == null) valid = false else chains.add(c)
            } else {
                e.children.forEach { scan(it) }
            }
        }
        scan(equation)
        if (!valid || chains.isEmpty()) return null
        val unknown = chains.first().first
        val variable = chains.first().second
        if (chains.any { it.first != unknown || it.second != variable } || unknown == variable) return null
        val order = chains.maxOf { it.third }
        if (order > 2) return null
        val y0 = unknown
        val y1 = Variable(unknown.identifier + "′")
        val y2 = Variable(unknown.identifier + "″")
        fun symbols(e: Expression) = e.transform { part ->
            if (part is Derivative) {
                when (chain(part)!!.third) { 1 -> y1; else -> y2 }
            } else part
        }
        val scratch = context.copy()
        val g = Additions(symbols(equation.left), symbols(equation.right).negate()).canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        // The highest derivative must really be in the equation
        if (!g.containsVariable(if (order == 1) y1 else y2)) return null
        return Problem(unknown, variable, order, y0, y1, y2, g)
    }

    private fun partial(e: Expression, v: Variable, context: MathContext): Expression? {
        val scratch = context.copy()
        val result = Derivative(e, v).canonicalize(scratch)
        return result.takeUnless { scratch.shouldStop || scratch.issues.isNotEmpty() }
    }

    private fun canonical(e: Expression, context: MathContext): Expression? {
        val scratch = context.copy()
        val result = e.canonicalize(scratch)
        return result.takeUnless { scratch.shouldStop || scratch.issues.isNotEmpty() }
    }

    private fun integral(e: Expression, v: Variable, context: MathContext): Expression? {
        val scratch = context.copy()
        val result = Antiderivative(e, v).canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || result.containsExpressionMatching { it is Antiderivative || it is Integral }) return null
        return result
    }

    /** ln|u| as ln(u): the sign goes into the constant (a factor of an integrating factor, an e^C). */
    private fun withoutSigns(e: Expression): Expression = e.transform { part ->
        if (part is Logarithm && part.operand is AbsoluteValue) Logarithm(part.base, (part.operand as AbsoluteValue).operand) else part
    }

    private fun eq(left: Expression, right: Expression): Expression = Equals(left, right)

    // ----- first order -----

    private fun firstOrder(p: Problem, context: MathContext): Derivation? {
        linear(p, context)?.let { return it }
        if (context.shouldStop) return null
        return separable(p, context)
    }

    /** a·y′ + b·y + c = 0 with a, b and c not depending on y: y′ + (b/a)·y = −c/a. */
    private fun linear(p: Problem, context: MathContext): Derivation? {
        val a = partial(p.g, p.y1, context) ?: return null
        val b = partial(p.g, p.y0, context) ?: return null
        if (a.isZero || listOf(a, b).any { it.containsVariable(p.y0) || it.containsVariable(p.y1) }) return null
        val c = canonical(p.g.substituteVariables(mapOf(p.y1 to Value.ZERO, p.y0 to Value.ZERO)), context) ?: return null
        val coefficient = canonical(Multiplications.of(b, Power(a, Value.MINUS_ONE)), context) ?: return null
        val right = canonical(Multiplications.of(Value.MINUS_ONE, c, Power(a, Value.MINUS_ONE)), context) ?: return null
        val y = p.unknown
        val x = p.variable

        val integralOfP = integral(coefficient, x, context)?.let { withoutSigns(it) } ?: return null
        val factor = canonical(exponential(integralOfP), context) ?: return null
        val integrand = canonical(Multiplications.of(factor, right), context) ?: return null
        val integralOfRight = integral(integrand, x, context) ?: return null
        val solution = canonical(Multiplications.of(Additions(integralOfRight, CONSTANT), Power(factor, Value.MINUS_ONE)), context) ?: return null

        val mu = Variable("μ")
        val stages = listOf(
            Stage(Kind.STANDARD_FORM, emptyList(), eq(Additions(p.derivative, Multiplications.of(coefficient, y)), right)),
            Stage(Kind.INTEGRATING_FACTOR, listOf(Antiderivative(coefficient, x) to integralOfP), eq(mu, factor)),
            Stage(Kind.LINEAR_SOLUTION, listOf(Antiderivative(integrand, x) to integralOfRight), eq(Multiplications.of(mu, y), Additions(integralOfRight, CONSTANT))),
            Stage(Kind.SOLVE_FOR_UNKNOWN, emptyList(), eq(y, solution))
        )
        if (!satisfies(p, solution, context)) return null
        return Derivation(y, x, 1, eq(p.derivative, right.let { Additions(Multiplications.of(Value.MINUS_ONE, coefficient, y), it) }), eq(y, solution), stages)
    }

    /** y′ = f(x)·g(y): 1/g(y) dy = f(x) dx, integrated on both sides and solved for y where that can be done. */
    private fun separable(p: Problem, context: MathContext): Derivation? {
        val a = partial(p.g, p.y1, context) ?: return null
        if (a.isZero || a.containsVariable(p.y1)) return null
        val rest = canonical(p.g.substituteVariables(mapOf(p.y1 to Value.ZERO)), context) ?: return null
        val h = canonical(Multiplications.of(Value.MINUS_ONE, rest, Power(a, Value.MINUS_ONE)), context) ?: return null
        if (!h.containsVariable(p.y0)) return null
        val (f, g) = separate(h, p, context) ?: return null
        val y = p.unknown
        val x = p.variable

        val inverse = canonical(Power(g, Value.MINUS_ONE), context) ?: return null
        val withBars = integral(inverse, y, context) ?: return null
        // ln|y| stays in the equation (it is for y < 0 as well); for solving for y the sign goes into the constant
        val left = withoutSigns(withBars)
        val right = integral(f, x, context) ?: return null
        // The integrals are antiderivatives of what they were made from
        if (!isAntiderivative(withBars, inverse, y, p, context) || !isAntiderivative(right, f, x, p, context)) return null
        val implicit = eq(withBars, Additions(right, CONSTANT))
        val stages = mutableListOf(
            Stage(Kind.SEPARATE, emptyList(), eq(Multiplications.of(inverse, differential(y)), Multiplications.of(f, differential(x)))),
            Stage(Kind.INTEGRATE_BOTH, listOf(Antiderivative(inverse, y) to withBars, Antiderivative(f, x) to right), implicit)
        )
        val typed = eq(p.derivative, h)

        // y by taking the sides apart; what that cannot do is left to the equation solver
        val rhs = Additions(right, CONSTANT)
        val candidates = invert(left, y, rhs) ?: run {
            val scratch = context.copy()
            val solved = (implicit as Equals).solveForVariable(y, scratch)
            if (scratch.shouldStop || solved.isEmpty() || solved == setOf(y)) emptyList() else solved.toList()
        }
        val explicit = candidates.filter { !it.containsVariable(y) }.mapNotNull { c -> canonical(c, context)?.let { canonical(absorbConstant(it), context) } }
        if (explicit.isNotEmpty() && explicit.size == candidates.size && explicit.all { satisfies(p, it, context) }) {
            val result = if (explicit.size == 1) eq(y, explicit.single()) else Alternatives(explicit.map { eq(y, it) }, inline = true)
            stages.add(Stage(Kind.SOLVE_FOR_UNKNOWN, emptyList(), result))
            return Derivation(y, x, 1, typed, result, stages)
        }
        // What cannot be solved for y stays an equation between y and x
        return Derivation(y, x, 1, typed, implicit, stages)
    }

    /**
     * e^u for the integral u of the coefficient: e^(k·ln(v) + w) is v^k·e^w, as the calculator leaves e^ln(x) as it is
     * (it is only x for x > 0), which an integrating factor does not need: the sign goes into the constant.
     */
    private fun exponential(u: Expression): Expression {
        val terms = if (u is Additions) u.operands else listOf(u)
        return Multiplications.of(*terms.map { term ->
            val factors = if (term is Multiplications) term.operands else listOf(term)
            val logs = factors.filterIsInstance<Logarithm>().filter { it.base == Constant.E }
            val others = factors.filter { it !is Logarithm || it.base != Constant.E }
            if (logs.size == 1 && others.all { it is Value }) Power(logs.single().operand, Multiplications.of(*others.toTypedArray())) else Power(Constant.E, term)
        }.toTypedArray())
    }

    /**
     * The y with [phi] = [rhs], by undoing what is done to y from the outside in: numbers, sums, a power (two ways for an even one),
     * ln, e^ and atan. Null if y is not in just one place.
     */
    private fun invert(phi: Expression, y: Variable, rhs: Expression): List<Expression>? {
        if (phi == y) return listOf(rhs)
        if (!phi.containsVariable(y)) return null
        return when (phi) {
            is Multiplications -> {
                val (with, without) = phi.operands.partition { it.containsVariable(y) }
                if (with.size != 1) null
                else invert(with.single(), y, Multiplications.of(rhs, Power(Multiplications.of(*without.toTypedArray()), Value.MINUS_ONE)))
            }
            is Additions -> {
                val (with, without) = phi.operands.partition { it.containsVariable(y) }
                if (with.size != 1) null else invert(with.single(), y, Additions(listOf(rhs) + without.map { it.negate() }))
            }
            is Power -> if (phi.exponent.containsVariable(y)) {
                // b^u = rhs: u = ln(rhs)/ln(b)
                if (phi.base.containsVariable(y)) null
                else invert(phi.exponent, y, if (phi.base == Constant.E) Logarithm.ln(rhs) else Multiplications.of(Logarithm.ln(rhs), Power(Logarithm.ln(phi.base), Value.MINUS_ONE)))
            } else {
                val n = (phi.exponent as? Value)?.rational
                if (n == null || n.isZero) null
                else {
                    val root = Power(rhs, Value(Rational.ONE / n))
                    val even = !n.isFractional && n.numerator.value.mod(java.math.BigInteger.TWO).signum() == 0
                    val roots = if (even) listOf(root, root.negate()) else listOf(root)
                    val inverted = roots.map { invert(phi.base, y, it) }
                    if (inverted.any { it == null }) null else inverted.flatMap { it!! }
                }
            }
            is Logarithm -> if (phi.base == Constant.E) invert(phi.operand, y, Power(Constant.E, rhs)) else null
            is TrigonometricFunction -> when (phi.type) {
                TrigType.ATAN -> invert(phi.operand, y, TrigonometricFunction(TrigType.TAN, rhs, phi.angleUnit))
                TrigType.TAN -> invert(phi.operand, y, TrigonometricFunction(TrigType.ATAN, rhs, phi.angleUnit))
                else -> null
            }
            else -> null
        }
    }

    /** Whether the derivative of [antiderivative] by [v] is [integrand], at some points. */
    private fun isAntiderivative(antiderivative: Expression, integrand: Expression, v: Variable, p: Problem, context: MathContext): Boolean {
        val derivative = partial(antiderivative, v, context) ?: return false
        val letters = (derivative.variables() + integrand.variables()).toList()
        var checked = 0
        for (t in SAMPLES) for (shift in CONSTANT_SAMPLES.indices) {
            val point = letters.withIndex().associate { (j, letter) -> letter to if (letter == v) t else CONSTANT_SAMPLES[(shift + j) % CONSTANT_SAMPLES.size] }
            val l = derivative.evaluateNumerically(point)
            val r = integrand.evaluateNumerically(point)
            if (!l.isFinite() || !r.isFinite()) continue
            if (abs(l - r) > 1e-7 * maxOf(1.0, abs(r))) return false
            checked++
        }
        return checked >= 3
    }

    /** dy and dx as they are written next to the integrands. */
    private fun differential(v: Variable): Expression = Variable("d${v.identifier}")

    /** e^(u + C) is C·e^u, and e^C or e^(2C) is C: the constant is only determined up to a factor there. */
    private fun absorbConstant(e: Expression): Expression = e.transform { part ->
        val exponent = (part as? Power)?.takeIf { it.base == Constant.E }?.exponent
        if (exponent == null || !exponent.containsVariable(CONSTANT)) return@transform part
        fun isMultipleOfConstant(t: Expression) = t == CONSTANT || (t is Multiplications && t.operands.size == 2 && t.operands.contains(CONSTANT) && t.operands.any { it is Value })
        val terms = if (exponent is Additions) exponent.operands else listOf(exponent)
        val (withConstant, rest) = terms.partition { it.containsVariable(CONSTANT) }
        if (!withConstant.all { isMultipleOfConstant(it) }) return@transform part
        if (rest.isEmpty()) CONSTANT else Multiplications.of(CONSTANT, Power(Constant.E, if (rest.size == 1) rest.single() else Additions(rest)))
    }

    /**
     * h(x, y) = f(x)·g(y): by the factors where it is a product, otherwise from one value of y and one of x
     * (f(x) = h(x, c), g(y) = h(x₀, y)/h(x₀, c)) checked at other points. Null if it is not a product.
     */
    private fun separate(h: Expression, p: Problem, context: MathContext): Pair<Expression, Expression>? {
        val factors = if (h is Multiplications) h.operands else listOf(h)
        // A factor with both x and y cannot be put on one side; numbers go with f
        if (factors.none { it.containsVariable(p.variable) && it.containsVariable(p.y0) }) {
            val f = canonical(Multiplications.of(*factors.filter { !it.containsVariable(p.y0) }.toTypedArray()), context)
            val g = canonical(Multiplications.of(*factors.filter { it.containsVariable(p.y0) }.toTypedArray()), context)
            if (f != null && g != null && agrees(h, f, g, p)) return f to g
        }
        fun at(x: Rational?, y: Rational?): Expression? {
            val bindings = buildMap<Variable, Expression> { x?.let { put(p.variable, Value(it)) }; y?.let { put(p.y0, Value(it)) } }
            return canonical(h.substituteVariables(bindings), context)
        }
        for (c in listOf(0L, 1L, 2L, -1L, 3L)) {
            val f = at(null, Rational.of(c)) ?: continue
            if (f.isZero) continue
            for (x0 in listOf(1L, 2L, 3L, -1L)) {
                val base = at(Rational.of(x0), Rational.of(c))?.takeIf { !it.isZero && it.variables().isEmpty() } ?: continue
                val g = at(Rational.of(x0), null)?.let { canonical(Multiplications.of(it, Power(base, Value.MINUS_ONE)), context) } ?: continue
                if (agrees(h, f, g, p)) return f to g
            }
        }
        return null
    }

    private fun agrees(h: Expression, f: Expression, g: Expression, p: Problem): Boolean {
        // Letters other than x and y (a rate constant k) are numbers too
        val others = (h.variables() + f.variables() + g.variables() - p.variable - p.y0).toList()
        var checked = 0
        for (shift in listOf(0, 2)) for (x in SAMPLES) for (y in SAMPLES) {
            val point = mapOf(p.variable to x, p.y0 to y) + others.withIndex().associate { (j, v) -> v to CONSTANT_SAMPLES[(shift + j) % CONSTANT_SAMPLES.size].let { c -> if (c == 0.0) 1.0 else c } }
            val expected = h.evaluateNumerically(point)
            val actual = f.evaluateNumerically(point) * g.evaluateNumerically(point)
            if (!expected.isFinite() || !actual.isFinite()) continue
            if (abs(expected - actual) > 1e-9 * maxOf(1.0, abs(expected))) return false
            checked++
        }
        return checked >= 6
    }

    // ----- second order -----

    /** a·y″ + b·y′ + c·y = r(x) with numbers a ≠ 0, b, c. */
    private fun secondOrder(p: Problem, context: MathContext): Derivation? {
        val a = (partial(p.g, p.y2, context) as? Value)?.rational ?: return null
        val b = (partial(p.g, p.y1, context) as? Value)?.rational ?: return null
        val c = (partial(p.g, p.y0, context) as? Value)?.rational ?: return null
        if (a.isZero) return null
        val right = canonical(Multiplications.of(Value.MINUS_ONE, p.g.substituteVariables(mapOf(p.y2 to Value.ZERO, p.y1 to Value.ZERO, p.y0 to Value.ZERO))), context) ?: return null
        // y″, y′ and y must be all there is of y
        if (right.containsVariable(p.y0) || right.containsVariable(p.y1) || right.containsVariable(p.y2)) return null
        val y = p.unknown
        val x = p.variable
        val r = Variable("r")

        val discriminant = b * b - a * c * 4L
        val twoA = a * 2L
        val first: Expression
        val second: Expression
        val notes = mutableListOf<Pair<Expression, Expression>>()
        val characteristic = eq(
            Additions(listOfNotNull(
                Multiplications.of(Value(a), Power(r, Value.TWO)).takeIf { !a.isZero },
                Multiplications.of(Value(b), r).takeIf { !b.isZero },
                Value(c).takeIf { !c.isZero }
            )), Value.ZERO
        )
        notes.add(Variable("D") to Value(discriminant))
        when {
            discriminant.isNegative -> {
                // r = α ± βi: e^(αx)·(C₁ cos(βx) + C₂ sin(βx))
                val alpha = -b / twoA
                val beta = canonical(Multiplications.of(Power.squareRoot(Value(-discriminant)), Value(Rational.ONE / twoA.abs())), context) ?: return null
                val growth = canonical(Power(Constant.E, Multiplications.of(Value(alpha), x)), context) ?: return null
                val angle = canonical(Multiplications.of(beta, x), context) ?: return null
                // In degrees, cos(βx) is cos of (180/π·βx)°, so that the answer means the same as it does in radians
                val degrees = context.angleUnit == AngleUnit.DEGREES
                val inUnit = if (degrees) canonical(Multiplications.of(angle, Value(180L), Power(Constant.PI, Value.MINUS_ONE)), context) ?: return null else angle
                val unit = if (degrees) null else AngleUnit.RADIANS
                first = canonical(Multiplications.of(growth, TrigonometricFunction(TrigType.COS, inUnit, unit)), context) ?: return null
                second = canonical(Multiplications.of(growth, TrigonometricFunction(TrigType.SIN, inUnit, unit)), context) ?: return null
                notes.add(Variable("α") to Value(alpha))
                notes.add(Variable("β") to beta)
            }
            discriminant.isZero -> {
                val root = -b / twoA
                notes.add(r to Value(root))
                first = canonical(Power(Constant.E, Multiplications.of(Value(root), x)), context) ?: return null
                second = canonical(Multiplications.of(x, first), context) ?: return null
            }
            else -> {
                val root = Power.squareRoot(Value(discriminant))
                fun rootOf(sign: Long) = canonical(Multiplications.of(Additions(Value(-b), Multiplications.of(Value(sign), root)), Value(Rational.ONE / twoA)), context)
                val r1 = rootOf(1) ?: return null
                val r2 = rootOf(-1) ?: return null
                notes.add(Variable("r₁") to r1)
                notes.add(Variable("r₂") to r2)
                first = canonical(Power(Constant.E, Multiplications.of(r1, x)), context) ?: return null
                second = canonical(Power(Constant.E, Multiplications.of(r2, x)), context) ?: return null
            }
        }
        val homogeneous = canonical(Additions(Multiplications.of(CONSTANT_1, first), Multiplications.of(CONSTANT_2, second)), context) ?: return null
        val stages = mutableListOf(
            Stage(Kind.CHARACTERISTIC, notes, characteristic),
            Stage(Kind.HOMOGENEOUS, emptyList(), eq(y, homogeneous))
        )
        val typed = eq(Additions(listOfNotNull(
            Multiplications.of(Value(a), p.secondDerivative).takeIf { !a.isZero },
            Multiplications.of(Value(b), p.derivative).takeIf { !b.isZero },
            Multiplications.of(Value(c), y).takeIf { !c.isZero }
        )), right)

        var solution = homogeneous
        if (!right.isZero) {
            // For a right side of the kind c·xᵐ·eᵏˣ·cos/sin(wx): the coefficients of a guess of the same kind, which needs no integrals.
            // Otherwise y_p = −y₁·∫ y₂·r/(a·W) dx + y₂·∫ y₁·r/(a·W) dx, with the Wronskian W = y₁·y₂′ − y₁′·y₂
            val guessed = byCoefficients(a, b, c, right, x, context)
            if (guessed != null) {
                solution = canonical(Additions(homogeneous, guessed), context) ?: return null
                stages.add(Stage(Kind.UNDETERMINED_COEFFICIENTS, listOf(Variable("${y.identifier}ₚ") to guessed), eq(y, solution)))
            } else {
                val d1 = partial(first, x, context) ?: return null
                val d2 = partial(second, x, context) ?: return null
                val wronskian = canonical(Additions(Multiplications.of(first, d2), Multiplications.of(Value.MINUS_ONE, d1, second)), context) ?: return null
                val scale = Multiplications.of(Power(Multiplications.of(Value(a), wronskian), Value.MINUS_ONE), right)
                val integrand1 = canonical(Multiplications.of(second, scale), context) ?: return null
                val integrand2 = canonical(Multiplications.of(first, scale), context) ?: return null
                val integral1 = integral(integrand1, x, context) ?: return null
                val integral2 = integral(integrand2, x, context) ?: return null
                val particular = canonical(Additions(Multiplications.of(Value.MINUS_ONE, first, integral1), Multiplications.of(second, integral2)), context) ?: return null
                solution = canonical(Additions(homogeneous, particular), context) ?: return null
                stages.add(Stage(Kind.VARIATION_OF_PARAMETERS, listOf(Variable("W") to wronskian, Antiderivative(integrand1, x) to integral1, Antiderivative(integrand2, x) to integral2, Variable("${y.identifier}ₚ") to particular), eq(y, solution)))
            }
        }
        if (!satisfies(p, solution, context)) return null
        return Derivation(y, x, 2, typed, eq(y, solution), stages)
    }

    // ----- undetermined coefficients -----

    /** A complex number with rational parts. */
    private class Cx(val re: Rational, val im: Rational = Rational.ZERO) {
        val isZero: Boolean get() = re.isZero && im.isZero
        operator fun plus(o: Cx) = Cx(re + o.re, im + o.im)
        operator fun minus(o: Cx) = Cx(re - o.re, im - o.im)
        operator fun times(o: Cx) = Cx(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun div(o: Cx): Cx {
            val d = o.re * o.re + o.im * o.im
            return Cx((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }
        operator fun times(r: Rational) = Cx(re * r, im * r)
    }

    /** One term c·xᵐ·eᵏˣ·cos(wx) or sin(wx) (or without the trigonometric function: w = 0) of a right side. */
    private class Term(val coefficient: Rational, val power: Int, val k: Rational, val w: Rational, val isSine: Boolean)

    private fun termOf(term: Expression, x: Variable, context: MathContext): Term? {
        var coefficient = Rational.ONE
        var power = 0
        var k = Rational.ZERO
        var w = Rational.ZERO
        var isSine = false
        var trig = false
        /** u = r·x as r, or null. */
        fun slope(u: Expression): Rational? = when {
            u == x -> Rational.ONE
            u is Multiplications && u.operands.size == 2 && u.operands.contains(x) -> (u.operands.firstOrNull { it is Value } as? Value)?.rational
            else -> null
        }
        for (factor in if (term is Multiplications) term.operands else listOf(term)) {
            when {
                factor is Value -> coefficient *= factor.rational ?: return null
                factor == x -> power += 1
                factor is Power && factor.base == x -> {
                    val n = (factor.exponent as? Value)?.rational?.takeIf { !it.isFractional && !it.isNegative && it.numerator.value.bitLength() < 6 } ?: return null
                    power += n.numerator.value.toInt()
                }
                factor is Power && factor.base == Constant.E -> k += slope(factor.exponent) ?: return null
                factor is TrigonometricFunction && (factor.type == TrigType.SIN || factor.type == TrigType.COS) && !trig -> {
                    // Angles in degrees are another kind of right side: not here
                    if (factor.angleUnit == AngleUnit.DEGREES || (factor.angleUnit == null && context.angleUnit == AngleUnit.DEGREES)) return null
                    val slope = slope(factor.operand) ?: return null
                    if (slope.isZero) return null
                    trig = true
                    isSine = factor.type == TrigType.SIN
                    // sin(−wx) = −sin(wx), cos(−wx) = cos(wx)
                    if (slope.isNegative) { w = -slope; if (isSine) coefficient = -coefficient } else w = slope
                }
                else -> return null
            }
        }
        return Term(coefficient, power, k, w, isSine)
    }

    /**
     * A particular solution of a·y″ + b·y′ + c·y = r(x) for r a sum of terms c·xᵐ·eᵏˣ·cos(wx) or sin(wx), by writing each in
     * the complex exponentials: for p(x)·e^(λx) with λ = k + iw the left side gives e^(λx)·(P(λ)·q + P′(λ)·q′ + a·q″) for
     * y = q(x)·e^(λx), P being the characteristic polynomial. q has the degree of p, times x for each time λ is a root of P
     * (resonance). Null if r is not of that kind.
     */
    private fun byCoefficients(a: Rational, b: Rational, c: Rational, right: Expression, x: Variable, context: MathContext): Expression? {
        val terms = (if (right is Additions) right.operands else listOf(right)).map { termOf(it, x, context) ?: return null }
        // λ = k + iw (w ≥ 0): the polynomial in x of the e^(λx) part of the right side (the conjugate part is its mirror image)
        val groups = linkedMapOf<Pair<Rational, Rational>, MutableList<Cx>>()
        for (t in terms) {
            val polynomial = groups.getOrPut(t.k to t.w) { mutableListOf() }
            while (polynomial.size <= t.power) polynomial.add(Cx(Rational.ZERO))
            // cos(wx) = (e^(iwx) + e^(−iwx))/2, sin(wx) = (e^(iwx) − e^(−iwx))/(2i) = −i/2·e^(iwx) + …
            val weight = when {
                t.w.isZero -> Cx(t.coefficient)
                t.isSine -> Cx(Rational.ZERO, -t.coefficient / 2L)
                else -> Cx(t.coefficient / 2L)
            }
            polynomial[t.power] = polynomial[t.power] + weight
        }
        val parts = mutableListOf<Expression>()
        for ((key, p) in groups) {
            val (k, w) = key
            val lambda = Cx(k, w)
            val characteristic = Cx(a) * lambda * lambda + Cx(b) * lambda + Cx(c)
            val slope = Cx(a * 2L) * lambda + Cx(b)
            // How often λ is a root of P: the guess is multiplied by x that many times
            val multiplicity = if (!characteristic.isZero) 0 else if (!slope.isZero) 1 else 2
            val size = p.indexOfLast { !it.isZero } + 1
            if (size == 0) continue
            // T(x^n) = P·xⁿ + P′·n·xⁿ⁻¹ + a·n(n−1)·xⁿ⁻²; the unknowns are the coefficients of x^(multiplicity + j)
            val matrix = List(size) { MutableList(size + 1) { Cx(Rational.ZERO) } }
            for (j in 0 until size) {
                val n = multiplicity + j
                if (n < size) matrix[n][j] = matrix[n][j] + characteristic
                if (n - 1 in 0 until size) matrix[n - 1][j] = matrix[n - 1][j] + slope * Rational.of(n.toLong())
                if (n - 2 in 0 until size) matrix[n - 2][j] = matrix[n - 2][j] + Cx(a * (n.toLong() * (n - 1).toLong())) 
            }
            for (i in 0 until size) matrix[i][size] = p[i]
            val q = solveComplex(matrix, size) ?: return null
            // y = e^(kx)·x^s·Σ qⱼxʲ (real w = 0), or 2·Re(q·e^(iwx)) = 2Re(q)·cos(wx) − 2Im(q)·sin(wx)
            val growth: Expression = if (k.isZero) Value.ONE else Power(Constant.E, Multiplications.of(Value(k), x))
            for (j in 0 until size) {
                val monomial: Expression = when (val n = multiplicity + j) { 0 -> Value.ONE; 1 -> x; else -> Power(x, Value(n.toLong())) }
                val coefficient = q[j]
                if (w.isZero) {
                    if (!coefficient.re.isZero) parts.add(Multiplications.of(Value(coefficient.re), monomial, growth))
                } else {
                    val angle = Multiplications.of(Value(w), x)
                    val unit = if (context.angleUnit == AngleUnit.DEGREES) null else AngleUnit.RADIANS
                    val inUnit = if (unit == null) Multiplications.of(angle, Value(180L), Power(Constant.PI, Value.MINUS_ONE)) else angle
                    if (!coefficient.re.isZero) parts.add(Multiplications.of(Value(coefficient.re * 2L), monomial, growth, TrigonometricFunction(TrigType.COS, inUnit, unit)))
                    if (!coefficient.im.isZero) parts.add(Multiplications.of(Value(-coefficient.im * 2L), monomial, growth, TrigonometricFunction(TrigType.SIN, inUnit, unit)))
                }
            }
        }
        if (parts.isEmpty()) return null
        return canonical(if (parts.size == 1) parts.single() else Additions(parts), context)
    }

    /** Gaussian elimination over complex rationals of the n × (n + 1) augmented matrix; null if it is singular. */
    private fun solveComplex(m: List<MutableList<Cx>>, n: Int): List<Cx>? {
        for (col in 0 until n) {
            val pivot = (col until n).firstOrNull { !m[it][col].isZero } ?: return null
            val swap = m[col].toList(); for (j in 0..n) m[col][j] = m[pivot][j]; for (j in 0..n) m[pivot][j] = swap[j]
            val scale = m[col][col]
            for (j in col..n) m[col][j] = m[col][j] / scale
            for (row in 0 until n) {
                if (row == col || m[row][col].isZero) continue
                val factor = m[row][col]
                for (j in col..n) m[row][j] = m[row][j] - factor * m[col][j]
            }
        }
        return List(n) { m[it][n] }
    }

    // ----- conditions -----

    /** The constants of [general] chosen for y(x₀) = y₀ (and y′(x₀) = y₁). */
    private fun withConditions(p: Problem, general: Derivation, conditions: List<Expression>, context: MathContext): Derivation? {
        val y = p.unknown
        val x = p.variable
        val x0 = conditions[0]
        val around = x0.evaluateNumerically().takeIf { x0.variables().isEmpty() }
        val solution = (general.solution as? Equals)?.takeIf { it.left == y }?.right
        // Φ(y) = F(x) + C: the constant from the point, the solution stays an equation
        if (solution == null && general.solution is Equals && p.order == 1) {
            val notes = mutableListOf<Pair<Expression, Expression>>()
            val specific = implicitCondition(p, general.solution, x0, conditions[1], context, notes) ?: return null
            return Derivation(y, x, 1, general.equation, specific, general.stages + Stage(Kind.CONSTANTS, notes, specific))
        }
        val branches = if (general.solution is Alternatives) (general.solution as Alternatives).equations.mapNotNull { (it as? Equals)?.right } else listOfNotNull(solution)
        if (branches.isEmpty()) return null
        val found = mutableListOf<Expression>()
        val notes = mutableListOf<Pair<Expression, Expression>>()
        for (branch in branches) {
            val specific = if (p.order == 1) firstCondition(branch, x, x0, conditions[1], context, notes) else listOfNotNull(secondCondition(branch, x, x0, conditions[1], conditions[2], context, notes))
            for (candidate in specific) if (satisfies(p, candidate, context, around) && found.none { it == candidate }) found.add(candidate)
        }
        // A constant solution y = y₀ is not in the family (y′ = y², y(0) = 0)
        if (found.isEmpty() && p.order == 1 && satisfies(p, conditions[1], context, around)) found.add(conditions[1])
        if (found.isEmpty()) return null
        val result = if (found.size == 1) eq(y, found.single()) else Alternatives(found.map { eq(y, it) }, inline = true)
        val stages = general.stages + Stage(Kind.CONSTANTS, notes, result)
        return Derivation(y, x, p.order, general.equation, result, stages)
    }

    /**
     * The solutions through (x₀, y₀): one for each value of the constant that gives y(x₀) = y₀ (a periodic one is given once).
     * The constant is found by undoing what is done to it, and by the equation solver where that is not enough.
     */
    private fun firstCondition(branch: Expression, x: Variable, x0: Expression, y0: Expression, context: MathContext, notes: MutableList<Pair<Expression, Expression>>): List<Expression> {
        val atPoint = canonical(branch.substituteVariables(mapOf(x to x0)), context) ?: return emptyList()
        val values = (invert(atPoint, CONSTANT, y0)?.mapNotNull { canonical(it, context) } ?: run {
            val scratch = context.copy()
            val solved = Equals(atPoint, y0).solveForVariable(CONSTANT, scratch)
            if (scratch.shouldStop || solved == setOf(CONSTANT)) emptyList() else solved.toList()
        }).filter { value ->
            // Only what really gives y₀ at x₀ (for letters in it: at some values of them)
            val given = atPoint.substituteVariables(mapOf(CONSTANT to value))
            CONSTANT !in value.variables() && listOf(0, 2).all { shift ->
                val letters = (given.variables() + y0.variables()).toList()
                val point = letters.withIndex().associate { (j, v) -> v to CONSTANT_SAMPLES[(shift + j) % CONSTANT_SAMPLES.size] }
                val wanted = y0.evaluateNumerically(point)
                val actual = given.evaluateNumerically(point)
                !wanted.isFinite() || !actual.isFinite() || abs(actual - wanted) <= 1e-9 * maxOf(1.0, abs(wanted))
            }
        }
        if (values.isEmpty()) return emptyList()
        notes.add(CONSTANT to values.first())
        return values.mapNotNull { canonical(branch.substituteVariables(mapOf(CONSTANT to it)), context) }
    }

    /** What is left of an implicit solution Φ(y) = F(x) + C once the constant is chosen for the point: Φ(y) = F(x) + Φ(y₀) − F(x₀). */
    private fun implicitCondition(p: Problem, implicit: Equals, x0: Expression, y0: Expression, context: MathContext, notes: MutableList<Pair<Expression, Expression>>): Expression? {
        val left = implicit.left.substituteVariables(mapOf(p.y0 to y0))
        val right = implicit.right.substituteVariables(mapOf(p.variable to x0))
        val scratch = context.copy()
        val constants = Equals(left, right).solveForVariable(CONSTANT, scratch).filter { CONSTANT !in it.variables() }
        if (scratch.shouldStop || constants.size != 1) return null
        notes.add(CONSTANT to constants.single())
        return canonical(Equals(implicit.left, implicit.right.substituteVariables(mapOf(CONSTANT to constants.single()))), context)
    }

    /** y = C₁·y₁ + C₂·y₂ + y_p: the two conditions are two equations in C₁ and C₂, solved with Cramer's rule. */
    private fun secondCondition(branch: Expression, x: Variable, x0: Expression, y0: Expression, slope: Expression, context: MathContext, notes: MutableList<Pair<Expression, Expression>>): Expression? {
        fun at(e: Expression) = canonical(e.substituteVariables(mapOf(x to x0)), context)
        fun withConstants(c1: Expression, c2: Expression) = branch.substituteVariables(mapOf(CONSTANT_1 to c1, CONSTANT_2 to c2))
        // Value and slope at x₀ for C₁ = C₂ = 0 (the particular solution), and for each constant on its own
        val p0 = at(withConstants(Value.ZERO, Value.ZERO)) ?: return null
        val p1 = at(withConstants(Value.ZERO, Value.ZERO).let { partial(it, x, context) ?: return null }) ?: return null
        val a1 = at(withConstants(Value.ONE, Value.ZERO))?.let { canonical(Additions(it, p0.negate()), context) } ?: return null
        val a2 = at(withConstants(Value.ZERO, Value.ONE))?.let { canonical(Additions(it, p0.negate()), context) } ?: return null
        val b1 = at(partial(withConstants(Value.ONE, Value.ZERO), x, context) ?: return null)?.let { canonical(Additions(it, p1.negate()), context) } ?: return null
        val b2 = at(partial(withConstants(Value.ZERO, Value.ONE), x, context) ?: return null)?.let { canonical(Additions(it, p1.negate()), context) } ?: return null
        val rightValue = canonical(Additions(y0, p0.negate()), context) ?: return null
        val rightSlope = canonical(Additions(slope, p1.negate()), context) ?: return null
        val determinant = canonical(Additions(Multiplications.of(a1, b2), Multiplications.of(Value.MINUS_ONE, a2, b1)), context)?.takeIf { !it.isZero } ?: return null
        val c1 = canonical(Multiplications.of(Additions(Multiplications.of(rightValue, b2), Multiplications.of(Value.MINUS_ONE, rightSlope, a2)), Power(determinant, Value.MINUS_ONE)), context) ?: return null
        val c2 = canonical(Multiplications.of(Additions(Multiplications.of(a1, rightSlope), Multiplications.of(Value.MINUS_ONE, b1, rightValue)), Power(determinant, Value.MINUS_ONE)), context) ?: return null
        notes.add(CONSTANT_1 to c1)
        notes.add(CONSTANT_2 to c2)
        return canonical(withConstants(c1, c2), context)
    }

    // ----- checking -----

    private val SAMPLES = listOf(0.31, 0.77, 1.3, 1.9, 2.4)
    private val CONSTANT_SAMPLES = listOf(0.8, 1.7, -0.6, -1.5, -3.0, 0.3)
    private val AROUND = listOf(-0.2, -0.05, 0.02, 0.06, 0.12, 0.25)

    /** Whether [solution] (y as a function of x, with the constants) satisfies the equation at several points. */
    private fun satisfies(p: Problem, solution: Expression, context: MathContext, around: Double? = null): Boolean {
        val d1 = partial(solution, p.variable, context) ?: return false
        val d2 = if (p.order == 2) partial(d1, p.variable, context) ?: return false else Value.ZERO
        val residual = p.g.substituteVariables(mapOf(p.y0 to solution, p.y1 to d1, p.y2 to d2))
        val letters = (residual.variables() + solution.variables() + d1.variables()).toList()
        var checked = 0
        // Every x with several values for the constants: a root or a logarithm is not defined for all of them
        // A solution through (x₀, y₀) may only exist near x₀ (1/√(9/4 − 2eˣ) for x < 0.118): points there as well
        val xs = SAMPLES + (if (around != null && around.isFinite()) AROUND.map { around + it } else emptyList())
        for (x in xs) for (shift in CONSTANT_SAMPLES.indices) {
            val point = letters.withIndex().associate { (j, v) -> v to if (v == p.variable) x else CONSTANT_SAMPLES[(shift + j) % CONSTANT_SAMPLES.size] }
            val value = residual.evaluateNumerically(point)
            if (!value.isFinite()) continue
            val scale = 1.0 + listOf(solution, d1, d2).sumOf { abs(it.evaluateNumerically(point)).takeIf { v -> v.isFinite() } ?: 0.0 }
            if (abs(value) > 1e-7 * scale) return false
            checked++
        }
        return checked >= 3
    }
}
