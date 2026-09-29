package com.vortessence.calculator.ui.graph

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AreaChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.MathEngine
import com.vortessence.calculator.core.math.PlotData
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.type.MathOp
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.OperatorNode
import com.vortessence.calculator.ui.math.MathExpressionView
import com.vortessence.calculator.ui.theme.AccentBlue
import com.vortessence.calculator.ui.theme.AccentGreen
import com.vortessence.calculator.ui.theme.AccentPurple
import com.vortessence.calculator.ui.theme.AccentRed
import com.vortessence.calculator.ui.theme.OrangePrimary
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.*

private const val DEFAULT_SCALE = 40f
private const val MIN_SCALE = 5f
private const val MAX_SCALE = 400f

private enum class GraphTool(val label: String, val icon: ImageVector) {
    TRACE("Trace", Icons.Default.Timeline),
    TANGENT("Tangent", Icons.AutoMirrored.Filled.TrendingUp),
    INTEGRAL("Integral", Icons.Default.AreaChart)
}

/** What a touch grabbed: the trace/tangent cursor or one of the integration bounds. */
private enum class GraphHandle { CURSOR, LOWER_BOUND, UPPER_BOUND }

private enum class KeyPointKind(val label: String, val color: Color) {
    ZERO("Zero", AccentRed),
    MAXIMUM("Maximum", AccentGreen),
    MINIMUM("Minimum", AccentBlue),
    INFLECTION("Inflection point", AccentPurple)
}

private data class KeyPoint(val x: Double, val y: Double, val kind: KeyPointKind)

private fun PlotData.toKeyPoints(): List<KeyPoint> =
    roots.map { KeyPoint(it, 0.0, KeyPointKind.ZERO) } +
        maxima.map { KeyPoint(it.x, it.y, KeyPointKind.MAXIMUM) } +
        minima.map { KeyPoint(it.x, it.y, KeyPointKind.MINIMUM) } +
        inflectionPoints.map { KeyPoint(it.x, it.y, KeyPointKind.INFLECTION) }

/** Maps between math coordinates and canvas pixels. */
private class Viewport(val originX: Float, val originY: Float, val scaleX: Float, val scaleY: Float) {
    fun toScreenX(x: Double): Float = originX + (x * scaleX).toFloat()
    fun toScreenY(y: Double): Float = originY - (y * scaleY).toFloat()
    fun toMathX(px: Float): Double = ((px - originX) / scaleX).toDouble()
}

@Composable
fun GraphPlotterView(
    function: Function1D?,
    expression: MathString,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onClose: (() -> Unit)? = null
) {
    if (onClose != null) BackHandler(onBack = onClose)

    var scaleX by remember { mutableFloatStateOf(DEFAULT_SCALE) } // pixels per unit
    var scaleY by remember { mutableFloatStateOf(DEFAULT_SCALE) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    var tool by remember { mutableStateOf<GraphTool?>(null) }
    // Back leaves the active tool before it closes the graph
    BackHandler(enabled = tool != null) { tool = null }

    // Shared by trace and tangent, so switching between them keeps the position
    var cursorX by remember { mutableDoubleStateOf(0.0) }
    var lowerBound by remember { mutableDoubleStateOf(-1.0) }
    var upperBound by remember { mutableDoubleStateOf(1.0) }
    var keyPoints by remember(function) { mutableStateOf(emptyList<KeyPoint>()) }
    // Null while the first value is computed; afterwards the previous value stays while dragging
    var area by remember(function) { mutableStateOf<Double?>(null) }

    fun viewport() = Viewport(canvasSize.width / 2f + offsetX, canvasSize.height / 2f + offsetY, scaleX, scaleY)

    // Zeros, extrema and inflection points of the visible x-range, recomputed in the background
    // after panning or zooming settles. Integration bounds snap to the zeros.
    val visibleMinX = (-(canvasSize.width / 2f + offsetX) / scaleX).toDouble()
    val visibleMaxX = ((canvasSize.width / 2f - offsetX) / scaleX).toDouble()
    val needsKeyPoints = tool == GraphTool.TRACE || tool == GraphTool.INTEGRAL
    LaunchedEffect(function, visibleMinX, visibleMaxX, needsKeyPoints) {
        if (function == null || canvasSize.width == 0 || !needsKeyPoints) return@LaunchedEffect
        delay(150)
        keyPoints = MathEngine.analyzePlot(function, visibleMinX, visibleMaxX).toKeyPoints()
    }

    LaunchedEffect(function, tool == GraphTool.INTEGRAL, lowerBound, upperBound) {
        if (function == null || tool != GraphTool.INTEGRAL) return@LaunchedEffect
        delay(30)
        area = MathEngine.integratePlot(function, lowerBound, upperBound)
    }

    val density = LocalDensity.current
    val touchRadiusPx = with(density) { 28.dp.toPx() }
    val snapRadiusPx = with(density) { 14.dp.toPx() }

    fun moveHandle(handle: GraphHandle, px: Float) {
        val vp = viewport()
        var x = vp.toMathX(px)
        val snapTargets = when {
            handle == GraphHandle.CURSOR && tool == GraphTool.TRACE -> keyPoints
            handle != GraphHandle.CURSOR -> keyPoints.filter { it.kind == KeyPointKind.ZERO }
            else -> emptyList()
        }
        snapTargets.minByOrNull { abs(vp.toScreenX(it.x) - px) }
            ?.takeIf { abs(vp.toScreenX(it.x) - px) <= snapRadiusPx }
            ?.let { x = it.x }
        when (handle) {
            GraphHandle.CURSOR -> cursorX = x
            GraphHandle.LOWER_BOUND -> lowerBound = x
            GraphHandle.UPPER_BOUND -> upperBound = x
        }
    }

    fun handleAt(position: Offset): GraphHandle? {
        val vp = viewport()
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

    fun onTap(position: Offset) {
        when (tool) {
            GraphTool.TRACE, GraphTool.TANGENT -> moveHandle(GraphHandle.CURSOR, position.x)
            GraphTool.INTEGRAL -> {
                // Move whichever bound is closer to the tap
                val vp = viewport()
                val closerToLower = abs(vp.toScreenX(lowerBound) - position.x) <= abs(vp.toScreenX(upperBound) - position.x)
                moveHandle(if (closerToLower) GraphHandle.LOWER_BOUND else GraphHandle.UPPER_BOUND, position.x)
            }
            null -> {}
        }
    }

    /** Pans and zooms so that the point under [centroid] stays under the fingers. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScaleX = (scaleX * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val newScaleY = (scaleY * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val originX = canvasSize.width / 2f + offsetX
        val originY = canvasSize.height / 2f + offsetY
        offsetX += centroid.x - (centroid.x - originX) * (newScaleX / scaleX) - originX + pan.x
        offsetY += centroid.y - (centroid.y - originY) * (newScaleY / scaleY) - originY + pan.y
        scaleX = newScaleX
        scaleY = newScaleY
    }

    fun selectTool(selected: GraphTool) {
        if (tool == selected) {
            tool = null
            return
        }
        // Bring the handles into view if they were panned away
        val span = visibleMaxX - visibleMinX
        val center = (visibleMinX + visibleMaxX) / 2.0
        when (selected) {
            GraphTool.TRACE, GraphTool.TANGENT ->
                if (cursorX !in visibleMinX..visibleMaxX) cursorX = center
            GraphTool.INTEGRAL ->
                if (lowerBound !in visibleMinX..visibleMaxX || upperBound !in visibleMinX..visibleMaxX) {
                    lowerBound = center - span / 4.0
                    upperBound = center + span / 4.0
                }
        }
        tool = selected
    }

    val textMeasurer = rememberTextMeasurer()
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val gridColor = if (isDark) Color(0xFF2A2D35) else Color(0xFFE0E3EA)
    val axisColor = if (isDark) Color(0xFF6B7280) else Color(0xFF9CA3AF)
    val curveColor = OrangePrimary
    val labelColor = if (isDark) Color(0xFFA0A6B2) else Color(0xFF606675)
    val handleColor = MaterialTheme.colorScheme.onBackground
    val backgroundColor = MaterialTheme.colorScheme.background

    // Trace snaps exactly onto key points, but they are recomputed after panning, so compare loosely
    val snappedPoint = if (tool == GraphTool.TRACE) {
        keyPoints.firstOrNull { abs(it.x - cursorX) <= 1e-9 * max(1.0, abs(cursorX)) }
    } else null

    Column(
        modifier = modifier
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        GraphHeader(
            expression = expression,
            onRecenter = {
                scaleX = DEFAULT_SCALE
                scaleY = DEFAULT_SCALE
                offsetX = 0f
                offsetY = 0f
            },
            onClose = onClose
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(function) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val handle = if (function != null) handleAt(down.position) else null
                            if (handle != null) {
                                // Dragging a handle follows the first finger only
                                moveHandle(handle, down.position.x)
                                drag(down.id) { change ->
                                    moveHandle(handle, change.position.x)
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
                            if (!isTransforming && function != null) onTap(down.position)
                        }
                    }
            ) {
                val vp = viewport()
                val width = size.width
                val height = size.height

                drawGrid(vp.originX, vp.originY, width, height, scaleX, scaleY, gridColor, axisColor, labelColor, textMeasurer)

                if (function != null) {
                    if (tool == GraphTool.INTEGRAL) {
                        drawIntegralArea(function, vp, lowerBound, upperBound, curveColor, AccentRed)
                    }
                    drawCurve(function, vp.originX, vp.originY, scaleX, scaleY, curveColor)

                    when (tool) {
                        GraphTool.TRACE -> {
                            drawKeyPoints(keyPoints, vp, backgroundColor, textMeasurer)
                            drawCursor(function, vp, cursorX, snappedPoint?.kind?.color ?: curveColor, axisColor)
                        }
                        GraphTool.TANGENT -> {
                            val y = function.evaluateAt(cursorX)
                            val slope = function.derivativeAt(cursorX)
                            if (y.isFinite() && slope.isFinite()) {
                                // Line through (x, y) with the slope, drawn across the whole width
                                val x0 = vp.toMathX(0f)
                                val x1 = vp.toMathX(width)
                                drawLine(
                                    color = AccentBlue,
                                    start = Offset(0f, vp.toScreenY(y + slope * (x0 - cursorX))),
                                    end = Offset(width, vp.toScreenY(y + slope * (x1 - cursorX))),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                            drawCursor(function, vp, cursorX, AccentBlue, axisColor)
                        }
                        GraphTool.INTEGRAL -> {
                            drawBoundHandle(vp, lowerBound, "a", handleColor, backgroundColor, textMeasurer)
                            drawBoundHandle(vp, upperBound, "b", handleColor, backgroundColor, textMeasurer)
                        }
                        null -> {}
                    }
                }
            }

            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                function == null -> Text(
                    text = "This expression cannot be plotted",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (function != null && tool != null) {
                GraphInfoCard(modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    when (tool) {
                        GraphTool.TRACE -> TraceInfo(function, cursorX, snappedPoint)
                        GraphTool.TANGENT -> TangentInfo(function, cursorX)
                        GraphTool.INTEGRAL -> IntegralInfo(lowerBound, upperBound, area)
                        null -> {}
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
                    enabled = function != null,
                    onClick = { selectTool(entry) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GraphHeader(expression: MathString, onRecenter: () -> Unit, onClose: (() -> Unit)?) {
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val isEquation = expression.nodes.any { it is OperatorNode && it.op == MathOp.EQUALS }
            if (!isEquation) {
                Text(
                    text = "y =",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            MathExpressionView(
                mathString = expression,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRecenter) {
                Icon(Icons.Default.Refresh, contentDescription = "Recenter")
            }
            if (onClose != null) {
                Button(onClick = onClose, shape = RoundedCornerShape(8.dp)) {
                    Text("Close")
                }
            }
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
        modifier = modifier,
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
            Text(tool.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
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
private fun TraceInfo(function: Function1D, x: Double, snapped: KeyPoint?) {
    InfoTitle(snapped?.kind?.label ?: "Trace", snapped?.kind?.color ?: OrangePrimary)
    InfoValues("x" to formatGraphNumber(x), "y" to formatGraphNumber(function.evaluateAt(x)))
    if (snapped == null) InfoHint("Drag along the graph; snaps to highlighted points")
}

@Composable
private fun TangentInfo(function: Function1D, x: Double) {
    val y = function.evaluateAt(x)
    val slope = function.derivativeAt(x)
    InfoTitle("Tangent", AccentBlue)
    InfoValues("x" to formatGraphNumber(x), "y" to formatGraphNumber(y))
    Text(
        text = "slope = ${formatGraphNumber(slope)}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    if (y.isFinite() && slope.isFinite()) InfoHint(formatLineEquation(slope, y - slope * x))
}

@Composable
private fun IntegralInfo(lowerBound: Double, upperBound: Double, area: Double?) {
    InfoTitle("Integral", OrangePrimary)
    InfoValues("a" to formatGraphNumber(lowerBound), "b" to formatGraphNumber(upperBound))
    Text(
        text = "\u222B f(x) dx = ${area?.let { formatGraphNumber(it) } ?: "…"}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    InfoHint("Drag a or b; they snap to zeros")
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
    if (value.isNaN()) return "undefined"
    if (value.isInfinite()) return if (value > 0) "\u221E" else "\u2212\u221E"
    if (abs(value) < 1e-9) return "0"
    val rounded = BigDecimal(value).round(java.math.MathContext(significantDigits)).stripTrailingZeros()
    val magnitude = abs(rounded.toDouble())
    if (magnitude >= 1e-4 && magnitude < 1e7) return rounded.toPlainString()
    val exponent = rounded.precision() - rounded.scale() - 1
    val mantissa = rounded.movePointLeft(exponent).toPlainString()
    return "${mantissa}\u00D710${toSuperscript(exponent)}"
}

/** Short x-value for point labels: at most three decimals, so -10.996 does not become -11. */
private fun formatKeyPointX(x: Double): String {
    if (abs(x) < 5e-4) return "0"
    return String.format(Locale.US, "%.3f", x).trimEnd('0').trimEnd('.')
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
        drawCircle(color = point.kind.color, radius = 3.5.dp.toPx(), center = center)

        val layout = textMeasurer.measure(
            text = AnnotatedString("x=${formatKeyPointX(point.x)}"),
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
    drawLine(
        color = guideColor.copy(alpha = 0.6f),
        start = Offset(cx, 0f),
        end = Offset(cx, size.height),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    )
    val y = function.evaluateAt(x)
    if (!y.isFinite()) return
    val center = Offset(cx, vp.toScreenY(y))
    if (center.y !in 0f..size.height) return
    drawCircle(color = Color.White, radius = 8.dp.toPx(), center = center)
    drawCircle(color = color, radius = 5.5.dp.toPx(), center = center)
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
    drawLine(color = color.copy(alpha = 0.7f), start = Offset(cx, 0f), end = Offset(cx, size.height), strokeWidth = 1.5.dp.toPx())
    val radius = 12.dp.toPx()
    val center = Offset(cx, vp.originY.coerceIn(radius, size.height - radius))
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
 */
private fun DrawScope.drawIntegralArea(
    function: Function1D,
    vp: Viewport,
    a: Double,
    b: Double,
    positiveColor: Color,
    negativeColor: Color
) {
    val left = vp.toScreenX(min(a, b)).coerceIn(0f, size.width)
    val right = vp.toScreenX(max(a, b)).coerceIn(0f, size.width)
    if (right <= left) return
    val limit = size.height * 3f
    val path = Path()
    path.moveTo(left, vp.originY)
    var px = left
    while (true) {
        val y = function.evaluateAt(vp.toMathX(px))
        // Gaps (undefined values) drop back to the axis
        val cy = if (y.isFinite()) vp.toScreenY(y).coerceIn(-limit, limit) else vp.originY
        path.lineTo(px, cy)
        if (px >= right) break
        px = min(px + 2f, right)
    }
    path.lineTo(right, vp.originY)
    path.close()

    clipRect(bottom = vp.originY) { drawPath(path, positiveColor.copy(alpha = 0.25f)) }
    clipRect(top = vp.originY) { drawPath(path, negativeColor.copy(alpha = 0.2f)) }
}

/**
 * Draws the curve sample by sample. The path is interrupted at gaps (undefined values) and at
 * discontinuities such as the poles of tan(x), which would otherwise be drawn as vertical lines.
 */
private fun DrawScope.drawCurve(
    function: Function1D,
    originX: Float,
    originY: Float,
    scaleX: Float,
    scaleY: Float,
    color: Color
) {
    val width = size.width
    val height = size.height
    val path = Path()
    var previousY: Float? = null
    val stepPx = 2f
    var px = 0f

    fun toCanvasY(mathX: Double): Float? {
        val mathY = function.evaluateAt(mathX)
        if (!mathY.isFinite() || abs(mathY) > 1e6) return null
        return originY - (mathY * scaleY).toFloat()
    }

    while (px <= width) {
        val mathX = ((px - originX) / scaleX).toDouble()
        val canvasY = toCanvasY(mathX)?.takeIf { it in -height * 2f..height * 3f }
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
                val midY = toCanvasY(((px - stepPx / 2f - originX) / scaleX).toDouble())
                connected = midY != null && midY in min(prev, canvasY)..max(prev, canvasY)
            }
            if (connected) path.lineTo(px, canvasY) else path.moveTo(px, canvasY)
            previousY = canvasY
        }
        px += stepPx
    }
    drawPath(path = path, color = color, style = Stroke(width = 3.5f))
}

private fun DrawScope.drawGrid(
    originX: Float,
    originY: Float,
    width: Float,
    height: Float,
    scaleX: Float,
    scaleY: Float,
    gridColor: Color,
    axisColor: Color,
    labelColor: Color,
    textMeasurer: TextMeasurer
) {
    // Choose nice step interval (1, 2, 5, 10, etc.)
    val minPixelSpacing = 60f
    val stepUnitX = calculateGridStep(scaleX, minPixelSpacing)
    val stepUnitY = calculateGridStep(scaleY, minPixelSpacing)
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    // Vertical grid lines at multiples of the step, labelled with their real value
    val firstX = ceil(-originX / scaleX / stepUnitX).toLong()
    val lastX = floor((width - originX) / scaleX / stepUnitX).toLong()
    for (k in firstX..lastX) {
        val x = originX + (k * stepUnitX * scaleX).toFloat()
        drawLine(gridColor, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
        if (k != 0L) {
            val layout = textMeasurer.measure(AnnotatedString(formatGridLabel(k * stepUnitX, stepUnitX)), labelStyle)
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, originY.coerceIn(10f, height - 30f) + 4f))
        }
    }

    // Horizontal grid lines
    val firstY = ceil((originY - height) / scaleY / stepUnitY).toLong()
    val lastY = floor(originY / scaleY / stepUnitY).toLong()
    for (k in firstY..lastY) {
        val y = originY - (k * stepUnitY * scaleY).toFloat()
        drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
        if (k != 0L) {
            val layout = textMeasurer.measure(AnnotatedString(formatGridLabel(k * stepUnitY, stepUnitY)), labelStyle)
            drawText(layout, topLeft = Offset(originX.coerceIn(10f, width - 40f) + 6f, y - layout.size.height / 2f))
        }
    }

    // Main X & Y axes
    drawLine(axisColor, Offset(0f, originY), Offset(width, originY), strokeWidth = 2f)
    drawLine(axisColor, Offset(originX, 0f), Offset(originX, height), strokeWidth = 2f)
}

/** Formats a grid value with just enough decimals for the step size (e.g. 0.5, 0.2). */
private fun formatGridLabel(value: Double, step: Double): String {
    val decimals = if (step >= 1.0) 0 else ceil(-log10(step)).toInt()
    return String.format(Locale.US, "%.${decimals}f", value)
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
