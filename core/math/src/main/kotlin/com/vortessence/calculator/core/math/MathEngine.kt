package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.Antiderivative
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.EquationSystem
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Limit
import com.vortessence.calculator.core.math.expressions.Series
import com.vortessence.calculator.core.math.expressions.containsImaginaryUnit
import com.vortessence.calculator.core.math.expressions.numericComplex
import com.vortessence.calculator.core.math.expressions.polarForm
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.UnitConversion
import com.vortessence.calculator.core.math.expressions.Dimension
import com.vortessence.calculator.core.math.expressions.dimensionOf
import com.vortessence.calculator.core.math.expressions.substituteVariables
import com.vortessence.calculator.core.math.expressions.tidyForDisplay
import com.vortessence.calculator.core.math.expressions.toPreferredUnits
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.solvers.ImplicitCurve
import com.vortessence.calculator.core.math.solvers.ImplicitPlotter
import com.vortessence.calculator.core.math.solvers.LinearSystemSolver
import com.vortessence.calculator.core.math.solvers.NumericIntegrator
import com.vortessence.calculator.core.math.solvers.NumericRootFinder
import com.vortessence.calculator.core.math.solvers.PlotCurve
import com.vortessence.calculator.core.math.type.MathOp
import com.vortessence.calculator.core.math.type.MathParser
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.MathWriter
import com.vortessence.calculator.core.math.type.OperatorNode
import com.vortessence.calculator.core.math.type.TextNode
import com.vortessence.calculator.core.math.type.VariableNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * @property result What to display, e.g. "x = 2, x = 3", with numbers formatted for display.
 * @property answer The reusable, unrounded value of the result (for "Ans" or tapping the
 * result), or `null` if there is none, e.g. for failed calculations or "true"/"false".
 * @property unit The unit of the result (e.g. "m/s²"), or null if it has none. [numericValue]
 * is the magnitude in this unit.
 */
data class CalculationResult(
    val input: MathString,
    val result: MathString,
    val expression: Expression,
    val numericValue: Double,
    val issues: List<MathIssue>,
    val isSuccess: Boolean = issues.isEmpty(),
    val answer: MathString? = if (isSuccess) result else null,
    val unit: MathString? = null,
    /** The decimal places setting [result] was written with (null = automatic). */
    val decimalPlaces: Int? = null,
    /** For a definition such as "a := 2x²" the defined variable ("a"); its value is [answer]. */
    val definedVariable: String? = null,
    /** The imaginary part of a complex result; [numericValue] is then its real part. */
    val numericImaginary: Double = 0.0,
    /** A complex result in polar form, r·e^(iθ), exact; null if the result is not complex. */
    val polar: MathString? = null
)

data class PlotPoint(val x: Double, val y: Double)

/** A piece of an implicit curve between two points on it. */
data class PlotSegment(val start: PlotPoint, val end: PlotPoint)

data class PlotData(
    val roots: List<Double>,
    val maxima: List<PlotPoint>,
    val minima: List<PlotPoint>,
    val inflectionPoints: List<PlotPoint>,
    /** Only for implicit curves, which can cross the y-axis several times. */
    val yIntercepts: List<Double> = emptyList()
) {
    val extrema: List<PlotPoint> get() = maxima + minima
}

object MathEngine {
    const val DEFAULT_TIMEOUT_MILLIS = 5_000L

    private val parser = MathParser()
    private val writer = MathWriter()
    /** Writes reusable answers: exact fractions and all digits, independent of display settings. */
    private val answerWriter = MathWriter(lossless = true)

    /** Writes results for display with the context's number format. */
    private fun displayWriter(ctx: MathContext) = MathWriter(decimalPlaces = ctx.decimalPlaces)

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
        val definition = splitDefinition(input)
        if (definition != null && definition.value.isEmpty) return failure(input, ctx, MathIssue.SYNTAX_ERROR)
        val parsed = parse(definition?.value ?: input, ctx)
        if (ctx.shouldStop) return failure(input, ctx, expression = parsed)

        if (definition != null && (parsed is Equals || parsed is EquationSystem)) {
            // A variable stands for a value, not for an equation
            return failure(input, ctx, MathIssue.SYNTAX_ERROR, parsed)
        }
        if (parsed is Equals) return evaluateEquation(input, parsed, ctx)
        if (parsed is EquationSystem) return evaluateSystem(input, parsed, ctx)

        val canonical = parsed.canonicalize(ctx)
        if (ctx.shouldStop) return failure(input, ctx, expression = parsed)
        // A matrix that did not combine into the result (e.g. a factorial of a matrix)
        if (canonical !is MatrixExpression && canonical.containsExpressionMatching { it is MatrixExpression }) {
            return failure(input, ctx, MathIssue.MATRIX_UNSUPPORTED, canonical)
        }
        // An explicit conversion (5 km → mi) keeps its target unit
        val simplified = if (parsed is UnitConversion) canonical else canonical.toPreferredUnits(ctx)
        if (ctx.shouldStop) return failure(input, ctx, expression = canonical)

        val complex = if (simplified.containsImaginaryUnit()) numericComplex(simplified) else null
        val numVal = complex?.re ?: simplified.evaluateNumerically()
        // ∞ is a result only where it is meant, e.g. for a limit or a divergent sum
        val isMeantToBeInfinite = numVal.isInfinite() && parsed.containsExpressionMatching {
            it is Limit || it is Series || (it is Value && it.real.isInfinite)
        }
        if (simplified is Value && !numVal.isFinite() && !isMeantToBeInfinite) {
            val issue = if (numVal.isNaN()) MathIssue.UNDEFINED else MathIssue.VALUES_TOO_LARGE
            return failure(input, ctx, issue, simplified)
        }
        val display = displayWriter(ctx)
        val issues = ctx.issues
        // Symbolic results are shown in a compact form; numbers and quantities as they are
        val compact = if (simplified.variables().isNotEmpty() && simplified.partWithUnit() == null) tidyForDisplay(simplified, ctx) else simplified
        // A definition shows what the variable now stands for: "a = 2x²"
        val shown = if (definition != null) Equals(Variable(definition.name), compact) else compact
        val written = display.write(shown)
        // An indefinite integral is only determined up to a constant
        if (hasIntegrationConstant(parsed)) {
            written.insert(written.length, TextNode(" + C"))
        }
        return CalculationResult(
            input = input,
            result = written,
            expression = simplified,
            numericValue = numVal,
            issues = issues,
            // Reused (Ans, tapping the result) in the form that is shown; the value is the same
            answer = if (issues.isEmpty()) answerWriter.write(compact) else null,
            unit = simplified.partWithUnit()?.let { display.writeUnit(it) },
            decimalPlaces = ctx.decimalPlaces,
            definedVariable = definition?.name,
            numericImaginary = complex?.im ?: 0.0,
            polar = if (complex != null && definition == null) polarResult(simplified, ctx) else null
        )
    }

    /**
     * [z] written as r·e^(iθ), e.g. √2·e^(iπ/4) for 1 + i; null if it has no polar form. Computed
     * in a scratch context, so that a problem with it does not affect the result itself.
     */
    private fun polarResult(z: Expression, ctx: MathContext): MathString? {
        val scratch = ctx.copy()
        val (modulus, argument) = polarForm(z, scratch) ?: return null
        if (scratch.shouldStop) return null
        return displayWriter(ctx).writePolar(modulus, argument)
    }

    /** Whether [e] contains an indefinite integral whose constant is not differentiated away. */
    private fun hasIntegrationConstant(e: Expression): Boolean = when (e) {
        is Antiderivative -> true
        is Derivative -> e.point?.let { hasIntegrationConstant(it) } ?: false
        else -> e.children.any { hasIntegrationConstant(it) }
    }

    /** "a := 2x²": the variable [name] gets the [value] on the right. */
    private class Definition(val name: String, val value: MathString)

    /** The definition in [input], or null if it is none (a misplaced ":=" is left to the parser). */
    private fun splitDefinition(input: MathString): Definition? {
        val nodes = input.nodes
        if (nodes.size < 2 || (nodes[1] as? OperatorNode)?.op != MathOp.DEFINE) return null
        val name = (nodes[0] as? VariableNode)?.name ?: return null
        return Definition(name, MathString(nodes.subList(2, nodes.size)))
    }

    /** Parses [input] and replaces the variables defined in [ctx] by their values. */
    private fun parse(input: MathString, ctx: MathContext): Expression {
        val parsed = parser.parse(input)
        if (ctx.variables.isEmpty() || ctx.shouldStop) return parsed
        val values = ctx.variables.entries.associate { (name, value) -> Variable(name) to parser.parse(value) }
        return parsed.substituteVariables(values)
    }

    private fun evaluateEquation(input: MathString, equation: Equals, ctx: MathContext): CalculationResult {
        if (equation.containsExpressionMatching { it is MatrixExpression }) {
            return failure(input, ctx, MathIssue.MATRIX_UNSUPPORTED, equation)
        }
        val vars = equation.variables()
        if (vars.isEmpty()) {
            val truth = equation.truthValue(ctx) ?: return failure(input, ctx, MathIssue.UNDEFINED, equation)
            return truthResult(input, equation, truth, ctx)
        }

        val targetVar = pickVariable(vars)
        val solutions = equation.solveForVariable(targetVar, ctx)
            .map { if (it == targetVar) it else it.toPreferredUnits(ctx) }
            .toCollection(LinkedHashSet())
        if (ctx.shouldStop || solutions.isEmpty()) {
            return failure(input, ctx, MathIssue.CANT_SOLVE_GENERIC, equation)
        }
        if (solutions == setOf(targetVar)) {
            // Identity such as x + 1 = 1 + x: true for every value
            return truthResult(input, equation, true, ctx)
        }

        val display = displayWriter(ctx)
        val combinedResult = MathString()
        for ((idx, sol) in solutions.withIndex()) {
            if (idx > 0) combinedResult.insert(combinedResult.length, TextNode(", "))
            combinedResult.insertAll(combinedResult.length, display.write(Equals(targetVar, sol)).nodes)
        }
        return CalculationResult(
            input = input,
            result = combinedResult,
            expression = equation,
            numericValue = solutions.first().evaluateNumerically(),
            issues = ctx.issues,
            answer = answerWriter.write(solutions.first()),
            unit = solutions.first().partWithUnit()?.let { display.writeUnit(it) },
            decimalPlaces = ctx.decimalPlaces
        )
    }

    /**
     * A system of equations, one per line, e.g. −2x + y = −3 and 3x + 2y = 8 gives
     * "x = 2, y = 1". Only linear systems are solved.
     */
    private fun evaluateSystem(input: MathString, system: EquationSystem, ctx: MathContext): CalculationResult {
        // A line without "=" is still being typed
        val equations = system.lines.map { it as? Equals ?: return failure(input, ctx, MathIssue.SYNTAX_ERROR, system) }
        if (system.containsExpressionMatching { it is MatrixExpression }) {
            return failure(input, ctx, MathIssue.MATRIX_UNSUPPORTED, system)
        }
        val vars = system.variables()
        if (vars.isEmpty()) {
            val truths = equations.map { it.truthValue(ctx) ?: return failure(input, ctx, MathIssue.UNDEFINED, system) }
            return truthResult(input, system, truths.all { it }, ctx)
        }

        // x, y, z first, then alphabetically, which is also the order of the solutions
        val orderedVars = PREFERRED_VARIABLES.filter { it in vars } +
            (vars - PREFERRED_VARIABLES.toSet()).sortedBy { it.identifier }
        val solution = LinearSystemSolver.solve(equations, orderedVars, ctx)
            ?: return failure(input, ctx, MathIssue.CANT_SOLVE_GENERIC, system)
        if (ctx.shouldStop) return failure(input, ctx, MathIssue.CANT_SOLVE_GENERIC, system)
        if (solution.isEmpty()) {
            // Every line is an identity such as x + y = y + x
            return truthResult(input, system, true, ctx)
        }

        val solved = orderedVars.filter { it in solution }.map { it to solution.getValue(it).toPreferredUnits(ctx) }
        val display = displayWriter(ctx)
        val combinedResult = MathString()
        for ((idx, pair) in solved.withIndex()) {
            if (idx > 0) combinedResult.insert(combinedResult.length, TextNode(", "))
            combinedResult.insertAll(combinedResult.length, display.write(Equals(pair.first, pair.second)).nodes)
        }
        val first = solved.first().second
        return CalculationResult(
            input = input,
            result = combinedResult,
            expression = system,
            numericValue = Double.NaN,
            issues = ctx.issues,
            answer = answerWriter.write(first),
            decimalPlaces = ctx.decimalPlaces
        )
    }

    private fun truthResult(input: MathString, equation: Expression, truth: Boolean, ctx: MathContext) =
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
                val parsed = parse(input, execContext)
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
    suspend fun createPlotCurve(
        input: MathString,
        context: MathContext = MathContext()
    ): PlotCurve? = withContext(Dispatchers.Default) {
        val execContext = context.copy()
        execContext.deadlineMillis = System.currentTimeMillis() + DEFAULT_TIMEOUT_MILLIS
        try {
            MathContext.withContext(execContext) {
                val parsed = parse(input, execContext)
                if (execContext.shouldStop) null else PlotCurve.fromExpression(parsed, execContext)
            }
        } catch (e: ArithmeticException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * The physical dimension of [input], e.g. volume for "2 m³" or for an Ans that has a volume,
     * or null if it has no (consistent) unit. Only parses, so it is fast enough to call directly.
     */
    fun unitDimension(input: MathString, context: MathContext = MathContext()): Dimension? {
        val execContext = context.copy()
        return try {
            MathContext.withContext(execContext) {
                val parsed = parse(input, execContext)
                if (execContext.shouldStop) null else dimensionOf(parsed)?.takeUnless { it.isNone }
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
        // findExtrema only returns true extrema, so one neighbour tells maximum from minimum
        val probe = (maxX - minX) / steps / 4.0
        val (maxima, minima) = extrema.partition { function.evaluateAt(it.x - probe) <= it.y }
        val inflectionPoints = NumericRootFinder.findInflectionPoints(function, minX, maxX, steps, context)
            .map { PlotPoint(it, function.evaluateAt(it)) }
        ensureActive()
        PlotData(roots, maxima, minima, inflectionPoints)
    }

    /**
     * Line segments approximating [curve] in the given range, on a [cols] × [rows] grid that is
     * spaced logarithmically along an axis with [logX] / [logY].
     */
    suspend fun contourImplicit(
        curve: ImplicitCurve,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        cols: Int,
        rows: Int,
        logX: Boolean = false,
        logY: Boolean = false
    ): List<PlotSegment> = withContext(Dispatchers.Default) {
        val context = MathContext()
        val job = coroutineContext[Job]
        context.externalCancellation = { job?.isActive == false }
        val segments = ImplicitPlotter.contour(curve, minX, maxX, minY, maxY, cols, rows, context, logX, logY)
        ensureActive()
        segments
    }

    /**
     * Axis intercepts and highest/lowest points (horizontal tangents) of an implicit curve,
     * using [segments] from [contourImplicit]. Inflection points are not looked for.
     */
    suspend fun analyzeImplicitPlot(
        curve: ImplicitCurve,
        segments: List<PlotSegment>,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double
    ): PlotData = withContext(Dispatchers.Default) {
        val context = MathContext()
        val job = coroutineContext[Job]
        context.externalCancellation = { job?.isActive == false }
        val xIntercepts = ImplicitPlotter.xIntercepts(curve, minX, maxX, context)
        val yIntercepts = ImplicitPlotter.yIntercepts(curve, minY, maxY, context)
        val (maxima, minima) = ImplicitPlotter.horizontalTangents(curve, segments, context)
        ensureActive()
        PlotData(xIntercepts, maxima, minima, emptyList(), yIntercepts)
    }

    /** Signed area under [function] from [a] to [b]; NaN if it is undefined there or diverges. */
    suspend fun integratePlot(function: Function1D, a: Double, b: Double): Double = withContext(Dispatchers.Default) {
        val context = MathContext()
        val job = coroutineContext[Job]
        context.externalCancellation = { job?.isActive == false }
        val area = NumericIntegrator.integrate(function, a, b, context)
        ensureActive()
        area
    }

    private val PREFERRED_VARIABLES = listOf(Variable("x"), Variable("y"), Variable("z"))
}
