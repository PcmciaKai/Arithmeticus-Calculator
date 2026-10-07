import android.app.Application
import com.vortessence.calculator.ui.theme.ThemeMode
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.AnsNode
import com.vortessence.calculator.core.math.type.BigOperatorNode
import com.vortessence.calculator.core.math.type.LimitNode
import com.vortessence.calculator.core.math.type.MixedFractionNode
import com.vortessence.calculator.core.math.type.ExponentNode
import com.vortessence.calculator.core.math.type.FunctionNode
import com.vortessence.calculator.core.math.type.LogBaseNode
import com.vortessence.calculator.core.math.type.MatrixNode
import com.vortessence.calculator.core.math.type.VariableNode
import com.vortessence.calculator.core.math.type.RadicalNode
import com.vortessence.calculator.core.math.type.SciExponentNode
import com.vortessence.calculator.core.math.type.FractionNode
import com.vortessence.calculator.core.math.type.LineBreakNode
import com.vortessence.calculator.core.math.type.OperatorNode
import com.vortessence.calculator.core.math.type.ParenthesesNode
import com.vortessence.calculator.ui.keypad.KeyAction
import com.vortessence.calculator.viewmodel.CalculatorViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val viewModels = mutableListOf<CalculatorViewModel>()

    /** Creates a ViewModel whose background work is shut down after the test. */
    private fun newViewModel(): CalculatorViewModel =
        CalculatorViewModel(Application(), liveResultDelayMillis = 0).also { viewModels.add(it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        // Live results are computed in the background and resume on Dispatchers.Main, so they
        // must be finished before Main is reset (otherwise they leak into the next test)
        runBlocking {
            viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        }
        Dispatchers.resetMain()
    }

    @Test
    fun testDivisionAutoWrapsPrecedingNumber() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.Number("2"))

        // Press divide '÷'
        vm.onKeyAction(KeyAction.Operator("÷"))

        val state = vm.uiState.value
        assertEquals(1, state.expression.length)
        val frac = state.expression.nodeAt(0) as? FractionNode
        assertNotNull(frac)
        assertEquals("12", frac!!.numerator.toString())
        assertEquals("", frac.denominator.toString())
        // Cursor should be inside denominator
        assertEquals(listOf(0, 1, 0), state.cursorPath)
    }

    @Test
    fun testSymmetricalCursorNavigationThroughFraction() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("5"))
        vm.onKeyAction(KeyAction.Operator("÷"))
        vm.onKeyAction(KeyAction.Number("9"))

        // Expression is 5/9, cursor in denominator at pos 1 [0, 1, 1]
        assertEquals(listOf(0, 1, 1), vm.uiState.value.cursorPath)

        // Move left: pos 1 -> pos 0 in denominator
        vm.onKeyAction(KeyAction.MoveLeft)
        assertEquals(listOf(0, 1, 0), vm.uiState.value.cursorPath)

        // Move left: should jump to end of numerator [0, 0, 1]!
        vm.onKeyAction(KeyAction.MoveLeft)
        assertEquals(listOf(0, 0, 1), vm.uiState.value.cursorPath)

        // Move left: start of numerator [0, 0, 0]
        vm.onKeyAction(KeyAction.MoveLeft)
        assertEquals(listOf(0, 0, 0), vm.uiState.value.cursorPath)

        // Move left: before the fraction [0]
        vm.onKeyAction(KeyAction.MoveLeft)
        assertEquals(listOf(0), vm.uiState.value.cursorPath)

        // Now move right: into numerator [0, 0, 0]
        vm.onKeyAction(KeyAction.MoveRight)
        assertEquals(listOf(0, 0, 0), vm.uiState.value.cursorPath)

        // Move right: end of numerator [0, 0, 1]
        vm.onKeyAction(KeyAction.MoveRight)
        assertEquals(listOf(0, 0, 1), vm.uiState.value.cursorPath)

        // Move right: start of denominator [0, 1, 0]
        vm.onKeyAction(KeyAction.MoveRight)
        assertEquals(listOf(0, 1, 0), vm.uiState.value.cursorPath)
    }

    @Test
    fun testSmartBackspaceDoesNotNukeFraction() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("4"))
        vm.onKeyAction(KeyAction.Operator("÷"))
        vm.onKeyAction(KeyAction.Number("2"))

        // Move cursor right to exit fraction [1]
        vm.onKeyAction(KeyAction.MoveRight)
        assertEquals(listOf(1), vm.uiState.value.cursorPath)

        // Backspace should step inside denominator rather than deleting whole fraction
        vm.onKeyAction(KeyAction.Backspace)
        assertEquals(listOf(0, 1, 1), vm.uiState.value.cursorPath)
        assertEquals(1, vm.uiState.value.expression.length)
    }

    @Test
    fun testSelectionWrappingWithSingleParenthesisButton() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Number("3"))

        assertEquals("2 + 3", vm.uiState.value.expression.toString())

        // Select the whole "2 + 3" expression (nodes 0..2)
        vm.setSelection(listOf(3), 0..2)
        assertEquals(0..2, vm.uiState.value.selectionRange)

        // Press single parenthesis button
        vm.onKeyAction(KeyAction.Parentheses)

        // Should wrap into a single ParenthesesNode: "(2 + 3)"
        assertEquals(1, vm.uiState.value.expression.length)
        val paren = vm.uiState.value.expression.nodeAt(0) as? ParenthesesNode
        assertNotNull(paren)
        assertEquals("2 + 3", paren!!.inner.toString())
        assertNull(vm.uiState.value.selectionRange)
    }

    @Test
    fun testSingleParenthesisNestsAtEdges() {
        val vm = newViewModel()
        // Press single parenthesis button on empty expression
        vm.onKeyAction(KeyAction.Parentheses)

        // Creates "()", cursor inside [0, 0, 0]
        assertEquals(listOf(0, 0, 0), vm.uiState.value.cursorPath)

        // Pressing it again right after the opening parenthesis nests: "(())"
        vm.onKeyAction(KeyAction.Parentheses)
        assertEquals(listOf(0, 0, 0, 0, 0), vm.uiState.value.cursorPath)

        // Type 5, then press it at the end of the inner content: nests again
        vm.onKeyAction(KeyAction.Number("5"))
        assertEquals(listOf(0, 0, 0, 0, 1), vm.uiState.value.cursorPath)
        vm.onKeyAction(KeyAction.Parentheses)
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 0), vm.uiState.value.cursorPath)
        val inner = (vm.uiState.value.expression.nodeAt(0) as ParenthesesNode).inner.nodeAt(0) as ParenthesesNode
        assertTrue(inner.inner.nodeAt(1) is ParenthesesNode)
    }

    @Test
    fun testEEButtonScientificExponent() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("5"))
        vm.onKeyAction(KeyAction.EE)
        vm.onKeyAction(KeyAction.Number("3"))

        // Should have 5 * 10^3
        assertTrue(vm.uiState.value.expression.toString().contains("10"))
        assertTrue(vm.uiState.value.expression.toString().contains("3"))
    }

    @Test
    fun testClearLineAndClearAll() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("8"))
        vm.onKeyAction(KeyAction.Number("9"))
        assertEquals("89", vm.uiState.value.expression.toString())

        // Clear Line only clears current formula
        vm.onKeyAction(KeyAction.ClearLine)
        assertEquals("", vm.uiState.value.expression.toString())

        // Type another number
        vm.onKeyAction(KeyAction.Number("4"))
        assertEquals("4", vm.uiState.value.expression.toString())

        // Clear All clears formula and history
        vm.onKeyAction(KeyAction.ClearAll)
        assertEquals("", vm.uiState.value.expression.toString())
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun testOperatorBeforeDivisionKeepsOperatorAndCreatesEmptyFraction() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("5"))
        vm.onKeyAction(KeyAction.Operator("+"))
        assertEquals("5 + ", vm.uiState.value.expression.toString())

        // Press divide '÷'
        vm.onKeyAction(KeyAction.Operator("÷"))

        // Should keep "5 + " and insert empty fraction with cursor in numerator [2, 0, 0]
        assertEquals(3, vm.uiState.value.expression.length)
        assertEquals("5", vm.uiState.value.expression.nodeAt(0).toString())
        assertTrue(vm.uiState.value.expression.nodeAt(1) is OperatorNode)
        val frac = vm.uiState.value.expression.nodeAt(2) as? FractionNode
        assertNotNull(frac)
        assertEquals("", frac!!.numerator.toString())
        assertEquals("", frac.denominator.toString())
        assertEquals(listOf(2, 0, 0), vm.uiState.value.cursorPath)
    }

    @Test
    fun testTokenSelectionSelectsEntireMultiDigitValue() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Number("3"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Number("4"))
        vm.onKeyAction(KeyAction.Number("5"))

        // Long press on '2' (index 1)
        vm.selectTokenAt(emptyList(), 1)
        assertEquals(0..2, vm.uiState.value.selectionRange)

        // Drag on to between '4' and '5' (caret 5): the selection covers the token and the drag
        vm.extendSelection(listOf(5))
        assertEquals(0..4, vm.uiState.value.selectionRange)

        // Dragging back to the left keeps the long-pressed token "123" selected
        vm.extendSelection(listOf(1))
        assertEquals(0..2, vm.uiState.value.selectionRange)
    }

    @Test
    fun testWrapSelectionWithDivisionDoesNotCrash() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.Number("2"))

        // Select "12"
        vm.selectTokenAt(emptyList(), 0)
        assertEquals(0..1, vm.uiState.value.selectionRange)

        // Press divide '÷'
        vm.onKeyAction(KeyAction.Operator("÷"))

        // Should wrap 12 into numerator and place cursor into denominator [0, 1, 0]
        assertEquals(1, vm.uiState.value.expression.length)
        val frac = vm.uiState.value.expression.nodeAt(0) as? FractionNode
        assertNotNull(frac)
        assertEquals("12", frac!!.numerator.toString())
        assertEquals("", frac.denominator.toString())
        assertEquals(listOf(0, 1, 0), vm.uiState.value.cursorPath)
        assertNull(vm.uiState.value.selectionRange)
    }

    @Test
    fun testOperatorEqualsInsertsEquationAndEqualsEvaluates() = runBlocking {
        val vm = newViewModel()
        // Type: x + 2 = 5
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Number("2"))

        // Page 0 equal sign sends KeyAction.Operator("=")
        vm.onKeyAction(KeyAction.Operator("="))

        // The expression should contain the equation with =
        assertEquals("x + 2 = ", vm.uiState.value.expression.toString())

        vm.onKeyAction(KeyAction.Number("5"))
        assertEquals("x + 2 = 5", vm.uiState.value.expression.toString())

        // The equation is solved live while typing, without pressing Enter
        vm.awaitLiveResult()
        assertEquals("x = 3", vm.uiState.value.result!!.result.toString())

        // Enter pushes the calculation to the history and clears the line
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        assertEquals("", vm.uiState.value.expression.toString())
        assertNull(vm.uiState.value.result)
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals("x + 2 = 5", vm.uiState.value.history[0].calculation.input.toString())
        assertEquals("x = 3", vm.uiState.value.history[0].calculation.result.toString())
    }

    @Test
    fun testBackspaceInEmptyDenominatorUnwrapsNumerator() {
        val vm = newViewModel()
        // Type: 12 /
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Operator("÷"))

        // Cursor is now in empty denominator
        val state = vm.uiState.value
        assertEquals(1, state.expression.length)
        assertTrue(state.expression.nodeAt(0) is FractionNode)
        assertEquals(listOf(0, 1, 0), state.cursorPath)

        // Press backspace
        vm.onKeyAction(KeyAction.Backspace)

        // Fraction should be removed, leaving "12" outside the fraction, cursor at end [2]
        val newState = vm.uiState.value
        assertEquals("12", newState.expression.toString())
        assertEquals(2, newState.expression.length)
        assertTrue(newState.expression.nodeAt(0) !is FractionNode)
        assertEquals(listOf(2), newState.cursorPath)
    }

    @Test
    fun testBackspaceInEmptyExponentUnwrapsBase() {
        val vm = newViewModel()
        // Type: 10 ^ 6
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.Number("0"))
        vm.onKeyAction(KeyAction.Power)
        vm.onKeyAction(KeyAction.Number("6"))

        // First backspace deletes "6", leaving empty exponent
        vm.onKeyAction(KeyAction.Backspace)
        val expNode = vm.uiState.value.expression.nodeAt(0) as? ExponentNode
        assertNotNull(expNode)
        assertEquals("", expNode!!.exponent.toString())
        assertEquals(listOf(0, 1, 0), vm.uiState.value.cursorPath)

        // Second backspace removes the exponent feature completely and leaves base "10"
        vm.onKeyAction(KeyAction.Backspace)
        val newState = vm.uiState.value
        assertEquals("10", newState.expression.toString())
        assertEquals(2, newState.expression.length)
        assertTrue(newState.expression.nodeAt(0) !is ExponentNode)
        assertEquals(listOf(2), newState.cursorPath)
    }

    @Test
    fun testFactorialEvaluation() = runBlocking {
        val vm = newViewModel()
        // Type: 5 !
        vm.onKeyAction(KeyAction.Number("5"))
        vm.onKeyAction(KeyAction.Operator("!"))
        assertEquals("5!", vm.uiState.value.expression.toString())

        vm.awaitLiveResult()
        assertEquals("120", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun testReciprocalEvaluation() = runBlocking {
        val vm = newViewModel()
        // Type: 4 x⁻¹
        vm.onKeyAction(KeyAction.Number("4"))
        vm.onKeyAction(KeyAction.Reciprocal)

        vm.awaitLiveResult()
        assertEquals(0.25, vm.uiState.value.result!!.numericValue, 1e-12)
    }

    @Test
    fun testInlineDivideEvaluation() = runBlocking {
        val vm = newViewModel()
        // Type: 6 ÷ 3 ÷ 2 with the inline division (long press), not fractions
        vm.onKeyAction(KeyAction.Number("6"))
        vm.onKeyAction(KeyAction.InlineDivide)
        vm.onKeyAction(KeyAction.Number("3"))
        vm.onKeyAction(KeyAction.InlineDivide)
        vm.onKeyAction(KeyAction.Number("2"))
        assertTrue(vm.uiState.value.expression.nodeAt(1) is OperatorNode)
        assertEquals(listOf(5), vm.uiState.value.cursorPath)

        // Left to right: (6 ÷ 3) ÷ 2
        vm.awaitLiveResult()
        assertEquals(1.0, vm.uiState.value.result!!.numericValue, 1e-12)
    }

    /** Waits until the live result belongs to the current expression. */
    private suspend fun CalculatorViewModel.awaitLiveResult() {
        var attempts = 0
        while (uiState.value.result?.input != uiState.value.expression && attempts < 100) {
            delay(20)
            attempts++
        }
        assertEquals(uiState.value.expression, uiState.value.result?.input)
    }

    private suspend fun CalculatorViewModel.awaitCommit() {
        var attempts = 0
        while (uiState.value.expression.isNotEmpty && attempts < 100) {
            delay(20)
            attempts++
        }
    }

    @Test
    fun testPowerAfterOperatorPutsCursorIntoEmptyBase() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Power)

        val power = vm.uiState.value.expression.nodeAt(2) as ExponentNode
        assertNotNull(power.base)
        assertEquals(listOf(2, 0, 0), vm.uiState.value.cursorPath)

        vm.onKeyAction(KeyAction.Number("3"))
        assertEquals("3", (vm.uiState.value.expression.nodeAt(2) as ExponentNode).base.toString())
    }

    @Test
    fun testBackspaceInEmptyRootDegreeKeepsRadicand() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.NthRoot)
        assertEquals(listOf(0, 0, 0), vm.uiState.value.cursorPath)

        vm.onKeyAction(KeyAction.MoveRight) // into the radicand
        vm.onKeyAction(KeyAction.Number("5"))
        assertEquals(listOf(0, 1, 1), vm.uiState.value.cursorPath)

        vm.onKeyAction(KeyAction.MoveLeft)
        vm.onKeyAction(KeyAction.MoveLeft) // back into the empty degree
        assertEquals(listOf(0, 0, 0), vm.uiState.value.cursorPath)

        vm.onKeyAction(KeyAction.Backspace)
        val root = vm.uiState.value.expression.nodeAt(0) as RadicalNode
        assertNull(root.degree)
        assertEquals("5", root.radicand.toString())
    }

    @Test
    fun testFunctionKeyWrapsSelection() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.setSelection(emptyList(), 0..1)
        vm.onKeyAction(KeyAction.Function("sin"))

        val sin = vm.uiState.value.expression.nodeAt(0) as FunctionNode
        assertEquals("2x", sin.argument.toString())
        assertEquals(1, vm.uiState.value.expression.length)
    }

    @Test
    fun testLogBaseKeyInsertsLogarithmWithBaseSlot() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.LogBase)
        assertTrue(vm.uiState.value.expression.nodeAt(0) is LogBaseNode)
        assertEquals(listOf(0, 0, 0), vm.uiState.value.cursorPath)
    }

    @Test
    fun testAnsInsertsNegativeResultAsOneValue() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Operator("−"))
        vm.onKeyAction(KeyAction.Number("5"))
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        assertEquals(-3.0, vm.uiState.value.history[0].calculation.numericValue, 1e-12)

        // "2 Ans" must mean 2 × (−3), not 2 − 3
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Ans)
        vm.awaitLiveResult()
        assertEquals(-6.0, vm.uiState.value.result!!.numericValue, 1e-12)
    }

    @Test
    fun testAnsIsInsertedAsVariableForLastResult() = runBlocking {
        val vm = newViewModel()
        vm.type("7")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()

        vm.onKeyAction(KeyAction.Ans)
        vm.type("+", "1")
        assertEquals("Ans + 1", vm.uiState.value.expression.toString())
        assertEquals(AnsNode, vm.uiState.value.expression.nodeAt(0))
        vm.awaitLiveResult()
        assertEquals("8", vm.uiState.value.result!!.result.toString())

        // Committing it makes 8 the new Ans: pressing Enter on "Ans + 1" again counts up
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        vm.onKeyAction(KeyAction.Ans)
        vm.type("+", "1")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        assertEquals("9", vm.uiState.value.history[0].calculation.result.toString())
    }

    @Test
    fun testAnsFollowsHistoryChanges() = runBlocking {
        val vm = newViewModel()
        vm.type("2")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        vm.type("5")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()

        vm.onKeyAction(KeyAction.Ans)
        vm.awaitLiveResult()
        assertEquals("5", vm.uiState.value.result!!.result.toString())

        // Removing the newest calculation makes the one before it Ans again
        vm.deleteHistoryEntry(vm.uiState.value.history[0].id)
        var attempts = 0
        while (vm.uiState.value.result?.result?.toString() != "2" && attempts < 100) {
            delay(20)
            attempts++
        }
        assertEquals("2", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun testAnsWithoutPreviousResultIsAnError() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Ans)
        assertEquals(AnsNode, vm.uiState.value.expression.nodeAt(0))
        vm.awaitLiveResult()
        assertEquals(listOf(MathIssue.NO_ANS), vm.uiState.value.result!!.issues)
    }

    /** Types "name := value" and commits it. */
    private suspend fun CalculatorViewModel.defineVariable(name: String, vararg value: String) {
        onKeyAction(KeyAction.Variable(name))
        onKeyAction(KeyAction.Operator(":="))
        type(*value)
        onKeyAction(KeyAction.Enter)
        awaitCommit()
    }

    /** Types [keys] after variable [name] and waits for the live result. */
    private suspend fun CalculatorViewModel.liveResultOf(name: String, vararg keys: String): String {
        clearLine()
        onKeyAction(KeyAction.Variable(name))
        type(*keys)
        awaitLiveResult()
        return uiState.value.result!!.result.toString()
    }

    @Test
    fun testDefinedVariableStaysUntilDefinitionIsSwipedAway() = runBlocking {
        val vm = newViewModel()
        vm.defineVariable("a", "2")
        assertEquals("a = 2", vm.uiState.value.history[0].calculation.result.toString())
        vm.type("5")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()

        // Still defined after other calculations
        assertEquals("3", vm.liveResultOf("a", "+", "1"))

        // Swiping away the definition makes a a plain symbol again
        vm.deleteHistoryEntry(vm.uiState.value.history.single { it.calculation.definedVariable == "a" }.id)
        vm.awaitLiveResultText("a + 1")
    }

    @Test
    fun testRemovingNewerDefinitionRestoresOlderOne() = runBlocking {
        val vm = newViewModel()
        vm.defineVariable("f", "2")
        vm.defineVariable("f", "7")
        assertEquals("8", vm.liveResultOf("f", "+", "1"))

        vm.deleteHistoryEntry(vm.uiState.value.history[0].id)
        vm.awaitLiveResultText("3")
    }

    @Test
    fun testClearAllRemovesDefinitions() = runBlocking {
        val vm = newViewModel()
        vm.defineVariable("j", "4")
        vm.onKeyAction(KeyAction.Clear) // line is empty: clears everything
        assertTrue(vm.uiState.value.history.isEmpty())
        assertEquals("j + 1", vm.liveResultOf("j", "+", "1"))
    }

    private fun categoryName(vm: CalculatorViewModel) =
        com.vortessence.calculator.core.math.expressions.UnitRegistry.categories[vm.uiState.value.unitCategory].name

    @Test
    fun testConvertingAnsOpensUnitPickerOnItsCategory() = runBlocking {
        val vm = newViewModel()
        vm.type("2")
        vm.insertUnit("m³")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()

        vm.onKeyAction(KeyAction.Ans)
        vm.onKeyAction(KeyAction.Convert)
        assertTrue(vm.uiState.value.showUnitsDialog)
        assertEquals("Volume", categoryName(vm))
        assertEquals("Ans → ", vm.uiState.value.expression.toString().trimStart())
    }

    @Test
    fun testConvertLooksOnlyAtValueBeforeArrow() = runBlocking {
        val vm = newViewModel()
        // In "a := 3 h →" the value to convert is the time 3 h
        vm.onKeyAction(KeyAction.Variable("a"))
        vm.onKeyAction(KeyAction.Operator(":="))
        vm.type("3")
        vm.insertUnit("h")
        vm.onKeyAction(KeyAction.Convert)
        assertEquals("Time", categoryName(vm))
        vm.dismissUnitsDialog()

        // Without a unit, the picker stays on the category used last
        vm.clearLine()
        vm.selectUnitCategory(2)
        vm.type("5")
        vm.onKeyAction(KeyAction.Convert)
        assertEquals(2, vm.uiState.value.unitCategory)
        assertNull(vm.uiState.value.unitFamilyToShow)
    }

    private suspend fun CalculatorViewModel.awaitLiveResultText(expected: String) {
        var attempts = 0
        while (uiState.value.result?.result?.toString() != expected && attempts < 100) {
            delay(20)
            attempts++
        }
        assertEquals(expected, uiState.value.result?.result?.toString())
    }

    @Test
    fun testLiveResultUpdatesWhileTyping() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.Number("2"))
        vm.awaitLiveResult()
        assertEquals("12", vm.uiState.value.result!!.result.toString())

        // Typing more digits extends the line instead of starting a new calculation
        vm.onKeyAction(KeyAction.Operator("×"))
        vm.onKeyAction(KeyAction.Number("3"))
        assertEquals("12 × 3", vm.uiState.value.expression.toString())
        vm.awaitLiveResult()
        assertEquals("36", vm.uiState.value.result!!.result.toString())
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun testEnterOnIncompleteLineKeepsItAndShowsError() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.awaitLiveResult()
        assertFalse(vm.uiState.value.showResultError)

        vm.onKeyAction(KeyAction.Enter)
        var attempts = 0
        while (!vm.uiState.value.showResultError && attempts < 100) {
            delay(20)
            attempts++
        }
        assertTrue(vm.uiState.value.showResultError)
        assertEquals("2 + ", vm.uiState.value.expression.toString())
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun testDeleteSingleHistoryEntry() = runBlocking {
        val vm = newViewModel()
        for (digit in listOf("1", "2", "3")) {
            vm.onKeyAction(KeyAction.Number(digit))
            vm.onKeyAction(KeyAction.Enter)
            vm.awaitCommit()
        }
        assertEquals(listOf("3", "2", "1"), vm.uiState.value.history.map { it.calculation.input.toString() })
        assertEquals(3, vm.uiState.value.history.map { it.id }.toSet().size)

        val middle = vm.uiState.value.history[1]
        vm.deleteHistoryEntry(middle.id)
        assertEquals(listOf("3", "1"), vm.uiState.value.history.map { it.calculation.input.toString() })
    }

    @Test
    fun testDraggingSelectionHandles() {
        val vm = newViewModel()
        for (key in listOf("1", "2", "+", "3", "4")) {
            vm.onKeyAction(if (key == "+") KeyAction.Operator("+") else KeyAction.Number(key))
        }
        // "12 + 34": long press on '2' selects "12"
        vm.selectTokenAt(emptyList(), 1)
        assertEquals(0..1, vm.uiState.value.selectionRange)

        // Drag the end handle to the end: "12 + 34"
        vm.beginHandleDrag(isStartHandle = false)
        vm.extendSelection(listOf(5))
        assertEquals(0..4, vm.uiState.value.selectionRange)

        // Drag the start handle past the end (handles cross): only "4" remains
        vm.beginHandleDrag(isStartHandle = true)
        vm.extendSelection(listOf(4))
        assertEquals(4..4, vm.uiState.value.selectionRange)

        // Dragging onto the anchor itself never collapses the selection
        vm.extendSelection(listOf(5))
        assertEquals(4..4, vm.uiState.value.selectionRange)
    }

    @Test
    fun testDraggingIntoAFractionSelectsTheWholeFraction() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Fraction)
        vm.onKeyAction(KeyAction.Number("1"))
        // "2 + (1)/()" with the fraction at index 2
        vm.selectTokenAt(emptyList(), 0)
        vm.extendSelection(listOf(2, 0, 1))
        assertEquals(0..2, vm.uiState.value.selectionRange)
        assertEquals(listOf(3), vm.uiState.value.cursorPath)
    }

    @Test
    fun testContextAwareClearKey() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("7"))
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        vm.onKeyAction(KeyAction.Number("8"))

        // First press clears only the line
        vm.onKeyAction(KeyAction.Clear)
        assertEquals("", vm.uiState.value.expression.toString())
        assertEquals(1, vm.uiState.value.history.size)

        // With an empty line it clears everything
        vm.onKeyAction(KeyAction.Clear)
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun testConvertKeyInsertsArrowAndOpensUnitPicker() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("5"))
        vm.insertUnit("km")
        vm.onKeyAction(KeyAction.Convert)
        assertTrue(vm.uiState.value.showUnitsDialog)
        vm.insertUnit("m")
        assertFalse(vm.uiState.value.showUnitsDialog)
        assertEquals("5km → m", vm.uiState.value.expression.toString())

        vm.awaitLiveResult()
        assertEquals("5000m", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun testUnitSettingsChangeTheLiveResult() = runBlocking {
        val vm = newViewModel()
        vm.type("1")
        vm.insertUnit("km")
        vm.awaitLiveResult()
        assertEquals("1000m", vm.uiState.value.result!!.result.toString())

        vm.setUnitSystem(com.vortessence.calculator.core.math.context.UnitSystem.US_CUSTOMARY)
        vm.awaitLiveResultText("3280.839895ft")
    }

    @Test
    fun testTemperaturesAreShownInKelvinInEveryUnitSystem() = runBlocking {
        val vm = newViewModel()
        vm.type("0")
        vm.insertUnit("°C")
        vm.awaitLiveResult()
        assertEquals("273.15K", vm.uiState.value.result!!.result.toString())

        vm.setUnitSystem(com.vortessence.calculator.core.math.context.UnitSystem.US_CUSTOMARY)
        delay(100)
        assertEquals("273.15K", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun testDivisionTakesWholeQuantityWithUnitAsNumerator() = runBlocking {
        val vm = newViewModel()
        // 2 + 5 mmol ÷ 100 µL
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Number("5"))
        vm.insertUnit("mmol")
        vm.onKeyAction(KeyAction.Operator("÷"))

        val frac = vm.uiState.value.expression.nodeAt(2) as FractionNode
        assertEquals("5mmol", frac.numerator.toString())
        assertEquals("2 + ", vm.uiState.value.expression.nodes.take(2).joinToString("") { it.toString() })
        assertEquals(listOf(2, 1, 0), vm.uiState.value.cursorPath)

        // 5 mmol ÷ 100 µL = 50 mol/L
        vm.clearLine()
        vm.onKeyAction(KeyAction.Number("5"))
        vm.insertUnit("mmol")
        vm.onKeyAction(KeyAction.Operator("÷"))
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.Number("0"))
        vm.onKeyAction(KeyAction.Number("0"))
        vm.insertUnit("µL")
        vm.awaitLiveResult()
        assertEquals("50mol/L", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun testDivisionAfterBareUnitBuildsUnitFraction() {
        val vm = newViewModel()
        vm.insertUnit("km")
        vm.onKeyAction(KeyAction.Operator("÷"))
        val frac = vm.uiState.value.expression.nodeAt(0) as FractionNode
        assertEquals("km", frac.numerator.toString())
    }

    private fun CalculatorViewModel.type(vararg keys: String) {
        for (key in keys) {
            when (key) {
                "EE" -> onKeyAction(KeyAction.EE)
                "→" -> onKeyAction(KeyAction.MoveRight)
                "+", "−", "×", "÷" -> onKeyAction(KeyAction.Operator(key))
                else -> onKeyAction(KeyAction.Number(key))
            }
        }
    }

    @Test
    fun testEEInsertsScientificExponentOfTheNumber() {
        val vm = newViewModel()
        vm.type("5", "EE", "3")
        val expression = vm.uiState.value.expression
        assertEquals(2, expression.length)
        assertEquals("3", (expression.nodeAt(1) as SciExponentNode).exponent.toString())
        assertEquals(listOf(1, 0, 1), vm.uiState.value.cursorPath)

        // Backspace twice: the exponent digit, then the empty "×10" itself
        vm.onKeyAction(KeyAction.Backspace)
        vm.onKeyAction(KeyAction.Backspace)
        assertEquals("5", vm.uiState.value.expression.toString())
    }

    @Test
    fun testFractionTakesScientificQuantityButNotAMultiplication() {
        val vm = newViewModel()
        // 5×10³ mmol ÷ → the whole quantity is the numerator
        vm.type("5", "EE", "3", "→")
        vm.insertUnit("mmol")
        vm.onKeyAction(KeyAction.Operator("÷"))
        var frac = vm.uiState.value.expression.nodeAt(0) as FractionNode
        assertEquals("5×10^(3)mmol", frac.numerator.toString())

        // 3 × 5 mmol ÷ → only "5 mmol", the multiplication stays outside
        vm.clearLine()
        vm.type("3", "×", "5")
        vm.insertUnit("mmol")
        vm.onKeyAction(KeyAction.Operator("÷"))
        frac = vm.uiState.value.expression.nodeAt(2) as FractionNode
        assertEquals("5mmol", frac.numerator.toString())
        assertEquals("3 × ", vm.uiState.value.expression.nodes.take(2).joinToString(""))
    }

    @Test
    fun testPowerAndSelectionTreatScientificNumberAsOne() {
        val vm = newViewModel()
        vm.type("2", "EE", "3", "→")
        vm.onKeyAction(KeyAction.Square)
        val power = vm.uiState.value.expression.nodeAt(0) as ExponentNode
        assertEquals("2×10^(3)", power.base.toString())

        vm.clearLine()
        vm.type("1", "2", "EE", "3", "→", "+", "4")
        vm.selectTokenAt(emptyList(), 0)
        assertEquals(0..2, vm.uiState.value.selectionRange)
    }

    @Test
    fun testCommittedResultUsesChosenFormButAnsStaysExact() = runBlocking {
        val vm = newViewModel()
        vm.setResultForm(com.vortessence.calculator.core.math.type.ResultForm.DECIMAL)
        // 1 ÷ 3, committed while "Decimal" is selected
        vm.type("1", "÷", "3")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()

        val entry = vm.uiState.value.history.single()
        assertEquals(com.vortessence.calculator.core.math.type.ResultForm.DECIMAL, entry.form)
        assertEquals(
            "0.3333333333",
            com.vortessence.calculator.core.math.type.ResultFormatter.format(entry.calculation, entry.form).toString()
        )

        // Ans reuses the exact 1/3, so Ans × 3 is exactly 1
        vm.onKeyAction(KeyAction.Ans)
        vm.type("×", "3")
        vm.awaitLiveResult()
        assertEquals("1", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun testDigitGroupingIsOnByDefault() {
        assertTrue(newViewModel().uiState.value.digitGrouping)
    }

    @Test
    fun testThemeModeFollowsSystemByDefault() {
        val vm = newViewModel()
        assertEquals(ThemeMode.SYSTEM, vm.uiState.value.themeMode)
        vm.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, vm.uiState.value.themeMode)
    }

    @Test
    fun testMatrixKeyOpensSizePickerAndInsertsChosenSize() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Matrix)
        assertTrue(vm.uiState.value.showMatrixDialog)

        vm.insertMatrix(3, 4)
        val state = vm.uiState.value
        assertFalse(state.showMatrixDialog)
        val matrix = state.expression.nodeAt(0) as MatrixNode
        assertEquals(3, matrix.rows)
        assertEquals(4, matrix.cols)
        assertEquals(12, matrix.elements.size)
        // Cursor in the first entry; the size is preselected next time
        assertEquals(listOf(0, 0, 0), state.cursorPath)
        assertEquals(3 to 4, state.matrixRows to state.matrixCols)
    }

    @Test
    fun testTransposeAppliesToPrecedingMatrix() {
        val vm = newViewModel()
        vm.insertMatrix(2, 2)
        vm.setCursorPath(listOf(1))
        vm.onKeyAction(KeyAction.Transpose)

        val power = vm.uiState.value.expression.nodeAt(0) as ExponentNode
        assertTrue(power.base!!.nodeAt(0) is MatrixNode)
        assertEquals(VariableNode("T"), power.exponent.nodeAt(0))
    }

    @Test
    fun testNewLineSolvesSystemOfEquations() = runBlocking {
        val vm = newViewModel()
        // −2x + y = −3, new line, 3x + 2y = 8
        listOf(
            KeyAction.Operator("−"), KeyAction.Number("2"), KeyAction.Variable("x"), KeyAction.Operator("+"),
            KeyAction.Variable("y"), KeyAction.Operator("="), KeyAction.Operator("−"), KeyAction.Number("3"),
            KeyAction.NewLine,
            KeyAction.Number("3"), KeyAction.Variable("x"), KeyAction.Operator("+"), KeyAction.Number("2"),
            KeyAction.Variable("y"), KeyAction.Operator("="), KeyAction.Number("8")
        ).forEach { vm.onKeyAction(it) }
        assertEquals(LineBreakNode, vm.uiState.value.expression.nodeAt(8))

        vm.awaitLiveResult()
        assertEquals("x = 2, y = 1", vm.uiState.value.result!!.result.toString())

        // Enter still commits, the whole system at once
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals("x = 2, y = 1", vm.uiState.value.history[0].calculation.result.toString())
        assertEquals("", vm.uiState.value.expression.toString())
    }

    @Test
    fun testNewLineInsideSlotStartsLineAfterTheStructure() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.Operator("÷"))
        vm.onKeyAction(KeyAction.Number("2"))
        // The cursor is in the denominator; the line break goes after the whole fraction
        vm.onKeyAction(KeyAction.NewLine)
        val state = vm.uiState.value
        assertTrue(state.expression.nodeAt(0) is FractionNode)
        assertEquals(LineBreakNode, state.expression.nodeAt(1))
        assertEquals(listOf(2), state.cursorPath)

        // Backspace at the start of a line joins it with the previous one
        vm.onKeyAction(KeyAction.Backspace)
        assertEquals(1, vm.uiState.value.expression.length)
        assertEquals(listOf(1), vm.uiState.value.cursorPath)
    }

    @Test
    fun testSumKeyPutsCursorAfterIndex() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Sum)
        // Σ with "n=" prefilled; the cursor is after it, ready for the start value
        assertTrue(vm.uiState.value.expression.nodeAt(0) is BigOperatorNode)
        assertEquals(listOf(0, 0, 2), vm.uiState.value.cursorPath)
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.MoveRight)
        vm.onKeyAction(KeyAction.Number("4"))
        vm.onKeyAction(KeyAction.MoveRight)
        vm.onKeyAction(KeyAction.Variable("n"))
        assertEquals("Σ_{n = 1}^{4} (n)", vm.uiState.value.expression.toString())
        runBlocking { vm.awaitLiveResult() }
        assertEquals("10", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun testLimitWrapsSelection() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Square)
        vm.selectTokenAt(listOf(), 0)
        vm.onKeyAction(KeyAction.Limit)
        val node = vm.uiState.value.expression.nodeAt(0) as LimitNode
        assertTrue(node.body.nodes.single() is ExponentNode)
        // The cursor is in "x→", although the wrapped selection already filled the last slot
        assertEquals(listOf(0, 0, 2), vm.uiState.value.cursorPath)
    }

    @Test
    fun testBackspaceRemovesUntouchedSum() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Product)
        // In the empty upper bound: only the prefilled "n=" is left, so the whole ∏ goes
        vm.onKeyAction(KeyAction.MoveRight)
        vm.onKeyAction(KeyAction.Backspace)
        assertEquals("2", vm.uiState.value.expression.toString())
        assertEquals(listOf(1), vm.uiState.value.cursorPath)
    }

    @Test
    fun testImaginaryUnitKeyTurnsOnComplexNumbers() {
        val vm = newViewModel()
        assertFalse(vm.uiState.value.complexNumbers)
        vm.onKeyAction(KeyAction.ImaginaryUnit)
        assertTrue(vm.uiState.value.complexNumbers)
        assertTrue(vm.uiState.value.showComplexNotice)
        vm.dismissComplexNotice()
        vm.onKeyAction(KeyAction.Square)
        runBlocking { vm.awaitLiveResult() }
        assertEquals(" − 1", vm.uiState.value.result!!.result.toString())

        // Off again: i is an error, and pressing it again says it turns them back on
        vm.setComplexNumbers(false)
        runBlocking { vm.awaitLiveResultText("") }
        assertEquals(listOf(MathIssue.COMPLEX_RESULT), vm.uiState.value.result!!.issues)
        vm.onKeyAction(KeyAction.ImaginaryUnit)
        assertTrue(vm.uiState.value.showComplexNotice)
    }

    @Test
    fun testPercentAndModKeys() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("7"))
        vm.onKeyAction(KeyAction.Operator("mod"))
        vm.onKeyAction(KeyAction.Number("4"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Number("50"))
        vm.onKeyAction(KeyAction.Operator("%"))
        assertEquals("7 mod 4 + 50%", vm.uiState.value.expression.toString())
        runBlocking { vm.awaitLiveResult() }
        assertEquals("(7)/(2)", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun testMixedFractionTakesPrecedingDigitsAsWholePart() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.MixedFraction)
        assertTrue(vm.uiState.value.expression.nodeAt(0) is MixedFractionNode)
        assertEquals(listOf(0, 1, 0), vm.uiState.value.cursorPath)
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.MoveRight)
        vm.onKeyAction(KeyAction.Number("4"))
        assertEquals("12(1)/(4)", vm.uiState.value.expression.toString())
        runBlocking { vm.awaitLiveResult() }
        assertEquals("(49)/(4)", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun testMixedFractionWithoutNumberStartsInWholePart() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.MixedFraction)
        assertEquals(listOf(0, 0, 0), vm.uiState.value.cursorPath)
    }

    @Test
    fun testBackspaceInEmptyFractionPartLeavesWholeNumber() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("3"))
        vm.onKeyAction(KeyAction.MixedFraction)
        vm.onKeyAction(KeyAction.Backspace)
        assertEquals("3", vm.uiState.value.expression.toString())
        assertEquals(listOf(1), vm.uiState.value.cursorPath)
    }
}
