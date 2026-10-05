package com.vortessence.calculator.viewmodel

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vortessence.calculator.core.math.*
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.UnitSystem
import com.vortessence.calculator.core.math.expressions.UnitRegistry
import com.vortessence.calculator.core.math.solvers.PlotCurve
import com.vortessence.calculator.core.math.type.*
import com.vortessence.calculator.ui.keypad.KeyAction
import com.vortessence.calculator.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalculatorUiState(
    val expression: MathString = MathString(),
    val cursorPath: List<Int> = listOf(0),
    val selectionRange: IntRange? = null,
    /** Live result of the current [expression], updated while typing. */
    val result: CalculationResult? = null,
    /** True after Enter was pressed on an invalid line: show its error even if it is "incomplete". */
    val showResultError: Boolean = false,
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
    val showSettingsDialog: Boolean = false,
    val showMatrixDialog: Boolean = false,
    /** Size of the most recently inserted matrix, preselected in the matrix dialog. */
    val matrixRows: Int = 2,
    val matrixCols: Int = 2
)

/**
 * A committed calculation, shown in the [form] chosen when it was committed. [id] is stable,
 * so list items can be animated and removed.
 */
data class HistoryEntry(val id: Long, val calculation: CalculationResult, val form: ResultForm = ResultForm.EXACT)

/**
 * @param liveResultDelayMillis Debounce before the live result is recomputed after a change,
 * so that fast typing does not start a calculation for every keystroke.
 */
class CalculatorViewModel @JvmOverloads constructor(
    application: Application,
    private val liveResultDelayMillis: Long = 120L
) : AndroidViewModel(application) {

    private val prefs = try {
        application.getSharedPreferences("calculator_settings", Context.MODE_PRIVATE)
    } catch (e: Exception) {
        null
    }

    private val _uiState = MutableStateFlow(CalculatorUiState())
    val uiState: StateFlow<CalculatorUiState> = _uiState.asStateFlow()

    private var nextHistoryId = 0L

    /** Fixed end(s) of the selection while it is being extended by dragging. */
    private var selectionAnchors: List<List<Int>> = emptyList()

    private var commitJob: Job? = null
    private var plotJob: Job? = null

    init {
        loadSettings()
        observeLiveResult()
    }

    /**
     * Recomputes the live result whenever the expression or a calculation setting changes.
     * collectLatest cancels a still running calculation as soon as the input changes again.
     */
    private fun observeLiveResult() {
        viewModelScope.launch {
            _uiState
                .map { LiveInput(it.expression, it.mathContext()) }
                .distinctUntilChanged()
                .collectLatest { input ->
                    if (input.expression.isEmpty) {
                        _uiState.update { it.copy(result = null, showResultError = false) }
                        return@collectLatest
                    }
                    if (liveResultDelayMillis > 0) delay(liveResultDelayMillis)
                    val res = MathEngine.calculate(input.expression, input.settings.toMathContext())
                    _uiState.update {
                        if (it.expression == input.expression) it.copy(result = res, showResultError = false) else it
                    }
                }
        }
    }

    /** The expression and all calculation settings; a change of any of them updates the live result. */
    private data class LiveInput(val expression: MathString, val settings: Settings)

    private data class Settings(
        val angleUnit: AngleUnit,
        val unitSystem: UnitSystem,
        val decimalPlaces: Int?,
        val ans: MathString?,
        val variables: Map<String, MathString>
    ) {
        // Always exact; decimal display is a matter of the result form
        fun toMathContext() = MathContext(
            mode = CalculationMode.EXACT,
            angleUnit = angleUnit,
            unitSystem = unitSystem,
            decimalPlaces = decimalPlaces,
            ans = ans,
            variables = variables
        )
    }

    private fun CalculatorUiState.mathContext() =
        Settings(angleUnit, unitSystem, decimalPlaces, ans(), variables())

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
        val matrixRows = prefs?.getInt("matrix_rows", 2) ?: 2
        val matrixCols = prefs?.getInt("matrix_cols", 2) ?: 2
        val savedTheme = prefs?.getString("theme_mode", null)
        val themeMode = ThemeMode.entries.firstOrNull { it.name == savedTheme } ?: ThemeMode.SYSTEM
        _uiState.update {
            it.copy(
                resultForm = form,
                angleUnit = angle,
                unitSystem = unitSystem,
                decimalPlaces = places,
                digitGrouping = grouping,
                themeMode = themeMode,
                matrixRows = matrixRows,
                matrixCols = matrixCols
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
                        "!" -> MathOp.FACTORIAL
                        else -> MathOp.PLUS
                    }
                    insertNode(OperatorNode(op))
                }
            }
            is KeyAction.Variable -> insertNode(VariableNode(action.name))
            is KeyAction.Constant -> insertNode(ConstantNode(action.symbol))
            is KeyAction.Function -> insertNode(FunctionNode(action.name, MathString()))
            is KeyAction.LogBase -> insertNode(LogBaseNode())
            is KeyAction.EE -> insertEE()
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
            is KeyAction.OpenGraph -> toggleGraph()
            is KeyAction.OpenSettings -> _uiState.update { it.copy(showSettingsDialog = true) }
            is KeyAction.Integral -> insertNode(IntegralNode())
            is KeyAction.Matrix -> _uiState.update { it.copy(showMatrixDialog = true) }
            is KeyAction.Transpose -> insertPower(MathString(listOf(VariableNode("T"))))
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

    fun setThemeMode(mode: ThemeMode) {
        prefs?.edit { putString("theme_mode", mode.name) }
        _uiState.update { it.copy(themeMode = mode) }
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
        val dimension = MathEngine.unitDimension(conversionSource(state), state.mathContext().toMathContext())
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

        commitJob?.cancel()
        commitJob = viewModelScope.launch {
            // Reuse the live result if it is up to date, otherwise calculate right away
            val res = state.result?.takeIf { it.input == expr }
                ?: MathEngine.calculate(expr, state.mathContext().toMathContext())
            if (_uiState.value.expression != expr) return@launch // edited meanwhile
            if (!res.isSuccess) {
                _uiState.update { it.copy(result = res, showResultError = true) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    history = listOf(HistoryEntry(nextHistoryId++, res, it.resultForm)) + it.history,
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
            val context = state.mathContext().toMathContext()
            val curve = MathEngine.createPlotCurve(expr, context)
            _uiState.update { it.copy(plotCurve = curve, isPlotLoading = false) }
        }
    }

    private companion object {
        val SOURCE_DELIMITERS = setOf(MathOp.EQUALS, MathOp.DEFINE, MathOp.CONVERT)
    }
}
