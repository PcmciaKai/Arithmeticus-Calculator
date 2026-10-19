package com.vortessence.arithmeticus.ui.graph

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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import com.vortessence.arithmeticus.core.math.solvers.Surface3D
import com.vortessence.arithmeticus.ui.theme.AccentBlue
import com.vortessence.arithmeticus.ui.theme.AccentGreen
import com.vortessence.arithmeticus.ui.theme.OrangePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.compose.ui.res.stringResource
import com.vortessence.arithmeticus.R

/** Grid cells per side; the surface is sampled at (CELLS + 1)² points. */
private const val CELLS = 48
/** The sampled area is this much larger than the visible one, so moving shows surface at once. */
private const val SAMPLE_MARGIN = 1.25
private const val DEFAULT_HALF_RANGE = 5.0
private const val DEFAULT_YAW = -0.75f
private const val DEFAULT_PITCH = 0.5f
/** The box's half-width on screen, as a fraction of the smaller side of the view. */
private const val BOX_SCALE = 0.27f

/**
 * Rotation and visible region of a [SurfacePlot]: x and y in [centerX] / [centerY] ± [halfRange],
 * z in [zCenter] ± [zHalf]. [reset] restores the initial view.
 */
@Stable
class SurfaceViewState {
    /** Rotation about the vertical axis, in radians. */
    var yaw by mutableFloatStateOf(DEFAULT_YAW)
    /** Elevation of the viewer, in radians: 0 looks from the side, π/2 from above. */
    var pitch by mutableFloatStateOf(DEFAULT_PITCH)
    var centerX by mutableDoubleStateOf(0.0)
    var centerY by mutableDoubleStateOf(0.0)
    var halfRange by mutableDoubleStateOf(DEFAULT_HALF_RANGE)
    /** Null until fitted to the surface (when the graph opens and after [reset]). */
    var zCenter by mutableStateOf<Double?>(null)
    var zHalf by mutableStateOf<Double?>(null)

    /** Zooms all axes together, so the surface's shape changes like on a 2D graph. */
    fun zoom(factor: Float) {
        val newRange = (halfRange / factor).coerceIn(1e-3, 1e5)
        zHalf = zHalf?.times(newRange / halfRange)
        halfRange = newRange
    }

    /**
     * Moves the view by a finger movement of ([dx], [dy]) pixels, where one box unit (half the
     * box) is [boxScale] pixels: the surface follows the finger. Seen from above a movement
     * goes along x and y, seen from the side up and down along z.
     */
    fun pan(dx: Float, dy: Float, boxScale: Float) {
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        // Screen right is the rotated x direction; screen up is shared between the rotated
        // y direction (seen from above) and z (seen from the side)
        val xr = dx / boxScale
        val up = -dy / boxScale
        val yr = up * sin(pitch)
        val z = up * cos(pitch)
        val bx = xr * cosYaw + yr * sinYaw
        val by = -xr * sinYaw + yr * cosYaw
        centerX -= bx * halfRange
        centerY -= by * halfRange
        zCenter = zCenter?.let { it - z * (zHalf ?: 0.0) }
    }

    fun reset() {
        yaw = DEFAULT_YAW
        pitch = DEFAULT_PITCH
        centerX = 0.0
        centerY = 0.0
        halfRange = DEFAULT_HALF_RANGE
        zCenter = null
        zHalf = null
    }
}

/**
 * Samples of the surface on the grid over [centerX] / [centerY] ± [halfRange], row by row
 * (index j·(CELLS+1) + i for x_i, y_j), with the height range that fits them (used for the
 * initial view). Undefined points are NaN.
 */
private class SurfaceMesh(
    val centerX: Double,
    val centerY: Double,
    val halfRange: Double,
    val z: DoubleArray,
    val zMin: Double,
    val zMax: Double
)

private data class SampledRegion(val centerX: Double, val centerY: Double, val halfRange: Double, val fitting: Boolean)

/** Samples the surface in the background. */
private suspend fun sampleSurface(surface: Surface3D, centerX: Double, centerY: Double, halfRange: Double): SurfaceMesh =
    withContext(Dispatchers.Default) {
        val n = CELLS + 1
        val z = DoubleArray(n * n)
        for (j in 0 until n) {
            ensureActive()
            val y = centerY - halfRange + 2 * halfRange * j / CELLS
            for (i in 0 until n) {
                val x = centerX - halfRange + 2 * halfRange * i / CELLS
                z[j * n + i] = surface.evaluateAt(x, y)
            }
        }
        // A pole would squash the rest of the surface flat: if the most extreme 2% at an end lie
        // far outside the rest, the fitted range leaves them out (they are cut off)
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
        SurfaceMesh(centerX, centerY, halfRange, z, zMin, zMax)
    }

/**
 * A surface z = f(x, y) as a shaded, height-colored mesh in a box, with the axes through the
 * origin. One finger rotates; two fingers move the view and pinch to zoom (all axes together).
 */
@Composable
fun SurfacePlot(surface: Surface3D, state: SurfaceViewState, modifier: Modifier = Modifier) {
    var mesh by remember(surface) { mutableStateOf<SurfaceMesh?>(null) }
    // The latest mesh is always drawn in the current view, so moving and zooming follow the
    // fingers without waiting. During a gesture each sampling finishes and is followed by one
    // for the view at that time (views in between are skipped), so the surface keeps filling in.
    LaunchedEffect(surface) {
        // A reset (height range null again) needs a new fit even if the region is the same
        snapshotFlow { SampledRegion(state.centerX, state.centerY, state.halfRange, fitting = state.zHalf == null) }
            .conflate()
            .collect { (centerX, centerY, halfRange, fitting) ->
                val sampled = sampleSurface(surface, centerX, centerY, halfRange * if (fitting) 1.0 else SAMPLE_MARGIN)
                // The box's height range is fitted once (to the visible area); zooming and moving
                // change it from there
                if (state.zHalf == null) {
                    state.zCenter = (sampled.zMin + sampled.zMax) / 2
                    state.zHalf = (sampled.zMax - sampled.zMin) / 2
                }
                mesh = sampled
            }
    }

    val textMeasurer = rememberTextMeasurer()
    val colors = PlotColors(
        label = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        grid = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f),
        axis = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
        meshLine = MaterialTheme.colorScheme.background.copy(alpha = 0.35f)
    )

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
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) state.zoom(zoom)
                                val pan = event.calculatePan()
                                state.pan(pan.x, pan.y, min(size.width, size.height) * BOX_SCALE)
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
            val view = SurfaceView(
                centerX = state.centerX,
                centerY = state.centerY,
                halfRange = state.halfRange,
                zLow = zCenter - zHalf,
                zHigh = zCenter + zHalf,
                projection = Projection(state.yaw, state.pitch, size.width / 2f, size.height / 2f, min(size.width, size.height) * BOX_SCALE)
            )
            drawSurface(current, view, colors, textMeasurer)
        }
        Text(
            text = stringResource(R.string.graph_surface_hint),
            style = MaterialTheme.typography.labelMedium,
            color = colors.label,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        )
    }
}

private class PlotColors(val label: Color, val grid: Color, val axis: Color, val meshLine: Color)

/** The visible region, and conversions from its coordinates to box coordinates (each −1..1). */
private class SurfaceView(
    val centerX: Double,
    val centerY: Double,
    val halfRange: Double,
    val zLow: Double,
    val zHigh: Double,
    val projection: Projection
) {
    fun boxX(x: Double) = ((x - centerX) / halfRange).toFloat()
    fun boxY(y: Double) = ((y - centerY) / halfRange).toFloat()
    fun boxZ(z: Double) = (2.0 * (z - zLow) / (zHigh - zLow) - 1.0).coerceIn(-1e6, 1e6).toFloat()
}

/** Projects box coordinates (each −1..1, z up) onto the screen; also gives the depth. */
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

    fun screen(p: FloatArray) = screen(p[0], p[1], p[2])

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
 * The part of [polygon] (corners x, y, z) where coordinate [axis] is at most [limit] (at
 * least, if not [keepBelow]), cut along that plane (Sutherland–Hodgman).
 */
private fun clip(polygon: List<FloatArray>, axis: Int, limit: Float, keepBelow: Boolean): List<FloatArray> {
    fun inside(p: FloatArray) = if (keepBelow) p[axis] <= limit else p[axis] >= limit
    if (polygon.all { inside(it) }) return polygon
    val result = ArrayList<FloatArray>(polygon.size + 2)
    for (index in polygon.indices) {
        val a = polygon[index]
        val b = polygon[(index + 1) % polygon.size]
        if (inside(a)) result.add(a)
        if (inside(a) != inside(b)) {
            val t = (limit - a[axis]) / (b[axis] - a[axis])
            result.add(FloatArray(3) { k -> if (k == axis) limit else a[k] + t * (b[k] - a[k]) })
        }
    }
    return result
}

/** The part of [polygon] inside the box. */
private fun clipToBox(polygon: List<FloatArray>): List<FloatArray> {
    var result = polygon
    for (axis in 0..2) {
        result = clip(result, axis, 1f, keepBelow = true)
        if (result.size < 3) return result
        result = clip(result, axis, -1f, keepBelow = false)
        if (result.size < 3) return result
    }
    return result
}

/** Low to high: blue, green, orange. */
private fun heightColor(t: Float): Color =
    if (t < 0.5f) lerp(AccentBlue, AccentGreen, t * 2f) else lerp(AccentGreen, OrangePrimary, (t - 0.5f) * 2f)

/** A step of 1, 2 or 5 × 10ⁿ that gives about [count] ticks over [span]. */
private fun niceStep(span: Double, count: Int): Double {
    val raw = span / count
    val magnitude = 10.0.pow(floor(log10(raw)))
    val fraction = raw / magnitude
    return magnitude * when {
        fraction < 1.5 -> 1.0
        fraction < 3.5 -> 2.0
        fraction < 7.5 -> 5.0
        else -> 10.0
    }
}

/** The multiples of [step] in [low]..[high]. */
private fun ticks(low: Double, high: Double, step: Double): List<Double> =
    (ceil(low / step).toLong()..floor(high / step).toLong()).map { it * step }

/** Something drawn in 3D; drawn back to front, so that near things cover far ones. */
private sealed class Primitive(val depth: Float)
private class Face(val polygon: List<FloatArray>, val color: Color, depth: Float) : Primitive(depth)
private class Segment(val a: FloatArray, val b: FloatArray, val color: Color, val width: Float, depth: Float) : Primitive(depth)
/** A text centered at [anchor] on screen, moved by ([dx], [dy]) pixels. */
private class Label(val text: String, val anchor: FloatArray, val dx: Float, val dy: Float, val style: TextStyle, depth: Float) : Primitive(depth)
/** An arrowhead at [tip], pointing away from [from]. */
private class Arrowhead(val from: FloatArray, val tip: FloatArray, val color: Color, depth: Float) : Primitive(depth)

/** How far the axes reach beyond the box, to their arrowheads. */
private const val AXIS_END = 1.1f

private fun DrawScope.drawSurface(mesh: SurfaceMesh, view: SurfaceView, colors: PlotColors, textMeasurer: TextMeasurer) {
    val projection = view.projection
    val n = CELLS + 1
    val primitives = ArrayList<Primitive>(CELLS * CELLS + 400)

    fun depthOf(points: List<FloatArray>) = points.map { projection.depth(it[0], it[1], it[2]) }.average().toFloat()

    // A line in the box, split into short pieces so that each is sorted correctly against the surface
    fun line(a: FloatArray, b: FloatArray, color: Color, width: Float) {
        val length = sqrt((0..2).sumOf { ((b[it] - a[it]) * (b[it] - a[it])).toDouble() }).toFloat()
        val pieces = max(1, ceil(length / 0.1f).toInt())
        for (k in 0 until pieces) {
            val p = FloatArray(3) { a[it] + (b[it] - a[it]) * k / pieces }
            val q = FloatArray(3) { a[it] + (b[it] - a[it]) * (k + 1) / pieces }
            primitives.add(Segment(p, q, color, width, depthOf(listOf(p, q))))
        }
    }

    // The surface cells, in the current view (the mesh may still be from a previous one)
    fun meshX(i: Int) = view.boxX(mesh.centerX - mesh.halfRange + 2 * mesh.halfRange * i / CELLS)
    fun meshY(j: Int) = view.boxY(mesh.centerY - mesh.halfRange + 2 * mesh.halfRange * j / CELLS)
    val bz = FloatArray(n * n) { k -> if (mesh.z[k].isFinite()) view.boxZ(mesh.z[k]) else Float.NaN }
    for (j in 0 until CELLS) for (i in 0 until CELLS) {
        val k = j * n + i
        val corners = listOf(
            floatArrayOf(meshX(i), meshY(j), bz[k]),
            floatArrayOf(meshX(i + 1), meshY(j), bz[k + 1]),
            floatArrayOf(meshX(i + 1), meshY(j + 1), bz[k + n + 1]),
            floatArrayOf(meshX(i), meshY(j + 1), bz[k + n])
        )
        if (corners.any { it[2].isNaN() }) continue
        val polygon = clipToBox(corners)
        if (polygon.size < 3) continue
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
        val base = heightColor((polygon.map { it[2] }.average().toFloat() + 1f) / 2f)
        primitives.add(Face(polygon, Color(base.red * brightness, base.green * brightness, base.blue * brightness), depthOf(polygon)))
    }

    // The plane z = 0 with grid lines, and the axes through the origin; if the origin is outside
    // the view, they stay at the nearest side of the box
    val planeZ = view.boxZ(0.0).coerceIn(-1f, 1f)
    val axisX = view.boxX(0.0).coerceIn(-1f, 1f)
    val axisY = view.boxY(0.0).coerceIn(-1f, 1f)
    val gridWidth = 1.dp.toPx()
    val axisWidth = 1.8.dp.toPx()
    val xyStep = niceStep(2 * view.halfRange, 6)
    val xTicks = ticks(view.centerX - view.halfRange, view.centerX + view.halfRange, xyStep)
    val yTicks = ticks(view.centerY - view.halfRange, view.centerY + view.halfRange, xyStep)
    for (x in xTicks) line(floatArrayOf(view.boxX(x), -1f, planeZ), floatArrayOf(view.boxX(x), 1f, planeZ), colors.grid, gridWidth)
    for (y in yTicks) line(floatArrayOf(-1f, view.boxY(y), planeZ), floatArrayOf(1f, view.boxY(y), planeZ), colors.grid, gridWidth)
    for (edge in listOf(-1f, 1f)) {
        line(floatArrayOf(edge, -1f, planeZ), floatArrayOf(edge, 1f, planeZ), colors.grid, gridWidth)
        line(floatArrayOf(-1f, edge, planeZ), floatArrayOf(1f, edge, planeZ), colors.grid, gridWidth)
    }
    // The axes, with arrowheads at their positive ends just outside the box
    fun axis(from: FloatArray, tip: FloatArray) {
        line(from, tip, colors.axis, axisWidth)
        val beforeTip = FloatArray(3) { tip[it] - (tip[it] - from[it]) * 0.05f }
        primitives.add(Arrowhead(beforeTip, tip, colors.axis, depthOf(listOf(tip))))
    }
    axis(floatArrayOf(-1f, axisY, planeZ), floatArrayOf(AXIS_END, axisY, planeZ))
    axis(floatArrayOf(axisX, -1f, planeZ), floatArrayOf(axisX, AXIS_END, planeZ))
    axis(floatArrayOf(axisX, axisY, -1f), floatArrayOf(axisX, axisY, AXIS_END))
    // Tick marks
    val tick = 0.035f
    for (x in xTicks) line(floatArrayOf(view.boxX(x), axisY - tick, planeZ), floatArrayOf(view.boxX(x), axisY + tick, planeZ), colors.axis, axisWidth)
    for (y in yTicks) line(floatArrayOf(axisX - tick, view.boxY(y), planeZ), floatArrayOf(axisX + tick, view.boxY(y), planeZ), colors.axis, axisWidth)
    val zStep = niceStep(view.zHigh - view.zLow, 5)
    val zTicks = ticks(view.zLow, view.zHigh, zStep)
    for (z in zTicks) line(floatArrayOf(axisX - tick, axisY, view.boxZ(z)), floatArrayOf(axisX + tick, axisY, view.boxZ(z)), colors.axis, axisWidth)

    // The values at the ticks (zero only once, at the origin) and the axis names at the positive
    // ends, anchored at a point in the box and sorted like everything else, so the surface hides
    // them where it hides their axis. Slightly nearer than their anchor, so they are drawn over
    // their own tick mark.
    val style = TextStyle(color = colors.label, fontSize = 11.sp)
    val nameStyle = TextStyle(color = colors.axis, fontSize = 13.sp)
    val offset = 11.dp.toPx()
    fun label(text: String, at: FloatArray, dx: Float = 0f, dy: Float = 0f, labelStyle: TextStyle = style) {
        primitives.add(Label(text, at, dx, dy, labelStyle, projection.depth(at[0], at[1], at[2]) - 1e-3f))
    }
    for (x in xTicks) if (abs(x) > xyStep / 2) label(formatGraphNumber(x, 3), floatArrayOf(view.boxX(x), axisY, planeZ), dy = offset)
    for (y in yTicks) if (abs(y) > xyStep / 2) label(formatGraphNumber(y, 3), floatArrayOf(axisX, view.boxY(y), planeZ), dy = offset)
    for (z in zTicks) if (abs(z) > zStep / 2) label(formatGraphNumber(z, 3), floatArrayOf(axisX, axisY, view.boxZ(z)), dx = -2 * offset)
    if (view.boxX(0.0) in -1f..1f && view.boxY(0.0) in -1f..1f && view.boxZ(0.0) in -1f..1f) {
        label("0", floatArrayOf(axisX, axisY, planeZ), dx = -offset, dy = offset)
    }
    label("x", floatArrayOf(AXIS_END + 0.1f, axisY, planeZ), labelStyle = nameStyle)
    label("y", floatArrayOf(axisX, AXIS_END + 0.1f, planeZ), labelStyle = nameStyle)
    label("z", floatArrayOf(axisX, axisY, AXIS_END + 0.1f), labelStyle = nameStyle)

    primitives.sortByDescending { it.depth }
    val path = Path()
    val outline = Stroke(width = 0.5.dp.toPx())
    val arrowLength = 10.dp.toPx()
    val arrowHalfWidth = 4.5.dp.toPx()
    for (primitive in primitives) {
        when (primitive) {
            is Face -> {
                path.reset()
                primitive.polygon.forEachIndexed { index, p ->
                    val screen = projection.screen(p)
                    if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
                }
                path.close()
                drawPath(path, primitive.color)
                drawPath(path, colors.meshLine, style = outline)
            }
            is Segment -> drawLine(
                primitive.color, projection.screen(primitive.a), projection.screen(primitive.b),
                strokeWidth = primitive.width, cap = StrokeCap.Round
            )
            is Label -> {
                val at = projection.screen(primitive.anchor)
                val layout = textMeasurer.measure(primitive.text, primitive.style)
                drawText(layout, topLeft = Offset(at.x + primitive.dx - layout.size.width / 2f, at.y + primitive.dy - layout.size.height / 2f))
            }
            is Arrowhead -> {
                // A triangle of fixed size on screen, along the axis as it appears there
                val tip = projection.screen(primitive.tip)
                val direction = tip - projection.screen(primitive.from)
                val length = direction.getDistance()
                // An axis pointing straight at the viewer has no visible direction
                if (length < 0.01f) continue
                val unit = direction / length
                val normal = Offset(-unit.y, unit.x)
                val base = tip - unit * arrowLength
                path.reset()
                path.moveTo(tip.x, tip.y)
                path.lineTo(base.x + normal.x * arrowHalfWidth, base.y + normal.y * arrowHalfWidth)
                path.lineTo(base.x - normal.x * arrowHalfWidth, base.y - normal.y * arrowHalfWidth)
                path.close()
                drawPath(path, primitive.color)
            }
        }
    }
}
