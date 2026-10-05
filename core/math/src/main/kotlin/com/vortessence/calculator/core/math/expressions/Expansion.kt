package com.vortessence.calculator.core.math.expressions

/** Powers of sums up to this exponent are multiplied out; (x+1)¹⁰ would get too long. */
private const val MAX_EXPANDED_POWER = 8

/**
 * Multiplies out products and powers of sums, e.g. x(x+1)² → x·x·x + 2·x·x + x (not simplified;
 * canonicalize the result). Everything else is left as it is.
 */
internal fun expandProducts(e: Expression): Expression = when (e) {
    is Additions -> Additions(e.operands.map { expandProducts(it) })
    is Multiplications -> e.operands.map { expandProducts(it) }.reduce { a, b -> multiplyOut(a, b) }
    is Power -> {
        val n = expandablePower(e)
        if (n != null) {
            val base = expandProducts(e.base)
            (2..n).fold(base) { acc, _ -> multiplyOut(acc, base) }
        } else {
            e
        }
    }
    else -> e
}

/** Whether [expandProducts] would change something in [e]. */
internal fun hasExpandableProducts(e: Expression): Boolean = e.containsExpressionMatching {
    (it is Power && expandablePower(it) != null) ||
        (it is Multiplications && it.operands.any { op -> op is Additions })
}

/** The exponent n of (sum)ⁿ if it is an integer that is expanded, otherwise null. */
private fun expandablePower(p: Power): Int? {
    if (p.base !is Additions) return null
    val n = (p.exponent as? Value)?.rational ?: return null
    if (n.isFractional || n.isNegative) return null
    return n.numerator.value.toInt().takeIf { it in 2..MAX_EXPANDED_POWER && n.numerator.value.bitLength() < 31 }
}

private fun multiplyOut(a: Expression, b: Expression): Expression {
    val aTerms = if (a is Additions) a.operands else listOf(a)
    val bTerms = if (b is Additions) b.operands else listOf(b)
    val products = aTerms.flatMap { x -> bTerms.map { y -> Multiplications(listOf(x, y)) } }
    return if (products.size == 1) products.single() else Additions(products)
}

/** The number of nodes in [e]'s tree, as a measure of how long it is written. */
internal fun expressionSize(e: Expression): Int = 1 + e.children.sumOf { expressionSize(it) }
