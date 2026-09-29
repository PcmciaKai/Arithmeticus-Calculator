package com.vortessence.calculator.ui.math

import com.vortessence.calculator.core.math.type.MathNode
import com.vortessence.calculator.core.math.type.MathOp
import com.vortessence.calculator.core.math.type.NumberNode
import com.vortessence.calculator.core.math.type.OperatorNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitGroupingTest {
    private val sep = ' '

    private fun display(vararg nodes: MathNode): String {
        val grouped = groupDigits(nodes.toList())
        return nodes.withIndex().joinToString("") { (i, node) -> grouped[i] ?: node.toString() }
    }

    @Test
    fun groupsWholeNumbersAndTypedDigits() {
        assertEquals("300${sep}000", display(NumberNode("300000")))
        // Typed input is one node per digit
        assertEquals("1${sep}234${sep}567", display(*"1234567".map { NumberNode(it.toString()) }.toTypedArray()))
    }

    @Test
    fun onlyTheIntegerPartOfLargeNumbersIsGrouped() {
        assertEquals("12${sep}345.6789", display(NumberNode("12345.6789")))
        // Four digits stay together (SI convention)
        assertEquals("1234", display(NumberNode("1234")))
        assertTrue(groupDigits(listOf(NumberNode("1234"))).isEmpty())
    }

    @Test
    fun separateNumbersAreGroupedIndependently() {
        assertEquals("10${sep}000 + 99${sep}999",
            display(NumberNode("10000"), OperatorNode(MathOp.PLUS), NumberNode("99999")))
    }
}
