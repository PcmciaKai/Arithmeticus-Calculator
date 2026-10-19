package com.vortessence.arithmeticus.viewmodel

import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.context.UnitSystem
import com.vortessence.arithmeticus.core.math.type.MathString
import com.vortessence.arithmeticus.core.math.type.MathStringCodec
import com.vortessence.arithmeticus.core.math.type.ResultForm

/** A history entry as saved: its input is calculated again when it is restored. */
data class SavedEntry(val id: Long, val input: MathString, val form: ResultForm, val snapshot: CalculationSnapshot)

/** The calculator as the app was closed: the line being typed and the history, newest first. */
data class SavedSession(val expression: MathString, val entries: List<SavedEntry>, val randomSeed: Long = 0L)

/**
 * Where the [SavedSession] is kept between runs of the app. Only the inputs are saved, not the
 * results: those come from calculating the inputs again with the settings, Ans and variables of
 * each entry (see [CalculationSnapshot]), so they are the same as before.
 */
interface SessionStore {
    fun load(): String?
    fun save(text: String)
}

/**
 * The text form of a [SavedSession]: a version line, the line being typed, then one line per
 * history entry. Fields are separated by tabs, which the encoded math never contains.
 */
object SessionCodec {
    private const val HEADER = "calculator-session 1"
    private const val NONE = "-"

    fun encode(session: SavedSession): String = buildString {
        appendLine(HEADER)
        appendLine("line\t${MathStringCodec.encode(session.expression)}\t${session.randomSeed}")
        for (entry in session.entries) {
            val s = entry.snapshot
            val fields = mutableListOf(
                "entry", entry.id.toString(), entry.form.name, s.angleUnit.name, s.unitSystem.name,
                s.decimalPlaces?.toString() ?: NONE, if (s.complexNumbers) "1" else "0",
                MathStringCodec.encode(entry.input), s.ans?.let { MathStringCodec.encode(it) } ?: NONE,
                // The seed of the line, before the variables (an entry from before it has none, and an even number of fields after the ninth)
                s.randomSeed.toString()
            )
            for ((name, value) in s.variables) {
                fields.add(MathStringCodec.quote(name))
                fields.add(MathStringCodec.encode(value))
            }
            appendLine(fields.joinToString("\t"))
        }
    }

    /** The session [text] was made from, or null if it is not a session. Entries that cannot be read are left out. */
    fun decode(text: String): SavedSession? {
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return null
        var expression = MathString()
        var seed = 0L
        val entries = mutableListOf<SavedEntry>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            when (fields[0]) {
                "line" -> {
                    expression = fields.getOrNull(1)?.let { MathStringCodec.decode(it) } ?: MathString()
                    seed = fields.getOrNull(2)?.toLongOrNull() ?: 0L
                }
                "entry" -> decodeEntry(fields)?.let { entries.add(it) }
            }
        }
        return SavedSession(expression, entries, seed)
    }

    private fun decodeEntry(fields: List<String>): SavedEntry? {
        // entry, id, form, angle, units, places, complex, input, ans, the seed (not in older versions), then pairs of name and value
        if (fields.size < 9) return null
        val hasSeed = (fields.size - 9) % 2 == 1
        val first = if (hasSeed) 10 else 9
        if ((fields.size - first) % 2 != 0) return null
        return try {
            val variables = LinkedHashMap<String, MathString>()
            for (i in first until fields.size step 2) {
                variables[MathStringCodec.unquote(fields[i]) ?: return null] = MathStringCodec.decode(fields[i + 1]) ?: return null
            }
            val ans = if (fields[8] == NONE) null else MathStringCodec.decode(fields[8]) ?: return null
            SavedEntry(
                id = fields[1].toLong(),
                input = MathStringCodec.decode(fields[7]) ?: return null,
                form = ResultForm.valueOf(fields[2]),
                snapshot = CalculationSnapshot(
                    angleUnit = AngleUnit.valueOf(fields[3]),
                    unitSystem = UnitSystem.valueOf(fields[4]),
                    decimalPlaces = if (fields[5] == NONE) null else fields[5].toInt(),
                    ans = ans,
                    variables = variables,
                    complexNumbers = fields[6] == "1",
                    randomSeed = if (hasSeed) fields[9].toLong() else 0L
                )
            )
        } catch (e: IllegalArgumentException) {
            // An unknown enum name or a malformed number, e.g. from an older or newer version
            null
        }
    }
}
