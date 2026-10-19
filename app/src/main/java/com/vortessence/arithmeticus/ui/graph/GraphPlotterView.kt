package com.vortessence.arithmeticus.ui.graph

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AreaChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.arithmeticus.core.math.MathEngine
import com.vortessence.arithmeticus.core.math.context.AngleUnit
import com.vortessence.arithmeticus.core.math.PlotData
import com.vortessence.arithmeticus.core.math.PlotPoint
import com.vortessence.arithmeticus.core.math.PlotSegment
import com.vortessence.arithmeticus.core.math.solvers.Function1D
import com.vortessence.arithmeticus.core.math.solvers.ImplicitCurve
import com.vortessence.arithmeticus.core.math.solvers.InequalityPlot
import com.vortessence.arithmeticus.core.math.solvers.PlotEntry
import com.vortessence.arithmeticus.core.math.solvers.PlotIntersection
import com.vortessence.arithmeticus.core.math.solvers.PlotScene
import com.vortessence.arithmeticus.core.math.type.isInequality
import com.vortessence.arithmeticus.core.math.solvers.PlotCurve
import com.vortessence.arithmeticus.core.math.solvers.Surface3D
import com.vortessence.arithmeticus.core.math.type.MathOp
import com.vortessence.arithmeticus.core.math.type.MathString
import com.vortessence.arithmeticus.core.math.type.OperatorNode
import com.vortessence.arithmeticus.core.math.type.VariableNode
import com.vortessence.arithmeticus.ui.math.DecimalMark
import com.vortessence.arithmeticus.ui.math.MathExpressionView
import com.vortessence.arithmeticus.ui.theme.AccentBlue
import com.vortessence.arithmeticus.ui.theme.AccentGreen
import com.vortessence.arithmeticus.ui.theme.AccentPurple
import com.vortessence.arithmeticus.ui.theme.AccentRed
import com.vortessence.arithmeticus.ui.theme.OrangePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.*
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vortessence.arithmeticus.R
import com.vortessence.arithmeticus.core.math.i18n.Language
import com.vortessence.arithmeticus.core.math.i18n.Text
import com.vortessence.arithmeticus.core.math.i18n.Texts

private const val DEFAULT_SCALE = 40f
private const val MIN_SCALE = 5f
private const val MAX_SCALE = 400f

// On a logarithmic axis the scale is in pixels per decade
private const val DEFAULT_LOG_DECADES = 5f
private const val MIN_LOG_SCALE = 30f
private const val MAX_LOG_SCALE = 20000f

// A polar curve r = f(θ) is sampled once over a full turn, finely enough to stay smooth when zoomed in
private const val POLAR_SAMPLES = 2880

/**
 * The colours of the curves, by the position of their line (so a curve keeps its colour when another is hidden): a categorical
 * palette checked for colour-blind readers, with the steps made for dark surfaces. The legend names every curve, so colour is
 * never the only way to tell them apart.
 */
private val CURVE_COLORS_LIGHT = listOf(0xFFEB6834, 0xFF2A78D6, 0xFF1BAF7A, 0xFF6250D6, 0xFFE87BA4, 0xFFEDA100, 0xFF008300, 0xFFE34948).map { Color(it) }
private val CURVE_COLORS_DARK = listOf(0xFFD95926, 0xFF3987E5, 0xFF199E70, 0xFF9085E9, 0xFFD55181, 0xFFC98500, 0xFF008300, 0xFFE66767).map { Color(it) }

/** Whether this is the same crossing as [other], which was found for another part of the graph (so it may differ in the last digits). */
private fun PlotIntersection.sameAs(other: PlotIntersection): Boolean =
    first == other.first && second == other.second && abs(x - other.x) <= 1e-6 * max(1.0, abs(other.x)) && abs(y - other.y) <= 1e-6 * max(1.0, abs(other.y))

/** The key of the region where all inequalities hold, among the passive data of the curves (which are keyed by line). */
private const val COMMON_REGION = -1

/** The curves are drawn from the moment they are known; the cells and segments of the ones that are not the active curve. */
private class PassiveCurve(val segments: List<PlotSegment>, val shade: ShadeGrid?)

private enum class GraphTool(@StringRes val label: Int, val icon: ImageVector) {
    TRACE(R.string.graph_tool_trace, Icons.Default.Timeline),
    TANGENT(R.string.graph_tool_tangent, TangentIcon),
    INTEGRAL(R.string.graph_tool_integral, Icons.Default.AreaChart)
}

/** A curve with a straight line touching it at one marked point; Material has no tangent icon. */
private val TangentIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Tangent", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            // The parabola y = 8.4 + 0.2 (x - 12)² from x = 5 to 19 (screen coordinates, so a hill)
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(5f, 18.2f)
                quadTo(12f, -1.4f, 19f, 18.2f)
            }
            // Its tangent at x = 11.3, just left of the top, so it is only slightly tilted
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(3f, 10.82f)
                lineTo(21f, 5.78f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13.5f, 8.5f)
                arcTo(2.2f, 2.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 9.1f, y1 = 8.5f)
                arcTo(2.2f, 2.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 13.5f, y1 = 8.5f)
                close()
            }
        }
        .build()
}

/** What a touch grabbed: the trace/tangent cursor or one of the integration bounds. */
private enum class GraphHandle { CURSOR, LOWER_BOUND, UPPER_BOUND }

private enum class KeyPointKind(@StringRes val label: Int, val color: Color) {
    ZERO(R.string.graph_point_zero, AccentRed),
    MAXIMUM(R.string.graph_point_maximum, AccentGreen),
    MINIMUM(R.string.graph_point_minimum, AccentBlue),
    INFLECTION(R.string.graph_point_inflection, AccentPurple),
    // Implicit curves only; they have no inflection points, so the colour is free
    Y_INTERCEPT(R.string.graph_point_y_intercept, AccentPurple)
}

private data class KeyPoint(val x: Double, val y: Double, val kind: KeyPointKind)

private fun PlotData.toKeyPoints(): List<KeyPoint> =
    roots.map { KeyPoint(it, 0.0, KeyPointKind.ZERO) } +
        maxima.map { KeyPoint(it.x, it.y, KeyPointKind.MAXIMUM) } +
        minima.map { KeyPoint(it.x, it.y, KeyPointKind.MINIMUM) } +
        inflectionPoints.map { KeyPoint(it.x, it.y, KeyPointKind.INFLECTION) } +
        yIntercepts.map { KeyPoint(0.0, it, KeyPointKind.Y_INTERCEPT) }

/**
 * Maps between math coordinates and canvas pixels. On a logarithmic axis, origin and scale
 * apply to log10 of the value; zero and negative values have no position there (NaN).
 */
private class Viewport(
    val originX: Float,
    val originY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val logX: Boolean = false,
    val logY: Boolean = false
) {
    private fun toAxis(value: Double, log: Boolean) = if (!log) value else if (value > 0) log10(value) else Double.NaN
    private fun fromAxis(u: Double, log: Boolean) = if (log) 10.0.pow(u) else u

    fun toScreenX(x: Double): Float = originX + (toAxis(x, logX) * scaleX).toFloat()
    fun toScreenY(y: Double): Float = originY - (toAxis(y, logY) * scaleY).toFloat()
    fun toMathX(px: Float): Double = fromAxis(((px - originX) / scaleX).toDouble(), logX)
    fun toMathY(py: Float): Double = fromAxis(((originY - py) / scaleY).toDouble(), logY)
    fun toScreen(point: PlotPoint) = Offset(toScreenX(point.x), toScreenY(point.y))

    /** Screen position of the line x = 0 / y = 0, or null on a log axis, which has no zero. */
    val zeroX: Float? get() = if (logX) null else originX
    val zeroY: Float? get() = if (logY) null else originY
}

@Composable
fun GraphPlotterView(
    scene: PlotScene?,
    /** What was typed: shown in the header until the curves are known. */
    expression: MathString,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onAddLine: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    if (onClose != null) BackHandler(onBack = onClose)

    val entries = scene?.entries.orEmpty()

    // A surface z = f(x, y) has its own 3D view without the 2D tools, and cannot be drawn with other curves
    val surfaceEntry = scene?.surfaceEntry
    if (surfaceEntry != null) {
        SurfaceGraph(surfaceEntry.curve as Surface3D, surfaceEntry.line, alone = scene.entries.count { !it.isDefinition } == 1, modifier, onClose)
        return
    }

    // The curves in the order of the lines (with their index among all the lines); some may be hidden by the legend
    val curveEntries = remember(scene) { entries.withIndex().filter { it.value.curve != null } }
    var hidden by remember(scene) { mutableStateOf(emptySet<Int>()) }
    // The curve the tools work on: the first one until another is chosen
    var activeIndex by remember(scene) { mutableIntStateOf(curveEntries.firstOrNull()?.index ?: -1) }
    val visibleEntries = curveEntries.filter { it.index !in hidden }
    val curve: PlotCurve? = if (activeIndex in entries.indices && activeIndex !in hidden) entries[activeIndex].curve else null

    fun toggleHidden(index: Int) {
        val nowHidden = index !in hidden
        hidden = if (nowHidden) hidden + index else hidden - index
        // The tools need a curve that is shown: the next one takes over from a curve that was hidden
        if (nowHidden && index == activeIndex) activeIndex = curveEntries.firstOrNull { it.index !in hidden }?.index ?: -1
        else if (!nowHidden && (activeIndex == -1 || activeIndex in hidden)) activeIndex = index
    }

    // A function y = f(x) supports all tools; an implicit curve like x² + y² = 1 has no
    // integral and its cursor moves freely along the curve instead of left to right
    // An inequality is shaded; its boundary (if it has one) is what the tools work on
    val region = curve as? InequalityPlot
    val base: PlotCurve? = if (region != null) region.primary else curve
    val function = base as? Function1D
    val implicit = base as? ImplicitCurve

    var scaleX by remember { mutableFloatStateOf(DEFAULT_SCALE) } // pixels per unit
    var scaleY by remember { mutableFloatStateOf(DEFAULT_SCALE) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Deliberately not saved: a forgotten log axis would be confusing on the next graph
    var logX by remember { mutableStateOf(false) }
    var logY by remember { mutableStateOf(false) }
    // Draws a function as r = f(θ) on a polar grid; implicit curves stay Cartesian
    var polarSelected by remember { mutableStateOf(false) }
    val polar = polarSelected && function != null && visibleEntries.size == 1

    var tool by remember { mutableStateOf<GraphTool?>(null) }
    // Back leaves the active tool before it closes the graph
    BackHandler(enabled = tool != null) { tool = null }

    // Shared by trace and tangent, so switching between them keeps the position
    var cursorX by remember { mutableDoubleStateOf(0.0) }
    // Cursor on an implicit curve; null until placed on it
    var cursorPoint by remember(curve) { mutableStateOf<PlotPoint?>(null) }
    var lowerBound by remember { mutableDoubleStateOf(-1.0) }
    var upperBound by remember { mutableDoubleStateOf(1.0) }
    var keyPoints by remember(curve) { mutableStateOf(emptyList<KeyPoint>()) }
    // Null while the first value is computed; afterwards the previous value stays while dragging
    var area by remember(function) { mutableStateOf<Double?>(null) }
    // Implicit curves are drawn from line segments computed in the background
    var segments by remember(curve) { mutableStateOf(emptyList<PlotSegment>()) }
    // The cells where an inequality holds, over the visible area plus a margin
    var shade by remember(curve) { mutableStateOf<ShadeGrid?>(null) }
    // Polar curves: Cartesian points of one full turn, independent of the viewport; NaN marks gaps
    var polarPoints by remember(function) { mutableStateOf(emptyList<PlotPoint>()) }
    // The curves that are not the active one: their segments and shading, by the index of their line
    var passive by remember(scene) { mutableStateOf(emptyMap<Int, PassiveCurve>()) }
    // Where the shown curves cross, and the crossing that was tapped
    var crossings by remember(scene) { mutableStateOf(emptyList<PlotIntersection>()) }
    var selectedCrossing by remember(scene) { mutableStateOf<PlotIntersection?>(null) }

    fun viewport() = Viewport(canvasSize.width / 2f + offsetX, canvasSize.height / 2f + offsetY, scaleX, scaleY, logX, logY)

    // Zeros, extrema and inflection points of the visible x-range, recomputed in the background
    // after panning or zooming settles. Integration bounds snap to the zeros.
    val visibleViewport = viewport()
    val visibleMinX = visibleViewport.toMathX(0f)
    val visibleMaxX = visibleViewport.toMathX(canvasSize.width.toFloat())
    val visibleMinY = visibleViewport.toMathY(canvasSize.height.toFloat())
    val visibleMaxY = visibleViewport.toMathY(0f)
    val needsKeyPoints = tool == GraphTool.TRACE || tool == GraphTool.INTEGRAL
    LaunchedEffect(function, visibleMinX, visibleMaxX, needsKeyPoints) {
        if (function == null || canvasSize.width == 0 || !needsKeyPoints) return@LaunchedEffect
        delay(150)
        keyPoints = if (logX) {
            // Even sampling would skip the small values of a log axis, so analyse decade by decade
            val low = log10(visibleMinX)
            val high = log10(visibleMaxX)
            val pieces = ceil(high - low).toInt().coerceIn(1, 40)
            (0 until pieces).flatMap { i ->
                val from = 10.0.pow(low + (high - low) * i / pieces)
                val to = 10.0.pow(low + (high - low) * (i + 1) / pieces)
                MathEngine.analyzePlot(function, from, to, steps = 200).toKeyPoints()
            }
        } else {
            MathEngine.analyzePlot(function, visibleMinX, visibleMaxX).toKeyPoints()
        }
    }

    val density = LocalDensity.current
    val touchRadiusPx = with(density) { 28.dp.toPx() }
    val snapRadiusPx = with(density) { 14.dp.toPx() }
    val contourCellPx = with(density) { 4.dp.toPx() }
    // Finer where no boundary curve is drawn over the edge (several inequalities), and in one variable, where a column costs next to nothing
    val shadeCellPx = with(density) { (if (region?.twoVariables == false) 1.2.dp else if (region?.primary == null) 1.5.dp else 2.5.dp).toPx() }

    // Implicit curves: segments for the visible area plus a margin, so that short pans do not
    // reveal gaps before the recomputation after the gesture settles
    /** The visible range extended by a quarter of the canvas on each side: minX, maxX, minY, maxY. */
    fun rangeWithMargin(): List<Double> {
        val vp = viewport()
        val width = canvasSize.width.toFloat()
        val height = canvasSize.height.toFloat()
        return listOf(vp.toMathX(-width / 4f), vp.toMathX(width * 1.25f), vp.toMathY(height * 1.25f), vp.toMathY(-height / 4f))
    }

    LaunchedEffect(implicit, visibleMinX, visibleMaxX, visibleMinY, visibleMaxY, logX, logY) {
        if (implicit == null || canvasSize.width == 0) return@LaunchedEffect
        if (segments.isNotEmpty()) delay(100)
        val (minX, maxX, minY, maxY) = rangeWithMargin()
        segments = MathEngine.contourImplicit(
            implicit, minX, maxX, minY, maxY,
            cols = (canvasSize.width * 1.5f / contourCellPx).toInt().coerceAtLeast(10),
            rows = (canvasSize.height * 1.5f / contourCellPx).toInt().coerceAtLeast(10),
            logX = logX,
            logY = logY
        )
    }

    LaunchedEffect(region, visibleMinX, visibleMaxX, visibleMinY, visibleMaxY, logX, logY) {
        if (region == null || canvasSize.width == 0 || logX || logY) {
            shade = null
            return@LaunchedEffect
        }
        if (shade != null) delay(100)
        val (minX, maxX, minY, maxY) = rangeWithMargin()
        val cols = (canvasSize.width * 1.5f / shadeCellPx).toInt().coerceIn(10, 700)
        val rows = if (region.twoVariables) (canvasSize.height * 1.5f / shadeCellPx).toInt().coerceIn(10, 700) else 1
        shade = withContext(Dispatchers.Default) { ShadeGrid(minX, maxX, minY, maxY, cols, rows, region.shade(minX, maxX, minY, maxY, cols, rows)) }
    }

    LaunchedEffect(implicit, segments, tool == GraphTool.TRACE) {
        if (implicit == null || tool != GraphTool.TRACE || segments.isEmpty()) return@LaunchedEffect
        val (minX, maxX, minY, maxY) = rangeWithMargin()
        keyPoints = MathEngine.analyzeImplicitPlot(implicit, segments, minX, maxX, minY, maxY).toKeyPoints()
        // The cursor may have been placed before the points were known; snap it now
        val vp = viewport()
        cursorPoint?.let { p ->
            keyPoints.firstOrNull { (vp.toScreen(PlotPoint(it.x, it.y)) - vp.toScreen(p)).getDistance() <= snapRadiusPx }
                ?.let { cursorPoint = PlotPoint(it.x, it.y) }
        }
    }

    LaunchedEffect(function, polar) {
        if (function == null || !polar || polarPoints.isNotEmpty()) return@LaunchedEffect
        polarPoints = withContext(Dispatchers.Default) { samplePolarCurve(function) }
    }

    // The curves besides the active one: lines of the implicit curves and cells of the inequalities, for the area on screen plus a margin
    LaunchedEffect(scene, hidden, activeIndex, visibleMinX, visibleMaxX, visibleMinY, visibleMaxY, logX, logY) {
        val others = visibleEntries.filter { it.index != activeIndex }
        val common = scene?.commonRegion(hidden)
        if (canvasSize.width == 0 || (others.isEmpty() && common == null)) {
            passive = emptyMap()
            return@LaunchedEffect
        }
        if (passive.isNotEmpty()) delay(100)
        val (minX, maxX, minY, maxY) = rangeWithMargin()
        val result = mutableMapOf<Int, PassiveCurve>()
        // Where all the inequalities hold together
        if (common != null && !logX && !logY) {
            val cellPx = with(density) { (if (common.twoVariables) 2.5.dp else 1.2.dp).toPx() }
            val cols = (canvasSize.width * 1.5f / cellPx).toInt().coerceIn(10, 700)
            val rows = if (common.twoVariables) (canvasSize.height * 1.5f / cellPx).toInt().coerceIn(10, 700) else 1
            val grid = withContext(Dispatchers.Default) { ShadeGrid(minX, maxX, minY, maxY, cols, rows, common.shade(minX, maxX, minY, maxY, cols, rows)) }
            result[COMMON_REGION] = PassiveCurve(emptyList(), grid)
        }
        for (entry in others) {
            val shown = entry.value.curve
            val inequality = shown as? InequalityPlot
            val implicitCurve = (inequality?.primary ?: shown) as? ImplicitCurve
            val lines = if (implicitCurve == null) emptyList() else MathEngine.contourImplicit(
                implicitCurve, minX, maxX, minY, maxY,
                cols = (canvasSize.width * 1.5f / contourCellPx).toInt().coerceAtLeast(10),
                rows = (canvasSize.height * 1.5f / contourCellPx).toInt().coerceAtLeast(10),
                logX = logX,
                logY = logY
            )
            val cells = if (inequality == null || logX || logY) null else {
                val cellPx = with(density) { (if (inequality.twoVariables) 2.5.dp else 1.2.dp).toPx() }
                val cols = (canvasSize.width * 1.5f / cellPx).toInt().coerceIn(10, 700)
                val rows = if (inequality.twoVariables) (canvasSize.height * 1.5f / cellPx).toInt().coerceIn(10, 700) else 1
                withContext(Dispatchers.Default) { ShadeGrid(minX, maxX, minY, maxY, cols, rows, inequality.shade(minX, maxX, minY, maxY, cols, rows)) }
            }
            result[entry.index] = PassiveCurve(lines, cells)
        }
        passive = result
    }

    // Where the shown curves cross, for the area on screen plus a margin
    LaunchedEffect(scene, hidden, visibleMinX, visibleMaxX, visibleMinY, visibleMaxY, logX) {
        if (canvasSize.width == 0 || visibleEntries.size < 2) {
            crossings = emptyList()
            selectedCrossing = null
            return@LaunchedEffect
        }
        delay(150)
        val shown = entries.mapIndexed { index, entry -> if (index in hidden) null else entry.curve }
        val (minX, maxX, minY, maxY) = rangeWithMargin()
        crossings = if (logX && minX > 0.0) {
            // Even sampling would skip the small values of a log axis, so look decade by decade
            val low = log10(minX)
            val high = log10(maxX)
            val pieces = ceil(high - low).toInt().coerceIn(1, 40)
            (0 until pieces).flatMap { i ->
                MathEngine.findIntersections(shown, 10.0.pow(low + (high - low) * i / pieces), 10.0.pow(low + (high - low) * (i + 1) / pieces), minY, maxY)
            }.sortedBy { it.x }.fold(emptyList<PlotIntersection>()) { kept, next ->
                // The decades overlap at their ends: the same crossing, seen twice (the tolerance is relative: x is small on a log axis)
                if (kept.any { it.first == next.first && it.second == next.second && abs(it.x - next.x) <= 1e-9 * abs(next.x) }) kept else kept + next
            }
        } else {
            MathEngine.findIntersections(shown, minX, maxX, minY, maxY)
        }
        // The selected crossing is found again (a little different in the last digits), or let go if it is gone
        selectedCrossing = selectedCrossing?.let { chosen -> crossings.firstOrNull { it.sameAs(chosen) } }
    }

    LaunchedEffect(function, tool == GraphTool.INTEGRAL, lowerBound, upperBound) {
        if (function == null || tool != GraphTool.INTEGRAL) return@LaunchedEffect
        delay(30)
        area = MathEngine.integratePlot(function, lowerBound, upperBound)
    }

    /** The crossings that lie on the active curve: where its cursor and bounds can snap to. Read when called, not when composed. */
    fun crossingsOnActive(): List<PlotIntersection> = crossings.filter { it.first == activeIndex || it.second == activeIndex }

    /** Puts the implicit-curve cursor on the curve point nearest to [position]. */
    fun moveImplicitCursor(position: Offset) {
        val curveNow = implicit ?: return
        val vp = viewport()
        // Nearest point on the drawn segments, measured on screen
        var nearest: Offset? = null
        var nearestDistance = Float.POSITIVE_INFINITY
        for (segment in segments) {
            val a = vp.toScreen(segment.start)
            val b = vp.toScreen(segment.end)
            val ab = b - a
            val lengthSquared = ab.x * ab.x + ab.y * ab.y
            val t = if (lengthSquared == 0f) 0f else
                (((position.x - a.x) * ab.x + (position.y - a.y) * ab.y) / lengthSquared).coerceIn(0f, 1f)
            val candidate = a + ab * t
            val distance = (candidate - position).getDistance()
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = candidate
            }
        }
        val onScreen = nearest ?: return
        val approximate = PlotPoint(vp.toMathX(onScreen.x), vp.toMathY(onScreen.y))
        // Keep the exact point only if it stays where the finger is (it may jump near singular points)
        var point = curveNow.projectOntoCurve(approximate.x, approximate.y)
            ?.takeIf { (vp.toScreen(it) - onScreen).getDistance() <= snapRadiusPx }
            ?: approximate
        // Trace snaps to the special points of the curve, trace and tangent to the crossings with the other curves
        if (tool == GraphTool.TRACE || tool == GraphTool.TANGENT) {
            val screenPoint = vp.toScreen(point)
            val targets = (if (tool == GraphTool.TRACE) keyPoints.map { PlotPoint(it.x, it.y) } else emptyList()) +
                crossingsOnActive().map { PlotPoint(it.x, it.y) }
            targets.minByOrNull { (vp.toScreen(it) - screenPoint).getDistance() }
                ?.takeIf { (vp.toScreen(it) - screenPoint).getDistance() <= snapRadiusPx }
                ?.let { point = it }
        }
        cursorPoint = point
    }

    fun moveHandle(handle: GraphHandle, position: Offset) {
        if (implicit != null) {
            if (handle == GraphHandle.CURSOR) moveImplicitCursor(position)
            return
        }
        val px = position.x
        val vp = viewport()
        var x = vp.toMathX(px)
        val snapTargets = when {
            handle == GraphHandle.CURSOR && tool == GraphTool.TRACE -> keyPoints
            handle != GraphHandle.CURSOR -> keyPoints.filter { it.kind == KeyPointKind.ZERO }
            else -> emptyList()
        }
        // The cursor and the bounds snap to the crossings of the active curve with the others as well
        (snapTargets.map { it.x } + crossingsOnActive().map { it.x })
            .minByOrNull { abs(vp.toScreenX(it) - px) }
            ?.takeIf { abs(vp.toScreenX(it) - px) <= snapRadiusPx }
            ?.let { x = it }
        when (handle) {
            GraphHandle.CURSOR -> cursorX = x
            GraphHandle.LOWER_BOUND -> lowerBound = x
            GraphHandle.UPPER_BOUND -> upperBound = x
        }
    }

    fun handleAt(position: Offset): GraphHandle? {
        val vp = viewport()
        if (implicit != null) {
            // The cursor can sit anywhere on the curve, so grab it by its distance in 2D
            val point = cursorPoint ?: return null
            val isNear = tool != null && tool != GraphTool.INTEGRAL &&
                (vp.toScreen(point) - position).getDistance() <= touchRadiusPx * 1.3f
            return if (isNear) GraphHandle.CURSOR else null
        }
        val candidates = when (tool) {
            GraphTool.TRACE, GraphTool.TANGENT -> listOf(GraphHandle.CURSOR to cursorX)
            GraphTool.INTEGRAL -> listOf(GraphHandle.LOWER_BOUND to lowerBound, GraphHandle.UPPER_BOUND to upperBound)
            null -> emptyList()
        }
        return candidates
            .map { (handle, x) -> handle to abs(vp.toScreenX(x) - position.x) }
            .filter { it.second <= touchRadiusPx }
            .minByOrNull { it.second }
            ?.first
    }

    /** The crossing under [position], if one is within reach of a finger. */
    fun crossingNear(position: Offset): PlotIntersection? {
        val vp = viewport()
        return crossings
            .map { it to (vp.toScreen(PlotPoint(it.x, it.y)) - position).getDistance() }
            .filter { it.second <= touchRadiusPx }
            .minByOrNull { it.second }
            ?.first
    }

    fun onTap(position: Offset) {
        when (tool) {
            GraphTool.TRACE, GraphTool.TANGENT -> moveHandle(GraphHandle.CURSOR, position)
            GraphTool.INTEGRAL -> {
                // Move whichever bound is closer to the tap
                val vp = viewport()
                val closerToLower = abs(vp.toScreenX(lowerBound) - position.x) <= abs(vp.toScreenX(upperBound) - position.x)
                moveHandle(if (closerToLower) GraphHandle.LOWER_BOUND else GraphHandle.UPPER_BOUND, position)
            }
            null -> {}
        }
    }

    /** Pans and zooms so that the point under [centroid] stays under the fingers. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScaleX = if (logX) (scaleX * zoom).coerceIn(MIN_LOG_SCALE, MAX_LOG_SCALE) else (scaleX * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val newScaleY = if (logY) (scaleY * zoom).coerceIn(MIN_LOG_SCALE, MAX_LOG_SCALE) else (scaleY * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val originX = canvasSize.width / 2f + offsetX
        val originY = canvasSize.height / 2f + offsetY
        offsetX += centroid.x - (centroid.x - originX) * (newScaleX / scaleX) - originX + pan.x
        offsetY += centroid.y - (centroid.y - originY) * (newScaleY / scaleY) - originY + pan.y
        scaleX = newScaleX
        scaleY = newScaleY
    }

    /** Brings the handles of [target] into view if they were panned away or lie off a log axis. */
    fun placeHandlesInView(target: GraphTool) {
        val vp = viewport()
        val width = canvasSize.width.toFloat()
        val height = canvasSize.height.toFloat()
        val minX = vp.toMathX(0f)
        val maxX = vp.toMathX(width)
        when (target) {
            GraphTool.TRACE, GraphTool.TANGENT -> if (implicit != null) {
                val point = cursorPoint
                if (point == null || point.x !in minX..maxX || point.y !in vp.toMathY(height)..vp.toMathY(0f)) {
                    moveImplicitCursor(Offset(width / 2f, height / 2f))
                }
            } else if (cursorX !in minX..maxX) {
                cursorX = vp.toMathX(width / 2f)
            }
            GraphTool.INTEGRAL ->
                if (lowerBound !in minX..maxX || upperBound !in minX..maxX) {
                    lowerBound = vp.toMathX(width / 4f)
                    upperBound = vp.toMathX(width * 3f / 4f)
                }
        }
    }

    fun selectTool(selected: GraphTool) {
        if (tool == selected) {
            tool = null
            return
        }
        placeHandlesInView(selected)
        tool = selected
    }

    // A log axis starts out showing five decades around 1 to 1000; a linear one the default zoom
    fun resetX() {
        scaleX = if (logX) canvasSize.width / DEFAULT_LOG_DECADES else DEFAULT_SCALE
        offsetX = if (logX) -1.5f * scaleX else 0f
    }

    fun resetY() {
        scaleY = if (logY) canvasSize.height / DEFAULT_LOG_DECADES else DEFAULT_SCALE
        offsetY = if (logY) 1.5f * scaleY else 0f
    }

    fun setLogScale(xAxis: Boolean, enabled: Boolean) {
        if (xAxis) {
            logX = enabled
            resetX()
        } else {
            logY = enabled
            resetY()
        }
        tool?.let { placeHandlesInView(it) }
    }

    // Polar mode needs equal scales for round circles and has no log axes or tools
    fun setPolar(enabled: Boolean) {
        polarSelected = enabled
        if (enabled) {
            logX = false
            logY = false
            tool = null
        }
        resetX()
        resetY()
    }

    // Another curve took over (a tap on the legend, or the active one was hidden): the tool and its handles have to fit it
    LaunchedEffect(activeIndex) {
        val current = tool ?: return@LaunchedEffect
        if (curve == null || (current == GraphTool.INTEGRAL && function == null)) tool = null else placeHandlesInView(current)
    }
    // Polar needs a single curve: with more shown it is left (and the scales made equal) rather than coming back unannounced
    LaunchedEffect(visibleEntries.size) {
        if (polarSelected && visibleEntries.size != 1) setPolar(false)
    }

    val textMeasurer = rememberTextMeasurer()
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val gridColor = if (isDark) Color(0xFF2A2D35) else Color(0xFFE0E3EA)
    val axisColor = if (isDark) Color(0xFF6B7280) else Color(0xFF9CA3AF)
    val palette = if (isDark) CURVE_COLORS_DARK else CURVE_COLORS_LIGHT
    // The colour of a curve follows its line: the position among the lines that are curves
    fun colorOf(entryIndex: Int): Color = palette[curveEntries.indexOfFirst { it.index == entryIndex }.coerceAtLeast(0) % palette.size]
    val curveColor = colorOf(activeIndex)
    val labelColor = if (isDark) Color(0xFFA0A6B2) else Color(0xFF606675)
    val handleColor = MaterialTheme.colorScheme.onBackground
    val backgroundColor = MaterialTheme.colorScheme.background

    // Trace snaps exactly onto key points, but they are recomputed after panning, so compare loosely
    fun isSame(a: Double, b: Double) = abs(a - b) <= 1e-9 * max(1.0, abs(b))
    // The cursor of trace or tangent sits on a crossing of the active curve
    val snappedCrossing = if (tool != GraphTool.TRACE && tool != GraphTool.TANGENT) null else crossings.firstOrNull { c ->
        (c.first == activeIndex || c.second == activeIndex) && if (implicit != null) {
            cursorPoint?.let { p -> abs(c.x - p.x) <= 1e-6 * max(1.0, abs(c.x)) && abs(c.y - p.y) <= 1e-6 * max(1.0, abs(c.y)) } == true
        } else {
            abs(c.x - cursorX) <= 1e-6 * max(1.0, abs(c.x))
        }
    }
    val snappedPoint = when {
        tool != GraphTool.TRACE -> null
        implicit != null -> cursorPoint?.let { p -> keyPoints.firstOrNull { isSame(it.x, p.x) && isSame(it.y, p.y) } }
        else -> keyPoints.firstOrNull { isSame(it.x, cursorX) }
    }

    Column(
        modifier = modifier
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // The line of the active curve; with several curves the legend below lists all of them
        val headerLine = entries.getOrNull(activeIndex)?.line ?: entries.firstOrNull { !it.isDefinition }?.line ?: expression
        GraphHeader(
            // Shown as r = f(θ), the way the curve is read in polar mode
            expression = if (polar) remember(headerLine, function) {
                headerLine.renameVariables(mapOf(function.variable.identifier to "\u03B8", "y" to "r"))
            } else headerLine,
            variable = if (polar) "r" else "y",
            axisOptions = AxisOptions(
                logX = logX,
                logY = logY,
                polar = polar,
                polarAvailable = function != null && visibleEntries.size == 1,
                onLogChange = { xAxis, enabled -> setLogScale(xAxis, enabled) },
                onPolarChange = { setPolar(it) }
            ),
            onRecenter = {
                resetX()
                resetY()
            },
            onAddLine = onAddLine,
            onClose = onClose
        )

        // With several lines: what each is, and which curve the tools work on
        if (entries.count { !it.isDefinition } >= 2) {
            CurveLegend(
                entries = entries,
                colorOf = ::colorOf,
                hidden = hidden,
                activeIndex = activeIndex,
                onActivate = { index ->
                    if (index in hidden) toggleHidden(index)
                    activeIndex = index
                },
                onToggle = { toggleHidden(it) }
            )
        }

        // What the picture shows, for screen readers
        val summary = pluralStringResource(R.plurals.graph_summary_curves, visibleEntries.size, visibleEntries.size) + ", " +
            pluralStringResource(R.plurals.graph_summary_crossings, crossings.size, crossings.size)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = summary }
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(curve) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val handle = if (base != null) handleAt(down.position) else null
                            if (handle != null) {
                                // Dragging a handle follows the first finger only
                                moveHandle(handle, down.position)
                                drag(down.id) { change ->
                                    moveHandle(handle, change.position)
                                    change.consume()
                                }
                                return@awaitEachGesture
                            }

                            var isTransforming = false
                            var accumulatedPan = Offset.Zero
                            var accumulatedZoom = 1f
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) break
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (!isTransforming) {
                                    accumulatedPan += pan
                                    accumulatedZoom *= zoom
                                    val zoomMotion = abs(1f - accumulatedZoom) * event.calculateCentroidSize()
                                    isTransforming = accumulatedPan.getDistance() > viewConfiguration.touchSlop ||
                                        zoomMotion > viewConfiguration.touchSlop
                                }
                                if (isTransforming) {
                                    transform(event.calculateCentroid(), pan, zoom)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            if (!isTransforming) {
                                // A tap on a crossing shows it; a tap anywhere else lets it go and goes to the tool
                                val crossing = crossingNear(down.position)
                                val forTool = tool != null && base != null && crossing != null && (crossing.first == activeIndex || crossing.second == activeIndex)
                                selectedCrossing = if (forTool) null else crossing
                                if ((crossing == null || forTool) && base != null) onTap(down.position)
                            }
                        }
                    }
            ) {
                val vp = viewport()
                val width = size.width
                val height = size.height

                if (polar) {
                    drawPolarGrid(vp, function.angleUnit, gridColor, axisColor, labelColor, backgroundColor, textMeasurer)
                    drawPolarCurve(polarPoints, vp, curveColor)
                } else {
                    drawGrid(vp, gridColor, axisColor, labelColor, textMeasurer)
                }

                // The curves besides the active one, under it
                if (!polar) {
                    for (entry in visibleEntries) {
                        if (entry.index == activeIndex) continue
                        val color = colorOf(entry.index)
                        val shown = entry.value.curve
                        val inequality = shown as? InequalityPlot
                        val data = passive[entry.index]
                        if (inequality != null) data?.shade?.let { drawShade(it, vp, inequality.twoVariables, color.copy(alpha = 0.18f)) }
                        when (val boundary = inequality?.primary ?: shown) {
                            is Function1D -> drawCurve(boundary::evaluateAt, vp, color, dashed = inequality?.strict == true)
                            is ImplicitCurve -> data?.let { drawSegments(it.segments, vp, color) }
                            else -> {}
                        }
                    }
                }

                shade?.let { drawShade(it, vp, region?.twoVariables == true, curveColor.copy(alpha = 0.18f)) }
                // Where all inequalities hold: darker than any of them alone, in the colour of the text
                scene?.commonRegion(hidden)?.let { common ->
                    passive[COMMON_REGION]?.shade?.let { drawShade(it, vp, common.twoVariables, handleColor.copy(alpha = 0.16f)) }
                }

                if (function != null && !polar) {
                    if (tool == GraphTool.INTEGRAL) {
                        drawIntegralArea(function, vp, lowerBound, upperBound, curveColor, AccentRed)
                    }
                    drawCurve(function::evaluateAt, vp, curveColor, dashed = region?.strict == true)

                    when (tool) {
                        GraphTool.TRACE -> {
                            drawKeyPoints(keyPoints, vp, backgroundColor, textMeasurer)
                            drawCursor(function, vp, cursorX, snappedPoint?.kind?.color ?: if (snappedCrossing != null) handleColor else curveColor, axisColor)
                        }
                        GraphTool.TANGENT -> {
                            val y = function.evaluateAt(cursorX)
                            drawTangentLine(vp, PlotPoint(cursorX, y), function.derivativeAt(cursorX), handleColor)
                            drawCursor(function, vp, cursorX, handleColor, axisColor)
                        }
                        GraphTool.INTEGRAL -> {
                            drawBoundHandle(vp, lowerBound, "a", handleColor, backgroundColor, textMeasurer)
                            drawBoundHandle(vp, upperBound, "b", handleColor, backgroundColor, textMeasurer)
                        }
                        null -> {}
                    }
                }

                if (implicit != null) {
                    drawSegments(segments, vp, curveColor)
                    val point = cursorPoint
                    when (tool) {
                        GraphTool.TRACE -> {
                            drawKeyPoints(keyPoints, vp, backgroundColor, textMeasurer)
                            if (point != null) drawCursorDot(vp.toScreen(point), snappedPoint?.kind?.color ?: if (snappedCrossing != null) handleColor else curveColor)
                        }
                        GraphTool.TANGENT -> if (point != null) {
                            drawTangentLine(vp, point, implicit.slopeAt(point.x, point.y), handleColor)
                            drawCursorDot(vp.toScreen(point), handleColor)
                        }
                        else -> {}
                    }
                }

                // The points where the curves cross: dots in the colour of the text, with a ring of the background
                for (crossing in crossings) {
                    val center = vp.toScreen(PlotPoint(crossing.x, crossing.y))
                    if (center.x !in 0f..width || center.y !in 0f..height) continue
                    val selected = selectedCrossing?.let { crossing.sameAs(it) } == true
                    drawCircle(color = backgroundColor, radius = (if (selected) 9.dp else 7.dp).toPx(), center = center)
                    drawCircle(color = handleColor, radius = (if (selected) 6.5.dp else 4.5.dp).toPx(), center = center)
                    if (selected) drawCircle(color = backgroundColor, radius = 2.dp.toPx(), center = center)
                }
            }

            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                curveEntries.isEmpty() -> Text(
                    text = stringResource(R.string.graph_cannot_plot),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            val chosenCrossing = selectedCrossing
            if (chosenCrossing != null) {
                GraphInfoCard(modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    CrossingInfo(
                        crossing = chosenCrossing,
                        first = entries[chosenCrossing.first].line,
                        second = entries[chosenCrossing.second].line,
                        firstColor = colorOf(chosenCrossing.first),
                        secondColor = colorOf(chosenCrossing.second)
                    )
                }
            } else if (base != null && tool != null && canvasSize.width > 0) {
                // The implicit cursor needs a curve to sit on; before that only a hint is shown
                val point = if (implicit != null) cursorPoint else PlotPoint(cursorX, function!!.evaluateAt(cursorX))
                val slope = when {
                    point == null -> Double.NaN
                    implicit != null -> implicit.slopeAt(point.x, point.y)
                    else -> function!!.derivativeAt(cursorX)
                }
                GraphInfoCard(modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    when {
                        tool == GraphTool.INTEGRAL -> IntegralInfo(lowerBound, upperBound, area)
                        point == null -> InfoHint(stringResource(R.string.graph_tap_hint))
                        tool == GraphTool.TRACE && snappedCrossing != null -> CrossingInfo(
                            crossing = snappedCrossing,
                            first = entries[snappedCrossing.first].line,
                            second = entries[snappedCrossing.second].line,
                            firstColor = colorOf(snappedCrossing.first),
                            secondColor = colorOf(snappedCrossing.second)
                        )
                        tool == GraphTool.TRACE -> TraceInfo(point, snappedPoint, isImplicit = implicit != null)
                        tool == GraphTool.TANGENT -> TangentInfo(point, slope)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (entry in GraphTool.entries) {
                GraphToolButton(
                    tool = entry,
                    selected = tool == entry,
                    // The area under a curve is only defined for functions; polar mode has no tools
                    enabled = !polar && if (entry == GraphTool.INTEGRAL) function != null else base != null,
                    onClick = { selectTool(entry) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The 3D view of a surface, with the same header as the 2D graph. */
@Composable
private fun SurfaceGraph(surface: Surface3D, expression: MathString, alone: Boolean, modifier: Modifier, onClose: (() -> Unit)?) {
    val state = remember(surface) { SurfaceViewState() }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        GraphHeader(
            expression = expression,
            variable = "z",
            axisOptions = null,
            onRecenter = { state.reset() },
            onAddLine = null,
            onClose = onClose
        )
        // Other lines cannot be drawn in the 3D view
        if (!alone) {
            Text(
                text = stringResource(R.string.graph_surface_alone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        SurfacePlot(surface, state, Modifier.weight(1f).fillMaxWidth())
    }
}

/** Whether the axes are logarithmic or polar, and how to change that. */
private class AxisOptions(
    val logX: Boolean,
    val logY: Boolean,
    val polar: Boolean,
    val polarAvailable: Boolean,
    val onLogChange: (xAxis: Boolean, enabled: Boolean) -> Unit,
    val onPolarChange: (enabled: Boolean) -> Unit
)

/** A copy with the variables named in [names] renamed, inside fractions, roots etc. too. */
private fun MathString.renameVariables(names: Map<String, String>): MathString = MathString(
    nodes.map { node ->
        if (node is VariableNode) names[node.name]?.let(::VariableNode) ?: node
        else node.subStrings.foldIndexed(node) { i, renamed, sub -> renamed.replaceSubString(i, sub.renameVariables(names)) }
    }
)

/**
 * The expression, shown as "[variable] = …" unless it is an equation, with the buttons; the
 * axis menu only if there are [axisOptions].
 */
@Composable
private fun GraphHeader(
    expression: MathString,
    variable: String = "y",
    axisOptions: AxisOptions?,
    onRecenter: () -> Unit,
    onAddLine: (() -> Unit)?,
    onClose: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        // Translucent colors do not match the scheme, so contentColorFor would fall back to black
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // A plain f(x) is shown as y = f(x), rendered like a typed-in y = so both look the same
            val displayed = remember(expression, variable) { asEquation(expression, variable) }
            // Scrolls sideways when too long, instead of being squeezed next to the buttons
            Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                MathExpressionView(mathString = displayed, fontSize = 18.sp)
            }
            if (axisOptions != null) AxisMenu(axisOptions)
            if (onAddLine != null) {
                IconButton(onClick = onAddLine) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.graph_add_line))
                }
            }
            IconButton(onClick = onRecenter) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.graph_recenter))
            }
            if (onClose != null) {
                // Filled so the way back to the calculator is easy to spot
                FilledIconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = OrangePrimary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.graph_close))
                }
            }
        }
    }
}

/** [expression] as "[variable] = …", unless it is an equation or an inequality already. */
private fun asEquation(expression: MathString, variable: String): MathString {
    val isEquation = expression.nodes.any { (it is OperatorNode && (it.op == MathOp.EQUALS || it.op.isInequality)) || it is com.vortessence.arithmeticus.core.math.type.LineBreakNode }
    return if (isEquation) expression else MathString(listOf(VariableNode(variable), OperatorNode(MathOp.EQUALS)) + expression.nodes)
}

/**
 * The lines of a graph with several curves, one chip each: the dot in the colour of the curve shows or hides it, the line makes it
 * the curve the tools work on (outlined in its colour). A line that cannot be graphed is listed, dimmed, and says so.
 */
@Composable
private fun CurveLegend(
    entries: List<PlotEntry>,
    colorOf: (Int) -> Color,
    hidden: Set<Int>,
    activeIndex: Int,
    onActivate: (Int) -> Unit,
    onToggle: (Int) -> Unit
) {
    val shownLabel = stringResource(R.string.graph_curve_shown)
    val hiddenLabel = stringResource(R.string.graph_curve_hidden)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((index, entry) in entries.withIndex()) {
            if (entry.isDefinition) continue
            val line = remember(entry.line) { asEquation(entry.line, "y") }
            if (entry.curve == null) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MathExpressionView(mathString = line, fontSize = 14.sp, textColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        Text(stringResource(R.string.graph_line_cannot_plot), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                continue
            }
            val color = colorOf(index)
            val isHidden = index in hidden
            val active = index == activeIndex
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = if (active) BorderStroke(1.5.dp, color) else null
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The dot: filled while the curve is shown, a ring while it is hidden
                    val toggleLabel = stringResource(R.string.graph_toggle_curve)
                    Box(
                        modifier = Modifier
                            .clickable(role = Role.Checkbox, onClickLabel = toggleLabel) { onToggle(index) }
                            .semantics { stateDescription = if (isHidden) hiddenLabel else shownLabel }
                            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(if (isHidden) Color.Transparent else color, CircleShape)
                                .border(2.dp, color, CircleShape)
                        )
                    }
                    Box(modifier = Modifier.clickable { onActivate(index) }.semantics { selected = active }.padding(end = 12.dp, top = 6.dp, bottom = 6.dp)) {
                        MathExpressionView(
                            mathString = line,
                            fontSize = 14.sp,
                            textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isHidden) 0.45f else 1f)
                        )
                    }
                }
            }
        }
    }
}

/** Where two curves cross: the point, and which curves they are (in their colours). */
@Composable
private fun CrossingInfo(crossing: PlotIntersection, first: MathString, second: MathString, firstColor: Color, secondColor: Color) {
    InfoTitle(stringResource(R.string.graph_intersection), MaterialTheme.colorScheme.onSurface)
    InfoValues("x" to formatGraphNumber(crossing.x), "y" to formatGraphNumber(crossing.y))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(8.dp).background(firstColor, CircleShape))
        MathExpressionView(mathString = remember(first) { asEquation(first, "y") }, fontSize = 13.sp)
        Text("·", style = MaterialTheme.typography.bodySmall)
        Box(modifier = Modifier.size(8.dp).background(secondColor, CircleShape))
        MathExpressionView(mathString = remember(second) { asEquation(second, "y") }, fontSize = 13.sp)
    }
}

/**
 * Switches between linear, logarithmic and polar axes; highlighted while any of them is not
 * linear. Log axes are unavailable in polar mode, and polar mode for implicit curves.
 */
@Composable
private fun AxisMenu(options: AxisOptions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.Straighten,
                contentDescription = stringResource(R.string.graph_axes),
                tint = if (options.logX || options.logY || options.polar) OrangePrimary else LocalContentColor.current
            )
        }
        // Stays open after a change, so both axes can be switched in one go
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((xAxis, enabled) in listOf(true to options.logX, false to options.logY)) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (xAxis) R.string.graph_log_x else R.string.graph_log_y)) },
                    onClick = { options.onLogChange(xAxis, !enabled) },
                    enabled = !options.polar,
                    trailingIcon = { Switch(checked = enabled, onCheckedChange = null, enabled = !options.polar) }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.graph_polar)) },
                onClick = { options.onPolarChange(!options.polar) },
                enabled = options.polarAvailable,
                trailingIcon = { Switch(checked = options.polar, onCheckedChange = null, enabled = options.polarAvailable) }
            )
        }
    }
}

@Composable
private fun GraphToolButton(
    tool: GraphTool,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { this.selected = selected },
        shape = RoundedCornerShape(16.dp),
        color = if (selected) OrangePrimary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            selected -> OrangePrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) BorderStroke(1.dp, OrangePrimary) else null
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(tool.icon, contentDescription = null)
            Text(stringResource(tool.label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun GraphInfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        // Translucent colors do not match the scheme, so contentColorFor would fall back to black
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

@Composable
private fun InfoTitle(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun InfoValues(vararg values: Pair<String, String>) {
    Text(
        text = values.joinToString("    ") { (name, value) -> "$name = $value" },
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun InfoHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TraceInfo(point: PlotPoint, snapped: KeyPoint?, isImplicit: Boolean) {
    InfoTitle(stringResource(snapped?.kind?.label ?: R.string.graph_tool_trace), snapped?.kind?.color ?: OrangePrimary)
    InfoValues("x" to formatGraphNumber(point.x), "y" to formatGraphNumber(point.y))
    if (snapped == null) {
        InfoHint(stringResource(if (isImplicit) R.string.graph_drag_curve else R.string.graph_drag_graph))
    }
}

@Composable
private fun TangentInfo(point: PlotPoint, slope: Double) {
    InfoTitle(stringResource(R.string.graph_tool_tangent), MaterialTheme.colorScheme.onBackground)
    InfoValues("x" to formatGraphNumber(point.x), "y" to formatGraphNumber(point.y))
    Text(
        text = if (slope.isInfinite()) stringResource(R.string.graph_vertical_tangent) else stringResource(R.string.graph_slope, formatGraphNumber(slope)),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    when {
        slope.isInfinite() -> InfoHint("x = ${formatGraphNumber(point.x)}")
        point.y.isFinite() && slope.isFinite() -> InfoHint(formatLineEquation(slope, point.y - slope * point.x))
    }
}

@Composable
private fun IntegralInfo(lowerBound: Double, upperBound: Double, area: Double?) {
    InfoTitle(stringResource(R.string.graph_tool_integral), OrangePrimary)
    InfoValues("a" to formatGraphNumber(lowerBound), "b" to formatGraphNumber(upperBound))
    Text(
        text = "\u222B f(x) dx = ${area?.let { formatGraphNumber(it) } ?: "…"}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    InfoHint(stringResource(R.string.graph_integral_hint))
}

/** y = mx + b with the sign of b folded into the operator. */
private fun formatLineEquation(slope: Double, intercept: Double): String {
    val m = formatGraphNumber(slope)
    val b = formatGraphNumber(abs(intercept))
    return when {
        m == "0" -> "y = ${formatGraphNumber(intercept)}"
        b == "0" -> "y = ${m}x"
        intercept < 0 -> "y = ${m}x \u2212 $b"
        else -> "y = ${m}x + $b"
    }
}

/**
 * Formats a value for the graph read-outs: up to [significantDigits] significant digits without
 * trailing zeros, switching to scientific notation for very large or small magnitudes. Values
 * within numerical noise of zero are shown as 0.
 */
internal fun formatGraphNumber(value: Double, significantDigits: Int = 6): String {
    if (value.isNaN()) return Texts.get(Text.UNDEFINED_VALUE, Language.fromTag(java.util.Locale.getDefault().toLanguageTag()))
    if (value.isInfinite()) return if (value > 0) "\u221E" else "\u2212\u221E"
    if (abs(value) < 1e-9) return "0"
    val rounded = BigDecimal(value).round(java.math.MathContext(significantDigits)).stripTrailingZeros()
    val magnitude = abs(rounded.toDouble())
    if (magnitude >= 1e-4 && magnitude < 1e7) return DecimalMark.display(rounded.toPlainString())
    val exponent = rounded.precision() - rounded.scale() - 1
    val mantissa = rounded.movePointLeft(exponent).toPlainString()
    return "${DecimalMark.display(mantissa)}\u00D710${toSuperscript(exponent)}"
}

/** Short x-value for point labels: at most three decimals, so -10.996 does not become -11. */
private fun formatKeyPointX(x: Double): String {
    if (abs(x) < 5e-4) return "0"
    return DecimalMark.display("%.3f".format(Locale.US, x).trimEnd('0').trimEnd('.'))
}

private fun toSuperscript(n: Int): String {
    val digits = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079"
    val body = abs(n).toString().map { digits[it - '0'] }.joinToString("")
    return if (n < 0) "\u207B$body" else body
}

/** Small highlighted dots with their x-values at zeros, extrema and inflection points. */
private fun DrawScope.drawKeyPoints(
    points: List<KeyPoint>,
    vp: Viewport,
    backgroundColor: Color,
    textMeasurer: TextMeasurer
) {
    // Labels that would overlap an already drawn one are skipped; zeros come first in the list
    val labelRects = mutableListOf<Rect>()
    for (point in points) {
        val center = Offset(vp.toScreenX(point.x), vp.toScreenY(point.y))
        if (center.x !in 0f..size.width || center.y !in 0f..size.height) continue
        drawCircle(color = point.kind.color.copy(alpha = 0.25f), radius = 8.dp.toPx(), center = center)
        // A ring of the background keeps the dot visible on a curve of its own colour
        drawCircle(color = backgroundColor, radius = 5.5.dp.toPx(), center = center)
        drawCircle(color = point.kind.color, radius = 3.5.dp.toPx(), center = center)

        val layout = textMeasurer.measure(
            text = AnnotatedString(
                if (point.kind == KeyPointKind.Y_INTERCEPT) "y=${formatKeyPointX(point.y)}" else "x=${formatKeyPointX(point.x)}"
            ),
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = point.kind.color,
                background = backgroundColor.copy(alpha = 0.7f)
            )
        )
        // Labels of maxima go above the point, everything else below
        val above = point.kind == KeyPointKind.MAXIMUM
        val top = if (above) center.y - 10.dp.toPx() - layout.size.height else center.y + 10.dp.toPx()
        val rect = Rect(Offset(center.x - layout.size.width / 2f, top), Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
        if (labelRects.any { it.overlaps(rect) }) continue
        labelRects.add(rect)
        drawText(layout, topLeft = rect.topLeft)
    }
}

/** The movable cursor of trace and tangent: a dashed guide line and a dot on the curve. */
private fun DrawScope.drawCursor(function: Function1D, vp: Viewport, x: Double, color: Color, guideColor: Color) {
    val cx = vp.toScreenX(x)
    if (cx.isNaN()) return
    drawLine(
        color = guideColor.copy(alpha = 0.6f),
        start = Offset(cx, 0f),
        end = Offset(cx, size.height),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    )
    val y = function.evaluateAt(x)
    if (!y.isFinite()) return
    drawCursorDot(Offset(cx, vp.toScreenY(y)), color)
}

private fun DrawScope.drawCursorDot(center: Offset, color: Color) {
    if (center.y !in 0f..size.height || center.x !in 0f..size.width) return
    drawCircle(color = Color.White, radius = 8.dp.toPx(), center = center)
    drawCircle(color = color, radius = 5.5.dp.toPx(), center = center)
}

/** The tangent through [point] across the whole canvas; an infinite [slope] means vertical. */
private fun DrawScope.drawTangentLine(vp: Viewport, point: PlotPoint, slope: Double, color: Color) {
    if (!point.x.isFinite() || !point.y.isFinite() || slope.isNaN()) return
    if (slope.isInfinite()) {
        val cx = vp.toScreenX(point.x)
        if (!cx.isNaN()) drawLine(color = color, start = Offset(cx, 0f), end = Offset(cx, size.height), strokeWidth = 2.dp.toPx())
        return
    }
    if (vp.logX || vp.logY) {
        // A straight line is curved on a log axis, so follow it pixel by pixel
        drawCurve({ x -> point.y + slope * (x - point.x) }, vp, color, strokeWidth = 2.dp.toPx())
        return
    }
    val center = vp.toScreen(point)
    // Direction on screen: one unit in x moves scaleX right and slope × scaleY up
    val direction = if (slope.isInfinite()) Offset(0f, 1f) else Offset(vp.scaleX, -(slope * vp.scaleY).toFloat())
    val length = direction.getDistance()
    if (!length.isFinite() || length == 0f) return
    val reach = hypot(size.width, size.height) * 2f
    val step = direction * (reach / length)
    drawLine(color = color, start = center - step, end = center + step, strokeWidth = 2.dp.toPx())
}

/** The cells of a grid over a range (row by row from the top) where an inequality holds. */
private class ShadeGrid(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val cols: Int, val rows: Int, val cells: BooleanArray)

/** The cells that hold, each run of them in a row as one rectangle; in one variable the full height. */
private fun DrawScope.drawShade(grid: ShadeGrid, vp: Viewport, twoVariables: Boolean, color: Color) {
    val dx = (grid.maxX - grid.minX) / grid.cols
    val dy = (grid.maxY - grid.minY) / grid.rows
    for (r in 0 until grid.rows) {
        val top = if (twoVariables) vp.toScreenY(grid.maxY - r * dy) else 0f
        val bottom = if (twoVariables) vp.toScreenY(grid.maxY - (r + 1) * dy) else size.height
        var c = 0
        while (c < grid.cols) {
            if (!grid.cells[r * grid.cols + c]) { c++; continue }
            val start = c
            while (c < grid.cols && grid.cells[r * grid.cols + c]) c++
            val left = vp.toScreenX(grid.minX + start * dx)
            val right = vp.toScreenX(grid.minX + c * dx)
            // A little over, so that neighbouring rectangles leave no seams
            drawRect(color, topLeft = Offset(left, top), size = Size(right - left + 0.5f, bottom - top + 0.5f))
        }
    }
}

/** An implicit curve from its segments, as one path. */
private fun DrawScope.drawSegments(segments: List<PlotSegment>, vp: Viewport, color: Color) {
    val path = Path()
    for (segment in segments) {
        val a = vp.toScreen(segment.start)
        val b = vp.toScreen(segment.end)
        path.moveTo(a.x, a.y)
        path.lineTo(b.x, b.y)
    }
    drawPath(path = path, color = color, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
}

/** A vertical integration bound with a labelled knob on the x-axis (kept on screen). */
private fun DrawScope.drawBoundHandle(
    vp: Viewport,
    x: Double,
    label: String,
    color: Color,
    backgroundColor: Color,
    textMeasurer: TextMeasurer
) {
    val cx = vp.toScreenX(x)
    if (cx.isNaN()) return
    drawLine(color = color.copy(alpha = 0.7f), start = Offset(cx, 0f), end = Offset(cx, size.height), strokeWidth = 1.5.dp.toPx())
    val radius = 12.dp.toPx()
    if (cx.isNaN()) return
    val center = Offset(cx, (vp.zeroY ?: size.height).coerceIn(radius, size.height - radius))
    drawCircle(color = color, radius = radius, center = center)
    val layout = textMeasurer.measure(
        text = AnnotatedString(label),
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = backgroundColor)
    )
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/**
 * Shades the area between the curve and the x-axis from [a] to [b]: parts above the axis in
 * [positiveColor], parts below in [negativeColor], matching the sign they add to the integral.
 * A log y-axis has no zero line, so the area then reaches down to the bottom edge.
 */
private fun DrawScope.drawIntegralArea(
    function: Function1D,
    vp: Viewport,
    a: Double,
    b: Double,
    positiveColor: Color,
    negativeColor: Color
) {
    // On a log x-axis bounds at or below zero lie off the left edge
    val rawRight = vp.toScreenX(max(a, b))
    if (rawRight.isNaN()) return
    val rawLeft = vp.toScreenX(min(a, b)).let { if (it.isNaN()) 0f else it }
    val left = rawLeft.coerceIn(0f, size.width)
    val right = rawRight.coerceIn(0f, size.width)
    if (right <= left) return
    val baseline = vp.zeroY ?: size.height
    val limit = size.height * 3f
    val path = Path()
    path.moveTo(left, baseline)
    var px = left
    while (true) {
        val cy = vp.toScreenY(function.evaluateAt(vp.toMathX(px)))
        // Gaps (undefined values, or values a log axis cannot show) drop back to the axis
        path.lineTo(px, if (cy.isFinite()) cy.coerceIn(-limit, limit) else baseline)
        if (px >= right) break
        px = min(px + 2f, right)
    }
    path.lineTo(right, baseline)
    path.close()

    clipRect(bottom = baseline) { drawPath(path, positiveColor.copy(alpha = 0.25f)) }
    clipRect(top = baseline) { drawPath(path, negativeColor.copy(alpha = 0.2f)) }
}

/**
 * Draws the curve sample by sample. The path is interrupted at gaps (undefined values, or values
 * a log axis cannot show) and at discontinuities such as the poles of tan(x), which would
 * otherwise be drawn as vertical lines.
 */
private fun DrawScope.drawCurve(evaluate: (Double) -> Double, vp: Viewport, color: Color, strokeWidth: Float = 3.5f, dashed: Boolean = false) {
    val width = size.width
    val height = size.height
    val path = Path()
    var previousY: Float? = null
    val stepPx = 2f
    var px = 0f

    fun toCanvasY(screenX: Float): Float? =
        vp.toScreenY(evaluate(vp.toMathX(screenX))).takeIf { it.isFinite() }

    while (px <= width) {
        val canvasY = toCanvasY(px)?.takeIf { it in -height * 2f..height * 3f }
        val prev = previousY
        if (canvasY == null) {
            previousY = null
        } else if (prev == null) {
            path.moveTo(px, canvasY)
            previousY = canvasY
        } else {
            var connected = true
            if (abs(canvasY - prev) > height / 2f) {
                // Big jump: continuous only if the midpoint lies between both samples
                val midY = toCanvasY(px - stepPx / 2f)
                connected = midY != null && midY in min(prev, canvasY)..max(prev, canvasY)
            }
            if (connected) path.lineTo(px, canvasY) else path.moveTo(px, canvasY)
            previousY = canvasY
        }
        px += stepPx
    }
    drawPath(path = path, color = color, style = Stroke(width = strokeWidth, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(22f, 14f)) else null))
}

/**
 * Reads [function] as r = f(θ) and samples one full turn, θ = 0 to 2π (0° to 360° if the
 * function works in degrees). Negative r points the opposite way, as usual for polar curves.
 */
private fun samplePolarCurve(function: Function1D): List<PlotPoint> {
    val fullTurn = if (function.angleUnit == AngleUnit.DEGREES) 360.0 else 2 * PI
    return (0..POLAR_SAMPLES).map { i ->
        val r = function.evaluateAt(fullTurn * i / POLAR_SAMPLES)
        val angle = 2 * PI * i / POLAR_SAMPLES
        if (r.isFinite()) PlotPoint(r * cos(angle), r * sin(angle)) else PlotPoint(Double.NaN, Double.NaN)
    }
}

/**
 * Draws the sampled polar curve. Like [drawCurve] the path is interrupted at gaps and at poles
 * such as those of r = 1/cos(θ), where consecutive points jump across the canvas.
 */
private fun DrawScope.drawPolarCurve(points: List<PlotPoint>, vp: Viewport, color: Color) {
    val limit = max(size.width, size.height) * 3f
    val maxJump = max(size.width, size.height)
    val path = Path()
    var previous: Offset? = null
    for (point in points) {
        val p = vp.toScreen(point)
        val isDrawable = p.x.isFinite() && p.y.isFinite() && abs(p.x - size.width / 2f) <= limit && abs(p.y - size.height / 2f) <= limit
        val prev = previous
        when {
            !isDrawable -> previous = null
            prev == null || (p - prev).getDistance() > maxJump -> {
                path.moveTo(p.x, p.y)
                previous = p
            }
            else -> {
                path.lineTo(p.x, p.y)
                previous = p
            }
        }
    }
    drawPath(path = path, color = color, style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** π/6, 2π/3 or 150° for the [k]-th twelfth of a turn. */
private fun formatPolarAngle(k: Int, angleUnit: AngleUnit): String {
    if (angleUnit == AngleUnit.DEGREES) return "${k * 30}\u00B0"
    if (k == 0) return "0"
    val divisor = gcd(k, 6)
    val numerator = k / divisor
    val denominator = 6 / divisor
    val top = if (numerator == 1) "\u03C0" else "${numerator}\u03C0"
    return if (denominator == 1) top else "$top/$denominator"
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/**
 * Concentric circles at nice radii and rays every 30° around the origin, the four axis rays
 * emphasised. Radii are labelled on their circles, angles where their ray leaves the canvas.
 */
private fun DrawScope.drawPolarGrid(
    vp: Viewport,
    angleUnit: AngleUnit,
    gridColor: Color,
    axisColor: Color,
    labelColor: Color,
    backgroundColor: Color,
    textMeasurer: TextMeasurer
) {
    val width = size.width
    val height = size.height
    val origin = Offset(vp.originX, vp.originY)
    // Radius labels sit on their circle, which would otherwise run through the text
    val labelBackground = backgroundColor.copy(alpha = 0.8f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    // Radii (in pixels) of the canvas parts nearest to and farthest from the origin
    val nearestPx = hypot(max(0f, max(-origin.x, origin.x - width)), max(0f, max(-origin.y, origin.y - height)))
    val farthestPx = listOf(Offset.Zero, Offset(width, 0f), Offset(0f, height), Offset(width, height))
        .maxOf { (it - origin).getDistance() }

    val step = calculateGridStep(vp.scaleX, MIN_TICK_SPACING_PX)
    val radii = (max(1L, ceil(nearestPx / vp.scaleX / step).toLong())..floor(farthestPx / vp.scaleX / step).toLong())
        .map { it * step }
    for (r in radii) {
        drawCircle(gridColor, radius = (r * vp.scaleX).toFloat(), center = origin, style = Stroke(width = 1f))
    }

    val directions = (0 until 12).map { k ->
        val angle = k * PI / 6
        Offset(cos(angle).toFloat(), -sin(angle).toFloat())
    }
    directions.forEachIndexed { k, direction ->
        val isAxis = k % 3 == 0
        drawLine(
            if (isAxis) axisColor else gridColor,
            origin,
            origin + direction * farthestPx,
            strokeWidth = if (isAxis) 2f else 1f
        )
    }

    // Labels that would overlap one already drawn or be cut off at the edge are skipped
    val labelRects = mutableListOf<Rect>()
    fun drawLabel(text: String, background: Color = Color.Unspecified, topLeft: (Size) -> Offset) {
        val layout = textMeasurer.measure(AnnotatedString(text), labelStyle.copy(background = background))
        val labelSize = Size(layout.size.width.toFloat(), layout.size.height.toFloat())
        val bounds = Rect(topLeft(labelSize), labelSize)
        if (bounds.left < 0f || bounds.top < 0f || bounds.right > width || bounds.bottom > height) return
        val rect = bounds.inflate(3f)
        if (labelRects.any { it.overlaps(rect) }) return
        labelRects.add(rect)
        drawText(layout, topLeft = bounds.topLeft)
    }

    directions.forEachIndexed { k, direction ->
        // Where the ray leaves the canvas (slab method); it may also miss the canvas entirely
        fun slab(o: Float, d: Float, extent: Float): Pair<Float, Float> = when {
            d > 1e-6f -> -o / d to (extent - o) / d
            d < -1e-6f -> (extent - o) / d to -o / d
            o in 0f..extent -> Float.NEGATIVE_INFINITY to Float.POSITIVE_INFINITY
            else -> Float.POSITIVE_INFINITY to Float.NEGATIVE_INFINITY
        }
        val (enterX, exitX) = slab(origin.x, direction.x, width)
        val (enterY, exitY) = slab(origin.y, direction.y, height)
        val enter = max(0f, max(enterX, enterY))
        val exit = min(exitX, exitY)
        if (exit <= enter) return@forEachIndexed
        drawLabel(formatPolarAngle(k, angleUnit)) {
            // Pulled in from the edge far enough for the whole label to fit
            val inset = max(it.width, it.height) / 2f + 6f
            if (exit - inset <= enter) return@drawLabel Offset(-width, -height)
            val center = origin + direction * (exit - inset)
            Offset(center.x - it.width / 2f, center.y - it.height / 2f)
        }
    }
    // Radii go on their circles along a ray between two grid rays, so they are not mistaken
    // for x-values: at 15° while the origin is in view, otherwise on the ray towards the
    // canvas centre (snapped likewise), so they stay visible
    val originInView = origin.x in 0f..width && origin.y in 0f..height
    val labelAngle = if (originInView) PI / 12 else {
        val towardsCenter = atan2((origin.y - height / 2f).toDouble(), (width / 2f - origin.x).toDouble())
        PI / 12 + round((towardsCenter - PI / 12) / (PI / 6)) * PI / 6
    }
    val labelDirection = Offset(cos(labelAngle).toFloat(), -sin(labelAngle).toFloat())
    for (r in radii) {
        val center = origin + labelDirection * (r * vp.scaleX).toFloat()
        drawLabel(formatGridLabel(r, step), labelBackground) { Offset(center.x - it.width / 2f, center.y - it.height / 2f) }
    }
}

/** A grid line at axis position [u] (log10 of the value on a log axis), optionally labelled. */
private class AxisTick(val u: Double, val label: String?, val isMinor: Boolean = false)

private const val MIN_TICK_SPACING_PX = 60f

/** Lines at multiples of a nice step (1, 2, 5, 10, ...); 0 is left unlabelled for the axis. */
private fun linearTicks(minU: Double, maxU: Double, scale: Float): List<AxisTick> {
    val step = calculateGridStep(scale, MIN_TICK_SPACING_PX)
    return (ceil(minU / step).toLong()..floor(maxU / step).toLong()).map { k ->
        AxisTick(k * step, if (k == 0L) null else formatGridLabel(k * step, step))
    }
}

/**
 * Lines at powers of ten with lighter lines at 2-9 times them. Zoomed out far, only every n-th
 * decade is labelled; zoomed in to a fraction of a decade, nice linear steps are used instead.
 */
private fun logTicks(minU: Double, maxU: Double, scale: Float): List<AxisTick> {
    val ticks = mutableListOf<AxisTick>()
    if (scale < 3000f) {
        val every = max(1, ceil(MIN_TICK_SPACING_PX / scale).toInt())
        for (k in floor(minU).toInt()..ceil(maxU).toInt()) {
            if (k.mod(every) == 0) ticks.add(AxisTick(k.toDouble(), formatLogTick(10.0.pow(k))))
            if (scale < MIN_TICK_SPACING_PX) continue
            for (m in 2..9) {
                // Labels that do not fit are skipped when drawing; decades take precedence
                val label = if (scale >= 250f) formatLogTick(m * 10.0.pow(k)) else null
                ticks.add(AxisTick(k + log10(m.toDouble()), label, isMinor = true))
            }
        }
    } else {
        // Spacing on screen is tightest at the largest value, so the step is chosen for that end
        val low = 10.0.pow(minU)
        val high = 10.0.pow(maxU)
        val step = calculateGridStep((scale / (high * ln(10.0))).toFloat(), MIN_TICK_SPACING_PX)
        for (k in ceil(low / step).toLong()..floor(high / step).toLong()) {
            val value = k * step
            if (value > 0) ticks.add(AxisTick(log10(value), formatGridLabel(value, step)))
        }
    }
    return ticks.filter { it.u in minU..maxU }
}

/** 1000, 0.01 or 10⁸ rather than 1×10⁸ on a log axis. */
private fun formatLogTick(value: Double): String = formatGraphNumber(value, 3).removePrefix("1\u00D7")

private fun DrawScope.drawGrid(
    vp: Viewport,
    gridColor: Color,
    axisColor: Color,
    labelColor: Color,
    textMeasurer: TextMeasurer
) {
    val width = size.width
    val height = size.height
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    val minUx = ((0f - vp.originX) / vp.scaleX).toDouble()
    val maxUx = ((width - vp.originX) / vp.scaleX).toDouble()
    val minUy = ((vp.originY - height) / vp.scaleY).toDouble()
    val maxUy = (vp.originY / vp.scaleY).toDouble()
    val xTicks = if (vp.logX) logTicks(minUx, maxUx, vp.scaleX) else linearTicks(minUx, maxUx, vp.scaleX)
    val yTicks = if (vp.logY) logTicks(minUy, maxUy, vp.scaleY) else linearTicks(minUy, maxUy, vp.scaleY)

    for (tick in xTicks) {
        val x = vp.originX + (tick.u * vp.scaleX).toFloat()
        drawLine(if (tick.isMinor) gridColor.copy(alpha = 0.5f) else gridColor, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
    }
    for (tick in yTicks) {
        val y = vp.originY - (tick.u * vp.scaleY).toFloat()
        drawLine(if (tick.isMinor) gridColor.copy(alpha = 0.5f) else gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
    }

    // Main axes; a log axis has no zero, so its labels move to the canvas edge instead
    vp.zeroY?.let { drawLine(axisColor, Offset(0f, it), Offset(width, it), strokeWidth = 2f) }
    vp.zeroX?.let { drawLine(axisColor, Offset(it, 0f), Offset(it, height), strokeWidth = 2f) }

    // Labels that would overlap one already drawn or be cut off at the edge are skipped; the
    // log markers go first, then major ticks
    val labelRects = mutableListOf<Rect>()
    fun drawLabel(text: String, style: TextStyle = labelStyle, topLeft: (Size) -> Offset) {
        val layout = textMeasurer.measure(AnnotatedString(text), style)
        val labelSize = Size(layout.size.width.toFloat(), layout.size.height.toFloat())
        val bounds = Rect(topLeft(labelSize), labelSize)
        if (bounds.left < 0f || bounds.top < 0f || bounds.right > width || bounds.bottom > height) return
        val rect = bounds.inflate(3f)
        if (labelRects.any { it.overlaps(rect) }) return
        labelRects.add(rect)
        drawText(layout, topLeft = bounds.topLeft)
    }

    val labelHeight = textMeasurer.measure(AnnotatedString("0"), labelStyle).size.height.toFloat()
    val xLabelTop = vp.zeroY?.let { it.coerceIn(10f, height - 30f) + 4f } ?: (height - labelHeight - 4f)
    val yLabelLeft = vp.zeroX?.let { it.coerceIn(10f, width - 40f) + 6f } ?: 6f

    // Marks which axis is logarithmic, at the end of its label row
    val markerStyle = labelStyle.copy(fontWeight = FontWeight.Bold, color = OrangePrimary)
    if (vp.logX) drawLabel("log x", markerStyle) { Offset(width - it.width - 6f, xLabelTop - it.height - 2f) }
    if (vp.logY) drawLabel("log y", markerStyle) { Offset(yLabelLeft, 6f) }
    for (tick in xTicks.sortedBy { it.isMinor }) {
        val label = tick.label ?: continue
        val x = vp.originX + (tick.u * vp.scaleX).toFloat()
        drawLabel(label) { Offset(x - it.width / 2f, xLabelTop) }
    }
    for (tick in yTicks.sortedBy { it.isMinor }) {
        val label = tick.label ?: continue
        val y = vp.originY - (tick.u * vp.scaleY).toFloat()
        drawLabel(label) { Offset(yLabelLeft, y - it.height / 2f) }
    }

}

/** Formats a grid value with just enough decimals for the step size (e.g. 0.5, 0.2). */
private fun formatGridLabel(value: Double, step: Double): String {
    val decimals = if (step >= 1.0) 0 else ceil(-log10(step)).toInt()
    return "%.${decimals}f".format(Locale.US, value)
}

private fun calculateGridStep(scale: Float, minSpacing: Float): Double {
    val rawStep = minSpacing / scale
    val magnitude = 10.0.pow(floor(log10(rawStep.toDouble())))
    val normalized = rawStep / magnitude
    return when {
        normalized <= 1.0 -> 1.0 * magnitude
        normalized <= 2.0 -> 2.0 * magnitude
        normalized <= 5.0 -> 5.0 * magnitude
        else -> 10.0 * magnitude
    }
}
