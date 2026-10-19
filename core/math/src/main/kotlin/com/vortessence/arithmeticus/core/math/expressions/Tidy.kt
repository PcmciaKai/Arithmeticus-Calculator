package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.steps.Alternatives

/**
 * Rewrites a canonical symbolic result into a more compact form for display, e.g. the
 * derivative 2x·e^sin(x) + x²·e^sin(x)·cos(x) + 1/(3x−1) − 3x/(3x−1)² as
 * e^sin(x)·(2x + x²·cos(x)) − 1/(3x−1)²:
 * - fractions with the same sum as denominator are combined over its highest power,
 * - factors shared by several terms (other than numbers and powers of variables) are factored out.
 * Each step is only taken if it makes the expression shorter.
 *
 * The result is not canonical (canonicalizing would multiply it out again); only write it.
 */
internal fun tidyForDisplay(e: Expression, context: MathContext): Expression {
    // An equation or a list of solutions is tidied side by side, e.g. the result of dsolve
    if (e is Equals) return Equals(tidyForDisplay(e.left, context), tidyForDisplay(e.right, context))
    if (e is Alternatives) return Alternatives(e.equations.map { tidyForDisplay(it, context) }, e.inline, e.separator, e.group)
    val cleared = clearInnerFractions(e, context)
    if (cleared !is Additions) return cleared
    return tidySum(cleared, context)
}

private fun tidySum(e: Additions, context: MathContext): Expression {
    val combined = combineFractions(e.operands, context)
    val factored = factorCommonFactors(combined)
    val tidy = if (factored.size == 1) factored.single() else Additions(factored)
    return if (expressionSize(tidy) < expressionSize(e)) tidy else e
}

/**
 * A fraction with a fraction in its denominator, 1/(1/b − 2kt), as one fraction: b/(1 − 2kbt). The sum in the denominator is put
 * over its common denominator and the fraction turned over, anywhere in the expression. Not if the numerator would grow to more than twice the size.
 */
internal fun clearInnerFractions(e: Expression, context: MathContext): Expression {
    val children = e.children
    val cleared = children.map { clearInnerFractions(it, context) }
    var result = if (cleared.indices.all { cleared[it] === children[it] }) e else e.copyWithChildren(cleared)
    // Products inside products are one product (the part that was turned over)
    if (result is Multiplications && result.operands.any { it is Multiplications }) {
        result = Multiplications(result.operands.flatMap { if (it is Multiplications) it.operands else listOf(it) })
    }
    return (result as? Power)?.let { turnOver(it, context) } ?: result
}

/** (N/D)^−k as D^k·N^−k for a sum N/D that has fractions in it (also 1/3 and x/3); null if [power] is not that. */
private fun turnOver(power: Power, context: MathContext): Expression? {
    val sum = power.base as? Additions ?: return null
    val k = (power.exponent as? Value)?.rational?.takeIf { !it.isFractional && it.isNegative && it.numerator.value.bitLength() < 8 } ?: return null
    val times = (-k.numerator.value.toInt()).toLong()
    // Each term as numerator and the denominators it has (a variable or a sum to a power)
    class Part(val numerator: List<Expression>, val denominators: List<Pair<Expression, Int>>)
    fun split(term: Expression): Part {
        val numerator = mutableListOf<Expression>()
        val denominators = mutableListOf<Pair<Expression, Int>>()
        for (factor in if (term is Multiplications) term.operands else listOf(term)) {
            val n = ((factor as? Power)?.exponent as? Value)?.rational
            val number = (factor as? Value)?.rational
            if (factor is Power && n != null && !n.isFractional && n.isNegative && factor.base.variables().isNotEmpty()) {
                denominators.add(factor.base to -n.numerator.value.toInt())
            } else if (number != null && number.isFractional) {
                // 1/3 − 2kt: the 3 is a denominator as well
                numerator.add(Value(number.numerator))
                denominators.add(Value(number.denominator) to 1)
            } else {
                numerator.add(factor)
            }
        }
        return Part(numerator, denominators)
    }
    val parts = sum.operands.map { split(it) }
    if (parts.none { it.denominators.isNotEmpty() }) return null
    // The common denominator: every one once, with its highest power
    val common = mutableListOf<Pair<Expression, Int>>()
    for (part in parts) for ((base, exponent) in part.denominators) {
        val i = common.indexOfFirst { it.first == base }
        if (i < 0) common.add(base to exponent) else if (exponent > common[i].second) common[i] = base to exponent
    }
    fun powered(base: Expression, exponent: Int): Expression = if (exponent == 1) base else Power(base, Value(exponent.toLong()))
    val numerators = parts.map { part ->
        val missing = common.mapNotNull { (base, exponent) ->
            val have = part.denominators.firstOrNull { it.first == base }?.second ?: 0
            (exponent - have).takeIf { it > 0 }?.let { powered(base, it) }
        }
        Multiplications.of(*(part.numerator + missing).toTypedArray())
    }
    val scratch = context.copy()
    val numerator = expandProducts(Additions(numerators)).canonicalize(scratch)
    if (scratch.shouldStop || scratch.issues.isNotEmpty() || numerator.isZero) return null
    val denominator = Multiplications.of(*common.map { (base, exponent) -> powered(base, exponent) }.toTypedArray())
    val turned = Multiplications.of(
        if (times == 1L) denominator else Power(denominator, Value(times)),
        Power(numerator, Value(-times))
    )
    return if (expressionSize(numerator) <= 2 * expressionSize(sum)) turned else null
}

/** A term split into the power of a sum in its denominator, (sum)^−[power], and the [rest]. */
private class FractionTerm(val denominator: Additions, val power: Int, val rest: Expression)

private fun asFractionTerm(term: Expression): FractionTerm? {
    val factors = if (term is Multiplications) term.operands else listOf(term)
    for ((index, factor) in factors.withIndex()) {
        if (factor !is Power || factor.base !is Additions) continue
        val exponent = (factor.exponent as? Value)?.rational ?: continue
        if (exponent.isFractional || !exponent.isNegative || exponent.numerator.value.bitLength() > 8) continue
        val rest = Multiplications.of(*factors.filterIndexed { i, _ -> i != index }.toTypedArray())
        return FractionTerm(factor.base, -exponent.numerator.value.toInt(), rest)
    }
    return null
}

/** a/(s) + b/(s)² → (a·s + b)/(s)², with the numerator multiplied out and simplified. */
private fun combineFractions(terms: List<Expression>, context: MathContext): List<Expression> {
    val fractions = terms.associateWith { asFractionTerm(it) }
    val result = terms.toMutableList()
    for ((denominator, group) in terms.filter { fractions[it] != null }.groupBy { fractions.getValue(it)!!.denominator }) {
        if (group.size < 2) continue
        val parts = group.map { fractions.getValue(it)!! }
        val maxPower = parts.maxOf { it.power }
        val scratch = context.copy()
        val numerator = expandProducts(Additions(parts.map { part ->
            Multiplications(listOf(part.rest, Power(denominator, Value((maxPower - part.power).toLong()))))
        })).canonicalize(scratch)
        if (scratch.shouldStop) continue
        val combined = if (numerator.isZero) Value.ZERO else Multiplications(listOf(numerator, Power(denominator, Value(-maxPower.toLong()))))
        if (expressionSize(combined) >= group.sumOf { expressionSize(it) }) continue
        result[result.indexOf(group.first())] = combined
        result.removeAll(group.drop(1).toSet())
    }
    return result.filterNot { it.isZero }.ifEmpty { listOf(Value.ZERO) }
}

/** a·f + b·f → f·(a + b) for factors f in several terms, the most common factor first. */
private fun factorCommonFactors(terms: List<Expression>): List<Expression> {
    val result = terms.toMutableList()
    while (true) {
        fun factorsOf(term: Expression) = if (term is Multiplications) term.operands else listOf(term)
        val candidates = result.flatMap { term -> factorsOf(term).filter { isFactorable(it) }.distinct() }
        val common = candidates.groupingBy { it }.eachCount().filter { it.value >= 2 }
            .maxByOrNull { (factor, count) -> count * expressionSize(factor) }?.key ?: return result
        val group = result.filter { common in factorsOf(it) }
        val inner = group.map { term ->
            val factors = factorsOf(term).toMutableList().apply { remove(common) }
            Multiplications.of(*factors.toTypedArray())
        }
        // Positive terms first: (2 − x²) rather than (−x² + 2)
        val factored = Multiplications(listOf(common, Additions(inner.sortedBy { it.isNegative })))
        if (expressionSize(factored) >= group.sumOf { expressionSize(it) }) return result
        result[result.indexOf(group.first())] = factored
        result.removeAll(group.drop(1).toSet())
    }
}

/** Numbers and powers of variables stay with their terms: e^x·(2x + x²) rather than x·e^x·(2 + x). */
private fun isFactorable(factor: Expression): Boolean {
    if (factor is Value || factor is Variable || factor is UnitExpression) return false
    if (factor is Power && factor.base is Variable && factor.exponent is Value) return false
    return true
}
