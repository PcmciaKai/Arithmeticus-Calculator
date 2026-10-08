package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Power
import com.vortessence.calculator.core.math.expressions.Value

/**
 * Simplifications as steps of a solution, one at a time, as they would be written by hand:
 * powers of numbers (3² = 9), multiplying numbers (2·2x = 4x), powers of powers and of products
 * ((x²)³ = x⁶, (2x)³ = 2³x³), combining powers (x·x² = x³), cancelling (x·1/x = 1), combining
 * like terms (2x + 3x = 5x), adding numbers, fractions with the same denominator
 * ((x + 1)/x − 1/x = (x + 1 − 1)/x) and multiplying out (−(x + 1) = −x − 1,
 * (x + 1)(x − 1) = x² − x + x − 1). Multiplying out comes last, so that fractions are combined
 * rather than split.
 *
 * Multiplying out only happens where the result, [match]'s target, does not keep the sum: the
 * calculator leaves (x + 1)² as it is, but (x + 1)² − x² is 2x + 1.
 * Highlighted parts count as what they contain, e.g. the 2 in 2·[2x].
 */
internal object SimplificationSteps {

    /** Powers of sums are multiplied out up to this exponent. */
    private const val MAX_EXPANDED_POWER = 4

    /** The first simplification of [e] toward [target] that applies, in the order of the rules above. */
    fun match(e: Expression, target: Expression, context: MathContext): Match? {
        val keeps = { sum: Expression -> target.containsExpressionMatching { it == sum } }
        val powers = pathsOf(e) { it is Power }
        val products = pathsOf(e) { it is Multiplications }.map { factors(at(e, it), it) }
        val sums = pathsOf(e) { it is Additions }.map { terms(at(e, it), it) }
        return powers.firstNotNullOfOrNull { numberPower(at(e, it), it, context) }
            ?: products.firstNotNullOfOrNull { multiplyNumbers(it, context) }
            ?: powers.firstNotNullOfOrNull { powerOfPower(at(e, it), it, context) }
            ?: powers.firstNotNullOfOrNull { powerOfProduct(at(e, it), it) }
            ?: products.firstNotNullOfOrNull { combinePowers(it, context) }
            ?: sums.firstNotNullOfOrNull { combineLikeTerms(it, context) }
            ?: sums.firstNotNullOfOrNull { addNumbers(it, context) }
            ?: sums.firstNotNullOfOrNull { combineFractions(it, context) }
            ?: products.firstNotNullOfOrNull { distribute(it, context, keeps) }
            ?: products.firstNotNullOfOrNull { expandProduct(it, context, keeps) }
            ?: powers.firstNotNullOfOrNull { expandPower(at(e, it), it, context, keeps) }
            ?: pathsOf(e) { it is LiteralPower }.firstNotNullOfOrNull { asRootOrFraction(at(e, it) as LiteralPower, it) }
    }

    /** A part of a product or sum (without its highlighting) and where it is. */
    private class Part(val path: Path, val expression: Expression)

    /** The factors of the product [e], also those inside highlighted products. */
    private fun factors(e: Expression, path: Path): List<Part> = when (e) {
        is Marked -> factors(e.inner, path + 0)
        // A fraction kept in front of a power (½·x^(−½)) is a number to multiply
        is LiteralPower -> if (e.inner is Value) listOf(Part(path + 0, e.inner)) else listOf(Part(path, unmarked(e)))
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

    private fun isInteger(e: Expression) = e is Value && e.rational?.isFractional == false

    /** A factor xⁿ with a negative number n, i.e. one that is written in a denominator. */
    private fun isReciprocal(e: Expression) = e is Power && (e.exponent as? Value)?.isNegative == true

    private fun canonical(e: Expression, context: MathContext) = e.canonicalize(context.copy())

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

    /** 2³ = 8, 3⁻¹ = 1/3, and x⁰ = 1, x¹ = x */
    private fun numberPower(e: Expression, path: Path, context: MathContext): Match? {
        val power = unmarked(e) as? Power ?: return null
        if (power.exponent == Value.ZERO) return Match(StepRule.NUMBER_POWER, mapOf(path to Value.ONE), power, Value.ONE)
        if (power.exponent == Value.ONE) return Match(StepRule.NUMBER_POWER, mapOf(path to power.base), power, power.base)
        if (!isNumber(power.base) || !isInteger(power.exponent)) return null
        val value = canonical(power, context).takeIf { isNumber(it) } ?: return null
        val isDivision = power.exponent == Value.MINUS_ONE && (power.base as Value).rational?.isFractional == true
        return Match(if (isDivision) StepRule.DIVIDE_BY_FRACTION else StepRule.NUMBER_POWER, mapOf(path to value), power, value)
    }

    /** 2·3 = 6. A factor −1 is the sign and stays in front: −2·3x = −6x. */
    private fun multiplyNumbers(factors: List<Part>, context: MathContext): Match? {
        val numbers = factors.filter { isNumber(it.expression) && it.expression != Value.MINUS_ONE }
        if (numbers.size < 2) return null
        val product = Multiplications(numbers.map { it.expression })
        return merge(StepRule.MULTIPLY_NUMBERS, numbers, product, canonical(product, context), Value.ONE)
    }

    /** (x²)³ = x⁶, √(x²) = x */
    private fun powerOfPower(e: Expression, path: Path, context: MathContext): Match? {
        val power = unmarked(e) as? Power ?: return null
        val inner = power.base as? Power ?: return null
        val result = canonical(Power(inner.base, Multiplications(inner.exponent, power.exponent)), context)
        return Match(StepRule.POWER_OF_POWER, mapOf(path to result), power, result)
    }

    /** (2x)³ = 2³x³, also in a denominator: 1/(3x) = 1/3·1/x */
    private fun powerOfProduct(e: Expression, path: Path): Match? {
        val power = unmarked(e) as? Power ?: return null
        val product = power.base as? Multiplications ?: return null
        if (!isInteger(power.exponent)) return null
        val result = normalize(Multiplications(product.operands.map { Power(it, power.exponent) }))
        return Match(StepRule.POWER_OF_PRODUCT, mapOf(path to result), power, result)
    }

    /** x·x² = x³, and x·1/x = 1. */
    private fun combinePowers(factors: List<Part>, context: MathContext): Match? {
        fun base(e: Expression) = if (e is Power) e.base else e
        fun exponent(e: Expression) = if (e is Power) e.exponent else Value.ONE
        val group = factors.filterNot { it.expression is Value }
            .groupBy { base(it.expression) }
            .values.firstOrNull { it.size >= 2 } ?: return null
        val combined = canonical(Power(base(group.first().expression), Additions(group.map { exponent(it.expression) })), context)
        val rule = if (combined == Value.ONE) StepRule.CANCEL else StepRule.COMBINE_POWERS
        return merge(rule, group, Multiplications(group.map { it.expression }), combined, Value.ONE)
    }

    /** −(x + 1) = −x − 1, 2(x + 1) = 2x + 2: a number times a sum. */
    private fun distribute(factors: List<Part>, context: MathContext, keeps: (Expression) -> Boolean): Match? {
        if (factors.size != 2) return null
        val number = factors.firstOrNull { isNumber(it.expression) } ?: return null
        val sum = factors.firstOrNull { it.expression is Additions } ?: return null
        if (keeps(sum.expression)) return null
        val c = number.expression
        val expanded = Additions((sum.expression as Additions).operands.map { canonical(Multiplications(c, it), context) })
        return Match(
            StepRule.DISTRIBUTE,
            mapOf(sum.path to expanded),
            Multiplications(c, sum.expression),
            expanded,
            removals = mapOf(number.path to Value.ONE)
        )
    }

    /** x(x + 1) = x² + x, (x + 1)(x − 1) = x² − x + x − 1: every term times every term. */
    private fun expandProduct(factors: List<Part>, context: MathContext, keeps: (Expression) -> Boolean): Match? {
        // Numbers stay in front, the rest is multiplied into the sums
        val multiplied = factors.filterNot { isNumber(it.expression) }
        val sums = multiplied.filter { it.expression is Additions }
        if (sums.isEmpty() || multiplied.size < 2 || sums.any { keeps(it.expression) }) return null
        val termLists = multiplied.map { (it.expression as? Additions)?.operands ?: listOf(it.expression) }
        val combinations = termLists.fold(listOf(emptyList<Expression>())) { acc, terms -> acc.flatMap { c -> terms.map { c + it } } }
        val expanded = Additions(combinations.map { canonical(Multiplications(it), context) })
        return merge(StepRule.EXPAND, multiplied, Multiplications(multiplied.map { it.expression }), expanded, Value.ONE)
    }

    /** (x + 1)² = x² + 2x + 1 */
    private fun expandPower(e: Expression, path: Path, context: MathContext, keeps: (Expression) -> Boolean): Match? {
        val power = unmarked(e) as? Power ?: return null
        val sum = power.base as? Additions ?: return null
        val n = (power.exponent as? Value)?.rational?.takeIf { !it.isFractional }?.toDouble()?.toInt() ?: return null
        if (n !in 2..MAX_EXPANDED_POWER || keeps(sum)) return null
        val expanded = canonical(Additions(List(n) { sum.operands }
            .fold(listOf(emptyList<Expression>())) { acc, terms -> acc.flatMap { c -> terms.map { c + it } } }
            .map { Multiplications(it) }), context)
        if (expanded !is Additions) return null
        return Match(StepRule.EXPAND_POWER, mapOf(path to expanded), power, expanded)
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
        return merge(StepRule.COMBINE_LIKE_TERMS, group, sum, canonical(sum, context), Value.ZERO)
    }

    /**
     * (x + 1)/x − 1/x = (x + 1 − 1)/x: fractions with the same denominator, where their
     * numerators simplify together (otherwise multiplying out would split them again).
     */
    private fun combineFractions(terms: List<Part>, context: MathContext): Match? {
        fun factorsOf(e: Expression) = if (e is Multiplications) e.operands else listOf(e)
        fun denominator(e: Expression) = factorsOf(e).filter { isReciprocal(it) }
        fun numerator(e: Expression) = Multiplications.of(*factorsOf(e).filterNot { isReciprocal(it) }.toTypedArray())
        fun termCount(e: Expression) = if (e is Additions) e.operands.size else 1
        val group = terms.filter { denominator(it.expression).isNotEmpty() }
            .groupBy { Multiplications.of(*denominator(it.expression).toTypedArray()) }
            .values.firstOrNull { group ->
                val numerators = group.map { numerator(it.expression) }
                group.size >= 2 && termCount(canonical(Additions(numerators), context)) < numerators.sumOf { termCount(it) }
            } ?: return null
        val denominatorFactors = denominator(group.first().expression)
        val numerators = group.map { numerator(it.expression) }
        val combined = normalize(Multiplications(listOf(Additions(numerators)) + denominatorFactors))
        return merge(StepRule.COMBINE_FRACTIONS, group, Additions(group.map { it.expression }), combined, Value.ZERO)
    }

    /**
     * x^(1/2) = √x, x⁻¹ = 1/x: a power written as a power for the power rule is written as
     * usual again, once nothing else is left to do.
     */
    private fun asRootOrFraction(e: LiteralPower, path: Path): Match {
        val power = unmarked(e.inner)
        val exponent = ((power as? Power)?.exponent as? Value)?.rational
        val rule = if (exponent != null && exponent.isFractional) StepRule.POWER_AS_ROOT else StepRule.POWER_AS_FRACTION
        return Match(rule, mapOf(path to power), LiteralPower(power), power)
    }

    /** 2 + 3 = 5 */
    private fun addNumbers(terms: List<Part>, context: MathContext): Match? {
        val numbers = terms.filter { isNumber(it.expression) }
        if (numbers.size < 2) return null
        val sum = Additions(numbers.map { it.expression })
        return merge(StepRule.ADD_NUMBERS, numbers, sum, canonical(sum, context), Value.ZERO)
    }
}
