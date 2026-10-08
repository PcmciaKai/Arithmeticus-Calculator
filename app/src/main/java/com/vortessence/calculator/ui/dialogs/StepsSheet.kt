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
                if (step.rule?.isIntegral == true) RuleText("Linear argument", "∫ f(ax + b) dx = F(ax + b)/a")
                else RuleText("Chain rule", "(f(g))′ = f′(g)·g′")
            }
            step.notes.forEach { NoteLine(it) }
            step.change?.let { ChangeLine(it) }
        }
    }
}

@Composable
private fun RuleText(description: String, formula: String?) {
    Text(
        text = if (formula == null) description else "$description: $formula",
        fontSize = 14.sp,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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

/** The rule in general, as a formula; null where the description says it all. */
private val StepRule.formula: String?
    get() = when (this) {
        StepRule.CONSTANT -> "c′ = 0"
        StepRule.VARIABLE -> "x′ = 1"
        StepRule.SUM -> "(f ± g)′ = f′ ± g′"
        StepRule.CONSTANT_FACTOR -> "(c·f)′ = c·f′"
        StepRule.PRODUCT -> "(f·g)′ = f′·g + f·g′"
        StepRule.QUOTIENT -> "(f/g)′ = (f′·g − f·g′)/g²"
        StepRule.POWER -> "(xⁿ)′ = n·xⁿ⁻¹"
        StepRule.EXPONENTIAL -> "(aˣ)′ = aˣ·ln(a), (eˣ)′ = eˣ"
        StepRule.GENERAL_POWER -> "(fᵍ)′ = (e^(g·ln f))′ = fᵍ·(g·ln f)′"
        StepRule.LOGARITHM -> "(ln x)′ = 1/x, (logₐ x)′ = 1/(x·ln a)"
        StepRule.CHANGE_OF_BASE -> "log_f(g) = ln g/ln f"
        StepRule.SINE -> "(sin x)′ = cos x"
        StepRule.COSINE -> "(cos x)′ = −sin x"
        StepRule.TANGENT -> "(tan x)′ = 1/cos²x"
        StepRule.ARCSINE -> "(asin x)′ = 1/√(1 − x²)"
        StepRule.ARCCOSINE -> "(acos x)′ = −1/√(1 − x²)"
        StepRule.ARCTANGENT -> "(atan x)′ = 1/(1 + x²)"
        StepRule.ABSOLUTE_VALUE -> "|x|′ = x/|x|"
        StepRule.FUNDAMENTAL_THEOREM -> "(∫ₐˣ f(t) dt)′ = f(x)"
        StepRule.COMBINE_POWERS -> "xᵃ·xᵇ = xᵃ⁺ᵇ"
        StepRule.DIVIDE_BY_FRACTION -> "1/(a/b) = b/a"
        StepRule.REWRITE_AS_POWER -> "ⁿ√x = x^(1/n), 1/xⁿ = x⁻ⁿ"
        StepRule.CHAIN_RULE -> "(f(g(x)))′ = f′(u)·g′(x) with u = g(x)"
        StepRule.POWER_AS_ROOT -> "x^(1/n) = ⁿ√x"
        StepRule.POWER_AS_FRACTION -> "x⁻ⁿ = 1/xⁿ"
        StepRule.POWER_OF_POWER -> "(xᵃ)ᵇ = xᵃᵇ"
        StepRule.POWER_OF_PRODUCT -> "(a·b)ⁿ = aⁿ·bⁿ"
        StepRule.EXPAND -> "(a + b)·c = a·c + b·c"
        StepRule.EXPAND_POWER -> "(a + b)² = a² + 2ab + b²"
        StepRule.COMBINE_FRACTIONS -> "a/c + b/c = (a + b)/c"
        StepRule.CANCEL -> "x/x = 1"
        StepRule.DISTRIBUTE -> "c·(a + b) = c·a + c·b"
        StepRule.INTEGRAL_CONSTANT -> "∫ c dx = c·x"
        StepRule.INTEGRAL_SUM -> "∫ (f ± g) dx = ∫ f dx ± ∫ g dx"
        StepRule.INTEGRAL_CONSTANT_FACTOR -> "∫ c·f dx = c·∫ f dx"
        StepRule.INTEGRAL_POWER -> "∫ xⁿ dx = xⁿ⁺¹/(n + 1)"
        StepRule.INTEGRAL_RECIPROCAL -> "∫ 1/x dx = ln|x|"
        StepRule.INTEGRAL_EXPONENTIAL -> "∫ eˣ dx = eˣ, ∫ aˣ dx = aˣ/ln(a)"
        StepRule.INTEGRAL_SINE -> "∫ sin x dx = −cos x"
        StepRule.INTEGRAL_COSINE -> "∫ cos x dx = sin x"
        StepRule.INTEGRAL_TANGENT -> "∫ tan x dx = −ln|cos x|"
        StepRule.INTEGRAL_LOGARITHM -> "∫ ln x dx = x·ln x − x"
        StepRule.SUBSTITUTION -> "∫ f(g(x))·g′(x) dx = ∫ f(u) du"
        StepRule.BY_PARTS -> "∫ u dv = u·v − ∫ v du"
        StepRule.ANTIDERIVATIVE -> "∫ₐᵇ f dx = [F]ₐᵇ"
        StepRule.BOUNDS -> "[F]ₐᵇ = F(b) − F(a)"
        StepRule.INTEGRAL_TABLE, StepRule.EXPAND_INTEGRAND, StepRule.BACK_SUBSTITUTION,
        StepRule.MULTIPLY_NUMBERS, StepRule.NUMBER_POWER, StepRule.ADD_NUMBERS, StepRule.COMBINE_LIKE_TERMS,
        StepRule.SIMPLIFY, StepRule.SUBSTITUTE, StepRule.EVALUATE -> null
    }
