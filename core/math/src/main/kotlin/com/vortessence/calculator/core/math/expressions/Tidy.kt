package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext

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
    if (e !is Additions) return e
    val combined = combineFractions(e.operands, context)
    val factored = factorCommonFactors(combined)
    val tidy = if (factored.size == 1) factored.single() else Additions(factored)
    return if (expressionSize(tidy) < expressionSize(e)) tidy else e
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
