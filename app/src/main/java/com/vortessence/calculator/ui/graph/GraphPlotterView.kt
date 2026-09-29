package com.vortessence.calculator.ui.graph

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.MathEngine
import com.vortessence.calculator.core.math.PlotData
import com.vortessence.calculator.core.math.PlotPoint
import com.vortessence.calculator.core.math.solvers.Function1D
import com.vortessence.calculator.core.math.type.MathOp
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.OperatorNode
import com.vortessence.calculator.ui.math.MathExpressionView
import com.vortessence.calculator.ui.theme.AccentGreen
import com.vortessence.calculator.ui.theme.AccentRed
import com.vortessence.calculator.ui.theme.OrangePrimary
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.*

@Composable
fun GraphPlotterView(
    function: Function1D?,
    expression: MathString,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onClose: (() -> Unit)? = null
) {
    if (onClose != null) BackHandler(onBack = onClose)

    var scaleX by remember { mutableFloatStateOf(40f) } // pixels per unit
    var scaleY by remember { mutableFloatStateOf(40f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var tracePoint by remember { mutableStateOf<PlotPoint?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var plotData by remember(function) { mutableStateOf(PlotData(emptyList(), emptyList())) }

    // Roots and extrema of the visible x-range, recomputed in the background after panning
    // or zooming settles
    val visibleMinX = (-(canvasSize.width / 2f + offsetX) / scaleX).toDouble()
    val visibleMaxX = ((canvasSize.width / 2f - offsetX) / scaleX).toDouble()
    LaunchedEffect(function, visibleMinX, visibleMaxX) {
        if (function == null || canvasSize.width == 0) return@LaunchedEffect
        delay(150)
        plotData = MathEngine.analyzePlot(function, visibleMinX, visibleMaxX)
    }

    val textMeasurer = rememberTextMeasurer()
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f

    val gridColor = if (isDark) Color(0xFF2A2D35) else Color(0xFFE0E3EA)
    val axisColor = if (isDark) Color(0xFF6B7280) else Color(0xFF9CA3AF)
    val curveColor = OrangePrimary
    val rootColor = AccentRed
    val extremaColor = AccentGreen
    val labelColor = if (isDark) Color(0xFFA0A6B2) else Color(0xFF606675)

    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scaleX = (scaleX * zoom).coerceIn(5f, 400f)
                        scaleY = (scaleY * zoom).coerceIn(5f, 400f)
                        offsetX += pan.x
                        offsetY += pan.y
                        tracePoint = null
                    }
                }
                .pointerInput(function) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Trace with a single finger only; two fingers are a zoom gesture
                            val change = event.changes.singleOrNull()
                            if (change != null && change.pressed && function != null) {
                                val originX = size.width / 2f + offsetX
                                val mathX = ((change.position.x - originX) / scaleX).toDouble()
                                val mathY = function.evaluateAt(mathX)
                                tracePoint = if (mathY.isFinite()) PlotPoint(mathX, mathY) else null
                            } else {
                                tracePoint = null
                            }
                        }
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val originX = width / 2f + offsetX
            val originY = height / 2f + offsetY

            // 1. Draw Grid Lines
            drawGrid(originX, originY, width, height, scaleX, scaleY, gridColor, axisColor, labelColor, textMeasurer)

            // 2. Draw Function Curve
            if (function != null) {
                drawCurve(function, originX, originY, scaleX, scaleY, curveColor)
            }

            // 3. Draw Roots (x-intercepts)
            for (rootX in plotData.roots) {
                val cx = originX + (rootX * scaleX).toFloat()
                val cy = originY

                if (cx in 0f..width) {
                    drawCircle(color = rootColor, radius = 6f, center = Offset(cx, cy))
                    drawCircle(color = Color.White, radius = 3f, center = Offset(cx, cy))

                    val rootText = String.format(Locale.US, "x=%.2f", rootX)
                    val layout = textMeasurer.measure(
                        text = AnnotatedString(rootText),
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = rootColor)
                    )
                    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy + 10f))
                }
            }

            // 4. Draw Extrema
            for (ext in plotData.extrema) {
                val cx = originX + (ext.x * scaleX).toFloat()
                val cy = originY - (ext.y * scaleY).toFloat()

                if (cx in 0f..width && cy in 0f..height) {
                    drawCircle(color = extremaColor, radius = 6f, center = Offset(cx, cy))
                    drawCircle(color = Color.White, radius = 3f, center = Offset(cx, cy))

                    val extText = String.format(Locale.US, "(%.2f, %.2f)", ext.x, ext.y)
                    val layout = textMeasurer.measure(
                        text = AnnotatedString(extText),
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = extremaColor)
                    )
                    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - 24f))
                }
            }

            // 5. Draw Tracer
            tracePoint?.let { pt ->
                val cx = originX + (pt.x * scaleX).toFloat()
                val cy = originY - (pt.y * scaleY).toFloat()

                if (cx in 0f..width && cy in 0f..height) {
                    drawCircle(color = Color.White, radius = 10f, center = Offset(cx, cy))
                    drawCircle(color = curveColor, radius = 7f, center = Offset(cx, cy))

                    val traceText = String.format(Locale.US, "(%.3f, %.3f)", pt.x, pt.y)
                    val layout = textMeasurer.measure(
                        text = AnnotatedString(traceText),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = labelColor)
                    )
                    drawText(
                        layout,
                        topLeft = Offset(
                            (cx + 15f).coerceAtMost(width - layout.size.width - 10f),
                            (cy - 40f).coerceAtLeast(10f)
                        )
                    )
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

        // Header controls (Expression & Reset zoom)
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
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
                    modifier = Modifier.weight(1f, fill = false)
                )
                IconButton(
                    onClick = {
                        scaleX = 40f
                        scaleY = 40f
                        offsetX = 0f
                        offsetY = 0f
                    }
                ) {
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
