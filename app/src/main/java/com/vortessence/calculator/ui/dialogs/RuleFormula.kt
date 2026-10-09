package com.vortessence.calculator.ui.dialogs

import com.vortessence.calculator.core.math.type.*

/**
 * A rule formula written in a small LaTeX-like notation, as a [MathString] that is drawn like
 * any other math (exponents raised, fractions as fractions):
 * - letters are variables, e the constant; sin, cos, tan, asin, acos, atan and ln followed by
 *   "(…)" are functions,
 * - `^x` or `^{…}` raises x or … to the power of the part before it,
 * - `\frac{a}{b}`, `\sqrt{x}`, `\sqrt[n]{x}`, `\log{a}{x}` (logₐ x), `|x|`,
 * - `\int{f}{x}` (∫ f dx), `\intab{a}{b}{f}{x}` (∫ₐᵇ f dx), `\eval{F}{a}{b}` ([F]ₐᵇ),
 * - `\text{…}` for words; + − = are operators, · ′ ± and "," are written as they are.
 */
internal fun ruleFormula(source: String): MathString = FormulaParser(source).parse()

private class FormulaParser(private val source: String) {
    private var pos = 0

    fun parse(): MathString = MathString(sequence(until = null))

    /** Nodes up to [until] (consumed) or the end. */
    private fun sequence(until: Char?): List<MathNode> {
        val nodes = mutableListOf<MathNode>()
        while (pos < source.length) {
            val c = source[pos]
            if (c == until) {
                pos++
                return nodes
            }
            when {
                c == ' ' -> pos++
                c == '^' -> {
                    pos++
                    val base = nodes.removeLastOrNull()?.let { MathString(listOf(it)) } ?: MathString()
                    nodes.add(ExponentNode(base, MathString(argument())))
                }
                c == '\\' -> nodes.add(command())
                c == '(' -> {
                    pos++
                    nodes.add(ParenthesesNode(MathString(sequence(until = ')'))))
                }
                c == '|' -> {
                    pos++
                    nodes.add(FunctionNode("abs", MathString(sequence(until = '|'))))
                }
                c.isLetter() -> nodes.add(word())
                c.isDigit() -> {
                    pos++
                    nodes.add(NumberNode(c.toString()))
                }
                c == '+' -> operator(nodes, MathOp.PLUS)
                c == '-' || c == '−' -> operator(nodes, MathOp.MINUS)
                c == '=' -> operator(nodes, MathOp.EQUALS)
                c == ',' -> {
                    pos++
                    nodes.add(TextNode(",   "))
                }
                c == '±' -> {
                    pos++
                    nodes.add(TextNode(" ± "))
                }
                else -> {
                    // · ′ and the like
                    pos++
                    nodes.add(TextNode(c.toString()))
                }
            }
        }
        return nodes
    }

    private fun operator(nodes: MutableList<MathNode>, op: MathOp) {
        pos++
        nodes.add(OperatorNode(op))
    }

    /** `{…}` or a single character, e.g. the exponent in x^2. */
    private fun argument(): List<MathNode> {
        if (pos < source.length && source[pos] == '{') {
            pos++
            return sequence(until = '}')
        }
        val start = pos
        if (pos < source.length && source[pos] == '\\') return listOf(command())
        pos++
        return FormulaParser(source.substring(start, pos)).sequence(until = null)
    }

    private fun group(): MathString = MathString(argument())

    private fun command(): MathNode {
        pos++ // backslash
        val start = pos
        while (pos < source.length && source[pos].isLetter()) pos++
        return when (val name = source.substring(start, pos)) {
            "frac" -> FractionNode(group(), group())
            "sqrt" -> {
                val degree = if (pos < source.length && source[pos] == '[') {
                    pos++
                    MathString(sequence(until = ']'))
                } else {
                    null
                }
                RadicalNode(degree, group())
            }
            "log" -> LogBaseNode(group(), group())
            "int" -> {
                val integrand = group()
                AntiderivativeNode(integrand, variableName(group()))
            }
            "intab" -> {
                val lower = group()
                val upper = group()
                val integrand = group()
                IntegralNode(lower, upper, integrand, variableName(group()))
            }
            "eval" -> {
                val content = group()
                val lower = group()
                EvaluationNode(content, lower, group())
            }
            "text" -> {
                val end = source.indexOf('}', pos + 1)
                val text = source.substring(pos + 1, end)
                pos = end + 1
                TextNode(text)
            }
            else -> error("Unknown command \\$name in $source")
        }
    }

    private fun variableName(string: MathString): String = (string.nodes.singleOrNull() as? VariableNode)?.name ?: "x"

    /** A function such as sin(…), or a single letter. */
    private fun word(): MathNode {
        for (name in FUNCTIONS) {
            if (source.startsWith("$name(", pos)) {
                pos += name.length + 1
                return FunctionNode(name, MathString(sequence(until = ')')))
            }
        }
        val c = source[pos++]
        return if (c == 'e') ConstantNode("e") else VariableNode(c.toString())
    }

    companion object {
        private val FUNCTIONS = listOf("asin", "acos", "atan", "sin", "cos", "tan", "ln")
    }
}
