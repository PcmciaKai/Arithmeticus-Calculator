package com.vortessence.arithmeticus

import com.vortessence.arithmeticus.core.math.MathEngine
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.type.MathText
import com.vortessence.arithmeticus.model.help.HelpBlock
import com.vortessence.arithmeticus.model.help.HelpDb
import com.vortessence.arithmeticus.model.formulas.FormulasDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpDbTest {

    private val examples = HelpDb.topics.flatMap { topic -> topic.blocks.filterIsInstance<HelpBlock.Example>().map { topic.id to it } }

    @Test
    fun topicsHaveDistinctIds() {
        val ids = HelpDb.topics.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun theFunctionPickerLinksToTopicsThatExist() {
        FormulasDb.items.mapNotNull { it.helpTopic }.forEach { assertNotNull("No help topic $it", HelpDb.topic(it)) }
    }

    /** What the help says comes out is what comes out: each example is calculated, and its result read as plain text. */
    @Test
    fun examplesCalculateToWhatTheHelpShows() {
        for ((topic, example) in examples) {
            val math = example.math ?: continue
            if (example.result.isEmpty()) continue
            val result = runBlocking { MathEngine.calculate(math, MathContext(complexNumbers = true)) }
            assertTrue("$topic: ${example.input} gives ${result.issues}", result.isSuccess)
            assertEquals("$topic: ${example.input}", plain(example.result), plain(MathText.toPlainText(result.result)))
        }
    }

    /** Both ways of writing a result the same: x³/3 and (x^3)/3, C·e^(x²) and Ce^(x^2), [−2 1; 3/2 −1/2] and [[-2, 1], [3/2, -1/2]]. */
    private fun plain(text: String): String = text
        .replace("³", "^3").replace("²", "^2").replace("⁵", "^5").replace("ˣ", "^x")
        .replace(Regex("[()\\[\\],;·\\s]"), "")
        .replace("−", "-")
}
