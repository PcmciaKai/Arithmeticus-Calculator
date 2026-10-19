package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.type.MathOp

/** The relation of an inequality, in the direction it is written: left [op] right. */
enum class Relation(val op: MathOp, val strict: Boolean, val greater: Boolean) {
    LESS(MathOp.LESS, true, false),
    GREATER(MathOp.GREATER, true, true),
    LESS_EQUAL(MathOp.LESS_EQUAL, false, false),
    GREATER_EQUAL(MathOp.GREATER_EQUAL, false, true);

    /** The relation with the sides swapped, or both multiplied by a negative number: < becomes >. */
    fun reversed(): Relation = when (this) {
        LESS -> GREATER
        GREATER -> LESS
        LESS_EQUAL -> GREATER_EQUAL
        GREATER_EQUAL -> LESS_EQUAL
    }

    /** Whether a left side that is [difference] more than the right one satisfies the relation (the sign of left − right). */
    fun holds(difference: Double, tolerance: Double): Boolean {
        val zero = kotlin.math.abs(difference) <= tolerance
        return when {
            zero -> !strict
            greater -> difference > 0
            else -> difference < 0
        }
    }

    companion object {
        fun of(op: MathOp): Relation? = values().firstOrNull { it.op == op }
    }
}

/**
 * left < right, left > right, left ≤ right or left ≥ right, e.g. x² − 4 > 0. Solved for the variable
 * by [InequalitySolver]: the result is a set of intervals.
 */
class Inequality(
    val left: Expression,
    val right: Expression,
    val relation: Relation
) : Expression() {

    override val children: List<Expression> get() = listOf(left, right)

    override fun copyWithChildren(children: List<Expression>): Expression {
        require(children.size == 2)
        return Inequality(children[0], children[1], relation)
    }

    override fun canonicalize(context: MathContext): Expression {
        val cLeft = left.canonicalize(context)
        if (context.shouldStop) return this
        val cRight = right.canonicalize(context)
        if (context.shouldStop) return this
        return Inequality(cLeft, cRight, relation)
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double =
        if (relation.holds(left.evaluateNumerically(bindings) - right.evaluateNumerically(bindings), 0.0)) 1.0 else 0.0

    /** For an inequality without variables: whether it holds; null if it cannot be decided. */
    fun truthValue(context: MathContext = MathContext.current()): Boolean? {
        val diff = Additions(left, right.negate()).canonicalize(context)
        if (context.shouldStop || diff.variables().isNotEmpty()) return null
        val numeric = diff.evaluateNumerically()
        if (numeric.isNaN()) return null
        if (diff.isZero) return !relation.strict
        val scale = maxOf(1.0, kotlin.math.abs(left.evaluateNumerically()), kotlin.math.abs(right.evaluateNumerically()))
        return relation.holds(numeric, 1e-12 * scale)
    }

    override fun equals(other: Any?): Boolean = other is Inequality && relation == other.relation && left == other.left && right == other.right

    override fun hashCode(): Int = (left.hashCode() * 31 + right.hashCode()) * 31 + relation.hashCode()

    override fun toString(): String = "$left ${relation.op.symbol} $right"
}

/** lower [lowerRelation] variable [upperRelation] upper, e.g. −2 < x ≤ 3: one interval of a solution. */
class Between(
    val lower: Expression,
    val lowerRelation: Relation,
    val variable: Expression,
    val upperRelation: Relation,
    val upper: Expression
) : Expression() {

    override val children: List<Expression> get() = listOf(lower, variable, upper)

    override fun copyWithChildren(children: List<Expression>): Expression =
        Between(children[0], lowerRelation, children[1], upperRelation, children[2])

    override fun canonicalize(context: MathContext): Expression = this

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = Double.NaN

    override fun equals(other: Any?): Boolean = other is Between && lowerRelation == other.lowerRelation &&
        upperRelation == other.upperRelation && lower == other.lower && variable == other.variable && upper == other.upper

    override fun hashCode(): Int = ((lower.hashCode() * 31 + variable.hashCode()) * 31 + upper.hashCode()) * 31 + lowerRelation.hashCode() * 7 + upperRelation.hashCode()

    override fun toString(): String = "$lower ${lowerRelation.op.symbol} $variable ${upperRelation.op.symbol} $upper"
}
