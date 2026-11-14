package com.vortessence.arithmeticus.core.math.type

/**
 * Functions defined by the user, "f(x) := x² + 1". They are kept with the variables (MathContext.variables, name to value):
 * the name of a function is written with its parameters, "f(x)" or "g(x,y)", and its value is the body. A name without
 * brackets is a variable.
 *
 * A function is used by its name followed by brackets, f(3) or g(a + 1, 2): the body is put in its place, in brackets, with
 * every parameter replaced by the argument (in brackets), before the input is parsed. So f(x)² is the square of the body,
 * d/dx f(x) its derivative, and the parameters are replaced all at once (g(y, x) swaps them).
 */
object FunctionDefinitions {

    /** The key a function is kept under: "f(x)", "g(x,y)". */
    fun key(name: String, parameters: List<String>): String = "$name(${parameters.joinToString(",")})"

    /** Whether [key] is the key of a function rather than the name of a variable. */
    fun isFunction(key: String): Boolean = key.endsWith(")") && '(' in key

    /** The name of the variable or function kept under [key]: "f" for "f(x)" and for "f". */
    fun nameOf(key: String): String = key.substringBefore('(')

    /** The parameters of the function kept under [key]; empty for a variable. */
    fun parametersOf(key: String): List<String> =
        if (isFunction(key)) key.substringAfter('(').dropLast(1).split(',').filter { it.isNotEmpty() } else emptyList()

    /**
     * The parameters of a definition's left side "f(x, y)" (the bracket after the name): the letters, separated by commas.
     * Null if it is not such a list (a number, a letter twice).
     */
    fun parameterList(bracket: ParenthesesNode): List<String>? {
        val parameters = mutableListOf<String>()
        var expectLetter = true
        for (node in bracket.inner.nodes) {
            if (expectLetter) {
                val name = (node as? VariableNode)?.name?.takeIf { isValidVariableName(it) } ?: return null
                if (name in parameters) return null
                parameters.add(name)
            } else if (node !is SeparatorNode) {
                return null
            }
            expectLetter = !expectLetter
        }
        return parameters.takeIf { it.isNotEmpty() && !expectLetter }
    }

    /**
     * Adds [key] = [value] to [definitions], replacing an older definition of the same name: a function f replaces the
     * variable f, and the other way round.
     */
    fun define(definitions: MutableMap<String, MathString>, key: String, value: MathString) {
        val name = nameOf(key)
        definitions.keys.removeAll { nameOf(it) == name }
        definitions[key] = value
    }

    /** Whether the letter [name] stands anywhere in [input]. */
    fun uses(input: MathString, name: String): Boolean =
        input.nodes.any { node -> (node as? VariableNode)?.name == name || node.subStrings.any { uses(it, name) } }

    /** [input] with the functions of [definitions] put in where they are used; [input] itself if it uses none. */
    fun apply(input: MathString, definitions: Map<String, MathString>): MathString {
        val functions = definitions.entries.filter { isFunction(it.key) }
            .associate { (key, body) -> nameOf(key) to (parametersOf(key) to body) }
        if (functions.isEmpty()) return input
        return applyFunctions(input, functions, 0)
    }

    private fun applyFunctions(input: MathString, functions: Map<String, Pair<List<String>, MathString>>, depth: Int): MathString {
        // The bodies are worked out when they are defined, so they use no other function; this only guards against a cycle
        if (depth > MAX_DEPTH) return input
        val nodes = input.nodes.map { node ->
            var copy = node
            node.subStrings.forEachIndexed { i, sub -> copy = copy.replaceSubString(i, applyFunctions(sub, functions, depth)) }
            copy
        }
        val result = mutableListOf<MathNode>()
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            val call = (node as? VariableNode)?.let { functions[it.name] }
            val bracket = nodes.getOrNull(i + 1) as? ParenthesesNode
            if (call != null && bracket != null) {
                val arguments = splitArguments(bracket.inner)
                if (arguments.size == call.first.size) {
                    val replaced = substitute(call.second, call.first.zip(arguments).toMap())
                    result.add(ParenthesesNode(applyFunctions(replaced, functions, depth + 1)))
                    i += 2
                    continue
                }
            }
            result.add(node)
            i++
        }
        return MathString(result)
    }

    /** The arguments in [inner], separated by commas. */
    private fun splitArguments(inner: MathString): List<MathString> {
        val arguments = mutableListOf(mutableListOf<MathNode>())
        for (node in inner.nodes) {
            if (node is SeparatorNode) arguments.add(mutableListOf()) else arguments.last().add(node)
        }
        return arguments.map { MathString(it) }
    }

    /**
     * [body] with each parameter replaced by its argument in brackets, all at once. A letter that is bound where it stands (the
     * x of ∫₀ˣ e^sin(x) dx inside the integral, the index of a sum, the variable of a limit) is not the parameter there; d/dx of
     * a body with the parameter x is the derivative at the argument.
     */
    private fun substitute(body: MathString, arguments: Map<String, MathString>): MathString = MathString(body.nodes.map { node ->
        val argument = (node as? VariableNode)?.let { arguments[it.name] }
        when {
            argument != null -> ParenthesesNode(argument)
            node is DerivativeNode && node.point == null && node.variable in arguments ->
                DerivativeNode(substitute(node.body, arguments - node.variable), arguments.getValue(node.variable), node.variable)
            else -> {
                val bound = boundVariable(node)
                var copy = node
                node.subStrings.forEachIndexed { i, sub ->
                    val inside = if (bound != null && i in bound.second) arguments - bound.first else arguments
                    copy = copy.replaceSubString(i, substitute(sub, inside))
                }
                copy
            }
        }
    })

    /** The letter bound by [node] and the indices of its sub-strings where it is bound; null for a node that binds none. */
    private fun boundVariable(node: MathNode): Pair<String, Set<Int>>? = when (node) {
        is IntegralNode -> node.variable to setOf(2)
        is DerivativeNode -> node.variable to setOf(0)
        is BigOperatorNode -> (node.lower.nodes.firstOrNull() as? VariableNode)?.name?.let { it to setOf(0, 2) }
        is LimitNode -> (node.approach.nodes.firstOrNull() as? VariableNode)?.name?.let { it to setOf(0, 1) }
        else -> null
    }

    private const val MAX_DEPTH = 20
}
