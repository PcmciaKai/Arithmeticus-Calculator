package com.vortessence.arithmeticus.core.math.expressions

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.numbers.Rational
import kotlin.math.abs
import kotlin.math.max

/**
 * An exact form of [x], a value computed in floating point (e.g. an infinite sum or a limit),
 * if it is within [tolerance] (relative) of a simple fraction, or of a simple fraction of π, π²,
 * π⁴, e, ln 2, √2 or √3: Σ 1/n² gives π²/6 rather than 1.644934067. Null otherwise.
 */
internal fun recognizeExact(x: Double, tolerance: Double, context: MathContext): Expression? {
    if (!x.isFinite()) return null
    val allowed = tolerance * max(1.0, abs(x))
    if (abs(x) <= allowed) return Value.ZERO
    for ((constant, value) in candidates) {
        val ratio = x / value
        val r = Rational.approximateFromDouble(ratio, MAX_DENOMINATOR) ?: continue
        if (r.isZero || r.numerator.abs().toDouble() > MAX_NUMERATOR) continue
        if (abs(r.toDouble() * value - x) > allowed) continue
        return if (constant == null) Value(r) else Multiplications.of(Value(r), constant).canonicalize(context)
    }
    return null
}

private const val MAX_DENOMINATOR = 100L
private const val MAX_NUMERATOR = 100_000.0

/** (constant, its value); null stands for 1, i.e. a plain fraction. */
private val candidates: List<Pair<Expression?, Double>> by lazy {
    listOf(
        null to 1.0,
        Constant.PI to Math.PI,
        Power(Constant.PI, Value.TWO) to Math.PI * Math.PI,
        Power(Constant.PI, Value(4L)) to Math.PI * Math.PI * Math.PI * Math.PI,
        Constant.E to Math.E,
        Logarithm.ln(Value.TWO) to kotlin.math.ln(2.0),
        Power(Value.TWO, Value.HALF) to kotlin.math.sqrt(2.0),
        Power(Value(3L), Value.HALF) to kotlin.math.sqrt(3.0)
    )
}
