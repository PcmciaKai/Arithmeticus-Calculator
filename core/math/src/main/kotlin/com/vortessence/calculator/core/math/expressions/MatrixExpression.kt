package com.vortessence.calculator.core.math.expressions

import com.vortessence.calculator.core.math.context.MathContext

class MatrixExpression(
    val rows: Int,
    val cols: Int,
    val elements: List<Expression>
) : Expression() {

    init {
        require(elements.size == rows * cols) { "Matrix elements size must be rows * cols" }
    }

    override val children: List<Expression> get() = elements

    override fun copyWithChildren(children: List<Expression>): Expression {
        return MatrixExpression(rows, cols, children)
    }

    override fun canonicalize(context: MathContext): Expression {
        return MatrixExpression(rows, cols, elements.map { it.canonicalize(context) })
    }

    fun plus(other: MatrixExpression, context: MathContext): MatrixExpression? {
        if (rows != other.rows || cols != other.cols) return null
        val newElements = elements.indices.map { i ->
            Additions.of(elements[i], other.elements[i]).canonicalize(context)
        }
        return MatrixExpression(rows, cols, newElements)
    }

    fun times(other: MatrixExpression, context: MathContext): MatrixExpression? {
        if (cols != other.rows) return null
        val newElements = mutableListOf<Expression>()
        for (r in 0 until rows) {
            for (c in 0 until other.cols) {
                val sumTerms = (0 until cols).map { k ->
                    Multiplications.of(elements[r * cols + k], other.elements[k * other.cols + c])
                }
                newElements.add(Additions(sumTerms).canonicalize(context))
            }
        }
        return MatrixExpression(rows, other.cols, newElements)
    }

    fun timesScalar(scalar: Expression, context: MathContext): MatrixExpression {
        val newElements = elements.map { Multiplications.of(scalar, it).canonicalize(context) }
        return MatrixExpression(rows, cols, newElements)
    }

    fun determinant(context: MathContext): Expression? {
        if (rows != cols) return null
        if (rows == 1) return elements[0]
        if (rows == 2) {
            val ad = Multiplications.of(elements[0], elements[3]).canonicalize(context)
            val bc = Multiplications.of(elements[1], elements[2]).canonicalize(context)
            return Additions(ad, bc.negate()).canonicalize(context)
        }
        return null
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        return Double.NaN
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MatrixExpression) return false
        return rows == other.rows && cols == other.cols && elements == other.elements
    }

    override fun hashCode(): Int {
        var result = rows
        result = 31 * result + cols
        result = 31 * result + elements.hashCode()
        return result
    }

    override fun toString(): String {
        return "Matrix(${rows}x${cols})"
    }
}
