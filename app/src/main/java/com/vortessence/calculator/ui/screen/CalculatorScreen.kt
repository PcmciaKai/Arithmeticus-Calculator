package com.vortessence.calculator.ui.screen

import android.app.Application
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vortessence.calculator.core.math.CalculationResult
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.ResultForm
import com.vortessence.calculator.core.math.type.ResultFormatter
import com.vortessence.calculator.ui.dialogs.ConstantsDialog
import com.vortessence.calculator.ui.dialogs.FormulasDialog
import com.vortessence.calculator.ui.dialogs.MatrixDialog
import com.vortessence.calculator.ui.dialogs.UnitsDialog
import com.vortessence.calculator.ui.graph.GraphPlotterView
import com.vortessence.calculator.ui.keypad.CalculatorKeypad
import com.vortessence.calculator.ui.math.LocalDigitGrouping
import com.vortessence.calculator.ui.math.MathEditor
import com.vortessence.calculator.ui.math.MathEditorActions
import com.vortessence.calculator.ui.math.MathExpressionView
import com.vortessence.calculator.ui.math.rememberMathClipboard
import androidx.compose.ui.tooling.preview.Preview
import com.vortessence.calculator.ui.dialogs.SettingsDialog
import com.vortessence.calculator.ui.theme.AccentRed
import com.vortessence.calculator.ui.theme.CalculatorTheme
import com.vortessence.calculator.viewmodel.CalculatorUiState
import com.vortessence.calculator.viewmodel.CalculatorViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun CalculatorScreen(
    viewModel: CalculatorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Digit grouping applies to every number shown: input, results, history and graph
    CompositionLocalProvider(LocalDigitGrouping provides uiState.digitGrouping) {
        CalculatorScreenContent(viewModel, uiState)
    }
}

@Composable
private fun CalculatorScreenContent(viewModel: CalculatorViewModel, uiState: CalculatorUiState) {
    if (uiState.isGraphOpen) {
        GraphPlotterView(
            curve = uiState.plotCurve,
            expression = uiState.expression,
            isLoading = uiState.isPlotLoading,
            modifier = Modifier.fillMaxSize(),
            onClose = { viewModel.closeGraph() }
        )
        return
    }

    // Bring every newly pushed entry into view, even when the history was scrolled up
    // (e.g. after tapping an older entry to reuse it)
    val historyListState = rememberLazyListState()
    val newestHistoryId = uiState.history.firstOrNull()?.id
    LaunchedEffect(newestHistoryId) {
        if (newestHistoryId != null) historyListState.animateScrollToItem(0)
    }

    // Pressing i turned complex numbers on: say so, and where to turn them off again
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.showComplexNotice) {
        if (uiState.showComplexNotice) {
            // Cleared afterwards: clearing it first would restart this effect and cancel the message
            snackbarHostState.showSnackbar(
                message = "Complex numbers are now on. You can switch them off in the settings.",
                withDismissAction = true
            )
            viewModel.dismissComplexNotice()
        }
    }

    val clipboard = rememberMathClipboard()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) }
    }
    fun copy(math: MathString) {
        clipboard.copy(math)
        // Android 13 and later confirm copies themselves
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) showMessage("Copied")
    }
    // Long press on a history entry or the live result copies it
    fun copyWithFeedback(math: MathString) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        copy(math)
    }
    val editorActions = MathEditorActions(
        onCopy = {
            viewModel.selectedMath()?.let(::copy)
            // Like text fields: copying ends the selection, the cursor stays at its end
            viewModel.setCursorPath(uiState.cursorPath)
        },
        onCut = { viewModel.cutSelection()?.let(::copy) },
        onPaste = {
            val content = clipboard.paste()
            when {
                content == null -> Unit
                content.math == null -> showMessage("Can't paste \"${content.text.ellipsized(24)}\" as math")
                !viewModel.paste(content.math) -> showMessage("Several lines can't be pasted into a fraction, root, …")
            }
        },
        onSelectAll = { viewModel.selectAll() },
        canPaste = { clipboard.hasText() }
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                state = historyListState,
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
                            form = entry.form,
                            onInputClick = { viewModel.selectHistoryItem(entry.calculation) },
                            onResultClick = { viewModel.useResultAsInput(entry.calculation) },
                            onCopy = ::copyWithFeedback
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
                        onExtendSelection = { caret -> viewModel.extendSelection(caret) },
                        actions = editorActions
                    )

                    LiveResultView(
                        result = uiState.result,
                        showError = uiState.showResultError,
                        form = uiState.resultForm,
                        onFormChange = { viewModel.setResultForm(it) },
                        onCopy = ::copyWithFeedback
                    )
                }
            }

            // Keypad with swipe navigation (Other, Main, Advanced)
            CalculatorKeypad(
                isLineEmpty = uiState.expression.isEmpty,
                onKeyAction = { action -> viewModel.onKeyAction(action) }
            )
        }
    }

    // Units Dialog
    if (uiState.showUnitsDialog) {
        UnitsDialog(
            onDismiss = { viewModel.dismissUnitsDialog() },
            onUnitSelected = { symbol -> viewModel.insertUnit(symbol) },
            selectedCategory = uiState.unitCategory,
            onCategorySelected = { viewModel.selectUnitCategory(it) },
            scrollToFamily = uiState.unitFamilyToShow
        )
    }

    // Constants Dialog
    if (uiState.showConstantsDialog) {
        ConstantsDialog(
            onDismiss = { viewModel.dismissConstantsDialog() },
            onConstantSelected = { symbol -> viewModel.insertConstant(symbol) }
        )
    }

    if (uiState.showFormulasDialog) {
        FormulasDialog(
            onDismiss = { viewModel.dismissFormulasDialog() },
            onFormulaSelected = { template -> viewModel.insertFormula(template) }
        )
    }

    // Matrix size picker
    if (uiState.showMatrixDialog) {
        MatrixDialog(
            initialRows = uiState.matrixRows,
            initialCols = uiState.matrixCols,
            onDismiss = { viewModel.dismissMatrixDialog() },
            onSizeSelected = { rows, cols -> viewModel.insertMatrix(rows, cols) }
        )
    }

    // Settings Dialog
    if (uiState.showSettingsDialog) {
        SettingsDialog(
            angleUnit = uiState.angleUnit,
            unitSystem = uiState.unitSystem,
            decimalPlaces = uiState.decimalPlaces,
            digitGrouping = uiState.digitGrouping,
            complexNumbers = uiState.complexNumbers,
            themeMode = uiState.themeMode,
            onToggleAngleUnit = { viewModel.toggleAngleUnit() },
            onUnitSystemChange = { viewModel.setUnitSystem(it) },
            onDecimalPlacesChange = { viewModel.setDecimalPlaces(it) },
            onDigitGroupingChange = { viewModel.setDigitGrouping(it) },
            onComplexNumbersChange = { viewModel.setComplexNumbers(it) },
            onThemeModeChange = { viewModel.setThemeMode(it) },
            onDismiss = { viewModel.dismissSettingsDialog() }
        )
    }
}

/** Fraction of an item's width it must be swiped to be deleted. */
private const val SWIPE_TO_DELETE_THRESHOLD = 0.5f

/**
 * The result of the line being typed, in the chosen [form] (with pills to change it).
 * Incomplete input (e.g. "2 +") shows nothing rather than an error, unless the user tried to
 * commit it with Enter.
 */
@Composable
private fun LiveResultView(
    result: CalculationResult?,
    showError: Boolean,
    form: ResultForm,
    onFormChange: (ResultForm) -> Unit,
    onCopy: (MathString) -> Unit,
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

        val forms = ResultFormatter.availableForms(result)
        // The form actually shown: the chosen one, or exact if it does not apply here
        val shownForm = if (form in forms) form else ResultForm.EXACT

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (forms.size > 1) {
                forms.forEach { option ->
                    AnswerPill(option.label, shownForm == option) { onFormChange(option) }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            val shownResult = ResultFormatter.format(result, shownForm)
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState(), reverseScrolling = true)
                    .pointerInput(shownResult) { detectTapGestures(onLongPress = { onCopy(shownResult) }) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "= ",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.primary
                )
                MathExpressionView(
                    mathString = shownResult,
                    fontSize = 24.sp,
                    textColor = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

private val ResultForm.label: String
    get() = when (this) {
        ResultForm.EXACT -> "Exact"
        ResultForm.DECIMAL -> "Decimal"
        ResultForm.SCIENTIFIC -> "Sci"
        ResultForm.MIXED -> "Mixed"
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
    form: ResultForm,
    onInputClick: () -> Unit,
    onResultClick: () -> Unit,
    onCopy: (MathString) -> Unit
) {
    // Shown as chosen when committed; tapping it still reuses the exact value
    val shownResult = ResultFormatter.format(item, form)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Input expression; shrunk when too long instead of being squeezed (which made its
            // text wrap and parts overlap). Not scrollable, so swiping always deletes the entry.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onLongClick = { onCopy(item.input) }, onClick = onInputClick)
            ) {
                ShrinkToFitWidth {
                    MathExpressionView(
                        mathString = item.input,
                        fontSize = 20.sp,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Result
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onLongClick = { onCopy(shownResult) }, onClick = onResultClick),
                contentAlignment = Alignment.CenterEnd
            ) {
                ShrinkToFitWidth {
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
                            mathString = shownResult,
                            fontSize = 22.sp,
                            textColor = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}

/** [this], cut to [maxLength] characters with "…" if longer. */
private fun String.ellipsized(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength - 1).trimEnd() + "…"

/**
 * Lays out [content] at its natural width and scales it down uniformly when that is wider
 * than the available space, so it never gets squeezed (wrapping, overlapping) or clipped.
 */
@Composable
private fun ShrinkToFitWidth(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val scale = if (constraints.hasBoundedWidth && placeable.width > constraints.maxWidth) {
            constraints.maxWidth.toFloat() / placeable.width
        } else {
            1f
        }
        val width = (placeable.width * scale).roundToInt()
        val height = (placeable.height * scale).roundToInt()
        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CalculatorScreenPreview() {
    CalculatorTheme(darkTheme = false) {
        CalculatorScreen(viewModel = remember { CalculatorViewModel(Application()) })
    }
}

@Preview(showBackground = true)
@Composable
fun CalculatorScreenDarkPreview() {
    CalculatorTheme(darkTheme = true) {
        CalculatorScreen(viewModel = remember { CalculatorViewModel(Application()) })
    }
}
