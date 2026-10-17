package com.vortessence.calculator.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.test.platform.app.InstrumentationRegistry
import com.vortessence.calculator.MainActivity
import com.vortessence.calculator.R
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** The calculator as a person meets it: keys, the line being typed, the result. */
class CalculatorScreenTest {

    /** Every test starts with an empty history and line. */
    private val freshStart = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences("calculator_settings", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(freshStart).around(compose)

    private fun key(label: String) = compose.onAllNodesWithText(label)[0].performClick()

    private fun baseSwitch(label: String) =
        compose.onNodeWithContentDescription("${compose.activity.getString(R.string.keypad_number_base)}: $label")

    private fun described(text: String) = SemanticsMatcher("described as $text") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
    }

    @Test
    fun typingShowsTheLineAndItsResult() {
        key("1"); key("2"); key("+"); key("3")
        compose.waitUntil(5_000) { compose.onAllNodes(described("12 + 3")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(described("15")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aFractionCanBeShownAsADecimal() {
        key("1"); key("÷"); key("4")
        compose.waitUntil(5_000) { compose.onAllNodes(described("1/4")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Decimal").fetchSemanticsNodes().isNotEmpty() }
        key("Decimal")
        compose.waitUntil(5_000) { compose.onAllNodes(described("0.25")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theNumberBaseSwitchOffersTheOtherBases() {
        baseSwitch("DEC").performClick()
        compose.onNodeWithText("HEX").assertIsDisplayed()
        compose.onNodeWithText("OCT").assertIsDisplayed()
        compose.onNodeWithText("BIN").assertIsDisplayed()
    }

    @Test
    fun aBinaryLineIsCalculatedInBinary() {
        baseSwitch("DEC").performClick()
        compose.onNodeWithText("BIN").performClick()
        // In binary mode the digit keys 2 to 9 do nothing
        key("1"); key("0"); key("1"); key("+"); key("1")
        compose.waitUntil(5_000) { compose.onAllNodes(described("0b110")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aDeletedEntryComesBackWithUndo() {
        key("1"); key("+"); key("1")
        compose.waitUntil(5_000) { compose.onAllNodes(described("1 + 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.keypad_enter)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(described("1 + 1")).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(described("2")).fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodes(described("1 + 1"))[0].performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Entry deleted").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(described("1 + 1")).fetchSemanticsNodes().isNotEmpty() }
        // It stays: it is not swiped away again, and there is no new message about a deletion
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.waitForIdle()
        compose.onAllNodes(described("1 + 1")).assertCountEquals(1)
        compose.onAllNodesWithText("Entry deleted").assertCountEquals(0)
    }

    @Test
    fun aClearedHistoryComesBackWithUndo() {
        key("1"); key("+"); key("1")
        compose.waitUntil(5_000) { compose.onAllNodes(described("1 + 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.keypad_enter)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(described("1 + 1")).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(described("2")).fetchSemanticsNodes().isNotEmpty() }

        // The line is empty after Enter: AC (on the last page of the keypad) clears the history. A wide
        // keypad shows all its pages at once, it has nothing to scroll
        val clear = compose.onAllNodesWithText("AC")[0]
        if (compose.onAllNodes(hasText("AC") and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) clear.performScrollTo()
        clear.performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("History cleared").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(described("1 + 1")).assertCountEquals(0)
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(described("1 + 1")).fetchSemanticsNodes().isNotEmpty() }
    }
}
