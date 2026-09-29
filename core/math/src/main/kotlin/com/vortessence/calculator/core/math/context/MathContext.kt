package com.vortessence.calculator.core.math.context

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Execution context for math evaluation, tracking issues, mode, and cancellation.
 */
class MathContext(
    var mode: CalculationMode = CalculationMode.EXACT,
    var angleUnit: AngleUnit = AngleUnit.RADIANS,
    var maxPrecision: Int = 30,
    var unitSystem: UnitSystem = UnitSystem.METRIC,
    /** Show temperatures in kelvin (the SI unit) instead of °C/°F. */
    var temperatureInKelvin: Boolean = false
) {
    private val _issues = mutableListOf<MathIssue>()
    val issues: List<MathIssue> get() = _issues

    private val isCancelled = AtomicBoolean(false)
    var deadlineMillis: Long = Long.MAX_VALUE

    /** Extra stop condition, e.g. "the calling coroutine was cancelled". */
    var externalCancellation: (() -> Boolean)? = null

    fun stopWithError(issue: MathIssue) {
        if (!_issues.contains(issue)) {
            _issues.add(issue)
        }
        isCancelled.set(true)
    }

    fun reportIssue(issue: MathIssue) {
        if (!_issues.contains(issue)) {
            _issues.add(issue)
        }
    }

    fun cancel() {
        isCancelled.set(true)
    }

    val shouldStop: Boolean
        get() = isCancelled.get() || isTimedOut || externalCancellation?.invoke() == true

    val isTimedOut: Boolean
        get() = System.currentTimeMillis() > deadlineMillis

    fun reset() {
        _issues.clear()
        isCancelled.set(false)
        deadlineMillis = Long.MAX_VALUE
    }

    fun copy(): MathContext {
        val copy = MathContext(mode, angleUnit, maxPrecision, unitSystem, temperatureInKelvin)
        copy._issues.addAll(_issues)
        copy.isCancelled.set(isCancelled.get())
        copy.deadlineMillis = deadlineMillis
        copy.externalCancellation = externalCancellation
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
