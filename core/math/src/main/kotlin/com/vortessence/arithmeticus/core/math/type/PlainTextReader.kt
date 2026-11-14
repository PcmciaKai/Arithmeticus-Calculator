package com.vortessence.arithmeticus.core.math.type

import com.vortessence.arithmeticus.core.math.expressions.UnitRegistry

/**
 * Reads math written as linear plain text into a [MathString], as it is typed: what [MathText.toPlainText] writes ("sqrt(2)/3",
 * "x^(n+1)", "integral(x, x, 0, 1)", "[[1, 2], [3, 4]]", "5 km -> mi") and what other apps put on the clipboard ("2·3", "x²", "√2",
 * "|x − 1|"). Text that is no math (words, letters that are not names of anything) is refused rather than read as a product of
 * variables: [read] gives null for it.
 *
 * A unit is told from a variable as the app writes them: after a value and a space (3 m), or of two letters or more (5km), or as
 * the target of an arrow, or as part of a unit before it (kg*m); a single letter right after a number is a variable (3m).
 */
internal class PlainTextReader(private val text: String, private val decimalComma: Boolean) {

    private var i = 0

    private class Unreadable : RuntimeException()

    private fun fail(): Nothing = throw Unreadable()

    fun read(): MathString? = try {
        val result = sequence(stops = "", topLevel = true)
        if (i < text.length || result.isEmpty) null else result
    } catch (_: Unreadable) {
        null
    }

    private fun peek(offset: Int = 0): Char = text.getOrElse(i + offset) { '\u0000' }

    private fun at(word: String) = text.startsWith(word, i)

    private fun skipSpaces(): Boolean {
        val start = i
        while (peek() == ' ' || peek() == '\t' || peek() == ' ' || peek() == ' ' || peek() == '\r') i++
        return i > start
    }

    /** The nodes up to one of the characters [stops] (not taken) or the end. */
    private fun sequence(stops: String, topLevel: Boolean = false): MathString {
        val nodes = mutableListOf<MathNode>()
        var spaceBefore = false
        while (true) {
            spaceBefore = skipSpaces() || spaceBefore
            val c = peek()
            if (c == '\u0000' || c in stops) break
            val before = nodes.size
            when {
                c == '\n' -> { if (!topLevel) fail(); i++; nodes.add(LineBreakNode) }
                c.isDigit() || (c == '.' && peek(1).isDigit()) -> number(nodes)
                c == '(' -> { i++; val inner = sequence(")"); expect(')'); nodes.add(ParenthesesNode(inner)) }
                c == '[' -> nodes.add(matrix())
                c == '|' -> { i++; val inner = sequence("|"); expect('|'); nodes.add(FunctionNode("abs", inner)) }
                c == '√' -> { i++; skipSpaces(); nodes.add(RadicalNode(null, unwrapped(operand()))) }
                c == '^' || at("**") -> { i += if (c == '^') 1 else 2; nodes.add(ExponentNode(null, exponent())) }
                c == '²' || c == '³' -> { i++; nodes.add(ExponentNode(null, MathString(listOf(NumberNode(if (c == '²') "2" else "3"))))) }
                c == ',' || c == ';' -> { i++; nodes.add(SeparatorNode) }
                c == 'π' -> { i++; nodes.add(ConstantNode("π")) }
                c == '∞' -> { i++; nodes.add(ConstantNode("∞")) }
                c.isLetter() || c == '°' || c == 'µ' || c == 'Ω' || c == 'Å' -> word(nodes, spaceBefore)
                else -> nodes.add(OperatorNode(operator() ?: fail()))
            }
            if (nodes.size > before) spaceBefore = false
        }
        return MathString(withFractions(nodes))
    }

    private fun expect(c: Char) {
        if (peek() != c) fail()
        i++
    }

    /** One operand, for √ and exponents: a number, a name or a group, with the powers and ! that belong to it. */
    private fun operand(): MathString {
        val nodes = mutableListOf<MathNode>()
        val c = peek()
        when {
            c.isDigit() || c == '.' -> number(nodes, scientific = false)
            c == '(' -> { i++; val inner = sequence(")"); expect(')'); nodes.add(ParenthesesNode(inner)) }
            c == '|' -> { i++; val inner = sequence("|"); expect('|'); nodes.add(FunctionNode("abs", inner)) }
            c == 'π' -> { i++; nodes.add(ConstantNode("π")) }
            c == '∞' -> { i++; nodes.add(ConstantNode("∞")) }
            c.isLetter() -> word(nodes, spaceBefore = false, single = true)
            else -> fail()
        }
        return MathString(nodes)
    }

    /** What follows ^: an optional sign and one operand; parentheses around it are not kept (the exponent is raised). */
    private fun exponent(): MathString {
        skipSpaces()
        val sign = when (peek()) {
            '-', '−', '–' -> { i++; OperatorNode(MathOp.MINUS) }
            '+' -> { i++; OperatorNode(MathOp.PLUS) }
            else -> null
        }
        skipSpaces()
        val value = unwrapped(operand())
        return MathString(listOfNotNull(sign) + value.nodes)
    }

    private fun unwrapped(s: MathString): MathString = (s.nodes.singleOrNull() as? ParenthesesNode)?.inner ?: s

    /** Digits with a decimal point (or comma), and an exponent E5 or e−3 as ×10ⁿ. */
    private fun number(nodes: MutableList<MathNode>, scientific: Boolean = true) {
        val digits = StringBuilder()
        var hasPoint = false
        while (true) {
            val c = peek()
            when {
                c.isDigit() -> digits.append(c)
                c == '.' && !hasPoint && peek(1).isDigit() || c == '.' && !hasPoint && digits.isNotEmpty() && !peek(1).isLetter() -> { hasPoint = true; digits.append('.') }
                c == ',' && decimalComma && !hasPoint && peek(1).isDigit() -> { hasPoint = true; digits.append('.') }
                else -> break
            }
            i++
        }
        // 1.2.3 is no number
        if (peek() == '.' && (peek(1).isDigit() || hasPoint)) fail()
        // 0xFF, 0b1010, 0o17: a number in another base, with its prefix as typing makes it; 0b12 or 0x alone is none
        if (digits.toString() == "0" && peek() in "xXbBoO") {
            val base = NumberBase.entries.first { it.prefix == "0" + peek().lowercaseChar() }
            i++
            val baseDigits = StringBuilder()
            while (base.isDigit(peek()) || peek() == '_') { if (peek() != '_') baseDigits.append(peek().uppercaseChar()); i++ }
            if (baseDigits.isEmpty() || peek().isLetterOrDigit()) fail()
            nodes.add(NumberNode(base.prefix))
            nodes.add(NumberNode(baseDigits.toString()))
            return
        }
        nodes.add(NumberNode(digits.toString()))
        // 3.2E-4: an exponent only where digits follow (2e is 2·e)
        if (scientific && (peek() == 'E' || peek() == 'e')) {
            var j = 1
            val negative = peek(j) == '-' || peek(j) == '−'
            if (negative || peek(j) == '+') j++
            if (peek(j).isDigit()) {
                i += j
                val exponentDigits = StringBuilder()
                while (peek().isDigit()) { exponentDigits.append(peek()); i++ }
                val exponent = MathString(listOfNotNull(if (negative) OperatorNode(MathOp.MINUS) else null, NumberNode(exponentDigits.toString())))
                nodes.add(SciExponentNode(exponent))
            }
        }
    }

    /** [[1, 2], [3, 4]] or a single row [1, 2, 3], each entry anything up to its comma. */
    private fun matrix(): MathNode {
        expect('[')
        skipSpaces()
        val rows = mutableListOf<List<MathString>>()
        if (peek() == '[') {
            while (true) {
                skipSpaces()
                rows.add(row())
                skipSpaces()
                if (peek() == ',') { i++; continue }
                expect(']')
                break
            }
        } else {
            i-- // the row's own bracket is the matrix's
            rows.add(row())
        }
        val cols = rows.first().size
        if (rows.any { it.size != cols } || cols == 0) fail()
        return MatrixNode(rows.size, cols, rows.flatten())
    }

    private fun row(): List<MathString> {
        expect('[')
        val entries = mutableListOf<MathString>()
        while (true) {
            val entry = sequence(",;]")
            if (entry.isEmpty) fail()
            entries.add(entry)
            if (peek() == ']') { i++; return entries }
            // The text ended inside the row
            if (peek() == '\u0000') fail()
            i++
        }
    }

    /** The comma-separated arguments of a function whose "(" was taken; the ")" is taken too. */
    private fun arguments(): List<MathString> {
        val arguments = mutableListOf<MathString>()
        while (true) {
            arguments.add(sequence(",;)"))
            skipSpaces()
            if (peek() == ')') { i++; return arguments }
            // The text ended inside the parentheses
            if (peek() == '\u0000') fail()
            // ", " separates; a decimal comma was taken by the number already
            i++
        }
    }

    private fun joined(arguments: List<MathString>): MathString =
        MathString(arguments.flatMapIndexed { index, argument -> (if (index > 0) listOf(SeparatorNode) else emptyList()) + argument.nodes })

    /** A word: a function with its arguments, a constant, an operator word, a unit, or single-letter variables. */
    private fun word(nodes: MutableList<MathNode>, spaceBefore: Boolean, single: Boolean = false) {
        val start = i
        while (peek().isLetter()) i++
        val word = text.substring(start, i)
        // A function: its name and "("
        val afterName = i
        skipSpaces()
        // (Also the symbols of special functions, of one letter: Γ(5), W(1))
        if ((word.length > 1 || word in FunctionNames.symbols.values) && peek() == '(') {
            i++
            nodes.add(function(word, arguments()))
            return
        }
        i = afterName
        when (word) {
            "pi" -> { nodes.add(ConstantNode("π")); return }
            "inf", "infinity", "Infinity" -> { nodes.add(ConstantNode("∞")); return }
            "Ans", "ans", "ANS" -> { nodes.add(AnsNode); return }
            "mod" -> { nodes.add(OperatorNode(MathOp.MODULO)); return }
            "AND" -> { nodes.add(OperatorNode(MathOp.AND)); return }
            "OR" -> { nodes.add(OperatorNode(MathOp.OR)); return }
            "XOR" -> { nodes.add(OperatorNode(MathOp.XOR)); return }
        }
        i = start
        unit(nodes, spaceBefore)?.let { nodes.add(it); return }
        i = afterName
        if (word.isEmpty()) fail() // a ° or µ that is no unit
        // Single-letter variables, written next to each other (xy is x·y); longer runs are words, not math
        if (word.length > MAX_LETTERS_IN_A_ROW || (single && word.length > 1)) fail()
        if (FunctionNames.endingWord(word)?.length == word.length) fail() // "sin" without its argument
        for (letter in word) {
            nodes.add(
                when (letter) {
                    'e' -> ConstantNode("e")
                    'i' -> ConstantNode("i")
                    else -> VariableNode(letter.toString())
                }
            )
        }
    }

    /** The unit at the position, if one is meant there (see the class); its symbol is taken. */
    private fun unit(nodes: List<MathNode>, spaceBefore: Boolean): UnitNode? {
        val previous = nodes.lastOrNull()
        val beforeOperator = nodes.getOrNull(nodes.size - 2)
        val afterArrow = (previous as? OperatorNode)?.op == MathOp.CONVERT
        val inUnit = previous is UnitNode ||
            (previous is OperatorNode && (previous.op == MathOp.MULTIPLY || previous.op == MathOp.DIVIDE) && beforeOperator is UnitNode)
        val afterValue = previous is NumberNode || previous is SciExponentNode || previous is ParenthesesNode
        for (symbol in UnitRegistry.symbolsLongestFirst) {
            if (!at(symbol)) continue
            val next = peek(symbol.length)
            if (next.isLetter() || next.isDigit() || next == '(') continue
            // m/s^2 is m/s²: the power belongs to the last unit, so m/s is not one unit here
            if ('/' in symbol && (next == '^' || next == '²' || next == '³')) continue
            val letters = symbol.count { it.isLetter() }
            // A single letter right after a number is a variable (3m); with a space, more letters or a ° it is a unit (3 m, 5km, 20°C)
            val meant = afterArrow || inUnit || (afterValue && (spaceBefore || letters != 1 || symbol.first() == '°'))
            if (!meant) continue
            i += symbol.length
            return UnitNode(symbol)
        }
        return null
    }

    private fun function(name: String, arguments: List<MathString>): MathNode {
        fun variableName(s: MathString): String = (s.nodes.singleOrNull() as? VariableNode)?.name ?: fail()
        return when (name) {
            "sqrt" -> RadicalNode(null, arguments.singleOrNull() ?: fail())
            "abs" -> FunctionNode("abs", arguments.singleOrNull() ?: fail())
            "log" -> if (arguments.size == 2) LogBaseNode(arguments[1], arguments[0]) else FunctionNode("log", arguments.single())
            "integral" -> when (arguments.size) {
                2 -> AntiderivativeNode(arguments[0], variableName(arguments[1]))
                4 -> IntegralNode(arguments[2], arguments[3], arguments[0], variableName(arguments[1]))
                else -> fail()
            }
            "derivative" -> when (arguments.size) {
                2 -> DerivativeNode(arguments[0], null, variableName(arguments[1]))
                3 -> DerivativeNode(arguments[0], arguments[2], variableName(arguments[1]))
                else -> fail()
            }
            "limit" -> if (arguments.size == 2 && arguments[1].nodes.any { (it as? OperatorNode)?.op == MathOp.CONVERT }) {
                LimitNode(arguments[1], arguments[0])
            } else fail()
            "sum", "product" -> if (arguments.size == 3 && arguments[1].nodes.any { (it as? OperatorNode)?.op == MathOp.EQUALS }) {
                BigOperatorNode(if (name == "sum") BigOperator.SUM else BigOperator.PRODUCT, arguments[1], arguments[2], arguments[0])
            } else {
                // sum(1, 2, 3): the function of a list
                FunctionNode(if (name == "product") "prod" else name, joined(arguments))
            }
            else -> {
                // Any function the keypad has, by its name or symbol (Γ for gamma)
                val known = FunctionNames.endingWord(name)?.takeIf { it.length == name.length }
                    ?: FunctionNames.symbols.entries.firstOrNull { it.value == name }?.key
                    ?: fail()
                FunctionNode(FunctionNames.written(known), joined(arguments))
            }
        }
    }

    private fun operator(): MathOp? {
        // Not equal has no key: "x != 2" is not x! = 2
        if (at("!=") || at("<>") || peek() == '≠') return null
        val (op, length) = when {
            at(":=") -> MathOp.DEFINE to 2
            at("->") -> MathOp.CONVERT to 2
            at("<=") -> MathOp.LESS_EQUAL to 2
            at(">=") -> MathOp.GREATER_EQUAL to 2
            at("<<") -> MathOp.SHIFT_LEFT to 2
            at(">>") -> MathOp.SHIFT_RIGHT to 2
            at("==") -> MathOp.EQUALS to 2
            else -> when (peek()) {
                '+' -> MathOp.PLUS
                '-', '−', '–' -> MathOp.MINUS
                '*', '×', '·', '⋅', '∙' -> MathOp.MULTIPLY
                '/', '÷', '∕' -> MathOp.DIVIDE
                '=' -> MathOp.EQUALS
                '<' -> MathOp.LESS
                '>' -> MathOp.GREATER
                '≤' -> MathOp.LESS_EQUAL
                '≥' -> MathOp.GREATER_EQUAL
                '→' -> MathOp.CONVERT
                '!' -> MathOp.FACTORIAL
                '%' -> MathOp.PERCENT
                else -> return null
            } to 1
        }
        i += length
        return op
    }

    /**
     * a/b as a fraction where it reads as one: a and b each a single operand (a number, a name, a group in parentheses, with its
     * powers), and nothing multiplied with a before it. 2x/3 stays as it is typed, (2x)/3 is the fraction 2x over 3.
     */
    private fun withFractions(nodes: List<MathNode>): List<MathNode> {
        val result = nodes.toMutableList()
        var k = 0
        while (k < result.size) {
            val node = result[k]
            if ((node as? OperatorNode)?.op != MathOp.DIVIDE) { k++; continue }
            val leftStart = operandStart(result, k)
            val rightEnd = operandEnd(result, k + 1)
            val beforeLeft = result.getOrNull(leftStart - 1)
            val standsAlone = beforeLeft == null || beforeLeft is LineBreakNode || beforeLeft is SeparatorNode ||
                (beforeLeft is OperatorNode && !beforeLeft.op.isPostfix && beforeLeft.op != MathOp.DIVIDE)
            if (leftStart < 0 || rightEnd < 0 || !standsAlone) { k++; continue }
            val numerator = group(result.subList(leftStart, k))
            val denominator = group(result.subList(k + 1, rightEnd))
            repeat(rightEnd - leftStart) { result.removeAt(leftStart) }
            result.add(leftStart, FractionNode(numerator, denominator))
            k = leftStart + 1
        }
        return result
    }

    private fun group(nodes: List<MathNode>): MathString = unwrapped(MathString(nodes.toList()))

    private fun isPrimary(node: MathNode): Boolean = node !is OperatorNode && node !is ExponentNode && node !is SciExponentNode &&
        node !is LineBreakNode && node !is SeparatorNode && node !is UnitNode

    /** Where the operand that ends right before [end] starts, or −1 if there is none. */
    private fun operandStart(nodes: List<MathNode>, end: Int): Int {
        var j = end - 1
        while (j >= 0 && (nodes[j] is ExponentNode || nodes[j] is SciExponentNode || (nodes[j] as? OperatorNode)?.op?.isPostfix == true)) j--
        if (j < 0 || !isPrimary(nodes[j])) return -1
        if (nodes[j] is NumberNode) while (j > 0 && nodes[j - 1] is NumberNode) j--
        return j
    }

    /** Where the operand that starts at [start] ends (exclusive), or −1 if there is none. */
    private fun operandEnd(nodes: List<MathNode>, start: Int): Int {
        var j = start
        if (j >= nodes.size || !isPrimary(nodes[j])) return -1
        j++
        while (j < nodes.size && (nodes[j] is ExponentNode || nodes[j] is SciExponentNode || (nodes[j] as? OperatorNode)?.op?.isPostfix == true)) j++
        // 1/2x is (1/2)·x as the app writes it; a name right after makes it no single operand
        if (j < nodes.size && isPrimary(nodes[j]) && nodes[start] !is NumberNode) return -1
        return j
    }

    private companion object {
        /** Letters next to each other that are still read as variables (xyz); more are a word. */
        const val MAX_LETTERS_IN_A_ROW = 3
    }
}
