package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.DifferentialEquations
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Factorial
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.NamedFunction
import com.vortessence.arithmeticus.core.math.expressions.NamedFunctionKind
import com.vortessence.arithmeticus.core.math.expressions.PartialFractions
import com.vortessence.arithmeticus.core.math.expressions.tidyForDisplay
import com.vortessence.arithmeticus.core.math.expressions.Power
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.numbers.Rational

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


    /**
     * The stages of the derivation of the solution of an ordinary differential equation, each with what it works out: what is
     * asked for, the type, the method, the constants.
     */
    private fun dsolve(call: NamedFunction, context: MathContext): ExpressionSolution? {
        val scratch = context.copy()
        val derivation = DifferentialEquations.solve(call.arguments, scratch) ?: return null
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        val solution = Solver(context)
        solution.start(call)
        for (stage in derivation.stages) {
            val rule = when (stage.kind) {
                DifferentialEquations.Kind.PROBLEM -> StepRule.ODE_PROBLEM
                DifferentialEquations.Kind.INITIAL_VALUE_PROBLEM -> StepRule.ODE_INITIAL_VALUE_PROBLEM
                DifferentialEquations.Kind.TYPE_SEPARABLE -> StepRule.ODE_TYPE_SEPARABLE
                DifferentialEquations.Kind.TYPE_LINEAR -> StepRule.ODE_TYPE_LINEAR
                DifferentialEquations.Kind.TYPE_SECOND_ORDER_HOMOGENEOUS -> StepRule.ODE_TYPE_SECOND_ORDER_HOMOGENEOUS
                DifferentialEquations.Kind.TYPE_SECOND_ORDER_INHOMOGENEOUS -> StepRule.ODE_TYPE_SECOND_ORDER_INHOMOGENEOUS
                DifferentialEquations.Kind.INTEGRATING_FACTOR -> StepRule.ODE_INTEGRATING_FACTOR
                DifferentialEquations.Kind.LINEAR_SOLUTION -> StepRule.ODE_LINEAR_SOLUTION
                DifferentialEquations.Kind.SEPARATE -> StepRule.ODE_SEPARATE
                DifferentialEquations.Kind.INTEGRATE_BOTH -> StepRule.ODE_INTEGRATE_BOTH
                DifferentialEquations.Kind.INTEGRALS -> StepRule.ODE_INTEGRALS
                DifferentialEquations.Kind.ADD -> StepRule.ADD_TO_BOTH_SIDES
                DifferentialEquations.Kind.SUBTRACT -> StepRule.SUBTRACT_FROM_BOTH_SIDES
                DifferentialEquations.Kind.MULTIPLY -> StepRule.MULTIPLY_BOTH_SIDES
                DifferentialEquations.Kind.DIVIDE -> StepRule.DIVIDE_BOTH_SIDES
                DifferentialEquations.Kind.EXPONENTIATE -> StepRule.EXPONENTIATE_BOTH_SIDES
                DifferentialEquations.Kind.LOGARITHM -> StepRule.LOGARITHM_BOTH_SIDES
                DifferentialEquations.Kind.SQUARE_ROOT -> StepRule.SQUARE_ROOT_BOTH_SIDES
                DifferentialEquations.Kind.ROOT -> StepRule.NTH_ROOT_BOTH_SIDES
                DifferentialEquations.Kind.SQUARE -> StepRule.SQUARE_BOTH_SIDES
                DifferentialEquations.Kind.POWER -> StepRule.POWER_BOTH_SIDES
                DifferentialEquations.Kind.INVERSE_FUNCTION -> StepRule.INVERSE_FUNCTION_BOTH_SIDES
                DifferentialEquations.Kind.ABSOLUTE_VALUE -> StepRule.ODE_ABSOLUTE_VALUE
                DifferentialEquations.Kind.NEW_CONSTANT -> StepRule.ODE_NEW_CONSTANT
                DifferentialEquations.Kind.SOLVE_FOR_UNKNOWN -> StepRule.ODE_SOLVE_FOR_UNKNOWN
                DifferentialEquations.Kind.TRY_EXPONENTIAL -> StepRule.ODE_TRY_EXPONENTIAL
                DifferentialEquations.Kind.FACTOR_EXPONENTIAL -> StepRule.ODE_FACTOR_EXPONENTIAL
                DifferentialEquations.Kind.CHARACTERISTIC -> StepRule.ODE_CHARACTERISTIC
                DifferentialEquations.Kind.CHARACTERISTIC_ROOTS -> StepRule.ODE_CHARACTERISTIC_ROOTS
                DifferentialEquations.Kind.HOMOGENEOUS_DISTINCT -> StepRule.ODE_HOMOGENEOUS_DISTINCT
                DifferentialEquations.Kind.HOMOGENEOUS_DOUBLE -> StepRule.ODE_HOMOGENEOUS_DOUBLE
                DifferentialEquations.Kind.HOMOGENEOUS_COMPLEX -> StepRule.ODE_HOMOGENEOUS_COMPLEX
                DifferentialEquations.Kind.VARIATION_OF_PARAMETERS -> StepRule.ODE_VARIATION_OF_PARAMETERS
                DifferentialEquations.Kind.UNDETERMINED_COEFFICIENTS -> StepRule.ODE_UNDETERMINED_COEFFICIENTS
                DifferentialEquations.Kind.DERIVATIVE_OF_SOLUTION -> StepRule.ODE_DERIVATIVE_OF_SOLUTION
                DifferentialEquations.Kind.INSERT_CONDITIONS -> StepRule.ODE_INSERT_CONDITIONS
                DifferentialEquations.Kind.SIMPLIFY -> StepRule.SIMPLIFY
                DifferentialEquations.Kind.SOLVE_CONSTANTS -> StepRule.ODE_SOLVE_CONSTANTS
                DifferentialEquations.Kind.PUT_CONSTANTS -> StepRule.ODE_PUT_CONSTANTS
                DifferentialEquations.Kind.CONSTANTS -> StepRule.ODE_CONSTANTS
            }
            val notes = stage.notes.map { (left, right) -> Note(left, right) }
            // Shown as the result is: 1/(1/b − 2kt) as b/(1 − 2kbt)
            val line = if (stage.tidy) tidyForDisplay(stage.line, context) else stage.line
            solution.apply(Match(rule, mapOf(emptyList<Int>() to line), unmarked(solution.current), line, notes = notes, operation = stage.operation, showChange = false))
        }
        return solution.finish(tidyForDisplay(derivation.solution, context))
    }

    /** f′, f″, f‴, then f⁽⁴⁾ */
    private fun derivativeMarks(k: Int): String = when (k) {
        0 -> ""
        1 -> "′"
        2 -> "″"
        3 -> "‴"
        else -> "⁽" + k.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("") + "⁾"
    }

    /** Tₙ: the degree as small lowered digits */
    private fun subscript(n: Int): String = n.toString().map { "₀₁₂₃₄₅₆₇₈₉"[it - '0'] }.joinToString("")

    /**
     * The steps of taylor(f, x, a, n) as in a textbook: what is asked for (f, a and n), the formula for this n, the derivatives,
     * their values at a, the values put into the formula, then the factorials worked out and the terms that are 0 left out.
     */
    private fun taylor(call: NamedFunction, context: MathContext): ExpressionSolution? {
        val scratch = context.copy()
        val expansion = call.taylorExpansion(scratch) ?: return null
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        val n = expansion.order
        val point = expansion.point
        val x = expansion.variable.identifier
        // A whole number is written into f′(0); anything else is named a
        val whole = ((point as? Value)?.rational)?.takeIf { !it.isFractional }?.numerator?.value?.toString()?.replace("-", "−")
        val at = whole ?: "a"
        fun f(k: Int, argument: String) = TextExpression("f${derivativeMarks(k)}($argument)")
        fun rows(rows: List<Expression>) = if (rows.size == 1) rows.single() else Alternatives(rows)

        // f(a) + f′(a)·(x − a) + f″(a)/2!·(x − a)² + …, written as it is read: the fraction before the power
        val base = expansion.base
        fun term(k: Int, value: Expression): Expression = when (k) {
            0 -> value
            1 -> WrittenOut(listOf(value, base))
            else -> WrittenOut(listOf(LiteralFraction(value, Factorial(Value(k.toLong()))), Power(base, Value(k.toLong()))))
        }
        fun polynomial(values: List<Expression>) = Additions((0..n).map { term(it, values[it]) }).let { if (n == 0) it.operands.single() else it }
        val name = TextExpression("T${subscript(n)}($x)")
        val general = Equals(name, polynomial((0..n).map { f(it, at) }))

        val problemNotes = listOf(Note(TextExpression("a"), point), Note(TextExpression("n"), Value(n.toLong())))
        val derivatives = rows((0..n).map { k -> Equals(f(k, x), expansion.derivatives[k]) })
        // f″(0) = −sin(0) = 0: the point put in (boxed, and kept as it is: 3·1), then its value (only the value where nothing is put in)
        val values = rows((0..n).map { k ->
            val derivative = expansion.derivatives[k]
            val inserted = derivative.transform { if (it == expansion.variable) Marked(point, PENDING_COLOR, boxed = true, keep = true) else it }
            Equals(f(k, at), if (derivative.containsVariable(expansion.variable)) Equals(inserted, expansion.values[k]) else expansion.values[k])
        })
        // The values put in, each boxed and kept as it is, also 0 and 1
        val inserted = Equals(name, polynomial(expansion.values.map { Marked(it, PENDING_COLOR, boxed = true, keep = true) }))
        val target = expansion.toSum()
        if (target.isZero && n > 0 && expansion.values.all { it.isZero }) return null
        val factorials = (2..n).map { k -> Note(Factorial(Value(k.toLong())), Value((1..k).fold(1L) { p, i -> p * i })) }

        val solution = Solver(context)
        solution.start(call)
        fun step(rule: StepRule, line: Expression, notes: List<Note> = emptyList(), boxed: Boolean = true) =
            solution.apply(Match(rule, mapOf(emptyList<Int>() to line), unmarked(solution.current), line, notes = notes, showChange = false, boxReplacements = boxed))
        step(StepRule.TAYLOR_PROBLEM, Equals(f(0, x), expansion.derivatives[0]), problemNotes)
        step(StepRule.TAYLOR_FORMULA, general)
        step(StepRule.TAYLOR_DERIVATIVES, derivatives)
        step(StepRule.TAYLOR_VALUES, values, boxed = false)
        step(StepRule.TAYLOR_SERIES, inserted, boxed = false)
        step(StepRule.TAYLOR_SIMPLIFY, Equals(name, target), factorials)
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
        val setup = com.vortessence.arithmeticus.core.math.expressions.Additions(
            letters.map { (letter, r, k) -> Multiplications.of(letter, Power(powered(linear(r), k), Value.MINUS_ONE)) } +
                listOfNotNull(quadraticLetters?.let { (b, c) ->
                    Multiplications.of(
                        com.vortessence.arithmeticus.core.math.expressions.Additions(Multiplications.of(b, v), c),
                        Power(polynomial(decomposition.quadratic.polynomial), Value.MINUS_ONE)
                    )
                })
        )

        val notes = buildList {
            if (decomposition.quotient.isNotEmpty()) {
                add(Note(fraction(numerator), com.vortessence.arithmeticus.core.math.expressions.Additions(polynomial(decomposition.quotient), fraction(decomposition.remainder))))
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
                add(Note(b, Value(decomposition.quadratic.slope)))
                add(Note(c, Value(decomposition.quadratic.offset)))
            }
        }
        val solution = Solver(context)
        solution.start(typed)
        solution.apply(Match(StepRule.PARTIAL_FRACTIONS, mapOf(emptyList<Int>() to result), typed, result, notes = notes))
        return solution.finish(result)
    }
}
