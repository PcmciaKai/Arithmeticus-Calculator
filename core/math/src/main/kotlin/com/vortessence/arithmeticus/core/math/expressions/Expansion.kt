package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext

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

/** Powers that expand(…) multiplies out when asked to, and how many terms its result may have at most. */
private const val MAX_EXPLICIT_POWER = 200
private const val MAX_EXPLICIT_TERMS = 2000

/**
 * For expand(…), which asks for it: like [expandProducts], also for powers above [MAX_EXPANDED_POWER] (expand((x + 1)¹⁰) was left
 * as it is), simplified after every factor so that (x + 1)²⁰ is 21 terms on the way, not 2²⁰. Null if the result would have
 * more than [MAX_EXPLICIT_TERMS] terms (or [context] stops).
 */
internal fun expandExplicitly(e: Expression, context: MathContext): Expression? {
    fun simplified(x: Expression): Expression? {
        val result = x.canonicalize(context)
        if (context.shouldStop) return null
        return result.takeIf { (it as? Additions)?.operands?.size ?: 1 <= MAX_EXPLICIT_TERMS }
    }
    return when (e) {
        is Additions -> simplified(Additions(e.operands.map { expandExplicitly(it, context) ?: return null }))
        is Multiplications -> e.operands.map { expandExplicitly(it, context) ?: return null }
            .reduce { a, b -> simplified(multiplyOut(a, b)) ?: return null }
        is Power -> {
            val n = (e.exponent as? Value)?.rational?.takeIf { e.base is Additions && !it.isFractional && !it.isNegative && it.numerator.value.bitLength() < 31 }
                ?.numerator?.value?.toInt()?.takeIf { it in 2..MAX_EXPLICIT_POWER }
            if (n == null) e else {
                val base = expandExplicitly(e.base, context) ?: return null
                // A sum of k terms to the n-th has at most C(n + k − 1, k − 1) terms: known too long before working it out
                val k = (base as? Additions)?.operands?.size ?: 1
                var bound = 1.0
                for (i in 1 until k) bound = bound * (n + i) / i
                if (bound > MAX_EXPLICIT_TERMS) return null
                (2..n).fold(base) { acc, _ -> simplified(multiplyOut(acc, base)) ?: return null }
            }
        }
        else -> e
    }
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
