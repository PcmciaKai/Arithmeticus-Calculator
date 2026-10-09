package com.vortessence.calculator.core.math.steps

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.expressions.Additions
import com.vortessence.calculator.core.math.expressions.Expression
import com.vortessence.calculator.core.math.expressions.Multiplications
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.core.math.expressions.Variable
import com.vortessence.calculator.core.math.expressions.tidyForDisplay
import com.vortessence.calculator.core.math.type.MathWriter

/**
 * A step of a solution before it is written for display: [expression] with the part the next
 * step changes highlighted, and the [change] that led to it (see [SolutionStep]).
 */
internal class ExpressionStep(
    val expression: Expression,
    val rule: StepRule?,
    val chainRule: Boolean = false,
    val change: Pair<Expression, Expression>? = null,
    val notes: List<Note> = emptyList(),
    /** What the next step does to both sides of the equation in [expression], e.g. "− 2x". */
    val operation: BothSides? = null
)

/**
 * An operation on both sides of an equation, shown next to it as in "3x + 5 = 2x − 1 | − 2x":
 * adding, subtracting, multiplying or dividing by [operand], or taking the square root.
 */
internal class BothSides(val kind: Kind, val operand: Expression? = null) {
    enum class Kind { ADD, SUBTRACT, MULTIPLY, DIVIDE, SQUARE_ROOT }
}

/**
 * The color of boxes a rule puts into its replacement itself (with [Match.boxReplacements]
 * false), e.g. around the −2x added to each side; the step's color once it is applied.
 */
internal const val PENDING_COLOR = -1

internal class ExpressionSolution(val steps: List<ExpressionStep>, val result: Expression)

/**
 * A line that tells what a rule needs, e.g. u = x² or, with a [differential], du = 2x dx.
 */
internal class Note(val left: Expression, val right: Expression, val differential: Variable? = null)

/** The position of a part of an expression: the child indices from the root down to it. */
internal typealias Path = List<Int>

/**
 * A rule applied to one part of an expression: [replacements] replace the parts at their paths
 * (and are highlighted as the result of the step), [removals] replace the parts that merge into
 * them by a neutral 1 or 0 (e.g. the second number in 2·2 → 4). [before] = [after] shows it.
 */
internal class Match(
    val rule: StepRule,
    val replacements: Map<Path, Expression>,
    val before: Expression,
    val after: Expression,
    val removals: Map<Path, Expression> = emptyMap(),
    val chainRule: Boolean = false,
    val notes: List<Note> = emptyList(),
    /** Shown next to the expression the step is applied to, see [BothSides]. */
    val operation: BothSides? = null,
    /** Whether [before] = [after] is shown; not for an equation changed on both sides. */
    val showChange: Boolean = true,
    /** Whether replacements are boxed as a whole; if not, they bring their own boxes. */
    val boxReplacements: Boolean = true,
    /** The parts of the previous line to color, where they are not those that are replaced. */
    val highlight: Set<Path>? = null
)

/** Builds the steps: each applied [Match] gives the next expression and highlights its parts. */
internal class Solver(val context: MathContext) {
    private class Line(
        var expression: Expression,
        val rule: StepRule?,
        val chainRule: Boolean,
        val change: Pair<Expression, Expression>?,
        val notes: List<Note> = emptyList()
    ) {
        var next: Set<Path> = emptySet()
        var operation: BothSides? = null
    }

    private val lines = mutableListOf<Line>()
    val size: Int get() = lines.size
    val current: Expression get() = lines.last().expression

    fun start(problem: Expression) {
        lines.add(Line(problem, null, false, null))
    }

    fun apply(match: Match) {
        // A change that cannot be seen, e.g. 2⁻¹ = 1/2, is made without a step of its own
        val keepsValue = current.containsExpressionMatching { it is Marked && it.keep }
        if (!keepsValue && match.notes.isEmpty() && match.operation == null && writtenForm(match.before) == writtenForm(match.after)) {
            // A value written in is no longer kept as a factor 1 or 0 once something is done with it
            val rebuilt = rebuild(current, emptyList(), match, color = 0, silent = true)
            lines.last().expression = normalize(rebuilt.transform { if (it is Marked && it.keep) Marked(it.inner, it.colorIndex, it.boxed) else it })
            return
        }
        val color = lines.size
        lines.last().next = match.highlight ?: (match.replacements.keys + match.removals.keys)
        lines.last().operation = match.operation
        val rebuilt = rebuild(current, emptyList(), match, color)
        // Boxes the rule put in itself get the step's color
        val result = normalize(rebuilt.transform { if (it is Marked && it.colorIndex == PENDING_COLOR) Marked(it.inner, color, it.boxed, it.keep) else it })
        val change = if (match.showChange) match.before to match.after else null
        lines.add(Line(result, match.rule, match.chainRule, change, match.notes))
    }

    /**
     * Simplifies step by step ([SimplificationSteps]) toward [target], the calculated result as
     * it is shown, and if the rules do not get there, takes the rest as one last step. False if
     * it took too many steps.
     */
    fun simplifyToward(target: Expression, maxSteps: Int): Boolean {
        while (true) {
            if (context.shouldStop || size > maxSteps) return false
            val match = SimplificationSteps.match(current, target, context) ?: break
            apply(match)
        }
        // What the rules did not do, e.g. factoring
        if (writtenForm(current) != writtenForm(target)) {
            apply(Match(StepRule.SIMPLIFY, mapOf(emptyList<Int>() to target), normalize(unmarked(current)), target))
        }
        return true
    }

    fun finish(result: Expression) = ExpressionSolution(
        lines.mapIndexed { i, line ->
            ExpressionStep(highlight(line.expression, emptyList(), line.next, i + 1), line.rule, line.chainRule, line.change, line.notes, line.operation)
        },
        result
    )

    /**
     * [e] with the parts of [match] replaced (boxed in [color]) and earlier boxes removed. A
     * [silent] change is not boxed and keeps the earlier boxes.
     */
    private fun rebuild(e: Expression, path: Path, match: Match, color: Int, silent: Boolean = false): Expression {
        match.replacements[path]?.let { return if (silent || !match.boxReplacements) it else Marked(it, color, boxed = true) }
        match.removals[path]?.let { return it }
        if (e is Marked && !silent) return rebuild(e.inner, path + 0, match, color)
        if (e.children.isEmpty()) return e
        return e.copyWithChildren(e.children.mapIndexed { i, child -> rebuild(child, path + i, match, color, silent) })
    }

    /** [e] with the parts at [paths] colored in [color]. */
    private fun highlight(e: Expression, path: Path, paths: Set<Path>, color: Int): Expression {
        if (path in paths) return Marked(e, color, boxed = false)
        if (e.children.isEmpty() || paths.none { it.size > path.size && it.subList(0, path.size) == path }) return e
        return e.copyWithChildren(e.children.mapIndexed { i, child -> highlight(child, path + i, paths, color) })
    }
}


/** The calculated result [canonical] as it is shown: symbolic results in a compact form. */
internal fun displayForm(canonical: Expression, context: MathContext): Expression =
    if (canonical.variables().isNotEmpty() && canonical.partWithUnit() == null) tidyForDisplay(canonical, context) else canonical

/** How [e] looks without highlighting (a highlighted product inside a product is flattened). */
internal fun writtenForm(e: Expression): String = MathWriter().write(normalize(unmarked(e))).toString()


/**
 * Flattens nested sums and products and drops factors 1 and terms 0, so that a step does
 * not show leftovers of the rules such as 1·x or + 0. Nothing else is simplified.
 */
internal fun normalize(e: Expression): Expression = when (e) {
    is Additions -> {
        val terms = e.operands.map { normalize(it) }
            .flatMap { if (it is Additions) it.operands else listOf(it) }
            .filterNot { it.isZero }
        when (terms.size) {
            0 -> Value.ZERO
            1 -> terms.single()
            else -> Additions(terms)
        }
    }
    is Multiplications -> {
        val factors = e.operands.map { normalize(it) }
            .flatMap { if (it is Multiplications) it.operands else listOf(it) }
        when {
            factors.any { it.isZero && !isKept(it) } -> Value.ZERO
            // Also a highlighted 1, e.g. from d/dx(x) = 1: the step shows where it went
            else -> Multiplications.of(*factors.filterNot { unmarked(it) == Value.ONE && !isKept(it) }.toTypedArray())
        }
    }
    else -> if (e.children.isEmpty()) e else e.copyWithChildren(e.children.map { normalize(it) })
}

/** The paths of the parts of [e] that match [predicate], in reading order (parents first). */
internal fun pathsOf(e: Expression, path: Path = emptyList(), predicate: (Expression) -> Boolean): List<Path> {
    val own = if (predicate(e)) listOf(path) else emptyList()
    return own + e.children.flatMapIndexed { i, child -> pathsOf(child, path + i, predicate) }
}

/** The part of [e] at [path]. */
internal fun at(e: Expression, path: Path): Expression = path.fold(e) { part, i -> part.children[i] }

private fun isKept(e: Expression) = e is Marked && e.keep
