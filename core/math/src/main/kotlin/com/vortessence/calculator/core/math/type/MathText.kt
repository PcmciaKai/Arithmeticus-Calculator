package com.vortessence.calculator.core.math.type

/**
 * Conversion between [MathString]s and linear plain text, for copying to and pasting from
 * the clipboard. The text is meant for other apps and people: ASCII operators, "sqrt(2)/3",
 * "x^(n+1)", with parentheses wherever the linear form would otherwise be ambiguous.
 */
object MathText {

    /** [string] as linear plain text, e.g. "(x + 1)/2 + sqrt(3)". */
    fun toPlainText(string: MathString): String {
        val nodes = string.nodes
        val text = StringBuilder()
        for ((i, node) in nodes.withIndex()) {
            text.append(writeNode(node, nodes.getOrNull(i - 1), nodes.getOrNull(i + 1)))
        }
        return text.toString()
    }

    /**
     * [text] as a [MathString], or null if it cannot be read. So far only plain numbers are
     * understood, e.g. "-12.5", "1 234" or "3.2e-4".
     */
    fun fromPlainText(text: String): MathString? {
        val match = NUMBER.matchEntire(text.trim()) ?: return null
        val (sign, mantissa, exponent) = match.destructured
        val digits = numberDigits(mantissa.filterNot { it == ' ' || it == '_' || it == ' ' || it == ' ' }) ?: return null
        val nodes = mutableListOf<MathNode>()
        if (sign.isNotEmpty()) nodes.add(OperatorNode(MathOp.MINUS))
        digits.forEach { nodes.add(NumberNode(it.toString())) }
        if (exponent.isNotEmpty()) {
            val exponentNodes = mutableListOf<MathNode>()
            if (exponent[0] == '-' || exponent[0] == '−') exponentNodes.add(OperatorNode(MathOp.MINUS))
            exponent.trimStart('+', '-', '−').forEach { exponentNodes.add(NumberNode(it.toString())) }
            nodes.add(SciExponentNode(MathString(exponentNodes)))
        }
        return MathString(nodes)
    }

    /**
     * The digits and decimal point of [mantissa]. Commas group thousands ("1,234.5"), or else
     * a single comma is a decimal comma ("3,5").
     */
    private fun numberDigits(mantissa: String): String? {
        val digits = when {
            ',' !in mantissa -> mantissa
            mantissa.matches(Regex("""\d{1,3}(,\d{3})+(\.\d*)?""")) -> mantissa.replace(",", "")
            mantissa.count { it == ',' } == 1 && '.' !in mantissa -> mantissa.replace(',', '.')
            else -> return null
        }
        return if (digits.count { it == '.' } > 1) null else digits
    }

    /**
     * A copy of [string] as if it had been typed: numbers split into one node per character,
     * so the cursor can be placed between their digits (results hold whole numbers in one node).
     */
    fun splitNumbers(string: MathString): MathString = MathString(string.nodes.flatMap { node ->
        when {
            node is NumberNode && node.text.length > 1 -> node.text.map { NumberNode(it.toString(), node.approximate) }
            node.subStrings.isEmpty() -> listOf(node)
            else -> listOf(node.subStrings.foldIndexed(node) { i, acc, child -> acc.replaceSubString(i, splitNumbers(child)) })
        }
    })

    /** Optional sign, digits (grouped by spaces, commas or underscores) and an optional exponent. */
    private val NUMBER = Regex("""([-−]?)\+?(\d[\d ,_  ]*(?:\.\d*)?|\.\d+)(?:[eE]([+\-−]?\d+))?""")

    private fun writeNode(node: MathNode, previous: MathNode?, next: MathNode?): String = when (node) {
        is NumberNode -> node.text
        is OperatorNode -> writeOperator(node.op, previous)
        is VariableNode -> node.name
        is ConstantNode -> node.name
        is AnsNode -> "Ans"
        is UnitNode -> if (isValue(previous)) " ${node.symbol}" else node.symbol
        is TextNode -> node.text
        is LineBreakNode -> "\n"
        is SeparatorNode -> ", "
        is FractionNode -> {
            val fraction = "${group(node.numerator)}/${group(node.denominator)}"
            // Inside a product or under a power, "1/2x" or "1/2^2" would read differently
            if (isValue(previous) || isValue(next) || next.isPostfix || next is ExponentNode || previous.isOp(MathOp.DIVIDE)) "($fraction)" else fraction
        }
        is PolarNode -> "(${group(node.magnitude)}∠${group(node.angle)})"
        is MixedFractionNode -> "(${toPlainText(node.whole)} + ${group(node.numerator)}/${group(node.denominator)})"
        is ExponentNode -> (node.base?.let { group(it) } ?: "") + "^" + group(node.exponent)
        is SciExponentNode -> {
            val exponent = toPlainText(node.exponent)
            when {
                previous !is NumberNode -> "10^" + group(node.exponent)
                exponent.matches(Regex("-?\\d+")) -> "E$exponent"
                else -> "*10^" + group(node.exponent)
            }
        }
        is RadicalNode -> if (node.degree == null) {
            "sqrt(${toPlainText(node.radicand)})"
        } else {
            "${group(node.radicand)}^(1/${group(node.degree)})"
        }
        is ParenthesesNode -> "(${toPlainText(node.inner)})"
        is HighlightNode -> "(${toPlainText(node.content)})"
        is EvaluationNode -> "evaluate(${toPlainText(node.content)}, ${node.variable}, ${toPlainText(node.lower)}, ${toPlainText(node.upper)})"
        is FunctionNode -> "${node.name}(${toPlainText(node.argument)})"
        is LogBaseNode -> "log(${toPlainText(node.argument)}, ${toPlainText(node.base)})"
        is IntegralNode -> "integral(${toPlainText(node.integrand)}, ${node.variable}, " +
            "${toPlainText(node.lowerBound)}, ${toPlainText(node.upperBound)})"
        is AntiderivativeNode -> "integral(${toPlainText(node.integrand)}, ${node.variable})"
        is DerivativeNode -> "derivative(${toPlainText(node.body)}, ${node.variable}" +
            (node.point?.let { ", ${toPlainText(it)}" } ?: "") + ")"
        is BigOperatorNode -> {
            val name = if (node.operator == BigOperator.SUM) "sum" else "product"
            "$name(${toPlainText(node.body)}, ${toPlainText(node.lower)}, ${toPlainText(node.upper)})"
        }
        is LimitNode -> "limit(${toPlainText(node.body)}, ${toPlainText(node.approach)})"
        is MatrixNode -> (0 until node.rows).joinToString(", ", "[", "]") { row ->
            (0 until node.cols).joinToString(", ", "[", "]") { col -> toPlainText(node.elements[row * node.cols + col]) }
        }
    }

    private fun writeOperator(op: MathOp, previous: MathNode?): String = when (op) {
        // A sign: at the start, or right after another operator
        MathOp.MINUS -> if (isValue(previous) || previous.isPostfix) " - " else "-"
        MathOp.PLUS -> if (isValue(previous) || previous.isPostfix) " + " else "+"
        MathOp.MULTIPLY -> "*"
        MathOp.DIVIDE -> "/"
        MathOp.EQUALS -> " = "
        MathOp.FACTORIAL -> "!"
        MathOp.PERCENT -> "%"
        MathOp.MODULO -> " mod "
        MathOp.CONVERT -> " -> "
        MathOp.DEFINE -> " := "
        MathOp.LESS -> " < "
        MathOp.GREATER -> " > "
        MathOp.LESS_EQUAL -> " <= "
        MathOp.GREATER_EQUAL -> " >= "
    }

    /** [string] as plain text, in parentheses unless it is a single number, name or bracket. */
    private fun group(string: MathString): String {
        val text = toPlainText(string)
        return if (isAtomic(string)) text else "($text)"
    }

    /**
     * Whether the base of a power ([ExponentNode.base]) is a group of several parts, e.g. 2x in
     * (2x)², which must be shown in parentheses: without them it would look like 2x², where
     * only x is squared. A unit (m², km²) needs none.
     */
    fun isGroupedBase(base: MathString): Boolean =
        base.isNotEmpty && !isAtomic(base) && !base.nodes.all { it is UnitNode }

    private fun isAtomic(string: MathString): Boolean {
        val nodes = string.nodes
        if (nodes.isEmpty()) return false
        if (nodes.all { it is NumberNode }) return true
        val single = nodes.singleOrNull() ?: return false
        return single is VariableNode || single is ConstantNode || single is AnsNode || single is ParenthesesNode ||
            single is FunctionNode || single is LogBaseNode || single is MatrixNode
    }

    /** Whether [node] is (the end of) an operand, so a node right after it would multiply it. */
    private fun isValue(node: MathNode?): Boolean = node != null && node !is OperatorNode && node !is LineBreakNode && node !is TextNode && node !is SeparatorNode

    private val MathNode?.isPostfix: Boolean get() = this is OperatorNode && op.isPostfix

    private fun MathNode?.isOp(op: MathOp): Boolean = this is OperatorNode && this.op == op
}
