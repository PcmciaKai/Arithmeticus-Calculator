import android.app.Application
import com.vortessence.calculator.core.math.type.ExponentNode
import com.vortessence.calculator.core.math.type.FunctionNode
import com.vortessence.calculator.core.math.type.LogBaseNode
import com.vortessence.calculator.core.math.type.RadicalNode
import com.vortessence.calculator.core.math.type.FractionNode
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
    fun testSingleParenthesisAutoStepOut() {
        val vm = newViewModel()
        // Press single parenthesis button on empty expression
        vm.onKeyAction(KeyAction.Parentheses)

        // Creates "()", cursor inside [0, 0, 0]
        assertEquals(listOf(0, 0, 0), vm.uiState.value.cursorPath)

        // Type 5
        vm.onKeyAction(KeyAction.Number("5"))
        assertEquals(listOf(0, 0, 1), vm.uiState.value.cursorPath)

        // Press single parenthesis button at the end: steps out to [1]
        vm.onKeyAction(KeyAction.Parentheses)
        assertEquals(listOf(1), vm.uiState.value.cursorPath)
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

        // Extend to '4' (index 4)
        vm.extendSelectionTo(emptyList(), 4)
        // Should extend all the way to index 5 (to include all of 45!)
        assertEquals(0..5, vm.uiState.value.selectionRange)
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
}
