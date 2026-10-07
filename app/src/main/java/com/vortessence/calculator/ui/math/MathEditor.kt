package com.vortessence.calculator.ui.math

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.type.MathString
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Editable math input with Android-like touch interaction:
 * - tap places the cursor at the nearest position,
 * - long press selects the token under the finger; keep dragging to extend the selection,
 * - while something is selected, two handles below its ends can be dragged to adjust it,
 * - with [actions], Android's floating Cut/Copy/Paste/Select all menu appears over a selection
 *   once the finger is lifted, and with Paste over the caret after a long press on empty space
 *   or a tap on the caret.
 *
 * All hit testing uses the node positions reported by [MathExpressionView] to [CaretGeometry].
 * Positions are caret paths (see CaretPaths), selections are applied by the caller.
 */
@Composable
fun MathEditor(
    expression: MathString,
    cursorPath: List<Int>,
    selectionRange: IntRange?,
    onPlaceCursor: (caret: List<Int>) -> Unit,
    onSelectToken: (stringPath: List<Int>, index: Int) -> Unit,
    onBeginHandleDrag: (isStartHandle: Boolean) -> Unit,
    onExtendSelection: (focusCaret: List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    actions: MathEditorActions? = null,
    fontSize: TextUnit = 32.sp,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val geometry = remember { CaretGeometry() }
    val scrollState = rememberScrollState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val autoScrollEdge = with(density) { 32.dp.toPx() }

    val currentOnPlaceCursor by rememberUpdatedState(onPlaceCursor)
    val currentOnSelectToken by rememberUpdatedState(onSelectToken)
    val currentOnExtendSelection by rememberUpdatedState(onExtendSelection)
    val currentOnBeginHandleDrag by rememberUpdatedState(onBeginHandleDrag)
    val currentCursorPath by rememberUpdatedState(cursorPath)
    val currentSelectionRange by rememberUpdatedState(selectionRange)
    val currentExpression by rememberUpdatedState(expression)

    // The floating text menu belongs to the state of the input it was opened for and goes away
    // as soon as anything changes (typing, moving the cursor, ...) or the input is touched.
    var menu by remember { mutableStateOf<MenuState?>(null) }
    var isMenuRequested by remember { mutableStateOf(false) }
    fun requestMenu() {
        isMenuRequested = true
    }
    fun dismissMenu() {
        isMenuRequested = false
        menu = null
    }
    LaunchedEffect(isMenuRequested) {
        if (!isMenuRequested) return@LaunchedEffect
        // Opened for the state after the last selection change has arrived from the caller
        withFrameNanos { }
        menu = MenuState(currentExpression, currentCursorPath, currentSelectionRange)
        isMenuRequested = false
    }
    val shownMenu = menu?.takeIf {
        it.expression === expression && it.cursorPath == cursorPath && it.selectionRange == selectionRange
    }
    val isMenuShown by rememberUpdatedState(shownMenu != null)
    if (actions != null) {
        TextMenu(shownMenu, actions, geometry, onDismiss = ::dismissMenu, onReopen = ::requestMenu)
    }

    // Where the selection's ends are; the handles sit below them
    val selectionStringPath = cursorPath.dropLast(1)
    val handleHitAreas = remember { mutableMapOf<Boolean, Rect>() }

    /** Scrolls the input when a drag gets close to its left/right edge. */
    fun autoScroll(x: Float) {
        val width = geometry.editorWidth
        val delta = when {
            x < autoScrollEdge -> -(autoScrollEdge - x) / 3f
            x > width - autoScrollEdge -> (x - (width - autoScrollEdge)) / 3f
            else -> 0f
        }
        if (delta != 0f) scrollState.dispatchRawDelta(delta)
    }

    // Keep the cursor in view while typing: after each edit (once laid out), scroll just
    // enough to bring it inside the edges. Not while selecting; drags auto-scroll themselves.
    LaunchedEffect(expression, cursorPath) {
        if (selectionRange != null) return@LaunchedEffect
        withFrameNanos { }
        val x = geometry.caretX(cursorPath) ?: return@LaunchedEffect
        val width = geometry.editorWidth
        val delta = when {
            x > width - autoScrollEdge -> x - (width - autoScrollEdge)
            x < autoScrollEdge -> x - autoScrollEdge
            else -> 0f
        }
        if (delta != 0f) scrollState.scrollBy(delta)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .onGloballyPositioned { geometry.setEditor(it) }
            .pointerInput(geometry) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    // Touches on a selection handle belong to the handle
                    if (handleHitAreas.values.any { it.contains(down.position) }) return@awaitEachGesture
                    val hadMenu = isMenuShown
                    dismissMenu()

                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        // A tap, unless the gesture was a scroll (consumed by horizontalScroll)
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) {
                            val caret = geometry.hitTestCaret(down.position) ?: return@awaitEachGesture
                            // Tapping the caret itself toggles the Paste menu, like in a text field
                            if (caret == currentCursorPath && currentSelectionRange == null) {
                                if (!hadMenu) requestMenu()
                            } else {
                                currentOnPlaceCursor(caret)
                            }
                        }
                        return@awaitEachGesture
                    }

                    val hit = geometry.hitTestNode(longPress.position)
                    if (hit == null) {
                        // Nothing to select (e.g. an empty input): offer to paste at the caret
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        requestMenu()
                        return@awaitEachGesture
                    }
                    val (stringPath, nodeIndex) = hit
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnSelectToken(stringPath, nodeIndex)

                    // Keep dragging after the long press to extend the selection. Events are
                    // handled in the Initial pass so the horizontal scroll does not react.
                    var lastCaret: List<Int>? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        if (!change.positionChanged()) continue
                        autoScroll(change.position.x)
                        val caret = geometry.hitTestCaret(change.position)
                        if (caret != null && caret != lastCaret) {
                            lastCaret = caret
                            currentOnExtendSelection(caret)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    requestMenu()
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .horizontalScroll(scrollState)
                // Room below the text for the selection handles
                .padding(top = 4.dp, bottom = HANDLE_SIZE - 4.dp, end = 24.dp)
        ) {
            MathExpressionView(
                mathString = expression,
                cursorPath = cursorPath,
                selectionRange = selectionRange,
                fontSize = fontSize,
                accentColor = accentColor,
                caretGeometry = geometry
            )
        }

        // Overlay for the handles, aligned top-left so their offsets are editor coordinates
        if (selectionRange != null) Box(modifier = Modifier.matchParentSize()) {
            for (isStart in listOf(true, false)) {
                val nodeIndex = if (isStart) selectionRange.first else selectionRange.last
                SelectionHandle(
                    isStart = isStart,
                    color = accentColor,
                    anchor = { geometry.handleAnchor(selectionStringPath, nodeIndex, isStart) },
                    onHitAreaChanged = { area ->
                        if (area == null) handleHitAreas.remove(isStart) else handleHitAreas[isStart] = area
                    },
                    onDragStart = {
                        dismissMenu()
                        currentOnBeginHandleDrag(isStart)
                        // Hit test at the middle of the text, not at the finger below it
                        geometry.handleGrabPoint(selectionStringPath, nodeIndex, isStart)
                    },
                    onDrag = { point, isNewCaret ->
                        autoScroll(point.x)
                        val caret = geometry.hitTestCaret(point)
                        if (caret != null && isNewCaret(caret)) {
                            currentOnExtendSelection(caret)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDragEnd = ::requestMenu,
                    scrollState = scrollState
                )
            }
        }
        if (selectionRange == null) {
            SideEffect { handleHitAreas.clear() }
        }
    }
}

/** What the floating text menu of a [MathEditor] does; the editor decides when to offer what. */
class MathEditorActions(
    val onCopy: () -> Unit,
    val onCut: () -> Unit,
    val onPaste: () -> Unit,
    val onSelectAll: () -> Unit,
    /** Whether there is something to paste; asked whenever the menu opens. */
    val canPaste: () -> Boolean
)

/** The state of the input a text menu was opened for. */
private class MenuState(val expression: MathString, val cursorPath: List<Int>, val selectionRange: IntRange?)

/**
 * Shows Android's floating text menu (the one of text fields) for [menu], or nothing while it
 * is null. It follows the selection or caret while the input scrolls.
 */
@Composable
private fun TextMenu(
    menu: MenuState?,
    actions: MathEditorActions,
    geometry: CaretGeometry,
    onDismiss: () -> Unit,
    onReopen: () -> Unit
) {
    val textToolbar = LocalTextToolbar.current
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(menu) {
        if (menu == null) return@LaunchedEffect
        val selection = menu.selectionRange?.takeIf { !it.isEmpty() }
        val isAllSelected = menu.cursorPath.size == 1 && selection == 0 until menu.expression.length
        val canPaste = currentActions.canPaste()
        // Every item closes the menu first; Select all opens it again for the new selection
        fun item(available: Boolean, action: MathEditorActions.() -> Unit): (() -> Unit)? =
            if (available) ({ onDismiss(); currentActions.action() }) else null
        val onCopy = item(selection != null) { onCopy() }
        val onCut = item(selection != null) { onCut() }
        val onPaste = item(canPaste) { onPaste() }
        val onSelectAll = item(menu.expression.isNotEmpty && !isAllSelected) { onSelectAll(); onReopen() }
        if (listOf(onCopy, onCut, onPaste, onSelectAll).all { it == null }) return@LaunchedEffect
        try {
            snapshotFlow { geometry.menuAnchor(menu.cursorPath, selection) }.collect { anchor ->
                if (anchor == null) {
                    textToolbar.hide()
                } else {
                    textToolbar.showMenu(
                        rect = anchor,
                        onCopyRequested = onCopy,
                        onPasteRequested = onPaste,
                        onCutRequested = onCut,
                        onSelectAllRequested = onSelectAll
                    )
                }
            }
        } finally {
            textToolbar.hide()
        }
    }
}

private val HANDLE_SIZE = 22.dp
private val HANDLE_TOUCH_SIZE = 44.dp

/**
 * A teardrop selection handle like Android's: its pointed corner touches the bottom of the
 * selection's start (pointing right) or end (pointing left).
 */
@Composable
private fun SelectionHandle(
    isStart: Boolean,
    color: Color,
    anchor: () -> Offset?,
    onHitAreaChanged: (Rect?) -> Unit,
    onDragStart: () -> Offset?,
    onDrag: (point: Offset, isNewCaret: (List<Int>) -> Boolean) -> Unit,
    onDragEnd: () -> Unit,
    scrollState: ScrollState
) {
    val density = LocalDensity.current
    val touchSize = with(density) { HANDLE_TOUCH_SIZE.toPx() }
    val visualSize = with(density) { HANDLE_SIZE.toPx() }
    // Horizontal inset of the pointed corner inside the touch area
    val cornerX = if (isStart) (touchSize + visualSize) / 2f else (touchSize - visualSize) / 2f
    val shape = if (isStart) {
        RoundedCornerShape(topStartPercent = 50, topEndPercent = 0, bottomEndPercent = 50, bottomStartPercent = 50)
    } else {
        RoundedCornerShape(topStartPercent = 0, topEndPercent = 50, bottomEndPercent = 50, bottomStartPercent = 50)
    }

    Box(
        modifier = Modifier
            .offset {
                scrollState.value // re-place while scrolling
                val point = anchor()
                if (point == null) {
                    onHitAreaChanged(null)
                    IntOffset(-100_000, -100_000)
                } else {
                    val topLeft = Offset(point.x - cornerX, point.y)
                    onHitAreaChanged(Rect(topLeft, androidx.compose.ui.geometry.Size(touchSize, touchSize)))
                    IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt())
                }
            }
            .size(HANDLE_TOUCH_SIZE)
            .pointerInput(isStart) {
                var virtualPoint = Offset.Zero
                var lastCaret: List<Int>? = null
                detectDragGestures(
                    onDragStart = {
                        virtualPoint = onDragStart() ?: Offset.Zero
                        lastCaret = null
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        virtualPoint += dragAmount
                        onDrag(virtualPoint) { caret ->
                            (caret != lastCaret).also { lastCaret = caret }
                        }
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                )
            },
        contentAlignment = if (isStart) Alignment.TopEnd else Alignment.TopStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = (HANDLE_TOUCH_SIZE - HANDLE_SIZE) / 2)
                .size(HANDLE_SIZE)
                .background(color, shape)
        )
    }
}

/**
 * On-screen positions of the strings and nodes of the expression shown in a [MathEditor],
 * reported by [MathExpressionView]. All rectangles are stored in root coordinates; queries
 * take and return editor-local coordinates.
 */
@Stable
class CaretGeometry {
    private class StringInfo(val bounds: Rect, val length: Int)

    /** A line of a string with line breaks: caret positions [start]..[end] (its line break). */
    private data class LineInfo(val bounds: Rect, val start: Int, val end: Int)

    private val strings = HashMap<List<Int>, StringInfo>()
    private val nodes = HashMap<List<Int>, HashMap<Int, Rect>>()
    /** Lines by index, only for strings with line breaks; [lineCounts] tells which are current. */
    private val lines = HashMap<List<Int>, HashMap<Int, LineInfo>>()
    private val lineCounts = HashMap<List<Int>, Int>()
    private var editorOrigin = Offset.Zero
    /** The visible part of the editor (it scrolls its content), in root coordinates. */
    private var editorBounds = Rect.Zero

    /** Changes whenever a position changes, so handles are re-placed. */
    private var version by mutableIntStateOf(0)

    var editorWidth = 0f
        private set

    internal fun setEditor(coordinates: LayoutCoordinates) {
        val origin = coordinates.localToRoot(Offset.Zero)
        val bounds = boundsInRoot(coordinates)
        editorWidth = coordinates.size.width.toFloat()
        if (origin != editorOrigin || bounds != editorBounds) {
            editorOrigin = origin
            editorBounds = bounds
            version++
        }
    }

    internal fun setString(path: List<Int>, length: Int, lineCount: Int, coordinates: LayoutCoordinates) {
        if (lineCount == 1 && lineCounts.remove(path) != null) {
            lines.remove(path)
            version++
        }
        val bounds = boundsInRoot(coordinates)
        val old = strings[path]
        if (old == null || old.bounds != bounds || old.length != length) {
            strings[path] = StringInfo(bounds, length)
            version++
        }
    }

    internal fun setNode(path: List<Int>, index: Int, coordinates: LayoutCoordinates) {
        val bounds = boundsInRoot(coordinates)
        val map = nodes.getOrPut(path) { HashMap() }
        if (map[index] != bounds) {
            map[index] = bounds
            version++
        }
    }

    /** Line [index] of [count] of the string at [path], covering caret positions [start]..[end]. */
    internal fun setLine(path: List<Int>, index: Int, count: Int, start: Int, end: Int, coordinates: LayoutCoordinates) {
        val info = LineInfo(boundsInRoot(coordinates), start, end)
        val map = lines.getOrPut(path) { HashMap() }
        if (map[index] != info || lineCounts[path] != count) {
            map[index] = info
            lineCounts[path] = count
            version++
        }
    }

    internal fun removeString(path: List<Int>) {
        strings.remove(path)
        nodes.remove(path)
        lines.remove(path)
        lineCounts.remove(path)
        version++
    }

    /**
     * The caret positions of the line at the height of [point] (or the nearest line), or all
     * positions of a string without line breaks.
     */
    private fun lineAt(path: List<Int>, length: Int, point: Offset): IntRange {
        val count = lineCounts[path] ?: return 0..length
        val current = (0 until count).mapNotNull { lines[path]?.get(it) }
        if (current.size != count) return 0..length
        val line = current.minBy { line ->
            when {
                point.y < line.bounds.top -> line.bounds.top - point.y
                point.y > line.bounds.bottom -> point.y - line.bounds.bottom
                else -> 0f
            }
        }
        return line.start..line.end
    }

    private fun boundsInRoot(coordinates: LayoutCoordinates): Rect =
        coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)

    private fun nodeRects(path: List<Int>): List<Rect>? {
        val info = strings[path] ?: return null
        val map = nodes[path] ?: return if (info.length == 0) emptyList() else null
        return (0 until info.length).map { map[it] ?: return null }
    }

    /** The string under [point]: the most deeply nested one, falling back to the root string. */
    private fun stringAt(point: Offset): List<Int>? {
        val slop = 6f
        val containing = strings.filter { (_, info) ->
            info.bounds.width > 0f && info.bounds.inflate(slop).contains(point)
        }
        return containing.keys.maxByOrNull { it.size } ?: strings.keys.firstOrNull { it.isEmpty() }
    }

    /** The caret path of the position nearest to [localPoint]. */
    fun hitTestCaret(localPoint: Offset): List<Int>? {
        version // read for snapshot consistency
        val point = localPoint + editorOrigin
        val path = stringAt(point) ?: return null
        val info = strings[path] ?: return null
        val rects = nodeRects(path) ?: return null
        val line = lineAt(path, info.length, point)
        if (line.first == line.last) return path + line.first
        // Candidate caret x positions: before each node of the line and after its last one
        val caretXs = line.associateWith { i ->
            when (i) {
                line.first -> rects[i].left
                line.last -> rects[i - 1].right
                else -> (rects[i - 1].right + rects[i].left) / 2f
            }
        }
        val index = caretXs.keys.minBy { abs(caretXs.getValue(it) - point.x) }
        return path + index.coerceIn(0, info.length)
    }

    /** The x position of the caret at [caretPath], in editor-local coordinates. */
    fun caretX(caretPath: List<Int>): Float? {
        val path = caretPath.dropLast(1)
        val index = caretPath.lastOrNull() ?: return null
        val info = strings[path] ?: return null
        val map = nodes[path]
        val x = when {
            index < info.length -> map?.get(index)?.left
            index > 0 -> map?.get(index - 1)?.right
            else -> info.bounds.left
        } ?: return null
        return x - editorOrigin.x
    }

    /** The node under [localPoint] as (string path, node index), e.g. for a long press. */
    fun hitTestNode(localPoint: Offset): Pair<List<Int>, Int>? {
        val point = localPoint + editorOrigin
        val path = stringAt(point) ?: return null
        val rects = nodeRects(path) ?: return null
        // The nodes of the line under the point, without its (invisible) line break
        val line = lineAt(path, rects.size, point).let { it.first until it.last }
        if (line.isEmpty()) return null
        val index = line.firstOrNull { point.x >= rects[it].left && point.x <= rects[it].right }
            ?: line.minBy { abs(rects[it].center.x - point.x) }
        return path to index
    }

    /**
     * Where a handle's pointed corner goes, in editor-local coordinates: bottom-left of the
     * first selected node (start) or bottom-right of the last one (end).
     */
    fun handleAnchor(stringPath: List<Int>, nodeIndex: Int, isStart: Boolean): Offset? {
        version // re-place the handle whenever positions change
        val rect = nodes[stringPath]?.get(nodeIndex) ?: return null
        val x = if (isStart) rect.left else rect.right
        return Offset(x, rect.bottom) - editorOrigin
    }

    /**
     * What the floating text menu points at, in root coordinates: the selected nodes, or the
     * caret if [selectionRange] is null. Clipped to the visible part of the editor; null if
     * it is scrolled out of view.
     */
    fun menuAnchor(cursorPath: List<Int>, selectionRange: IntRange?): Rect? {
        version // follow layout and scrolling
        val path = cursorPath.dropLast(1)
        val map = nodes[path]
        val area = if (selectionRange != null) {
            val selected = selectionRange.mapNotNull { map?.get(it) }.reduceOrNull { a, b ->
                Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
            } ?: return null
            // Without line breaks, as tall as the whole string, so the menu does not cover
            // taller neighbours (a "2" next to a fraction)
            val string = strings[path]?.bounds
            if (string == null || lineCounts.containsKey(path)) selected
            else Rect(selected.left, minOf(selected.top, string.top), selected.right, maxOf(selected.bottom, string.bottom))
        } else {
            val x = (caretX(cursorPath) ?: return null) + editorOrigin.x
            val index = cursorPath.last()
            // As tall as the node next to the caret (its line, in a string with line breaks)
            val height = map?.get(index) ?: map?.get(index - 1) ?: strings[path]?.bounds ?: return null
            Rect(x, height.top, x, height.bottom)
        }
        val left = maxOf(area.left, editorBounds.left)
        val right = minOf(area.right, editorBounds.right)
        return if (left > right) null else Rect(left, area.top, right, area.bottom)
    }

    /** The point used for hit testing when a handle drag starts: the middle of that edge. */
    fun handleGrabPoint(stringPath: List<Int>, nodeIndex: Int, isStart: Boolean): Offset? {
        val rect = nodes[stringPath]?.get(nodeIndex) ?: return null
        val x = if (isStart) rect.left else rect.right
        return Offset(x, rect.center.y) - editorOrigin
    }
}
