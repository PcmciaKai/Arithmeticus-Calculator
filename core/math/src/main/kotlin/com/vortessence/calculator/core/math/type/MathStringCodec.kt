package com.vortessence.calculator.core.math.type

/**
 * Lossless text form of a [MathString], for saving it and reading it back as it was. Unlike
 * [MathText], which writes for other apps and people, every node and slot survives, so a saved
 * line with matrices, integrals, ... comes back exactly.
 *
 * A string is "[" followed by its nodes and "]"; a node is its name and its arguments in
 * parentheses, separated by commas: `N("12",0)`, `Frac([N("1",0)],[N("2",0)])`. Text arguments
 * are quoted (see [quote]), a missing slot is `~`. The text never contains a raw line break or
 * tab, so encoded strings can be stored one per field of a line.
 */
object MathStringCodec {

    fun encode(string: MathString): String =
        string.nodes.joinToString("", "[", "]") { encodeNode(it) }

    /** The string [text] was encoded from, or null if [text] is not valid. */
    fun decode(text: String): MathString? = try {
        val reader = Reader(text)
        reader.string().takeIf { reader.atEnd }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** [text] in quotes, with backslash, quote and line breaks escaped; it contains no whitespace other than spaces. */
    fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    /** The text [quoted] was made from by [quote], or null if it is not valid. */
    fun unquote(quoted: String): String? = try {
        val reader = Reader(quoted)
        reader.text().takeIf { reader.atEnd }
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun encodeNode(node: MathNode): String = when (node) {
        is NumberNode -> call("N", quote(node.text), flag(node.approximate))
        is OperatorNode -> call("Op", quote(node.op.name))
        is VariableNode -> call("V", quote(node.name))
        is ConstantNode -> call("C", quote(node.name))
        is AnsNode -> call("Ans")
        is UnitNode -> call("U", quote(node.symbol))
        is FractionNode -> call("Frac", encode(node.numerator), encode(node.denominator))
        is MixedFractionNode -> call("Mixed", encode(node.whole), encode(node.numerator), encode(node.denominator))
        is PolarNode -> call("Polar", encode(node.magnitude), encode(node.angle))
        is ExponentNode -> call("Pow", optional(node.base), encode(node.exponent))
        is RadicalNode -> call("Rad", optional(node.degree), encode(node.radicand))
        is ParenthesesNode -> call("Paren", encode(node.inner))
        is HighlightNode -> call("Hl", encode(node.content), node.colorIndex.toString(), flag(node.boxed))
        is EvaluationNode -> call("Eval", encode(node.content), encode(node.lower), encode(node.upper), quote(node.variable))
        is SciExponentNode -> call("Sci", encode(node.exponent))
        is TextNode -> call("T", quote(node.text))
        is LineBreakNode -> call("Br")
        is SeparatorNode -> call("Sep")
        is LogBaseNode -> call("LogB", encode(node.base), encode(node.argument))
        is FunctionNode -> call("Fn", quote(node.name), encode(node.argument))
        is IntegralNode -> call("Int", encode(node.lowerBound), encode(node.upperBound), encode(node.integrand), quote(node.variable))
        is AntiderivativeNode -> call("Anti", encode(node.integrand), quote(node.variable))
        is DerivativeNode -> call("Der", encode(node.body), optional(node.point), quote(node.variable))
        is BigOperatorNode -> call("Big", quote(node.operator.name), encode(node.lower), encode(node.upper), encode(node.body))
        is LimitNode -> call("Lim", encode(node.approach), encode(node.body))
        is MatrixNode -> call("Mat", node.rows.toString(), node.cols.toString(), *node.elements.map { encode(it) }.toTypedArray())
    }

    private fun call(name: String, vararg arguments: String) = arguments.joinToString(",", "$name(", ")")

    private fun flag(value: Boolean) = if (value) "1" else "0"

    private fun optional(string: MathString?) = if (string == null) "~" else encode(string)

    private fun malformed(): Nothing = throw IllegalArgumentException("Malformed math text")

    private class Reader(private val source: String) {
        private var pos = 0

        val atEnd: Boolean get() = pos == source.length

        fun string(): MathString {
            expect('[')
            val nodes = mutableListOf<MathNode>()
            while (peek() != ']') nodes.add(node())
            expect(']')
            return MathString(nodes)
        }

        /** A quoted text argument. */
        fun text(): String {
            expect('"')
            val text = StringBuilder()
            while (peek() != '"') {
                val c = next()
                if (c != '\\') {
                    text.append(c)
                    continue
                }
                text.append(
                    when (next()) {
                        '\\' -> '\\'
                        '"' -> '"'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> malformed()
                    }
                )
            }
            expect('"')
            return text.toString()
        }

        private fun node(): MathNode {
            val start = pos
            while (pos < source.length && source[pos].isLetter()) pos++
            val name = source.substring(start, pos)
            expect('(')
            val node: MathNode = when (name) {
                "N" -> NumberNode(argText(), argFlag())
                "Op" -> OperatorNode(MathOp.valueOf(argText()))
                "V" -> VariableNode(argText())
                "C" -> ConstantNode(argText())
                "Ans" -> AnsNode
                "U" -> UnitNode(argText())
                "Frac" -> FractionNode(argString(), argString())
                "Mixed" -> MixedFractionNode(argString(), argString(), argString())
                "Polar" -> PolarNode(argString(), argString())
                "Pow" -> ExponentNode(argOptional(), argString())
                "Rad" -> RadicalNode(argOptional(), argString())
                "Paren" -> ParenthesesNode(argString())
                "Hl" -> HighlightNode(argString(), argInt(), argFlag())
                "Eval" -> EvaluationNode(argString(), argString(), argString(), argText())
                "Sci" -> SciExponentNode(argString())
                "T" -> TextNode(argText())
                "Br" -> LineBreakNode
                "Sep" -> SeparatorNode
                "LogB" -> LogBaseNode(argString(), argString())
                "Fn" -> FunctionNode(argText(), argString())
                "Int" -> IntegralNode(argString(), argString(), argString(), argText())
                "Anti" -> AntiderivativeNode(argString(), argText())
                "Der" -> DerivativeNode(argString(), argOptional(), argText())
                "Big" -> BigOperatorNode(BigOperator.valueOf(argText()), argString(), argString(), argString())
                "Lim" -> LimitNode(argString(), argString())
                "Mat" -> {
                    val rows = argInt()
                    val cols = argInt()
                    MatrixNode(rows, cols, List(rows * cols) { argString() })
                }
                else -> malformed()
            }
            expect(')')
            return node
        }

        private fun argText(): String = argument { text() }
        private fun argString(): MathString = argument { string() }
        private fun argOptional(): MathString? = argument { if (peek() == '~') { pos++; null } else string() }
        private fun argFlag(): Boolean = when (argument { next() }) {
            '1' -> true
            '0' -> false
            else -> malformed()
        }
        private fun argInt(): Int = argument {
            val start = pos
            while (pos < source.length && (source[pos].isDigit() || source[pos] == '-')) pos++
            source.substring(start, pos).toIntOrNull() ?: malformed()
        }

        /** Reads one argument; every argument but the first follows a comma. */
        private fun <T> argument(read: () -> T): T {
            if (pos > 0 && source[pos - 1] != '(') expect(',')
            return read()
        }

        private fun peek(): Char = if (pos < source.length) source[pos] else malformed()

        private fun next(): Char = peek().also { pos++ }

        private fun expect(c: Char) {
            if (next() != c) malformed()
        }
    }
}
