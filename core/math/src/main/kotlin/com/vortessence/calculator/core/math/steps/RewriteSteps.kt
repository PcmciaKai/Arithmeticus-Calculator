package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.DifferentialEquations
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Factorial
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.NamedFunction
import com.vortessence.calculator.core.math.expressions.NamedFunctionKind
import com.vortessence.calculator.core.math.expressions.PartialFractions
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.numbers.Rational

/**
 * The steps of the commands expand(e) and apart(e), which start from the argument: the products and powers multiplied
 * out ([Transforms]), or the fraction split into A/(x − r) + B/(x − s) + … with the numbers worked out; and of taylor(f, x, a, n),
 * which starts from the call: the derivatives and their values at a, put into the formula, then simplified.
 */
internal object RewriteSteps {

    fun steps(e: Expression, context: MathContext): ExpressionSolution? {
        val call = e as? NamedFunction ?: return null
        val body = call.arguments.first()
        return when (call.kind) {
            NamedFunctionKind.EXPAND -> Transforms.steps(body, Transform.EXPAND, context)
            NamedFunctionKind.APART -> apart(call, body, context)
            NamedFunctionKind.TAYLOR -> taylor(call, context)
            NamedFunctionKind.DSOLVE -> dsolve(call, context)
            else -> null
        }
    }

    private const val MAX_STEPS = 80

    /** The stages of the derivation of the solution of an ordinary differential equation, each with what it works out. */
    private fun dsolve(call: NamedFunction, context: MathContext): ExpressionSolution? {
        val scratch = context.copy()
        val derivation = DifferentialEquations.solve(call.arguments, scratch) ?: return null
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        val solution = Solver(context)
        solution.start(call)
        for (stage in derivation.stages) {
            val rule = when (stage.kind) {
                DifferentialEquations.Kind.STANDARD_FORM -> StepRule.ODE_STANDARD_FORM
                DifferentialEquations.Kind.INTEGRATING_FACTOR -> StepRule.ODE_INTEGRATING_FACTOR
                DifferentialEquations.Kind.LINEAR_SOLUTION -> StepRule.ODE_LINEAR_SOLUTION
                DifferentialEquations.Kind.SEPARATE -> StepRule.ODE_SEPARATE
                DifferentialEquations.Kind.INTEGRATE_BOTH -> StepRule.ODE_INTEGRATE_BOTH
                DifferentialEquations.Kind.SOLVE_FOR_UNKNOWN -> StepRule.ODE_SOLVE_FOR_UNKNOWN
                DifferentialEquations.Kind.CHARACTERISTIC -> StepRule.ODE_CHARACTERISTIC
                DifferentialEquations.Kind.HOMOGENEOUS -> StepRule.ODE_HOMOGENEOUS
                DifferentialEquations.Kind.VARIATION_OF_PARAMETERS -> StepRule.ODE_VARIATION_OF_PARAMETERS
                DifferentialEquations.Kind.CONSTANTS -> StepRule.ODE_CONSTANTS
            }
            val notes = stage.notes.map { (left, right) -> Note(left, right) }
            solution.apply(Match(rule, mapOf(emptyList<Int>() to stage.line), unmarked(solution.current), stage.line, notes = notes, showChange = false))
        }
        return solution.finish(derivation.solution)
    }

    /** f′, f″, f‴, then f⁽⁴⁾ */
    private fun derivativeMarks(k: Int): String = when (k) {
        0 -> ""
        1 -> "′"
        2 -> "″"
        3 -> "‴"
        else -> "⁽" + k.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("") + "⁾"
    }

    private fun taylor(call: NamedFunction, context: MathContext): ExpressionSolution? {
        val scratch = context.copy()
        val expansion = call.taylorExpansion(scratch) ?: return null
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        val n = expansion.order
        val point = expansion.point
        // A whole number is written into f′(0); anything else is named a
        val whole = ((point as? Value)?.rational)?.takeIf { !it.isFractional }?.numerator?.value?.toString()?.replace("-", "−")
        val at = whole ?: "a"

        val notes = buildList {
            if (whole == null) add(Note(TextExpression("a"), point))
            for (k in 0..n) {
                add(Note(TextExpression("f${derivativeMarks(k)}(x)"), expansion.derivatives[k]))
                add(Note(TextExpression("f${derivativeMarks(k)}($at)"), expansion.values[k]))
            }
        }
        // f(a) + f′(a)·(x − a) + f″(a)/2!·(x − a)² + …, the values put in as they are
        val base = expansion.base
        val terms = (0..n).filter { !expansion.values[it].isZero }.map { k ->
            val value = expansion.values[k]
            when (k) {
                0 -> value
                1 -> Multiplications.of(value, base)
                else -> Multiplications.of(value, Power(Factorial(Value(k.toLong())), Value.MINUS_ONE), Power(base, Value(k.toLong())))
            }
        }
        if (terms.isEmpty()) return null
        val formula = if (terms.size == 1) terms.single() else Additions(terms)
        val target = expansion.toSum()

        val solution = Solver(context)
        solution.start(call)
        solution.apply(Match(StepRule.TAYLOR_SERIES, mapOf(emptyList<Int>() to formula), call, formula, notes = notes, showChange = false))
        if (!solution.simplifyToward(target, MAX_STEPS)) return null
        return solution.finish(target)
    }

    private fun apart(call: NamedFunction, body: Expression, context: MathContext): ExpressionSolution? {
        val typed = normalize(body)
        val scratch = context.copy()
        val canonical = typed.canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        val v = call.apartVariable(canonical) ?: return null
        if (canonical.variables() != setOf(v)) return null
        val (numerator, denominator) = PartialFractions.fraction(canonical, v, context) ?: return null
        if (denominator.size < 2) return null
        val decomposition = PartialFractions.decomposeOverRationals(numerator, denominator) ?: return null
        val result = decomposition.toSum(v)
        // Nothing changes for a fraction that is already a partial fraction: 1/(x² + 1)
        if (writtenForm(typed) == writtenForm(result)) return null

        fun polynomial(c: List<Rational>) = PartialFractions.toExpression(c, v)
        fun fraction(n: List<Rational>) = Multiplications.of(polynomial(n), Power(polynomial(denominator), Value.MINUS_ONE))
        fun linear(r: Rational): Expression = PartialFractions.linearFactor(r, v).expression
        fun powered(base: Expression, k: Int): Expression = if (k == 1) base else Power(base, Value(k.toLong()))

        // Q = a·(x − r₁)(x − r₂)…·(x² + px + q)
        val roots = decomposition.roots.sortedByDescending { it.first }
        val factors = roots.map { (r, m) -> powered(linear(r), m) } + listOfNotNull(decomposition.quadratic?.let { polynomial(it.polynomial) })
        // x − p/q is written q·x − p; the q's go into the number in front
        val leading = roots.fold(decomposition.leading) { l, (r, m) -> l / Rational.of(r.denominator).pow(m.toLong()) }
        val factored = Multiplications.of(*(listOfNotNull(Value(leading).takeIf { it != Value.ONE }) + factors).toTypedArray())
        val remainder = if (decomposition.quotient.isEmpty()) numerator else decomposition.remainder

        // A/(x − r₁) + B/(x − r₂) + …, with letters A, B … for the numbers; (B·x + C)/(x² + px + q) for the quadratic
        val letters = roots.flatMap { (r, m) -> (1..m).map { r to it } }.mapIndexed { i, (r, k) -> Triple(Variable(('A' + i).toString()), r, k) }
        val quadraticLetters = decomposition.quadratic?.let { Variable(('A' + letters.size).toString()) to Variable(('A' + letters.size + 1).toString()) }
        val setup = com.vortessence.calculator.core.math.expressions.Additions(
            letters.map { (letter, r, k) -> Multiplications.of(letter, Power(powered(linear(r), k), Value.MINUS_ONE)) } +
                listOfNotNull(quadraticLetters?.let { (b, c) ->
                    Multiplications.of(
                        com.vortessence.calculator.core.math.expressions.Additions(Multiplications.of(b, v), c),
                        Power(polynomial(decomposition.quadratic!!.polynomial), Value.MINUS_ONE)
                    )
                })
        )

        val notes = buildList {
            if (decomposition.quotient.isNotEmpty()) {
                add(Note(fraction(numerator), com.vortessence.calculator.core.math.expressions.Additions(polynomial(decomposition.quotient), fraction(decomposition.remainder))))
            } else if (writtenForm(typed) != writtenForm(fraction(numerator))) {
                add(Note(typed, fraction(numerator)))
            }
            // Already factored as it is: nothing to say
            if (writtenForm(polynomial(denominator)) != writtenForm(factored)) add(Note(polynomial(denominator), factored))
            add(Note(fraction(remainder), setup))
            for ((letter, r, k) in letters) {
                val value = decomposition.terms.firstOrNull { it.root == r && it.power == k }?.coefficient ?: Rational.ZERO
                add(Note(letter, Value(value * Rational.of(r.denominator).pow(k.toLong()))))
            }
            quadraticLetters?.let { (b, c) ->
                // As the numerator is written in the result: whole numbers over the number in front of the denominator
                add(Note(b, Value(decomposition.quadratic!!.slope)))
                add(Note(c, Value(decomposition.quadratic.offset)))
            }
        }
        val solution = Solver(context)
        solution.start(typed)
        solution.apply(Match(StepRule.PARTIAL_FRACTIONS, mapOf(emptyList<Int>() to result), typed, result, notes = notes))
        return solution.finish(result)
    }
}
