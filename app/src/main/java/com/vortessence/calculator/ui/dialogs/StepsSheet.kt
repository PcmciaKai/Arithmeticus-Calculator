package com.vortessence.calculator.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            Text("Steps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val solution = state.solution
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                solution == null -> Text(
                    "There are no steps for this calculation.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                ) {
                    itemsIndexed(solution.steps) { index, step ->
                        if (index > 0) StepArrow(step, highlightColor(index))
                        if (index == solution.steps.lastIndex && index > 0) {
                            ResultLine(step.expression)
                        } else {
                            ScrollingMath(step.expression, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A downward arrow in [color], next to what the step does. */
@Composable
private fun StepArrow(step: SolutionStep, color: Color) {
    Row(modifier = Modifier.height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
        Canvas(modifier = Modifier.width(24.dp).fillMaxHeight()) {
            val x = size.width / 2
            val stroke = 2.dp.toPx()
            val head = 6.dp.toPx()
            val bottom = size.height - stroke
            drawLine(color, Offset(x, 0f), Offset(x, bottom), stroke, StrokeCap.Round)
            drawLine(color, Offset(x - head, bottom - head), Offset(x, bottom), stroke, StrokeCap.Round)
            drawLine(color, Offset(x + head, bottom - head), Offset(x, bottom), stroke, StrokeCap.Round)
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            step.rule?.let { RuleText(it.description, it.formula) }
            if (step.chainRule) {
                if (step.rule?.isIntegral == true) RuleText("Linear argument", "\\int{f(ax + b)}{x} = \\frac{F(ax + b)}{a}")
                else RuleText("Chain rule", "(f(g))′ = f′(g)·g′")
            }
            step.notes.forEach { NoteLine(it) }
            step.change?.let { ChangeLine(it) }
        }
    }
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
            Text("Result", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
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

private val StepRule.description: String
    get() = when (this) {
        StepRule.CONSTANT -> "The derivative of a constant is zero"
        StepRule.VARIABLE -> "The derivative of the variable is one"
        StepRule.SUM -> "Differentiate term by term"
        StepRule.CONSTANT_FACTOR -> "Factor out the constant"
        StepRule.PRODUCT -> "Apply the product rule"
        StepRule.QUOTIENT -> "Apply the quotient rule"
        StepRule.POWER -> "Apply the power rule"
        StepRule.EXPONENTIAL -> "Differentiate the exponential function"
        StepRule.GENERAL_POWER -> "Write the power with base e"
        StepRule.LOGARITHM -> "Differentiate the logarithm"
        StepRule.CHANGE_OF_BASE -> "Change the base of the logarithm"
        StepRule.SINE -> "Differentiate the sine"
        StepRule.COSINE -> "Differentiate the cosine"
        StepRule.TANGENT -> "Differentiate the tangent"
        StepRule.ARCSINE -> "Differentiate the arcsine"
        StepRule.ARCCOSINE -> "Differentiate the arccosine"
        StepRule.ARCTANGENT -> "Differentiate the arctangent"
        StepRule.ABSOLUTE_VALUE -> "Differentiate the absolute value"
        StepRule.FUNDAMENTAL_THEOREM -> "Apply the fundamental theorem of calculus"
        StepRule.MULTIPLY_NUMBERS -> "Multiply numbers"
        StepRule.NUMBER_POWER -> "Calculate the power"
        StepRule.DIVIDE_BY_FRACTION -> "Divide by the fraction"
        StepRule.REWRITE_AS_POWER -> "Write as a power"
        StepRule.CHAIN_RULE -> "Apply the chain rule"
        StepRule.POWER_AS_ROOT -> "Write the power as a root"
        StepRule.POWER_AS_FRACTION -> "Write the power as a fraction"
        StepRule.POWER_OF_POWER -> "Multiply the exponents"
        StepRule.POWER_OF_PRODUCT -> "Raise each factor to the power"
        StepRule.EXPAND -> "Multiply out"
        StepRule.EXPAND_POWER -> "Multiply out the power"
        StepRule.COMBINE_FRACTIONS -> "Combine the fractions"
        StepRule.ADD_NUMBERS -> "Add numbers"
        StepRule.COMBINE_POWERS -> "Add the exponents"
        StepRule.CANCEL -> "Cancel"
        StepRule.DISTRIBUTE -> "Multiply out"
        StepRule.COMBINE_LIKE_TERMS -> "Combine like terms"
        StepRule.INTEGRAL_CONSTANT -> "Integrate the constant"
        StepRule.INTEGRAL_SUM -> "Integrate term by term"
        StepRule.INTEGRAL_CONSTANT_FACTOR -> "Factor out the constant"
        StepRule.INTEGRAL_POWER -> "Apply the power rule"
        StepRule.INTEGRAL_RECIPROCAL -> "Integrate 1/x"
        StepRule.INTEGRAL_EXPONENTIAL -> "Integrate the exponential function"
        StepRule.INTEGRAL_SINE -> "Integrate the sine"
        StepRule.INTEGRAL_COSINE -> "Integrate the cosine"
        StepRule.INTEGRAL_TANGENT -> "Integrate the tangent"
        StepRule.INTEGRAL_LOGARITHM -> "Integrate the logarithm"
        StepRule.INTEGRAL_TABLE -> "Use a standard integral"
        StepRule.EXPAND_INTEGRAND -> "Multiply out the integrand"
        StepRule.SUBSTITUTION -> "Substitute"
        StepRule.BACK_SUBSTITUTION -> "Substitute back"
        StepRule.BY_PARTS -> "Integrate by parts"
        StepRule.ANTIDERIVATIVE -> "Find an antiderivative F"
        StepRule.BOUNDS -> "Insert the bounds"
        StepRule.SIMPLIFY -> "Simplify"
        StepRule.SUBSTITUTE -> "Insert the point"
        StepRule.EVALUATE -> "Calculate"
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
        StepRule.ANTIDERIVATIVE -> "\\intab{a}{b}{f}{x} = \\eval{F}{a}{b}"
        StepRule.BOUNDS -> "\\eval{F}{a}{b} = F(b) − F(a)"
        StepRule.INTEGRAL_TABLE, StepRule.EXPAND_INTEGRAND, StepRule.BACK_SUBSTITUTION,
        StepRule.MULTIPLY_NUMBERS, StepRule.NUMBER_POWER, StepRule.ADD_NUMBERS, StepRule.COMBINE_LIKE_TERMS,
        StepRule.SIMPLIFY, StepRule.SUBSTITUTE, StepRule.EVALUATE -> null
    }
