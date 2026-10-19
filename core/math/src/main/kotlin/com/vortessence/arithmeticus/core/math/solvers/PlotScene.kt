package com.vortessence.arithmeticus.core.math.solvers

import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.type.MathString

/**
 * What became of one line of the graph input: the [curve] it draws, or none if it cannot be graphed. A definition ("a := 2",
 * [definedVariable] is "a") draws nothing, it gives the other lines a value.
 */
class PlotEntry(val line: MathString, val curve: PlotCurve?, val definedVariable: String? = null) {
    val isDefinition: Boolean get() = definedVariable != null
}

/**
 * The lines of the graph input, each as a curve (or as what could not be made of it): the graph draws all the curves and marks
 * where they cross.
 */
class PlotScene(val entries: List<PlotEntry>) {
    /** A surface z = f(x, y) cannot be drawn with other curves: the graph shows the first surface alone. */
    val surfaceEntry: PlotEntry? get() = entries.firstOrNull { it.curve is Surface3D }

    /**
     * Where all the inequalities of the lines hold together (the lines that are not in [hidden]): the region a system of
     * inequalities describes. Null unless there are at least two of them, and they can be put in one picture (one variable
     * each, or x and y).
     */
    fun commonRegion(hidden: Set<Int> = emptySet()): InequalityPlot? {
        val inequalities = entries.withIndex().filter { it.index !in hidden }.mapNotNull { it.value.curve as? InequalityPlot }
            .filter { it.constraints.size == 1 }
        if (inequalities.size < 2) return null
        // x > 1 and y < 2 are in one plane, which a variable other than x or y is not part of
        val horizontals = inequalities.map { it.horizontal }.toSet()
        val two = inequalities.any { it.twoVariables } || horizontals.size > 1
        if (two && !horizontals.all { it == Variable("x") || it == Variable("y") }) return null
        val horizontal = if (two) Variable("x") else horizontals.single()
        return InequalityPlot(inequalities.flatMap { it.constraints }, null, two, horizontal, inequalities.first().angleUnit)
    }
}

/** Where the curves [first] and [second] (the indices of the entries of a [PlotScene]) cross. */
data class PlotIntersection(val x: Double, val y: Double, val first: Int, val second: Int)
