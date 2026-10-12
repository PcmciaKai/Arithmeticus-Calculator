package com.vortessence.calculator.viewmodel

import com.vortessence.calculator.ui.math.DecimalMark
import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortessence.calculator.core.math.steps.Transform
import com.vortessence.calculator.core.math.*
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.UnitSystem
import com.vortessence.calculator.core.math.expressions.UnitRegistry
import com.vortessence.calculator.core.math.solvers.PlotCurve
import com.vortessence.calculator.core.math.steps.Solution
import com.vortessence.calculator.core.math.type.*
import com.vortessence.calculator.ui.keypad.KeyAction
import com.vortessence.calculator.ui.theme.ThemeMode
import com.vortessence.calculator.core.math.i18n.Language
import com.vortessence.calculator.i18n.AppLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CalculatorUiState(
    val expression: MathString = MathString(),
    val cursorPath: List<Int> = listOf(0),
    val selectionRange: IntRange? = null,
    /** Live result of the current [expression], updated while typing. */
    val result: CalculationResult? = null,
    /** True after Enter was pressed on an invalid line: show its error even if it is "incomplete". */
    val showResultError: Boolean = false,
    /** Whether the step-by-step solution of the live result can be shown (for derivatives). */
    val hasSteps: Boolean = false,
    /** The forms the live result can be asked to be written in (multiplied out, factored, one fraction), with steps. */
    val transforms: List<Transform> = emptyList(),
    /** The step-by-step solution being shown, or null if it is closed. */
    val steps: StepsState? = null,
    /** For "a + b%": the pocket-calculator reading, b% of a, with its result; null for other lines. */
    val percentHint: PercentHint? = null,
    /** Committed calculations, newest first. */
    val history: List<HistoryEntry> = emptyList(),
    /**
     * How results are shown (exact, decimal, ...), for the live result and for calculations
     * committed with Enter. Calculations themselves are always exact.
     */
    val resultForm: ResultForm = ResultForm.EXACT,
    val angleUnit: AngleUnit = AngleUnit.RADIANS,
    /** Units that results with units are expressed in. */
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** Fixed number of decimal places for displayed decimals, or null for automatic. */
    val decimalPlaces: Int? = null,
    /** Show large numbers with digit groups (300 000); display only. */
    val digitGrouping: Boolean = true,
    /** Show a decimal comma (1,5) instead of the point; display only, independent of the language. */
    val decimalComma: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val isGraphOpen: Boolean = false,
    /** Function or implicit curve shown in the graph, or null if the expression cannot be plotted. */
    val plotCurve: PlotCurve? = null,
    val isPlotLoading: Boolean = false,
    val showUnitsDialog: Boolean = false,
    /** Category shown in the unit picker; it reopens on the category used last. */
    val unitCategory: Int = 0,
    /** Family of [unitCategory] the unit picker scrolls to when it opens, e.g. for a conversion. */
    val unitFamilyToShow: Int? = null,
    val showConstantsDialog: Boolean = false,
    val showFormulasDialog: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val showMatrixDialog: Boolean = false,
    /** Size of the most recently inserted matrix, preselected in the matrix dialog. */
    val matrixRows: Int = 2,
    val matrixCols: Int = 2,
    /** Whether i and complex results are allowed (√−4 = 2i instead of an error). */
    val complexNumbers: Boolean = false,
    /** Set when the i key turned complex numbers on, until the message about it was shown. */
    val showComplexNotice: Boolean = false,
    /** The language of the texts the calculation produces (and the app is shown in). */
    val language: Language = Language.ENGLISH
)

/**
 * The step-by-step solution of [input]; [solution] is null while it is worked out, or if
 * there is none after all ([isLoading] false).
 */
/**
 * "200 + 10%" is 200.1 here; [expression] is "200 + 200 × 10%", which has [result] (220), what a
 * pocket calculator would show.
 */
data class PercentHint(
    val expression: MathString,
    val result: CalculationResult,
    /** What the percent is of (200), the percent itself (10) and whether it is taken away. */
    val base: String,
    val percent: String,
    val subtracts: Boolean
)

/**
 * The steps being shown. [options] are what the line can show steps of: its solution (null), or being written in another
 * form ([Transform]); [selected] is the one shown.
 */
data class StepsState(
    val input: MathString,
    val solution: Solution? = null,
    val isLoading: Boolean = true,
    val options: List<Transform?> = emptyList(),
    val selected: Transform? = null
)

/**
 * A committed calculation, shown in the [form] chosen when it was committed. [id] is stable,
 * so list items can be animated and removed. [snapshot] is what it was calculated with; the
 * entry is saved as its input and the snapshot, and calculated again when the app is reopened.
 */
data class HistoryEntry(val id: Long, val calculation: CalculationResult, val form: ResultForm, val snapshot: CalculationSnapshot)

/**
 * @param liveResultDelayMillis Debounce before the live result is recomputed after a change,
 * so that fast typing does not start a calculation for every keystroke.
 */
class CalculatorViewModel @JvmOverloads constructor(
    application: Application,
    private val liveResultDelayMillis: Long = 120L,
    sessionStore: SessionStore? = null
) : AndroidViewModel(application) {

    private val prefs = try {
        application.getSharedPreferences("calculator_settings", Context.MODE_PRIVATE)
    } catch (e: Exception) {
        null
    }

    private val sessionStore: SessionStore = sessionStore ?: PrefsSessionStore(prefs)

    private val _uiState = MutableStateFlow(CalculatorUiState())
    val uiState: StateFlow<CalculatorUiState> = _uiState.asStateFlow()

    private var nextHistoryId = 0L

    /** Fixed end(s) of the selection while it is being extended by dragging. */
    private var selectionAnchors: List<List<Int>> = emptyList()

    private var commitJob: Job? = null
    private var plotJob: Job? = null
    private var stepsJob: Job? = null
    private var restoreJob: Job? = null

    /**
     * False while the saved history is being calculated again. Nothing is saved until then, or
     * the empty history of that moment would overwrite the saved one.
     */
    private var sessionRestored = false

    /**
     * Saved entries that could not be calculated again (e.g. a calculation that timed out on a
     * slow start). They are not shown, but kept in the saved session so the next start can try
     * again; AC removes them.
     */
    private var unrestoredEntries: List<SavedEntry> = emptyList()

    private val saveLock = Any()
    private var saveCounter = 0L
    private var lastSavedCounter = 0L

    /** The steps worked out for the live result, to show them at once when the sheet is opened. */
    private var cachedSteps: CachedSteps? = null

    init {
        loadSettings()
        _uiState.update { it.copy(language = AppLanguage.current(application)) }
        restoreSession()
        observeLiveResult()
        observeSession()
    }

    /**
     * Recomputes the live result whenever the expression or a calculation setting changes.
     * collectLatest cancels a still running calculation as soon as the input changes again.
     */
    private fun observeLiveResult() {
        viewModelScope.launch {
            _uiState
                .map { LiveInput(it.expression, it.snapshot()) }
                .distinctUntilChanged()
                .collectLatest { input ->
                    if (input.expression.isEmpty) {
                        _uiState.update { it.copy(result = null, showResultError = false, hasSteps = false, transforms = emptyList(), percentHint = null) }
                        return@collectLatest
                    }
                    if (liveResultDelayMillis > 0) delay(liveResultDelayMillis)
                    val context = input.snapshot.toMathContext()
                    val res = MathEngine.calculate(input.expression, context)
                    _uiState.update {
                        if (it.expression == input.expression) it.copy(result = res, showResultError = false, hasSteps = false, transforms = emptyList(), percentHint = null) else it
                    }
                    percentHint(input.expression, res, context)?.let { hint ->
                        _uiState.update { if (it.expression == input.expression) it.copy(percentHint = hint) else it }
                    }
                    // Finding out whether there are steps can take as long as the calculation: the
                    // result is shown first, the "Steps" pill follows
                    if (res.isSuccess) {
                        // The steps are kept for the sheet: working them out is what tells whether there are any
                        val solution = MathEngine.steps(input.expression, context)
                        cachedSteps = CachedSteps(input, solution)
                        if (solution != null) {
                            _uiState.update { if (it.expression == input.expression) it.copy(hasSteps = true) else it }
                        }
                        val forms = MathEngine.transforms(input.expression, context)
                        if (forms.isNotEmpty()) {
                            _uiState.update { if (it.expression == input.expression) it.copy(transforms = forms) else it }
                        }
                    }
                }
        }
    }

    /**
     * Brings back the line and the history as the app was closed. The history entries are
     * calculated again from their saved inputs and snapshots, so they show up a moment later.
     */
    private fun restoreSession() {
        val saved = sessionStore.load()?.let { SessionCodec.decode(it) }
        if (saved == null) {
            sessionRestored = true
            return
        }
        nextHistoryId = (saved.entries.maxOfOrNull { it.id } ?: -1L) + 1
        _uiState.update { it.copy(expression = saved.expression, cursorPath = listOf(saved.expression.length)) }
        restoreJob = viewModelScope.launch {
            try {
                // Each entry shows up as soon as it is calculated, in its place (newest first)
                coroutineScope {
                    for (entry in saved.entries) {
                        launch {
                            val restored = restoreEntry(entry)
                            if (restored == null) {
                                unrestoredEntries = unrestoredEntries + entry
                            } else {
                                _uiState.update { it.copy(history = (it.history + restored).sortedByDescending { e -> e.id }) }
                            }
                        }
                    }
                }
            } finally {
                sessionRestored = true
            }
        }
    }

    /** The entry calculated again, or null if that fails now. */
    private suspend fun restoreEntry(entry: SavedEntry): HistoryEntry? {
        // In the language of the day, not the one the entry was saved in
        val snapshot = entry.snapshot.copy(language = _uiState.value.language)
        val result = MathEngine.calculate(entry.input, snapshot.toMathContext(), RESTORE_TIMEOUT_MILLIS)
        return if (result.isSuccess) HistoryEntry(entry.id, result, entry.form, snapshot) else null
    }

    /** Saves the session a moment after the line or the history changed. */
    private fun observeSession() {
        viewModelScope.launch {
            _uiState
                .map { SessionKey(it.history.map { entry -> entry.id }, it.expression) }
                .distinctUntilChanged()
                .collectLatest {
                    delay(SAVE_DELAY_MILLIS)
                    // Encoding and writing can take a while with a long history: not on the main thread
                    val write = prepareSave() ?: return@collectLatest
                    withContext(Dispatchers.Default) { write() }
                }
        }
    }

    private data class SessionKey(val historyIds: List<Long>, val expression: MathString)

    /**
     * Saves the line and the history, so that the next start continues from here. Done
     * automatically after changes; also to be called when the app goes to the background.
     */
    fun saveSession() {
        prepareSave()?.invoke()
    }

    /** Captures what is to be saved now; the returned function does the saving. Null while restoring. */
    private fun prepareSave(): (() -> Unit)? {
        if (!sessionRestored) return null
        val state = _uiState.value
        val shown = state.history.map { SavedEntry(it.id, it.calculation.input, it.form, it.snapshot) }
        val entries = (shown + unrestoredEntries).sortedByDescending { it.id }
        val counter = ++saveCounter
        return {
            // Saves may overlap on different threads: only ever write a newer state over an older one
            synchronized(saveLock) {
                if (counter > lastSavedCounter) {
                    lastSavedCounter = counter
                    sessionStore.save(SessionCodec.encode(SavedSession(state.expression, entries)))
                }
            }
        }
    }

    /** The expression and all calculation settings; a change of any of them updates the live result. */
    private data class LiveInput(val expression: MathString, val snapshot: CalculationSnapshot)

    private class CachedSteps(val input: LiveInput, val solution: Solution?)

    private fun CalculatorUiState.snapshot() =
        CalculationSnapshot(angleUnit, unitSystem, decimalPlaces, ans(), variables(), complexNumbers, language)

    /** Ans is the most recently committed result, not the live result of this line. */
    private fun CalculatorUiState.ans(): MathString? =
        history.firstNotNullOfOrNull { it.calculation.answer }

    /**
     * Variables defined with ":=" by calculations in the history; the newest definition of a
     * name wins. A variable stays defined until its definition is removed from the history.
     */
    private fun CalculatorUiState.variables(): Map<String, MathString> =
        history.asReversed().mapNotNull { entry ->
            val name = entry.calculation.definedVariable ?: return@mapNotNull null
            entry.calculation.answer?.let { name to it }
        }.toMap()

    private fun loadSettings() {
        val savedAngleStr = prefs?.getString("angle_unit", AngleUnit.RADIANS.name) ?: AngleUnit.RADIANS.name
        val angle = try { AngleUnit.valueOf(savedAngleStr) } catch (e: Exception) { AngleUnit.RADIANS }
        val savedUnitSystem = prefs?.getString("unit_system", null)
        val unitSystem = UnitSystem.entries.firstOrNull { it.name == savedUnitSystem } ?: UnitSystem.METRIC
        val savedForm = prefs?.getString("result_form", null)
        val form = ResultForm.entries.firstOrNull { it.name == savedForm } ?: ResultForm.EXACT
        val places = prefs?.getInt("decimal_places", -1)?.takeIf { it >= 0 }
        val grouping = prefs?.getBoolean("digit_grouping", true) ?: true
        val decimalComma = prefs?.getBoolean("decimal_comma", false) ?: false
        DecimalMark.comma = decimalComma
        val matrixRows = prefs?.getInt("matrix_rows", 2) ?: 2
        val matrixCols = prefs?.getInt("matrix_cols", 2) ?: 2
        val savedTheme = prefs?.getString("theme_mode", null)
        val themeMode = ThemeMode.entries.firstOrNull { it.name == savedTheme } ?: ThemeMode.SYSTEM
        val complexNumbers = prefs?.getBoolean("complex_numbers", false) ?: false
        _uiState.update {
            it.copy(
                resultForm = form,
                angleUnit = angle,
                unitSystem = unitSystem,
                decimalPlaces = places,
                digitGrouping = grouping,
                decimalComma = decimalComma,
                themeMode = themeMode,
                matrixRows = matrixRows,
                matrixCols = matrixCols,
                complexNumbers = complexNumbers
            )
        }
    }

    fun onKeyAction(action: KeyAction) {
        when (action) {
            is KeyAction.Number -> insertNode(NumberNode(action.digit))
            is KeyAction.Operator -> {
                if (action.opSymbol == "÷" || action.opSymbol == "/") {
                    insertDivision()
                } else {
                    val op = when (action.opSymbol) {
                        "+" -> MathOp.PLUS
                        "−", "-" -> MathOp.MINUS
                        "×", "*" -> MathOp.MULTIPLY
                        "=" -> MathOp.EQUALS
                        ":=" -> MathOp.DEFINE
                        "<" -> MathOp.LESS
                        ">" -> MathOp.GREATER
                        "≤", "<=" -> MathOp.LESS_EQUAL
                        "≥", ">=" -> MathOp.GREATER_EQUAL
                        "!" -> MathOp.FACTORIAL
                        "%" -> MathOp.PERCENT
                        "mod" -> MathOp.MODULO
                        else -> MathOp.PLUS
                    }
                    insertNode(OperatorNode(op))
                }
            }
            is KeyAction.InlineDivide -> insertNode(OperatorNode(MathOp.DIVIDE))
            is KeyAction.MixedFraction -> insertMixedFraction()
            is KeyAction.Variable -> insertNode(VariableNode(action.name))
            is KeyAction.Constant -> insertNode(ConstantNode(action.symbol))
            is KeyAction.Function -> insertNode(FunctionNode(action.name, MathString()))
            is KeyAction.LogBase -> insertNode(LogBaseNode())
            is KeyAction.EE -> insertEE()
            is KeyAction.SuperscriptSign -> insertPower(MathString(listOf(OperatorNode(if (action.plus) MathOp.PLUS else MathOp.MINUS))))
            is KeyAction.Parentheses -> onParenthesesAction()
            is KeyAction.Fraction -> insertDivision()
            is KeyAction.Power -> insertPower()
            is KeyAction.Square -> insertPower(MathString(listOf(NumberNode("2"))))
            is KeyAction.Reciprocal -> insertPower(MathString(listOf(OperatorNode(MathOp.MINUS), NumberNode("1"))))
            is KeyAction.Sqrt -> insertSqrt()
            is KeyAction.NthRoot -> insertNode(RadicalNode(MathString(), MathString()))
            is KeyAction.LeftParen -> insertNode(ParenthesesNode(MathString()))
            is KeyAction.RightParen -> moveCursorRight()
            is KeyAction.Backspace -> deleteAtCursor()
            is KeyAction.ClearLine -> clearLine()
            is KeyAction.ClearAll -> clearAll()
            // One key: clears the line, or everything once the line is already empty
            is KeyAction.Clear -> if (_uiState.value.expression.isEmpty) clearAll() else clearLine()
            is KeyAction.Convert -> insertConversion()
            is KeyAction.Enter -> commit()
            is KeyAction.NewLine -> insertLineBreak()
            is KeyAction.Ans -> insertAns()
            is KeyAction.ToggleAngleUnit -> toggleAngleUnit()
            is KeyAction.MoveLeft -> moveCursorLeft()
            is KeyAction.MoveRight -> moveCursorRight()
            is KeyAction.OpenUnits -> _uiState.update { it.copy(showUnitsDialog = true) }
            is KeyAction.OpenConstants -> _uiState.update { it.copy(showConstantsDialog = true) }
            is KeyAction.OpenFormulas -> _uiState.update { it.copy(showFormulasDialog = true) }
            is KeyAction.Separator -> insertNode(SeparatorNode)
            is KeyAction.OpenGraph -> toggleGraph()
            is KeyAction.OpenSettings -> _uiState.update { it.copy(showSettingsDialog = true) }
            is KeyAction.Integral -> insertNode(IntegralNode())
            is KeyAction.Antiderivative -> insertNode(AntiderivativeNode())
            is KeyAction.Derivative -> insertNode(DerivativeNode())
            is KeyAction.DerivativeAt -> insertNode(DerivativeNode(point = MathString()))
            is KeyAction.Matrix -> _uiState.update { it.copy(showMatrixDialog = true) }
            is KeyAction.Transpose -> insertPower(MathString(listOf(VariableNode("T"))))
            is KeyAction.Sum -> insertBigOperator(BigOperator.SUM)
            is KeyAction.Product -> insertBigOperator(BigOperator.PRODUCT)
            is KeyAction.Limit -> insertWithBinding(LimitNode(MathString(listOf(VariableNode("x"), OperatorNode(MathOp.CONVERT)))))
            is KeyAction.ImaginaryUnit -> insertImaginaryUnit()
            is KeyAction.Polar -> insertPolar()
        }
    }

    fun setCursorPath(path: List<Int>) {
        _uiState.update { it.copy(cursorPath = path, selectionRange = null) }
    }

    fun setSelection(path: List<Int>, range: IntRange) {
        val fullCursorPath = path + (range.last + 1)
        _uiState.update { it.copy(cursorPath = fullCursorPath, selectionRange = range) }
    }

    fun selectTokenAt(path: List<Int>, index: Int) {
        val root = _uiState.value.expression
        val target = getTargetString(root, path)
        if (index !in target.nodes.indices) return
        val tokenRange = target.getTokenRangeAt(index)
        val fullCursorPath = path + (tokenRange.last + 1)
        // Dragging after the long press extends from this token and always keeps it selected
        selectionAnchors = listOf(path + tokenRange.first, path + (tokenRange.last + 1))
        _uiState.update { it.copy(cursorPath = fullCursorPath, selectionRange = tokenRange) }
    }

    /**
     * Starts dragging a selection handle: the opposite end of the selection stays fixed while
     * [extendSelection] moves the dragged end.
     */
    fun beginHandleDrag(isStartHandle: Boolean) {
        val state = _uiState.value
        val range = state.selectionRange ?: return
        val stringPath = state.cursorPath.dropLast(1)
        selectionAnchors = listOf(if (isStartHandle) stringPath + (range.last + 1) else stringPath + range.first)
    }

    /**
     * Moves the free end of the selection to [focusCaret] (a caret path). The selection covers
     * the anchor(s) and the focus; it is never collapsed to nothing while dragging.
     */
    fun extendSelection(focusCaret: List<Int>) {
        if (selectionAnchors.isEmpty()) return
        val selection = CaretPaths.covering(selectionAnchors + listOf(focusCaret), _uiState.value.expression) ?: return
        _uiState.update {
            it.copy(
                cursorPath = selection.stringPath + (selection.range.last + 1),
                selectionRange = selection.range
            )
        }
    }

    /** Selects the whole input. */
    fun selectAll() {
        val expression = _uiState.value.expression
        if (expression.isEmpty) return
        _uiState.update { it.copy(cursorPath = listOf(expression.length), selectionRange = 0 until expression.length) }
    }

    /** A copy of the selected nodes, or null if nothing is selected. */
    fun selectedMath(): MathString? {
        val state = _uiState.value
        val range = state.selectionRange?.takeIf { !it.isEmpty() } ?: return null
        val target = getTargetString(state.expression, state.cursorPath)
        return MathString(range.filter { it in target.nodes.indices }.map { target.nodeAt(it) })
    }

    /** Removes the selection and returns it, or null if nothing is selected. */
    fun cutSelection(): MathString? = selectedMath()?.also { deleteAtCursor() }

    /**
     * Inserts [math] at the cursor, replacing the selection, as if it had been typed there.
     * Returns false (and changes nothing) if it does not fit: line breaks only exist at the top
     * level, not inside a fraction, root, ...
     */
    fun paste(math: MathString): Boolean {
        val state = _uiState.value
        val path = state.cursorPath
        if (math.isEmpty || (path.size > 1 && math.nodes.any { it is LineBreakNode })) return false
        val pasted = MathText.splitNumbers(math)
        val root = state.expression.copy()
        val target = getTargetString(root, path)
        var pos = path.last().coerceIn(0, target.length)
        val range = state.selectionRange
        if (range != null && !range.isEmpty()) {
            val start = range.first.coerceIn(0, target.length)
            val end = (range.last + 1).coerceIn(start, target.length)
            for (i in (end - 1) downTo start) target.removeAt(i)
            pos = start
        }
        target.insertAll(pos, pasted.nodes)
        _uiState.update {
            it.copy(expression = root, cursorPath = path.dropLast(1) + (pos + pasted.length), selectionRange = null)
        }
        return true
    }

    /** Selects how results are shown; also applies to calculations committed afterwards. */
    fun setResultForm(form: ResultForm) {
        prefs?.edit { putString("result_form", form.name) }
        _uiState.update { it.copy(resultForm = form) }
    }

    /** A fixed number of decimal places, or null for automatic. */
    fun setDecimalPlaces(places: Int?) {
        prefs?.edit { putInt("decimal_places", places ?: -1) }
        _uiState.update { it.copy(decimalPlaces = places) }
    }

    fun setDigitGrouping(enabled: Boolean) {
        prefs?.edit { putBoolean("digit_grouping", enabled) }
        _uiState.update { it.copy(digitGrouping = enabled) }
    }

    fun setDecimalComma(enabled: Boolean) {
        prefs?.edit { putBoolean("decimal_comma", enabled) }
        DecimalMark.comma = enabled
        _uiState.update { it.copy(decimalComma = enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs?.edit { putString("theme_mode", mode.name) }
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setComplexNumbers(enabled: Boolean) {
        prefs?.edit { putBoolean("complex_numbers", enabled) }
        _uiState.update { it.copy(complexNumbers = enabled) }
    }

    /**
     * The language of the app changed (or was found to be another after the activity was made again): the texts the calculations
     * produce, in the live result and the history, are written again in it.
     */
    fun refreshLanguage() {
        val language = AppLanguage.current(getApplication())
        if (_uiState.value.language == language) return
        _uiState.update { it.copy(language = language) }
        viewModelScope.launch {
            val entries = _uiState.value.history
            val again = entries.map { entry ->
                async {
                    val snapshot = entry.snapshot.copy(language = language)
                    val result = MathEngine.calculate(entry.calculation.input, snapshot.toMathContext(), RESTORE_TIMEOUT_MILLIS)
                    if (result.isSuccess) HistoryEntry(entry.id, result, entry.form, snapshot) else entry
                }
            }.awaitAll()
            _uiState.update { state -> state.copy(history = state.history.map { old -> again.firstOrNull { it.id == old.id } ?: old }) }
        }
    }

    fun dismissComplexNotice() {
        _uiState.update { it.copy(showComplexNotice = false) }
    }

    /** Inserts i; if complex numbers are off, it turns them on and says so. */
    private fun insertImaginaryUnit() {
        enableComplexNumbers()
        insertNode(ConstantNode("i"))
    }

    /** Inserts r∠θ; a number before the cursor becomes r. Turns complex numbers on like i. */
    private fun insertPolar() {
        enableComplexNumbers()
        insertWithNumberAsFirstSlot { first -> PolarNode(first, MathString()) }
    }

    private fun enableComplexNumbers() {
        if (_uiState.value.complexNumbers) return
        setComplexNumbers(true)
        _uiState.update { it.copy(showComplexNotice = true) }
    }

    /** Σ or ∏ with the index n; the cursor goes after "n=", to the start value. */
    private fun insertBigOperator(operator: BigOperator) =
        insertWithBinding(BigOperatorNode(operator, MathString(listOf(VariableNode("n"), OperatorNode(MathOp.EQUALS)))))

    /**
     * Inserts a node whose first slot is already filled with a variable and an operator ("n=",
     * "x→"), with the cursor after them. A selection becomes the summand / the limit's body.
     */
    private fun insertWithBinding(node: MathNode) {
        val wrapsSelection = _uiState.value.selectionRange?.isEmpty() == false
        insertNode(node)
        val path = _uiState.value.cursorPath
        // insertNode put the cursor into the first empty slot, or after the node if a wrapped
        // selection filled the last one; it belongs into the binding slot
        val hasEmptySlot = node.subStrings.withIndex().any { (index, slot) -> slot.isEmpty && !(wrapsSelection && index == mainSlotIndex(node)) }
        val nodePath = if (hasEmptySlot) path.dropLast(2) else path.dropLast(1) + (path.last() - 1)
        _uiState.update { it.copy(cursorPath = nodePath + listOf(0, node.subStrings[0].length)) }
    }

    fun setUnitSystem(system: UnitSystem) {
        prefs?.edit { putString("unit_system", system.name) }
        _uiState.update { it.copy(unitSystem = system) }
    }

    fun toggleAngleUnit() {
        val newUnit = if (_uiState.value.angleUnit == AngleUnit.RADIANS) AngleUnit.DEGREES else AngleUnit.RADIANS
        prefs?.edit { putString("angle_unit", newUnit.name) }
        _uiState.update { it.copy(angleUnit = newUnit) }
    }

    fun toggleGraph() {
        val willOpen = !_uiState.value.isGraphOpen
        _uiState.update { it.copy(isGraphOpen = willOpen) }
        if (willOpen) {
            updatePlotData()
        }
    }

    fun closeGraph() {
        plotJob?.cancel()
        _uiState.update { it.copy(isGraphOpen = false, isPlotLoading = false) }
    }

    fun dismissUnitsDialog() {
        _uiState.update { it.copy(showUnitsDialog = false, unitFamilyToShow = null) }
    }

    fun selectUnitCategory(index: Int) {
        _uiState.update { it.copy(unitCategory = index, unitFamilyToShow = null) }
    }

    /**
     * Inserts "→". Its target is a unit, so the unit picker opens right away, on the units the
     * value before the arrow can be converted to (e.g. volumes for "Ans →" if Ans is 2 m³).
     */
    private fun insertConversion() {
        val state = _uiState.value
        val dimension = MathEngine.unitDimension(conversionSource(state), state.snapshot().toMathContext())
        val location = dimension?.let { UnitRegistry.locate(it) }
        insertNode(OperatorNode(MathOp.CONVERT))
        _uiState.update {
            it.copy(
                showUnitsDialog = true,
                unitCategory = location?.category ?: it.unitCategory,
                unitFamilyToShow = location?.family
            )
        }
    }

    /**
     * What an arrow inserted at the cursor would convert: everything before it, back to the
     * previous "=", ":=", "→" or line break.
     */
    private fun conversionSource(state: CalculatorUiState): MathString {
        val target = getTargetString(state.expression, state.cursorPath)
        val end = (state.selectionRange?.first ?: state.cursorPath.last()).coerceIn(0, target.length)
        val before = target.nodes.subList(0, end)
        val start = before.indexOfLast {
            it is LineBreakNode || (it is OperatorNode && it.op in SOURCE_DELIMITERS)
        } + 1
        return MathString(before.subList(start, end))
    }

    fun dismissConstantsDialog() {
        _uiState.update { it.copy(showConstantsDialog = false) }
    }

    fun dismissSettingsDialog() {
        _uiState.update { it.copy(showSettingsDialog = false) }
    }

    fun dismissMatrixDialog() {
        _uiState.update { it.copy(showMatrixDialog = false) }
    }

    fun insertMatrix(rows: Int, cols: Int) {
        prefs?.edit {
            putInt("matrix_rows", rows)
            putInt("matrix_cols", cols)
        }
        _uiState.update { it.copy(showMatrixDialog = false, matrixRows = rows, matrixCols = cols) }
        insertNode(MatrixNode(rows, cols, List(rows * cols) { MathString() }))
    }

    fun insertUnit(symbol: String) {
        insertNode(UnitNode(symbol))
        dismissUnitsDialog()
    }

    /** The alternative reading of "a + b%" with its result, if the line is of that kind and has one. */
    private suspend fun percentHint(expression: MathString, result: CalculationResult, context: MathContext): PercentHint? {
        if (!result.isSuccess) return null
        val alternative = MathEngine.percentOfFirstAlternative(expression) ?: return null
        val alternativeResult = MathEngine.calculate(alternative.expression, context)
        if (!alternativeResult.isSuccess) return null
        return PercentHint(
            alternative.expression, alternativeResult,
            base = MathText.toPlainText(alternative.base), percent = MathText.toPlainText(alternative.percent), subtracts = alternative.subtracts
        )
    }

    /** Replaces the line by the pocket-calculator reading of its percent: 200 + 10% becomes 200 + 200 × 10%. */
    fun applyPercentHint() {
        val hint = _uiState.value.percentHint ?: return
        _uiState.update {
            it.copy(expression = hint.expression.copy(), cursorPath = listOf(hint.expression.length), selectionRange = null, percentHint = null)
        }
    }

    /**
     * Opens the steps of the line being typed: its solution if it has one, otherwise the steps of writing it in another form
     * (multiplied out, factored, as one fraction); the sheet offers the others.
     */
    fun showSteps() {
        val state = _uiState.value
        val options = buildList {
            if (state.hasSteps) add(null)
            addAll(state.transforms)
        }
        if (options.isEmpty()) return
        loadSteps(options.first(), options)
    }

    /** Switches the sheet to the steps of [option]: the solution (null) or a form. */
    fun selectStepsOption(option: Transform?) {
        val steps = _uiState.value.steps ?: return
        loadSteps(option, steps.options)
    }

    private fun loadSteps(option: Transform?, options: List<Transform?>) {
        val state = _uiState.value
        val input = state.expression
        val cached = if (option == null) cachedSteps?.takeIf { it.solution != null && it.input == LiveInput(input, state.snapshot()) } else null
        if (cached != null) {
            stepsJob?.cancel()
            _uiState.update { it.copy(steps = StepsState(input, cached.solution, isLoading = false, options = options, selected = option)) }
            return
        }
        _uiState.update { it.copy(steps = StepsState(input, options = options, selected = option)) }
        stepsJob?.cancel()
        stepsJob = viewModelScope.launch {
            val solution = MathEngine.steps(input, state.snapshot().toMathContext(), transform = option)
            _uiState.update {
                if (it.steps?.input == input && it.steps.selected == option) it.copy(steps = StepsState(input, solution, isLoading = false, options = options, selected = option)) else it
            }
        }
    }

    fun dismissSteps() {
        stepsJob?.cancel()
        _uiState.update { it.copy(steps = null) }
    }

    fun dismissFormulasDialog() {
        _uiState.update { it.copy(showFormulasDialog = false) }
    }

    /**
     * Inserts a formula from the picker like a paste (replacing the selection), with the
     * cursor in its first empty slot or before its first comma, ready for the first value.
     */
    fun insertFormula(template: MathString) {
        dismissFormulasDialog()
        val state = _uiState.value
        // A function of a list wraps the selection like the function keys: selecting the
        // typed values "2, 4, 9" and picking the mean gives mean(2, 4, 9)
        val function = template.nodes.singleOrNull() as? FunctionNode
        if (function != null && function.argument.isEmpty && state.selectionRange?.isEmpty() == false) {
            insertNode(function)
            return
        }
        val stringPath = state.cursorPath.dropLast(1)
        val start = state.selectionRange?.takeIf { !it.isEmpty() }?.first ?: state.cursorPath.last()
        if (!paste(template)) return
        val target = getTargetString(_uiState.value.expression, stringPath)
        val blank = (start until start + template.length).firstNotNullOfOrNull { i ->
            target.nodes.getOrNull(i)?.let { node -> firstBlankIn(node)?.let { listOf(i) + it } }
        } ?: return
        _uiState.update { it.copy(cursorPath = stringPath + blank) }
    }

    /** The caret path (relative to [node]) of its first empty slot or slot starting with a comma. */
    private fun firstBlankIn(node: MathNode): List<Int>? {
        node.subStrings.forEachIndexed { child, string ->
            if (string.isEmpty || string.nodes.first() is SeparatorNode) return listOf(child, 0)
            string.nodes.forEachIndexed { i, inner -> firstBlankIn(inner)?.let { return listOf(child, i) + it } }
        }
        return null
    }

    fun insertConstant(symbol: String) {
        insertNode(ConstantNode(symbol))
        dismissConstantsDialog()
    }

    fun selectHistoryItem(item: CalculationResult) {
        _uiState.update {
            it.copy(
                expression = item.input.copy(),
                cursorPath = listOf(item.input.length),
                selectionRange = null
            )
        }
    }

    fun useResultAsInput(item: CalculationResult) {
        val answer = item.answer ?: return
        _uiState.update {
            it.copy(
                expression = answer.copy(),
                cursorPath = listOf(answer.length),
                selectionRange = null
            )
        }
    }

    /** Inserts "Ans", which stands for the most recently committed result when calculated. */
    fun insertAns() = insertNode(AnsNode)

    private fun getTargetString(root: MathString, path: List<Int>): MathString {
        var current = root
        for (i in 0 until path.size - 1 step 2) {
            val nodeIdx = path[i]
            val childIdx = path[i + 1]
            if (nodeIdx in current.nodes.indices) {
                val node = current.nodes[nodeIdx]
                if (childIdx in node.subStrings.indices) {
                    current = node.subStrings[childIdx]
                }
            }
        }
        return current
    }

    private fun insertNode(node: MathNode) {
        val root = _uiState.value.expression.copy()
        val path = _uiState.value.cursorPath
        val target = getTargetString(root, path)
        val range = _uiState.value.selectionRange

        val pos: Int
        var nodeToInsert = node
        if (range != null && !range.isEmpty()) {
            val start = range.first.coerceIn(0, target.length)
            val end = (range.last + 1).coerceIn(start, target.length)
            val removed = mutableListOf<MathNode>()
            for (i in (end - 1) downTo start) {
                if (i in target.nodes.indices) {
                    removed.add(0, target.removeAt(i)!!)
                }
            }
            // Structured nodes wrap the selection (e.g. selecting "2x" and pressing sin gives
            // sin(2x)) instead of replacing it
            val wrapSlot = mainSlotIndex(node)
            if (wrapSlot != null && removed.isNotEmpty()) {
                nodeToInsert = node.replaceSubString(wrapSlot, MathString(removed))
            }
            pos = start
        } else {
            pos = path.last().coerceIn(0, target.length)
        }

        target.insert(pos, nodeToInsert)

        val containerPath = if (path.isNotEmpty()) path.subList(0, path.size - 1) else emptyList()
        val firstEmptySlot = nodeToInsert.subStrings.indexOfFirst { it.isEmpty }
        val newPath = if (firstEmptySlot >= 0) {
            containerPath + listOf(pos, firstEmptySlot, 0)
        } else {
            containerPath + (pos + 1)
        }

        _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
    }

    /**
     * Starts a new line. Lines only exist at the top level: inside a fraction, root, ... the
     * new line starts after that whole structure.
     */
    private fun insertLineBreak() {
        val path = _uiState.value.cursorPath
        if (path.size == 1) {
            insertNode(LineBreakNode)
            return
        }
        val root = _uiState.value.expression.copy()
        val pos = path.first() + 1
        root.insert(pos, LineBreakNode)
        _uiState.update { it.copy(expression = root, cursorPath = listOf(pos + 1), selectionRange = null) }
    }

    /** The slot that receives a wrapped selection, e.g. the argument of a function. */
    private fun mainSlotIndex(node: MathNode): Int? = when (node) {
        is FunctionNode, is ParenthesesNode -> 0
        is LogBaseNode -> 1
        is RadicalNode -> if (node.degree != null) 1 else 0
        is IntegralNode -> 2
        is DerivativeNode, is AntiderivativeNode -> 0
        is BigOperatorNode -> 2
        is LimitNode -> 1
        else -> null
    }

    private fun onParenthesesAction() {
        val state = _uiState.value
        val root = state.expression.copy()
        val path = state.cursorPath
        val target = getTargetString(root, path)
        val range = state.selectionRange

        if (range != null && !range.isEmpty()) {
            val start = range.first.coerceIn(0, target.length)
            val end = (range.last + 1).coerceIn(start, target.length)
            val cutNodes = mutableListOf<MathNode>()
            for (i in (end - 1) downTo start) {
                if (i in target.nodes.indices) {
                    cutNodes.add(0, target.removeAt(i)!!)
                }
            }
            val parenNode = ParenthesesNode(MathString(cutNodes))
            target.insert(start, parenNode)
            val containerPath = if (path.isNotEmpty()) path.subList(0, path.size - 1) else emptyList()
            val newPath = containerPath + (start + 1)
            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
            return
        }

        // Always insert a new pair (even right inside another one), so parentheses can be nested
        insertNode(ParenthesesNode(MathString()))
    }

    private fun insertEE() {
        val root = _uiState.value.expression.copy()
        val path = _uiState.value.cursorPath
        val target = getTargetString(root, path)
        val pos = path.last().coerceIn(0, target.length)

        // "×10ⁿ" attached to the number before the cursor; the cursor goes into the exponent
        target.insert(pos, SciExponentNode())

        val containerPath = if (path.isNotEmpty()) path.subList(0, path.size - 1) else emptyList()
        val newPath = containerPath + listOf(pos, 0, 0)
        _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
    }

    private fun insertDivision() {
        val root = _uiState.value.expression.copy()
        val path = _uiState.value.cursorPath
        val target = getTargetString(root, path)
        val range = _uiState.value.selectionRange
        val containerPath = if (path.isNotEmpty()) path.subList(0, path.size - 1) else emptyList()

        val numString = MathString()
        val insertIdx: Int
        var cursorInNumerator = false

        if (range != null && !range.isEmpty()) {
            val start = range.first.coerceIn(0, target.length)
            val end = (range.last + 1).coerceIn(start, target.length)
            for (i in (end - 1) downTo start) {
                if (i in target.nodes.indices) {
                    numString.insert(0, target.removeAt(i)!!)
                }
            }
            insertIdx = start
        } else {
            val pos = path.last().coerceIn(0, target.length)
            if (pos > 0) {
                val lastNode = target.nodeAt(pos - 1)
                if (lastNode is OperatorNode) {
                    // Operator before division: DO NOT move operator into numerator!
                    insertIdx = pos
                    cursorInNumerator = true
                } else if (isNumberPart(lastNode) || isUnitLike(lastNode)) {
                    // Take the whole quantity as numerator: a number ("12", "5×10³"), or a number
                    // with its units ("5 mmol", "3 m²"). A unit without a number stays alone (km ÷ h).
                    insertIdx = takeNumberBefore(target, pos, includeUnits = true, into = numString)
                } else {
                    val prevNode = target.removeAt(pos - 1)!!
                    numString.insert(0, prevNode)
                    insertIdx = pos - 1
                }
            } else {
                insertIdx = pos
                cursorInNumerator = true
            }
        }

        val fracNode = FractionNode(numString, MathString())
        target.insert(insertIdx, fracNode)

        val newPath = if (cursorInNumerator) {
            containerPath + listOf(insertIdx, 0, 0)
        } else {
            containerPath + listOf(insertIdx, 1, 0)
        }
        _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
    }

    /**
     * Inserts a mixed number. Digits right before the cursor (or a selection) become its whole
     * part and the cursor goes to the numerator; otherwise it starts in the whole part.
     */
    private fun insertMixedFraction() =
        insertWithNumberAsFirstSlot { whole -> MixedFractionNode(whole, MathString(), MathString()) }

    /**
     * Inserts the node [create] makes from the digits right before the cursor (or a selection),
     * which fill its first slot; the cursor goes to the next slot, or into the first one if
     * there were no digits.
     */
    private fun insertWithNumberAsFirstSlot(create: (MathString) -> MathNode) {
        val root = _uiState.value.expression.copy()
        val path = _uiState.value.cursorPath
        val target = getTargetString(root, path)
        val range = _uiState.value.selectionRange
        val containerPath = path.dropLast(1)

        val whole = MathString()
        val insertIdx: Int
        if (range != null && !range.isEmpty()) {
            val start = range.first.coerceIn(0, target.length)
            val end = (range.last + 1).coerceIn(start, target.length)
            for (i in (end - 1) downTo start) whole.insert(0, target.removeAt(i)!!)
            insertIdx = start
        } else {
            var pos = path.last().coerceIn(0, target.length)
            while (pos > 0 && target.nodeAt(pos - 1) is NumberNode) {
                whole.insert(0, target.removeAt(pos - 1)!!)
                pos--
            }
            insertIdx = pos
        }

        target.insert(insertIdx, create(whole))
        val slot = if (whole.isEmpty) 0 else 1
        _uiState.update { it.copy(expression = root, cursorPath = containerPath + listOf(insertIdx, slot, 0), selectionRange = null) }
    }

    private fun isNumberPart(node: MathNode): Boolean = node is NumberNode || node is SciExponentNode

    /**
     * Moves the number ending at [pos] (digits plus an optional "×10ⁿ", and with
     * [includeUnits] also the units after it) from [target] into [into].
     * Returns the index where the number started.
     */
    private fun takeNumberBefore(target: MathString, pos: Int, includeUnits: Boolean, into: MathString): Int {
        val taken = mutableListOf<MathNode>()
        var p = pos - 1
        fun takeWhile(predicate: (MathNode) -> Boolean, max: Int = Int.MAX_VALUE) {
            var count = 0
            while (p >= 0 && count < max && predicate(target.nodeAt(p))) {
                taken.add(0, target.removeAt(p)!!)
                p--
                count++
            }
        }
        if (includeUnits) takeWhile({ isUnitLike(it) })
        takeWhile({ it is SciExponentNode }, max = 1)
        takeWhile({ it is NumberNode })
        into.insertAll(0, taken)
        return p + 1
    }

    /** A unit, possibly raised to a power (m, km/h, m²). */
    private fun isUnitLike(node: MathNode): Boolean =
        node is UnitNode || (node is ExponentNode && node.base?.let { base ->
            base.isNotEmpty && base.nodes.all { it is UnitNode }
        } == true)

    private fun insertPower(exponentStr: MathString = MathString()) {
        val root = _uiState.value.expression.copy()
        val path = _uiState.value.cursorPath
        val target = getTargetString(root, path)
        val range = _uiState.value.selectionRange
        val containerPath = if (path.isNotEmpty()) path.subList(0, path.size - 1) else emptyList()

        val baseStr = MathString()
        val insertIdx: Int
        if (range != null && !range.isEmpty()) {
            val start = range.first.coerceIn(0, target.length)
            val end = (range.last + 1).coerceIn(start, target.length)
            for (i in (end - 1) downTo start) {
                if (i in target.nodes.indices) {
                    baseStr.insert(0, target.removeAt(i)!!)
                }
            }
            insertIdx = start
        } else {
            val pos = path.last().coerceIn(0, target.length)
            if (pos > 0) {
                val lastNode = target.nodeAt(pos - 1)
                if (isNumberPart(lastNode)) {
                    // The whole number is the base, including a scientific part: (5×10³)²
                    insertIdx = takeNumberBefore(target, pos, includeUnits = false, into = baseStr)
                } else if (lastNode !is OperatorNode) {
                    val prevNode = target.removeAt(pos - 1)!!
                    baseStr.insert(0, prevNode)
                    insertIdx = pos - 1
                } else {
                    insertIdx = pos
                }
            } else {
                insertIdx = pos
            }
        }

        // The base slot always exists (child 0); when nothing could be taken as the base it
        // stays empty and receives the cursor first.
        val expNode = ExponentNode(baseStr, exponentStr)
        target.insert(insertIdx, expNode)

        val newPath = when {
            baseStr.isEmpty -> containerPath + listOf(insertIdx, 0, 0)
            exponentStr.isEmpty -> containerPath + listOf(insertIdx, 1, 0)
            else -> containerPath + (insertIdx + 1)
        }
        _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
    }

    private fun insertSqrt() {
        val root = _uiState.value.expression.copy()
        val path = _uiState.value.cursorPath
        val target = getTargetString(root, path)
        val range = _uiState.value.selectionRange
        val containerPath = if (path.isNotEmpty()) path.subList(0, path.size - 1) else emptyList()

        val radString = MathString()
        val insertIdx: Int
        if (range != null && !range.isEmpty()) {
            val start = range.first.coerceIn(0, target.length)
            val end = (range.last + 1).coerceIn(start, target.length)
            for (i in (end - 1) downTo start) {
                if (i in target.nodes.indices) {
                    radString.insert(0, target.removeAt(i)!!)
                }
            }
            insertIdx = start
        } else {
            insertIdx = path.last().coerceIn(0, target.length)
        }

        val radNode = RadicalNode(null, radString)
        target.insert(insertIdx, radNode)

        val newPath = containerPath + listOf(insertIdx, 0, radString.length)
        _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
    }

    private fun deleteAtCursor() {
        val range = _uiState.value.selectionRange
        val root = _uiState.value.expression.copy()
        val path = _uiState.value.cursorPath
        val target = getTargetString(root, path)

        if (range != null && !range.isEmpty()) {
            val start = range.first.coerceIn(0, target.length)
            val end = (range.last + 1).coerceIn(start, target.length)
            for (i in (end - 1) downTo start) {
                if (i in target.nodes.indices) {
                    target.removeAt(i)
                }
            }
            val containerPath = if (path.isNotEmpty()) path.subList(0, path.size - 1) else emptyList()
            val newPath = containerPath + start
            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
            return
        }

        val pos = path.last()
        if (pos > 0) {
            val nodeBefore = target.nodeAt(pos - 1)
            if (nodeBefore.subStrings.any { it.isNotEmpty }) {
                moveCursorLeft()
                return
            }
            target.removeAt(pos - 1)
            _uiState.update {
                it.copy(
                    expression = root,
                    cursorPath = path.toMutableList().apply { this[lastIndex] = pos - 1 },
                    selectionRange = null
                )
            }
        } else if (path.size >= 3) {
            // Cursor is at pos 0 inside a child of a composite node
            val childIdx = path[path.size - 2]
            val containerNodeIdx = path[path.size - 3]
            val parentPath = path.subList(0, path.size - 3)
            val parentString = getTargetString(root, parentPath)

            if (containerNodeIdx in parentString.nodes.indices) {
                val containerNode = parentString.nodeAt(containerNodeIdx)

                when (containerNode) {
                    is FractionNode -> {
                        if (childIdx == 1 && target.isEmpty) {
                            // In empty denominator: remove fraction, keep numerator
                            parentString.removeAt(containerNodeIdx)
                            val numNodes = containerNode.numerator.nodes.map { it.copy() }
                            parentString.insertAll(containerNodeIdx, numNodes)
                            val newPath = parentPath + (containerNodeIdx + numNodes.size)
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        } else if (childIdx == 0 && target.isEmpty) {
                            // In empty numerator: remove fraction, keep denominator
                            parentString.removeAt(containerNodeIdx)
                            val denNodes = containerNode.denominator.nodes.map { it.copy() }
                            parentString.insertAll(containerNodeIdx, denNodes)
                            val newPath = parentPath + containerNodeIdx
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        }
                    }
                    is ExponentNode -> {
                        // Child indices follow ExponentNode.subStrings: [base, exponent] or [exponent]
                        val base = containerNode.base
                        val exponentIdx = if (base != null) 1 else 0
                        if (childIdx == exponentIdx && target.isEmpty) {
                            // In empty exponent: remove exponent, keep base
                            parentString.removeAt(containerNodeIdx)
                            val baseNodes = base?.nodes?.map { it.copy() } ?: emptyList()
                            parentString.insertAll(containerNodeIdx, baseNodes)
                            val newPath = parentPath + (containerNodeIdx + baseNodes.size)
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        } else if (base != null && childIdx == 0 && target.isEmpty) {
                            // In empty base: remove the power, keep the exponent's content
                            parentString.removeAt(containerNodeIdx)
                            val expNodes = containerNode.exponent.nodes.map { it.copy() }
                            parentString.insertAll(containerNodeIdx, expNodes)
                            val newPath = parentPath + containerNodeIdx
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        }
                    }
                    is RadicalNode -> {
                        // Child indices follow RadicalNode.subStrings: [degree, radicand] or [radicand]
                        val degree = containerNode.degree
                        val radicandIdx = if (degree != null) 1 else 0
                        if (childIdx == radicandIdx && target.isEmpty) {
                            parentString.removeAt(containerNodeIdx)
                            val degNodes = degree?.nodes?.map { it.copy() } ?: emptyList()
                            parentString.insertAll(containerNodeIdx, degNodes)
                            val newPath = parentPath + (containerNodeIdx + degNodes.size)
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        } else if (degree != null && childIdx == 0 && target.isEmpty) {
                            // In empty degree: turn the n-th root into a square root, keep the radicand
                            parentString.setNode(containerNodeIdx, RadicalNode(null, containerNode.radicand.copy()))
                            val newPath = parentPath + listOf(containerNodeIdx, 0, 0)
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        }
                    }
                    is ParenthesesNode -> {
                        if (target.isEmpty) {
                            parentString.removeAt(containerNodeIdx)
                            val newPath = parentPath + containerNodeIdx
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        }
                    }
                    is FunctionNode -> {
                        if (target.isEmpty) {
                            parentString.removeAt(containerNodeIdx)
                            val newPath = parentPath + containerNodeIdx
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        }
                    }
                    is MixedFractionNode, is PolarNode -> {
                        // Everything but the first part empty: back to the plain number
                        if (target.isEmpty && containerNode.subStrings.drop(1).all { it.isEmpty }) {
                            parentString.removeAt(containerNodeIdx)
                            val wholeNodes = containerNode.subStrings[0].nodes.map { it.copy() }
                            parentString.insertAll(containerNodeIdx, wholeNodes)
                            val newPath = parentPath + (containerNodeIdx + wholeNodes.size)
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        }
                    }
                    is BigOperatorNode, is LimitNode -> {
                        // Untouched apart from the prefilled "n=" / "x→": remove it as a whole
                        val slots = containerNode.subStrings
                        if (slots[0].length <= 2 && slots.drop(1).all { it.isEmpty }) {
                            parentString.removeAt(containerNodeIdx)
                            val newPath = parentPath + containerNodeIdx
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        }
                    }
                    else -> {
                        if (containerNode.subStrings.all { it.isEmpty }) {
                            parentString.removeAt(containerNodeIdx)
                            val newPath = parentPath + containerNodeIdx
                            _uiState.update { it.copy(expression = root, cursorPath = newPath, selectionRange = null) }
                            return
                        }
                    }
                }
            }
            moveCursorLeft()
        } else if (path.size > 1) {
            moveCursorLeft()
        }
    }

    /** Removes a single calculation from the history (e.g. swiped away). */
    fun deleteHistoryEntry(id: Long) {
        _uiState.update { state -> state.copy(history = state.history.filterNot { it.id == id }) }
    }

    fun clearLine() {
        _uiState.update {
            it.copy(
                expression = MathString(),
                cursorPath = listOf(0),
                selectionRange = null
            )
        }
    }

    fun clearAll() {
        restoreJob?.cancel() // do not bring back what was just cleared
        unrestoredEntries = emptyList()
        _uiState.update {
            it.copy(
                expression = MathString(),
                cursorPath = listOf(0),
                selectionRange = null,
                history = emptyList()
            )
        }
    }

    private fun moveCursorLeft() {
        val path = _uiState.value.cursorPath.toMutableList()
        val pos = path.last()
        val root = _uiState.value.expression

        if (pos > 0) {
            val target = getTargetString(root, path)
            val nodeBefore = target.nodeAt(pos - 1)
            if (nodeBefore.subStrings.isNotEmpty()) {
                path[path.lastIndex] = pos - 1
                val lastChildIdx = nodeBefore.subStrings.size - 1
                path.add(lastChildIdx)
                path.add(nodeBefore.subStrings[lastChildIdx].length)
            } else {
                path[path.lastIndex] = pos - 1
            }
        } else if (path.size > 1) {
            val childIdx = path[path.size - 2]
            val containerPath = path.subList(0, path.size - 2)
            val containerTarget = getTargetString(root, containerPath)
            val containerNodeIdx = containerPath.last()
            val containerNode = containerTarget.nodeAt(containerNodeIdx)

            if (childIdx > 0) {
                path[path.size - 2] = childIdx - 1
                val prevChild = containerNode.subStrings[childIdx - 1]
                path[path.size - 1] = prevChild.length
            } else {
                path.removeAt(path.lastIndex)
                path.removeAt(path.lastIndex)
            }
        }
        _uiState.update { it.copy(cursorPath = path, selectionRange = null) }
    }

    private fun moveCursorRight() {
        val path = _uiState.value.cursorPath.toMutableList()
        val pos = path.last()
        val root = _uiState.value.expression
        val target = getTargetString(root, path)

        if (pos < target.length) {
            val nodeAt = target.nodeAt(pos)
            if (nodeAt.subStrings.isNotEmpty()) {
                path[path.lastIndex] = pos
                path.add(0)
                path.add(0)
            } else {
                path[path.lastIndex] = pos + 1
            }
        } else if (path.size > 1) {
            val childIdx = path[path.size - 2]
            val containerPath = path.subList(0, path.size - 2)
            val containerTarget = getTargetString(root, containerPath)
            val containerNodeIdx = containerPath.last()
            val containerNode = containerTarget.nodeAt(containerNodeIdx)

            if (childIdx < containerNode.subStrings.size - 1) {
                path[path.size - 2] = childIdx + 1
                path[path.size - 1] = 0
            } else {
                path.removeAt(path.lastIndex)
                path.removeAt(path.lastIndex)
                path[path.lastIndex] = path.last() + 1
            }
        }
        _uiState.update { it.copy(cursorPath = path, selectionRange = null) }
    }

    /**
     * Enter: moves the current line and its result into the history and clears the line.
     * An invalid line stays in place and its error is shown instead.
     */
    fun commit() {
        val state = _uiState.value
        val expr = state.expression
        if (expr.isEmpty) return

        // While the saved history is being restored, variables and Ans are incomplete: wait for it,
        // and do not trust a live result calculated meanwhile
        val wasRestoring = restoreJob?.isActive == true
        commitJob?.cancel()
        commitJob = viewModelScope.launch {
            restoreJob?.join()
            val current = _uiState.value
            if (current.expression != expr) return@launch // edited meanwhile
            val snapshot = current.snapshot()
            // Reuse the live result if it is up to date, otherwise calculate right away
            val res = current.result?.takeIf { !wasRestoring && it.input == expr }
                ?: MathEngine.calculate(expr, snapshot.toMathContext())
            if (_uiState.value.expression != expr) return@launch // edited meanwhile
            if (!res.isSuccess) {
                _uiState.update { it.copy(result = res, showResultError = true) }
                return@launch
            }
            // Taken outside of update: it runs again when another update comes in between
            val id = nextHistoryId++
            val relevant = snapshot.relevantTo(expr)
            _uiState.update {
                it.copy(
                    history = listOf(HistoryEntry(id, res, it.resultForm, relevant)) + it.history,
                    expression = MathString(),
                    cursorPath = listOf(0),
                    selectionRange = null,
                    result = null
                )
            }
        }
    }

    private fun updatePlotData() {
        val state = _uiState.value
        val expr = state.expression
        plotJob?.cancel()
        if (expr.isEmpty) {
            _uiState.update { it.copy(plotCurve = null, isPlotLoading = false) }
            return
        }
        _uiState.update { it.copy(plotCurve = null, isPlotLoading = true) }
        plotJob = viewModelScope.launch {
            val context = state.snapshot().toMathContext()
            val curve = MathEngine.createPlotCurve(expr, context)
            _uiState.update { it.copy(plotCurve = curve, isPlotLoading = false) }
        }
    }

    private companion object {
        /** Saving waits for this long without changes, so that typing does not save on every key. */
        const val SAVE_DELAY_MILLIS = 400L

        /** Longer than a normal calculation may take: the app has just started and calculates the whole history at once. */
        const val RESTORE_TIMEOUT_MILLIS = 15_000L

        val SOURCE_DELIMITERS = setOf(MathOp.EQUALS, MathOp.DEFINE, MathOp.CONVERT, MathOp.LESS, MathOp.GREATER, MathOp.LESS_EQUAL, MathOp.GREATER_EQUAL)
    }
}
