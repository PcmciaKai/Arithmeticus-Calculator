import android.app.Application
import com.vortessence.calculator.ui.theme.ThemeMode
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.AnsNode
import com.vortessence.calculator.core.math.type.ConstantNode
import com.vortessence.calculator.core.math.type.VariableNameProblem
import com.vortessence.calculator.viewmodel.TypingNotice
import com.vortessence.calculator.core.math.type.AntiderivativeNode
import com.vortessence.calculator.core.math.type.DerivativeNode
import com.vortessence.calculator.core.math.type.IntegralNode
import com.vortessence.calculator.core.math.type.BigOperatorNode
import com.vortessence.calculator.core.math.type.LimitNode
import com.vortessence.calculator.core.math.type.MixedFractionNode
import com.vortessence.calculator.core.math.type.PolarNode
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
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.NumberNode
import com.vortessence.calculator.core.math.type.SeparatorNode
import com.vortessence.calculator.core.math.type.MathOp
import com.vortessence.calculator.ui.keypad.KeyAction
import com.vortessence.calculator.viewmodel.CalculatorViewModel
import com.vortessence.calculator.viewmodel.CalculationSnapshot
import com.vortessence.calculator.viewmodel.SavedEntry
import com.vortessence.calculator.viewmodel.SavedSession
import com.vortessence.calculator.viewmodel.SessionCodec
import com.vortessence.calculator.viewmodel.SessionStore
import com.vortessence.calculator.viewmodel.InputMode
import com.vortessence.calculator.core.math.type.ResultForm
import com.vortessence.calculator.core.math.type.ResultFormatter
import com.vortessence.calculator.viewmodel.SessionMessage
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.UnitSystem
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
    private fun newViewModel(store: SessionStore? = null, seeds: (() -> Long)? = null): CalculatorViewModel =
        (if (seeds == null) CalculatorViewModel(Application(), liveResultDelayMillis = 0, sessionStore = store)
        else CalculatorViewModel(Application(), liveResultDelayMillis = 0, sessionStore = store, seedSource = seeds)).also { viewModels.add(it) }

    private class MemorySessionStore(text: String? = null) : SessionStore {
        // Saved from a background thread
        @Volatile var text: String? = text
        override fun load() = text
        override fun save(text: String) { this.text = text }
    }

    /** Waits for a save in the background to arrive in [store]. */
    private suspend fun awaitSaved(store: MemorySessionStore, condition: (String) -> Boolean = { true }) {
        var attempts = 0
        while (store.text?.let(condition) != true && attempts < 100) {
            testDispatcher.scheduler.advanceUntilIdle()
            delay(20)
            attempts++
        }
        assertTrue("not saved", store.text?.let(condition) == true)
    }

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

    @Test
    fun derivativeOffersStepByStepSolution() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("5"))
        vm.awaitLiveResult()
        assertFalse(vm.uiState.value.hasSteps)

        vm.onKeyAction(KeyAction.Clear)
        vm.onKeyAction(KeyAction.Derivative)
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Function("sin"))
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.awaitLiveResult()
        vm.awaitSteps()
        assertTrue(vm.uiState.value.hasSteps)

        vm.showSteps()
        var attempts = 0
        while (vm.uiState.value.steps?.isLoading != false && attempts < 100) {
            delay(20)
            attempts++
        }
        val solution = vm.uiState.value.steps?.solution
        assertNotNull(solution)
        assertEquals(vm.uiState.value.result!!.result.toString(), solution!!.result.toString())

        vm.dismissSteps()
        assertNull(vm.uiState.value.steps)
    }

    @Test
    fun theVariableOfAnIntegralCanBeChanged() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Antiderivative)
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Variable("y"))
        vm.awaitLiveResult()
        assertEquals("((x)^(2)y)/(2) + C", vm.uiState.value.result!!.result.toString())

        vm.editVariable(emptyList(), 0)
        assertEquals("x", vm.uiState.value.variableEdit?.name)
        assertTrue(vm.setVariable("y"))
        assertNull(vm.uiState.value.variableEdit)
        assertEquals("y", (vm.uiState.value.expression.nodeAt(0) as AntiderivativeNode).variable)
        // The integrand and the cursor in it are untouched
        assertEquals(listOf(0, 0, 2), vm.uiState.value.cursorPath)
        vm.awaitLiveResultText("(x(y)^(2))/(2) + C")
    }

    @Test
    fun theVariableOfADerivativeAndADefiniteIntegralCanBeChanged() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.DerivativeAt)
        vm.editVariable(emptyList(), 0)
        assertTrue(vm.setVariable("t"))
        assertEquals("t", (vm.uiState.value.expression.nodeAt(0) as DerivativeNode).variable)
        // The point stays
        assertNotNull((vm.uiState.value.expression.nodeAt(0) as DerivativeNode).point)

        vm.onKeyAction(KeyAction.Clear)
        vm.onKeyAction(KeyAction.Integral)
        vm.editVariable(emptyList(), 0)
        assertTrue(vm.setVariable("z"))
        assertEquals("z", (vm.uiState.value.expression.nodeAt(0) as IntegralNode).variable)
    }

    @Test
    fun onlyOneLetterThatIsNoConstantOrTransposeIsAVariable() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Derivative)
        vm.editVariable(emptyList(), 0)
        for (name in listOf("", "xy", "1", "e", "i", "π", "T")) {
            assertFalse("'$name'", vm.setVariable(name))
        }
        // Still open, nothing changed
        assertEquals("x", vm.uiState.value.variableEdit?.name)
        assertEquals("x", (vm.uiState.value.expression.nodeAt(0) as DerivativeNode).variable)
        // Capitals and Greek letters are fine
        assertTrue(vm.setVariable("A"))
        assertEquals("A", (vm.uiState.value.expression.nodeAt(0) as DerivativeNode).variable)

        vm.editVariable(emptyList(), 0)
        vm.dismissVariableEdit()
        assertNull(vm.uiState.value.variableEdit)
        // Only integrals and derivatives have a variable
        vm.onKeyAction(KeyAction.Clear)
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.editVariable(emptyList(), 0)
        assertNull(vm.uiState.value.variableEdit)
        vm.editVariable(listOf(7, 7), 3)
        assertNull(vm.uiState.value.variableEdit)
    }

    @Test
    fun typedLettersAreSingleLetterVariables() = runBlocking {
        val vm = newViewModel()
        vm.typeText("Ab")
        assertEquals(listOf(VariableNode("A"), VariableNode("b")), vm.uiState.value.expression.nodes)
        assertEquals(listOf(2), vm.uiState.value.cursorPath)
        assertNull(vm.uiState.value.typingNotice)
        // Greek letters are letters, too
        vm.typeText("θ")
        assertEquals(VariableNode("θ"), vm.uiState.value.expression.nodeAt(2))
    }

    @Test
    fun typedTextIsEnteredLikeKeys() = runBlocking {
        val vm = newViewModel()
        vm.typeText("2x+3")
        vm.awaitLiveResult()
        assertEquals("2x + 3", vm.uiState.value.result!!.result.toString())

        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("(1+2)*3")
        vm.awaitLiveResult()
        assertEquals("9", vm.uiState.value.result!!.result.toString())

        // The decimal point
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("1.5+1.5")
        vm.awaitLiveResult()
        assertEquals("3", vm.uiState.value.result!!.result.toString())
        // e and π are the constants, not variables
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("eπ")
        assertEquals(listOf(ConstantNode("e"), ConstantNode("π")), vm.uiState.value.expression.nodes)
        // What is no key is ignored
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText(" ?#\n")
        assertTrue(vm.uiState.value.expression.isEmpty)
    }

    @Test
    fun typedLettersThatCannotBeVariablesAreRefusedWithAReason() = runBlocking {
        val vm = newViewModel()
        vm.typeText("xTy")
        assertEquals(listOf(VariableNode("x"), VariableNode("y")), vm.uiState.value.expression.nodes)
        // Several letters at once (pasted): the one dropped is still told, although y came after it
        assertEquals(TypingNotice("T", VariableNameProblem.TRANSPOSE), vm.uiState.value.typingNotice)

        vm.typeText("T")
        assertEquals(TypingNotice("T", VariableNameProblem.TRANSPOSE), vm.uiState.value.typingNotice)
        // After a digit the i cannot continue a function name (Ti could be the start of tinv), so it is refused at once
        vm.typeText("1i")
        assertEquals(TypingNotice("i", VariableNameProblem.CONSTANT), vm.uiState.value.typingNotice)
        assertEquals(3, vm.uiState.value.expression.length)
        // The next text without such a letter clears it
        vm.typeText("z")
        assertNull(vm.uiState.value.typingNotice)
    }

    /** The nodes of the line, shown as short texts: functions by name, other nodes as they are written. */
    private fun CalculatorViewModel.nodeNames(): List<String> = uiState.value.expression.nodes.map {
        when (it) {
            is FunctionNode -> "${it.name}()"
            is ParenthesesNode -> "()"
            is RadicalNode -> "sqrt"
            else -> it.toString().trim()
        }
    }

    @Test
    fun aTypedFunctionNameAndParenthesisIsTheFunction() = runBlocking {
        val vm = newViewModel()
        vm.typeText("sin(0)+1")
        assertEquals(listOf("sin()", "+", "1"), vm.nodeNames())
        vm.awaitLiveResult()
        assertEquals("1", vm.uiState.value.result!!.result.toString())
        assertNull(vm.uiState.value.typingNotice)

        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("asin(0)")
        assertEquals(listOf("asin()"), vm.nodeNames())
        // Any case; the function is written the way it is known
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("MEAN(1,2,6)")
        assertEquals(listOf("mean()"), vm.nodeNames())
        vm.awaitLiveResult()
        assertEquals("3", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun aFunctionTypedKeyByKeyIsTheFunction() = runBlocking {
        val vm = newViewModel()
        // The keyboard hands on one key at a time
        for (key in " mean(1,2,6)+1") vm.typeText(key.toString())
        assertEquals(listOf("mean()", "+", "1"), vm.nodeNames())
    }

    @Test
    fun theTypedFunctionTakesItsArgumentsAndCloses() = runBlocking {
        val vm = newViewModel()
        vm.typeText("mean(1;2;6)*2")
        assertEquals(listOf("mean()", "×", "2"), vm.nodeNames())
        vm.awaitLiveResult()
        assertEquals("6", vm.uiState.value.result!!.result.toString())

        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("nCr(5,2)")
        vm.awaitLiveResult()
        assertEquals("10", vm.uiState.value.result!!.result.toString())

        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("sqrt(9)")
        assertEquals(listOf("sqrt"), vm.nodeNames())
        vm.awaitLiveResult()
        assertEquals("3", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun lettersBeforeTheFunctionNameStayVariables() = runBlocking {
        val vm = newViewModel()
        vm.typeText("xsin(0)")
        assertEquals(listOf("x", "sin()"), vm.nodeNames())
        assertNull(vm.uiState.value.typingNotice)
    }

    @Test
    fun namesOfTheMoreFunctionsCanBeTyped() = runBlocking {
        val vm = newViewModel()
        for (name in listOf("exp", "cbrt", "range", "percentile", "sign", "hypot", "conj", "mode", "prod", "fib", "normalcdf", "normalinv", "binompdf", "poissoncdf")) {
            vm.onKeyAction(KeyAction.Clear)
            vm.typeText("$name(")
            assertEquals(listOf("$name()"), vm.nodeNames())
            assertNull(vm.uiState.value.typingNotice)
        }
        // Names that start with i are only in the formula picker: the i is refused as the imaginary unit
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("im(")
        assertEquals(listOf("m", "()"), vm.nodeNames())
        assertEquals(TypingNotice("i", VariableNameProblem.CONSTANT), vm.uiState.value.typingNotice)
    }

    @Test
    fun otherWordsWithAParenthesisStayLetters() = runBlocking {
        val vm = newViewModel()
        vm.typeText("foo(")
        assertEquals(listOf("f", "o", "o", "()"), vm.nodeNames())
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("x(")
        assertEquals(listOf("x", "()"), vm.nodeNames())
        // Not a word any more once something else was typed in between
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("sin2(")
        assertEquals(listOf("s", "n", "2", "()"), vm.nodeNames())
    }

    @Test
    fun anIThatWasLeftOutForAFunctionNameIsToldWhenNoFunctionComes() = runBlocking {
        val vm = newViewModel()
        // While s-i-n is typed the i is not told off, ...
        vm.typeText("si")
        assertNull(vm.uiState.value.typingNotice)
        vm.typeText("n")
        assertNull(vm.uiState.value.typingNotice)
        // ... but it is when the word turns out to be no function
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("sit(")
        assertEquals(listOf("s", "t", "()"), vm.nodeNames())
        assertEquals(TypingNotice("i", VariableNameProblem.CONSTANT), vm.uiState.value.typingNotice)
        // An i that cannot start a name is told at once
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("xi")
        assertEquals(TypingNotice("i", VariableNameProblem.CONSTANT), vm.uiState.value.typingNotice)
    }

    @Test
    fun backspaceKeepsTheTypedWordRight() = runBlocking {
        val vm = newViewModel()
        // sib, back, n: the word is sin again
        vm.typeText("sib")
        vm.typeBackspace()
        vm.typeText("n(")
        assertEquals(listOf("sin()"), vm.nodeNames())

        // Taking back the left out i takes no node with it
        vm.onKeyAction(KeyAction.Clear)
        vm.typeText("si")
        vm.typeBackspace()
        assertEquals(listOf("s"), vm.nodeNames())
        vm.typeBackspace()
        assertEquals(emptyList<String>(), vm.nodeNames())
        // Without a typed word it is the normal backspace
        vm.typeText("5")
        vm.typeBackspace()
        assertEquals(emptyList<String>(), vm.nodeNames())
    }

    @Test
    fun movingTheCursorEndsTheTypedWord() = runBlocking {
        val vm = newViewModel()
        vm.typeText("sin")
        vm.onKeyAction(KeyAction.MoveLeft)
        vm.onKeyAction(KeyAction.MoveRight)
        vm.typeText("(")
        assertEquals(listOf("s", "n", "()"), vm.nodeNames())
    }

    @Test
    fun aCapitalVariableCanBeDefinedAndUsed() = runBlocking {
        val vm = newViewModel()
        vm.typeText("A")
        vm.onKeyAction(KeyAction.Operator(":="))
        vm.typeText("5")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        assertEquals("A", vm.uiState.value.history[0].calculation.definedVariable)
        vm.typeText("A*2")
        vm.awaitLiveResult()
        assertEquals("10", vm.uiState.value.result!!.result.toString())
    }

    @Test
    fun aDerivativeWithoutItsVariableSuggestsTheOthers() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Derivative)
        vm.onKeyAction(KeyAction.Variable("y"))
        vm.awaitLiveResult()
        assertEquals("0", vm.uiState.value.result!!.result.toString())
        val hint = vm.uiState.value.variableHint!!
        assertEquals(listOf("y"), hint.candidates)
        assertTrue(hint.isDerivative)
        assertEquals(0, hint.nodeIndex)

        // A name the hint did not offer is no way around it
        vm.applyVariableHint("z")
        assertEquals("x", (vm.uiState.value.expression.nodeAt(0) as DerivativeNode).variable)

        vm.applyVariableHint("y")
        assertEquals("y", (vm.uiState.value.expression.nodeAt(0) as DerivativeNode).variable)
        vm.awaitLiveResultText("1")
        assertNull(vm.uiState.value.variableHint)
    }

    @Test
    fun noSuggestionWhenTheVariableIsThereOrTheLineIsUnfinished() = runBlocking {
        val vm = newViewModel()
        // d/dx(x·y) is a partial derivative, nothing to correct
        vm.onKeyAction(KeyAction.Derivative)
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Variable("y"))
        vm.awaitLiveResult()
        assertNull(vm.uiState.value.variableHint)

        // ∫ y dx while y is still being typed: "y +" is no question yet
        vm.onKeyAction(KeyAction.Clear)
        vm.onKeyAction(KeyAction.Antiderivative)
        vm.onKeyAction(KeyAction.Variable("y"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.awaitLiveResult()
        assertNull(vm.uiState.value.variableHint)
        vm.onKeyAction(KeyAction.Variable("y"))
        vm.awaitLiveResult()
        assertEquals(listOf("y"), vm.uiState.value.variableHint?.candidates)
        assertFalse(vm.uiState.value.variableHint!!.isDerivative)
    }

    @Test
    fun theSheetOpensWithTheStepsThatWereWorkedOutForThePill() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.awaitLiveResult()
        vm.awaitSteps()

        // No loading: the solution is there as soon as the sheet is opened
        vm.showSteps()
        val state = vm.uiState.value.steps
        assertNotNull(state)
        assertFalse(state!!.isLoading)
        assertNotNull(state.solution)
    }

    @Test
    fun simplificationOffersStepByStepSolution() = runBlocking {
        val vm = newViewModel()
        // x + x = 2x has a step; 2x is already as simple as it gets
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.awaitLiveResult()
        vm.awaitSteps()
        assertTrue(vm.uiState.value.hasSteps)

        vm.onKeyAction(KeyAction.Clear)
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.awaitLiveResult()
        delay(100)
        assertFalse(vm.uiState.value.hasSteps)
    }

    @Test
    fun aPlusPercentOffersThePocketCalculatorReading() = runBlocking {
        val vm = newViewModel()
        // 200 + 10% is 200.1; the hint offers 200 + 200 × 10% = 220
        listOf("2", "0", "0").forEach { vm.onKeyAction(KeyAction.Number(it)) }
        vm.onKeyAction(KeyAction.Operator("+"))
        listOf("1", "0").forEach { vm.onKeyAction(KeyAction.Number(it)) }
        vm.onKeyAction(KeyAction.Operator("%"))
        vm.awaitLiveResult()
        var attempts = 0
        while (vm.uiState.value.percentHint == null && attempts < 100) {
            delay(20)
            attempts++
        }
        val hint = vm.uiState.value.percentHint
        assertNotNull(hint)
        assertEquals("200", hint!!.base)
        assertEquals("10", hint.percent)
        assertEquals("220", hint.result.result.toString())

        // Tapping it replaces the line, and the hint is gone
        vm.applyPercentHint()
        vm.awaitLiveResult()
        assertNull(vm.uiState.value.percentHint)
        assertEquals("220", vm.uiState.value.result!!.result.toString())

        // A line that is no a ± b% has none
        vm.onKeyAction(KeyAction.Clear)
        listOf("5", "0").forEach { vm.onKeyAction(KeyAction.Number(it)) }
        vm.onKeyAction(KeyAction.Operator("%"))
        vm.awaitLiveResult()
        delay(100)
        assertNull(vm.uiState.value.percentHint)
    }

    @Test
    fun anInequalityIsTypedWithTheKeyOfTheEqualsSignAndSolved() = runBlocking {
        val vm = newViewModel()
        // x² − 4 > 0
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Square)
        vm.onKeyAction(KeyAction.Operator("−"))
        vm.onKeyAction(KeyAction.Number("4"))
        vm.onKeyAction(KeyAction.Operator(">"))
        vm.onKeyAction(KeyAction.Number("0"))
        vm.awaitLiveResult()
        val result = vm.uiState.value.result!!
        assertTrue(result.issues.toString(), result.isSuccess)
        assertEquals("x < −2 or x > 2", result.result.toString().replace(Regex("\\s+"), " ").replace("− ", "−").trim())
        vm.awaitSteps()
        assertTrue(vm.uiState.value.hasSteps)
        assertTrue(vm.uiState.value.expression.nodes.any { it is OperatorNode && it.op == MathOp.GREATER })

        // The other signs
        for ((symbol, op) in listOf("<" to MathOp.LESS, "≤" to MathOp.LESS_EQUAL, "≥" to MathOp.GREATER_EQUAL)) {
            vm.onKeyAction(KeyAction.Clear)
            vm.onKeyAction(KeyAction.Variable("x"))
            vm.onKeyAction(KeyAction.Operator(symbol))
            vm.onKeyAction(KeyAction.Number("1"))
            assertTrue(symbol, vm.uiState.value.expression.nodes.any { it is OperatorNode && it.op == op })
        }
    }

    @Test
    fun anExpressionCanBeAskedToBeExpandedOrFactored() = runBlocking {
        val vm = newViewModel()
        // (x + 1)²: the calculator leaves it, "Expand" writes it out
        vm.onKeyAction(KeyAction.LeftParen)
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Number("1"))
        vm.onKeyAction(KeyAction.RightParen)
        vm.onKeyAction(KeyAction.Square)
        vm.awaitLiveResult()
        var attempts = 0
        while (vm.uiState.value.transforms.isEmpty() && attempts < 100) {
            delay(20)
            attempts++
        }
        assertEquals(listOf(com.vortessence.calculator.core.math.steps.Transform.EXPAND), vm.uiState.value.transforms)

        // One pill, "Steps": with only a form to show, the sheet opens on it
        vm.showSteps()
        assertEquals(com.vortessence.calculator.core.math.steps.Transform.EXPAND, vm.uiState.value.steps?.selected)
        attempts = 0
        while (vm.uiState.value.steps?.isLoading != false && attempts < 100) {
            delay(20)
            attempts++
        }
        val solution = vm.uiState.value.steps?.solution
        assertNotNull(solution)
        assertTrue(solution!!.result.toString(), solution.result.toString().contains("2x"))

        // Typing on takes the pills away again
        vm.dismissSteps()
        vm.onKeyAction(KeyAction.Clear)
        assertTrue(vm.uiState.value.transforms.isEmpty())
    }

    @Test
    fun aLimitFromTheRightIsTypedWithASuperscriptPlus() = runBlocking {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Limit)
        // The cursor is behind "x →": 0, then a plus as the superscript of the 0
        vm.onKeyAction(KeyAction.Number("0"))
        vm.onKeyAction(KeyAction.SuperscriptSign(plus = true))
        val limit = vm.uiState.value.expression.nodes.single() as LimitNode
        val sign = limit.approach.nodes.last() as ExponentNode
        assertEquals("0", sign.base.toString())
        assertEquals(MathOp.PLUS, (sign.exponent.nodes.single() as OperatorNode).op)
    }

    @Test
    fun aFractionInequalityTypedWithTheDivideKeyHasSteps() = runBlocking {
        val vm = newViewModel()
        // (7x + 5)/(3x − 5) < 5
        vm.onKeyAction(KeyAction.LeftParen)
        vm.onKeyAction(KeyAction.Number("7"))
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.onKeyAction(KeyAction.Number("5"))
        vm.onKeyAction(KeyAction.MoveRight)
        vm.onKeyAction(KeyAction.Operator("÷"))
        vm.onKeyAction(KeyAction.LeftParen)
        vm.onKeyAction(KeyAction.Number("3"))
        vm.onKeyAction(KeyAction.Variable("x"))
        vm.onKeyAction(KeyAction.Operator("−"))
        vm.onKeyAction(KeyAction.Number("5"))
        vm.onKeyAction(KeyAction.MoveRight)
        vm.onKeyAction(KeyAction.MoveRight)
        vm.onKeyAction(KeyAction.Operator("<"))
        vm.onKeyAction(KeyAction.Number("5"))
        vm.awaitLiveResult()
        val result = vm.uiState.value.result!!
        assertTrue(result.issues.toString(), result.isSuccess)
        vm.awaitSteps()
        assertTrue(vm.uiState.value.expression.toString(), vm.uiState.value.hasSteps)
    }

    /** Waits (a while) until the "Steps" pill is offered. */
    private suspend fun CalculatorViewModel.awaitSteps() {
        var attempts = 0
        while (!uiState.value.hasSteps && attempts < 100) {
            delay(20)
            attempts++
        }
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

    private fun typeKeys(vm: CalculatorViewModel, vararg actions: KeyAction) = actions.forEach { vm.onKeyAction(it) }

    @Test
    fun copyAndPasteKeepsStructureAndReplacesSelection() {
        val vm = newViewModel()
        // 1/2 + 3
        typeKeys(vm, KeyAction.Number("1"), KeyAction.Operator("÷"), KeyAction.Number("2"), KeyAction.MoveRight,
            KeyAction.Operator("+"), KeyAction.Number("3"))
        vm.setSelection(emptyList(), 0..0)
        val copied = vm.selectedMath()!!
        assertTrue(copied.nodeAt(0) is FractionNode)

        // Pasting over the "3" replaces it; the cursor ends up after the pasted fraction
        vm.setSelection(emptyList(), 2..2)
        assertTrue(vm.paste(copied))
        val state = vm.uiState.value
        assertEquals("(1)/(2) + (1)/(2)", state.expression.toString())
        assertEquals(listOf(3), state.cursorPath)
        assertNull(state.selectionRange)
        // The pasted fraction is a copy: editing one does not change the other
        assertNotSame(state.expression.nodeAt(0), state.expression.nodeAt(2))
    }

    @Test
    fun pastedNumbersCanBeEditedDigitByDigit() {
        val vm = newViewModel()
        assertTrue(vm.paste(MathString(listOf(NumberNode("125")))))
        assertEquals(3, vm.uiState.value.expression.length)
        assertEquals(listOf(3), vm.uiState.value.cursorPath)
    }

    @Test
    fun pasteIntoASlot() {
        val vm = newViewModel()
        typeKeys(vm, KeyAction.Sqrt)
        assertTrue(vm.paste(MathString(listOf(NumberNode("2"), VariableNode("x")))))
        assertEquals("sqrt(2x)", vm.uiState.value.expression.toString())
        assertEquals(listOf(0, 0, 2), vm.uiState.value.cursorPath)

        // Several lines only fit at the top level
        assertFalse(vm.paste(MathString(listOf(NumberNode("1"), LineBreakNode, NumberNode("2")))))
        assertEquals("sqrt(2x)", vm.uiState.value.expression.toString())
    }

    @Test
    fun cutRemovesTheSelection() {
        val vm = newViewModel()
        typeKeys(vm, KeyAction.Number("1"), KeyAction.Operator("+"), KeyAction.Number("2"))
        vm.setSelection(emptyList(), 1..2)
        assertEquals(" + 2", vm.cutSelection().toString())
        assertEquals("1", vm.uiState.value.expression.toString())
        assertEquals(listOf(1), vm.uiState.value.cursorPath)
        assertNull(vm.cutSelection())
    }

    @Test
    fun selectAllSelectsTheWholeInputFromAnywhere() {
        val vm = newViewModel()
        vm.selectAll()
        assertNull(vm.uiState.value.selectionRange)

        typeKeys(vm, KeyAction.Number("2"), KeyAction.Operator("+"), KeyAction.Number("1"), KeyAction.Operator("÷"))
        vm.selectAll()
        assertEquals(0..2, vm.uiState.value.selectionRange)
        assertEquals(listOf(3), vm.uiState.value.cursorPath)
    }

    @Test
    fun formulaGoesToItsFirstBlank() = runBlocking {
        val vm = newViewModel()
        typeKeys(vm, KeyAction.Number("2"), KeyAction.Operator("+"))
        // nCr(, ): the cursor starts before the comma, inside the function
        vm.insertFormula(MathString(listOf(FunctionNode("nCr", MathString(listOf(SeparatorNode))))))
        assertEquals(listOf(2, 0, 0), vm.uiState.value.cursorPath)
        typeKeys(vm, KeyAction.Number("5"), KeyAction.MoveRight, KeyAction.Number("2"))
        assertEquals("2 + nCr(5, 2)", vm.uiState.value.expression.toString())
        vm.awaitLiveResult()
        assertEquals(12.0, vm.uiState.value.result!!.numericValue, 0.0)

        // √(□² + □²): into the first empty base
        val vm2 = newViewModel()
        val square = ExponentNode(MathString(), MathString(listOf(NumberNode("2"))))
        vm2.insertFormula(MathString(listOf(RadicalNode(radicand = MathString(listOf(square, OperatorNode(MathOp.PLUS), square))))))
        assertEquals(listOf(0, 0, 0, 0, 0), vm2.uiState.value.cursorPath)
    }

    @Test
    fun listFormulaWrapsTheSelectedValues() = runBlocking {
        val vm = newViewModel()
        typeKeys(vm, KeyAction.Number("2"), KeyAction.Separator, KeyAction.Number("4"), KeyAction.Separator, KeyAction.Number("9"))
        vm.selectAll()
        vm.insertFormula(MathString(listOf(FunctionNode("mean"))))
        assertEquals("mean(2, 4, 9)", vm.uiState.value.expression.toString())
        vm.awaitLiveResult()
        assertEquals(5.0, vm.uiState.value.result!!.numericValue, 0.0)
    }

    @Test
    fun testPolarKeyTakesNumberAsMagnitudeAndTurnsOnComplexNumbers() {
        val vm = newViewModel()
        vm.onKeyAction(KeyAction.Number("2"))
        vm.onKeyAction(KeyAction.Polar)
        assertTrue(vm.uiState.value.complexNumbers)
        assertTrue(vm.uiState.value.showComplexNotice)
        assertTrue(vm.uiState.value.expression.nodeAt(0) is PolarNode)
        assertEquals(listOf(0, 1, 0), vm.uiState.value.cursorPath)
        // Backspace in the empty angle leaves the number
        vm.onKeyAction(KeyAction.Backspace)
        assertEquals("2", vm.uiState.value.expression.toString())
    }

    /** Waits until the restored history has been calculated again. */
    private suspend fun CalculatorViewModel.awaitHistory(size: Int) {
        var attempts = 0
        while (uiState.value.history.size != size && attempts < 100) {
            delay(20)
            attempts++
        }
        assertEquals(size, uiState.value.history.size)
    }

    @Test
    fun historyVariablesAndLineSurviveClosingTheApp() = runBlocking {
        val store = MemorySessionStore()
        val first = newViewModel(store)
        first.defineVariable("a", "2")
        first.type("5")
        first.onKeyAction(KeyAction.Enter)
        first.awaitCommit()
        first.type("7", "+")
        first.saveSession()

        val reopened = newViewModel(store)
        reopened.awaitHistory(2)
        assertEquals(listOf("5", "a := 2"), reopened.uiState.value.history.map { it.calculation.input.toString() })
        assertEquals("a = 2", reopened.uiState.value.history[1].calculation.result.toString())
        assertEquals("7 + ", reopened.uiState.value.expression.toString())
        assertEquals(listOf(2), reopened.uiState.value.cursorPath)
        // The variable is still defined, and Ans is the last result
        assertEquals("3", reopened.liveResultOf("a", "+", "1"))
        // New entries do not clash with the restored ones
        reopened.type("9")
        reopened.onKeyAction(KeyAction.Enter)
        reopened.awaitCommit()
        val ids = reopened.uiState.value.history.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun swipedAwayEntriesStayAway() = runBlocking {
        val store = MemorySessionStore()
        val first = newViewModel(store)
        first.defineVariable("a", "2")
        first.type("5")
        first.onKeyAction(KeyAction.Enter)
        first.awaitCommit()
        first.deleteHistoryEntry(first.uiState.value.history.single { it.calculation.definedVariable == "a" }.id)
        first.saveSession()

        val reopened = newViewModel(store)
        reopened.awaitHistory(1)
        assertEquals("5", reopened.uiState.value.history[0].calculation.input.toString())
        // a is a plain symbol again
        reopened.onKeyAction(KeyAction.Variable("a"))
        reopened.type("+", "1")
        reopened.awaitLiveResultText("a + 1")
    }

    @Test
    fun clearAllClearsWhatWasSaved() = runBlocking {
        val store = MemorySessionStore()
        val first = newViewModel(store)
        first.defineVariable("a", "2")
        first.type("4")
        first.onKeyAction(KeyAction.Clear) // clears the line
        first.onKeyAction(KeyAction.Clear) // line is empty: clears everything
        first.saveSession()

        val reopened = newViewModel(store)
        assertTrue(reopened.uiState.value.history.isEmpty())
        assertTrue(reopened.uiState.value.expression.isEmpty)
    }

    @Test
    fun anEntryKeepsItsResultWhenTheDefinitionItUsedIsGone() = runBlocking {
        val store = MemorySessionStore()
        val first = newViewModel(store)
        first.defineVariable("a", "2")
        first.onKeyAction(KeyAction.Variable("a"))
        first.type("+", "1")
        first.onKeyAction(KeyAction.Enter)
        first.awaitCommit()
        first.deleteHistoryEntry(first.uiState.value.history.single { it.calculation.definedVariable == "a" }.id)
        first.saveSession()

        val reopened = newViewModel(store)
        reopened.awaitHistory(1)
        assertEquals("a + 1", reopened.uiState.value.history[0].calculation.input.toString())
        assertEquals("3", reopened.uiState.value.history[0].calculation.result.toString())
    }

    @Test
    fun theFormAnEntryWasShownInIsKept() = runBlocking {
        val store = MemorySessionStore()
        val first = newViewModel(store)
        first.setResultForm(com.vortessence.calculator.core.math.type.ResultForm.DECIMAL)
        first.type("1", "÷", "3")
        first.onKeyAction(KeyAction.Enter)
        first.awaitCommit()
        first.saveSession()

        val reopened = newViewModel(store)
        reopened.awaitHistory(1)
        assertEquals(com.vortessence.calculator.core.math.type.ResultForm.DECIMAL, reopened.uiState.value.history[0].form)
    }

    @Test
    fun changesAreSavedWithoutAskingAfterAShortPause() = runBlocking {
        val store = MemorySessionStore()
        val vm = newViewModel(store)
        vm.type("5")
        assertNull(store.text) // not on every key
        awaitSaved(store)
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        awaitSaved(store) { it.contains("entry\t") }
    }

    private fun savedEntries(store: MemorySessionStore) = SessionCodec.decode(store.text!!)!!.entries

    @Test
    fun anEntryThatCannotBeCalculatedAgainIsKeptForTheNextStart() = runBlocking {
        val broken = SavedEntry(
            1, MathString(listOf(NumberNode("1"), OperatorNode(MathOp.PLUS))), // "1 +" has no result
            com.vortessence.calculator.core.math.type.ResultForm.EXACT,
            CalculationSnapshot(AngleUnit.RADIANS, UnitSystem.METRIC, null, null, emptyMap(), false)
        )
        val good = broken.copy(id = 2, input = MathString(listOf(NumberNode("5"))))
        val store = MemorySessionStore(SessionCodec.encode(SavedSession(MathString(), listOf(good, broken))))

        val vm = newViewModel(store)
        vm.awaitHistory(1)
        vm.saveSession()
        // Not shown, but still saved, in its place
        assertEquals(listOf(2L, 1L), savedEntries(store).map { it.id })

        // Only AC removes it
        vm.onKeyAction(KeyAction.Clear)
        vm.saveSession()
        assertTrue(savedEntries(store).isEmpty())
    }

    @Test
    fun enteringALineWhileTheHistoryIsRestoredStillSeesItsVariables() = runBlocking {
        val store = MemorySessionStore()
        val saver = newViewModel(store)
        saver.defineVariable("a", "2")
        saver.saveSession()

        val reopened = newViewModel(store)
        // Straight away, before the saved history is back
        reopened.onKeyAction(KeyAction.Variable("a"))
        reopened.type("+", "1")
        reopened.onKeyAction(KeyAction.Enter)
        reopened.awaitCommit()
        reopened.awaitHistory(2)
        assertEquals("3", reopened.uiState.value.history[0].calculation.result.toString())
        assertEquals(listOf(1L, 0L), reopened.uiState.value.history.map { it.id })
    }

    @Test
    fun savedEntriesOnlyCarryWhatTheyUse() = runBlocking {
        val store = MemorySessionStore()
        val vm = newViewModel(store)
        vm.defineVariable("a", "2")
        vm.defineVariable("b", "3")
        vm.type("4")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        vm.onKeyAction(KeyAction.Variable("a"))
        vm.type("+", "1")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        vm.saveSession()

        val entries = savedEntries(store) // newest first: a + 1, 4, b := 3, a := 2
        assertEquals(setOf("a"), entries[0].snapshot.variables.keys)
        assertTrue(entries[1].snapshot.variables.isEmpty())
        assertNull(entries[1].snapshot.ans)
        assertEquals(setOf("a"), entries[0].snapshot.variables.keys)
    }

    @Test
    fun anUnreadableSavedSessionIsIgnored() = runBlocking {
        val store = MemorySessionStore("this is not a session")
        val vm = newViewModel(store)
        assertTrue(vm.uiState.value.history.isEmpty())
        vm.type("5")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        vm.saveSession()
        newViewModel(store).awaitHistory(1)
    }

    /** Seeds that are known: 100, 101, 102, ... */
    private fun countingSeeds(start: Long = 100L): () -> Long {
        var next = start
        return { next++ }
    }

    @Test
    fun aRandomNumberStaysTheSameWhileTheLineIsEditedAndIsTheOneThatIsCommitted() = runBlocking {
        val vm = newViewModel(seeds = countingSeeds())
        vm.typeText("rand(")
        vm.typeText(")")
        vm.awaitLiveResult()
        val shown = vm.uiState.value.result!!.numericValue
        assertTrue(shown in 0.0..1.0)
        // Editing the line does not draw again
        vm.onKeyAction(KeyAction.Operator("+"))
        vm.type("0")
        vm.awaitLiveResult()
        assertEquals(shown, vm.uiState.value.result!!.numericValue, 1e-12)
        // Enter keeps the number that was shown
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        vm.awaitHistory(1)
        assertEquals(shown, vm.uiState.value.history[0].calculation.numericValue, 1e-12)
    }

    @Test
    fun theNextLineGetsNewRandomNumbers() = runBlocking {
        val vm = newViewModel(seeds = countingSeeds())
        val seeds = mutableListOf(vm.uiState.value.randomSeed)
        val numbers = mutableListOf<Double>()
        repeat(3) {
            vm.typeText("rand(")
            vm.typeText(")")
            vm.awaitLiveResult()
            numbers.add(vm.uiState.value.result!!.numericValue)
            vm.onKeyAction(KeyAction.Enter)
            vm.awaitCommit()
            seeds.add(vm.uiState.value.randomSeed)
        }
        assertEquals(4, seeds.toSet().size)
        assertEquals(3, numbers.toSet().size)
        // C and AC start a line of their own as well
        val before = vm.uiState.value.randomSeed
        vm.typeText("5")
        vm.onKeyAction(KeyAction.Clear)
        assertNotEquals(before, vm.uiState.value.randomSeed)
        val afterC = vm.uiState.value.randomSeed
        vm.onKeyAction(KeyAction.Clear)
        assertNotEquals(afterC, vm.uiState.value.randomSeed)
    }

    @Test
    fun aRandomNumberInTheHistoryIsTheSameAfterTheAppWasClosed() = runBlocking {
        val store = MemorySessionStore()
        val first = newViewModel(store, countingSeeds())
        first.typeText("rand(")
        first.typeText(")")
        first.onKeyAction(KeyAction.Operator("+"))
        first.typeText("randint(")
        first.type("1")
        first.onKeyAction(KeyAction.Separator)
        first.type("1", "0", "0")
        first.awaitLiveResult()
        val shown = first.uiState.value.result!!.numericValue
        first.onKeyAction(KeyAction.Enter)
        first.awaitCommit()
        first.awaitHistory(1)
        first.saveSession()

        val reopened = newViewModel(store, countingSeeds(5000L))
        reopened.awaitHistory(1)
        assertEquals(shown, reopened.uiState.value.history[0].calculation.numericValue, 1e-12)
        assertEquals(first.uiState.value.history[0].calculation.result.toString(), reopened.uiState.value.history[0].calculation.result.toString())
    }

    @Test
    fun theLineBeingTypedKeepsItsRandomNumbersAcrossARestart() = runBlocking {
        val store = MemorySessionStore()
        val first = newViewModel(store, countingSeeds())
        first.typeText("rand(")
        first.typeText(")")
        first.awaitLiveResult()
        val shown = first.uiState.value.result!!.numericValue
        first.saveSession()

        val reopened = newViewModel(store, countingSeeds(5000L))
        reopened.awaitLiveResult()
        assertEquals(shown, reopened.uiState.value.result!!.numericValue, 1e-12)
    }

    @Test
    fun usingAHistoryEntryAgainDrawsAgain() = runBlocking {
        val vm = newViewModel(seeds = countingSeeds())
        vm.typeText("rand(")
        vm.typeText(")")
        vm.awaitLiveResult()
        val first = vm.uiState.value.result!!.numericValue
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        vm.awaitHistory(1)
        // Tapping the entry puts the line (not its number) back
        vm.selectHistoryItem(vm.uiState.value.history[0].calculation)
        vm.awaitLiveResult()
        assertNotEquals(first, vm.uiState.value.result!!.numericValue, 0.0)
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        vm.awaitHistory(2)
        // The entries keep their own numbers
        assertEquals(first, vm.uiState.value.history[1].calculation.numericValue, 1e-12)
        assertNotEquals(first, vm.uiState.value.history[0].calculation.numericValue, 0.0)
    }

    @Test
    fun theVectorFunctionsOfTheMatrixKeyPutTheCursorInTheFirstValue() = runBlocking {
        val vm = newViewModel()
        // dot(▢, ▢): the cursor before the comma
        vm.onKeyAction(KeyAction.Insert(com.vortessence.calculator.model.formulas.FormulasDb.ofTwo("dot")))
        assertEquals(listOf("dot()"), vm.nodeNames())
        assertEquals(listOf(0, 0, 0), vm.uiState.value.cursorPath)
        // A vector in it: the 3×1 matrix of the dialog
        vm.insertMatrix(3, 1)
        val dot = vm.uiState.value.expression.nodes[0] as FunctionNode
        val matrix = dot.argument.nodes[0] as MatrixNode
        assertEquals(3, matrix.rows)
        assertEquals(1, matrix.cols)
        // The one-value functions work like the other function keys
        vm.onKeyAction(KeyAction.Clear)
        vm.onKeyAction(KeyAction.Function("norm"))
        assertEquals(listOf("norm()"), vm.nodeNames())
    }

    @Test
    fun aSelectedVectorBecomesTheFirstValueOfADotProduct() = runBlocking {
        val vm = newViewModel()
        vm.insertMatrix(3, 1)
        vm.setSelection(emptyList(), 0..0)
        vm.onKeyAction(KeyAction.Insert(com.vortessence.calculator.model.formulas.FormulasDb.ofTwo("dot")))
        val dot = vm.uiState.value.expression.nodes.single() as FunctionNode
        // dot(vector, ▢): the vector is still there, then the comma, and the cursor is where the second value goes
        assertTrue(dot.argument.nodes[0] is MatrixNode)
        assertTrue(dot.argument.nodes[1] is SeparatorNode)
        assertEquals(2, dot.argument.length)
        assertEquals(listOf(0, 0, 2), vm.uiState.value.cursorPath)
        assertNull(vm.uiState.value.selectionRange)
    }

    private suspend fun CalculatorViewModel.commit(vararg keys: String) {
        type(*keys)
        onKeyAction(KeyAction.Enter)
        awaitCommit()
    }

    private suspend fun fileOf(vararg lines: List<String>): String {
        val source = newViewModel()
        for (keys in lines) source.commit(*keys.toTypedArray())
        source.type("7", "+")
        return source.sessionText()
    }

    @Test
    fun aSavedSessionOpensInAnEmptyCalculatorWithoutAsking() = runBlocking {
        val file = fileOf(listOf("5"), listOf("2", "+", "3"))
        val other = newViewModel()
        other.offerSession(file)
        other.awaitHistory(2)
        assertNull(other.uiState.value.pendingImport)
        assertEquals(listOf("2 + 3", "5"), other.uiState.value.history.map { it.calculation.input.toString() })
        assertEquals("5", other.uiState.value.history[0].calculation.result.toString())
        assertEquals("7 + ", other.uiState.value.expression.toString())
        assertEquals(SessionMessage.Type.OPENED, other.uiState.value.sessionMessage?.type)
        assertEquals(2, other.uiState.value.sessionMessage?.count)
    }

    @Test
    fun openingASessionWithAHistoryAsksAndCanReplaceIt() = runBlocking {
        val file = fileOf(listOf("5"))
        val other = newViewModel()
        other.commit("9")
        other.offerSession(file)
        assertNotNull(other.uiState.value.pendingImport)
        // Nothing has changed before the answer
        assertEquals(listOf("9"), other.uiState.value.history.map { it.calculation.input.toString() })
        other.importSession(replace = true)
        other.restoreJobDone()
        assertEquals(listOf("5"), other.uiState.value.history.map { it.calculation.input.toString() })
        assertEquals("7 + ", other.uiState.value.expression.toString())
        assertNull(other.uiState.value.pendingImport)
    }

    @Test
    fun openingASessionCanAddItBelowTheHistory() = runBlocking {
        val file = fileOf(listOf("5"), listOf("6"))
        val other = newViewModel()
        other.commit("9")
        other.type("1")
        other.offerSession(file)
        other.importSession(replace = false)
        other.awaitHistory(3)
        other.restoreJobDone()
        assertEquals(listOf("9", "6", "5"), other.uiState.value.history.map { it.calculation.input.toString() })
        // The line being typed is kept
        assertEquals("1", other.uiState.value.expression.toString())
        // A new entry still comes first and the ids do not clash
        other.commit("8")
        val ids = other.uiState.value.history.map { it.id }
        assertEquals("18", other.uiState.value.history[0].calculation.input.toString())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun cancellingTheQuestionLeavesEverythingAsItWas() = runBlocking {
        val file = fileOf(listOf("5"))
        val other = newViewModel()
        other.commit("9")
        other.offerSession(file)
        other.cancelImport()
        assertNull(other.uiState.value.pendingImport)
        assertEquals(listOf("9"), other.uiState.value.history.map { it.calculation.input.toString() })
    }

    @Test
    fun aFileThatIsNoSessionIsSaidSoAndChangesNothing() = runBlocking {
        val other = newViewModel()
        other.commit("9")
        other.offerSession("hello")
        assertEquals(SessionMessage.Type.NOT_A_SESSION, other.uiState.value.sessionMessage?.type)
        assertNull(other.uiState.value.pendingImport)
        assertEquals(1, other.uiState.value.history.size)
    }

    @Test
    fun variablesComeBackWithAnOpenedSession() = runBlocking {
        val source = newViewModel()
        source.defineVariable("a", "2")
        val file = source.sessionText()
        val other = newViewModel()
        other.offerSession(file)
        other.awaitHistory(1)
        other.type("3")
        other.onKeyAction(KeyAction.Operator("×"))
        other.onKeyAction(KeyAction.Variable("a"))
        other.awaitLiveResultText("6")
    }

    @Test
    fun aFileWithAByteOrderMarkOpens() = runBlocking {
        val file = fileOf(listOf("5"))
        val other = newViewModel()
        other.offerSession("\uFEFF" + file)
        other.awaitHistory(1)
    }

    @Test
    fun anOpenedSessionIsSavedAgainForTheNextStart() = runBlocking {
        val file = fileOf(listOf("5"), listOf("6"))
        val store = MemorySessionStore()
        val other = newViewModel(store)
        other.offerSession(file)
        other.awaitHistory(2)
        other.restoreJobDone()
        // Saving was switched off while the file was being opened, and is on again
        awaitSaved(store) { it.contains("\t[N(\"6\",0)]\t") && it.contains("\t[N(\"5\",0)]\t") }
        val reopened = newViewModel(store)
        reopened.awaitHistory(2)
        assertEquals(listOf("6", "5"), reopened.uiState.value.history.map { it.calculation.input.toString() })
    }

    @Test
    fun addingSessionsTwiceKeepsTheOrderAndTheIdsApartAcrossAStart() = runBlocking {
        val first = fileOf(listOf("1"), listOf("2"))
        val second = fileOf(listOf("3"), listOf("4"))
        val store = MemorySessionStore()
        val other = newViewModel(store)
        other.commit("9")
        other.offerSession(first)
        other.importSession(replace = false)
        other.awaitHistory(3)
        other.restoreJobDone()
        other.dismissSessionMessage()
        other.offerSession(second)
        other.importSession(replace = false)
        other.awaitHistory(5)
        other.restoreJobDone()
        val inputs = listOf("9", "2", "1", "4", "3")
        assertEquals(inputs, other.uiState.value.history.map { it.calculation.input.toString() })
        val ids = other.uiState.value.history.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        other.saveSession()
        val reopened = newViewModel(store)
        reopened.awaitHistory(5)
        assertEquals(inputs, reopened.uiState.value.history.map { it.calculation.input.toString() })
    }

    private suspend fun CalculatorViewModel.restoreJobDone() {
        // The entries show up one by one; the message comes when the last one has
        var attempts = 0
        while (uiState.value.sessionMessage == null && attempts < 100) {
            delay(20)
            attempts++
        }
    }

    private fun CalculatorViewModel.line() = uiState.value.expression.toString()

    @Test
    fun digitsOfAnotherBaseStartWithTheirPrefix() = runBlocking {
        val vm = newViewModel()
        vm.setInputMode(InputMode.HEXADECIMAL)
        vm.type("F", "F")
        assertEquals("0xFF", vm.line())
        vm.onKeyAction(KeyAction.Operator("AND"))
        vm.type("0", "F")
        assertEquals("0xFF AND 0x0F", vm.line())
        vm.awaitLiveResultText("15")
    }

    @Test
    fun aDigitThatIsNotOfTheBaseTypesNothing() = runBlocking {
        val vm = newViewModel()
        vm.setInputMode(InputMode.BINARY)
        vm.type("1", "0", "2", "F", "1")
        assertEquals("0b101", vm.line())
        // The same inside a number that has a prefix, in another mode
        vm.setInputMode(InputMode.DECIMAL)
        vm.type("5")
        assertEquals("0b101", vm.line())
        vm.type("1")
        assertEquals("0b1011", vm.line())
    }

    @Test
    fun aDecimalNumberTurnsIntoTheBaseOfTheMode() = runBlocking {
        val vm = newViewModel()
        vm.type("1", "2")
        vm.setInputMode(InputMode.HEXADECIMAL)
        vm.type("F")
        assertEquals("0xCF", vm.line())
    }

    @Test
    fun theNumberOfAnotherBaseCanBeDeletedWithItsPrefix() = runBlocking {
        val vm = newViewModel()
        vm.setInputMode(InputMode.OCTAL)
        vm.type("7", "7")
        vm.onKeyAction(KeyAction.Backspace)
        assertEquals("0o7", vm.line())
        vm.onKeyAction(KeyAction.Backspace)
        // The prefix goes with the last digit
        assertEquals("", vm.line())
        assertEquals(listOf(0), vm.uiState.value.cursorPath)
    }

    @Test
    fun thePrefixIsKeptWhenDigitsAreLeft() = runBlocking {
        val vm = newViewModel()
        vm.setInputMode(InputMode.HEXADECIMAL)
        vm.type("1", "F")
        vm.onKeyAction(KeyAction.MoveLeft)
        vm.onKeyAction(KeyAction.Backspace)
        assertEquals("0xF", vm.line())
    }

    @Test
    fun theModeDecidesHowResultsAreShown() = runBlocking {
        val vm = newViewModel()
        assertEquals(ResultForm.EXACT, vm.uiState.value.resultForm)
        vm.setInputMode(InputMode.HEXADECIMAL)
        assertEquals(ResultForm.HEX, vm.uiState.value.resultForm)
        vm.setInputMode(InputMode.BINARY)
        assertEquals(ResultForm.BIN, vm.uiState.value.resultForm)
        // Back to decimal: the base form goes, the other forms are the user's
        vm.setInputMode(InputMode.DECIMAL)
        assertEquals(ResultForm.EXACT, vm.uiState.value.resultForm)
        vm.setResultForm(ResultForm.SCIENTIFIC)
        vm.setInputMode(InputMode.DECIMAL)
        assertEquals(ResultForm.SCIENTIFIC, vm.uiState.value.resultForm)
    }

    @Test
    fun aCommittedEntryKeepsTheBaseItWasShownIn() = runBlocking {
        val store = MemorySessionStore()
        val vm = newViewModel(store)
        vm.setInputMode(InputMode.HEXADECIMAL)
        vm.type("F", "F")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        assertEquals(ResultForm.HEX, vm.uiState.value.history[0].form)
        vm.saveSession()
        val reopened = newViewModel(store)
        reopened.awaitHistory(1)
        assertEquals("0xFF", reopened.uiState.value.history[0].calculation.input.toString())
        assertEquals(ResultForm.HEX, reopened.uiState.value.history[0].form)
    }

    @Test
    fun deletingTheDigitsWithASelectionTakesThePrefixAlong() = runBlocking {
        val vm = newViewModel()
        vm.setInputMode(InputMode.HEXADECIMAL)
        vm.type("F", "F")
        // Selecting the digits only (the prefix is the first node) and deleting them
        vm.setSelection(emptyList(), 1..2)
        vm.onKeyAction(KeyAction.Backspace)
        assertEquals("", vm.line())
        assertEquals(listOf(0), vm.uiState.value.cursorPath)
    }

    @Test
    fun aDigitOfAnotherBaseIsNotPutInsideADecimalNumber() = runBlocking {
        val vm = newViewModel()
        vm.type("1", "2", "3")
        vm.onKeyAction(KeyAction.MoveLeft)
        vm.setInputMode(InputMode.HEXADECIMAL)
        vm.type("A")
        assertEquals("123", vm.line())
    }

    @Test
    fun lettersAreNoDigitsInTheDecimalMode() = runBlocking {
        val vm = newViewModel()
        vm.type("1", "F")
        assertEquals("1", vm.line())
    }

    @Test
    fun aPastedHexResultCanBeEditedLikeATypedOne() = runBlocking {
        val vm = newViewModel()
        vm.setInputMode(InputMode.HEXADECIMAL)
        vm.type("F", "F")
        vm.onKeyAction(KeyAction.Enter)
        vm.awaitCommit()
        // What a long press on the result copies: the number in one node, 0xFF
        val shown = ResultFormatter.format(vm.uiState.value.history[0].calculation, ResultForm.HEX)
        assertTrue(vm.paste(shown))
        assertEquals("0xFF", vm.line())
        // It is the prefix and the digits again: a digit goes after them, and the prefix goes with the last digit
        vm.type("1")
        assertEquals("0xFF1", vm.line())
        vm.awaitLiveResultText("4081")
        repeat(4) { vm.onKeyAction(KeyAction.Backspace) }
        assertEquals("", vm.line())
    }


    private suspend fun CalculatorViewModel.awaitPlotScene() {
        var attempts = 0
        while (uiState.value.plotScene == null && attempts < 100) {
            delay(20)
            attempts++
        }
        assertNotNull(uiState.value.plotScene)
    }

    /** y = x on one line and y = 2 − x on the next. */
    private fun CalculatorViewModel.typeTwoLines() {
        onKeyAction(KeyAction.Variable("y"))
        onKeyAction(KeyAction.Operator("="))
        onKeyAction(KeyAction.Variable("x"))
        onKeyAction(KeyAction.NewLine)
        onKeyAction(KeyAction.Variable("y"))
        onKeyAction(KeyAction.Operator("="))
        type("2")
        onKeyAction(KeyAction.Operator("−"))
        onKeyAction(KeyAction.Variable("x"))
    }

    @Test
    fun everyLineBecomesACurveOfTheGraph() = runBlocking {
        val vm = newViewModel()
        vm.typeTwoLines()
        vm.toggleGraph()
        vm.awaitPlotScene()
        val scene = vm.uiState.value.plotScene!!
        assertEquals(2, scene.entries.size)
        assertTrue(scene.entries.all { it.curve != null })
        assertTrue(vm.uiState.value.isGraphOpen)
    }

    @Test
    fun theGraphSeesTheDefinitionsOfTheHistory() = runBlocking {
        val vm = newViewModel()
        vm.defineVariable("k", "3")
        vm.onKeyAction(KeyAction.Variable("y"))
        vm.onKeyAction(KeyAction.Operator("="))
        vm.onKeyAction(KeyAction.Variable("k"))
        vm.toggleGraph()
        vm.awaitPlotScene()
        assertNotNull(vm.uiState.value.plotScene!!.entries.single().curve)
    }

    @Test
    fun theAddButtonLeavesTheGraphForANewLine() = runBlocking {
        val vm = newViewModel()
        vm.typeTwoLines()
        vm.toggleGraph()
        vm.awaitPlotScene()
        vm.addGraphLine()
        assertFalse(vm.uiState.value.isGraphOpen)
        assertEquals(3, vm.uiState.value.expression.nodes.count { it is LineBreakNode } + 1)
        assertTrue(vm.uiState.value.expression.nodes.last() is LineBreakNode)
        assertEquals(listOf(vm.uiState.value.expression.length), vm.uiState.value.cursorPath)
        // Once more does not stack empty lines: the editor already ends in a new line
        vm.toggleGraph()
        vm.addGraphLine()
        assertEquals(1, vm.uiState.value.expression.nodes.takeLastWhile { it is LineBreakNode }.size)
    }

    @Test
    fun theAddButtonOnAnEmptyEditorAddsNothing() = runBlocking {
        val vm = newViewModel()
        vm.addGraphLine()
        assertTrue(vm.uiState.value.expression.isEmpty)
    }
}
