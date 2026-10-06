package com.vortessence.calculator.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.solvers.Surface3D
import com.vortessence.calculator.ui.theme.AccentBlue
import com.vortessence.calculator.ui.theme.AccentGreen
import com.vortessence.calculator.ui.theme.OrangePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Grid cells per side; the surface is sampled at (CELLS + 1)² points. */
private const val CELLS = 40
private const val DEFAULT_HALF_RANGE = 5.0
private const val DEFAULT_YAW = -0.75f
private const val DEFAULT_PITCH = 0.5f

/** Rotation and visible ranges of a [SurfacePlot]; [reset] restores the initial view. */
@Stable
class SurfaceViewState {
    /** Rotation about the vertical axis, in radians. */
    var yaw by mutableFloatStateOf(DEFAULT_YAW)
    /** Elevation of the viewer, in radians: 0 looks from the side, π/2 from above. */
    var pitch by mutableFloatStateOf(DEFAULT_PITCH)
    /** x and y range from −halfRange to +halfRange. */
    var halfRange by mutableStateOf(DEFAULT_HALF_RANGE)
    /**
     * The height range of the box, zCenter ± zHalf; null until it is fitted to the surface
     * (when the graph opens and after [reset]).
     */
    var zCenter by mutableStateOf<Double?>(null)
    var zHalf by mutableStateOf<Double?>(null)

    /** Zooms all axes together, so the surface's shape changes like on a 2D graph. */
    fun zoom(factor: Float) {
        val newRange = (halfRange / factor).coerceIn(0.01, 10_000.0)
        zHalf = zHalf?.times(newRange / halfRange)
        halfRange = newRange
    }

    fun reset() {
        yaw = DEFAULT_YAW
        pitch = DEFAULT_PITCH
        halfRange = DEFAULT_HALF_RANGE
        zCenter = null
        zHalf = null
    }
}

/**
 * Samples of the surface on the grid, row by row (index j·(CELLS+1) + i for x_i, y_j), with the
 * height range that fits them (used for the initial view). Undefined points are NaN.
 */
private class SurfaceMesh(val halfRange: Double, val z: DoubleArray, val zMin: Double, val zMax: Double)

private fun sampleSurface(surface: Surface3D, halfRange: Double): SurfaceMesh {
    val n = CELLS + 1
    val z = DoubleArray(n * n)
    for (j in 0 until n) {
        val y = -halfRange + 2 * halfRange * j / CELLS
        for (i in 0 until n) {
            val x = -halfRange + 2 * halfRange * i / CELLS
            z[j * n + i] = surface.evaluateAt(x, y)
        }
    }
    // A pole would squash the rest of the surface flat: if the most extreme 2% at an end lie
    // far outside the rest, the height range leaves them out (they are clamped)
    val finite = z.filter { it.isFinite() }.sorted()
    var zMin = finite.firstOrNull() ?: -1.0
    var zMax = finite.lastOrNull() ?: 1.0
    if (finite.isNotEmpty()) {
        val low = finite[(finite.size * 0.02).toInt()]
        val high = finite[((finite.size - 1) * 0.98).toInt()]
        val spread = high - low
        if (low - zMin > 2 * spread) zMin = low
        if (zMax - high > 2 * spread) zMax = high
    }
    if (zMax - zMin < 1e-9 * maxOf(1.0, abs(zMax))) {
        zMin -= 1.0
        zMax += 1.0
    }
    return SurfaceMesh(halfRange, z, zMin, zMax)
}

/**
 * A surface z = f(x, y) as a shaded, height-colored mesh in a box. Drag to rotate, pinch to
 * zoom (all axes together: a smaller or larger x/y range and height range).
 */
@Composable
fun SurfacePlot(surface: Surface3D, state: SurfaceViewState, modifier: Modifier = Modifier) {
    var mesh by remember(surface) { mutableStateOf<SurfaceMesh?>(null) }
    LaunchedEffect(surface, state.halfRange) {
        val range = state.halfRange
        val sampled = withContext(Dispatchers.Default) { sampleSurface(surface, range) }
        // The box's height range is fitted once; zooming scales it from there
        if (state.zHalf == null) {
            state.zCenter = (sampled.zMin + sampled.zMax) / 2
            state.zHalf = (sampled.zMax - sampled.zMin) / 2
        }
        mesh = sampled
    }

    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    val floorColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
    val meshLineColor = MaterialTheme.colorScheme.background.copy(alpha = 0.35f)

    Box(modifier = modifier.clipToBounds()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                // Pinch out shows a smaller range (zooms in)
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) state.zoom(zoom)
                            } else {
                                val pan = event.calculatePan()
                                state.yaw += pan.x * 0.008f
                                state.pitch = (state.pitch + pan.y * 0.008f).coerceIn(-1.5f, 1.5f)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
        ) {
            val current = mesh ?: return@Canvas
            val zCenter = state.zCenter ?: return@Canvas
            val zHalf = state.zHalf ?: return@Canvas
            drawSurface(current, zCenter - zHalf, zCenter + zHalf, state.yaw, state.pitch, floorColor, meshLineColor, labelColor, textMeasurer)
        }
        Text(
            text = "Drag to rotate · pinch to zoom",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        )
    }
}

/** Projects box coordinates (each −1..1, z up) onto the screen; also returns the depth. */
private class Projection(yaw: Float, pitch: Float, private val cx: Float, private val cy: Float, private val scale: Float) {
    private val cosYaw = cos(yaw)
    private val sinYaw = sin(yaw)
    private val cosPitch = cos(pitch)
    private val sinPitch = sin(pitch)

    fun screen(x: Float, y: Float, z: Float): Offset {
        val xr = x * cosYaw - y * sinYaw
        val yr = x * sinYaw + y * cosYaw
        val up = z * cosPitch + yr * sinPitch
        return Offset(cx + scale * xr, cy - scale * up)
    }

    /** Larger is farther away. */
    fun depth(x: Float, y: Float, z: Float): Float {
        val yr = x * sinYaw + y * cosYaw
        return yr * cosPitch - z * sinPitch
    }

    /** How directly a surface with normal (nx, ny, nz) faces the viewer, 0..1. */
    fun facing(nx: Float, ny: Float, nz: Float): Float {
        // The viewing direction in box coordinates (towards the viewer)
        val vx = -sinYaw * cosPitch
        val vy = -cosYaw * cosPitch
        val vz = sinPitch
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length == 0f) return 1f
        return abs(nx * vx + ny * vy + nz * vz) / length
    }
}

/**
 * The part of [polygon] (corners x, y, z) below [limit] (or above it if not [keepBelow]), cut
 * along the plane z = limit (Sutherland–Hodgman).
 */
private fun clipAtHeight(polygon: List<FloatArray>, limit: Float, keepBelow: Boolean): List<FloatArray> {
    fun inside(p: FloatArray) = if (keepBelow) p[2] <= limit else p[2] >= limit
    if (polygon.all { inside(it) }) return polygon
    val result = ArrayList<FloatArray>(polygon.size + 2)
    for (index in polygon.indices) {
        val a = polygon[index]
        val b = polygon[(index + 1) % polygon.size]
        if (inside(a)) result.add(a)
        if (inside(a) != inside(b)) {
            val t = (limit - a[2]) / (b[2] - a[2])
            result.add(floatArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), limit))
        }
    }
    return result
}

/** Low to high: blue, green, orange. */
private fun heightColor(t: Float): Color =
    if (t < 0.5f) lerp(AccentBlue, AccentGreen, t * 2f) else lerp(AccentGreen, OrangePrimary, (t - 0.5f) * 2f)

/** Draws [mesh] in a box whose height spans [zLow]..[zHigh]; the surface is cut off beyond it. */
private fun DrawScope.drawSurface(
    mesh: SurfaceMesh,
    zLow: Double,
    zHigh: Double,
    yaw: Float,
    pitch: Float,
    floorColor: Color,
    meshLineColor: Color,
    labelColor: Color,
    textMeasurer: TextMeasurer
) {
    val n = CELLS + 1
    // Small enough that the box and its labels fit in any rotation
    val projection = Projection(yaw, pitch, size.width / 2f, size.height / 2f, min(size.width, size.height) * 0.27f)
    val zSpan = zHigh - zLow
    // Box coordinates of the samples: x, y and the height each in −1..1 (heights beyond the box
    // can be larger; they are clamped when drawn)
    fun bx(i: Int) = -1f + 2f * i / CELLS
    val bz = FloatArray(n * n) { k ->
        val z = mesh.z[k]
        if (z.isFinite()) (2.0 * (z - zLow) / zSpan - 1.0).coerceIn(-1e6, 1e6).toFloat() else Float.NaN
    }

    // The floor of the box with the x and y axes through its middle
    val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
    val floor = Path().apply {
        corners.forEachIndexed { index, (x, y) ->
            val p = projection.screen(x, y, -1f)
            if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(floor, floorColor, style = Stroke(width = 1.dp.toPx()))
    drawLine(floorColor, projection.screen(-1f, 0f, -1f), projection.screen(1f, 0f, -1f), strokeWidth = 1.dp.toPx())
    drawLine(floorColor, projection.screen(0f, -1f, -1f), projection.screen(0f, 1f, -1f), strokeWidth = 1.dp.toPx())

    // The cells as polygons (x, y, z per corner), cut off at the top and bottom of the box, far
    // ones first so that near ones are painted over them
    class Cell(val polygon: List<FloatArray>, val depth: Float)
    val cells = ArrayList<Cell>(CELLS * CELLS)
    for (j in 0 until CELLS) for (i in 0 until CELLS) {
        val k = j * n + i
        val corners3d = listOf(
            floatArrayOf(bx(i), bx(j), bz[k]),
            floatArrayOf(bx(i + 1), bx(j), bz[k + 1]),
            floatArrayOf(bx(i + 1), bx(j + 1), bz[k + n + 1]),
            floatArrayOf(bx(i), bx(j + 1), bz[k + n])
        )
        if (corners3d.any { it[2].isNaN() }) continue
        val polygon = clipAtHeight(clipAtHeight(corners3d, 1f, keepBelow = true), -1f, keepBelow = false)
        if (polygon.size < 3) continue
        val depth = polygon.map { projection.depth(it[0], it[1], it[2]) }.average().toFloat()
        cells.add(Cell(polygon, depth))
    }
    cells.sortByDescending { it.depth }

    val path = Path()
    val outline = Stroke(width = 0.5.dp.toPx())
    for (cell in cells) {
        val polygon = cell.polygon
        path.reset()
        polygon.forEachIndexed { index, p ->
            val screen = projection.screen(p[0], p[1], p[2])
            if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        path.close()
        // Normal by Newell's method (works for the cut polygons too); shading by how it faces the viewer
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (index in polygon.indices) {
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            nx += (a[1] - b[1]) * (a[2] + b[2])
            ny += (a[2] - b[2]) * (a[0] + b[0])
            nz += (a[0] - b[0]) * (a[1] + b[1])
        }
        val brightness = 0.45f + 0.55f * projection.facing(nx, ny, nz)
        val t = (polygon.map { it[2] }.average().toFloat() + 1f) / 2f
        val base = heightColor(t)
        val color = Color(base.red * brightness, base.green * brightness, base.blue * brightness)
        drawPath(path, color)
        drawPath(path, meshLineColor, style = outline)
    }

    // Axis names at the ends of the floor axes, the ranges at the box corners
    val style = TextStyle(color = labelColor, fontSize = 12.sp)
    fun label(text: String, at: Offset) {
        val layout = textMeasurer.measure(text, style)
        drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
    }
    label("x", projection.screen(1.18f, 0f, -1f))
    label("y", projection.screen(0f, 1.18f, -1f))
    val range = formatGraphNumber(mesh.halfRange, 3)
    label("±$range", projection.screen(1.18f, 1.18f, -1f))
    // The height range on the vertical edge at the far left corner
    val edge = corners.minBy { (x, y) -> projection.screen(x, y, 0f).x }
    val bottom = projection.screen(edge.first, edge.second, -1f)
    val top = projection.screen(edge.first, edge.second, 1f)
    drawLine(floorColor, bottom, top, strokeWidth = 1.dp.toPx())
    label("z", Offset(top.x, top.y - 16.dp.toPx()))
    // Beside the edge, on the outer side
    val side = if (top.x < size.width / 2f) -1f else 1f
    label(formatGraphNumber(zHigh, 3), Offset(top.x + side * 24.dp.toPx(), top.y))
    label(formatGraphNumber(zLow, 3), Offset(bottom.x + side * 24.dp.toPx(), bottom.y))
}
