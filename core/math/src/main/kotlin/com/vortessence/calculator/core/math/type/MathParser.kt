package com.vortessence.calculator.core.math.type

import com.vortessence.calculator.core.math.context.MathContext
import com.vortessence.calculator.core.math.context.MathIssue
import com.vortessence.calculator.core.math.expressions.*
import com.vortessence.calculator.core.math.numbers.BigInt
import com.vortessence.calculator.core.math.numbers.Rational
import com.vortessence.calculator.core.math.numbers.Real
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Converts a [MathString] into an [Expression]. Problems such as empty slots, dangling
 * operators or unknown functions are reported to [MathContext.current] as issues; the parser
 * never silently substitutes a different meaning.
 */
class MathParser {

    /**
     * Parses the whole input. Input with several (non-empty) lines is an [EquationSystem];
     * empty lines are ignored, so a trailing line break does not change the meaning.
     */
    fun parse(string: MathString): Expression {
        if (string.nodes.none { it is LineBreakNode }) return parseLine(string)
        val lines = mutableListOf(MathString())
        for (node in string.nodes) {
            if (node is LineBreakNode) lines.add(MathString()) else lines.last().insert(lines.last().length, node)
        }
        val parsed = lines.filter { it.isNotEmpty }.map { parseLine(it) }
        return when (parsed.size) {
            0 -> Value.ZERO
            1 -> parsed.single()
            else -> EquationSystem(parsed)
        }
    }

    private fun parseLine(string: MathString): Expression {
        val tokens = tokenize(string)
        if (tokens.isEmpty()) return Value.ZERO
        val parser = TokenParser(tokens)
        return parser.parseAll()
    }

    /** Parses a slot of a structured node (numerator, exponent, ...). Empty slots are errors. */
    private fun parseSlot(string: MathString): Expression {
        if (string.isEmpty) {
            syntaxError()
            return Value.ZERO
        }
        return parseLine(string)
    }

    private sealed class Token {
        data class ValueToken(val expr: Expression) : Token()
        data class OpToken(val op: MathOp) : Token()
    }

    private fun tokenize(mathString: MathString): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val nodes = mathString.nodes

        while (i < nodes.size) {
            val node = nodes[i]
            when (node) {
                is NumberNode -> {
                    // Combine consecutive number nodes (e.g. '1', '2', '.', '5')
                    val sb = StringBuilder()
                    while (i < nodes.size && nodes[i] is NumberNode) {
                        sb.append((nodes[i] as NumberNode).text)
                        i++
                    }
                    var number: Expression = parseNumberString(sb.toString())
                    // A following "×10ⁿ" is part of this number literal
                    val sci = nodes.getOrNull(i) as? SciExponentNode
                    if (sci != null) {
                        number = Multiplications.of(number, Power.of(Value(10L), parseSlot(sci.exponent)))
                        i++
                    }
                    // Units right after the number belong to it: "25 °C" is one quantity
                    val units = mutableListOf<Expression>()
                    while (i < nodes.size && isUnitNode(nodes[i])) {
                        units.add(parseUnitNode(nodes[i]))
                        i++
                    }
                    if (units.isNotEmpty()) number = Multiplications.of(number, *units.toTypedArray())
                    tokens.add(Token.ValueToken(number))
                    continue
                }
                is SciExponentNode -> {
                    // "×10ⁿ" without a number in front of it: 10ⁿ
                    tokens.add(Token.ValueToken(Power.of(Value(10L), parseSlot(node.exponent))))
                }
                is OperatorNode -> tokens.add(Token.OpToken(node.op))
                is VariableNode -> tokens.add(Token.ValueToken(Variable(node.name)))
                is ConstantNode if node.name == IMAGINARY_UNIT -> tokens.add(Token.ValueToken(ImaginaryUnit))
                is ConstantNode if node.name == INFINITY -> tokens.add(Token.ValueToken(Value(Double.POSITIVE_INFINITY)))
                is ConstantNode -> {
                    val constant = Constant.findByName(node.name) ?: run {
                        syntaxError()
                        Value.ZERO
                    }
                    tokens.add(Token.ValueToken(constant))
                }
                is UnitNode -> tokens.add(Token.ValueToken(parseUnitNode(node)))
                is AnsNode -> {
                    // The answer is written losslessly, so parsing it again gives the exact value
                    val ans = MathContext.current().ans?.takeIf { it.isNotEmpty } ?: run {
                        MathContext.current().stopWithError(MathIssue.NO_ANS)
                        MathString()
                    }
                    tokens.add(Token.ValueToken(parseLine(ans)))
                }
                is FractionNode -> {
                    val numExpr = parseSlot(node.numerator)
                    val denExpr = parseSlot(node.denominator)
                    tokens.add(Token.ValueToken(Multiplications.of(numExpr, Power.of(denExpr, Value.MINUS_ONE))))
                }
                is MixedFractionNode -> tokens.add(Token.ValueToken(parseMixedFraction(node)))
                is ExponentNode -> {
                    val baseExpr = if (node.base != null) {
                        parseSlot(node.base)
                    } else if (tokens.isNotEmpty() && tokens.last() is Token.ValueToken) {
                        // Superscript applied to preceding token
                        (tokens.removeAt(tokens.lastIndex) as Token.ValueToken).expr
                    } else {
                        syntaxError()
                        Value.ONE
                    }
                    val expExpr = parseSlot(node.exponent)
                    tokens.add(Token.ValueToken(Power.of(baseExpr, expExpr)))
                }
                is RadicalNode -> {
                    val radicand = parseSlot(node.radicand)
                    val radExpr = if (node.degree != null) {
                        Power.nthRoot(parseSlot(node.degree), radicand)
                    } else {
                        Power.squareRoot(radicand)
                    }
                    tokens.add(Token.ValueToken(radExpr))
                }
                is ParenthesesNode -> tokens.add(Token.ValueToken(parseSlot(node.inner)))
                is FunctionNode if NamedFunctionKind.byName(node.name) != null -> {
                    val arguments = splitArguments(node.argument).map { parseSlot(it) }
                    tokens.add(Token.ValueToken(NamedFunction(NamedFunctionKind.byName(node.name)!!, arguments)))
                }
                is FunctionNode -> {
                    val arg = parseSlot(node.argument)
                    val fnExpr = when (node.name.lowercase()) {
                        "sin" -> TrigonometricFunction(TrigType.SIN, arg)
                        "cos" -> TrigonometricFunction(TrigType.COS, arg)
                        "tan" -> TrigonometricFunction(TrigType.TAN, arg)
                        "asin", "arcsin" -> TrigonometricFunction(TrigType.ASIN, arg)
                        "acos", "arccos" -> TrigonometricFunction(TrigType.ACOS, arg)
                        "atan", "arctan" -> TrigonometricFunction(TrigType.ATAN, arg)
                        "ln" -> Logarithm.ln(arg)
                        "log", "log10" -> Logarithm.log10(arg)
                        "sqrt" -> Power.squareRoot(arg)
                        "abs" -> AbsoluteValue(arg)
                        "det" -> Determinant(arg)
                        else -> {
                            MathContext.current().stopWithError(MathIssue.UNKNOWN_FUNCTION)
                            arg
                        }
                    }
                    tokens.add(Token.ValueToken(fnExpr))
                }
                is LogBaseNode -> {
                    val base = parseSlot(node.base)
                    val arg = parseSlot(node.argument)
                    tokens.add(Token.ValueToken(Logarithm(base, arg)))
                }
                is IntegralNode -> {
                    val lower = parseSlot(node.lowerBound)
                    val upper = parseSlot(node.upperBound)
                    val integrand = parseSlot(node.integrand)
                    tokens.add(Token.ValueToken(Integral(lower, upper, integrand, Variable(node.variable))))
                }
                is AntiderivativeNode -> {
                    val integrand = parseSlot(node.integrand)
                    tokens.add(Token.ValueToken(Antiderivative(integrand, Variable(node.variable))))
                }
                is DerivativeNode -> {
                    val body = parseSlot(node.body)
                    val point = node.point?.let { parseSlot(it) }
                    tokens.add(Token.ValueToken(Derivative(body, Variable(node.variable), point)))
                }
                is BigOperatorNode -> {
                    val (index, start) = parseBinding(node.lower, MathOp.EQUALS)
                    val end = parseSlot(node.upper)
                    val body = parseSlot(node.body)
                    tokens.add(Token.ValueToken(Series(node.operator == BigOperator.PRODUCT, index, start, end, body)))
                }
                is LimitNode -> {
                    val (variable, point) = parseBinding(node.approach, MathOp.CONVERT)
                    tokens.add(Token.ValueToken(Limit(parseSlot(node.body), variable, point)))
                }
                is MatrixNode -> {
                    val elements = node.elements.map { parseSlot(it) }
                    tokens.add(Token.ValueToken(MatrixExpression(node.rows, node.cols, elements)))
                }
                // A line break inside a slot, e.g. a fraction wrapped around two lines
                // A comma outside of a function with several arguments
                is TextNode, is LineBreakNode, is SeparatorNode -> syntaxError()
            }
            i++
        }

        // Insert implicit multiplication between adjacent value tokens
        val result = mutableListOf<Token>()
        for (idx in tokens.indices) {
            val curr = tokens[idx]
            if (idx > 0) {
                val prev = tokens[idx - 1]
                if (prev is Token.ValueToken && curr is Token.ValueToken) {
                    result.add(Token.OpToken(MathOp.MULTIPLY))
                } else if (prev is Token.OpToken && prev.op.isPostfix && curr is Token.ValueToken) {
                    result.add(Token.OpToken(MathOp.MULTIPLY))
                }
            }
            result.add(curr)
        }
        return result
    }

    /** The arguments of a function: [argument] split at its commas. */
    private fun splitArguments(argument: MathString): List<MathString> {
        val arguments = mutableListOf(MathString())
        for (node in argument.nodes) {
            if (node is SeparatorNode) arguments.add(MathString()) else arguments.last().insert(arguments.last().length, node)
        }
        return arguments
    }

    /** The exact value of a mixed number; each part must be a whole number written in digits. */
    private fun parseMixedFraction(node: MixedFractionNode): Expression {
        fun part(string: MathString): BigInteger? {
            if (string.nodes.any { it !is NumberNode }) return null
            val text = string.nodes.joinToString("") { (it as NumberNode).text }
            return text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let(::BigInteger)
        }
        val whole = part(node.whole)
        val numerator = part(node.numerator)
        val denominator = part(node.denominator)
        if (whole == null || numerator == null || denominator == null) {
            syntaxError()
            return Value.ZERO
        }
        if (denominator.signum() == 0) {
            MathContext.current().stopWithError(MathIssue.DIVISION_BY_ZERO)
            return Value.ZERO
        }
        return Value(Rational.of(BigInt(whole)) + Rational.of(BigInt(numerator), BigInt(denominator)))
    }

    /**
     * A variable bound to a value, "n=1" below a sum or "x→0" below a limit: the variable,
     * then [op], then the value.
     */
    private fun parseBinding(string: MathString, op: MathOp): Pair<Variable, Expression> {
        val name = (string.nodes.getOrNull(0) as? VariableNode)?.name
        if (name == null || (string.nodes.getOrNull(1) as? OperatorNode)?.op != op) {
            syntaxError()
            return Variable("x") to Value.ZERO
        }
        return Variable(name) to parseSlot(MathString(string.nodes.drop(2)))
    }

    /** A unit, or a unit raised to a power (m², typed as m with the power key). */
    private fun isUnitNode(node: MathNode): Boolean =
        node is UnitNode || (node is ExponentNode && node.base?.let { base ->
            base.isNotEmpty && base.nodes.all { it is UnitNode }
        } == true)

    private fun parseUnitNode(node: MathNode): Expression = when (node) {
        is UnitNode -> UnitRegistry.findBySymbol(node.symbol) ?: run {
            MathContext.current().stopWithError(MathIssue.INVALID_UNIT)
            Value.ONE
        }
        is ExponentNode -> Power.of(parseLine(node.base!!), parseSlot(node.exponent))
        else -> error("not a unit node")
    }

    /**
     * Parses a decimal number exactly, including scientific notation such as "1.5e-3"
     * (which results produced in numeric mode may contain).
     */
    private fun parseNumberString(str: String): Value {
        // Tolerate digit-group separators (300 000) in case grouped text is ever reused
        val normalized = str.filterNot { it == ' ' || it == '\u202F' || it == '\u2009' }.replace('−', '-')
        val decimal = try {
            BigDecimal(normalized)
        } catch (e: NumberFormatException) {
            syntaxError()
            return Value.ZERO
        }
        if (decimal.signum() == 0) return Value.ZERO
        val scale = decimal.scale()
        if (scale > MAX_EXACT_DECIMAL_SCALE || scale < -MAX_EXACT_DECIMAL_SCALE) {
            // Absurd exponents (e.g. 1e99999): fall back to floating point
            val d = decimal.toDouble()
            if (d.isInfinite()) MathContext.current().stopWithError(MathIssue.VALUES_TOO_LARGE)
            return Value(Real(d))
        }
        val unscaled = BigInt(decimal.unscaledValue())
        return if (scale >= 0) {
            Value(Rational.of(unscaled, BigInt(BigInteger.TEN.pow(scale))))
        } else {
            Value(Rational.of(unscaled * BigInt(BigInteger.TEN.pow(-scale))))
        }
    }

    private class TokenParser(val tokens: List<Token>) {
        var pos = 0

        fun parseAll(): Expression {
            val expr = parseExpression(MathOp.CONVERT.precedence)
            if (pos < tokens.size) syntaxError()
            return expr
        }

        fun parseExpression(minPrecedence: Int): Expression {
            var left = parsePrimary()

            while (pos < tokens.size) {
                val token = tokens[pos] as? Token.OpToken ?: break
                val op = token.op
                if (op.isPostfix) {
                    // A factorial or percent that did not directly follow a value (e.g. "2 + !")
                    syntaxError()
                    pos++
                    continue
                }
                val prec = op.precedence
                if (prec < minPrecedence) break

                pos++
                val right = parseExpression(prec + 1)
                left = when (op) {
                    MathOp.PLUS -> Additions.of(left, right)
                    MathOp.MINUS -> Additions.of(left, right.negate())
                    MathOp.MULTIPLY -> Multiplications.of(left, right)
                    MathOp.DIVIDE -> Multiplications.of(left, Power.of(right, Value.MINUS_ONE))
                    MathOp.EQUALS -> {
                        if (left is Equals || right is Equals) syntaxError()
                        Equals(left, right)
                    }
                    MathOp.FACTORIAL, MathOp.PERCENT -> left
                    MathOp.MODULO -> Modulo(left, right)
                    // Definitions are split off by MathEngine; anywhere else ":=" is invalid
                    MathOp.DEFINE -> {
                        syntaxError()
                        left
                    }
                    MathOp.CONVERT -> {
                        if (left is UnitConversion || right is UnitConversion) syntaxError()
                        UnitConversion(left, right)
                    }
                }
            }
            return left
        }

        private fun parsePrimary(): Expression {
            if (pos >= tokens.size) {
                // Dangling operator, e.g. "2 +"
                syntaxError()
                return Value.ZERO
            }

            val token = tokens[pos]
            if (token is Token.OpToken && token.op == MathOp.MINUS) {
                pos++
                return parsePrimary().negate()
            }
            if (token is Token.OpToken && token.op == MathOp.PLUS) {
                pos++
                return parsePrimary()
            }
            if (token !is Token.ValueToken) {
                // Operator without a left operand, e.g. "× 3"
                syntaxError()
                return Value.ZERO
            }

            pos++
            var expr = token.expr
            while (pos < tokens.size) {
                val nextToken = tokens[pos]
                if (nextToken is Token.OpToken && nextToken.op == MathOp.FACTORIAL) {
                    pos++
                    expr = Factorial(expr)
                } else if (nextToken is Token.OpToken && nextToken.op == MathOp.PERCENT) {
                    pos++
                    expr = Multiplications.of(expr, Value(1L, 100L))
                } else {
                    break
                }
            }

            return expr
        }
    }

    private companion object {
        const val IMAGINARY_UNIT = "i"
        const val INFINITY = "∞"
        const val MAX_EXACT_DECIMAL_SCALE = 10_000

        fun syntaxError() {
            MathContext.current().stopWithError(MathIssue.SYNTAX_ERROR)
        }
    }
}
