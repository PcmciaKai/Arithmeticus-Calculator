package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.Value

/**
 * Simplifications as steps of a solution, one at a time, as they would be written by hand:
 * multiplying numbers (2·2x = 4x), combining powers (x·x² = x³), cancelling (x·1/x = 1),
 * multiplying a number into a sum (−(x + 1) = −x − 1), combining like terms (2x + 3x = 5x)
 * and adding numbers. Highlighted parts count as what they contain, e.g. the 2 in 2·[2x].
 */
internal object SimplificationSteps {

    /** The first simplification that applies to [e], in the order of the rules above. */
    fun match(e: Expression, context: MathContext): Match? {
        val products = pathsOf(e) { it is Multiplications }.map { it to factors(at(e, it), it) }
        val sums = pathsOf(e) { it is Additions }.map { it to terms(at(e, it), it) }
        return products.firstNotNullOfOrNull { (_, factors) -> multiplyNumbers(factors, context) }
            ?: products.firstNotNullOfOrNull { (_, factors) -> combinePowers(factors, context) }
            ?: products.firstNotNullOfOrNull { (_, factors) -> distribute(factors, context) }
            ?: sums.firstNotNullOfOrNull { (_, terms) -> combineLikeTerms(terms, context) }
            ?: sums.firstNotNullOfOrNull { (_, terms) -> addNumbers(terms, context) }
    }

    /** A part of a product or sum (without its highlighting) and where it is. */
    private class Part(val path: Path, val expression: Expression)

    /** The factors of the product [e], also those inside highlighted products. */
    private fun factors(e: Expression, path: Path): List<Part> = when (e) {
        is Marked -> factors(e.inner, path + 0)
        is Multiplications -> e.operands.flatMapIndexed { i, op -> factors(op, path + i) }
        else -> listOf(Part(path, unmarked(e)))
    }

    /** The terms of the sum [e], also those inside highlighted sums. */
    private fun terms(e: Expression, path: Path): List<Part> = when (e) {
        is Marked -> terms(e.inner, path + 0)
        is Additions -> e.operands.flatMapIndexed { i, op -> terms(op, path + i) }
        else -> listOf(Part(path, unmarked(e)))
    }

    private fun isNumber(e: Expression) = e is Value && e.rational != null

    /**
     * The [parts] merged into one: the first is replaced by [result], the others are
     * removed (replaced by [neutral]).
     */
    private fun merge(rule: StepRule, parts: List<Part>, before: Expression, result: Expression, neutral: Expression) = Match(
        rule,
        mapOf(parts.first().path to result),
        before,
        result,
        removals = parts.drop(1).associate { it.path to neutral }
    )

    /** 2·3 = 6. A factor −1 is the sign and stays in front: −2·3x = −6x. */
    private fun multiplyNumbers(factors: List<Part>, context: MathContext): Match? {
        val numbers = factors.filter { isNumber(it.expression) && it.expression != Value.MINUS_ONE }
        if (numbers.size < 2) return null
        val product = Multiplications(numbers.map { it.expression })
        return merge(StepRule.MULTIPLY_NUMBERS, numbers, product, product.canonicalize(context.copy()), Value.ONE)
    }

    /** x·x² = x³, and x·1/x = 1. */
    private fun combinePowers(factors: List<Part>, context: MathContext): Match? {
        fun base(e: Expression) = if (e is Power) e.base else e
        fun exponent(e: Expression) = if (e is Power) e.exponent else Value.ONE
        val group = factors.filterNot { it.expression is Value }
            .groupBy { base(it.expression) }
            .values.firstOrNull { it.size >= 2 } ?: return null
        val combined = Power(base(group.first().expression), Additions(group.map { exponent(it.expression) }))
            .canonicalize(context.copy())
        val rule = if (combined == Value.ONE) StepRule.CANCEL else StepRule.COMBINE_POWERS
        return merge(rule, group, Multiplications(group.map { it.expression }), combined, Value.ONE)
    }

    /** −(x + 1) = −x − 1, 2(x + 1) = 2x + 2: a number times a sum. */
    private fun distribute(factors: List<Part>, context: MathContext): Match? {
        if (factors.size != 2) return null
        val number = factors.firstOrNull { isNumber(it.expression) } ?: return null
        val sum = factors.firstOrNull { it.expression is Additions } ?: return null
        val c = number.expression
        val expanded = Additions((sum.expression as Additions).operands.map { Multiplications(c, it).canonicalize(context.copy()) })
        return Match(
            StepRule.DISTRIBUTE,
            mapOf(sum.path to expanded),
            Multiplications(c, sum.expression),
            expanded,
            removals = mapOf(number.path to Value.ONE)
        )
    }

    /** 2x + 3x = 5x, x − x = 0. */
    private fun combineLikeTerms(terms: List<Part>, context: MathContext): Match? {
        fun rest(e: Expression): Expression? {
            val factors = if (e is Multiplications) e.operands else listOf(e)
            val nonNumbers = factors.filterNot { isNumber(it) }
            return if (nonNumbers.isEmpty()) null else Multiplications.of(*nonNumbers.toTypedArray())
        }
        val group = terms.filter { rest(it.expression) != null }
            .groupBy { rest(it.expression) }
            .values.firstOrNull { it.size >= 2 } ?: return null
        val sum = Additions(group.map { it.expression })
        return merge(StepRule.COMBINE_LIKE_TERMS, group, sum, sum.canonicalize(context.copy()), Value.ZERO)
    }

    /** 2 + 3 = 5 */
    private fun addNumbers(terms: List<Part>, context: MathContext): Match? {
        val numbers = terms.filter { isNumber(it.expression) }
        if (numbers.size < 2) return null
        val sum = Additions(numbers.map { it.expression })
        return merge(StepRule.ADD_NUMBERS, numbers, sum, sum.canonicalize(context.copy()), Value.ZERO)
    }
}
