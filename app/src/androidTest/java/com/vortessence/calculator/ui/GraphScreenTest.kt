package com.vortessence.calculator.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.vortessence.calculator.R
import com.vortessence.calculator.core.math.MathEngine
import com.vortessence.calculator.core.math.solvers.PlotScene
import com.vortessence.calculator.core.math.type.*
import com.vortessence.calculator.ui.graph.GraphPlotterView
import com.vortessence.calculator.ui.theme.CalculatorTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The graph with several curves: the legend, the crossings, the tools, as a screen reader and a finger meet them. */
class GraphScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int) = resources.getString(id)

    private fun curves(vararg lines: List<MathNode>): Pair<MathString, PlotScene> {
        val nodes = mutableListOf<MathNode>()
        lines.forEachIndexed { i, line ->
            if (i > 0) nodes.add(LineBreakNode)
            nodes.addAll(line)
        }
        val input = MathString(nodes)
        return input to runBlocking { MathEngine.createPlotScene(input) }
    }

    private fun n(s: String) = NumberNode(s)
    private fun v(s: String) = VariableNode(s)
    private fun op(o: MathOp) = OperatorNode(o)

    /** y = x² and y = 2x + 1, which cross twice. */
    private val parabolaAndLine = curves(
        listOf(v("y"), op(MathOp.EQUALS), v("x"), ExponentNode(null, MathString(listOf(n("2"))))),
        listOf(v("y"), op(MathOp.EQUALS), n("2"), op(MathOp.MULTIPLY), v("x"), op(MathOp.PLUS), n("1"))
    )

    private var addedLines = 0
    private var closed = 0

    private fun show(curves: Pair<MathString, PlotScene>) {
        rule.setContent {
            CalculatorTheme(darkTheme = false) {
                GraphPlotterView(
                    scene = curves.second,
                    expression = curves.first,
                    onAddLine = { addedLines++ },
                    onClose = { closed++ }
                )
            }
        }
    }

    private fun summary(text: String) = SemanticsMatcher("graph summary $text") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == text
    }

    private val isLegendDot = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
    private val isLegendLabel = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun waitForSummary(text: String) = rule.waitUntil(10_000) { rule.onAllNodes(summary(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun theGraphTellsHowManyCurvesAndCrossingsItShows() {
        show(parabolaAndLine)
        waitForSummary("2 curves, 2 intersections")
    }

    @Test
    fun hidingACurveRemovesItsCrossings() {
        show(parabolaAndLine)
        waitForSummary("2 curves, 2 intersections")
        rule.onAllNodes(isLegendDot)[1].performClick()
        waitForSummary("1 curve, 0 intersections")
        rule.onAllNodes(isLegendDot)[1].assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.graph_curve_hidden)))
        // And back
        rule.onAllNodes(isLegendDot)[1].performClick()
        waitForSummary("2 curves, 2 intersections")
        rule.onAllNodes(isLegendDot)[1].assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.graph_curve_shown)))
    }

    @Test
    fun tappingACurveInTheLegendMakesItTheActiveOne() {
        show(parabolaAndLine)
        rule.onAllNodes(isLegendLabel).assertCountEquals(2)
        rule.onAllNodes(isLegendLabel)[0].assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onAllNodes(isLegendLabel)[1].performClick()
        rule.onAllNodes(isLegendLabel)[1].assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onAllNodes(isLegendLabel)[0].assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test
    fun theButtonsAddALineAndCloseTheGraph() {
        show(parabolaAndLine)
        rule.onNodeWithContentDescription(text(R.string.graph_add_line)).performClick()
        assertEquals(1, addedLines)
        rule.onNodeWithContentDescription(text(R.string.graph_close)).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun aSingleCurveHasNoLegendAndNoCrossings() {
        show(curves(listOf(v("y"), op(MathOp.EQUALS), v("x"), ExponentNode(null, MathString(listOf(n("2")))))))
        waitForSummary("1 curve, 0 intersections")
        rule.onAllNodes(isLegendDot).assertCountEquals(0)
    }

    @Test
    fun aCurveThatCannotBePlottedIsSaidSoInTheLegend() {
        // y = x² next to a surface, which cannot share the picture with curves
        show(curves(
            listOf(v("y"), op(MathOp.EQUALS), v("x"), ExponentNode(null, MathString(listOf(n("2"))))),
            listOf(v("x"), op(MathOp.PLUS), v("y"), op(MathOp.PLUS), v("z"), op(MathOp.EQUALS), n("1"))
        ))
        waitForSummary("1 curve, 0 intersections")
        rule.onNodeWithText(text(R.string.graph_line_cannot_plot)).assertIsDisplayed()
    }

    @Test
    fun theToolsAreOffered() {
        show(parabolaAndLine)
        rule.onNodeWithText(text(R.string.graph_tool_trace)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.graph_tool_tangent)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.graph_tool_integral)).assertIsDisplayed()
    }
}
