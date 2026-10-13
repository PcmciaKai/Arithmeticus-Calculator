package com.vortessence.calculator

import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.UnitSystem
import com.vortessence.calculator.core.math.type.FractionNode
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.NumberNode
import com.vortessence.calculator.core.math.type.ResultForm
import com.vortessence.calculator.core.math.type.TextNode
import com.vortessence.calculator.core.math.type.VariableNode
import com.vortessence.calculator.viewmodel.CalculationSnapshot
import com.vortessence.calculator.viewmodel.SavedEntry
import com.vortessence.calculator.viewmodel.SavedSession
import com.vortessence.calculator.viewmodel.SessionCodec
import org.junit.Assert.*
import org.junit.Test

class SessionCodecTest {
    private fun ms(vararg nodes: com.vortessence.calculator.core.math.type.MathNode) = MathString(nodes.toList())

    private val plain = CalculationSnapshot(AngleUnit.RADIANS, UnitSystem.METRIC, null, null, emptyMap(), false)

    @Test
    fun sessionRoundTrips() {
        val full = CalculationSnapshot(
            AngleUnit.DEGREES, UnitSystem.US_CUSTOMARY, 4, ms(NumberNode("7")),
            linkedMapOf("a" to ms(NumberNode("2")), "b" to ms(FractionNode(ms(NumberNode("1")), ms(VariableNode("x"))))), true
        )
        val session = SavedSession(
            ms(NumberNode("5"), TextNode("a\tb\nc")),
            listOf(
                SavedEntry(7, ms(NumberNode("1")), ResultForm.DECIMAL, plain),
                SavedEntry(3, ms(VariableNode("a")), ResultForm.EXACT, full)
            )
        )
        val decoded = SessionCodec.decode(SessionCodec.encode(session))!!
        assertEquals(session, decoded)
        assertEquals(listOf("a", "b"), decoded.entries[1].snapshot.variables.keys.toList())
    }

    @Test
    fun emptySessionRoundTrips() {
        val session = SavedSession(MathString(), emptyList())
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun unknownVersionOrGarbageIsNotASession() {
        assertNull(SessionCodec.decode(""))
        assertNull(SessionCodec.decode("hello"))
        assertNull(SessionCodec.decode("calculator-session 2\nline\t[]"))
    }

    @Test
    fun anUnreadableEntryIsLeftOutAndTheRestKept() {
        val good = SavedEntry(1, ms(NumberNode("1")), ResultForm.EXACT, plain)
        val lines = SessionCodec.encode(SavedSession(MathString(), listOf(good))).lines().toMutableList()
        val entryLine = lines.first { it.startsWith("entry") }
        lines.add(entryLine.replace("DEGREES", "NOPE").replace("RADIANS", "NOPE"))
        lines.add(entryLine.replace("\t[N(", "\t[Broken(")) // input that cannot be read
        lines.add("entry\ttruncated")
        val decoded = SessionCodec.decode(lines.joinToString("\n"))!!
        assertEquals(listOf(good), decoded.entries)
    }

    @Test
    fun functionsWithSymbolsAndSeveralArgumentsSurviveTheSession() {
        // Γ, ψ and lnΓ are the names of results; a function with several values (and a comma inside) is one node
        val nodes = listOf(
            com.vortessence.calculator.core.math.type.FunctionNode("Γ", ms(NumberNode("2"), NumberNode(".5"))),
            com.vortessence.calculator.core.math.type.FunctionNode("ψ", ms(VariableNode("x"))),
            com.vortessence.calculator.core.math.type.FunctionNode("lnΓ", ms(NumberNode("3"))),
            com.vortessence.calculator.core.math.type.FunctionNode(
                "binompdf", ms(NumberNode("1"), NumberNode("0"), com.vortessence.calculator.core.math.type.SeparatorNode, NumberNode("0"), NumberNode(".5"), com.vortessence.calculator.core.math.type.SeparatorNode, NumberNode("5"))
            )
        )
        val session = SavedSession(ms(*nodes.toTypedArray()), listOf(SavedEntry(1, ms(*nodes.toTypedArray()), ResultForm.EXACT, plain)))
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session))!!)
    }
}
