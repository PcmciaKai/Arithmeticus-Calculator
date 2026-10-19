package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.AbsoluteValue
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.ImaginaryUnit
import com.vortessence.arithmeticus.core.math.expressions.Logarithm
import com.vortessence.arithmeticus.core.math.expressions.LogarithmRules
import com.vortessence.arithmeticus.core.math.expressions.MixedNumber
import com.vortessence.arithmeticus.core.math.expressions.Percent
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.HyperbolicFunction
import com.vortessence.arithmeticus.core.math.expressions.HyperbolicType
import com.vortessence.arithmeticus.core.math.expressions.TrigType
import com.vortessence.arithmeticus.core.math.expressions.reciprocalPartner
import com.vortessence.arithmeticus.core.math.expressions.TrigonometricFunction
import com.vortessence.arithmeticus.core.math.expressions.PartialFractions
import com.vortessence.arithmeticus.core.math.expressions.pythagoreanSum
import com.vortessence.arithmeticus.core.math.expressions.SymbolicCancel
import com.vortessence.arithmeticus.core.math.expressions.Power
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.numbers.BigInt
import com.vortessence.arithmeticus.core.math.numbers.Rational

/**
 * Simplifications as steps of a solution, one at a time, as they would be written by hand:
 * powers of numbers (3² = 9), multiplying numbers (2·2x = 4x), powers of powers and of products
 * ((x²)³ = x⁶, (2x)³ = 2³x³), combining powers (x·x² = x³), cancelling (x·1/x = 1), combining
 * like terms (2x + 3x = 5x), taking away a sum (2x + 3 − (2x + 3) = 0), adding numbers, fractions with the same denominator
 * ((x + 1)/x − 1/x = (x + 1 − 1)/x) and multiplying out (−(x + 1) = −x − 1,
 * (x + 1)(x − 1) = x² − x + x − 1). Multiplying out comes last, so that fractions are combined
 * rather than split.
 *
 * Multiplying out only happens where the result, [match]'s target, does not keep the sum: the
 * calculator leaves (x + 1)² as it is, but (x + 1)² − x² is 2x + 1.
 * Highlighted parts count as what they contain, e.g. the 2 in 2·[2x].
 *
 * With [match]'s `arithmetic` flag (plain arithmetic, see [ArithmeticSteps]) fractions are worked out
 * as by hand (common denominator, numerators times numerators, reducing) and roots simplified (√12 = 2√3).
 */
internal object SimplificationSteps {

    /** Powers of sums are multiplied out up to this exponent. */
    private const val MAX_EXPANDED_POWER = 4

    /** The first simplification of [e] toward [target] that applies, in the order of the rules above. */
    fun match(e: Expression, target: Expression, context: MathContext, arithmetic: Boolean = false, tangents: Boolean = true): Match? {
        val keeps = { sum: Expression -> target.containsExpressionMatching { it == sum } }
        val powers = pathsOf(e) { it is Power }
        val products = pathsOf(e) { it is Multiplications }.map { factors(at(e, it), it) }
        val sums = pathsOf(e) { it is Additions }.map { terms(at(e, it), it) }
        if (arithmetic) arithmeticMatch(powers.map { at(e, it) to it }, products, sums, e, context)?.let { return it }
        return products.firstNotNullOfOrNull { divideByNegativeNumber(it, context) }
            ?: powers.firstNotNullOfOrNull { imaginaryRoot(at(e, it), it, context) }
            ?: powers.firstNotNullOfOrNull { numberPower(at(e, it), it, context) }
            ?: products.firstNotNullOfOrNull { multiplyNumbers(it, context) }
            ?: powers.firstNotNullOfOrNull { powerOfPower(at(e, it), it, context) }
            ?: powers.firstNotNullOfOrNull { powerOfProduct(at(e, it), it) }
            ?: pathsOf(e) { it is TrigonometricFunction || it is HyperbolicFunction }.firstNotNullOfOrNull { exactTrigonometricValue(at(e, it), it, context) }
            ?: pathsOf(e) { it is Logarithm }.firstNotNullOfOrNull { logarithmOfPower(at(e, it), it, context) }
            ?: powers.firstNotNullOfOrNull { exponentialOfLogarithm(at(e, it), it) }
            ?: products.firstNotNullOfOrNull { if (tangents) tangentFromQuotient(it) else null }
            ?: products.firstNotNullOfOrNull { reciprocalFunctions(it, context) }
            ?: products.firstNotNullOfOrNull { combinePowers(it, context) }
            ?: products.firstNotNullOfOrNull { cancelCommonFactor(it, context) }
            ?: sums.firstNotNullOfOrNull { subtractSameSum(it, context) }
            ?: sums.firstNotNullOfOrNull { pythagoreanIdentity(it, context) }
            ?: sums.firstNotNullOfOrNull { combineLikeTerms(it, context) }
            ?: sums.firstNotNullOfOrNull { logarithmSum(it, context) }
            ?: products.firstNotNullOfOrNull { logarithmQuotient(it) }
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

    private fun canonical(e: Expression, context: MathContext) = inDegreeForm(e.canonicalize(context.copy()), context)

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
        // √9 = 3, when the root is a whole or fractional number (√2 stays)
        if (isNumber(power.base) && !power.base.isNegative && (power.exponent as? Value)?.rational?.isFractional == true) {
            val root = canonical(power, context).takeIf { isNumber(it) } ?: return null
            return Match(StepRule.ROOT_OF_NUMBER, mapOf(path to root), power, root)
        }
        if (!isNumber(power.base) || !isInteger(power.exponent)) return null
        val value = canonical(power, context).takeIf { isNumber(it) } ?: return null
        val isDivision = power.exponent == Value.MINUS_ONE && (power.base as Value).rational?.isFractional == true
        return Match(if (isDivision) StepRule.DIVIDE_BY_FRACTION else StepRule.NUMBER_POWER, mapOf(path to value), power, value)
    }

    /**
     * −3y ÷ (−3) = y: a number divided by a negative number is divided as it is written, rather
     * than first turning the division into the factor −⅓.
     */
    private fun divideByNegativeNumber(factors: List<Part>, context: MathContext): Match? {
        val divisor = factors.firstOrNull { part ->
            val power = part.expression as? Power
            power != null && power.exponent == Value.MINUS_ONE && isInteger(power.base) && (power.base as Value).isNegative
        } ?: return null
        val numbers = factors.filter { isNumber(it.expression) }
        if (numbers.isEmpty()) return null
        val parts = numbers + divisor
        val product = Multiplications(parts.map { it.expression })
        return merge(StepRule.DIVIDE_NUMBERS, parts, product, canonical(product, context), Value.ONE)
    }

    /** √(−4) = i·√4, with complex numbers. */
    private fun imaginaryRoot(e: Expression, path: Path, context: MathContext): Match? {
        if (!context.complexNumbers) return null
        val power = unmarked(e) as? Power ?: return null
        val base = power.base as? Value ?: return null
        if (base.rational == null || !base.isNegative || power.exponent != Value(1L, 2L)) return null
        val result = Multiplications(ImaginaryUnit, Power(base.negate(), power.exponent))
        return Match(StepRule.IMAGINARY_ROOT, mapOf(path to result), power, result)
    }

    /**
     * (x² − 1)/(x − 1) = (x − 1)(x + 1)/(x − 1) = x + 1: a sum in the numerator and a sum in the
     * denominator that are polynomials with a common factor. The factorisations are shown.
     */
    private fun cancelCommonFactor(factors: List<Part>, context: MathContext): Match? {
        for (numerator in factors) {
            val n = numerator.expression as? Additions ?: continue
            for (denominator in factors) {
                val power = denominator.expression as? Power ?: continue
                // (x² + x)/x is cancelled too: a single x is a denominator of one term
                val d = power.base.takeIf { it is Additions || it is Variable } ?: continue
                // (x + 1 − 1)/x: the numerator is simplified before anything is cancelled
                if (d is Variable && canonical(n, context) != n) continue
                val k = (power.exponent as? Value)?.rational?.takeIf { !it.isFractional && it.isNegative && it.numerator.value.bitLength() < 4 } ?: continue
                val variables = n.variables()
                if (variables.size != 1 || variables != d.variables()) {
                    if (d is Additions) symbolicCancel(numerator, denominator, n, d, k.numerator.value.toInt().let { -it }, context)?.let { return it }
                    continue
                }
                val v = variables.single()
                val p = PartialFractions.polynomial(n, v, context) ?: continue
                val q = PartialFractions.polynomial(d, v, context) ?: continue
                val g = PartialFractions.gcd(p, q)
                if (g.size < 2) continue
                val common = PartialFractions.toExpression(g, v)
                val newNumerator = PartialFractions.toExpression(PartialFractions.divide(p, g).first, v)
                val newDenominator = PartialFractions.toExpression(PartialFractions.divide(q, g).first, v)
                fun product(a: Expression, b: Expression) = if (a is Value) Multiplications(a, b) else Multiplications(b, a)
                val exponent = -k.numerator.value.toInt()
                // One of the factors of the denominator is cancelled, the others stay
                val denominatorResult: Expression = Multiplications.of(
                    *listOfNotNull(
                        Power(newDenominator, Value.MINUS_ONE).takeIf { newDenominator != Value.ONE },
                        if (exponent > 1) Power(d, Value(1L - exponent)) else null
                    ).toTypedArray()
                )
                // x − 1 = 1·(x − 1) says nothing
                val notes = listOfNotNull(
                    Note(n, product(newNumerator, common)),
                    Note(d, product(newDenominator, common)).takeIf { newDenominator != Value.ONE }
                )
                return Match(
                    StepRule.CANCEL_COMMON_FACTOR,
                    mapOf(numerator.path to newNumerator, denominator.path to denominatorResult),
                    Multiplications(n, power),
                    Multiplications(newNumerator, denominatorResult),
                    notes = notes
                )
            }
        }
        return null
    }

    /** (x² − y²)/(x − y) = (x − y)(x + y)/(x − y) = x + y: letters in the sums, a linear factor in common. */
    private fun symbolicCancel(numerator: Part, denominator: Part, n: Additions, d: Additions, exponent: Int, context: MathContext): Match? {
        val cancelled = (n.variables() intersect d.variables())
            .firstNotNullOfOrNull { SymbolicCancel.cancelLinear(n, d, it, context) } ?: return null
        val one = Value.ONE
        val denominatorResult: Expression = Multiplications.of(
            *listOfNotNull(
                Power(cancelled.leading, Value.MINUS_ONE).takeIf { cancelled.leading != one },
                if (exponent > 1) Power(d, Value(1L - exponent)) else null
            ).toTypedArray()
        )
        val notes = listOfNotNull(
            Note(n, Multiplications(cancelled.quotient, cancelled.factor)),
            Note(d, Multiplications(cancelled.leading, cancelled.factor)).takeIf { cancelled.leading != one }
        )
        return Match(
            StepRule.CANCEL_COMMON_FACTOR,
            mapOf(numerator.path to cancelled.quotient, denominator.path to denominatorResult),
            Multiplications(n, Power(d, Value(-exponent.toLong()))),
            Multiplications(cancelled.quotient, denominatorResult),
            notes = notes
        )
    }

    // ----- plain arithmetic: fractions and roots -----

    /** A number or a fraction of whole numbers as (numerator, denominator), or null. */
    private fun fractionParts(e: Expression): Pair<BigInt, BigInt>? = when {
        e is Value && e.rational != null -> e.rational.numerator to e.rational.denominator
        e is LiteralFraction -> {
            val n = (e.numerator as? Value)?.rational?.takeIf { !it.isFractional }
            val d = (e.denominator as? Value)?.rational?.takeIf { !it.isFractional }
            if (n != null && d != null && !d.isZero) n.numerator to d.numerator else null
        }
        else -> null
    }

    private fun isFraction(e: Expression) = e is LiteralFraction || (e is Value && e.rational?.isFractional == true)

    private fun lcm(a: BigInt, b: BigInt): BigInt = a * b / a.gcd(b)

    private fun arithmeticMatch(
        powers: List<Pair<Expression, Path>>,
        products: List<List<Part>>,
        sums: List<List<Part>>,
        e: Expression,
        context: MathContext
    ): Match? {
        // 15% = 15/100 = 3/20
        pathsOf(e) { it is Percent && it.operand is Value }.firstOrNull()?.let { path ->
            val percent = at(e, path) as Percent
            val number = (percent.operand as Value).rational ?: return@let null
            val value = Value(number / Rational.of(100L))
            // 10% = 10/100 = 1/10 says more than 7% = 7/100 does
            val notes = if (number.isFractional || value.rational?.denominator == BigInt(100L)) emptyList() else listOf(Note(percent, LiteralFraction(percent.operand, Value(100L))))
            return Match(StepRule.PERCENT_AS_FRACTION, mapOf(path to value), percent, value, notes = notes)
        }
        // 2 1/3 = (2·3 + 1)/3
        pathsOf(e) { it is MixedNumber }.firstOrNull()?.let { path ->
            val mixed = at(e, path) as MixedNumber
            val improper = LiteralFraction(Value(Rational.of(mixed.whole * mixed.denominator + mixed.numerator)), Value(Rational.of(mixed.denominator)))
            val written = LiteralFraction(
                Additions(WrittenOut(listOf(Value(Rational.of(mixed.whole)), Value(Rational.of(mixed.denominator)))), Value(Rational.of(mixed.numerator))),
                Value(Rational.of(mixed.denominator))
            )
            return Match(StepRule.IMPROPER_FRACTION, mapOf(path to improper), mixed, improper, notes = listOf(Note(written, improper)))
        }
        // 1/(a/b) = b/a
        for ((power, path) in powers) {
            val p = unmarked(power) as? Power ?: continue
            val inner = p.base as? LiteralFraction ?: continue
            if (p.exponent != Value.MINUS_ONE) continue
            val flipped = LiteralFraction(inner.denominator, inner.numerator)
            return Match(StepRule.DIVIDE_BY_FRACTION, mapOf(path to flipped), p, flipped)
        }
        // √(4/9) = √4/√9 = 2/3
        powers.firstNotNullOfOrNull { (power, path) -> rootOfFraction(unmarked(power), path) }?.let { return it }
        // 1/√2 = √2/2: no root in a denominator
        powers.firstNotNullOfOrNull { (power, path) -> rationalize(unmarked(power), path) }?.let { return it }
        // √12 = 2√3
        powers.firstNotNullOfOrNull { (power, path) -> simplifyRoot(unmarked(power), path) }?.let { return it }
        products.firstNotNullOfOrNull { multiplyFractions(it) }?.let { return it }
        sums.firstNotNullOfOrNull { extendFractions(it) }?.let { return it }
        sums.firstNotNullOfOrNull { combineLiteralFractions(it) }?.let { return it }
        return pathsOf(e) { it is LiteralFraction }.firstNotNullOfOrNull { finishFraction(at(e, it), it) }
    }

    /** √(4/9) = √4/√9: a fraction of two squares, each root then is a number. */
    private fun rootOfFraction(e: Expression, path: Path): Match? {
        val power = e as? Power ?: return null
        val fraction = (power.base as? Value)?.rational?.takeIf { it.isFractional && !it.isNegative } ?: return null
        if ((power.exponent as? Value)?.rational != Rational.HALF) return null
        fun isSquare(n: BigInt): Boolean = n.value.bitLength() < 60 && n.value.toLong().let { v -> Math.sqrt(v.toDouble()).let { r -> Math.rint(r).toLong().let { it * it == v } } }
        if (!isSquare(fraction.numerator) || !isSquare(fraction.denominator)) return null
        val result = LiteralFraction(Power(Value(fraction.numerator), Value.HALF), Power(Value(fraction.denominator), Value.HALF))
        return Match(StepRule.ROOT_OF_FRACTION, mapOf(path to result), power, result)
    }

    /** 1/√2 = √2/(√2·√2) = √2/2, also 1/∛2: a root of a whole number in a denominator. */
    private fun rationalize(e: Expression, path: Path): Match? {
        val power = e as? Power ?: return null
        // 1/√2 typed as (√2)⁻¹, or already as 2^(−1/2)
        val n: Rational = when {
            power.exponent == Value.MINUS_ONE && power.base is Power &&
                (power.base.exponent as? Value)?.rational == Rational.HALF -> (power.base.base as? Value)?.rational
            (power.exponent as? Value)?.rational == Rational.of(-1L, 2L) -> (power.base as? Value)?.rational
            else -> null
        }?.takeIf { !it.isFractional && !it.isNegative && it != Rational.ONE } ?: return null
        // A perfect square (1/√4) is a number: the roots rule has it
        if (Math.sqrt(n.toDouble()).let { Math.rint(it) * Math.rint(it) == n.toDouble() }) return null
        val root = Power(Value(n), Value.HALF)
        // √2 times √2 stays written out, like the products of the fraction rules
        val written = LiteralFraction(Multiplications(Value.ONE, root), WrittenOut(listOf(root, root)))
        val result = LiteralFraction(root, Value(n))
        return Match(StepRule.RATIONALIZE, mapOf(path to result), power, result, notes = listOf(Note(LiteralFraction(Value.ONE, root), written)))
    }

    /** √12 = √(4·3) = 2√3, ∛54 = 3∛2: the largest power that divides the radicand comes out. */
    private fun simplifyRoot(e: Expression, path: Path): Match? {
        val power = e as? Power ?: return null
        val n = (power.base as? Value)?.rational?.takeIf { !it.isFractional && !it.isNegative } ?: return null
        val exponent = (power.exponent as? Value)?.rational ?: return null
        if (exponent.numerator != BigInt.ONE) return null
        val degree = exponent.denominator.value.toInt().takeIf { it in 2..3 && exponent.denominator.value.bitLength() < 4 } ?: return null
        if (n.numerator.value.bitLength() > 40) return null
        val value = n.numerator.value.toLong()
        var k = 1L
        var candidate = 2L
        while (Math.pow(candidate.toDouble(), degree.toDouble()) <= value) {
            var p = 1L
            repeat(degree) { p *= candidate }
            if (value % p == 0L) k = candidate
            candidate++
        }
        if (k == 1L) return null
        var power2 = 1L
        repeat(degree) { power2 *= k }
        val rest = value / power2
        // A perfect power is calculated by the root rule
        if (rest == 1L) return null
        val result = Multiplications(Value(k), Power(Value(rest), power.exponent))
        return Match(
            StepRule.SIMPLIFY_ROOT, mapOf(path to result), power, result,
            notes = listOf(Note(Value(value), WrittenOut(listOf(Value(power2), Value(rest)))))
        )
    }

    /** a/b·c/d = (a·c)/(b·d), also with whole numbers. */
    private fun multiplyFractions(factors: List<Part>): Match? {
        if (factors.size < 2 || factors.any { fractionParts(it.expression) == null }) return null
        if (factors.none { isFraction(it.expression) }) return null
        val parts = factors.map { fractionParts(it.expression)!! }
        val numerators = parts.map { Value(Rational.of(it.first)) }
        val denominators = parts.filter { it.second != BigInt.ONE }.map { Value(Rational.of(it.second)) }
        val result = LiteralFraction(Multiplications.of(*numerators.toTypedArray()), Multiplications.of(*denominators.toTypedArray()))
        return merge(StepRule.MULTIPLY_FRACTIONS, factors, Multiplications(factors.map { it.expression }), result, Value.ONE)
    }

    /** 1/2 + 1/3 = 3/6 + 2/6: the fractions get the same denominator, the least common multiple. */
    private fun extendFractions(terms: List<Part>): Match? {
        val numbers = terms.filter { fractionParts(it.expression) != null }
        if (numbers.size < 2 || numbers.none { isFraction(it.expression) }) return null
        val parts = numbers.map { fractionParts(it.expression)!! }
        val common = parts.map { it.second }.reduce { a, b -> lcm(a, b) }
        // Only those that are not written with it yet
        val changed = numbers.zip(parts).filter { (term, part) -> !(term.expression is LiteralFraction && part.second == common) }
        if (changed.isEmpty()) return null
        val replacements = changed.associate { (term, part) ->
            val factor = common / part.second
            term.path to LiteralFraction(Value(Rational.of(part.first * factor)), Value(Rational.of(common)))
        }
        val notes = buildList {
            if (parts.map { it.second }.distinct().size > 1) {
                add(Note(TextExpression("lcm(${parts.map { it.second }.distinct().joinToString(", ")})"), Value(Rational.of(common))))
            }
            for ((term, part) in changed) {
                val factor = common / part.second
                if (factor == BigInt.ONE) continue
                add(Note(
                    LiteralFraction(WrittenOut(listOf(Value(Rational.of(part.first)), Value(Rational.of(factor)))), WrittenOut(listOf(Value(Rational.of(part.second)), Value(Rational.of(factor))))),
                    replacements.getValue(term.path)
                ))
            }
        }
        return Match(
            StepRule.EXTEND_FRACTIONS, replacements,
            Additions(numbers.map { it.expression }), Additions(numbers.map { replacements[it.path] ?: it.expression }),
            notes = notes
        )
    }

    /** 3/6 + 2/6 = (3 + 2)/6 */
    private fun combineLiteralFractions(terms: List<Part>): Match? {
        val fractions = terms.filter { it.expression is LiteralFraction && it.expression.denominator is Value }
        val group = fractions.groupBy { (it.expression as LiteralFraction).denominator }.values.firstOrNull { it.size >= 2 } ?: return null
        val denominator = (group.first().expression as LiteralFraction).denominator
        val combined = LiteralFraction(Additions(group.map { (it.expression as LiteralFraction).numerator }), denominator)
        return merge(StepRule.COMBINE_FRACTIONS, group, Additions(group.map { it.expression }), combined, Value.ZERO)
    }

    /** 6/4 = 3/2 (reduced), 6/3 = 2 (calculated); a fraction that is reduced already becomes a plain number. */
    private fun finishFraction(e: Expression, path: Path): Match? {
        val fraction = unmarked(e) as? LiteralFraction ?: return null
        val (n, d) = fractionParts(fraction) ?: return null
        if (d.isZero) return null
        val value = Rational.of(n, d)
        val g = n.abs().gcd(d.abs())
        return when {
            !value.isFractional -> Match(StepRule.DIVIDE_NUMBERS, mapOf(path to Value(value)), fraction, Value(value))
            g != BigInt.ONE -> {
                val reduced = LiteralFraction(Value(Rational.of(n / g)), Value(Rational.of(d / g)))
                Match(StepRule.REDUCE_FRACTION, mapOf(path to reduced), fraction, reduced)
            }
            // The same as it is written: the number 5/6 is that fraction
            else -> Match(StepRule.REDUCE_FRACTION, mapOf(path to Value(value)), fraction, Value(value))
        }
    }

    /** sin(x)/cos(x) = tan(x) */
    private fun tangentFromQuotient(factors: List<Part>): Match? {
        for (numerator in factors) {
            val sine = numerator.expression as? TrigonometricFunction ?: continue
            if (sine.type != TrigType.SIN) continue
            for (denominator in factors) {
                val power = denominator.expression as? Power ?: continue
                if (power.exponent != Value.MINUS_ONE) continue
                val cosine = power.base as? TrigonometricFunction ?: continue
                if (cosine.type != TrigType.COS || cosine.operand != sine.operand) continue
                val tangent = TrigonometricFunction(TrigType.TAN, sine.operand, sine.angleUnit)
                return merge(StepRule.TANGENT_AS_QUOTIENT, listOf(numerator, denominator), Multiplications(sine, power), tangent, Value.ONE)
            }
        }
        return null
    }

    /** 2·3 = 6. A factor −1 is the sign and stays in front: −2·3x = −6x. */
    private fun multiplyNumbers(factors: List<Part>, context: MathContext): Match? {
        // The sign is multiplied in only where there is nothing but numbers: −1·4 = −4
        val onlyNumbers = factors.all { isNumber(it.expression) }
        val numbers = factors.filter { isNumber(it.expression) && (onlyNumbers || it.expression != Value.MINUS_ONE) }
        if (numbers.size < 2) return null
        val product = Multiplications(numbers.map { it.expression })
        val result = canonical(product, context)
        // 3·1 = 3 is shown with its 1
        val written: Expression = if (numbers.any { it.expression == Value.ONE }) WrittenOut(numbers.map { it.expression }) else product
        return merge(productRule(numbers.mapNotNull { (it.expression as Value).rational }, result), numbers, written, result, Value.ONE)
    }

    /**
     * What multiplying the numbers [factors] into [result] is when written down: with fractions
     * among them dividing (4/2 = 2) or reducing a fraction (4/6 = 2/3), otherwise multiplying.
     */
    private fun productRule(factors: List<Rational>, result: Expression): StepRule {
        if (factors.none { it.isFractional }) return StepRule.MULTIPLY_NUMBERS
        if ((result as? Value)?.rational?.isFractional == false) return StepRule.DIVIDE_NUMBERS
        val numerator = factors.fold(BigInt.ONE) { p, f -> p * f.numerator }
        val denominator = factors.fold(BigInt.ONE) { p, f -> p * f.denominator }
        return if (factors.size == 2 && numerator.gcd(denominator) != BigInt.ONE) StepRule.REDUCE_FRACTION else StepRule.MULTIPLY_NUMBERS
    }

    /**
     * 2x + 3 − (2x + 3) = 0: a sum taken away from a side that contains all of its terms, in
     * one step rather than by multiplying out first.
     */
    private fun subtractSameSum(terms: List<Part>, context: MathContext): Match? {
        for (subtracted in terms) {
            val product = subtracted.expression as? Multiplications ?: continue
            if (product.operands.size != 2 || product.operands[0] != Value.MINUS_ONE) continue
            val sum = product.operands[1] as? Additions ?: continue
            val others = terms.filter { it !== subtracted }.toMutableList()
            val same = sum.operands.map { term ->
                val part = others.firstOrNull { canonical(it.expression, context) == canonical(term, context) } ?: return@map null
                others.remove(part)
                part
            }
            if (same.any { it == null }) continue
            val parts = same.filterNotNull() + subtracted
            return merge(StepRule.SUBTRACT_SAME, parts, Additions(parts.map { it.expression }), Value.ZERO, Value.ZERO)
        }
        return null
    }

    /** (x²)³ = x⁶, √(x²) = |x| */
    private fun powerOfPower(e: Expression, path: Path, context: MathContext): Match? {
        val power = unmarked(e) as? Power ?: return null
        if (power.base !is Power) return null
        val result = canonical(power, context)
        // √(x²) = |x|: the sign of x is lost, so it is not the same rule
        return Match(if (result is AbsoluteValue) StepRule.ROOT_OF_SQUARE else StepRule.POWER_OF_POWER, mapOf(path to result), power, result)
    }

    /** sin(π/6) = 1/2, cos(π) = −1, tan(π/4) = 1, sinh(0) = 0, sinh(ln 2) = 3/4: the exact values of the special angles and numbers. */
    private fun exactTrigonometricValue(e: Expression, path: Path, context: MathContext): Match? {
        val function = unmarked(e)
        val operand = (function as? TrigonometricFunction)?.operand ?: (function as? HyperbolicFunction)?.operand ?: return null
        if (operand.variables().isNotEmpty()) return null
        // sin(π/2 + π/2): the angle is simplified first
        if (canonical(operand, context) != operand) return null
        val value = canonical(function, context)
        if (value == function || value.containsExpressionMatching { it is TrigonometricFunction || it is HyperbolicFunction }) return null
        // A decimal is no exact value
        if (value is Value && value.rational == null) return null
        val rule = if (function is HyperbolicFunction) StepRule.EXACT_HYPERBOLIC_VALUE else StepRule.EXACT_TRIGONOMETRIC_VALUE
        return Match(rule, mapOf(path to value), function, value)
    }

    /** sec(x)·cos(x) = 1, csc(x)·sin(x) = 1, cot(x)·tan(x) = 1 and the same for the hyperbolic functions (also with powers). */
    private fun reciprocalFunctions(factors: List<Part>, context: MathContext): Match? {
        fun function(e: Expression) = if (e is Power) e.base else e
        for (i in factors.indices) for (j in i + 1 until factors.size) {
            val partner = reciprocalPartner(function(factors[i].expression)) ?: continue
            if (partner != function(factors[j].expression)) continue
            val before = Multiplications(factors[i].expression, factors[j].expression)
            return merge(StepRule.RECIPROCAL_FUNCTIONS, listOf(factors[i], factors[j]), before, canonical(before, context), Value.ONE)
        }
        return null
    }

    /** log(100) = log(10²) = 2, ln(eˣ) = x. */
    private fun logarithmOfPower(e: Expression, path: Path, context: MathContext): Match? {
        val log = unmarked(e) as? Logarithm ?: return null
        if (canonical(log.operand, context) != log.operand) return null
        val value = canonical(log, context)
        if (value is Logarithm) return null
        val operand = log.operand
        // 100 = 10²
        val notes = if (operand is Value && value is Value && operand.rational != null && value.rational?.isFractional == false && operand != Value.ONE) {
            listOf(Note(operand, Power(log.base, value)))
        } else emptyList()
        return Match(StepRule.LOGARITHM_OF_POWER, mapOf(path to value), log, value, notes = notes)
    }

    /** e^(ln x) = x, 10^(log x) = x. */
    private fun exponentialOfLogarithm(e: Expression, path: Path): Match? {
        val power = unmarked(e) as? Power ?: return null
        val log = power.exponent as? Logarithm ?: return null
        if (log.base != power.base) return null
        return Match(StepRule.EXPONENTIAL_OF_LOGARITHM, mapOf(path to log.operand), power, log.operand)
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
        // (1 + x/(x + 1))·(x + 1): the sum is multiplied into the terms of the other, so that x + 1 cancels
        // against 1/(x + 1) rather than being multiplied out first
        if (multiplied.size == 2) {
            for ((sum, other) in listOf(multiplied[0] to multiplied[1], multiplied[1] to multiplied[0])) {
                val outer = other.expression as? Additions ?: continue
                fun cancelsWith(term: Expression) = (if (term is Multiplications) term.operands else listOf(term))
                    .any { it is Power && it.base == sum.expression && (it.exponent as? Value)?.isNegative == true }
                if (outer.operands.none { cancelsWith(it) }) continue
                val distributed = Additions(outer.operands.map { canonical(Multiplications(it, sum.expression), context) })
                return merge(StepRule.DISTRIBUTE, multiplied, Multiplications(multiplied.map { it.expression }), distributed, Value.ONE)
            }
        }
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

    /** sin²(x) + cos²(x) = 1, sec²(x) − tan²(x) = 1 and the like, also times the same factors: 3sin²x + 3cos²x = 3. */
    private fun pythagoreanIdentity(terms: List<Part>, context: MathContext): Match? {
        for (i in terms.indices) for (j in i + 1 until terms.size) {
            val combined = pythagoreanSum(terms[i].expression, terms[j].expression) ?: continue
            val result = canonical(combined, context)
            val before = Additions(terms[i].expression, terms[j].expression)
            fun mentions(test: (Expression) -> Boolean) = before.containsExpressionMatching(test)
            val rule = when {
                mentions { it is HyperbolicFunction } -> StepRule.HYPERBOLIC_IDENTITY
                mentions { it is TrigonometricFunction && it.type == TrigType.SEC } -> StepRule.SECANT_IDENTITY
                mentions { it is TrigonometricFunction && it.type == TrigType.CSC } -> StepRule.COSECANT_IDENTITY
                else -> StepRule.PYTHAGOREAN_IDENTITY
            }
            return merge(rule, listOf(terms[i], terms[j]), before, result, Value.ZERO)
        }
        return null
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

    /** log 25 + log 4 = log(25·4), ln 6 − ln 2 = ln(6/2), 3 ln 2 − ln 4 = ln(2³/4): the same base, numbers inside, written as one logarithm. */
    private fun logarithmSum(terms: List<Part>, context: MathContext): Match? {
        val parsed = terms.map { LogarithmRules.term(it.expression) }
        val base = parsed.filterNotNull().map { it.base }.firstOrNull { b -> parsed.count { it?.base == b } >= 2 } ?: return null
        val parts = terms.filterIndexed { i, _ -> parsed[i]?.base == base }
        val own = parsed.filter { it?.base == base }.map { it!! }
        // Only what the calculator combines as well, with whole coefficients
        if (own.any { it.coefficient.isFractional }) return null
        if (LogarithmRules.combine(parts.map { it.expression }, context) == null) return null
        val factors = own.map { t ->
            val exponent = t.coefficient
            if (exponent == Rational.ONE) Value(t.argument) as Expression else Power(Value(t.argument), Value(exponent))
        }
        val combined = Logarithm(base, if (factors.size == 1) factors.single() else Multiplications(factors))
        return merge(StepRule.LOG_SUM, parts, Additions(parts.map { it.expression }), combined, Value.ZERO)
    }

    /** ln 8/ln 2 = log₂(8): a logarithm over another of the same base whose argument is a power of the other's. */
    private fun logarithmQuotient(factors: List<Part>): Match? {
        for (top in factors) {
            val a = LogarithmRules.term(top.expression)?.takeIf { it.coefficient == Rational.ONE } ?: continue
            for (bottom in factors) {
                val power = bottom.expression as? Power ?: continue
                if (power.exponent != Value.MINUS_ONE) continue
                val b = LogarithmRules.term(power.base)?.takeIf { it.coefficient == Rational.ONE && it.base == a.base } ?: continue
                if (LogarithmRules.quotient(listOf(top.expression, power)) == null) continue
                val result = Logarithm(Value(b.argument), Value(a.argument))
                return Match(
                    StepRule.LOG_QUOTIENT, mapOf(top.path to result), Multiplications(top.expression, power), result,
                    removals = mapOf(bottom.path to Value.ONE)
                )
            }
        }
        return null
    }

    /** 2 + 3 = 5 */
    private fun addNumbers(terms: List<Part>, context: MathContext): Match? {
        val numbers = terms.filter { isNumber(it.expression) }
        if (numbers.size < 2) return null
        val sum = Additions(numbers.map { it.expression })
        // Written with their signs: 5 − 1 subtracts, −1 + 5 adds
        val subtracted = numbers.drop(1).count { it.expression.isNegative }
        val rule = when (subtracted) {
            0 -> StepRule.ADD_NUMBERS
            numbers.size - 1 -> StepRule.SUBTRACT_NUMBERS
            else -> StepRule.ADD_AND_SUBTRACT_NUMBERS
        }
        return merge(rule, numbers, sum, canonical(sum, context), Value.ZERO)
    }
}
