package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.type.*

/**
 * Test input as short text, e.g. "[x^2 - 4;x + 1] > 0": [a;b] is a fraction, ^2 or ^{…} an exponent, sqrt(…) a root,
 * abs(…) and the like are functions, "pi", "inf" and "e" constants, "|" starts a new line, <= and >= are ≤ and ≥,
 * D{…} a derivative, I{…} an antiderivative, I[a,b]{…} an integral, L[x->a]{…} a limit.
 */
internal object TestInput {

    fun parse(text: String): MathString = Reader(text).string("")

    private class Reader(val s: String) {
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        fun peek2() = if (i + 1 < s.length) s[i + 1] else '\u0000'

        fun string(stop: String): MathString {
            val nodes = mutableListOf<MathNode>()
            while (i < s.length && peek() !in stop) {
                val c = peek()
                when {
                    c == ' ' -> i++
                    c.isDigit() || c == '.' -> { val st = i; while (peek().isDigit() || peek() == '.') i++; nodes.add(NumberNode(s.substring(st, i))) }
                    c == '+' -> { i++; nodes.add(OperatorNode(MathOp.PLUS)) }
                    c == '-' && !s.startsWith("->", i) -> { i++; nodes.add(OperatorNode(MathOp.MINUS)) }
                    c == '*' -> { i++; nodes.add(OperatorNode(MathOp.MULTIPLY)) }
                    c == '/' -> { i++; nodes.add(OperatorNode(MathOp.DIVIDE)) }
                    c == '=' -> { i++; nodes.add(OperatorNode(MathOp.EQUALS)) }
                    s.startsWith("<=", i) -> { i += 2; nodes.add(OperatorNode(MathOp.LESS_EQUAL)) }
                    s.startsWith(">=", i) -> { i += 2; nodes.add(OperatorNode(MathOp.GREATER_EQUAL)) }
                    c == '<' -> { i++; nodes.add(OperatorNode(MathOp.LESS)) }
                    c == '>' -> { i++; nodes.add(OperatorNode(MathOp.GREATER)) }
                    c == '%' -> { i++; nodes.add(OperatorNode(MathOp.PERCENT)) }
                    c == '!' -> { i++; nodes.add(OperatorNode(MathOp.FACTORIAL)) }
                    c == '|' -> { i++; nodes.add(LineBreakNode) }
                    c == '(' -> { i++; val inner = string(")"); i++; nodes.add(ParenthesesNode(inner)) }
                    c == '[' -> { i++; val a = string(";"); i++; val b = string("]"); i++; nodes.add(FractionNode(a, b)) }
                    s.startsWith("M[", i) -> {
                        // A matrix: M[1 2/3 4], rows separated by /, entries by spaces
                        val end = s.indexOf(']', i)
                        val rows = s.substring(i + 2, end).split('/').map { row -> row.trim().split(Regex("\\s+")).map { Reader(it).string("") } }
                        i = end + 1
                        nodes.add(MatrixNode(rows.size, rows.first().size, rows.flatten()))
                    }
                    c == '^' && (s.startsWith("^+", i) || s.startsWith("^-", i)) && nodes.lastOrNull() is NumberNode -> {
                        // A superscript sign after a number, as the power key makes it: the number is the base
                        val base = ArrayList<MathNode>()
                        while (nodes.lastOrNull() is NumberNode) base.add(0, nodes.removeAt(nodes.lastIndex))
                        val sign = if (s[i + 1] == '+') MathOp.PLUS else MathOp.MINUS
                        i += 2
                        nodes.add(ExponentNode(MathString(base), MathString(listOf(OperatorNode(sign)))))
                    }
                    c == '^' -> {
                        i++
                        val e = if (peek() == '{') { i++; string("}").also { i++ } }
                        else if (peek() == '-' && peek2().isDigit()) { i++; val st = i; while (peek().isDigit()) i++; MathString(listOf(OperatorNode(MathOp.MINUS), NumberNode(s.substring(st, i)))) }
                        else if (peek() == '+' || (peek() == '-' && !s.startsWith("->", i))) MathString(listOf(OperatorNode(if (peek() == '+') MathOp.PLUS else MathOp.MINUS))).also { i++ }
                        else MathString(listOf(single()))
                        nodes.add(ExponentNode(null, e))
                    }
                    s.startsWith("D{", i) -> { i += 2; val b = string("}"); i++; nodes.add(DerivativeNode(b)) }
                    s.startsWith("I{", i) -> { i += 2; val b = string("}"); i++; nodes.add(AntiderivativeNode(b)) }
                    s.startsWith("I[", i) -> { i += 2; val a = string(","); i++; val b = string("]"); i += 2; val body = string("}"); i++; nodes.add(IntegralNode(a, b, body)) }
                    s.startsWith("L[", i) -> { i += 2; val a = string("]"); i += 2; val body = string("}"); i++; nodes.add(LimitNode(a, body)) }
                    s.startsWith("->", i) -> { i += 2; nodes.add(OperatorNode(MathOp.CONVERT)) }
                    s.startsWith("inf", i) -> { i += 3; nodes.add(ConstantNode("∞")) }
                    s.startsWith("pi", i) -> { i += 2; nodes.add(ConstantNode("π")) }
                    c.isLetter() -> {
                        val st = i; while (peek().isLetter()) i++
                        val word = s.substring(st, i)
                        if (peek() == '(' && word.length > 1) {
                            i++; val inner = string(")"); i++
                            nodes.add(if (word == "sqrt") RadicalNode(null, inner) else FunctionNode(word, inner))
                        } else word.forEach { ch -> nodes.add(if (ch == 'e') ConstantNode("e") else VariableNode(ch.toString())) }
                    }
                    else -> error("bad char $c in $s")
                }
            }
            return MathString(nodes)
        }

        fun single(): MathNode { val c = peek(); i++; return if (c.isDigit()) NumberNode(c.toString()) else if (c == 'e') ConstantNode("e") else VariableNode(c.toString()) }
    }
}
