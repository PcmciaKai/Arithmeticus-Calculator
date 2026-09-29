package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.solvers.NumericRootFinder
import com.vortessence.calculator.core.math.type.MathParser
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.MathWriter
import com.vortessence.calculator.core.math.type.TextNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * @property result What to display, e.g. "x = 2, x = 3".
 * @property answer The reusable value of the result (for "Ans" or tapping the result), or
 * `null` if there is none, e.g. for failed calculations or "true"/"false".
 */
data class CalculationResult(
    val input: MathString,
    val result: MathString,
    val expression: Expression,
    val numericValue: Double,
    val issues: List<MathIssue>,
    val isSuccess: Boolean = issues.isEmpty(),
    val answer: MathString? = if (isSuccess) result else null
)

data class PlotPoint(val x: Double, val y: Double)

data class PlotData(
    val roots: List<Double>,
    val extrema: List<PlotPoint>
)

object MathEngine {
    const val DEFAULT_TIMEOUT_MILLIS = 5_000L

    private val parser = MathParser()
    private val writer = MathWriter()

    suspend fun calculate(
        input: MathString,
        context: MathContext = MathContext(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): CalculationResult = withContext(Dispatchers.Default) {
        val execContext = context.copy()
        execContext.deadlineMillis = System.currentTimeMillis() + timeoutMillis
        // The math code is not suspending, so let it poll for coroutine cancellation
        val job = coroutineContext[Job]
        execContext.externalCancellation = { job?.isActive == false }

        val result = try {
            MathContext.withContext(execContext) { evaluate(input, execContext) }
        } catch (e: ArithmeticException) {
            failure(input, execContext, MathIssue.UNDEFINED)
        } catch (e: IllegalArgumentException) {
            failure(input, execContext, MathIssue.UNDEFINED)
        } catch (e: StackOverflowError) {
            failure(input, execContext, MathIssue.VALUES_TOO_LARGE)
        } catch (e: OutOfMemoryError) {
            failure(input, execContext, MathIssue.VALUES_TOO_LARGE)
        }
        ensureActive() // a cancelled calculation must not deliver a (partial) result
        result
    }

    private fun evaluate(input: MathString, ctx: MathContext): CalculationResult {
        val parsed = parser.parse(input)
        if (ctx.shouldStop) return failure(input, ctx, expression = parsed)

        if (parsed is Equals) return evaluateEquation(input, parsed, ctx)

        val simplified = parsed.canonicalize(ctx)
        if (ctx.shouldStop) return failure(input, ctx, expression = parsed)

        val numVal = simplified.evaluateNumerically()
        if (simplified is Value && !numVal.isFinite()) {
            val issue = if (numVal.isNaN()) MathIssue.UNDEFINED else MathIssue.VALUES_TOO_LARGE
            return failure(input, ctx, issue, simplified)
        }
        return CalculationResult(
            input = input,
            result = writer.write(simplified),
            expression = simplified,
            numericValue = numVal,
            issues = ctx.issues
        )
    }

    private fun evaluateEquation(input: MathString, equation: Equals, ctx: MathContext): CalculationResult {
        val vars = equation.variables()
        if (vars.isEmpty()) {
            val truth = equation.truthValue(ctx) ?: return failure(input, ctx, MathIssue.UNDEFINED, equation)
            return truthResult(input, equation, truth, ctx)
        }

        val targetVar = pickVariable(vars)
        val solutions = equation.solveForVariable(targetVar, ctx)
        if (ctx.shouldStop || solutions.isEmpty()) {
            return failure(input, ctx, MathIssue.CANT_SOLVE_GENERIC, equation)
        }
        if (solutions == setOf(targetVar)) {
            // Identity such as x + 1 = 1 + x: true for every value
            return truthResult(input, equation, true, ctx)
        }

        val combinedResult = MathString()
        for ((idx, sol) in solutions.withIndex()) {
            if (idx > 0) combinedResult.insert(combinedResult.length, TextNode(", "))
            combinedResult.insertAll(combinedResult.length, writer.write(Equals(targetVar, sol)).nodes)
        }
        return CalculationResult(
            input = input,
            result = combinedResult,
            expression = equation,
            numericValue = solutions.first().evaluateNumerically(),
            issues = ctx.issues,
            answer = writer.write(solutions.first())
        )
    }

    private fun truthResult(input: MathString, equation: Equals, truth: Boolean, ctx: MathContext) =
        CalculationResult(
            input = input,
            result = MathString(listOf(TextNode(if (truth) "true" else "false"))),
            expression = equation,
            numericValue = Double.NaN,
            issues = ctx.issues,
            answer = null
        )

    /** Solve for x, y, z first (in that order), otherwise for the alphabetically first variable. */
    private fun pickVariable(vars: Set<Variable>): Variable {
        return PREFERRED_VARIABLES.firstOrNull { it in vars } ?: vars.minBy { it.identifier }
    }

    private fun failure(
        input: MathString,
        ctx: MathContext,
        fallbackIssue: MathIssue = MathIssue.UNDEFINED,
        expression: Expression = Value.ZERO
    ): CalculationResult {
        val issues = when {
            ctx.issues.isNotEmpty() -> ctx.issues
            ctx.isTimedOut -> listOf(MathIssue.TIMEOUT)
            else -> listOf(fallbackIssue)
        }
        return CalculationResult(
            input = input,
            result = MathString(),
            expression = expression,
            numericValue = Double.NaN,
            issues = issues.toList()
        )
    }

    suspend fun solve(
        input: MathString,
        variable: Variable = Variable("x"),
        context: MathContext = MathContext()
    ): List<MathString> = withContext(Dispatchers.Default) {
        val execContext = context.copy()
        execContext.deadlineMillis = System.currentTimeMillis() + DEFAULT_TIMEOUT_MILLIS
        try {
            MathContext.withContext(execContext) {
                val parsed = parser.parse(input)
                if (parsed is Equals && !execContext.shouldStop) {
                    val solutions = parsed.solveForVariable(variable, execContext)
                    solutions.map { writer.write(it) }
                } else {
                    emptyList()
                }
            }
        } catch (e: ArithmeticException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /**
     * Builds the function to plot for [input], using the angle unit and mode of [context].
     * Returns null if the input is invalid or has no single variable to plot against.
     */
    suspend fun createPlotFunction(
        input: MathString,
        context: MathContext = MathContext()
    ): Function1D? = withContext(Dispatchers.Default) {
        val execContext = context.copy()
        execContext.deadlineMillis = System.currentTimeMillis() + DEFAULT_TIMEOUT_MILLIS
        try {
            MathContext.withContext(execContext) {
                val parsed = parser.parse(input)
                if (execContext.shouldStop) null else Function1D.fromExpression(parsed, execContext)
            }
        } catch (e: ArithmeticException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Roots and extrema of [function] in [minX, maxX], e.g. for the visible graph area. */
    suspend fun analyzePlot(
        function: Function1D,
        minX: Double,
        maxX: Double,
        steps: Int = 400
    ): PlotData = withContext(Dispatchers.Default) {
        val context = MathContext()
        val job = coroutineContext[Job]
        context.externalCancellation = { job?.isActive == false }
        val roots = NumericRootFinder.findRoots(function, minX, maxX, steps, context)
        val extrema = NumericRootFinder.findExtrema(function, minX, maxX, steps, context)
            .map { PlotPoint(it, function.evaluateAt(it)) }
        ensureActive()
        PlotData(roots, extrema)
    }

    private val PREFERRED_VARIABLES = listOf(Variable("x"), Variable("y"), Variable("z"))
}
