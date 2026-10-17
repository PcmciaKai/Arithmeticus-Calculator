package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Expression
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
 * out ([Transforms]), or the fraction split into A/(x − r) + B/(x − s) + … with the numbers worked out.
 */
internal object RewriteSteps {

    fun steps(e: Expression, context: MathContext): ExpressionSolution? {
        val call = e as? NamedFunction ?: return null
        val body = call.arguments.first()
        return when (call.kind) {
            NamedFunctionKind.EXPAND -> Transforms.steps(body, Transform.EXPAND, context)
            NamedFunctionKind.APART -> apart(call, body, context)
            else -> null
        }
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
        fun linear(r: Rational): Expression = if (r.isZero) v else com.vortessence.calculator.core.math.expressions.Additions(v, Value(-r))
        fun powered(base: Expression, k: Int): Expression = if (k == 1) base else Power(base, Value(k.toLong()))

        // Q = a·(x − r₁)(x − r₂)…·(x² + px + q)
        val roots = decomposition.roots.sortedByDescending { it.first }
        val factors = roots.map { (r, m) -> powered(linear(r), m) } + listOfNotNull(decomposition.quadratic?.let { polynomial(it.polynomial) })
        val factored = Multiplications.of(*(listOfNotNull(Value(decomposition.leading).takeIf { it != Value.ONE }) + factors).toTypedArray())
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
                add(Note(letter, Value(decomposition.terms.firstOrNull { it.root == r && it.power == k }?.coefficient ?: Rational.ZERO)))
            }
            quadraticLetters?.let { (b, c) ->
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
