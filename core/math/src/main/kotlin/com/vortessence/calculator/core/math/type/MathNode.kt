package com.vortessence.calculator.core.math.type

sealed class MathNode {
    abstract fun copy(): MathNode
    abstract override fun toString(): String
    open val subStrings: List<MathString> get() = emptyList()
    open fun replaceSubString(index: Int, newString: MathString): MathNode = this
}

enum class MathOp(val symbol: String, val precedence: Int) {
    PLUS("+", 1),
    MINUS("−", 1),
    MULTIPLY("×", 2),
    DIVIDE("÷", 2),
    EQUALS("=", 0),
    FACTORIAL("!", 3),
    /** Unit conversion, e.g. "5 km → mi". Binds weakest of all operators. */
    CONVERT("→", -1),
    /** Definition of a variable, "a := 2x²". Only valid right after the variable at the start of a line. */
    DEFINE(":=", -2)
}

data class NumberNode(val text: String) : MathNode() {
    override fun copy(): MathNode = NumberNode(text)
    override fun toString(): String = text
}

data class OperatorNode(val op: MathOp) : MathNode() {
    override fun copy(): MathNode = OperatorNode(op)
    override fun toString(): String = if (op == MathOp.FACTORIAL) "!" else " ${op.symbol} "
}

data class VariableNode(val name: String) : MathNode() {
    override fun copy(): MathNode = VariableNode(name)
    override fun toString(): String = name
}

data class ConstantNode(val name: String) : MathNode() {
    override fun copy(): MathNode = ConstantNode(name)
    override fun toString(): String = name
}

/**
 * "Ans": the most recent result, looked up when the expression is calculated (see
 * [com.vortessence.calculator.core.math.context.MathContext.ans]).
 */
data object AnsNode : MathNode() {
    override fun copy(): MathNode = this
    override fun toString(): String = "Ans"
}

data class UnitNode(val symbol: String) : MathNode() {
    override fun copy(): MathNode = UnitNode(symbol)
    override fun toString(): String = symbol
}

data class FractionNode(
    val numerator: MathString = MathString(),
    val denominator: MathString = MathString()
) : MathNode() {
    override val subStrings: List<MathString> get() = listOf(numerator, denominator)
    override fun replaceSubString(index: Int, newString: MathString): MathNode {
        return if (index == 0) copy(numerator = newString) else if (index == 1) copy(denominator = newString) else this
    }
    override fun copy(): MathNode = FractionNode(numerator.copy(), denominator.copy())
    override fun toString(): String = "($numerator)/($denominator)"
}

data class ExponentNode(
    val base: MathString? = null,
    val exponent: MathString = MathString()
) : MathNode() {
    override val subStrings: List<MathString> get() = if (base != null) listOf(base, exponent) else listOf(exponent)
    override fun replaceSubString(index: Int, newString: MathString): MathNode {
        return if (base != null) {
            if (index == 0) copy(base = newString) else if (index == 1) copy(exponent = newString) else this
        } else {
            if (index == 0) copy(exponent = newString) else this
        }
    }
    override fun copy(): MathNode = ExponentNode(base?.copy(), exponent.copy())
    override fun toString(): String = if (base != null) "($base)^($exponent)" else "^($exponent)"
}

data class RadicalNode(
    val degree: MathString? = null,
    val radicand: MathString = MathString()
) : MathNode() {
    override val subStrings: List<MathString> get() = if (degree != null) listOf(degree, radicand) else listOf(radicand)
    override fun replaceSubString(index: Int, newString: MathString): MathNode {
        return if (degree != null) {
            if (index == 0) copy(degree = newString) else if (index == 1) copy(radicand = newString) else this
        } else {
            if (index == 0) copy(radicand = newString) else this
        }
    }
    override fun copy(): MathNode = RadicalNode(degree?.copy(), radicand.copy())
    override fun toString(): String = if (degree != null) "root[$degree]($radicand)" else "sqrt($radicand)"
}

data class ParenthesesNode(
    val inner: MathString = MathString()
) : MathNode() {
    override val subStrings: List<MathString> get() = listOf(inner)
    override fun replaceSubString(index: Int, newString: MathString): MathNode = copy(inner = newString)
    override fun copy(): MathNode = ParenthesesNode(inner.copy())
    override fun toString(): String = "($inner)"
}

/**
 * Scientific notation "×10ⁿ" that belongs to the number directly before it, e.g. the "×10³" in
 * 5×10³. Unlike a typed "× 10^n" it is part of that number: it is parsed as one number literal,
 * and editing (fractions, powers, selection) treats "5×10³" as a single value.
 * Without a number in front it means 10ⁿ.
 */
data class SciExponentNode(val exponent: MathString = MathString()) : MathNode() {
    override val subStrings: List<MathString> get() = listOf(exponent)
    override fun replaceSubString(index: Int, newString: MathString): MathNode =
        if (index == 0) copy(exponent = newString) else this
    override fun copy(): MathNode = SciExponentNode(exponent.copy())
    override fun toString(): String = "×10^($exponent)"
}

/**
 * Display-only text in a result, e.g. the separator between several solutions or "true".
 * It is not an expression: the parser rejects it.
 */
data class TextNode(val text: String) : MathNode() {
    override fun copy(): MathNode = TextNode(text)
    override fun toString(): String = text
}

/**
 * Ends a line of the input, e.g. between the equations of a system. Line breaks only appear in
 * the root string; inside a slot (numerator, exponent, ...) the parser rejects them.
 */
data object LineBreakNode : MathNode() {
    override fun copy(): MathNode = this
    override fun toString(): String = "\n"
}

/** Logarithm to an arbitrary base: log_base(argument). */
data class LogBaseNode(
    val base: MathString = MathString(),
    val argument: MathString = MathString()
) : MathNode() {
    override val subStrings: List<MathString> get() = listOf(base, argument)
    override fun replaceSubString(index: Int, newString: MathString): MathNode {
        return when (index) {
            0 -> copy(base = newString)
            1 -> copy(argument = newString)
            else -> this
        }
    }
    override fun copy(): MathNode = LogBaseNode(base.copy(), argument.copy())
    override fun toString(): String = "log_($base)($argument)"
}

data class FunctionNode(
    val name: String,
    val argument: MathString = MathString()
) : MathNode() {
    override val subStrings: List<MathString> get() = listOf(argument)
    override fun replaceSubString(index: Int, newString: MathString): MathNode = copy(argument = newString)
    override fun copy(): MathNode = FunctionNode(name, argument.copy())
    override fun toString(): String = "$name($argument)"
}

data class IntegralNode(
    val lowerBound: MathString = MathString(),
    val upperBound: MathString = MathString(),
    val integrand: MathString = MathString(),
    val variable: String = "x"
) : MathNode() {
    override val subStrings: List<MathString> get() = listOf(upperBound, lowerBound, integrand)
    override fun replaceSubString(index: Int, newString: MathString): MathNode {
        return when (index) {
            0 -> copy(upperBound = newString)
            1 -> copy(lowerBound = newString)
            2 -> copy(integrand = newString)
            else -> this
        }
    }
    override fun copy(): MathNode = IntegralNode(lowerBound.copy(), upperBound.copy(), integrand.copy(), variable)
    override fun toString(): String = "int_{$lowerBound}^{$upperBound} ($integrand) d$variable"
}

data class MatrixNode(
    val rows: Int,
    val cols: Int,
    val elements: List<MathString>
) : MathNode() {
    override val subStrings: List<MathString> get() = elements
    override fun replaceSubString(index: Int, newString: MathString): MathNode {
        if (index in elements.indices) {
            val newList = elements.toMutableList()
            newList[index] = newString
            return copy(elements = newList)
        }
        return this
    }
    override fun copy(): MathNode = MatrixNode(rows, cols, elements.map { it.copy() })
    override fun toString(): String = "Matrix(${rows}x${cols})"
}

class MathString(initialNodes: List<MathNode> = emptyList()) {
    private val _nodes = initialNodes.map { it.copy() }.toMutableList()
    val nodes: List<MathNode> get() = _nodes

    val length: Int get() = _nodes.size
    val isEmpty: Boolean get() = _nodes.isEmpty()
    val isNotEmpty: Boolean get() = _nodes.isNotEmpty()

    fun nodeAt(index: Int): MathNode = _nodes[index]

    fun insert(index: Int, node: MathNode) {
        val safeIndex = index.coerceIn(0, _nodes.size)
        _nodes.add(safeIndex, node)
    }

    fun insertAll(index: Int, newNodes: List<MathNode>) {
        val safeIndex = index.coerceIn(0, _nodes.size)
        _nodes.addAll(safeIndex, newNodes)
    }

    fun removeAt(index: Int): MathNode? {
        if (index in _nodes.indices) {
            return _nodes.removeAt(index)
        }
        return null
    }

    /**
     * The whole number around [index]: its digits plus a scientific "×10ⁿ" part if present,
     * e.g. all of "5.2×10³". Other nodes are tokens on their own.
     */
    fun getTokenRangeAt(index: Int): IntRange {
        if (index !in _nodes.indices) return index..index
        val targetNode = _nodes[index]
        if (targetNode !is NumberNode && targetNode !is SciExponentNode) return index..index
        var start = index
        while (start > 0 && _nodes[start - 1] is NumberNode) {
            start--
        }
        var end = index
        if (targetNode is NumberNode) {
            while (end < _nodes.size - 1 && _nodes[end + 1] is NumberNode) {
                end++
            }
            if (end < _nodes.size - 1 && _nodes[end + 1] is SciExponentNode) end++
        }
        return start..end
    }

    fun setNode(index: Int, node: MathNode) {
        if (index in _nodes.indices) {
            _nodes[index] = node
        }
    }

    fun clear() {
        _nodes.clear()
    }

    fun copy(): MathString = MathString(_nodes.map { it.copy() })

    override fun toString(): String = _nodes.joinToString("") { it.toString() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MathString) return false
        return _nodes == other._nodes
    }

    override fun hashCode(): Int = _nodes.hashCode()
}
