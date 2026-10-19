package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue

/**
 * Base class for all mathematical expressions in the symbolic math engine.
 */
abstract class Expression {

    open val children: List<Expression> get() = emptyList()

    open fun copyWithChildren(children: List<Expression>): Expression = this

    fun transform(fn: (Expression) -> Expression): Expression {
        val result = fn(this)
        // A replaced node is final: rebuilding it from the original node's children would
        // overwrite the replacement's own children (and may not even fit its arity).
        if (result !== this) return result
        val currentChildren = children
        if (currentChildren.isNotEmpty()) {
            val newChildren = currentChildren.map { it.transform(fn) }
            if (newChildren != currentChildren) {
                return result.copyWithChildren(newChildren)
            }
        }
        return result
    }

    /** The free variables of this expression (integration variables are bound, not free). */
    open fun variables(): Set<Variable> {
        val vars = mutableSetOf<Variable>()
        for (child in children) vars.addAll(child.variables())
        return vars
    }

    fun containsVariable(variable: Variable): Boolean = variables().contains(variable)

    fun containsExpressionMatching(predicate: (Expression) -> Boolean): Boolean {
        val queue = ArrayDeque<Expression>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val expr = queue.removeFirst()
            if (predicate(expr)) return true
            queue.addAll(expr.children)
        }
        return false
    }

    fun substitute(pattern: Expression, substitute: Expression): Expression {
        return transform { if (it == pattern) substitute else it }
    }

    open fun coefficient(): Value = Value.ONE

    open fun partWithoutCoefficient(): Expression? = this

    open fun partWithUnit(): Expression? {
        return if (containsExpressionMatching { it is UnitExpression }) this else null
    }

    /**
     * Converts this quantity into [targetUnit] (a unit or a product of units, e.g. km/h), or
     * returns null (with [MathIssue.INVALID_UNIT] reported) if the dimensions do not match.
     * A value without any unit is taken to already be in [targetUnit] (e.g. 5 + 3 m = 8 m).
     *
     * With [includeUnitInResult] the result is "magnitude × targetUnit", otherwise only the
     * magnitude, e.g. 2 km -> 2000 m or 2000.
     */
    open fun convertToUnit(
        targetUnit: Expression,
        includeUnitInResult: Boolean,
        context: MathContext = MathContext.current(),
    ): Expression? {
        val targetDimension = dimensionOf(targetUnit)
        if (targetDimension == null || !targetUnit.containsUnit()) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return null
        }
        if (!containsUnit()) {
            return if (includeUnitInResult) Multiplications.of(this, targetUnit).canonicalize(context) else this
        }
        if (dimensionOf(this) != targetDimension) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return null
        }

        val sourceUnit = partWithUnit()
        val converted = if (targetDimension == Dimension.TEMPERATURE && sourceUnit is UnitExpression &&
            targetUnit is UnitExpression && sourceUnit.offset != null && targetUnit.offset != null
        ) {
            // A temperature on a scale with a shifted zero, e.g. 20 °C -> 68 °F:
            // kelvin = value * f1 + o1  and  result = (kelvin - o2) / f2
            val magnitude = transform { if (it is UnitExpression) Value.ONE else it }
            Multiplications.of(
                Additions(
                    Multiplications.of(magnitude, sourceUnit.factor),
                    Value(sourceUnit.offset - targetUnit.offset)
                ),
                Power(targetUnit.factor, Value.MINUS_ONE)
            )
        } else {
            // Multiplicative units: magnitude in SI divided by the target unit's SI factor
            Multiplications.of(withUnitsAsSiFactors(), Power(targetUnit.withUnitsAsSiFactors(), Value.MINUS_ONE))
        }.canonicalize(context)
        if (context.shouldStop) return null
        return if (includeUnitInResult) Multiplications.of(converted, targetUnit).canonicalize(context) else converted
    }

    abstract fun canonicalize(context: MathContext = MathContext.current()): Expression

    /** Evaluates with the given variable values; unbound variables evaluate to NaN. */
    abstract fun evaluateNumerically(bindings: Map<Variable, Double>): Double

    fun evaluateNumerically(value: Double = 0.0, variable: Variable? = null): Double =
        evaluateNumerically(if (variable == null) emptyMap() else mapOf(variable to value))

    open val isNegative: Boolean get() = false
    open val isZero: Boolean get() = false

    open fun negate(): Expression = Multiplications.of(Value.MINUS_ONE, this)
    open fun invert(): Expression = Power.of(this, Value.MINUS_ONE)

    abstract override fun equals(other: Any?): Boolean
    abstract override fun hashCode(): Int
    abstract override fun toString(): String
}

/**
 * Multiset comparison for commutative operators: order is irrelevant, multiplicity is not
 * (so x + x differs from x + x + x, and x·y equals y·x).
 */
internal fun sameOperandsIgnoringOrder(a: List<Expression>, b: List<Expression>): Boolean {
    if (a.size != b.size) return false
    return a.groupingBy { it }.eachCount() == b.groupingBy { it }.eachCount()
}

internal fun operandMultisetHash(operands: List<Expression>): Int = operands.sumOf { it.hashCode() }
