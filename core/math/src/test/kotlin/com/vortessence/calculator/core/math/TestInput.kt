package com.vortessence.calculator.core.math

import com.vortessence.calculator.core.math.type.*

/**
 * Test input as short text, e.g. "[x^2 - 4;x + 1] > 0": [a;b] is a fraction, ^2 or ^{…} an exponent, sqrt(…) a root,
 * abs(…) and the like are functions, "pi" and "e" constants, "|" starts a new line, <= and >= are ≤ and ≥.
 */
internal object TestInput {

    fun parse(text: String): MathString = Reader(text).string("")

    private class Reader(val s: String) {
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'

        fun string(stop: String): MathString {
            val nodes = mutableListOf<MathNode>()
            while (i < s.length && peek() !in stop) {
                val c = peek()
                when {
                    c == ' ' -> i++
                    c.isDigit() || c == '.' -> { val st = i; while (peek().isDigit() || peek() == '.') i++; nodes.add(NumberNode(s.substring(st, i))) }
                    c == '+' -> { i++; nodes.add(OperatorNode(MathOp.PLUS)) }
                    c == '-' -> { i++; nodes.add(OperatorNode(MathOp.MINUS)) }
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
                    c == '^' -> {
                        i++
                        val e = if (peek() == '{') { i++; string("}").also { i++ } } else MathString(listOf(single()))
                        nodes.add(ExponentNode(null, e))
                    }
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
