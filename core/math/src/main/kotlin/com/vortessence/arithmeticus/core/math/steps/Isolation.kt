package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.Constant
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Logarithm
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.Power
import com.vortessence.arithmeticus.core.math.expressions.TrigType
import com.vortessence.arithmeticus.core.math.expressions.TrigonometricFunction
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.Variable

/**
 * Solves an equation in which the variable stands once, by undoing what is done to it, one operation after the other:
 * ln(x) = 1000 is x = e¹⁰⁰⁰, x³ = 10⁴⁰⁰ is x = 10^(400/3), tan(x) = 10¹⁰ is x = atan(10¹⁰) + πk. Exact all the way, so it
 * also solves what is beyond the numbers the search can try. Each step keeps to where it is defined: an even power or e^…
 * of what is negative has no real solution, ln(…) is undone only for a positive base.
 */
internal object Isolation {

    /** The solutions and, for a family (tan), the whole number they depend on. */
    class Result(val solutions: List<Expression>, val period: Variable?) {
        /** "x = …, x = …", with "k ∈ ℤ" after a family. */
        fun asExpression(v: Variable): Expression {
            val equations = solutions.map { Equals(v, it) }
            return if (period == null) Alternatives(equations, inline = true)
            else Alternatives(equations + TextExpression("${period.identifier} ∈ ℤ"), inline = true)
        }
    }

    /** The solutions of [equation] for [v]; an empty list if there are none, null where this cannot tell. */
    fun solve(equation: Equals, v: Variable, context: MathContext): Result? {
        if (context.complexNumbers) return null
        val scratch = context.copy()
        // The side with the variable simplified, to see its structure; the other as typed, so that 10⁵⁰⁰ stays a power, whose
        // logarithm is 500·ln(10) (as a whole number it would be too large for its logarithm to be taken)
        var left = equation.left
        var right = equation.right
        if (right.containsVariable(v)) left = right.also { right = left }
        // Once as typed, too: simplifying (x² − 4)/(x − 2) to x + 2 would lose that x = 2 is not allowed
        if (right.containsVariable(v) || occurrences(left, v) != 1) return null
        left = left.canonicalize(scratch)
        if (scratch.shouldStop) return null
        if (right.containsVariable(v) || occurrences(left, v) != 1) return null
        val k = listOf("k", "n", "m").map { Variable(it) }.firstOrNull { it !in equation.variables() } ?: return null
        var periodic = false

        var branches = listOf(right)
        var current = left
        while (current != v) {
            if (scratch.shouldStop) return null
            when (current) {
                is Additions -> {
                    val (inner, rest) = current.operands.partition { it.containsVariable(v) }
                    val others = Additions(rest)
                    branches = branches.map { Additions(listOf(it, Multiplications.of(Value.MINUS_ONE, others))) }
                    current = inner.single()
                }
                is Multiplications -> {
                    val (inner, rest) = current.operands.partition { it.containsVariable(v) }
                    val factor = Multiplications(rest)
                    val size = factor.evaluateNumerically()
                    if (size.isNaN() || size == 0.0) return null
                    branches = branches.map { Multiplications.of(it, Power(factor, Value.MINUS_ONE)) }
                    current = inner.single()
                }
                is Power -> {
                    if (current.base.containsVariable(v)) {
                        val n = (current.exponent as? Value)?.rational ?: return null
                        branches = branches.flatMap { r ->
                            val sign = signOf(r, k) ?: return null
                            when {
                                // x^(1/q): x = r^q, for an even root only where r is not negative
                                n.numerator.value == java.math.BigInteger.ONE && n.isFractional -> {
                                    val q = n.denominator.value
                                    if (sign < 0 && !q.testBit(0)) emptyList() else listOf(Power(r, Value(q.toLong())))
                                }
                                n.isFractional || n.isZero -> return null
                                // An odd power: the real root, of the sign of r
                                n.numerator.value.testBit(0) -> listOf(signedRoot(r, sign, n.numerator.value.toLong()))
                                // An even power: ± the root of what is positive
                                sign < 0 -> emptyList()
                                sign == 0 -> if (n.isNegative) emptyList() else listOf(Value.ZERO)
                                else -> {
                                    val root = Power(r, Value(1L, n.numerator.value.toLong()))
                                    listOf(Multiplications.of(Value.MINUS_ONE, root), root)
                                }
                            }
                        }
                        current = current.base
                    } else {
                        // a^(…) = r: … = ln(r)/ln(a), for a positive a other than 1 and a positive r
                        val base = current.base
                        val a = base.evaluateNumerically()
                        if (a.isNaN() || a <= 0.0 || a == 1.0) return null
                        branches = branches.flatMap { r ->
                            val sign = signOf(r, k) ?: return null
                            if (sign <= 0) emptyList()
                            else if (base == Constant.E) listOf(Logarithm.ln(r))
                            else listOf(Multiplications.of(Logarithm.ln(r), Power(Logarithm.ln(base), Value.MINUS_ONE)))
                        }
                        current = current.exponent
                    }
                }
                is Logarithm -> {
                    if (current.base.containsVariable(v)) return null
                    val base = current.base.evaluateNumerically()
                    if (base.isNaN() || base <= 0.0 || base == 1.0) return null
                    branches = branches.map { Power(current.base, it) }
                    current = current.operand
                }
                is TrigonometricFunction -> {
                    // tan(…) = r: … = atan(r) + πk, once, in radians
                    if (current.type != TrigType.TAN || periodic || (current.angleUnit != null && current.angleUnit != AngleUnit.RADIANS)) return null
                    if (branches.any { it.containsVariable(k) }) return null
                    branches = branches.map { r ->
                        Additions(listOf(TrigonometricFunction(TrigType.ATAN, r, AngleUnit.RADIANS), Multiplications.of(Constant.PI, k)))
                    }
                    periodic = true
                    current = current.operand
                }
                else -> return null
            }
        }
        val solutions = branches.map { it.canonicalize(scratch) }
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
        return Result(solutions, k.takeIf { periodic })
    }

    /** How often [v] stands in [e]. */
    private fun occurrences(e: Expression, v: Variable): Int = if (e == v) 1 else e.children.sumOf { occurrences(it, v) }

    /** The sign of [r] (−1, 0 or 1); null if it depends on k or cannot be told. */
    private fun signOf(r: Expression, k: Variable): Int? {
        if (r.containsVariable(k)) return null
        val x = r.evaluateNumerically()
        if (x.isNaN()) return null
        return if (x > 0) 1 else if (x < 0) -1 else 0
    }

    /** The real n-th root of r, n odd: −(−r)^(1/n) for a negative r. */
    private fun signedRoot(r: Expression, sign: Int, n: Long): Expression =
        if (sign < 0) Multiplications.of(Value.MINUS_ONE, Power(Multiplications.of(Value.MINUS_ONE, r), Value(1L, n)))
        else Power(r, Value(1L, n))
}
