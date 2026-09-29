package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue

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

    open fun quantityOfUnit(): Expression? {
        val unit = partWithUnit() ?: return null
        return unit.transform { (it as? UnitExpression)?.quantity ?: it }
    }

    open fun convertUnitsToBase(): Expression {
        return transform { (it as? UnitExpression)?.unitExpressedInBaseUnit ?: it }
    }

    open fun convertUnitsToDefault(context: MathContext = MathContext.current()): Expression {
        if (!containsExpressionMatching { it is UnitExpression }) return this
        val withUnit = partWithUnit()
        if (withUnit != null) {
            return convertToUnit(targetUnit = null, includeUnitInResult = true, context = context) ?: this
        }
        val newChildren = children.map { it.convertUnitsToDefault(context) }
        return copyWithChildren(newChildren)
    }

    open fun convertToUnit(
        targetUnit: Expression?,
        includeUnitInResult: Boolean,
        context: MathContext = MathContext.current(),
    ): Expression? {
        var result: Expression = this
        var sourceQuantity = quantityOfUnit()?.canonicalize(context)
        if (context.shouldStop) return null

        var effTarget = targetUnit
        if (effTarget == null) {
            if (sourceQuantity != null) {
                effTarget = UnitRegistry.defaultUnits[sourceQuantity]
                if (effTarget == null) {
                    context.stopWithError(MathIssue.INVALID_UNIT)
                    return null
                }
            } else {
                return this
            }
        }

        val targetQuantity = effTarget.quantityOfUnit()?.canonicalize(context)
        if (context.shouldStop) return null

        var isSourceQuantityImplicit = false
        if (sourceQuantity == null) {
            sourceQuantity = targetQuantity
            isSourceQuantityImplicit = true
        }

        if (sourceQuantity == Quantity.TEMPERATURE && targetQuantity == Quantity.TEMPERATURE) {
            return convertTemperature(effTarget, includeUnitInResult, isSourceQuantityImplicit, context)
        }

        if ((sourceQuantity == targetQuantity) && (sourceQuantity != null)) {
            val sourceUnit = sourceQuantity.transform {
                UnitRegistry.defaultUnits[it] ?: it
            }
            if (isSourceQuantityImplicit) {
                result = Multiplications.of(result, sourceUnit)
            }
            if (effTarget !is UnitExpression) {
                context.stopWithError(MathIssue.INVALID_UNIT)
                return null
            }
            var converted = result.convertUnitsToBase().substitute(
                effTarget.baseUnit,
                effTarget.baseUnitExpressedInUnit,
            )
            if (!includeUnitInResult) {
                converted = Multiplications.of(converted, effTarget.invert())
            }
            return converted.canonicalize(context)
        }
        return null
    }

    /** Affine temperature conversion, e.g. 20 °C -> 68 °F (see [UnitRegistry.temperatureToKelvin]). */
    private fun convertTemperature(
        target: Expression,
        includeUnitInResult: Boolean,
        isSourceImplicit: Boolean,
        context: MathContext,
    ): Expression? {
        val targetFactors = UnitRegistry.temperatureToKelvin[target]
        if (target !is UnitExpression || targetFactors == null) {
            context.stopWithError(MathIssue.INVALID_UNIT)
            return null
        }
        val magnitude = transform { if (it is UnitExpression) Value.ONE else it }.canonicalize(context)
        val converted = if (isSourceImplicit) {
            magnitude // a plain number next to temperatures is taken to be in the target unit
        } else {
            val sourceFactors = UnitRegistry.temperatureToKelvin[partWithUnit()]
            if (sourceFactors == null) {
                context.stopWithError(MathIssue.INVALID_UNIT)
                return null
            }
            // kelvin = m * s1 + o1  and  result = (kelvin - o2) / s2
            val (s1, o1) = sourceFactors
            val (s2, o2) = targetFactors
            Additions(
                Multiplications.of(magnitude, Value(s1 / s2)),
                Value((o1 - o2) / s2)
            ).canonicalize(context)
        }
        if (context.shouldStop) return null
        return if (includeUnitInResult) Multiplications.of(converted, target).canonicalize(context) else converted
    }

    abstract fun canonicalize(context: MathContext = MathContext.current()): Expression

    abstract fun evaluateNumerically(value: Double = 0.0, variable: Variable? = null): Double

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
