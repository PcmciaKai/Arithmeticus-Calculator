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
import com.vortessence.calculator.core.math.steps.SolutionStep
import com.vortessence.calculator.core.math.steps.StepChange
import com.vortessence.calculator.core.math.steps.StepRule
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
fun StepsSheet(state: StepsState, onDismiss: () -> Unit) {
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
                            if (row.to == row.from) StepItem(solution.steps, row.from)
                            else CalculationGroup(solution.steps, row.from, row.to)
                        }
                    }
                }
            }
        }
    }
}

/** A step, or with [to] after [from] a run of calculation steps that can be folded away. */
private class StepRow(val from: Int, val to: Int)

/** Runs of at least this many plain calculations (adding, multiplying … numbers) are folded. */
private const val MIN_FOLDED_STEPS = 3

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
        StepRule.LIMIT_SUBSTITUTE -> R.string.step_limit_substitute
        StepRule.DIVIDE_BY_HIGHEST_POWER -> R.string.step_divide_by_highest_power
        StepRule.LIMIT_VANISH -> R.string.step_limit_vanish
        StepRule.LIMIT_CONSTANT -> R.string.step_limit_constant
        StepRule.LIMIT_GROWS -> R.string.step_limit_grows
        StepRule.LIMIT_RECIPROCAL -> R.string.step_limit_reciprocal
        StepRule.LHOPITAL -> R.string.step_lhopital
        StepRule.ZERO_FRACTION -> R.string.step_zero_fraction
        StepRule.ABSOLUTE_VALUE_CASES -> R.string.step_absolute_value_cases
        StepRule.EXPONENTIATE_BOTH_SIDES -> R.string.step_exponentiate_both_sides
        StepRule.CHECK_SOLUTIONS -> R.string.step_check_solutions
        StepRule.SUBSTITUTE_POWER -> R.string.step_substitute_power
        StepRule.BACK_SUBSTITUTE_POWER -> R.string.step_back_substitute_power
        StepRule.ONE_SIDE -> R.string.step_one_side
        StepRule.NEWTON_METHOD -> R.string.step_newton_method
        StepRule.NO_REAL_SOLUTION -> R.string.step_no_real_solution
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
        StepRule.SWAP_SIDES -> "a = b\\text{  ⇔  }b = a"
        StepRule.SQUARE_ROOT_BOTH_SIDES -> "u^2 = c\\text{  ⇔  }u = \\sqrt{c}\\text{  or  }u = −\\sqrt{c}"
        StepRule.SQUARE_BOTH_SIDES -> "a = b\\text{  ⇒  }a^2 = b^2"
        StepRule.LIMIT_VANISH -> "\\frac{c}{x^n} → 0\\text{  for  }x → ±∞"
        StepRule.LHOPITAL -> "\\text{lim }\\frac{f}{g} = \\text{lim }\\frac{f′}{g′}"
        StepRule.ZERO_FRACTION -> "\\frac{a}{b} = 0\\text{  ⇔  }a = 0\\text{  (and  }b ≠ 0)"
        StepRule.ABSOLUTE_VALUE_CASES -> "|a| = b\\text{  ⇔  }a = b\\text{  or  }a = −b"
        StepRule.EXPONENTIATE_BOTH_SIDES -> "\\log{b}{f} = c\\text{  ⇔  }f = b^c"
        StepRule.NEWTON_METHOD -> "x → x − \\frac{f(x)}{f′(x)}\\text{  until it settles}"
        StepRule.LOGARITHM_BOTH_SIDES -> "b^f = c\\text{  ⇔  }f = \\log{b}{c}"
        StepRule.NTH_ROOT_BOTH_SIDES -> "u^n = c\\text{  ⇔  }u = \\sqrt[n]{c}"
        StepRule.FACTOR_OUT -> "ax^2 + bx = x·(ax + b)"
        StepRule.ZERO_PRODUCT -> "a·b = 0\\text{  ⇔  }a = 0\\text{  or  }b = 0"
        StepRule.QUADRATIC_FORMULA -> "x = \\frac{−b ± \\sqrt{D}}{2a}\\text{  with  }D = b^2 − 4ac"
        StepRule.ROOT_OF_NUMBER, StepRule.IDENTITY, StepRule.SOLVE_FOR, StepRule.SUBSTITUTE_SOLUTION,
        StepRule.MULTIPLY_EQUATIONS, StepRule.ADD_EQUATIONS, StepRule.SUBTRACT_EQUATIONS, StepRule.INSERT_VALUE, StepRule.COLLECT_SOLUTION, StepRule.FACTOR_BY_ROOT, StepRule.NO_REAL_SOLUTION, StepRule.ONE_SIDE, StepRule.TRIG_GENERAL_SOLUTION, StepRule.SUBSTITUTE_POWER, StepRule.BACK_SUBSTITUTE_POWER, StepRule.CHECK_SOLUTIONS, StepRule.LIMIT_SUBSTITUTE, StepRule.DIVIDE_BY_HIGHEST_POWER, StepRule.LIMIT_CONSTANT, StepRule.LIMIT_GROWS, StepRule.LIMIT_RECIPROCAL,
        StepRule.INTEGRAL_TABLE, StepRule.EXPAND_INTEGRAND, StepRule.BACK_SUBSTITUTION,
        StepRule.MULTIPLY_NUMBERS, StepRule.DIVIDE_NUMBERS, StepRule.REDUCE_FRACTION, StepRule.NUMBER_POWER,
        StepRule.ADD_NUMBERS, StepRule.SUBTRACT_NUMBERS, StepRule.ADD_AND_SUBTRACT_NUMBERS, StepRule.COMBINE_LIKE_TERMS,
        StepRule.SIMPLIFY, StepRule.SUBSTITUTE, StepRule.EVALUATE -> null
    }
