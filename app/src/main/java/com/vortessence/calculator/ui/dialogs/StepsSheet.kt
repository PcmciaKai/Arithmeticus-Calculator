package com.vortessence.calculator.ui.dialogs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.steps.SolutionStep
import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.ui.math.MathExpressionView
import com.vortessence.calculator.viewmodel.StepsState

/**
 * The step-by-step solution of a calculation: the problem, then one line per step with the
 * rule that leads to it, ending with the result.
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
            val steps = state.steps
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                steps == null -> Text(
                    "There are no steps for this calculation.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(steps) { index, step -> StepRow(step, isFirst = index == 0) }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: SolutionStep, isFirst: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        step.rule?.let { rule ->
            Text(
                text = if (step.chainRule) "${rule.label} and chain rule" else rule.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        // Long expressions scroll instead of being squeezed
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isFirst) {
                Text(
                    text = "= ",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            MathExpressionView(
                mathString = step.expression,
                fontSize = 22.sp,
                textColor = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

private val StepRule.label: String
    get() = when (this) {
        StepRule.CONSTANT -> "Derivative of a constant"
        StepRule.VARIABLE -> "Derivative of the variable"
        StepRule.SUM -> "Sum rule"
        StepRule.CONSTANT_FACTOR -> "Constant factor rule"
        StepRule.PRODUCT -> "Product rule"
        StepRule.QUOTIENT -> "Quotient rule"
        StepRule.POWER -> "Power rule"
        StepRule.EXPONENTIAL -> "Exponential rule"
        StepRule.GENERAL_POWER -> "Power with a variable exponent: u^w = e^(w·ln u)"
        StepRule.LOGARITHM -> "Logarithm rule"
        StepRule.CHANGE_OF_BASE -> "Change of base"
        StepRule.SINE -> "Derivative of sin"
        StepRule.COSINE -> "Derivative of cos"
        StepRule.TANGENT -> "Derivative of tan"
        StepRule.ARCSINE -> "Derivative of asin"
        StepRule.ARCCOSINE -> "Derivative of acos"
        StepRule.ARCTANGENT -> "Derivative of atan"
        StepRule.ABSOLUTE_VALUE -> "Derivative of the absolute value"
        StepRule.FUNDAMENTAL_THEOREM -> "Fundamental theorem of calculus"
        StepRule.SIMPLIFY -> "Simplify"
        StepRule.SUBSTITUTE -> "Insert the point"
        StepRule.EVALUATE -> "Calculate"
    }
