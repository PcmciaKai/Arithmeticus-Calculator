package com.vortessence.arithmeticus.core.math.type

import kotlin.math.max
import kotlin.math.min

/**
 * Helpers for caret paths. A caret path addresses a position inside nested [MathString]s:
 * `[node, child, node, child, ..., position]`, e.g. `[2, 0, 1]` is position 1 in the
 * numerator (child 0) of the fraction at index 2 of the root string.
 */
object CaretPaths {

    /** A selection of [range] (node indices) within the string at [stringPath]. */
    data class Selection(val stringPath: List<Int>, val range: IntRange)

    /** The string addressed by [stringPath] (a caret path without its position), or null. */
    fun resolve(root: MathString, stringPath: List<Int>): MathString? {
        var current = root
        for (i in 0 until stringPath.size - 1 step 2) {
            val node = current.nodes.getOrNull(stringPath[i]) ?: return null
            current = node.subStrings.getOrNull(stringPath[i + 1]) ?: return null
        }
        return current
    }

    /**
     * The smallest selection containing all [carets], like selecting text between two points.
     * The selection lives in the deepest string that contains every caret. A caret inside a
     * nested structure (e.g. a numerator) pulls in that whole structure, because a fraction
     * cannot be partially selected from outside.
     *
     * Returns null if the carets do not span any node (e.g. all carets are the same position).
     */
    fun covering(carets: List<List<Int>>, root: MathString): Selection? {
        if (carets.isEmpty() || carets.any { it.isEmpty() || it.size % 2 == 0 }) return null

        // Deepest common string: longest shared prefix of (node, child) pairs
        val depths = carets.map { (it.size - 1) / 2 }
        var commonPairs = 0
        while (commonPairs < depths.min() && carets.all {
                it[2 * commonPairs] == carets[0][2 * commonPairs] &&
                    it[2 * commonPairs + 1] == carets[0][2 * commonPairs + 1]
            }
        ) {
            commonPairs++
        }
        val stringPath = carets[0].take(2 * commonPairs)
        val target = resolve(root, stringPath) ?: return null

        var start = Int.MAX_VALUE
        var end = Int.MIN_VALUE
        for (caret in carets) {
            val level = stringPath.size
            val isAtThisLevel = caret.size == level + 1
            val lo = caret[level] // a position here, or the index of the node containing the caret
            val hi = if (isAtThisLevel) lo else lo + 1
            start = min(start, lo)
            end = max(end, hi)
        }
        start = start.coerceIn(0, target.length)
        end = end.coerceIn(0, target.length)
        if (start >= end) return null
        return Selection(stringPath, start until end)
    }
}
