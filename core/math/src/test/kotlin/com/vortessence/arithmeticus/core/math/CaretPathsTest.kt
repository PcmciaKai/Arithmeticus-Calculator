package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.type.*
import org.junit.Assert.*
import org.junit.Test

class CaretPathsTest {
    private fun n(s: String) = NumberNode(s)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())

    // 1 + (23)/(4) − 5, the fraction is node 2
    private val root = ms(
        n("1"), OperatorNode(MathOp.PLUS),
        FractionNode(ms(n("2"), n("3")), ms(n("4"))),
        OperatorNode(MathOp.MINUS), n("5")
    )

    @Test
    fun selectionBetweenTwoCaretsInTheSameString() {
        val sel = CaretPaths.covering(listOf(listOf(1), listOf(4)), root)
        assertEquals(CaretPaths.Selection(emptyList(), 1..3), sel)
        // Order of the carets does not matter (handles may cross)
        assertEquals(sel, CaretPaths.covering(listOf(listOf(4), listOf(1)), root))
    }

    @Test
    fun selectionInsideANestedString() {
        val sel = CaretPaths.covering(listOf(listOf(2, 0, 0), listOf(2, 0, 2)), root)
        assertEquals(CaretPaths.Selection(listOf(2, 0), 0..1), sel)
    }

    @Test
    fun caretInsideAStructureSelectsTheWholeStructure() {
        // From before "1" into the numerator: the whole fraction is included
        val sel = CaretPaths.covering(listOf(listOf(0), listOf(2, 0, 1)), root)
        assertEquals(CaretPaths.Selection(emptyList(), 0..2), sel)
        // From the numerator to the denominator: both are in the fraction, so select it
        val across = CaretPaths.covering(listOf(listOf(2, 0, 1), listOf(2, 1, 0)), root)
        assertEquals(CaretPaths.Selection(emptyList(), 2..2), across)
    }

    @Test
    fun identicalCaretsDoNotSelectAnything() {
        assertNull(CaretPaths.covering(listOf(listOf(3), listOf(3)), root))
        assertNull(CaretPaths.covering(listOf(listOf(9, 0, 0), listOf(9, 0, 1)), root))
    }

    @Test
    fun resolveFindsNestedStrings() {
        assertEquals("23", CaretPaths.resolve(root, listOf(2, 0)).toString())
        assertNull(CaretPaths.resolve(root, listOf(0, 0)))
    }
}
