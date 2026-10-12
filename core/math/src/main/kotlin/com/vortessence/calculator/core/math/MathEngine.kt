package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.i18n.Text
import com.vortessence.calculator.core.math.i18n.Texts
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Antiderivative
import com.vortessence.calculator.core.math.expressions.Integral
import com.vortessence.calculator.core.math.expressions.Derivative
import com.vortessence.calculator.core.math.expressions.Constant
import com.vortessence.calculator.core.math.expressions.EquationSystem
import com.vortessence.calculator.core.math.expressions.Equals
import com.vortessence.calculator.core.math.expressions.Inequality
import com.vortessence.calculator.core.math.expressions.InequalityAnalysis
import com.vortessence.calculator.core.math.expressions.InequalitySolver
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Limit
import com.vortessence.calculator.core.math.expressions.Series
import com.vortessence.calculator.core.math.expressions.containsImaginaryUnit
import com.vortessence.calculator.core.math.expressions.numericComplex
import com.vortessence.calculator.core.math.expressions.polarForm
import com.vortessence.calculator.core.math.expressions.EigenSystem
import com.vortessence.calculator.core.math.expressions.MatrixExpression
import com.vortessence.calculator.core.math.expressions.Percent
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.InequalitySystemSolver
import com.vortessence.calculator.core.math.expressions.TrigInequality
import com.vortessence.calculator.core.math.expressions.TrigInequalitySolver
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.UnitConversion
import com.vortessence.calculator.core.math.expressions.Dimension
import com.vortessence.calculator.core.math.expressions.dimensionOf
import com.vortessence.calculator.core.math.expressions.substituteVariables
import com.vortessence.calculator.core.math.expressions.toPreferredUnits
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.solvers.ImplicitCurve
import com.vortessence.calculator.core.math.solvers.ImplicitPlotter
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.solvers.LinearSystemSolver
import com.vortessence.calculator.core.math.solvers.NonlinearSystemSolver
import com.vortessence.calculator.core.math.solvers.NumericIntegrator
import com.vortessence.calculator.core.math.solvers.NumericRootFinder
import com.vortessence.calculator.core.math.solvers.PlotCurve
import com.vortessence.calculator.core.math.steps.ArithmeticSteps
import com.vortessence.calculator.core.math.steps.BothSides
import com.vortessence.calculator.core.math.steps.DerivativeSteps
import com.vortessence.calculator.core.math.steps.EquationSteps
import com.vortessence.calculator.core.math.steps.InequalitySteps
import com.vortessence.calculator.core.math.steps.MatrixSteps
import com.vortessence.calculator.core.math.steps.TextExpression
import com.vortessence.calculator.core.math.steps.Alternatives
import com.vortessence.calculator.core.math.steps.AbsoluteSplitSteps
import com.vortessence.calculator.core.math.steps.Transform
import com.vortessence.calculator.core.math.steps.Transforms
import com.vortessence.calculator.core.math.steps.SystemSteps
import com.vortessence.calculator.core.math.steps.ExpressionSteps
import com.vortessence.calculator.core.math.steps.IntegralSteps
import com.vortessence.calculator.core.math.steps.LimitSteps
import com.vortessence.calculator.core.math.steps.Note
import com.vortessence.calculator.core.math.steps.displayForm
import com.vortessence.calculator.core.math.steps.NumberLine
import com.vortessence.calculator.core.math.steps.NumberLinePiece
import com.vortessence.calculator.core.math.steps.NumberLinePoint
import com.vortessence.calculator.core.math.steps.Solution
import kotlin.math.abs
import com.vortessence.calculator.core.math.steps.SolutionStep
import com.vortessence.calculator.core.math.steps.StepChange
import com.vortessence.calculator.core.math.type.DerivativeNode
import com.vortessence.calculator.core.math.type.LineBreakNode
import com.vortessence.calculator.core.math.type.MathNode
import com.vortessence.calculator.core.math.type.MathOp
import com.vortessence.calculator.core.math.type.MathParser
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.MathWriter
import com.vortessence.calculator.core.math.type.isInequality
import com.vortessence.calculator.core.math.type.isPostfix
import com.vortessence.calculator.core.math.type.NumberNode
import com.vortessence.calculator.core.math.type.OperatorNode
import com.vortessence.calculator.core.math.type.ParenthesesNode
import com.vortessence.calculator.core.math.type.TextNode
import com.vortessence.calculator.core.math.type.VariableNode
import kotlinx.coroutines.CancellationException
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
    /** What went wrong said for this input, e.g. "m is a matrix. A matrix and a number cannot be added"; null if the issue's own message says it. */
    val issueNote: String? = null,
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
    private fun answerWriter(ctx: MathContext) = MathWriter(lossless = true, degrees = ctx.angleUnit == AngleUnit.DEGREES)

    /** Writes results for display with the context's number format. */
    private fun displayWriter(ctx: MathContext) = MathWriter(decimalPlaces = ctx.decimalPlaces, degrees = ctx.angleUnit == AngleUnit.DEGREES)

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
        if (result.isSuccess) result else result.copy(issueNote = matrixNote(input, result, execContext))
    }

    private val MATRIX_ISSUES = setOf(MathIssue.MATRIX_UNSUPPORTED, MathIssue.MATRIX_AND_SCALAR, MathIssue.DIMENSION_MISMATCH, MathIssue.MATRIX_NOT_SQUARE, MathIssue.SINGULAR_MATRIX, MathIssue.MATRIX_EXPECTED, MathIssue.MATRIX_NEEDS_NUMBERS, MathIssue.EIGEN_ALONE)

    /** "m is a matrix. Not defined for matrices": says which of the variables used is a matrix, if the problem is with matrices. */
    private fun matrixNote(input: MathString, result: CalculationResult, ctx: MathContext): String? {
        val issue = result.issues.firstOrNull()?.takeIf { it in MATRIX_ISSUES } ?: return null
        if (ctx.variables.isEmpty()) return null
        val used = try { parser.parse(input).variables().map { it.identifier }.toSet() } catch (e: RuntimeException) { return null }
        val names = ctx.variables.filter { (name, value) -> name in used && try { parser.parse(value) is MatrixExpression } catch (e: RuntimeException) { false } }.keys.sorted()
        if (names.isEmpty()) return null
        val subject = if (names.size == 1) Texts.format(Text.MATRIX_IS, names.single(), language = ctx.language)
        else Texts.format(Text.MATRICES_ARE, names.dropLast(1).joinToString(", ") + Texts.get(Text.AND, ctx.language) + names.last(), language = ctx.language)
        return "$subject. ${issue.message(ctx.language)}"
    }

    private fun evaluate(input: MathString, ctx: MathContext): CalculationResult {
        val definition = splitDefinition(input)
        if (definition != null && definition.value.isEmpty) return failure(input, ctx, MathIssue.SYNTAX_ERROR)
        val parsed = parse(definition?.value ?: input, ctx)
        if (ctx.shouldStop) return failure(input, ctx, expression = parsed)

        // A list of eigenvalues and vectors is no value to calculate with, nor to store
        if (parsed.containsExpressionMatching { it is EigenSystem } && (parsed !is EigenSystem || definition != null)) {
            return failure(input, ctx, if (definition != null) MathIssue.SYNTAX_ERROR else MathIssue.EIGEN_ALONE, parsed)
        }
        if (definition != null && (parsed is Equals || parsed is EquationSystem || parsed is Inequality)) {
            // A variable stands for a value, not for an equation
            return failure(input, ctx, MathIssue.SYNTAX_ERROR, parsed)
        }
        if (parsed is Equals) return evaluateEquation(input, parsed, ctx)
        if (parsed is Inequality) return evaluateInequality(input, parsed, ctx)
        if (parsed is EquationSystem) return evaluateSystem(input, parsed, ctx)

        val canonical = parsed.canonicalize(ctx)
        if (ctx.shouldStop) return failure(input, ctx, expression = parsed)
        // A matrix that did not combine into the result (e.g. a factorial of a matrix)
        // (the eigenvectors of eig(A) are matrices in a list of results)
        if (canonical !is MatrixExpression && canonical !is Alternatives && canonical.containsExpressionMatching { it is MatrixExpression }) {
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
        val compact = displayForm(simplified, ctx)
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
            // (a list of eigenvalues and vectors is not reused)
            answer = if (issues.isEmpty() && compact !is Alternatives) answerWriter(ctx).write(compact) else null,
            unit = simplified.partWithUnit()?.let { display.writeUnit(it) },
            decimalPlaces = ctx.decimalPlaces,
            definedVariable = definition?.name,
            numericImaginary = complex?.im ?: 0.0,
            polar = if (complex != null && definition == null) polarResult(simplified, ctx) else null
        )
    }

    /**
     * The steps of calculating [input], ending with the calculated result: for a derivative,
     * e.g. d/dx(x²·sin(x)), the product rule, then the power rule and so on; for an expression
     * with variables, e.g. (x + 1)² − x², the simplifications. Null if [input] is something
     * else, or if there are no steps for it (see [hasSteps]).
     */
    suspend fun steps(
        input: MathString,
        context: MathContext = MathContext(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        /** The steps of writing the expression in this form (multiplied out, factored, as one fraction) instead. */
        transform: Transform? = null
    ): Solution? = withContext(Dispatchers.Default) {
        val execContext = context.copy()
        execContext.deadlineMillis = System.currentTimeMillis() + timeoutMillis
        val job = coroutineContext[Job]
        execContext.externalCancellation = { job?.isActive == false }

        val steps = try {
            MathContext.withContext(execContext) steps@{
                // "a := …" defines a variable, there is nothing to solve
                if (splitDefinition(input) != null) return@steps null
                val asTyped = parse(input, execContext)
                if (execContext.shouldStop) return@steps null
                // The other kinds of steps work on 15·(1/100); only plain arithmetic shows how 15% becomes 15/100
                val parsed = Percent.withoutPercents(asTyped)
                val solution = if (transform != null) Transforms.steps(parsed, transform, execContext) else when (parsed) {
                    is Derivative -> DerivativeSteps.steps(parsed, execContext)
                    is Antiderivative, is Integral -> IntegralSteps.steps(parsed, execContext)
                    is Limit -> LimitSteps.steps(parsed, execContext)
                    is EquationSystem -> SystemSteps.steps(parsed, execContext)
                    is Inequality -> InequalitySteps.steps(parsed, execContext)
                    is Equals -> parsed.variables().takeIf { it.isNotEmpty() }?.let { vars -> pickVariable(vars).let { v -> EquationSteps.steps(parsed, v, execContext) ?: AbsoluteSplitSteps.steps(parsed, v, execContext) } }
                    else -> MatrixSteps.steps(parsed, execContext) ?: ExpressionSteps.steps(parsed, execContext) ?: ArithmeticSteps.steps(asTyped, execContext)
                }
                if (solution == null || execContext.shouldStop || execContext.issues.isNotEmpty()) return@steps null
                val display = displayWriter(execContext)
                // The problem is shown as it was typed: 0.5, not 1/2
                val typed = MathWriter(decimalPlaces = execContext.decimalPlaces, typedDecimals = typedDecimals(input), degrees = execContext.angleUnit == AngleUnit.DEGREES)
                val steps = solution.steps.mapIndexed { index, step ->
                    SolutionStep(
                        (if (index == 0) typed else display).write(step.expression),
                        step.rule,
                        step.chainRule,
                        step.change?.let { (before, after) -> StepChange(display.write(before), display.write(after)) },
                        step.notes.map { writeNote(it, display) },
                        step.operation?.let { writeOperation(it, display) }
                    )
                }
                val result = display.write(solution.result)
                // An indefinite integral is only determined up to a constant
                if (parsed is Antiderivative) {
                    result.insert(result.length, TextNode(" + C"))
                    steps.last().expression.insert(steps.last().expression.length, TextNode(" + C"))
                }
                val analysis = solution.inequality
                Solution(steps, result, analysis?.let { numberLine(it, display) }, analysis?.let { intervalNotation(it, display) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // A rule that does not fit the expression (e.g. a cast or an index) means no steps, not a crash
            null
        } catch (e: StackOverflowError) {
            null
        }
        ensureActive()
        steps
    }

    /** The critical points of an inequality and the pieces of its solution between them, for drawing. */
    private fun numberLine(analysis: InequalityAnalysis, display: MathWriter): NumberLine? {
        if (analysis.critical.isEmpty() && analysis.pieces.isEmpty()) return null
        val points = analysis.critical.map { NumberLinePoint(display.write(it), it.evaluateNumerically()) }
        fun indexOf(bound: Expression?): Int? = bound?.let { b -> analysis.critical.indexOfFirst { it === b || it == b || abs(it.evaluateNumerically() - b.evaluateNumerically()) < 1e-9 }.takeIf { it >= 0 } }
        return NumberLine(points, analysis.pieces.map { NumberLinePiece(indexOf(it.lower), it.lowerClosed, indexOf(it.upper), it.upperClosed) })
    }

    /** (−∞, −2) ∪ (2, ∞), [−1, 3], {2}; ∅ for no solution. */
    private fun intervalNotation(analysis: InequalityAnalysis, display: MathWriter): MathString {
        val result = MathString()
        if (analysis.pieces.isEmpty()) {
            result.insert(result.length, TextNode("∅"))
            return result
        }
        for ((i, piece) in analysis.pieces.withIndex()) {
            if (i > 0) result.insert(result.length, TextNode(" ∪ "))
            if (piece.isPoint) {
                result.insert(result.length, TextNode("{"))
                result.insertAll(result.length, display.write(piece.lower!!).nodes)
                result.insert(result.length, TextNode("}"))
                continue
            }
            result.insert(result.length, TextNode(if (piece.lower != null && piece.lowerClosed) "[" else "("))
            if (piece.lower == null) result.insert(result.length, TextNode("−∞")) else result.insertAll(result.length, display.write(piece.lower).nodes)
            result.insert(result.length, TextNode(", "))
            if (piece.upper == null) result.insert(result.length, TextNode("∞")) else result.insertAll(result.length, display.write(piece.upper).nodes)
            result.insert(result.length, TextNode(if (piece.upper != null && piece.upperClosed) "]" else ")"))
        }
        return result
    }

    /**
     * The forms [input] can be asked to be written in ([Transform]): multiplied out, factored, or as one fraction,
     * where that is not what the calculator gives anyway.
     */
    suspend fun transforms(input: MathString, context: MathContext = MathContext()): List<Transform> {
        // An equation, a number or a definition has none: cheaper than trying each
        if (input.nodes.any { it is OperatorNode && (it.op == MathOp.EQUALS || it.op == MathOp.DEFINE || it.op.isInequality) || it is LineBreakNode }) return emptyList()
        return Transform.values().filter { steps(input, context, transform = it) != null }
    }

    /**
     * For a line that ends in "a + b%" or "a − b%", the line for what a pocket calculator means by
     * it: b% *of a*, written out as "a + a × b%" (here a + b% is a plus b/100: 200 + 10% = 200.1,
     * the alternative is 220). Null for any other line.
     */
    fun percentOfFirstAlternative(input: MathString): PercentAlternative? {
        val nodes = input.nodes
        if (nodes.any { it is LineBreakNode } || nodes.size < 4) return null
        val last = nodes.last()
        if ((last as? OperatorNode)?.op != MathOp.PERCENT) return null
        // The number in front of the %: digits and a decimal point
        var start = nodes.size - 1
        while (start > 0 && nodes[start - 1] is NumberNode) start--
        if (start == nodes.size - 1 || start < 2) return null
        val operator = nodes[start - 1] as? OperatorNode ?: return null
        if (operator.op != MathOp.PLUS && operator.op != MathOp.MINUS) return null
        val left = nodes.subList(0, start - 1)
        // The part in front must be complete: not ending in an operator
        val leftLast = left.last()
        if (leftLast is OperatorNode && !leftLast.op.isPostfix) return null
        val percentNumber = nodes.subList(start, nodes.size - 1).map { it.copy() }
        fun leftCopy(): List<MathNode> {
            val copy = left.map { it.copy() }
            val simple = copy.all { it is NumberNode }
            return if (simple) copy else listOf(ParenthesesNode(MathString(copy)))
        }
        val result = mutableListOf<MathNode>()
        result.addAll(leftCopy())
        result.add(OperatorNode(operator.op))
        result.addAll(leftCopy())
        result.add(OperatorNode(MathOp.MULTIPLY))
        result.addAll(percentNumber)
        result.add(OperatorNode(MathOp.PERCENT))
        return PercentAlternative(
            MathString(result),
            base = MathString(left.map { it.copy() }),
            percent = MathString(percentNumber.map { it.copy() }),
            subtracts = operator.op == MathOp.MINUS
        )
    }

    /** "a + b%" read as "a + a × b%": that line, and the parts for telling about it. */
    class PercentAlternative(val expression: MathString, val base: MathString, val percent: MathString, val subtracts: Boolean)

    /** The decimals typed in [input], e.g. 0.5 as the fraction 1/2 (what they are parsed to). */
    private fun typedDecimals(input: MathString): Set<Rational> {
        val found = mutableSetOf<Rational>()
        fun scan(string: MathString) {
            var run = StringBuilder()
            fun flush() {
                val text = run.toString()
                run = StringBuilder()
                if (!text.contains('.')) return
                val decimal = text.toBigDecimalOrNull() ?: return
                val scale = decimal.scale().coerceAtLeast(0)
                found.add(Rational.of(BigInt(decimal.movePointRight(scale).toBigInteger()), BigInt(java.math.BigInteger.TEN.pow(scale))))
            }
            for (node in string.nodes) {
                if (node is NumberNode) run.append(node.text) else flush()
                node.subStrings.forEach { scan(it) }
            }
            flush()
        }
        scan(input)
        return found
    }

    /** "− 2x", "÷ 3", "· (x + 1)" or "√": what is done to both sides of an equation. */
    private fun writeOperation(operation: BothSides, writer: MathWriter): MathString {
        val result = MathString()
        when (operation.kind) {
            BothSides.Kind.ADD -> result.insert(result.length, OperatorNode(MathOp.PLUS))
            BothSides.Kind.SUBTRACT -> result.insert(result.length, OperatorNode(MathOp.MINUS))
            BothSides.Kind.MULTIPLY -> result.insert(result.length, TextNode("· "))
            BothSides.Kind.DIVIDE -> result.insert(result.length, OperatorNode(MathOp.DIVIDE))
            BothSides.Kind.SQUARE_ROOT -> result.insert(result.length, TextNode("√"))
            // ln, log, or log with the base as small lowered digits: log₂
            BothSides.Kind.LOGARITHM -> {
                val base = operation.operand
                val text = when {
                    base == Constant.E -> "ln"
                    base is Value && base.rational == Rational.of(10L) -> "log"
                    base is Value && base.rational?.isFractional == false ->
                        "log" + base.rational.toDouble().toInt().toString().map { SUBSCRIPT_DIGITS[it - '0'] }.joinToString("")
                    else -> "log"
                }
                result.insert(result.length, TextNode(text))
                return result
            }
            BothSides.Kind.SQUARE -> {
                result.insert(result.length, TextNode("²"))
                return result
            }
            // e^, 10^ or 2^: the base raised to both sides
            BothSides.Kind.EXPONENTIATE -> {
                val base = operation.operand
                val text = when {
                    base == Constant.E -> "e^"
                    base is Value && base.rational != null -> "${writer.write(base)}^"
                    else -> "^"
                }
                result.insert(result.length, TextNode(text))
                return result
            }
            // ³√ : the degree as small raised digits
            BothSides.Kind.ROOT -> {
                val degree = (operation.operand as? Value)?.rational?.toDouble()?.toInt()?.toString().orEmpty()
                result.insert(result.length, TextNode(degree.map { SUPERSCRIPT_DIGITS[it - '0'] }.joinToString("") + "√"))
                return result
            }
        }
        val operand = operation.operand ?: return result
        val written = writer.write(operand)
        // · (x + 1), ÷ (−3)
        if (operand is Additions || operand.isNegative) result.insert(result.length, ParenthesesNode(written)) else result.insertAll(result.length, written.nodes)
        return result
    }

    /** "u = x²", or with a differential "du = 2x dx". */
    private fun writeNote(note: Note, writer: MathWriter): MathString {
        // Only a line of text: R₂ → R₂ − 3R₁
        if (note.relation == "" && (note.right as? TextExpression)?.text?.isEmpty() == true) return writer.write(note.left)
        note.relation?.let { relation ->
            val result = writer.write(note.left)
            result.insert(result.length, TextNode(" $relation "))
            result.insertAll(result.length, writer.write(note.right).nodes)
            return result
        }
        val differential = note.differential ?: return writer.write(Equals(note.left, note.right))
        val result = writer.write(note.left)
        result.insert(result.length, OperatorNode(MathOp.EQUALS))
        val right = writer.write(note.right)
        // du = (2x + 1) dx
        when {
            // du = dx rather than du = 1 dx
            note.right == Value.ONE -> {}
            note.right is Additions -> result.insert(result.length, ParenthesesNode(right))
            else -> result.insertAll(result.length, right.nodes)
        }
        val gap = if (note.right == Value.ONE) "" else " "
        result.insert(result.length, TextNode("${gap}d${differential.identifier}"))
        return result
    }

    /**
     * Whether [steps] has steps for [input]. For a derivative this only parses it; for other
     * expressions it works out the steps (only then is it clear whether there is anything to
     * show step by step), so call it in the background like [calculate].
     */
    suspend fun hasSteps(input: MathString, context: MathContext = MathContext()): Boolean {
        val execContext = context.copy()
        val parsed = try {
            MathContext.withContext(execContext) {
                if (splitDefinition(input) != null) null else parse(input, execContext)
            }
        } catch (e: ArithmeticException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (parsed == null || execContext.shouldStop) return false
        if (parsed is Derivative) return DerivativeSteps.isSupported(parsed)
        return steps(input, context) != null
    }

    /**
     * Whether the derivative [node] is a partial one: once the [definitions] (variable name to
     * value, as in [MathContext.variables]) are substituted, its body still depends on a
     * variable other than the one it differentiates by, e.g. d/dx(x²y). The differentiation
     * variable is bound, so a definition of it is not used. Only the notation differs (∂
     * instead of d), the result is the same. False for a body that cannot be parsed yet.
     */
    fun isPartialDerivative(node: DerivativeNode, definitions: Map<String, MathString> = emptyMap()): Boolean {
        val body = try {
            val parsed = parser.parse(node.body)
            val used = parsed.variables().map { it.identifier }
            val values = definitions.filterKeys { it in used && it != node.variable }.entries.associate { (name, value) -> Variable(name) to parser.parse(value) }
            parsed.substituteVariables(values)
        } catch (e: RuntimeException) {
            return false
        }
        return body.variables().any { it.identifier != node.variable }
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

        // sin(x) = 1/2 repeats: x = π/6 + 2πk, … rather than the few solutions that were found
        val periodic = EquationSteps.periodicSolutions(equation, targetVar, ctx)
        if (periodic != null) {
            val display = displayWriter(ctx)
            return CalculationResult(
                input = input,
                result = display.write(periodic),
                expression = equation,
                numericValue = solutions.first().evaluateNumerically(),
                issues = ctx.issues,
                answer = answerWriter(ctx).write(solutions.first()),
                unit = null,
                decimalPlaces = ctx.decimalPlaces
            )
        }

        // 1.609… found numerically is ln(5) where the steps of the equation say so
        val shownSolutions = EquationSteps.exactSolutions(equation, targetVar, solutions, ctx)?.toCollection(LinkedHashSet()) ?: solutions
        val display = displayWriter(ctx)
        val combinedResult = MathString()
        for ((idx, sol) in shownSolutions.withIndex()) {
            if (idx > 0) combinedResult.insert(combinedResult.length, TextNode(", "))
            combinedResult.insertAll(combinedResult.length, display.write(Equals(targetVar, sol)).nodes)
        }
        return CalculationResult(
            input = input,
            result = combinedResult,
            expression = equation,
            numericValue = solutions.first().evaluateNumerically(),
            issues = ctx.issues,
            answer = answerWriter(ctx).write(solutions.first()),
            unit = solutions.first().partWithUnit()?.let { display.writeUnit(it) },
            decimalPlaces = ctx.decimalPlaces
        )
    }

    /** An inequality such as x² − 4 > 0 gives where it holds: "x < −2 or x > 2". */
    private fun evaluateInequality(input: MathString, inequality: Inequality, ctx: MathContext): CalculationResult {
        if (inequality.containsExpressionMatching { it is MatrixExpression }) {
            return failure(input, ctx, MathIssue.MATRIX_UNSUPPORTED, inequality)
        }
        if (inequality.variables().isEmpty()) {
            val truth = inequality.truthValue(ctx) ?: return failure(input, ctx, MathIssue.UNDEFINED, inequality)
            return truthResult(input, inequality, truth, ctx)
        }
        val analysis = InequalitySolver.analyze(inequality, ctx.copy().also { it.complexNumbers = false })
        if (analysis == null && !ctx.shouldStop) {
            // sin(x) > 1/2: the solution repeats in every period
            val trig = TrigInequalitySolver.solve(inequality, ctx.copy().also { it.complexNumbers = false })
            if (trig != null && !ctx.shouldStop) {
                if (trig.kind == TrigInequality.Kind.NONE) return failure(input, ctx, MathIssue.NO_REAL_SOLUTION, inequality)
                return CalculationResult(
                    input = input,
                    result = displayWriter(ctx).write(trig.forX),
                    expression = inequality,
                    numericValue = Double.NaN,
                    issues = ctx.issues,
                    answer = null,
                    decimalPlaces = ctx.decimalPlaces
                )
            }
        }
        if (analysis == null || ctx.shouldStop) return failure(input, ctx, MathIssue.CANT_SOLVE_GENERIC, inequality)
        if (analysis.pieces.isEmpty()) return failure(input, ctx, MathIssue.NO_REAL_SOLUTION, inequality)
        return CalculationResult(
            input = input,
            result = displayWriter(ctx).write(analysis.toExpression()),
            expression = inequality,
            numericValue = Double.NaN,
            issues = ctx.issues,
            answer = null,
            decimalPlaces = ctx.decimalPlaces
        )
    }

    /**
     * A system of equations, one per line, e.g. −2x + y = −3 and 3x + 2y = 8 gives
     * "x = 2, y = 1". Only linear systems are solved.
     */
    /** The solutions of two equations where one is linear in a variable, one per line: x = 3, y = 4. */
    private fun evaluateNonlinearSystem(input: MathString, system: EquationSystem, equations: List<Equals>, variables: List<Variable>, ctx: MathContext): CalculationResult {
        val solutions = NonlinearSystemSolver.solve(equations, variables, ctx)
        if (ctx.shouldStop) return failure(input, ctx, expression = system)
        if (solutions == null) return failure(input, ctx, MathIssue.CANT_SOLVE_GENERIC, system)
        if (solutions.isEmpty()) return failure(input, ctx, MathIssue.NO_REAL_SOLUTION, system)
        val display = displayWriter(ctx)
        val result = MathString()
        for ((i, solution) in solutions.withIndex()) {
            if (i > 0) result.insert(result.length, LineBreakNode)
            for ((j, v) in variables.withIndex()) {
                if (j > 0) result.insert(result.length, TextNode(", "))
                result.insertAll(result.length, display.write(Equals(v, solution.getValue(v).toPreferredUnits(ctx))).nodes)
            }
        }
        return CalculationResult(
            input = input,
            result = result,
            expression = system,
            numericValue = Double.NaN,
            issues = ctx.issues,
            // (with several solutions there is no one to reuse)
            answer = if (solutions.size == 1) answerWriter(ctx).write(solutions.single().getValue(variables[0])) else null,
            decimalPlaces = ctx.decimalPlaces
        )
    }

    private fun evaluateSystem(input: MathString, system: EquationSystem, ctx: MathContext): CalculationResult {
        // x > 1 and x < 5: the solutions of the inequalities have to hold together
        if (system.lines.isNotEmpty() && system.lines.all { it is Inequality }) {
            if (system.containsExpressionMatching { it is MatrixExpression }) return failure(input, ctx, MathIssue.MATRIX_UNSUPPORTED, system)
            val solved = InequalitySystemSolver.solve(system.lines.map { it as Inequality }, ctx.copy().also { it.complexNumbers = false })
            if (solved == null || ctx.shouldStop) return failure(input, ctx, MathIssue.CANT_SOLVE_GENERIC, system)
            if (solved.pieces.isEmpty()) return failure(input, ctx, MathIssue.NO_REAL_SOLUTION, system)
            return CalculationResult(
                input = input,
                result = displayWriter(ctx).write(solved.toExpression()),
                expression = system,
                numericValue = Double.NaN,
                issues = ctx.issues,
                answer = null,
                decimalPlaces = ctx.decimalPlaces
            )
        }
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
        // Not linear (x² + y² = 25): by substitution, with several solutions
        val probe = ctx.copy()
        if (LinearSystemSolver.solve(equations, orderedVars, probe) == null && probe.issues.contains(MathIssue.CANT_SOLVE_GENERIC)) {
            return evaluateNonlinearSystem(input, system, equations, orderedVars, ctx)
        }
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
            answer = answerWriter(ctx).write(first),
            decimalPlaces = ctx.decimalPlaces
        )
    }

    private fun truthResult(input: MathString, equation: Expression, truth: Boolean, ctx: MathContext) =
        CalculationResult(
            input = input,
            result = MathString(listOf(TextNode(Texts.get(if (truth) Text.TRUE else Text.FALSE, ctx.language)))),
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

    private const val SUBSCRIPT_DIGITS = "₀₁₂₃₄₅₆₇₈₉"

    private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    private val PREFERRED_VARIABLES = listOf(Variable("x"), Variable("y"), Variable("z"))
}
