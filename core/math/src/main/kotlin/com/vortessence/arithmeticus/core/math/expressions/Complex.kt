package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sinh

/**
 * The imaginary unit i, with i² = −1. Complex numbers are sums a + b·i of real parts and
 * multiples of i, so the usual simplification of sums and products applies to them.
 * Only allowed when [MathContext.complexNumbers] is on.
 */
object ImaginaryUnit : Expression() {

    override fun canonicalize(context: MathContext): Expression {
        if (!context.complexNumbers) context.stopWithError(MathIssue.COMPLEX_RESULT)
        return this
    }

    /** i has no real value; see [numericComplex] for the value of a complex expression. */
    override fun evaluateNumerically(bindings: Map<Variable, Double>): Double = Double.NaN

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0x1d0f

    override fun toString(): String = "i"
}

fun Expression.containsImaginaryUnit(): Boolean = containsExpressionMatching { it === ImaginaryUnit }

/**
 * The real and imaginary part of [e] if it is written as a + b·i (with a and b free of i),
 * e.g. (3, −2) for 3 − 2i, or null for other uses of i such as sin(i).
 */
internal fun complexParts(e: Expression): Pair<Expression, Expression>? {
    if (e === ImaginaryUnit) return Value.ZERO to Value.ONE
    if (!e.containsImaginaryUnit()) return e to Value.ZERO
    return when (e) {
        is Multiplications -> {
            val rest = e.operands.filter { it !== ImaginaryUnit }
            if (e.operands.size - rest.size != 1 || rest.any { it.containsImaginaryUnit() }) null
            else Value.ZERO to Multiplications.of(*rest.toTypedArray())
        }
        is Additions -> {
            val re = mutableListOf<Expression>()
            val im = mutableListOf<Expression>()
            for (operand in e.operands) {
                val (a, b) = complexParts(operand) ?: return null
                if (!a.isZero) re.add(a)
                if (!b.isZero) im.add(b)
            }
            sumOf(re) to sumOf(im)
        }
        else -> null
    }
}

private fun sumOf(terms: List<Expression>): Expression = when (terms.size) {
    0 -> Value.ZERO
    1 -> terms[0]
    else -> Additions(terms)
}

/** re + im·i, canonicalized. */
internal fun complexOf(re: Expression, im: Expression, context: MathContext): Expression =
    Additions(listOf(re, Multiplications(listOf(im, ImaginaryUnit)))).canonicalize(context)

/** A complex floating point number, for complex results that have no exact form. */
internal class ComplexDouble(val re: Double, val im: Double) {
    val abs: Double get() = hypot(re, im)
    val arg: Double get() = atan2(im, re)
    val isFinite: Boolean get() = re.isFinite() && im.isFinite()

    operator fun plus(o: ComplexDouble) = ComplexDouble(re + o.re, im + o.im)
    operator fun times(o: ComplexDouble) = ComplexDouble(re * o.re - im * o.im, re * o.im + im * o.re)
    operator fun div(o: ComplexDouble): ComplexDouble {
        val d = o.re * o.re + o.im * o.im
        return ComplexDouble((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
    }

    fun exp(): ComplexDouble {
        val m = exp(re)
        return ComplexDouble(m * cos(im), m * sin(im))
    }

    /** Principal value: ln|z| + i·arg(z), with arg in (−π, π]. */
    fun ln(): ComplexDouble = ComplexDouble(ln(abs), arg)

    fun pow(w: ComplexDouble): ComplexDouble {
        if (re == 0.0 && im == 0.0) return ComplexDouble(0.0, 0.0)
        return (ln() * w).exp()
    }

    fun sin() = ComplexDouble(sin(re) * cosh(im), cos(re) * sinh(im))
    fun cos() = ComplexDouble(cos(re) * cosh(im), -sin(re) * sinh(im))
    fun sinh() = ComplexDouble(sinh(re) * cos(im), cosh(re) * sin(im))
    fun cosh() = ComplexDouble(cosh(re) * cos(im), sinh(re) * sin(im))

    /**
     * a + b·i, with floating point noise removed: a part that is negligible next to the
     * other one is zero, e.g. e^(iπ) = −1 rather than −1 + 1.2×10⁻¹⁶ i.
     */
    fun toExpression(context: MathContext): Expression {
        val scale = max(abs(re), abs(im))
        fun clean(x: Double) = if (abs(x) <= NOISE * scale) 0.0 else x
        // 0.9999999999999999 is 1 (tanh(iπ/4) = i, not 1i with a decimal 1)
        fun part(x: Double): Value {
            val c = clean(x)
            val whole = Math.rint(c)
            return if (abs(c - whole) <= NOISE * max(1.0, abs(c)) && abs(whole) < 1e15) Value(whole.toLong()) else Value(c)
        }
        return complexOf(part(re), part(im), context)
    }

    private companion object {
        const val NOISE = 1e-14
    }
}

/** The value of [e] if it is a constant (complex) number, e.g. for ln(1 + i); otherwise null. */
internal fun numericComplex(e: Expression): ComplexDouble? {
    val (re, im) = complexParts(e) ?: return null
    if (re.variables().isNotEmpty() || im.variables().isNotEmpty()) return null
    val z = ComplexDouble(re.evaluateNumerically(), im.evaluateNumerically())
    return z.takeIf { it.isFinite }
}

/**
 * The modulus r and argument θ of the constant complex number [z] (with z = r·e^(iθ)), both
 * exact: θ in (−π, π] is a fraction of π where it is one (π/4 for 1 + i), otherwise an
 * arctangent with the quadrant corrected (atan(4/3) for 3 + 4i). Null if [z] is not a constant
 * complex number with a nonzero imaginary part.
 */
internal fun polarForm(z: Expression, context: MathContext): Pair<Expression, Expression>? {
    val (re, im) = complexParts(z) ?: return null
    val value = numericComplex(z) ?: return null
    if (value.im == 0.0) return null
    val modulus = AbsoluteValue(z).canonicalize(context)
    val angle = value.arg
    val twelfths = angle / (kotlin.math.PI / 12.0)
    val k = kotlin.math.round(twelfths)
    val argument = if (abs(twelfths - k) < 1e-9) {
        Multiplications.of(Value(k.toLong(), 12L), Constant.PI)
    } else {
        // atan(b/a) is in (−π/2, π/2); left of the imaginary axis the angle is π − atan|b/a|
        // or atan|b/a| − π
        fun atan(x: Expression) = TrigonometricFunction(TrigType.ATAN, x, AngleUnit.RADIANS)
        val ratio = Multiplications.of(im, Power(re, Value.MINUS_ONE))
        val magnitude = if (value.im / value.re < 0.0) Multiplications.of(Value.MINUS_ONE, ratio) else ratio
        when {
            value.re > 0.0 -> atan(ratio)
            value.im > 0.0 -> Additions(listOf(Constant.PI, Multiplications.of(Value.MINUS_ONE, atan(magnitude))))
            else -> Additions(listOf(atan(magnitude), Multiplications.of(Value.MINUS_ONE, Constant.PI)))
        }
    }.canonicalize(context)
    if (context.shouldStop) return null
    return modulus to argument
}
