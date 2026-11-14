package com.vortessence.arithmeticus.core.math.context

import com.vortessence.arithmeticus.core.math.i18n.Language
import com.vortessence.arithmeticus.core.math.type.MathString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Execution context for math evaluation, tracking issues, mode, and cancellation.
 */
class MathContext(
    var mode: CalculationMode = CalculationMode.EXACT,
    var angleUnit: AngleUnit = AngleUnit.RADIANS,
    var maxPrecision: Int = 30,
    var unitSystem: UnitSystem = UnitSystem.METRIC,
    /** Decimal places for displayed decimals; `null` = automatic (see MathWriter). */
    var decimalPlaces: Int? = null,
    /** The value "Ans" stands for, or null if there is no previous result. */
    var ans: MathString? = null,
    /** Values of the variables defined with ":=", by name. */
    var variables: Map<String, MathString> = emptyMap(),
    /** Whether i and complex results (√−1 = i, ln(−1) = iπ) are allowed. */
    var complexNumbers: Boolean = false,
    /** The language of the texts the calculation produces: error messages, notes in the steps. */
    var language: Language = Language.ENGLISH,
    /** The seed of the line: its random numbers (rand(), randint(a, b), ...) are a function of it, so they stay the same as long as the seed does. */
    var randomSeed: Long = 0L,
    /**
     * Temperatures in °C and °F as points on their scale: they stay in their scale, a step may be added to them, and two give a
     * difference; adding or multiplying them is an error. Otherwise (the default) they are taken in kelvin for any arithmetic.
     */
    var physicalTemperatures: Boolean = false
) {
    private val _issues = mutableListOf<MathIssue>()
    val issues: List<MathIssue> get() = _issues

    private val isCancelled = AtomicBoolean(false)
    var deadlineMillis: Long = Long.MAX_VALUE

    /**
     * Set where [MathIssue.NO_REAL_SOLUTION] of an equation only says that the search by numbers found none, which is no proof
     * that there is none (the search does not look everywhere).
     */
    var solutionNotFound: Boolean = false

    /** Set where the solutions of an equation that were found are not all there are: tan(x) = x has more beyond the ones listed. */
    var moreSolutions: Boolean = false

    /** Extra stop condition, e.g. "the calling coroutine was cancelled". */
    var externalCancellation: (() -> Boolean)? = null

    fun stopWithError(issue: MathIssue) {
        if (!_issues.contains(issue)) {
            _issues.add(issue)
        }
        isCancelled.set(true)
    }

    fun cancel() {
        isCancelled.set(true)
    }

    val shouldStop: Boolean
        get() = isCancelled.get() || isTimedOut || externalCancellation?.invoke() == true

    val isTimedOut: Boolean
        get() = System.currentTimeMillis() > deadlineMillis

    fun copy(): MathContext {
        val copy = MathContext(mode, angleUnit, maxPrecision, unitSystem, decimalPlaces, ans, variables, complexNumbers, language, randomSeed, physicalTemperatures)
        copy._issues.addAll(_issues)
        copy.isCancelled.set(isCancelled.get())
        copy.deadlineMillis = deadlineMillis
        copy.externalCancellation = externalCancellation
        copy.solutionNotFound = solutionNotFound
        copy.moreSolutions = moreSolutions
        return copy
    }

    companion object {
        private val currentContext = ThreadLocal<MathContext>()

        /**
         * Returns the context installed via [withContext], or a fresh default context.
         * The fallback is intentionally not cached: a cached per-thread context would keep
         * its issues and cancelled flag forever and poison later calculations on that thread.
         */
        fun current(): MathContext {
            return currentContext.get() ?: MathContext()
        }

        fun <T> withContext(context: MathContext, block: () -> T): T {
            val previous = currentContext.get()
            currentContext.set(context)
            return try {
                block()
            } finally {
                currentContext.set(previous)
            }
        }
    }
}
