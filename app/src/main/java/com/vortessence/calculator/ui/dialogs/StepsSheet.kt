package com.vortessence.calculator.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.ui.math.MathExpressionView
import com.vortessence.calculator.ui.math.highlightColor
import com.vortessence.calculator.viewmodel.StepsState

/**
 * The step-by-step solution of a calculation: the problem, then for each step an arrow in the
 * step's color with the rule (in general and applied to the part it changes) and the
 * expression it leads to, and finally the result. In each expression, the part the last step
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
                        ScrollingMath(step.expression, fontSize = 24.sp)
                    }
                    item {
                        Text(
                            "Result",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
                        )
                        ScrollingMath(solution.result, fontSize = 26.sp)
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
            if (step.chainRule) RuleText("Chain rule", "(f(g))′ = f′(g)·g′")
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
        StepRule.ADD_NUMBERS -> "Add numbers"
        StepRule.COMBINE_POWERS -> "Add the exponents"
        StepRule.CANCEL -> "Cancel"
        StepRule.DISTRIBUTE -> "Multiply out"
        StepRule.COMBINE_LIKE_TERMS -> "Combine like terms"
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
        StepRule.CANCEL -> "x/x = 1"
        StepRule.DISTRIBUTE -> "c·(a + b) = c·a + c·b"
        StepRule.MULTIPLY_NUMBERS, StepRule.ADD_NUMBERS, StepRule.COMBINE_LIKE_TERMS,
        StepRule.SIMPLIFY, StepRule.SUBSTITUTE, StepRule.EVALUATE -> null
    }
