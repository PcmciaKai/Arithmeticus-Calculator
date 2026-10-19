package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue

/**
 * A matrix of expressions, stored row by row. Matrices are evaluated eagerly: sums, products
 * and powers that contain a matrix canonicalize to a single [MatrixExpression], so a matrix
 * never stays inside an [Additions], [Multiplications] or [Power] (matrix products are not
 * commutative, and those classes assume they are).
 */
class MatrixExpression(
    val rows: Int,
    val cols: Int,
    val elements: List<Expression>
) : Expression() {

    init {
        require(elements.size == rows * cols) { "Matrix elements size must be rows * cols" }
    }

    val isSquare: Boolean get() = rows == cols

    /** Whether this is a vector: one row or one column. */
    val isVector: Boolean get() = rows == 1 || cols == 1

    operator fun get(row: Int, col: Int): Expression = elements[row * cols + col]

    override val children: List<Expression> get() = elements

    override fun copyWithChildren(children: List<Expression>): Expression {
        return MatrixExpression(rows, cols, children)
    }

    override fun canonicalize(context: MathContext): Expression {
        val canonical = elements.map { element ->
            element.canonicalize(context).also { if (context.shouldStop) return this }
        }
        if (canonical.any { it is MatrixExpression }) {
            // Matrices of matrices are not supported
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return this
        }
        return MatrixExpression(rows, cols, canonical)
    }

    /** A matrix is not a quantity with a unit as a whole, even if its entries have units. */
    override fun partWithUnit(): Expression? = null

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
                val sumTerms = (0 until cols).map { k -> Multiplications.of(this[r, k], other[k, c]) }
                newElements.add(Additions(sumTerms).canonicalize(context))
            }
        }
        return MatrixExpression(rows, other.cols, newElements)
    }

    fun timesScalar(scalar: Expression, context: MathContext): MatrixExpression {
        if (scalar == Value.ONE) return this
        val newElements = elements.map { Multiplications.of(scalar, it).canonicalize(context) }
        return MatrixExpression(rows, cols, newElements)
    }

    fun transpose(): MatrixExpression {
        val newElements = (0 until cols).flatMap { c -> (0 until rows).map { r -> this[r, c] } }
        return MatrixExpression(cols, rows, newElements)
    }

    /** The determinant, or null (with an issue reported) if this matrix is not square. */
    fun determinant(context: MathContext): Expression? {
        if (!isSquare) {
            context.stopWithError(MathIssue.MATRIX_NOT_SQUARE)
            return null
        }
        val values = valueEntries()
        return if (values != null) valueDeterminant(values) else cofactorDeterminant(elements, rows, context)
    }

    /** The inverse, or null (with an issue reported) if this matrix is not square or singular. */
    fun inverse(context: MathContext): MatrixExpression? {
        if (!isSquare) {
            context.stopWithError(MathIssue.MATRIX_NOT_SQUARE)
            return null
        }
        val values = valueEntries()
        val inverse = if (values != null) valueInverse(values) else adjugateInverse(context)
        if (inverse == null && !context.shouldStop) context.stopWithError(MathIssue.SINGULAR_MATRIX)
        return inverse
    }

    /**
     * This matrix raised to an integer power by repeated squaring; negative powers use the
     * inverse and the zeroth power is the identity matrix.
     */
    fun pow(exponent: Long, context: MathContext): MatrixExpression? {
        if (!isSquare) {
            context.stopWithError(MathIssue.MATRIX_NOT_SQUARE)
            return null
        }
        var base = if (exponent < 0) inverse(context) ?: return null else this
        var remaining = if (exponent < 0) -exponent else exponent
        var result = identity(rows)
        while (remaining > 0) {
            if (remaining and 1L == 1L) result = result.times(base, context)!!
            remaining = remaining shr 1
            if (remaining > 0) base = base.times(base, context)!!
            if (context.shouldStop) return null
        }
        return result
    }

    /** The entries if all of them are plain numbers, which allows Gaussian elimination. */
    private fun valueEntries(): List<Value>? = elements.map { it as? Value ?: return null }

    /** Determinant by Gaussian elimination: exact for fractions, partial pivoting for decimals. */
    private fun valueDeterminant(values: List<Value>): Value {
        val n = rows
        val a = Array(n) { r -> Array(n) { c -> values[r * n + c] } }
        var det = Value.ONE
        for (col in 0 until n) {
            val pivotRow = pivotRow(a, col) ?: return Value.ZERO
            if (pivotRow != col) {
                a[pivotRow] = a[col].also { a[col] = a[pivotRow] }
                det = det.negate()
            }
            val pivot = a[col][col]
            det = det.times(pivot)
            for (r in col + 1 until n) {
                if (a[r][col].isZero) continue
                val factor = a[r][col].div(pivot)
                for (c in col until n) a[r][c] = a[r][c].minus(factor.times(a[col][c]))
            }
        }
        return det
    }

    /** Inverse by Gauss-Jordan elimination, or null if the matrix is singular. */
    private fun valueInverse(values: List<Value>): MatrixExpression? {
        val n = rows
        val a = Array(n) { r -> Array(n) { c -> values[r * n + c] } }
        val inv = Array(n) { r -> Array(n) { c -> if (r == c) Value.ONE else Value.ZERO } }
        for (col in 0 until n) {
            val pivotRow = pivotRow(a, col) ?: return null
            if (pivotRow != col) {
                a[pivotRow] = a[col].also { a[col] = a[pivotRow] }
                inv[pivotRow] = inv[col].also { inv[col] = inv[pivotRow] }
            }
            val pivot = a[col][col]
            for (c in 0 until n) {
                a[col][c] = a[col][c].div(pivot)
                inv[col][c] = inv[col][c].div(pivot)
            }
            for (r in 0 until n) {
                if (r == col || a[r][col].isZero) continue
                val factor = a[r][col]
                for (c in 0 until n) {
                    a[r][c] = a[r][c].minus(factor.times(a[col][c]))
                    inv[r][c] = inv[r][c].minus(factor.times(inv[col][c]))
                }
            }
        }
        return MatrixExpression(n, n, inv.flatMap { it.toList() })
    }

    /** The row (at or below [col]) with the largest non-zero entry in [col], or null if there is none. */
    private fun pivotRow(a: Array<Array<Value>>, col: Int): Int? =
        (col until a.size).filter { !a[it][col].isZero }.maxByOrNull { kotlin.math.abs(a[it][col].real.value) }

    /** Inverse of a matrix with symbolic entries: the adjugate divided by the determinant. */
    private fun adjugateInverse(context: MathContext): MatrixExpression? {
        val det = cofactorDeterminant(elements, rows, context)
        if (context.shouldStop || det.isZero) return null
        val n = rows
        val adjugate = List(n * n) { index ->
            val r = index / n
            val c = index % n
            // adj(A)[r, c] is the cofactor of A[c, r]
            val minor = cofactorDeterminant(minorElements(elements, n, c, r), n - 1, context)
            if (context.shouldStop) return null
            (if ((r + c) % 2 == 0) minor else minor.negate()).canonicalize(context)
        }
        return MatrixExpression(n, n, adjugate).timesScalar(Power.of(det, Value.MINUS_ONE).canonicalize(context), context)
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

    companion object {
        fun identity(n: Int): MatrixExpression =
            MatrixExpression(n, n, List(n * n) { if (it / n == it % n) Value.ONE else Value.ZERO })

        /**
         * Determinant by expansion along the first row, for symbolic entries. At most 5×5
         * matrices are typed, so the n! terms stay manageable.
         */
        private fun cofactorDeterminant(elements: List<Expression>, n: Int, context: MathContext): Expression {
            if (n == 1) return elements[0]
            val terms = (0 until n).map { c ->
                val minor = cofactorDeterminant(minorElements(elements, n, 0, c), n - 1, context)
                val term = Multiplications.of(elements[c], minor)
                if (c % 2 == 0) term else term.negate()
            }
            return Additions(terms).canonicalize(context)
        }

        /** The entries of the n×n matrix [elements] without row [skipRow] and column [skipCol]. */
        private fun minorElements(elements: List<Expression>, n: Int, skipRow: Int, skipCol: Int): List<Expression> =
            (0 until n).filter { it != skipRow }.flatMap { r ->
                (0 until n).filter { it != skipCol }.map { c -> elements[r * n + c] }
            }
    }
}

/** The determinant det(A) of a square matrix; the determinant of a scalar is the scalar itself. */
class Determinant(val operand: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression = Determinant(children[0])

    override fun canonicalize(context: MathContext): Expression {
        val op = operand.canonicalize(context)
        if (context.shouldStop) return this
        if (op !is MatrixExpression) return op
        return op.determinant(context)?.canonicalize(context) ?: this
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double =
        operand.evaluateNumerically(bindings)

    override fun equals(other: Any?): Boolean = other is Determinant && operand == other.operand

    override fun hashCode(): Int = operand.hashCode() * 31 + 7

    override fun toString(): String = "det($operand)"
}

/** rref(A): the reduced row echelon form of a matrix of numbers, by Gauss-Jordan. */
class RowReduced(val operand: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression = RowReduced(children[0])

    override fun canonicalize(context: MathContext): Expression {
        val op = operand.canonicalize(context)
        if (context.shouldStop) return this
        if (op !is MatrixExpression) {
            context.stopWithError(MathIssue.MATRIX_EXPECTED)
            return this
        }
        return com.vortessence.arithmeticus.core.math.steps.MatrixSteps.rowReduced(op, context) ?: this
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = Double.NaN

    override fun equals(other: Any?): Boolean = other is RowReduced && operand == other.operand

    override fun hashCode(): Int = operand.hashCode() * 31 + 11

    override fun toString(): String = "rref($operand)"
}

/** eig(A): the eigenvalues of a square matrix of numbers, each with an eigenvector (as far as they are exact). */
class EigenSystem(val operand: Expression) : Expression() {

    override val children: List<Expression> get() = listOf(operand)

    override fun copyWithChildren(children: List<Expression>): Expression = EigenSystem(children[0])

    override fun canonicalize(context: MathContext): Expression {
        val op = operand.canonicalize(context)
        if (context.shouldStop) return this
        if (op !is MatrixExpression) {
            context.stopWithError(MathIssue.MATRIX_EXPECTED)
            return this
        }
        return com.vortessence.arithmeticus.core.math.steps.MatrixSteps.eigenSystem(op, context) ?: this
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = Double.NaN

    override fun equals(other: Any?): Boolean = other is EigenSystem && operand == other.operand

    override fun hashCode(): Int = operand.hashCode() * 31 + 13

    override fun toString(): String = "eig($operand)"
}

/** The matrix that [expressions] (canonical factors, in order) multiply to; scalars commute. */
internal fun multiplyWithMatrices(expressions: List<Expression>, context: MathContext): MatrixExpression? {
    var matrix: MatrixExpression? = null
    val scalars = mutableListOf<Expression>()
    for (expr in expressions) {
        if (expr is MatrixExpression) {
            matrix = if (matrix == null) expr else matrix.times(expr, context) ?: run {
                context.stopWithError(MathIssue.DIMENSION_MISMATCH)
                return null
            }
            if (context.shouldStop) return null
        } else {
            scalars.add(expr)
        }
    }
    if (matrix == null) return null
    if (scalars.isEmpty()) return matrix
    val scalar = Multiplications(scalars).canonicalize(context)
    if (context.shouldStop) return null
    return matrix.timesScalar(scalar, context)
}

/** The sum of [terms] (canonical) if any is a matrix; all of them must be matrices of the same size. */
internal fun addMatrices(terms: List<Expression>, context: MathContext): MatrixExpression? {
    var sum: MatrixExpression? = null
    for (term in terms) {
        if (term !is MatrixExpression) {
            // A matrix plus a number is undefined
            context.stopWithError(MathIssue.MATRIX_AND_SCALAR)
            return null
        }
        sum = if (sum == null) term else sum.plus(term, context) ?: run {
            context.stopWithError(MathIssue.DIMENSION_MISMATCH)
            return null
        }
        if (context.shouldStop) return null
    }
    return sum
}

/** A matrix raised to [exponent] (canonical): an integer power, or the transpose for "T". */
internal fun matrixPower(base: MatrixExpression, exponent: Expression, context: MathContext): MatrixExpression? {
    if (exponent == Variable("T")) return base.transpose()
    val value = exponent as? Value
    val rational = value?.rational
    val n: Long? = when {
        rational != null -> if (rational.isFractional || rational.numerator.value.bitLength() >= 63) null
            else rational.numerator.toLong()
        value != null && value.real.value % 1.0 == 0.0 && kotlin.math.abs(value.real.value) < Long.MAX_VALUE ->
            value.real.value.toLong()
        else -> null
    }
    if (n == null) {
        // Roots, symbolic powers, ... of matrices are not supported
        context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
        return null
    }
    return base.pow(n, context)
}
