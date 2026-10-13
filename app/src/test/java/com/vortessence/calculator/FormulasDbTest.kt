package com.vortessence.calculator

import com.vortessence.calculator.core.math.MathEngine
import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.type.FunctionNames
import com.vortessence.calculator.core.math.type.FunctionNode
import com.vortessence.calculator.core.math.type.MathNode
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.NumberNode
import com.vortessence.calculator.core.math.type.SeparatorNode
import com.vortessence.calculator.model.formulas.Formula
import com.vortessence.calculator.model.formulas.FormulasDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every entry of the formula picker is a function the calculator knows. */
class FormulasDbTest {

    private fun name(formula: Formula) =
        (formula.template.nodes.single() as FunctionNode).name

    /** The function called with [count] ones. */
    private fun withArguments(name: String, count: Int): MathString {
        val nodes = mutableListOf<MathNode>()
        repeat(count) { i ->
            if (i > 0) nodes.add(SeparatorNode)
            nodes.add(NumberNode("1"))
        }
        return MathString(listOf(FunctionNode(name, MathString(nodes))))
    }

    @Test
    fun everyEntryIsAFunctionTheParserKnows() {
        for (formula in FormulasDb.items) {
            val name = name(formula)
            // Some number of values is right for each function: the others say "wrong number of values", not "unknown"
            val issues = (1..4).map { count ->
                runBlocking { MathEngine.calculate(withArguments(name, count), MathContext()) }.issues
            }
            assertTrue("$name is not known: $issues", issues.any { MathIssue.UNKNOWN_FUNCTION !in it && MathIssue.SYNTAX_ERROR !in it })
        }
    }

    @Test
    fun everyEntryCanBeTypedExceptWhereTheNameStartsWithI() {
        for (formula in FormulasDb.items) {
            val name = name(formula)
            val typed = FunctionNames.endingWord(name)
            if (name.startsWith("i")) {
                // The typed i is refused as the imaginary unit: these are only in the picker
                assertTrue("$name should not be typeable", typed != name)
            } else {
                assertEquals("$name cannot be typed", name, typed)
            }
        }
    }

    @Test
    fun noEntryIsListedTwice() {
        val keys = FormulasDb.items.map { name(it) to it.template.nodes.single().toString() }
        assertEquals(keys.size, keys.toSet().size)
    }
}
