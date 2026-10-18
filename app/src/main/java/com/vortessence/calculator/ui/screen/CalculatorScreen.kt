package com.vortessence.calculator.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.roundToInt
import android.app.Activity
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vortessence.calculator.R
import com.vortessence.calculator.core.math.CalculationResult
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.MathText
import com.vortessence.calculator.core.math.type.ResultForm
import com.vortessence.calculator.core.math.type.ResultFormatter
import com.vortessence.calculator.ui.dialogs.ConstantsDialog
import com.vortessence.calculator.ui.dialogs.FormulasDialog
import com.vortessence.calculator.ui.dialogs.HelpDialog
import com.vortessence.calculator.ui.dialogs.StepsSheet
import com.vortessence.calculator.ui.dialogs.MatrixDialog
import com.vortessence.calculator.ui.dialogs.UnitsDialog
import com.vortessence.calculator.ui.dialogs.VariableDialog
import com.vortessence.calculator.ui.graph.GraphPlotterView
import com.vortessence.calculator.ui.keypad.CalculatorKeypad
import com.vortessence.calculator.ui.math.LocalDigitGrouping
import com.vortessence.calculator.ui.math.LocalVariableDefinitions
import com.vortessence.calculator.ui.math.MathEditor
import com.vortessence.calculator.ui.math.MathEditorActions
import com.vortessence.calculator.ui.math.MathExpressionView
import com.vortessence.calculator.ui.math.rememberMathClipboard
import androidx.compose.ui.tooling.preview.Preview
import com.vortessence.calculator.i18n.AppLanguage
import com.vortessence.calculator.ui.dialogs.SettingsDialog
import com.vortessence.calculator.ui.theme.AccentRed
import com.vortessence.calculator.ui.theme.CalculatorTheme
import com.vortessence.calculator.viewmodel.CalculatorUiState
import com.vortessence.calculator.viewmodel.CalculatorViewModel
import com.vortessence.calculator.viewmodel.PercentHint
import com.vortessence.calculator.viewmodel.SessionFileRequest
import com.vortessence.calculator.viewmodel.SessionMessage
import com.vortessence.calculator.viewmodel.UndoOffer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.LocalDate
import com.vortessence.calculator.viewmodel.TypingNotice
import com.vortessence.calculator.viewmodel.VariableHint
import com.vortessence.calculator.core.math.type.VariableNameProblem
import com.vortessence.calculator.ui.keypad.KeyAction
import com.vortessence.calculator.ui.math.TypingInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import com.vortessence.calculator.core.math.i18n.Language
import com.vortessence.calculator.core.math.i18n.Text
import com.vortessence.calculator.core.math.i18n.Texts

@Composable
fun CalculatorScreen(
    viewModel: CalculatorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Digit grouping applies to every number shown: input, results, history and graph
    // Defined variables decide whether a derivative is written d/dx or, partial, ∂/∂x
    val definitions = remember(uiState.history) { uiState.variables() }
    CompositionLocalProvider(
        LocalDigitGrouping provides uiState.digitGrouping,
        LocalVariableDefinitions provides definitions
    ) {
        CalculatorScreenContent(viewModel, uiState)
    }
}

@Composable
private fun CalculatorScreenContent(viewModel: CalculatorViewModel, uiState: CalculatorUiState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    if (uiState.isGraphOpen) {
        GraphPlotterView(
            scene = uiState.plotScene,
            expression = uiState.expression,
            isLoading = uiState.isPlotLoading,
            modifier = Modifier.fillMaxSize(),
            onAddLine = { viewModel.addGraphLine() },
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
                message = resources.getString(R.string.notice_complex_on),
                withDismissAction = true
            )
            viewModel.dismissComplexNotice()
        }
    }

    // Saving and opening a session go through the system's file dialogs; no permission is needed for them
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.saveSessionTo(uri)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openSessionFrom(uri)
    }
    LaunchedEffect(uiState.sessionFileRequest) {
        val request = uiState.sessionFileRequest ?: return@LaunchedEffect
        viewModel.sessionFileRequestHandled()
        when (request) {
            SessionFileRequest.SAVE -> saveFile.launch(resources.getString(R.string.session_file_name, LocalDate.now().toString()))
            SessionFileRequest.OPEN -> openFile.launch(arrayOf("*/*"))
        }
    }
    LaunchedEffect(uiState.sessionMessage) {
        val message = uiState.sessionMessage ?: return@LaunchedEffect
        val text = when (message.type) {
            SessionMessage.Type.SAVED -> resources.getString(R.string.session_saved)
            SessionMessage.Type.SAVE_FAILED -> resources.getString(R.string.session_save_failed)
            SessionMessage.Type.NOT_A_SESSION -> resources.getString(R.string.session_not_a_session)
            SessionMessage.Type.READ_FAILED -> resources.getString(R.string.session_read_failed)
            SessionMessage.Type.OPENED ->
                resources.getQuantityString(R.plurals.session_opened, message.count, message.count) +
                    if (message.skipped > 0) resources.getQuantityString(R.plurals.session_opened_skipped, message.skipped, message.skipped) else ""
        }
        snackbarHostState.showSnackbar(text, withDismissAction = true)
        viewModel.dismissSessionMessage()
    }

    // The history was cleared or an entry deleted: the message offers to bring it back
    LaunchedEffect(uiState.undoOffer) {
        val offer = uiState.undoOffer ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = resources.getString(
                if (offer.kind == UndoOffer.Kind.HISTORY_CLEARED) R.string.undo_history_cleared else R.string.undo_entry_deleted
            ),
            actionLabel = resources.getString(R.string.undo_action),
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.dismissUndoOffer(offer)
    }

    // A session file was opened while there is a history: replace it or add to it
    if (uiState.pendingImport != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text(stringResource(R.string.session_import_title)) },
            text = { Text(stringResource(R.string.session_import_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.importSession(replace = true) }) { Text(stringResource(R.string.session_import_replace)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelImport() }) { Text(stringResource(R.string.session_cancel)) }
                    TextButton(onClick = { viewModel.importSession(replace = false) }) { Text(stringResource(R.string.session_import_add)) }
                }
            }
        )
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) showMessage(resources.getString(R.string.message_copied))
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
                content.math == null -> showMessage(resources.getString(R.string.message_cant_paste, content.text.ellipsized(24)))
                !viewModel.paste(content.math) -> showMessage(resources.getString(R.string.message_cant_paste_lines))
            }
        },
        onSelectAll = { viewModel.selectAll() },
        canPaste = { clipboard.hasText() }
    )

    // The abc key brings up the system keyboard, which types into the expression through an
    // invisible field; the keypad makes room for it while it is up
    val typingFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The keypad and the keyboard trade places. The keypad gives up exactly as much height as the
    // keyboard takes (see keypadHeightLeft), so the input line moves only when the keyboard is taller
    // than the keypad, and then in step with it, however fast the keyboard slides.
    @OptIn(ExperimentalLayoutApi::class)
    val imeTarget = WindowInsets.imeAnimationTarget
    val ime = WindowInsets.ime
    val navigationBars = WindowInsets.navigationBars
    val density = LocalDensity.current
    /** The keyboard's height as far as the content is concerned: the navigation bar is below it already. */
    fun imeHeight(insets: WindowInsets) = (insets.getBottom(density) - navigationBars.getBottom(density)).coerceAtLeast(0)
    val isTyping = imeTarget.getBottom(density) > 0
    // Whenever the keyboard is up, it types into the expression (it can also be up without the abc key,
    // e.g. after the app was restored). Once it is down the field lets go of the focus: a field that
    // keeps it brings the keyboard back by itself when the app returns from the background.
    val focusManager = LocalFocusManager.current
    // The screen itself holds the focus otherwise, so that the arrow keys of a physical keyboard reach the cursor
    val screenFocus = remember { FocusRequester() }
    LaunchedEffect(isTyping) {
        if (isTyping) {
            runCatching { typingFocus.requestFocus() }
        } else {
            focusManager.clearFocus()
            runCatching { screenFocus.requestFocus() }
        }
    }
    // A keyboard that is lower than the keypad leaves a rest of it showing; once it has arrived that goes
    val keyboardArrived by remember { derivedStateOf { isTyping && imeHeight(ime) >= imeHeight(imeTarget) } }
    val restShown by animateFloatAsState(
        targetValue = if (keyboardArrived) 0f else 1f,
        animationSpec = tween(durationMillis = KEYBOARD_SWAP_MILLIS, easing = FastOutSlowInEasing),
        label = "keypad"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
                .onPreviewKeyEvent { event ->
                    val action = when (event.key) {
                        Key.DirectionLeft -> KeyAction.MoveLeft
                        Key.DirectionRight -> KeyAction.MoveRight
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) viewModel.onKeyAction(action)
                    true
                }
                .focusRequester(screenFocus)
                .focusable()
        ) {
            // History of committed calculations (scrollable, newest at the bottom)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
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
                // Once, for a new user: where the help is. Over the oldest part of the history, which is empty on a new install
                if (uiState.showHelpHint) {
                    HelpHintCard(
                        onOpen = { viewModel.openHelp() },
                        onDismiss = { viewModel.dismissHelpHint() },
                        modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 8.dp)
                    )
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
                        actions = editorActions,
                        onEditVariable = { path, index -> viewModel.editVariable(path, index) }
                    )

                    LiveResultView(
                        result = uiState.result,
                        showError = uiState.showResultError,
                        form = uiState.resultForm,
                        onFormChange = { viewModel.setResultForm(it) },
                        onCopy = ::copyWithFeedback,
                        hasSteps = uiState.hasSteps || uiState.transforms.isNotEmpty(),
                        language = uiState.language,
                        onShowSteps = { viewModel.showSteps() },
                        percentHint = uiState.percentHint,
                        onApplyPercentHint = { viewModel.applyPercentHint() },
                        variableHint = uiState.variableHint,
                        onApplyVariableHint = { viewModel.applyVariableHint(it) }
                    )
                }
            }

            TypingInput(
                focusRequester = typingFocus,
                onText = { viewModel.typeText(it) },
                onBackspace = { viewModel.typeBackspace() },
                onEnter = { viewModel.onKeyAction(KeyAction.Enter) }
            )

            AnimatedVisibility(
                visible = isTyping,
                enter = expandVertically(tween(KEYBOARD_SWAP_MILLIS, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(tween(KEYBOARD_SWAP_MILLIS, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                TypingHint(uiState.typingNotice)
            }

            // Keypad with swipe navigation (Other, Main, Advanced). It stays composed (so it keeps
            // its page) and gives its height up to the keyboard, from the bottom, as the keyboard takes it
            Box(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val left = (placeable.height - imeHeight(ime)).coerceAtLeast(0)
                        layout(placeable.width, (left * restShown).roundToInt()) { placeable.placeRelative(0, 0) }
                    }
                    .clipToBounds()
                    .then(if (isTyping && restShown == 0f) Modifier.clearAndSetSemantics { } else Modifier)
            ) {
                CalculatorKeypad(
                    isLineEmpty = uiState.expression.isEmpty,
                    inputMode = uiState.inputMode,
                    onInputModeChange = { viewModel.setInputMode(it) },
                    angleUnit = uiState.angleUnit,
                    onKeyAction = { action ->
                        if (action is KeyAction.FreeVariable) {
                            typingFocus.requestFocus()
                            keyboard?.show()
                        } else {
                            viewModel.onKeyAction(action)
                        }
                    }
                )
            }
        }
    }

    // Units Dialog
    if (uiState.showUnitsDialog) {
        UnitsDialog(
            onDismiss = { viewModel.dismissUnitsDialog() },
            onUnitSelected = { symbol -> viewModel.insertUnit(symbol) },
            selectedCategory = uiState.unitCategory,
            onCategorySelected = { viewModel.selectUnitCategory(it) },
            scrollToFamily = uiState.unitFamilyToShow,
            language = uiState.language
        )
    }

    uiState.variableEdit?.let { edit ->
        VariableDialog(
            initial = edit.name,
            onDismiss = { viewModel.dismissVariableEdit() },
            onLetter = { viewModel.setVariable(it) }
        )
    }

    // Constants Dialog
    if (uiState.showConstantsDialog) {
        ConstantsDialog(
            onDismiss = { viewModel.dismissConstantsDialog() },
            onConstantSelected = { symbol -> viewModel.insertConstant(symbol) }
        )
    }

    uiState.steps?.let { steps ->
        StepsSheet(state = steps, onDismiss = { viewModel.dismissSteps() }, onSelect = { viewModel.selectStepsOption(it) })
    }

    if (uiState.showFormulasDialog) {
        FormulasDialog(
            onDismiss = { viewModel.dismissFormulasDialog() },
            onFormulaSelected = { template -> viewModel.insertFormula(template) },
            onOpenHelp = { topic -> viewModel.openHelp(topic) }
        )
    }

    if (uiState.showHelp) {
        HelpDialog(
            initialTopic = uiState.helpTopic,
            onTryExample = { viewModel.tryExample(it) },
            onDismiss = { viewModel.dismissHelp() }
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
            unitSystem = uiState.unitSystem,
            decimalPlaces = uiState.decimalPlaces,
            digitGrouping = uiState.digitGrouping,
            decimalComma = uiState.decimalComma,
            complexNumbers = uiState.complexNumbers,
            themeMode = uiState.themeMode,
            languageChoice = remember(uiState.language, uiState.showSettingsDialog) { AppLanguage.choice(context) },
            onUnitSystemChange = { viewModel.setUnitSystem(it) },
            onDecimalPlacesChange = { viewModel.setDecimalPlaces(it) },
            onDigitGroupingChange = { viewModel.setDigitGrouping(it) },
            onDecimalCommaChange = { viewModel.setDecimalComma(it) },
            onComplexNumbersChange = { viewModel.setComplexNumbers(it) },
            onThemeModeChange = { viewModel.setThemeMode(it) },
            onLanguageChange = { choice -> (context as? Activity)?.let { AppLanguage.set(it, choice) } },
            onSaveSession = { viewModel.dismissSettingsDialog(); viewModel.requestSessionFile(SessionFileRequest.SAVE) },
            onOpenSession = { viewModel.dismissSettingsDialog(); viewModel.requestSessionFile(SessionFileRequest.OPEN) },
            onOpenHelp = { viewModel.openHelp() },
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
    hasSteps: Boolean,
    language: Language,
    onShowSteps: () -> Unit,
    modifier: Modifier = Modifier,
    percentHint: PercentHint? = null,
    onApplyPercentHint: () -> Unit = {},
    variableHint: VariableHint? = null,
    onApplyVariableHint: (String) -> Unit = {}
) {
    Column(horizontalAlignment = Alignment.End) {
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
                    text = result.issueNote ?: result.issues.firstOrNull()?.message(language) ?: Texts.get(Text.CALCULATION_ERROR, language),
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

        val shownResult = ResultFormatter.format(result, shownForm)
        // The buttons stay on the left and the result on the right. When both do not fit on one
        // line, the result moves to its own line below, so no part of the answer is hidden.
        // A layout may measure each child only once, so the result is composed twice: the first
        // copy is only measured, to learn its natural width, the second copy is the one shown.
        val resultContent: @Composable () -> Unit = {
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
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (hasSteps) AnswerPill(stringResource(R.string.steps_title), selected = false, onClick = onShowSteps)
                    if (forms.size > 1) {
                        forms.forEach { option ->
                            AnswerPill(stringResource(option.label), shownForm == option) { onFormChange(option) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { resultContent() }
                // Scrolls sideways only if the result alone is wider than the screen
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .pointerInput(shownResult) { detectTapGestures(onLongPress = { onCopy(shownResult) }) },
                    verticalAlignment = Alignment.CenterVertically
                ) { resultContent() }
            }
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val gap = 6.dp.roundToPx()
            val buttons = measurables[0].measure(Constraints(maxWidth = width))
            val naturalWidth = measurables[1].measure(Constraints()).width
            val result = measurables[2].measure(Constraints(maxWidth = width))
            if (buttons.width + gap + naturalWidth <= width) {
                val height = maxOf(buttons.height, result.height)
                layout(width, height) {
                    buttons.placeRelative(0, (height - buttons.height) / 2)
                    result.placeRelative(width - result.width, (height - result.height) / 2)
                }
            } else {
                layout(width, buttons.height + result.height) {
                    buttons.placeRelative(0, 0)
                    result.placeRelative(width - result.width, buttons.height)
                }
            }
        }
    }
    // 200 + 10% is read as 200 + 0.1: say so, and offer what a pocket calculator would mean
    if (percentHint != null && result?.isSuccess == true) {
        Text(
            text = stringResource(
                if (percentHint.subtracts) R.string.percent_hint_minus else R.string.percent_hint_plus,
                percentHint.percent,
                percentHint.base,
                percentHint.result.answer?.let { MathText.toPlainText(it) } ?: MathText.toPlainText(percentHint.result.result)
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .clickable(onClick = onApplyPercentHint)
        )
    }
    // d/dx(y²) is 0: offer the other variables of the body as the one meant
    if (variableHint != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 2.dp)) {
            for (name in variableHint.candidates.take(MAX_VARIABLE_HINTS)) {
                Text(
                    text = stringResource(
                        if (variableHint.isDerivative) R.string.variable_hint_derivative else R.string.variable_hint_integral,
                        name
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onApplyVariableHint(name) }
                )
            }
        }
    }
    }
}

/** Shown while the system keyboard types: variables are single letters; or why the last letter was not taken. */
@Composable
private fun TypingHint(notice: TypingNotice?) {
    val text = when (notice?.problem) {
        VariableNameProblem.CONSTANT -> stringResource(R.string.variable_is_constant, notice.letter)
        VariableNameProblem.TRANSPOSE -> stringResource(R.string.variable_is_transpose)
        else -> stringResource(R.string.typing_hint)
    }
    Text(
        text = text,
        fontSize = 12.sp,
        color = if (notice == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

/** How long the keypad and the system keyboard take to trade places (about as long as the keyboard slides). */
private const val KEYBOARD_SWAP_MILLIS = 280

/** How many variables a hint offers; more would not fit beside each other. */
private const val MAX_VARIABLE_HINTS = 2

private val ResultForm.label: Int
    @StringRes get() = when (this) {
        ResultForm.EXACT -> R.string.result_form_exact
        ResultForm.DECIMAL -> R.string.result_form_decimal
        ResultForm.SCIENTIFIC -> R.string.result_form_scientific
        ResultForm.MIXED -> R.string.result_form_mixed
        ResultForm.POLAR -> R.string.result_form_polar
        ResultForm.HEX -> R.string.result_form_hex
        ResultForm.OCT -> R.string.result_form_oct
        ResultForm.BIN -> R.string.result_form_bin
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
    // Not the saved kind (rememberSwipeToDismissBoxState): the list keeps the saved state of a removed entry, and an entry
    // that comes back with undo would be restored as swiped away, and be deleted again at once
    val state = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = { totalDistance -> totalDistance * SWIPE_TO_DELETE_THRESHOLD }
        )
    }
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
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.history_delete), tint = MaterialTheme.colorScheme.onError)
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

/** Points a new user to the help: open it, or dismiss the note for good. */
@Composable
private fun HelpHintCard(onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.help_hint_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.help_hint_dismiss), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Text(
                text = stringResource(R.string.help_hint_text),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(end = 12.dp)
            )
            TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.help_hint_open))
            }
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
