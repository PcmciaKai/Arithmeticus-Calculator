package com.vortessence.calculator.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.vortessence.calculator.R
import com.vortessence.calculator.core.math.steps.NumberLine
import com.vortessence.calculator.core.math.steps.SolutionStep
import com.vortessence.calculator.core.math.steps.StepChange
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.steps.Transform
import com.vortessence.calculator.core.math.type.HighlightNode
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.ui.math.MathExpressionView
import com.vortessence.calculator.ui.math.highlightColor
import com.vortessence.calculator.viewmodel.StepsState

/**
 * The step-by-step solution of a calculation: the problem, then for each step an arrow in the
 * step's color with the rule (in general and applied to the part it changes) and the
 * expression it leads to; the last one, the result, is framed. In each expression, the part the last step
 * produced is boxed and the part the next step changes is colored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepsSheet(state: StepsState, onDismiss: () -> Unit, onSelect: (Transform?) -> Unit = {}) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.steps_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            // The solution, or the line written in another form: one to choose from if there are several
            if (state.options.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.options.forEach { option ->
                        FilterChip(
                            selected = option == state.selected,
                            onClick = { onSelect(option) },
                            label = {
                                Text(stringResource(when (option) {
                                    null -> R.string.steps_option_solution
                                    Transform.EXPAND -> R.string.transform_expand
                                    Transform.FACTOR -> R.string.transform_factor
                                    Transform.COMBINE -> R.string.transform_combine
                                }))
                            }
                        )
                    }
                }
            }
            val solution = state.solution
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                solution == null -> Text(
                    stringResource(R.string.steps_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    val rows = remember(solution) { stepRows(solution.steps) }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                    ) {
                        items(rows, key = { it.from }) { row ->
                            when {
                                row.to == row.from -> StepItem(solution.steps, row.from)
                                row.section -> SectionGroup(solution.steps, row.from, row.to)
                                else -> CalculationGroup(solution.steps, row.from, row.to)
                            }
                        }
                        // An inequality: the solution drawn, and written as intervals
                        solution.numberLine?.let { line -> item(key = "numberLine") { NumberLineView(line) } }
                        solution.intervalNotation?.let { notation -> item(key = "intervals") { IntervalNotation(notation) } }
                    }
                }
            }
        }
    }
}

/** A step, or with [to] after [from] a run of calculation steps that can be folded away. */
private class StepRow(val from: Int, val to: Int, /** Folds the steps of an equation that is part of a longer solution. */ val section: Boolean = false)

/** Runs of at least this many plain calculations (adding, multiplying … numbers) are folded. */
private const val MIN_FOLDED_STEPS = 3

/** The steps that solve an equation inside an inequality start at these rules … */
private val SECTION_STARTS = setOf(StepRule.INEQUALITY_ZEROS, StepRule.INEQUALITY_BREAKS)

/** … and end before one of these. */
private val SECTION_ENDS = setOf(StepRule.INEQUALITY_ZEROS, StepRule.INEQUALITY_BREAKS, StepRule.INEQUALITY_CRITICAL, StepRule.INEQUALITY_RESULT)

private const val MIN_SECTION_STEPS = 4

private val CALCULATION_RULES = setOf(
    StepRule.NUMBER_POWER, StepRule.ROOT_OF_NUMBER, StepRule.DIVIDE_BY_FRACTION,
    StepRule.MULTIPLY_NUMBERS, StepRule.DIVIDE_NUMBERS, StepRule.REDUCE_FRACTION,
    StepRule.ADD_NUMBERS, StepRule.SUBTRACT_NUMBERS, StepRule.ADD_AND_SUBTRACT_NUMBERS
)

/** The steps in rows; the problem and the result stay as they are, long runs of calculations become one row. */
private fun stepRows(steps: List<SolutionStep>): List<StepRow> {
    val rows = mutableListOf<StepRow>()
    var i = 0
    while (i < steps.size) {
        // The steps of an equation inside an inequality (where its left side is 0): folded, they end at its solutions
        if (steps[i].rule in SECTION_STARTS) {
            var end = i
            while (end + 1 < steps.size && steps[end + 1].rule !in SECTION_ENDS) end++
            if (end - i + 1 >= MIN_SECTION_STEPS) {
                rows.add(StepRow(i, end, section = true))
                i = end + 1
                continue
            }
        }
        var end = i
        if (i > 0 && steps[i].rule in CALCULATION_RULES) {
            while (end + 1 < steps.lastIndex && steps[end + 1].rule in CALCULATION_RULES) end++
        }
        if (end - i + 1 >= MIN_FOLDED_STEPS) {
            rows.add(StepRow(i, end))
            i = end + 1
        } else {
            rows.add(StepRow(i, i))
            i++
        }
    }
    return rows
}

/** One step: its arrow (not for the problem) and the expression it leads to, the result framed. */
@Composable
private fun StepItem(steps: List<SolutionStep>, index: Int) {
    val step = steps[index]
    if (index > 0) StepArrow(step, highlightColor(index))
    if (index == steps.lastIndex && index > 0) {
        ResultLine(step.expression)
    } else {
        ExpressionLine(step, highlightColor(index + 1))
    }
}

/** The steps of solving an equation inside an inequality: the equation, then the steps folded, then its solutions. */
@Composable
private fun SectionGroup(steps: List<SolutionStep>, from: Int, to: Int) {
    var expanded by rememberSaveable(from) { mutableStateOf(false) }
    val count = to - from
    StepItem(steps, from)
    if (expanded) {
        FoldToggle(stringResource(R.string.steps_hide_section, count), expanded = true) { expanded = false }
        for (index in from + 1..to) StepItem(steps, index)
    } else {
        FoldedArrow(highlightColor(from + 1)) { FoldToggle(stringResource(R.string.steps_show_section, count), expanded = false) { expanded = true } }
        ExpressionLine(steps[to], highlightColor(to + 1))
    }
}

/** The solution of an inequality as intervals: (−∞, −2) ∪ (2, ∞). */
@Composable
private fun IntervalNotation(notation: MathString) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Text(stringResource(R.string.steps_interval_notation), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
        ScrollingMath(notation, fontSize = 22.sp)
    }
}

/**
 * The solution of an inequality on a number line: the points where it changes, a closed dot where the point
 * is part of the solution and an open one where it is not, the parts where it holds in color.
 */
@Composable
private fun NumberLineView(line: NumberLine) {
    val primary = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(stringResource(R.string.steps_number_line), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = axis)
        androidx.compose.ui.layout.Layout(
            content = { line.points.forEach { point -> MathExpressionView(mathString = point.label, fontSize = 16.sp, textColor = axis, accentColor = axis) } },
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val xs = numberLinePositions(line.points.size, size.width, 28.dp.toPx())
                    val y = 18.dp.toPx()
                    val stroke = 2.dp.toPx()
                    // The line with arrows at both ends
                    drawLine(axis, Offset(0f, y), Offset(size.width, y), stroke)
                    val head = 6.dp.toPx()
                    drawLine(axis, Offset(head, y - head), Offset(0f, y), stroke, StrokeCap.Round)
                    drawLine(axis, Offset(head, y + head), Offset(0f, y), stroke, StrokeCap.Round)
                    drawLine(axis, Offset(size.width - head, y - head), Offset(size.width, y), stroke, StrokeCap.Round)
                    drawLine(axis, Offset(size.width - head, y + head), Offset(size.width, y), stroke, StrokeCap.Round)
                    // Where it holds
                    for (piece in line.pieces) {
                        val from = piece.lower?.let { xs[it] } ?: 0f
                        val to = piece.upper?.let { xs[it] } ?: size.width
                        drawLine(primary, Offset(from, y), Offset(to, y), 6.dp.toPx(), StrokeCap.Butt)
                    }
                    // The points: closed if they are in the solution
                    val radius = 6.dp.toPx()
                    for ((i, x) in xs.withIndex()) {
                        val closed = line.pieces.any { (it.lower == i && it.lowerClosed) || (it.upper == i && it.upperClosed) }
                        if (closed) {
                            drawCircle(primary, radius, Offset(x, y))
                        } else {
                            drawCircle(surface, radius, Offset(x, y))
                            drawCircle(axis, radius, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                        }
                    }
                }
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val xs = numberLinePositions(line.points.size, width.toFloat(), 28.dp.toPx())
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            // The labels hang below the line; as tall as the tallest one (a fraction) needs, so that nothing overlaps
            val labelTop = 27.dp.roundToPx()
            val height = labelTop + (placeables.maxOfOrNull { it.height } ?: 0)
            layout(width, height) {
                placeables.forEachIndexed { i, placeable ->
                    placeable.placeRelative((xs[i] - placeable.width / 2f).toInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0)), labelTop)
                }
            }
        }
    }
}

/** The x positions of [n] points along [width], evenly spread (it is a sketch), with [margin] at both ends. */
private fun numberLinePositions(n: Int, width: Float, margin: Float): List<Float> = when (n) {
    0 -> emptyList()
    1 -> listOf(width / 2)
    else -> List(n) { margin + (width - 2 * margin) * it / (n - 1) }
}

/** A run of calculations, folded to one arrow with the expression it ends in; a tap shows every step. */
@Composable
private fun CalculationGroup(steps: List<SolutionStep>, from: Int, to: Int) {
    var expanded by rememberSaveable(from) { mutableStateOf(false) }
    val count = to - from + 1
    if (expanded) {
        FoldToggle(stringResource(R.string.steps_hide_calculations, count), expanded = true) { expanded = false }
        for (index in from..to) StepItem(steps, index)
    } else {
        FoldedArrow(highlightColor(from)) { FoldToggle(stringResource(R.string.steps_show_calculations, count), expanded = false) { expanded = true } }
        ExpressionLine(steps[to], highlightColor(to + 1))
    }
}

@Composable
private fun FoldToggle(label: String, expanded: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.10f)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 14.sp, color = color)
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = color
            )
        }
    }
}

/** The arrow of a folded run, with [content] beside it. */
@Composable
private fun FoldedArrow(color: Color, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .arrowBehind(color)
            .padding(start = 36.dp, top = 8.dp, bottom = 12.dp)
    ) { content() }
}

/** A downward arrow in [color], next to what the step does. */
@Composable
private fun StepArrow(step: SolutionStep, color: Color) {
    // The arrow is drawn behind the column, so the column keeps its natural height (measuring it
    // in advance, as for a Canvas beside it, cuts off tall lines such as the discriminant)
    Column(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .arrowBehind(color)
            .padding(start = 36.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        step.rule?.let { RuleText(stringResource(it.descriptionRes), it.formula) }
        if (step.chainRule) {
            if (step.rule?.isIntegral == true) RuleText(stringResource(R.string.steps_linear_argument), "\\int{f(ax + b)}{x} = \\frac{F(ax + b)}{a}")
            else RuleText(stringResource(R.string.steps_chain_rule), "(f(g))′ = f′(g)·g′")
        }
        step.notes.forEach { NoteLine(it) }
        step.change?.let { ChangeLine(it) }
    }
}

/** A downward arrow in [color] along the left edge, behind the content. */
private fun Modifier.arrowBehind(color: Color): Modifier = drawBehind {
    val x = 12.dp.toPx()
    val stroke = 2.dp.toPx()
    val head = 6.dp.toPx()
    val bottom = size.height - stroke
    drawLine(color, Offset(x, 0f), Offset(x, bottom), stroke, StrokeCap.Round)
    drawLine(color, Offset(x - head, bottom - head), Offset(x, bottom), stroke, StrokeCap.Round)
    drawLine(color, Offset(x + head, bottom - head), Offset(x, bottom), stroke, StrokeCap.Round)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun RuleText(description: String, formula: String?) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // The formula follows the description, or goes on the next line if there is no room
    FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (formula == null) description else "$description:  ",
            fontSize = 14.sp,
            fontStyle = FontStyle.Italic,
            color = color
        )
        if (formula != null) {
            MathExpressionView(mathString = remember(formula) { ruleFormula(formula) }, fontSize = 15.sp, textColor = color, accentColor = color)
        }
    }
}

/** What the rule needs, e.g. u = x². */
@Composable
private fun NoteLine(note: MathString) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        MathExpressionView(mathString = note, fontSize = 17.sp, textColor = color, accentColor = color)
    }
}

/** The rule applied to the part it changes: before = after. */
@Composable
private fun ChangeLine(change: StepChange) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MathExpressionView(mathString = change.before, fontSize = 17.sp, textColor = color, accentColor = color)
        Text(" = ", fontSize = 17.sp, color = color)
        MathExpressionView(mathString = change.after, fontSize = 17.sp, textColor = color, accentColor = color)
    }
}

/** The last line, the result, framed so that it stands out. */
@Composable
private fun ResultLine(math: MathString) {
    val color = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.5.dp, color.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.steps_result), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
            // The frame is enough, without the box of the last step inside it
            ScrollingMath(withoutHighlights(math), fontSize = 24.sp)
        }
    }
}

/** [math] with its highlighted parts as plain parts. */
private fun withoutHighlights(math: MathString): MathString = MathString(math.nodes.flatMap { node ->
    if (node is HighlightNode) {
        withoutHighlights(node.content).nodes
    } else {
        listOf(node.subStrings.foldIndexed(node) { i, acc, sub -> acc.replaceSubString(i, withoutHighlights(sub)) })
    }
})

/**
 * A step's expression and, for an equation, what the next step does to both sides after a bar
 * in that step's [color]: 3x + 5 = 2x − 1 | − 2x.
 */
@Composable
private fun ExpressionLine(step: SolutionStep, color: Color) {
    val operation = step.operation ?: return ScrollingMath(step.expression, fontSize = 24.sp)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MathExpressionView(mathString = step.expression, fontSize = 24.sp, textColor = MaterialTheme.colorScheme.onBackground)
        Text("   |  ", fontSize = 24.sp, fontWeight = FontWeight.Light, color = color)
        MathExpressionView(mathString = operation, fontSize = 22.sp, textColor = color, accentColor = color)
    }
}

/** Long expressions scroll instead of being squeezed. */
@Composable
private fun ScrollingMath(math: MathString, fontSize: androidx.compose.ui.unit.TextUnit) {
    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        MathExpressionView(
            mathString = math,
            fontSize = fontSize,
            textColor = MaterialTheme.colorScheme.onBackground
        )
    }
}

/** Whether this is a rule of integration (a linear inner function divides instead of multiplying). */
private val StepRule.isIntegral: Boolean
    get() = name.startsWith("INTEGRAL_")

private val StepRule.descriptionRes: Int
    get() = when (this) {
        StepRule.CONSTANT -> R.string.step_constant
        StepRule.VARIABLE -> R.string.step_variable
        StepRule.SUM -> R.string.step_sum
        StepRule.CONSTANT_FACTOR -> R.string.step_constant_factor
        StepRule.PRODUCT -> R.string.step_product
        StepRule.QUOTIENT -> R.string.step_quotient
        StepRule.POWER -> R.string.step_power
        StepRule.EXPONENTIAL -> R.string.step_exponential
        StepRule.GENERAL_POWER -> R.string.step_general_power
        StepRule.LOGARITHM -> R.string.step_logarithm
        StepRule.CHANGE_OF_BASE -> R.string.step_change_of_base
        StepRule.SINE -> R.string.step_sine
        StepRule.COSINE -> R.string.step_cosine
        StepRule.TANGENT -> R.string.step_tangent
        StepRule.ARCSINE -> R.string.step_arcsine
        StepRule.ARCCOSINE -> R.string.step_arccosine
        StepRule.ARCTANGENT -> R.string.step_arctangent
        StepRule.ABSOLUTE_VALUE -> R.string.step_absolute_value
        StepRule.FUNDAMENTAL_THEOREM -> R.string.step_fundamental_theorem
        StepRule.IMAGINARY_ROOT -> R.string.step_imaginary_root
        StepRule.MULTIPLY_NUMBERS -> R.string.step_multiply_numbers
        StepRule.DIVIDE_NUMBERS -> R.string.step_divide_numbers
        StepRule.EXTEND_FRACTIONS -> R.string.step_extend_fractions
        StepRule.IMPROPER_FRACTION -> R.string.step_improper_fraction
        StepRule.PERCENT_AS_FRACTION -> R.string.step_percent_as_fraction
        StepRule.MULTIPLY_FRACTIONS -> R.string.step_multiply_fractions
        StepRule.SIMPLIFY_ROOT -> R.string.step_simplify_root
        StepRule.REDUCE_FRACTION -> R.string.step_reduce_fraction
        StepRule.NUMBER_POWER -> R.string.step_number_power
        StepRule.DIVIDE_BY_FRACTION -> R.string.step_divide_by_fraction
        StepRule.REWRITE_AS_POWER -> R.string.step_rewrite_as_power
        StepRule.CHAIN_RULE -> R.string.step_chain_rule
        StepRule.POWER_AS_ROOT -> R.string.step_power_as_root
        StepRule.POWER_AS_FRACTION -> R.string.step_power_as_fraction
        StepRule.POWER_OF_POWER -> R.string.step_power_of_power
        StepRule.ROOT_OF_SQUARE -> R.string.step_root_of_square
        StepRule.POWER_OF_PRODUCT -> R.string.step_power_of_product
        StepRule.EXPAND -> R.string.step_expand
        StepRule.EXPAND_POWER -> R.string.step_expand_power
        StepRule.COMBINE_FRACTIONS -> R.string.step_combine_fractions
        StepRule.ADD_NUMBERS -> R.string.step_add_numbers
        StepRule.SUBTRACT_NUMBERS -> R.string.step_subtract_numbers
        StepRule.ADD_AND_SUBTRACT_NUMBERS -> R.string.step_add_and_subtract_numbers
        StepRule.SUBTRACT_SAME -> R.string.step_subtract_same
        StepRule.COMBINE_POWERS -> R.string.step_combine_powers
        StepRule.CANCEL -> R.string.step_cancel
        StepRule.TANGENT_AS_QUOTIENT -> R.string.step_tangent_as_quotient
        StepRule.CANCEL_COMMON_FACTOR -> R.string.step_cancel_common_factor
        StepRule.DISTRIBUTE -> R.string.step_distribute
        StepRule.COMBINE_LIKE_TERMS -> R.string.step_combine_like_terms
        StepRule.INTEGRAL_CONSTANT -> R.string.step_integral_constant
        StepRule.INTEGRAL_SUM -> R.string.step_integral_sum
        StepRule.INTEGRAL_CONSTANT_FACTOR -> R.string.step_integral_constant_factor
        StepRule.INTEGRAL_POWER -> R.string.step_integral_power
        StepRule.INTEGRAL_RECIPROCAL -> R.string.step_integral_reciprocal
        StepRule.INTEGRAL_EXPONENTIAL -> R.string.step_integral_exponential
        StepRule.INTEGRAL_SINE -> R.string.step_integral_sine
        StepRule.INTEGRAL_COSINE -> R.string.step_integral_cosine
        StepRule.INTEGRAL_TANGENT -> R.string.step_integral_tangent
        StepRule.INTEGRAL_LOGARITHM -> R.string.step_integral_logarithm
        StepRule.INTEGRAL_TABLE -> R.string.step_integral_table
        StepRule.EXPAND_INTEGRAND -> R.string.step_expand_integrand
        StepRule.SUBSTITUTION -> R.string.step_substitution
        StepRule.BACK_SUBSTITUTION -> R.string.step_back_substitution
        StepRule.BY_PARTS -> R.string.step_by_parts
        StepRule.PARTIAL_FRACTIONS -> R.string.step_partial_fractions
        StepRule.SPLIT_NUMERATOR -> R.string.step_split_numerator
        StepRule.COMPLETE_SQUARE -> R.string.step_complete_square
        StepRule.ANTIDERIVATIVE -> R.string.step_antiderivative
        StepRule.BOUNDS -> R.string.step_bounds
        StepRule.ADD_TO_BOTH_SIDES -> R.string.step_add_to_both_sides
        StepRule.SUBTRACT_FROM_BOTH_SIDES -> R.string.step_subtract_from_both_sides
        StepRule.MULTIPLY_BOTH_SIDES -> R.string.step_multiply_both_sides
        StepRule.DIVIDE_BOTH_SIDES -> R.string.step_divide_both_sides
        StepRule.DIVIDE_BY_NEGATIVE -> R.string.step_divide_by_negative
        StepRule.MULTIPLY_BY_NEGATIVE -> R.string.step_multiply_by_negative
        StepRule.INEQUALITY_SWAP -> R.string.step_inequality_swap
        StepRule.INEQUALITY_ONE_SIDE -> R.string.step_inequality_one_side
        StepRule.INEQUALITY_ZEROS -> R.string.step_inequality_zeros
        StepRule.INEQUALITY_BREAKS -> R.string.step_inequality_breaks
        StepRule.INEQUALITY_CRITICAL -> R.string.step_inequality_critical
        StepRule.INEQUALITY_RESULT -> R.string.step_inequality_result
        StepRule.SWAP_SIDES -> R.string.step_swap_sides
        StepRule.SQUARE_ROOT_BOTH_SIDES -> R.string.step_square_root_both_sides
        StepRule.FACTOR_OUT -> R.string.step_factor_out
        StepRule.FACTOR_BY_ROOT -> R.string.step_factor_by_root
        StepRule.NTH_ROOT_BOTH_SIDES -> R.string.step_nth_root_both_sides
        StepRule.LOGARITHM_BOTH_SIDES -> R.string.step_logarithm_both_sides
        StepRule.SQUARE_BOTH_SIDES -> R.string.step_square_both_sides
        StepRule.TRIG_GENERAL_SOLUTION -> R.string.step_trig_general_solution
        StepRule.AMPLITUDE_FORM -> R.string.step_amplitude_form
        StepRule.DOUBLE_ANGLE -> R.string.step_double_angle
        StepRule.FACTOR_COMMON -> R.string.step_factor_common
        StepRule.TANGENT_AS_FRACTION -> R.string.step_tangent_as_fraction
        StepRule.PYTHAGOREAN -> R.string.step_pythagorean
        StepRule.LOG_COMBINE -> R.string.step_log_combine
        StepRule.RATIONALIZE -> R.string.step_rationalize
        StepRule.TRIG_ODD_POWER -> R.string.step_trig_odd_power
        StepRule.PYTHAGOREAN_IDENTITY -> R.string.step_pythagorean_identity
        StepRule.HALF_ANGLE -> R.string.step_half_angle
        StepRule.POLYNOMIAL_DIVISION -> R.string.step_polynomial_division
        StepRule.CYCLIC_BY_PARTS -> R.string.step_cyclic_by_parts
        StepRule.ROOT_OF_FRACTION -> R.string.step_root_of_fraction
        StepRule.EXACT_TRIGONOMETRIC_VALUE -> R.string.step_exact_trigonometric_value
        StepRule.LOGARITHM_OF_POWER -> R.string.step_logarithm_of_power
        StepRule.EXPONENTIAL_OF_LOGARITHM -> R.string.step_exponential_of_logarithm
        StepRule.LOG_EQUAL -> R.string.step_log_equal
        StepRule.LIMIT_SUBSTITUTE -> R.string.step_limit_substitute
        StepRule.DIVIDE_BY_HIGHEST_POWER -> R.string.step_divide_by_highest_power
        StepRule.LIMIT_VANISH -> R.string.step_limit_vanish
        StepRule.LIMIT_CONJUGATE -> R.string.step_limit_conjugate
        StepRule.LIMIT_DIFFERENCE_OF_SQUARES -> R.string.step_limit_difference_of_squares
        StepRule.LIMIT_EXPONENT_INFINITE -> R.string.step_limit_exponent_infinite
        StepRule.LIMIT_ABSOLUTE_VALUE -> R.string.step_limit_absolute_value
        StepRule.LIMIT_CONSTANT -> R.string.step_limit_constant
        StepRule.LIMIT_GROWS -> R.string.step_limit_grows
        StepRule.LIMIT_RECIPROCAL -> R.string.step_limit_reciprocal
        StepRule.LHOPITAL -> R.string.step_lhopital
        StepRule.LIMIT_PRODUCT_AS_QUOTIENT -> R.string.step_limit_product_as_quotient
        StepRule.LIMIT_POLE -> R.string.step_limit_pole
        StepRule.IMPROPER_INTEGRAL -> R.string.step_improper_integral
        StepRule.FACTOR_POLYNOMIAL -> R.string.step_factor_polynomial
        StepRule.DETERMINANT_2X2 -> R.string.step_determinant_2x2
        StepRule.DETERMINANT_COFACTOR -> R.string.step_determinant_cofactor
        StepRule.INVERSE_2X2 -> R.string.step_inverse_2x2
        StepRule.INVERSE_AUGMENT -> R.string.step_inverse_augment
        StepRule.ROW_SWAP -> R.string.step_row_swap
        StepRule.ROW_SCALE -> R.string.step_row_scale
        StepRule.ROW_ADD -> R.string.step_row_add
        StepRule.INVERSE_RESULT -> R.string.step_inverse_result
        StepRule.ROW_REDUCE_BEGIN -> R.string.step_row_reduce_begin
        StepRule.EIGEN_CHARACTERISTIC -> R.string.step_eigen_characteristic
        StepRule.EIGEN_VECTOR -> R.string.step_eigen_vector
        StepRule.EIGEN_VECTOR_RESULT -> R.string.step_eigen_vector_result
        StepRule.EIGEN_RESULT -> R.string.step_eigen_result
        StepRule.MATRIX_MULTIPLY -> R.string.step_matrix_multiply
        StepRule.MATRIX_SCALAR -> R.string.step_matrix_scalar
        StepRule.MATRIX_ADD -> R.string.step_matrix_add
        StepRule.MATRIX_SUBTRACT -> R.string.step_matrix_subtract
        StepRule.MATRIX_TRANSPOSE -> R.string.step_matrix_transpose
        StepRule.MATRIX_POWER -> R.string.step_matrix_power
        StepRule.LIMIT_EXPONENTIAL_FORM -> R.string.step_limit_exponential_form
        StepRule.LIMIT_EXPONENTIAL_RESULT -> R.string.step_limit_exponential_result
        StepRule.LIMIT_LOGARITHM_ZERO -> R.string.step_limit_logarithm_zero
        StepRule.LIMIT_RECIPROCAL_SUBSTITUTION -> R.string.step_limit_reciprocal_substitution
        StepRule.ZERO_FRACTION -> R.string.step_zero_fraction
        StepRule.ABSOLUTE_VALUE_CASES -> R.string.step_absolute_value_cases
        StepRule.SUBSTITUTE_RATIO -> R.string.step_substitute_ratio
        StepRule.ABSOLUTE_VALUE_SPLIT -> R.string.step_absolute_value_split
        StepRule.ABSOLUTE_VALUE_CASE -> R.string.step_absolute_value_case
        StepRule.INEQUALITY_SYSTEM_EACH -> R.string.step_inequality_system_each
        StepRule.INEQUALITY_SYSTEM_INTERSECT -> R.string.step_inequality_system_intersect
        StepRule.LOG_SUM -> R.string.step_log_sum
        StepRule.LOG_QUOTIENT -> R.string.step_log_quotient
        StepRule.TRIG_INEQUALITY_ISOLATE -> R.string.step_trig_inequality_isolate
        StepRule.TRIG_INEQUALITY_PERIOD -> R.string.step_trig_inequality_period
        StepRule.TRIG_INEQUALITY_SOLVE_X -> R.string.step_trig_inequality_solve_x
        StepRule.TRIG_INEQUALITY_BOUNDED -> R.string.step_trig_inequality_bounded
        StepRule.ABSOLUTE_VALUES_EQUAL -> R.string.step_absolute_values_equal
        StepRule.EXPONENTIATE_BOTH_SIDES -> R.string.step_exponentiate_both_sides
        StepRule.CHECK_SOLUTIONS -> R.string.step_check_solutions
        StepRule.SUBSTITUTE_POWER -> R.string.step_substitute_power
        StepRule.BACK_SUBSTITUTE_POWER -> R.string.step_back_substitute_power
        StepRule.ONE_SIDE -> R.string.step_one_side
        StepRule.NEWTON_METHOD -> R.string.step_newton_method
        StepRule.NO_REAL_SOLUTION -> R.string.step_no_real_solution
        StepRule.CONTRADICTION -> R.string.step_contradiction
        StepRule.ZERO_PRODUCT -> R.string.step_zero_product
        StepRule.QUADRATIC_FORMULA -> R.string.step_quadratic_formula
        StepRule.ROOT_OF_NUMBER -> R.string.step_root_of_number
        StepRule.SOLVE_FOR -> R.string.step_solve_for
        StepRule.SUBSTITUTE_SOLUTION -> R.string.step_substitute_solution
        StepRule.MULTIPLY_EQUATIONS -> R.string.step_multiply_equations
        StepRule.ADD_EQUATIONS -> R.string.step_add_equations
        StepRule.SUBTRACT_EQUATIONS -> R.string.step_subtract_equations
        StepRule.INSERT_VALUE -> R.string.step_insert_value
        StepRule.COLLECT_SOLUTION -> R.string.step_collect_solution
        StepRule.IDENTITY -> R.string.step_identity
        StepRule.SIMPLIFY -> R.string.step_simplify
        StepRule.SUBSTITUTE -> R.string.step_substitute
        StepRule.EVALUATE -> R.string.step_evaluate
    }

/**
 * The rule in general, as a formula in the notation of [ruleFormula]; null where the
 * description says it all.
 */
internal val StepRule.formula: String?
    get() = when (this) {
        StepRule.CONSTANT -> "c′ = 0"
        StepRule.VARIABLE -> "x′ = 1"
        StepRule.SUM -> "(f ± g)′ = f′ ± g′"
        StepRule.CONSTANT_FACTOR -> "(c·f)′ = c·f′"
        StepRule.PRODUCT -> "(f·g)′ = f′·g + f·g′"
        StepRule.QUOTIENT -> "(\\frac{f}{g})′ = \\frac{f′·g − f·g′}{g^2}"
        StepRule.POWER -> "(x^n)′ = n·x^{n − 1}"
        StepRule.EXPONENTIAL -> "(e^x)′ = e^x, (a^x)′ = a^x·ln(a)"
        StepRule.GENERAL_POWER -> "(f^g)′ = (e^{g·ln(f)})′ = f^g·(g·ln(f))′"
        StepRule.LOGARITHM -> "(ln(x))′ = \\frac{1}{x}, (\\log{a}{x})′ = \\frac{1}{x·ln(a)}"
        StepRule.CHANGE_OF_BASE -> "\\log{f}{g} = \\frac{ln(g)}{ln(f)}"
        StepRule.SINE -> "(sin(x))′ = cos(x)"
        StepRule.COSINE -> "(cos(x))′ = −sin(x)"
        StepRule.TANGENT -> "(tan(x))′ = \\frac{1}{cos(x)^2}"
        StepRule.ARCSINE -> "(asin(x))′ = \\frac{1}{\\sqrt{1 − x^2}}"
        StepRule.ARCCOSINE -> "(acos(x))′ = −\\frac{1}{\\sqrt{1 − x^2}}"
        StepRule.ARCTANGENT -> "(atan(x))′ = \\frac{1}{1 + x^2}"
        StepRule.ABSOLUTE_VALUE -> "|x|′ = \\frac{x}{|x|}"
        StepRule.FUNDAMENTAL_THEOREM -> "(\\intab{a}{x}{f(t)}{t})′ = f(x)"
        StepRule.COMBINE_POWERS -> "x^a·x^b = x^{a + b}"
        StepRule.DIVIDE_BY_FRACTION -> "\\frac{1}{\\frac{a}{b}} = \\frac{b}{a}"
        StepRule.EXTEND_FRACTIONS -> "\\frac{a}{b} = \\frac{a·c}{b·c}"
        StepRule.TANGENT_AS_FRACTION -> "tan(u) = \\frac{sin(u)}{cos(u)}"
        StepRule.EXACT_TRIGONOMETRIC_VALUE -> "sin(\\frac{π}{6}) = \\frac{1}{2}\\text{,  }cos(\\frac{π}{3}) = \\frac{1}{2}\\text{,  }tan(\\frac{π}{4}) = 1"
        StepRule.LOGARITHM_OF_POWER -> "\\log{b}{b^n} = n"
        StepRule.EXPONENTIAL_OF_LOGARITHM -> "b^{\\log{b}{x}} = x"
        StepRule.ROOT_OF_FRACTION -> "\\sqrt{\\frac{a}{b}} = \\frac{\\sqrt{a}}{\\sqrt{b}}"
        StepRule.POLYNOMIAL_DIVISION -> "\\frac{P}{Q} = S + \\frac{R}{Q}\\text{  with  }P = S·Q + R"
        StepRule.CYCLIC_BY_PARTS -> "I = \\int{e^{px}sin(qx)}{x}\\text{:  by parts twice, then solve for }I"
        StepRule.HALF_ANGLE -> "sin^2(u) = \\frac{1 − cos(2u)}{2}\\text{,  }cos^2(u) = \\frac{1 + cos(2u)}{2}"
        StepRule.PYTHAGOREAN_IDENTITY -> "sin^2(x) + cos^2(x) = 1"
        StepRule.TRIG_ODD_POWER -> "sin^3(x) = sin(x)(1 − cos^2(x))"
        StepRule.RATIONALIZE -> "\\frac{1}{\\sqrt{a}} = \\frac{\\sqrt{a}}{\\sqrt{a}\\sqrt{a}} = \\frac{\\sqrt{a}}{a}"
        StepRule.LOG_COMBINE -> "\\log{b}{x} + \\log{b}{y} = \\log{b}{xy}\\text{,  }\\log{b}{x} − \\log{b}{y} = \\log{b}{\\frac{x}{y}}"
        StepRule.LOG_EQUAL -> "\\log{b}{x} = \\log{b}{y}\\text{  ⇔  }x = y"
        StepRule.PYTHAGOREAN -> "sin^2(u) + cos^2(u) = 1"
        StepRule.FACTOR_COMMON -> "a·c + b·c = c·(a + b)"
        StepRule.DOUBLE_ANGLE -> "sin(2u) = 2·sin(u)·cos(u)\\text{,  }cos(2u) = cos^2(u) − sin^2(u)"
        StepRule.AMPLITUDE_FORM -> "a·sin(u) + b·cos(u) = R·sin(u + φ)"
        StepRule.PERCENT_AS_FRACTION -> "p% = \\frac{p}{100}"
        StepRule.IMPROPER_FRACTION -> "w\\frac{a}{b} = \\frac{w·b + a}{b}"
        StepRule.MULTIPLY_FRACTIONS -> "\\frac{a}{b}·\\frac{c}{d} = \\frac{a·c}{b·d}"
        StepRule.SIMPLIFY_ROOT -> "\\sqrt{a²·b} = a·\\sqrt{b}"
        StepRule.REWRITE_AS_POWER -> "\\sqrt[n]{x} = x^{\\frac{1}{n}}, \\frac{1}{x^n} = x^{−n}"
        StepRule.CHAIN_RULE -> "(f(g(x)))′ = f′(u)·g′(x)\\text{  with  }u = g(x)"
        StepRule.POWER_AS_ROOT -> "x^{\\frac{1}{n}} = \\sqrt[n]{x}"
        StepRule.POWER_AS_FRACTION -> "x^{−n} = \\frac{1}{x^n}"
        StepRule.POWER_OF_POWER -> "(x^a)^b = x^{a·b}"
        StepRule.ROOT_OF_SQUARE -> "\\sqrt{x^2} = |x|"
        StepRule.POWER_OF_PRODUCT -> "(a·b)^n = a^n·b^n"
        StepRule.EXPAND -> "(a + b)·c = a·c + b·c"
        StepRule.EXPAND_POWER -> "(a + b)^2 = a^2 + 2ab + b^2"
        StepRule.COMBINE_FRACTIONS -> "\\frac{a}{c} + \\frac{b}{c} = \\frac{a + b}{c}"
        StepRule.CANCEL -> "\\frac{x}{x} = 1"
        StepRule.TANGENT_AS_QUOTIENT -> "\\frac{sin(x)}{cos(x)} = tan(x)"
        StepRule.CANCEL_COMMON_FACTOR -> "\\frac{a·c}{b·c} = \\frac{a}{b}"
        StepRule.IMAGINARY_ROOT -> "\\sqrt{−a} = i·\\sqrt{a}"
        StepRule.SUBTRACT_SAME -> "a − a = 0"
        StepRule.DISTRIBUTE -> "c·(a + b) = c·a + c·b"
        StepRule.INTEGRAL_CONSTANT -> "\\int{c}{x} = c·x"
        StepRule.INTEGRAL_SUM -> "\\int{f ± g}{x} = \\int{f}{x} ± \\int{g}{x}"
        StepRule.INTEGRAL_CONSTANT_FACTOR -> "\\int{c·f}{x} = c·\\int{f}{x}"
        StepRule.INTEGRAL_POWER -> "\\int{x^n}{x} = \\frac{x^{n + 1}}{n + 1}"
        StepRule.INTEGRAL_RECIPROCAL -> "\\int{\\frac{1}{x}}{x} = ln(|x|)"
        StepRule.INTEGRAL_EXPONENTIAL -> "\\int{e^x}{x} = e^x, \\int{a^x}{x} = \\frac{a^x}{ln(a)}"
        StepRule.INTEGRAL_SINE -> "\\int{sin(x)}{x} = −cos(x)"
        StepRule.INTEGRAL_COSINE -> "\\int{cos(x)}{x} = sin(x)"
        StepRule.INTEGRAL_TANGENT -> "\\int{tan(x)}{x} = −ln(|cos(x)|)"
        StepRule.INTEGRAL_LOGARITHM -> "\\int{ln(x)}{x} = x·ln(x) − x"
        StepRule.SUBSTITUTION -> "\\int{f(g(x))·g′(x)}{x} = \\int{f(u)}{u}"
        StepRule.BY_PARTS -> "\\int{u}{v} = u·v − \\int{v}{u}"
        StepRule.SPLIT_NUMERATOR -> "\\frac{px + q}{D} = \\frac{p}{2a}·\\frac{D′}{D} + \\frac{q − \\frac{pb}{2a}}{D}"
        StepRule.COMPLETE_SQUARE -> "ax^2 + bx + c = a((x + \\frac{b}{2a})^2 + k)"
        StepRule.PARTIAL_FRACTIONS -> "\\frac{P}{(x − a)(x − b)} = \\frac{A}{x − a} + \\frac{B}{x − b}"
        StepRule.ANTIDERIVATIVE -> "\\intab{a}{b}{f}{x} = \\eval{F}{a}{b}"
        StepRule.BOUNDS -> "\\eval{F}{a}{b} = F(b) − F(a)"
        StepRule.ADD_TO_BOTH_SIDES -> "a = b\\text{  ⇔  }a + c = b + c"
        StepRule.SUBTRACT_FROM_BOTH_SIDES -> "a = b\\text{  ⇔  }a − c = b − c"
        StepRule.MULTIPLY_BOTH_SIDES -> "a = b\\text{  ⇔  }a·c = b·c"
        StepRule.DIVIDE_BOTH_SIDES -> "a = b\\text{  ⇔  }\\frac{a}{c} = \\frac{b}{c}"
        StepRule.DIVIDE_BY_NEGATIVE -> "a < b\\text{  ⇔  }\\frac{a}{c} > \\frac{b}{c}\\text{  for  }c < 0"
        StepRule.MULTIPLY_BY_NEGATIVE -> "a < b\\text{  ⇔  }a·c > b·c\\text{  for  }c < 0"
        StepRule.INEQUALITY_SWAP -> "a < b\\text{  ⇔  }b > a"
        StepRule.INEQUALITY_ONE_SIDE -> "f < g\\text{  ⇔  }f − g < 0"
        StepRule.SWAP_SIDES -> "a = b\\text{  ⇔  }b = a"
        StepRule.SQUARE_ROOT_BOTH_SIDES -> "u^2 = c\\text{  ⇔  }u = \\sqrt{c}\\text{  or  }u = −\\sqrt{c}"
        StepRule.SQUARE_BOTH_SIDES -> "a = b\\text{  ⇒  }a^2 = b^2"
        StepRule.LIMIT_VANISH -> "\\frac{c}{x^n} → 0\\text{  for  }x → ±∞"
        StepRule.LIMIT_RECIPROCAL_SUBSTITUTION -> "u = \\frac{1}{x}\\text{:  }x → ∞\\text{  is  }u → 0"
        StepRule.DETERMINANT_2X2 -> "\\text{det} = a·d − b·c"
        StepRule.EIGEN_CHARACTERISTIC -> "\\text{det}(A − λ·I) = 0"
        StepRule.INVERSE_2X2 -> "A^{−1} = \\frac{1}{a·d − b·c}·\\text{(top: }d, −b\\text{; bottom: }−c, a\\text{)}"
        StepRule.MATRIX_MULTIPLY -> "\\text{entry} = \\text{row of }A · \\text{column of }B"
        StepRule.FACTOR_POLYNOMIAL -> "P(x) = (x − r)(x − s)…\\text{  where  }P(r) = 0"
        StepRule.IMPROPER_INTEGRAL -> "\\int{f}{x}\\text{ to }∞ = \\text{lim }(F(t) − F(a))"
        StepRule.LIMIT_EXPONENTIAL_FORM -> "\\text{lim }f^g = e^{\\text{lim }g·ln(f)}"
        StepRule.LIMIT_PRODUCT_AS_QUOTIENT -> "f·g = \\frac{f}{\\frac{1}{g}}"
        StepRule.LIMIT_DIFFERENCE_OF_SQUARES -> "(a + b)(a − b) = a² − b²"
        StepRule.LOG_SUM -> "\\text{log }a + \\text{log }b = \\text{log}(a·b)"
        StepRule.LHOPITAL -> "\\text{lim }\\frac{f}{g} = \\text{lim }\\frac{f′}{g′}"
        StepRule.ZERO_FRACTION -> "\\frac{a}{b} = 0\\text{  ⇔  }a = 0\\text{  (and  }b ≠ 0)"
        StepRule.ABSOLUTE_VALUES_EQUAL -> "|a| = |b|\\text{  ⇔  }a = b\\text{  or  }a = −b"
        StepRule.ABSOLUTE_VALUE_CASES -> "|a| = b\\text{  ⇔  }a = b\\text{  or  }a = −b"
        StepRule.EXPONENTIATE_BOTH_SIDES -> "\\log{b}{f} = c\\text{  ⇔  }f = b^c"
        StepRule.NEWTON_METHOD -> "x → x − \\frac{f(x)}{f′(x)}\\text{  until it settles}"
        StepRule.LOGARITHM_BOTH_SIDES -> "b^f = c\\text{  ⇔  }f = \\log{b}{c}"
        StepRule.NTH_ROOT_BOTH_SIDES -> "u^n = c\\text{  ⇔  }u = \\sqrt[n]{c}"
        StepRule.FACTOR_OUT -> "ax^2 + bx = x·(ax + b)"
        StepRule.ZERO_PRODUCT -> "a·b = 0\\text{  ⇔  }a = 0\\text{  or  }b = 0"
        StepRule.QUADRATIC_FORMULA -> "x = \\frac{−b ± \\sqrt{D}}{2a}\\text{  with  }D = b^2 − 4ac"
        StepRule.ROOT_OF_NUMBER, StepRule.IDENTITY, StepRule.SOLVE_FOR, StepRule.SUBSTITUTE_SOLUTION,
        StepRule.MULTIPLY_EQUATIONS, StepRule.ADD_EQUATIONS, StepRule.SUBTRACT_EQUATIONS, StepRule.INSERT_VALUE, StepRule.COLLECT_SOLUTION, StepRule.FACTOR_BY_ROOT, StepRule.NO_REAL_SOLUTION, StepRule.CONTRADICTION, StepRule.DETERMINANT_COFACTOR, StepRule.INVERSE_AUGMENT, StepRule.ROW_SWAP, StepRule.ROW_SCALE, StepRule.ROW_ADD, StepRule.INVERSE_RESULT, StepRule.SUBSTITUTE_RATIO, StepRule.ABSOLUTE_VALUE_SPLIT, StepRule.ABSOLUTE_VALUE_CASE, StepRule.INEQUALITY_SYSTEM_EACH, StepRule.INEQUALITY_SYSTEM_INTERSECT, StepRule.LOG_QUOTIENT, StepRule.TRIG_INEQUALITY_ISOLATE, StepRule.TRIG_INEQUALITY_PERIOD, StepRule.TRIG_INEQUALITY_SOLVE_X, StepRule.TRIG_INEQUALITY_BOUNDED, StepRule.LIMIT_CONJUGATE, StepRule.LIMIT_EXPONENT_INFINITE, StepRule.LIMIT_ABSOLUTE_VALUE, StepRule.ROW_REDUCE_BEGIN, StepRule.EIGEN_VECTOR, StepRule.EIGEN_VECTOR_RESULT, StepRule.EIGEN_RESULT, StepRule.MATRIX_SCALAR, StepRule.MATRIX_ADD, StepRule.MATRIX_SUBTRACT, StepRule.MATRIX_TRANSPOSE, StepRule.MATRIX_POWER, StepRule.INEQUALITY_ZEROS, StepRule.INEQUALITY_BREAKS, StepRule.INEQUALITY_CRITICAL, StepRule.INEQUALITY_RESULT, StepRule.ONE_SIDE, StepRule.TRIG_GENERAL_SOLUTION, StepRule.SUBSTITUTE_POWER, StepRule.BACK_SUBSTITUTE_POWER, StepRule.CHECK_SOLUTIONS, StepRule.LIMIT_SUBSTITUTE, StepRule.DIVIDE_BY_HIGHEST_POWER, StepRule.LIMIT_CONSTANT, StepRule.LIMIT_POLE, StepRule.LIMIT_LOGARITHM_ZERO, StepRule.LIMIT_EXPONENTIAL_RESULT, StepRule.LIMIT_GROWS, StepRule.LIMIT_RECIPROCAL,
        StepRule.INTEGRAL_TABLE, StepRule.EXPAND_INTEGRAND, StepRule.BACK_SUBSTITUTION,
        StepRule.MULTIPLY_NUMBERS, StepRule.DIVIDE_NUMBERS, StepRule.REDUCE_FRACTION, StepRule.NUMBER_POWER,
        StepRule.ADD_NUMBERS, StepRule.SUBTRACT_NUMBERS, StepRule.ADD_AND_SUBTRACT_NUMBERS, StepRule.COMBINE_LIKE_TERMS,
        StepRule.SIMPLIFY, StepRule.SUBSTITUTE, StepRule.EVALUATE -> null
    }
