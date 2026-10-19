package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.type.*
import org.junit.Assert.*
import org.junit.Test

class MathStringCodecTest {
    private fun n(s: String) = NumberNode(s)
    private fun v(name: String) = VariableNode(name)
    private fun op(op: MathOp) = OperatorNode(op)
    private fun ms(vararg nodes: MathNode) = MathString(nodes.toList())

    private fun assertRoundTrip(string: MathString) {
        val encoded = MathStringCodec.encode(string)
        assertFalse("raw line break or tab in $encoded", encoded.any { it == '\n' || it == '\t' || it == '\r' })
        assertEquals(string, MathStringCodec.decode(encoded))
    }

    @Test
    fun emptyString() = assertRoundTrip(MathString())

    @Test
    fun everyKindOfNode() {
        val x = ms(v("x"))
        assertRoundTrip(ms(
            n("12"), NumberNode("1.4626517459071848", approximate = true),
            *MathOp.entries.map { OperatorNode(it) }.toTypedArray(),
            v("a"), ConstantNode("π"), AnsNode, UnitNode("m/s²"),
            FractionNode(ms(n("1")), ms(n("2"))),
            MixedFractionNode(ms(n("3")), ms(n("1")), ms(n("2"))),
            PolarNode(ms(n("2")), ms(n("30"))),
            ExponentNode(null, ms(n("2"))), ExponentNode(x, ms(n("2"))),
            RadicalNode(null, x), RadicalNode(ms(n("3")), x),
            ParenthesesNode(x),
            HighlightNode(x, colorIndex = 2, boxed = true),
            EvaluationNode(x, ms(n("0")), ms(n("1")), "t"),
            SciExponentNode(ms(op(MathOp.MINUS), n("3"))),
            TextNode(" ∪ "), LineBreakNode, SeparatorNode,
            LogBaseNode(ms(n("2")), x), FunctionNode("sin", x),
            IntegralNode(ms(n("0")), ms(n("1")), x, "x"), AntiderivativeNode(x, "y"),
            DerivativeNode(x, null, "x"), DerivativeNode(x, ms(n("2")), "x"),
            BigOperatorNode(BigOperator.SUM, ms(v("n"), op(MathOp.EQUALS), n("1")), ms(n("5")), x),
            BigOperatorNode(BigOperator.PRODUCT, ms(), ms(), ms()),
            LimitNode(ms(v("x"), op(MathOp.CONVERT), n("0")), x),
            MatrixNode(2, 2, listOf(ms(n("1")), ms(n("2")), ms(), ms(FractionNode(x, x))))
        ))
    }

    @Test
    fun nestedSlotsKeepTheirPlace() {
        // The same nodes in different slots must not be mixed up
        val a = FractionNode(ms(n("1")), ms())
        val b = FractionNode(ms(), ms(n("1")))
        assertRoundTrip(ms(a, b))
        assertNotEquals(MathStringCodec.encode(ms(a)), MathStringCodec.encode(ms(b)))
        assertRoundTrip(ms(ExponentNode(ms(), ms(n("2"))), ExponentNode(null, ms(n("2")))))
    }

    @Test
    fun textWithSpecialCharacters() {
        assertRoundTrip(ms(TextNode("a \"quoted\" \\ back\nline\ttab\r(,)[]~")))
        assertRoundTrip(ms(TextNode("")))
    }

    @Test
    fun quoteRoundTrip() {
        val text = "x\"y\\z\n\t"
        assertEquals(text, MathStringCodec.unquote(MathStringCodec.quote(text)))
        assertNull(MathStringCodec.unquote("\"open"))
        assertNull(MathStringCodec.unquote("\"a\"b"))
    }

    @Test
    fun invalidTextIsRejected() {
        val valid = MathStringCodec.encode(ms(n("1"), FractionNode(ms(n("2")), ms(n("3")))))
        assertNotNull(MathStringCodec.decode(valid))
        assertNull(MathStringCodec.decode(""))
        assertNull(MathStringCodec.decode(valid.dropLast(1)))
        assertNull(MathStringCodec.decode(valid + "x"))
        assertNull(MathStringCodec.decode("[Unknown(\"1\")]"))
        assertNull(MathStringCodec.decode("[Op(\"NOPE\")]"))
        assertNull(MathStringCodec.decode("[N(\"1\",2)]"))
        assertNull(MathStringCodec.decode("[Mat(-1,2)]"))
        assertNull(MathStringCodec.decode("[Mat(2,2,[],[])]"))
    }
}
