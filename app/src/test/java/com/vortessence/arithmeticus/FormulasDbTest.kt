package com.vortessence.arithmeticus

import com.vortessence.arithmeticus.core.math.MathEngine
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.expressions.NamedFunctionKind
import com.vortessence.arithmeticus.core.math.type.FunctionNames
import com.vortessence.arithmeticus.core.math.type.FunctionNode
import com.vortessence.arithmeticus.core.math.type.MathNode
import com.vortessence.arithmeticus.core.math.type.MathString
import com.vortessence.arithmeticus.core.math.type.NumberNode
import com.vortessence.arithmeticus.core.math.type.SeparatorNode
import com.vortessence.arithmeticus.model.formulas.Formula
import com.vortessence.arithmeticus.model.formulas.FormulasDb
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
            // Some are inserted as symbols (Γ for gamma); what can be typed is the name
            val name = FunctionNames.symbols.entries.firstOrNull { it.value == name(formula) }?.key ?: name(formula)
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

    @Test
    fun functionsOfAFixedNumberOfValuesShowTheirCommas() {
        for (formula in FormulasDb.items) {
            val function = formula.template.nodes.single() as FunctionNode
            val kind = NamedFunctionKind.byName(function.name) ?: continue
            if (kind.minArguments != kind.maxArguments || kind.minArguments < 2) continue
            val commas = function.argument.nodes.count { it == SeparatorNode }
            assertEquals("${function.name} takes ${kind.minArguments} values", kind.minArguments - 1, commas)
        }
    }
}
