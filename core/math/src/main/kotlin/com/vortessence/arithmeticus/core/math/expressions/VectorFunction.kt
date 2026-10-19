package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue

/** The operations on vectors; a vector is a matrix of one row or one column. */
enum class VectorKind(val functionName: String, val argumentCount: Int) {
    /** The dot product a₁b₁ + a₂b₂ + …, a number. */
    DOT("dot", 2),
    /** The cross product of two vectors with 3 components, a vector. */
    CROSS("cross", 2),
    /** The length √(a₁² + a₂² + …). */
    NORM("norm", 1),
    /** The angle between two vectors, in the unit of the angle setting. */
    ANGLE("angle", 2),
    /** The projection of the first vector onto the second one: (a·b)/(b·b)·b. */
    PROJECTION("proj", 2),
    /** The vector divided by its length: the vector of length 1 in the same direction. */
    UNIT("unitvec", 1);

    companion object {
        fun byName(name: String): VectorKind? = entries.firstOrNull { it.functionName == name }
    }
}

/**
 * A [VectorKind] applied to [arguments]. Vectors are matrices of one row or one column, so a column and a row of the same
 * length are the same vector for these functions. A result that is a vector has the form of the first argument; the
 * projection is a multiple of the second one and has its form. For vectors with complex components the dot product
 * conjugates the second vector (Σ aᵢ·b̄ᵢ), so that dot(v, v) is |v|² and the angle and the projection are the usual ones.
 * It is calculated as soon as the arguments are matrices and stays as it is while they are only variables that are not
 * defined yet.
 */
class VectorFunction(val kind: VectorKind, val arguments: List<Expression>) : Expression() {

    override val children: List<Expression> get() = arguments

    override fun copyWithChildren(children: List<Expression>): Expression = VectorFunction(kind, children)

    override fun canonicalize(context: MathContext): Expression {
        val args = arguments.map { it.canonicalize(context) }
        if (context.shouldStop) return this
        if (args.size != kind.argumentCount) return fail(context, MathIssue.ARGUMENT_COUNT)
        // Not matrices yet: wait for the variables. A matrix with something that is not one cannot wait (a matrix is not part
        // of a result that is not a matrix), and without variables it is no vector
        if (args.any { it !is MatrixExpression }) {
            val waiting = args.none { it is MatrixExpression } && args.any { it.variables().isNotEmpty() }
            return if (waiting) VectorFunction(kind, args) else fail(context, MathIssue.VECTOR_EXPECTED)
        }
        val vectors = args.map { it as MatrixExpression }
        if (vectors.any { !it.isVector }) return fail(context, MathIssue.VECTOR_EXPECTED)
        if (kind != VectorKind.NORM && kind != VectorKind.UNIT && vectors[0].elements.size != vectors[1].elements.size) {
            return fail(context, MathIssue.DIMENSION_MISMATCH)
        }
        return when (kind) {
            VectorKind.DOT -> dot(vectors[0], vectors[1], context).canonicalize(context)
            VectorKind.CROSS -> cross(vectors[0], vectors[1], context) ?: return this
            VectorKind.NORM -> norm(vectors[0]).canonicalize(context)
            VectorKind.ANGLE -> angle(vectors[0], vectors[1], context) ?: return this
            VectorKind.PROJECTION -> projection(vectors[0], vectors[1], context) ?: return this
            VectorKind.UNIT -> unit(vectors[0], context) ?: return this
        }
    }

    private fun fail(context: MathContext, issue: MathIssue): Expression {
        context.stopWithError(issue)
        return this
    }

    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double {
        if (arguments.size != kind.argumentCount) return Double.NaN
        val vectors = arguments.map { it as? MatrixExpression ?: return Double.NaN }
        if (vectors.any { !it.isVector }) return Double.NaN
        fun values(m: MatrixExpression) = m.elements.map { it.evaluateNumerically(bindings) }
        return when (kind) {
            VectorKind.NORM -> Math.sqrt(values(vectors[0]).sumOf { it * it })
            VectorKind.DOT -> if (vectors[0].elements.size == vectors[1].elements.size) values(vectors[0]).zip(values(vectors[1])).sumOf { (a, b) -> a * b } else Double.NaN
            VectorKind.ANGLE -> {
                val a = values(vectors[0])
                val b = values(vectors[1])
                if (a.size != b.size) return Double.NaN
                val ratio = a.zip(b).sumOf { (x, y) -> x * y } / (Math.sqrt(a.sumOf { it * it }) * Math.sqrt(b.sumOf { it * it }))
                val radians = Math.acos(ratio.coerceIn(-1.0, 1.0))
                // In the unit of the angle setting, like the result of the calculation
                if (MathContext.current().angleUnit == AngleUnit.DEGREES) Math.toDegrees(radians) else radians
            }
            // The others are vectors
            else -> Double.NaN
        }
    }

    override fun equals(other: Any?): Boolean = other is VectorFunction && kind == other.kind && arguments == other.arguments

    override fun hashCode(): Int = kind.hashCode() * 31 + arguments.hashCode()

    override fun toString(): String = "${kind.functionName}(${arguments.joinToString(", ")})"

    companion object {
        /**
         * a₁b̄₁ + a₂b̄₂ + …, not yet worked out (the bar, the complex conjugate, only for components with an i). Written as it
         * is, with the products as factors, so that the steps can show it.
         */
        internal fun dot(a: MatrixExpression, b: MatrixExpression, context: MathContext): Expression {
            val terms = a.elements.indices.map { Multiplications(listOf(a.elements[it], conjugate(b.elements[it], context))) }
            return if (terms.size == 1) terms.single() else Additions(terms)
        }

        /** √(a₁² + a₂² + …); of a complex component the modulus is squared. */
        internal fun norm(a: MatrixExpression): Expression {
            val squares = a.elements.map { if (it.containsImaginaryUnit()) Power(AbsoluteValue(it), Value.TWO) else Power(it, Value.TWO) }
            return Power(if (squares.size == 1) squares.single() else Additions(squares), Value.HALF)
        }

        /** a − bi for a + bi; a number without an i is its own conjugate. */
        private fun conjugate(e: Expression, context: MathContext): Expression {
            if (!e.containsImaginaryUnit()) return e
            val (re, im) = complexParts(e) ?: return e
            return complexOf(re, im.negate(), context)
        }

        private fun cross(a: MatrixExpression, b: MatrixExpression, context: MathContext): Expression? {
            if (a.elements.size != 3) {
                context.stopWithError(MathIssue.CROSS_NEEDS_THREE)
                return null
            }
            val (a1, a2, a3) = a.elements
            val (b1, b2, b3) = b.elements
            fun difference(p: Expression, q: Expression, r: Expression, s: Expression): Expression =
                Additions(listOf(Multiplications.of(p, q), Multiplications.of(Value.MINUS_ONE, r, s))).canonicalize(context)
            val components = listOf(difference(a2, b3, a3, b2), difference(a3, b1, a1, b3), difference(a1, b2, a2, b1))
            return MatrixExpression(a.rows, a.cols, components)
        }

        private fun angle(a: MatrixExpression, b: MatrixExpression, context: MathContext): Expression? {
            val lengths = Multiplications.of(norm(a), norm(b)).canonicalize(context)
            if (context.shouldStop) return null
            if (lengths.isZero) {
                // The direction of the zero vector is not defined
                context.stopWithError(MathIssue.UNDEFINED)
                return null
            }
            // For complex vectors the real part of the dot product is the one that makes the angle
            val product = dot(a, b, context).canonicalize(context)
            val real = if (product.containsImaginaryUnit()) complexParts(product)?.first ?: product else product
            val ratio = Multiplications.of(real, Power(lengths, Value.MINUS_ONE)).canonicalize(context)
            if (context.shouldStop) return null
            // Decimal components give a ratio that rounding can put just beyond ±1 (or next to it) for parallel vectors
            val bounded = if (ratio is Value && ratio.rational == null) {
                val d = ratio.real.value
                when {
                    d >= 1.0 - 1e-14 -> Value.ONE
                    d <= -1.0 + 1e-14 -> Value.MINUS_ONE
                    else -> ratio
                }
            } else ratio
            return TrigonometricFunction(TrigType.ACOS, bounded).canonicalize(context).takeUnless { context.shouldStop }
        }

        private fun projection(a: MatrixExpression, b: MatrixExpression, context: MathContext): Expression? {
            val squared = dot(b, b, context).canonicalize(context)
            if (context.shouldStop) return null
            if (squared.isZero) {
                context.stopWithError(MathIssue.UNDEFINED)
                return null
            }
            val factor = Multiplications.of(dot(a, b, context), Power(squared, Value.MINUS_ONE)).canonicalize(context)
            if (context.shouldStop) return null
            return b.timesScalar(factor, context)
        }

        private fun unit(a: MatrixExpression, context: MathContext): Expression? {
            val length = norm(a).canonicalize(context)
            if (context.shouldStop) return null
            if (length.isZero) {
                context.stopWithError(MathIssue.UNDEFINED)
                return null
            }
            return a.timesScalar(Power(length, Value.MINUS_ONE).canonicalize(context), context)
        }
    }
}
