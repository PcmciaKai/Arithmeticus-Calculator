package com.vortessence.calculator.ui.screen

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vortessence.calculator.core.math.CalculationResult
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.ui.dialogs.ConstantsDialog
import com.vortessence.calculator.ui.dialogs.UnitsDialog
import com.vortessence.calculator.ui.graph.GraphPlotterView
import com.vortessence.calculator.ui.keypad.CalculatorKeypad
import com.vortessence.calculator.ui.math.MathEditor
import com.vortessence.calculator.ui.math.MathExpressionView
import androidx.compose.ui.tooling.preview.Preview
import com.vortessence.calculator.core.math.expressions.Value
import com.vortessence.calculator.ui.dialogs.SettingsDialog
import com.vortessence.calculator.ui.theme.AccentRed
import com.vortessence.calculator.ui.theme.ArchimedesTheme
import com.vortessence.calculator.viewmodel.CalculatorViewModel
import java.util.Locale

@Composable
fun CalculatorScreen(
    viewModel: CalculatorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isGraphOpen) {
        GraphPlotterView(
            function = uiState.plotFunction,
            expression = uiState.expression,
            isLoading = uiState.isPlotLoading,
            modifier = Modifier.fillMaxSize(),
            onClose = { viewModel.closeGraph() }
        )
        return
    }

    // The chosen answer form stays selected while the live result updates during typing
    var answerForm by rememberSaveable { mutableStateOf(AnswerForm.EXACT) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // History of committed calculations (scrollable, newest at the bottom)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.history, key = { it.id }) { entry ->
                    SwipeToDeleteBox(
                        onDelete = { viewModel.deleteHistoryEntry(entry.id) },
                        modifier = Modifier.animateItem()
                    ) {
                        HistoryItemCard(
                            item = entry.calculation,
                            onInputClick = { viewModel.selectHistoryItem(entry.calculation) },
                            onResultClick = { viewModel.useResultAsInput(entry.calculation) }
                        )
                    }
                }
            }

            // Current input line with its live result
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
                    MathEditor(
                        expression = uiState.expression,
                        cursorPath = uiState.cursorPath,
                        selectionRange = uiState.selectionRange,
                        fontSize = 32.sp,
                        onPlaceCursor = { caret -> viewModel.setCursorPath(caret) },
                        onSelectToken = { path, index -> viewModel.selectTokenAt(path, index) },
                        onBeginHandleDrag = { isStart -> viewModel.beginHandleDrag(isStart) },
                        onExtendSelection = { caret -> viewModel.extendSelection(caret) }
                    )

                    LiveResultView(
                        result = uiState.result,
                        showError = uiState.showResultError,
                        answerForm = answerForm,
                        onAnswerFormChange = { answerForm = it }
                    )
                }
            }

            // Keypad with swipe navigation (Other, Main, Advanced)
            CalculatorKeypad(
                angleUnit = uiState.angleUnit,
                onKeyAction = { action -> viewModel.onKeyAction(action) }
            )
        }
    }

    // Units Dialog
    if (uiState.showUnitsDialog) {
        UnitsDialog(
            onDismiss = { viewModel.dismissUnitsDialog() },
            onUnitSelected = { symbol -> viewModel.insertUnit(symbol) }
        )
    }

    // Constants Dialog
    if (uiState.showConstantsDialog) {
        ConstantsDialog(
            onDismiss = { viewModel.dismissConstantsDialog() },
            onConstantSelected = { symbol -> viewModel.insertConstant(symbol) }
        )
    }

    // Settings Dialog
    if (uiState.showSettingsDialog) {
        SettingsDialog(
            mode = uiState.mode,
            angleUnit = uiState.angleUnit,
            onToggleMode = { viewModel.toggleMode() },
            onToggleAngleUnit = { viewModel.toggleAngleUnit() },
            onDismiss = { viewModel.dismissSettingsDialog() }
        )
    }
}

enum class AnswerForm { EXACT, DECIMAL, SCIENTIFIC, MIXED }

/** Fraction of an item's width it must be swiped to be deleted. */
private const val SWIPE_TO_DELETE_THRESHOLD = 0.5f

/**
 * The result of the line being typed. Incomplete input (e.g. "2 +") shows nothing rather than
 * an error, unless the user tried to commit it with Enter.
 */
@Composable
private fun LiveResultView(
    result: CalculationResult?,
    showError: Boolean,
    answerForm: AnswerForm,
    onAnswerFormChange: (AnswerForm) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        if (result == null) return@Box
        if (!result.isSuccess) {
            val isIncomplete = result.issues.all { it == MathIssue.SYNTAX_ERROR }
            if (showError || !isIncomplete) {
                Text(
                    text = result.issues.firstOrNull()?.userMessage ?: "Calculation Error",
                    color = AccentRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            return@Box
        }

        val rational = (result.expression as? Value)?.rational
        val hasMixedForm = rational != null && rational.isFractional && rational.numerator.abs() > rational.denominator
        val hasNumericForms = result.numericValue.isFinite()
        // Fall back to the exact form if the chosen form does not apply to this result
        val form = when {
            answerForm == AnswerForm.MIXED && !hasMixedForm -> AnswerForm.EXACT
            answerForm != AnswerForm.EXACT && !hasNumericForms -> AnswerForm.EXACT
            else -> answerForm
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (hasNumericForms) {
                AnswerPill("Exact", form == AnswerForm.EXACT) { onAnswerFormChange(AnswerForm.EXACT) }
                AnswerPill("Decimal", form == AnswerForm.DECIMAL) { onAnswerFormChange(AnswerForm.DECIMAL) }
                AnswerPill("Sci", form == AnswerForm.SCIENTIFIC) { onAnswerFormChange(AnswerForm.SCIENTIFIC) }
                if (hasMixedForm) {
                    AnswerPill("Mixed", form == AnswerForm.MIXED) { onAnswerFormChange(AnswerForm.MIXED) }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "= ",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.primary
                )
                when (form) {
                    AnswerForm.EXACT -> MathExpressionView(
                        mathString = result.result,
                        fontSize = 24.sp,
                        textColor = MaterialTheme.colorScheme.onBackground
                    )
                    AnswerForm.DECIMAL -> ResultText(String.format(Locale.US, "%.10g", result.numericValue))
                    AnswerForm.SCIENTIFIC -> ResultText(String.format(Locale.US, "%.8e", result.numericValue))
                    AnswerForm.MIXED -> {
                        val r = rational!!
                        val whole = r.numerator / r.denominator
                        val rem = (r.numerator % r.denominator).abs()
                        ResultText("$whole  $rem/${r.denominator}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultText(text: String) {
    Text(
        text = text,
        fontSize = 24.sp,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun AnswerPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** Swiping the content away in either direction deletes it; a red "delete" backdrop is revealed. */
@Composable
private fun SwipeToDeleteBox(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Require dragging across half the item's width (the default is only 56dp), so that
    // entries are not deleted by accident while scrolling or tapping
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * SWIPE_TO_DELETE_THRESHOLD }
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        onDismiss = { onDelete() },
        backgroundContent = {
            // Only reveal the delete backdrop while the item is actually being swiped
            val direction = state.dismissDirection
            if (direction == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentRed.copy(alpha = 0.85f))
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onError)
            }
        }
    ) {
        // The history cards are translucent; give them an opaque base so the backdrop
        // cannot shine through while swiping
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
        ) {
            content()
        }
    }
}

@Composable
private fun HistoryItemCard(
    item: CalculationResult,
    onInputClick: () -> Unit,
    onResultClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Input expression
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onInputClick)
            ) {
                MathExpressionView(
                    mathString = item.input,
                    fontSize = 20.sp,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Result
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onResultClick),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "= ",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MathExpressionView(
                        mathString = item.result,
                        fontSize = 22.sp,
                        textColor = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CalculatorScreenPreview() {
    ArchimedesTheme(darkTheme = false) {
        CalculatorScreen(viewModel = remember { CalculatorViewModel(Application()) })
    }
}

@Preview(showBackground = true)
@Composable
fun CalculatorScreenDarkPreview() {
    ArchimedesTheme(darkTheme = true) {
        CalculatorScreen(viewModel = remember { CalculatorViewModel(Application()) })
    }
}
