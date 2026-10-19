package com.vortessence.arithmeticus.core.math.steps

import com.vortessence.arithmeticus.core.math.i18n.Text
import com.vortessence.arithmeticus.core.math.i18n.Texts
import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Additions
import com.vortessence.arithmeticus.core.math.expressions.Antiderivative
import com.vortessence.arithmeticus.core.math.expressions.Derivative
import com.vortessence.arithmeticus.core.math.context.MathIssue
import com.vortessence.arithmeticus.core.math.expressions.Determinant
import com.vortessence.arithmeticus.core.math.expressions.EigenSystem
import com.vortessence.arithmeticus.core.math.expressions.RowReduced
import com.vortessence.arithmeticus.core.math.expressions.Equals
import com.vortessence.arithmeticus.core.math.expressions.EquationSystem
import com.vortessence.arithmeticus.core.math.expressions.Expression
import com.vortessence.arithmeticus.core.math.expressions.Inequality
import com.vortessence.arithmeticus.core.math.expressions.Integral
import com.vortessence.arithmeticus.core.math.expressions.Limit
import com.vortessence.arithmeticus.core.math.expressions.MatrixExpression
import com.vortessence.arithmeticus.core.math.expressions.Multiplications
import com.vortessence.arithmeticus.core.math.expressions.PartialFractions
import com.vortessence.arithmeticus.core.math.expressions.Power
import com.vortessence.arithmeticus.core.math.expressions.Series
import com.vortessence.arithmeticus.core.math.expressions.UnitExpression
import com.vortessence.arithmeticus.core.math.expressions.Value
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.expressions.containsImaginaryUnit
import com.vortessence.arithmeticus.core.math.numbers.Rational
import kotlin.math.abs
import kotlin.math.max

/**
 * The steps of calculating with matrices, as by hand. Each operation on matrices is worked out where it is, the
 * innermost first, and its entries are calculated before the next one:
 * - the determinant: ad − bc for 2×2, otherwise expanded along the row or column with the most zeros into smaller ones,
 * - the inverse: for 2×2 by the formula with the determinant, otherwise Gauss-Jordan on (A | I), row by row,
 * - products (every entry a row times a column), sums and differences, a number times a matrix,
 * - the transpose and integer powers (as repeated products).
 * The steps must end at the calculator's own result.
 */
internal object MatrixSteps {

    private const val MAX_OPERATIONS = 40
    private const val MAX_SIMPLIFY = 120
    private const val MAX_POWER = 5
    private const val MAX_GAUSS_SIZE = 5
    private const val MAX_EIGEN_SIZE = 4
    private const val MAX_EIGEN_RESULT_SIZE = 5
    private const val MAX_REDUCE_ROWS = MAX_GAUSS_SIZE + 1
    private const val MAX_REDUCE_COLUMNS = 2 * MAX_GAUSS_SIZE

    fun steps(e: Expression, context: MathContext): ExpressionSolution? {
        if (!e.containsExpressionMatching { it is MatrixExpression }) return null
        // rref(A) and eig(A) are worked out as a whole, from the matrix that A stands for
        if (e is RowReduced || e is EigenSystem) {
            val operand = if (e is RowReduced) e.operand else (e as EigenSystem).operand
            val scratch = context.copy()
            // The entries as numbers: 1 + 1 is 2 (the first step of the steps shows the matrix as it was typed)
            val m = operand.canonicalize(scratch) as? MatrixExpression
            if (m == null || scratch.shouldStop) return null
            return if (e is RowReduced) rowReduceSteps(e, m, context) else eigenSteps(e, m, context)
        }
        if (e.containsExpressionMatching {
                it is Equals || it is Inequality || it is EquationSystem || it is Derivative || it is Antiderivative ||
                    it is Integral || it is Limit || it is Series || it is UnitExpression
            }
        ) return null
        val scratch = context.copy()
        val calculated = e.canonicalize(scratch)
        if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null

        val solution = Solver(context)
        solution.start(normalize(e))
        var operations = 0
        while (true) {
            if (context.shouldStop) return null
            val path = pathsOf(solution.current) { isReady(unmarked(it)) }.firstOrNull() ?: break
            if (++operations > MAX_OPERATIONS || !apply(solution, path, context)) return null
        }
        // What is left to calculate: the entries of the result
        if (!simplifyAt(solution, emptyList(), context)) return null
        val result = normalize(unmarked(solution.current))
        if (result.containsExpressionMatching { it is Determinant } || !sameValue(result, calculated)) return null
        if (solution.size < 2) return null
        return solution.finish(result)
    }

    // ----- which operation is next -----

    /** An operation whose operands are numbers and matrices as they are (nothing left to work out inside them). */
    private fun isReady(node: Expression): Boolean {
        fun resolved(operand: Expression) = operand is MatrixExpression || !operand.containsExpressionMatching { it is MatrixExpression }
        return when (node) {
            is Determinant -> (node.operand as? MatrixExpression)?.let { it.isSquare && it.rows >= 2 } == true
            is Power -> node.base is MatrixExpression && (node.exponent == Variable("T") || integerExponent(node.exponent).let { it == -1 || it in 2..MAX_POWER })
            is Multiplications -> {
                val matrices = node.operands.count { it is MatrixExpression }
                node.operands.all { resolved(it) } && (matrices >= 2 || (matrices == 1 && node.operands.size >= 2))
            }
            is Additions -> node.operands.size >= 2 && node.operands.all { it is MatrixExpression || negatedMatrix(it) != null }
            else -> false
        }
    }

    private fun integerExponent(e: Expression): Int? = (e as? Value)?.rational?.takeIf { !it.isFractional }?.numerator?.value?.takeIf { it.bitLength() < 8 }?.toInt()

    /** −1·A, a subtracted matrix: A. */
    private fun negatedMatrix(e: Expression): MatrixExpression? {
        val product = e as? Multiplications ?: return null
        if (product.operands.size != 2 || product.operands[0] != Value.MINUS_ONE) return null
        return product.operands[1] as? MatrixExpression
    }

    // ----- one operation -----

    private fun apply(solution: Solver, path: Path, context: MathContext): Boolean {
        val node = normalize(unmarked(at(solution.current, path)))
        return when (node) {
            is Determinant -> determinant(solution, path, node, context)
            is Power -> power(solution, path, node, context)
            is Multiplications -> product(solution, path, node, context)
            is Additions -> sum(solution, path, node, context)
            else -> false
        }
    }

    private fun replace(solution: Solver, path: Path, rule: StepRule, node: Expression, result: Expression, notes: List<Note> = emptyList()) {
        solution.apply(Match(rule, mapOf(path to result), node, result, notes = notes, showChange = false, boxReplacements = false))
    }

    private fun entries(m: MatrixExpression): List<Expression> = m.elements.map { normalize(unmarked(it)) }

    // ----- determinant -----

    private fun determinant(solution: Solver, path: Path, node: Determinant, context: MathContext): Boolean {
        val m = node.operand as MatrixExpression
        val a = entries(m)
        if (m.rows == 2) {
            val result = Additions(Multiplications(a[0], a[3]), Multiplications(Value.MINUS_ONE, a[1], a[2]))
            replace(solution, path, StepRule.DETERMINANT_2X2, node, result)
            return simplifyAt(solution, path, context)
        }
        // Along the row or column with the most zeros
        val n = m.rows
        fun isZero(i: Int) = a[i].isZero
        val lines = (0 until n).map { r -> "row" to r } + (0 until n).map { c -> "column" to c }
        val best = lines.maxByOrNull { (kind, k) -> (0 until n).count { j -> isZero(if (kind == "row") k * n + j else j * n + k) } } ?: return false
        val (kind, k) = best
        val terms = mutableListOf<Expression>()
        for (j in 0 until n) {
            val r = if (kind == "row") k else j
            val c = if (kind == "row") j else k
            val entry = a[r * n + c]
            if (entry.isZero) continue
            val minor = MatrixExpression(n - 1, n - 1, (0 until n).filter { it != r }.flatMap { rr -> (0 until n).filter { it != c }.map { cc -> a[rr * n + cc] } })
            val sign = if ((r + c) % 2 == 0) 1 else -1
            val determinant = Determinant(minor)
            terms.add(if (sign > 0) Multiplications(entry, determinant) else Multiplications(Value.MINUS_ONE, entry, determinant))
        }
        val result: Expression = when (terms.size) {
            0 -> Value.ZERO
            1 -> terms.single()
            else -> Additions(terms)
        }
        val note = Note(TextExpression(Texts.format(if (kind == "row") Text.EXPANDED_ALONG_ROW else Text.EXPANDED_ALONG_COLUMN, k + 1)), TextExpression(""), relation = "")
        replace(solution, path, StepRule.DETERMINANT_COFACTOR, node, result, notes = listOf(note))
        return true
    }

    // ----- powers: transpose, inverse, integer powers -----

    private fun power(solution: Solver, path: Path, node: Power, context: MathContext): Boolean {
        val m = node.base as MatrixExpression
        if (node.exponent == Variable("T")) {
            replace(solution, path, StepRule.MATRIX_TRANSPOSE, node, m.transpose())
            return true
        }
        val n = integerExponent(node.exponent) ?: return false
        if (n >= 2) {
            val repeated = Multiplications(List(n) { m })
            replace(solution, path, StepRule.MATRIX_POWER, node, repeated)
            return true
        }
        return inverse(solution, path, node, m, context)
    }

    private fun inverse(solution: Solver, path: Path, node: Power, m: MatrixExpression, context: MathContext): Boolean {
        if (!m.isSquare) return false
        val a = entries(m)
        val determinant = m.determinant(context.copy())?.canonicalize(context.copy()) ?: return false
        if (determinant.isZero) return false
        if (m.rows == 1) return false
        if (m.rows == 2) {
            // 1/(ad − bc)·(d −b; −c a)
            val d = Additions(Multiplications(a[0], a[3]), Multiplications(Value.MINUS_ONE, a[1], a[2]))
            val adjugate = MatrixExpression(2, 2, listOf(a[3], Multiplications(Value.MINUS_ONE, a[1]), Multiplications(Value.MINUS_ONE, a[2]), a[0]))
            val result = Multiplications(Power(d, Value.MINUS_ONE), adjugate)
            replace(solution, path, StepRule.INVERSE_2X2, node, result)
            return simplifyAt(solution, path, context)
        }
        return gaussJordan(solution, path, node, m, context)
    }

    /** (A | I) → (I | A⁻¹) by row operations, for numbers as entries. */
    private fun gaussJordan(solution: Solver, path: Path, node: Power, m: MatrixExpression, context: MathContext): Boolean {
        val n = m.rows
        if (n > MAX_GAUSS_SIZE) return false
        val numbers = numbers(m) ?: return false
        val width = 2 * n
        val start = List(n) { r -> List(width) { c -> if (c < n) numbers[r][c] else if (c - n == r) Rational.ONE else Rational.ZERO } }
        val reduction = reduce(start, n)
        if (reduction.rank < n) return false
        replace(solution, path, StepRule.INVERSE_AUGMENT, node, matrixOf(start))
        showOperations(solution, path, reduction.operations)
        val inverse = MatrixExpression(n, n, (0 until n).flatMap { r -> (0 until n).map { c -> Value(reduction.rows[r][n + c]) } })
        replace(solution, path, StepRule.INVERSE_RESULT, matrixOf(reduction.rows), inverse)
        return true
    }

    // ----- row reduction -----

    private class RowOperation(val rule: StepRule, val text: String, val changed: List<Int>, val rows: List<List<Rational>>)

    private class Reduction(val operations: List<RowOperation>, val rows: List<List<Rational>>, val pivotColumns: List<Int>) {
        val rank: Int get() = pivotColumns.size
    }

    private fun matrixOf(rows: List<List<Rational>>) = MatrixExpression(rows.size, rows.first().size, rows.flatten().map { Value(it) })

    /** The entries as numbers; null if there is anything else in them. */
    private fun numbers(m: MatrixExpression): List<List<Rational>>? =
        (0 until m.rows).map { r -> (0 until m.cols).map { c -> (normalize(unmarked(m[r, c])) as? Value)?.rational ?: return null } }

    private fun subscript(i: Int) = (i + 1).toString().map { "₀₁₂₃₄₅₆₇₈₉"[it - '0'] }.joinToString("")

    /**
     * Gauss-Jordan on the first [columns] columns: a 1 in each pivot place, zeros above and below it (reduced row echelon form).
     * The rows with the operations that made it: swapping, dividing a row by its pivot, subtracting a multiple of the pivot row.
     */
    private fun reduce(start: List<List<Rational>>, columns: Int): Reduction {
        val rows = start.map { it.toMutableList() }.toMutableList()
        val n = rows.size
        val width = rows.first().size
        val operations = mutableListOf<RowOperation>()
        val pivots = mutableListOf<Int>()
        fun record(rule: StepRule, text: String, changed: List<Int>) = operations.add(RowOperation(rule, text, changed, rows.map { it.toList() }))
        var row = 0
        for (col in 0 until columns) {
            if (row >= n) break
            val pivotRow = (row until n).firstOrNull { !rows[it][col].isZero } ?: continue
            if (pivotRow != row) {
                val swapped = rows[row]
                rows[row] = rows[pivotRow]
                rows[pivotRow] = swapped
                record(StepRule.ROW_SWAP, "R${subscript(row)} ↔ R${subscript(pivotRow)}", listOf(row, pivotRow))
            }
            val pivot = rows[row][col]
            if (pivot != Rational.ONE) {
                for (c in 0 until width) rows[row][c] = rows[row][c] / pivot
                record(StepRule.ROW_SCALE, "R${subscript(row)} → R${subscript(row)} ÷ ${writtenNumber(pivot)}", listOf(row))
            }
            for (r in 0 until n) {
                if (r == row || rows[r][col].isZero) continue
                val factor = rows[r][col]
                for (c in 0 until width) rows[r][c] = rows[r][c] - factor * rows[row][c]
                val size = if (factor.isNegative) -factor else factor
                val shown = if (size == Rational.ONE) "" else "${writtenNumber(size)}·"
                record(StepRule.ROW_ADD, "R${subscript(r)} → R${subscript(r)} ${if (factor.isNegative) "+" else "−"} ${shown}R${subscript(row)}", listOf(r))
            }
            pivots.add(col)
            row++
        }
        return Reduction(operations, rows.map { it.toList() }, pivots)
    }

    /** One step for each row operation, each showing the whole matrix with the changed rows marked; [last] is added to the notes of the final step. */
    private fun showOperations(solution: Solver, path: Path, operations: List<RowOperation>, last: Note? = null) {
        for ((i, operation) in operations.withIndex()) {
            val width = operation.rows.first().size
            val matrix = matrixOf(operation.rows)
            val replacements = operation.changed.flatMap { r -> (0 until width).map { c -> (path + (r * width + c)) to Value(operation.rows[r][c]) as Expression } }.toMap()
            val notes = listOfNotNull(Note(TextExpression(operation.text), TextExpression(""), relation = ""), last.takeIf { i == operations.lastIndex })
            solution.apply(Match(operation.rule, replacements, matrix, matrix, notes = notes, showChange = false))
        }
    }

    /** −2 or 3/4 as it is written in a note. */
    private fun writtenNumber(r: Rational): String {
        val sign = if (r.isNegative) "−" else ""
        val size = if (r.isNegative) -r else r
        val number = if (size.isFractional) "${size.numerator}/${size.denominator}" else size.toString()
        return if (r.isNegative || r.isFractional) "($sign$number)" else number
    }

    /** The reduced row echelon form of [m], or null (with an issue reported) if it is not a matrix of numbers. */
    internal fun rowReduced(m: MatrixExpression, context: MathContext): MatrixExpression? {
        if (m.rows > MAX_REDUCE_ROWS || m.cols > MAX_REDUCE_COLUMNS) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return null
        }
        val start = numbers(m)
        if (start == null) {
            context.stopWithError(MathIssue.MATRIX_NEEDS_NUMBERS)
            return null
        }
        return matrixOf(reduce(start, m.cols).rows)
    }

    /** rref(A) row by row; the rank is told in the last step. Null if A is reduced already (or not a matrix of numbers). */
    private fun rowReduceSteps(problem: Expression, m: MatrixExpression, context: MathContext): ExpressionSolution? {
        if (m.rows > MAX_REDUCE_ROWS || m.cols > MAX_REDUCE_COLUMNS) return null
        val start = numbers(m) ?: return null
        val reduction = reduce(start, m.cols)
        if (reduction.operations.isEmpty()) return null
        val solution = Solver(context)
        solution.start(normalize(problem))
        val matrix = matrixOf(start)
        solution.apply(Match(StepRule.ROW_REDUCE_BEGIN, mapOf(emptyList<Int>() to matrix), problem, matrix, showChange = false, boxReplacements = false))
        val rank = Note(TextExpression(Texts.format(Text.RANK, reduction.rank)), TextExpression(""), relation = "")
        showOperations(solution, emptyList(), reduction.operations, rank)
        return solution.finish(matrixOf(reduction.rows))
    }

    // ----- eigenvalues -----

    /**
     * The eigenvalues of a square matrix of numbers: the zeros of det(A − λ·I), expanded and solved, and for each rational one the eigenvector,
     * from the row reduction of A − λ·I. Null if the polynomial cannot be solved exactly (or has no real zero).
     */
    private fun eigenSteps(problem: Expression, m: MatrixExpression, context: MathContext): ExpressionSolution? {
        val n = m.rows
        if (!m.isSquare || n !in 2..MAX_EIGEN_SIZE) return null
        val a = numbers(m) ?: return null
        val lambda = Variable("λ")
        val polynomial = characteristicPolynomial(a)
        val shifted = MatrixExpression(n, n, (0 until n).flatMap { r ->
            (0 until n).map { c ->
                val entry = Value(a[r][c])
                when {
                    r != c -> entry
                    a[r][c].isZero -> Multiplications(Value.MINUS_ONE, lambda)
                    else -> Additions(entry, Multiplications(Value.MINUS_ONE, lambda))
                }
            }
        })
        val equation = Equals(Determinant(shifted), Value.ZERO)

        // 1. det(A − λI) = 0, worked out like any determinant, then written as a polynomial
        val first = Solver(context)
        first.start(normalize(problem))
        first.apply(Match(StepRule.EIGEN_CHARACTERISTIC, mapOf(emptyList<Int>() to equation), problem, equation, showChange = false, boxReplacements = false))
        var operations = 0
        while (true) {
            if (context.shouldStop) return null
            val path = pathsOf(first.current) { isReady(unmarked(it)) }.firstOrNull() ?: break
            if (++operations > MAX_OPERATIONS || !apply(first, path, context)) return null
        }
        val target = PartialFractions.toExpression(polynomial, lambda)
        val left = listOf(0)
        var guard = 0
        while (true) {
            if (context.shouldStop || ++guard > MAX_SIMPLIFY) return null
            val match = SimplificationSteps.match(at(first.current, left), target, context) ?: break
            first.apply(EquationSteps.prefixed(match, left))
        }
        if (writtenForm(at(first.current, left)) != writtenForm(target)) {
            first.apply(Match(StepRule.SIMPLIFY, mapOf(left to target), normalize(unmarked(at(first.current, left))), target))
        }
        val lines = first.finish(first.current).steps.toMutableList()

        // 2. The zeros
        val characteristic = Equals(target, Value.ZERO)
        val solved = EquationSteps.steps(characteristic, lambda, context)
        val values: List<Expression>
        if (solved != null) {
            values = solutionValues(solved.result, lambda) ?: run {
                if (solved.steps.any { it.rule == StepRule.NO_REAL_SOLUTION }) context.stopWithError(MathIssue.NO_REAL_SOLUTION)
                return null
            }
            val nested = solved.steps
            lines.addAll(if (writtenForm(nested.first().expression) == writtenForm(lines.last().expression)) nested.drop(1) else nested)
        } else {
            val scratch = context.copy()
            val roots = characteristic.solveForVariable(lambda, scratch)
            if (scratch.issues.contains(MathIssue.NO_REAL_SOLUTION)) context.stopWithError(MathIssue.NO_REAL_SOLUTION)
            if (scratch.shouldStop || scratch.issues.isNotEmpty()) return null
            values = roots.filter { it.variables().isEmpty() && (context.complexNumbers || !it.containsImaginaryUnit()) }.sortedBy { it.evaluateNumerically() }
            if (values.isEmpty() || values.size != roots.size) return null
            lines.add(ExpressionStep(if (values.size == 1) Equals(lambda, values.single()) else Alternatives(values.map { Equals(lambda, it) }, inline = true), StepRule.COLLECT_SOLUTION))
        }
        if (values.isEmpty() || values.any { it.variables().isNotEmpty() || (it.containsImaginaryUnit() && !context.complexNumbers) }) return null
        val sorted = values.sortedBy { it.evaluateNumerically() }
        // The steps must end at the calculator's result
        val expected = eigenvalueList(a, lambda, context.copy()) ?: return null
        if (expected.size != sorted.size || expected.indices.any { Math.abs(expected[it].evaluateNumerically() - sorted[it].evaluateNumerically()) > 1e-9 * maxOf(1.0, Math.abs(expected[it].evaluateNumerically())) }) return null

        // 3. The eigenvector of each rational eigenvalue
        val results = mutableListOf<Expression>()
        for (value in sorted) {
            val rational = (value as? Value)?.rational
            if (rational == null) {
                results.add(Equals(lambda, value))
                continue
            }
            val vectors = eigenvectors(a, rational, lambda, context, lines) ?: return null
            results.add(vectors)
        }
        val result: Expression = if (results.size == 1) results.single() else Alternatives(results)
        lines.add(ExpressionStep(result, StepRule.EIGEN_RESULT))
        return ExpressionSolution(lines, result)
    }

    /**
     * eig(A) as a result: λ = 3, v = (1, 1) for each eigenvalue (the vector only for the exact ones). Null, with the issue reported,
     * if A is not a square matrix of numbers or the eigenvalues cannot be found. Independent of the steps, which have limits of their own.
     */
    internal fun eigenSystem(m: MatrixExpression, context: MathContext): Expression? {
        if (!m.isSquare) {
            context.stopWithError(MathIssue.MATRIX_NOT_SQUARE)
            return null
        }
        val a = numbers(m)
        if (a == null) {
            context.stopWithError(MathIssue.MATRIX_NEEDS_NUMBERS)
            return null
        }
        if (m.rows > MAX_EIGEN_RESULT_SIZE) {
            context.stopWithError(MathIssue.MATRIX_UNSUPPORTED)
            return null
        }
        val lambda = Variable("λ")
        if (m.rows == 1) return eigenLine(a[0][0].let { Value(it) }, listOf(listOf(Rational.ONE)), lambda)
        val values = eigenvalueList(a, lambda, context) ?: return null
        val results = values.map { value ->
            val rational = (value as? Value)?.rational
            val vectors = rational?.let { nullVectors(a, it) }
            if (vectors == null) Equals(lambda, value) else eigenLine(value, vectors, lambda)
        }
        return if (results.size == 1) results.single() else Alternatives(results)
    }

    /** The zeros of det(A − λI), sorted; null, with the issue reported, if there is none or they cannot be found. */
    private fun eigenvalueList(a: List<List<Rational>>, lambda: Variable, context: MathContext): List<Expression>? {
        val equation = Equals(PartialFractions.toExpression(characteristicPolynomial(a), lambda), Value.ZERO)
        val scratch = context.copy()
        val roots = equation.solveForVariable(lambda, scratch)
        if (context.shouldStop) return null
        if (scratch.issues.contains(MathIssue.NO_REAL_SOLUTION)) {
            context.stopWithError(MathIssue.NO_EIGENVALUES)
            return null
        }
        val values = roots.filter { it.variables().isEmpty() && (context.complexNumbers || !it.containsImaginaryUnit()) }
        if (scratch.shouldStop || scratch.issues.isNotEmpty() || roots.isEmpty() || values.size != roots.size) {
            context.stopWithError(MathIssue.EIGENVALUES_NOT_EXACT)
            return null
        }
        return values.sortedBy { it.evaluateNumerically() }
    }

    /** λ = 3, v = (1, 1): the eigenvalue with its eigenvectors (v₁, v₂ if there are several). */
    private fun eigenLine(value: Expression, vectors: List<List<Rational>>, lambda: Variable): Expression {
        val named = vectors.mapIndexed { i, v ->
            Equals(Variable(if (vectors.size == 1) "v" else "v${subscript(i)}"), MatrixExpression(v.size, 1, v.map { Value(it) }))
        }
        return Alternatives(listOf<Expression>(Equals(lambda, value)) + named, inline = true, separator = ",   ")
    }

    /** The eigenvectors of [value]: a basis of the solutions of (A − λI)·v = 0 as whole numbers, a free variable 1 and the others 0; null if there are none. */
    private fun nullVectors(a: List<List<Rational>>, value: Rational): List<List<Rational>>? {
        val n = a.size
        val reduction = reduce(List(n) { r -> List(n) { c -> if (r == c) a[r][c] - value else a[r][c] } }, n)
        if (reduction.rank >= n) return null
        return (0 until n).filter { it !in reduction.pivotColumns }.map { f ->
            val v = MutableList(n) { Rational.ZERO }
            v[f] = Rational.ONE
            for ((i, p) in reduction.pivotColumns.withIndex()) v[p] = -reduction.rows[i][f]
            integers(v)
        }
    }

    /** The coefficients c₀ + c₁λ + … + λⁿ of det(λI − A) (which is det(A − λI) for an even n, its negative for an odd one), by the Faddeev-LeVerrier method. */
    private fun characteristicPolynomial(a: List<List<Rational>>): List<Rational> {
        val n = a.size
        // det(λI − A): c[n] = 1, c[n−k] = −tr(A·Mₖ)/k
        var m = List(n) { r -> List(n) { c -> if (r == c) Rational.ONE else Rational.ZERO } }
        val coefficients = MutableList(n + 1) { Rational.ZERO }
        coefficients[n] = Rational.ONE
        for (k in 1..n) {
            val product = List(n) { r -> List(n) { c -> (0 until n).fold(Rational.ZERO) { sum, i -> sum + a[r][i] * m[i][c] } } }
            val trace = (0 until n).fold(Rational.ZERO) { sum, i -> sum + product[i][i] }
            val c = -trace / Rational.of(k.toLong())
            coefficients[n - k] = c
            m = List(n) { r -> List(n) { col -> product[r][col] + if (r == col) c else Rational.ZERO } }
        }
        // det(A − λI) = (−1)ⁿ·det(λI − A)
        return if (n % 2 == 0) coefficients else coefficients.map { -it }
    }

    private fun solutionValues(result: Expression, v: Variable): List<Expression>? = when (result) {
        is Equals -> if (result.left == v) listOf(result.right) else null
        is Alternatives -> result.equations.map { (it as? Equals)?.takeIf { e -> e.left == v }?.right ?: return null }
        else -> null
    }

    /** The steps for the eigenvectors of [value] added to [lines]; the line "λ = 3, v = (1, 1)" of them (null if there is no eigenvector). */
    private fun eigenvectors(a: List<List<Rational>>, value: Rational, lambda: Variable, context: MathContext, lines: MutableList<ExpressionStep>): Expression? {
        val n = a.size
        val shifted = List(n) { r -> List(n) { c -> if (r == c) a[r][c] - value else a[r][c] } }
        val reduction = reduce(shifted, n)
        if (reduction.rank >= n) return null
        val matrix = matrixOf(shifted)
        val solution = Solver(context)
        solution.start(matrix)
        showOperations(solution, emptyList(), reduction.operations)
        val steps = solution.finish(solution.current).steps
        val head = steps.first()
        val note = Note(Equals(lambda, Value(value)), TextExpression(""), relation = "")
        lines.add(ExpressionStep(head.expression, StepRule.EIGEN_VECTOR, head.chainRule, head.change, listOf(note), head.operation))
        lines.addAll(steps.drop(1))

        val vectors = nullVectors(a, value) ?: return null
        val line = eigenLine(Value(value), vectors, lambda)
        lines.add(ExpressionStep(line, StepRule.EIGEN_VECTOR_RESULT))
        return line
    }

    /** (1/2, 1, 0) as (1, 2, 0): the smallest whole numbers in the same direction. */
    private fun integers(v: List<Rational>): List<Rational> {
        val scale = v.fold(com.vortessence.arithmeticus.core.math.numbers.BigInt.ONE) { l, x -> l * x.denominator / l.gcd(x.denominator) }
        return v.map { it * Rational.of(scale) }
    }

    // ----- products -----

    private fun product(solution: Solver, path: Path, node: Multiplications, context: MathContext): Boolean {
        val operands = node.operands
        val matrixIndices = operands.indices.filter { operands[it] is MatrixExpression }
        if (matrixIndices.size >= 2) {
            val i = matrixIndices[0]
            val j = matrixIndices[1]
            val a = operands[i] as MatrixExpression
            val b = operands[j] as MatrixExpression
            if (a.cols != b.rows) return false
            val ea = entries(a)
            val eb = entries(b)
            val elements = (0 until a.rows).flatMap { r ->
                (0 until b.cols).map { c ->
                    val terms = (0 until a.cols).map { k -> Multiplications(ea[r * a.cols + k], eb[k * b.cols + c]) as Expression }
                    if (terms.size == 1) terms.single() else Additions(terms)
                }
            }
            val result = MatrixExpression(a.rows, b.cols, elements)
            val remaining = operands.toMutableList()
            remaining[i] = result
            remaining.removeAt(j)
            val shown: Expression = if (remaining.size == 1) remaining.single() else Multiplications(remaining)
            replace(solution, path, StepRule.MATRIX_MULTIPLY, node, shown)
            return simplifyAt(solution, path, context)
        }
        // A number (or letters) times a matrix: every entry
        val i = matrixIndices.single()
        val m = operands[i] as MatrixExpression
        val scalars = operands.filterIndexed { index, _ -> index != i }
        val scalar: Expression = if (scalars.size == 1) scalars.single() else Multiplications(scalars)
        val result = MatrixExpression(m.rows, m.cols, entries(m).map { Multiplications(scalar, it) })
        replace(solution, path, StepRule.MATRIX_SCALAR, node, result)
        return simplifyAt(solution, path, context)
    }

    // ----- sums -----

    private fun sum(solution: Solver, path: Path, node: Additions, context: MathContext): Boolean {
        val operands = node.operands.map { negatedMatrix(it) ?: it as MatrixExpression }
        val signs = node.operands.map { if (negatedMatrix(it) != null) -1 else 1 }
        val first = operands.first()
        if (operands.any { it.rows != first.rows || it.cols != first.cols }) return false
        val all = operands.map { entries(it) }
        val elements = first.elements.indices.map { i ->
            Additions(operands.indices.map { k -> if (signs[k] > 0) all[k][i] else Multiplications(Value.MINUS_ONE, all[k][i]) as Expression })
        }
        val result = MatrixExpression(first.rows, first.cols, elements)
        replace(solution, path, if (signs.drop(1).any { it < 0 }) StepRule.MATRIX_SUBTRACT else StepRule.MATRIX_ADD, node, result)
        return simplifyAt(solution, path, context)
    }

    // ----- calculating the entries -----

    /** The arithmetic inside [path] done step by step; false if it takes too long. */
    private fun simplifyAt(solution: Solver, path: Path, context: MathContext): Boolean {
        repeat(MAX_SIMPLIFY) {
            if (context.shouldStop) return false
            val match = SimplificationSteps.match(at(solution.current, path), Value.ZERO, context, arithmetic = true) ?: return true
            solution.apply(EquationSteps.prefixed(match, path))
        }
        // Too many steps for a list: the rest in one
        val rest = normalize(unmarked(at(solution.current, path)))
        val scratch = context.copy()
        val value = rest.canonicalize(scratch)
        if (scratch.shouldStop) return false
        solution.apply(Match(StepRule.SIMPLIFY, mapOf(path to value), rest, value))
        return true
    }

    private fun sameValue(a: Expression, b: Expression): Boolean {
        if (a is MatrixExpression && b is MatrixExpression) {
            return a.rows == b.rows && a.cols == b.cols && a.elements.indices.all { sameValue(a.elements[it], b.elements[it]) }
        }
        if (a is MatrixExpression || b is MatrixExpression) return false
        val variables = (a.variables() + b.variables()).toList()
        var checked = 0
        for ((i, _) in SAMPLES.withIndex()) {
            val bindings = variables.withIndex().associate { (j, v) -> v to SAMPLES[(i + 2 * j) % SAMPLES.size] }
            val l = a.evaluateNumerically(bindings)
            val r = b.evaluateNumerically(bindings)
            if (!l.isFinite() || !r.isFinite()) continue
            if (abs(l - r) > 1e-9 * max(1.0, max(abs(l), abs(r)))) return false
            checked++
            if (variables.isEmpty()) break
        }
        return checked >= 1
    }

    private val SAMPLES = listOf(0.37, 1.13, 2.71, -0.83, 0.52, -1.9, 3.3)
}
