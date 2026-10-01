package com.vortessence.calculator.ui.math

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.type.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Whether numbers are shown with digit groups (300 000). Display only, the text is unchanged. */
val LocalDigitGrouping = staticCompositionLocalOf { false }

/**
 * Natural WYSIWYG mathematical expression layout in Jetpack Compose.
 * Supports fractions, radicals, exponents, parentheses, functions, cursor and selection display.
 *
 * The view itself is not interactive. Inside [MathEditor] it reports the on-screen position
 * of every node to [caretGeometry], which the editor uses for all touch handling.
 *
 * @param path The caret path prefix of [mathString] within the editor's root string.
 * @param verticalPadding Space above and below the row.
 */
@Composable
fun MathExpressionView(
    mathString: MathString,
    modifier: Modifier = Modifier,
    cursorPath: List<Int> = emptyList(),
    selectionRange: IntRange? = null,
    fontSize: TextUnit = 32.sp,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    caretGeometry: CaretGeometry? = null,
    path: List<Int> = emptyList(),
    verticalPadding: Dp = 4.dp
) {
    if (caretGeometry != null) {
        DisposableEffect(caretGeometry, path) {
            onDispose { caretGeometry.removeString(path) }
        }
    }
    // The cursor/selection is in this string (not in a nested one) when only a position is left
    val isCursorHere = cursorPath.size == 1
    val length = mathString.length
    // Node bounds within this row, used to draw the selection as one continuous band
    val nodeBounds = remember { mutableStateMapOf<Int, Rect>() }
    val activeSelection = if (isCursorHere) selectionRange else null
    val selectionColor = accentColor.copy(alpha = 0.25f)
    val groupedDigits = if (LocalDigitGrouping.current) groupDigits(mathString.nodes) else emptyMap()

    Row(
        modifier = modifier
            .padding(vertical = verticalPadding)
            .then(
                if (caretGeometry == null) Modifier
                else Modifier.onGloballyPositioned { caretGeometry.setString(path, length, it) }
            )
            .drawBehind {
                if (activeSelection == null) return@drawBehind
                // One highlight spanning all selected nodes, as tall as the tallest of them
                val rects = activeSelection.mapNotNull { nodeBounds[it] }
                if (rects.isEmpty()) return@drawBehind
                val band = Rect(
                    left = rects.minOf { it.left },
                    top = rects.minOf { it.top },
                    right = rects.maxOf { it.right },
                    bottom = rects.maxOf { it.bottom }
                )
                drawRoundRect(
                    color = selectionColor,
                    topLeft = band.topLeft,
                    size = band.size,
                    cornerRadius = CornerRadius(6.dp.toPx())
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        if (mathString.isEmpty) {
            if (isCursorHere && cursorPath[0] == 0) {
                BlinkingCursor(fontSize, accentColor)
            } else {
                EmptySlotPlaceholder(fontSize = fontSize, color = textColor)
            }
            return@Row
        }

        mathString.nodes.forEachIndexed { index, node ->
            if (isCursorHere && cursorPath[0] == index && selectionRange == null) {
                BlinkingCursor(fontSize, accentColor, Modifier.centeredMathAxis().alignBy(MathAxis))
            }

            val isNodeFocused = cursorPath.size > 1 && cursorPath[0] == index
            val isUnaryMinus = node is OperatorNode && node.op == MathOp.MINUS &&
                (index == 0 || mathString.nodes[index - 1] is OperatorNode)

            Box(
                modifier = Modifier
                    .mathAxisOrCenter()
                    .alignBy(MathAxis)
                    .onPlaced { nodeBounds[index] = Rect(it.positionInParent(), it.size.toSize()) }
                    .then(
                        if (caretGeometry == null) Modifier
                        else Modifier.onGloballyPositioned { caretGeometry.setNode(path, index, it) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val slots = SlotContext(
                    parentPath = path,
                    nodeIndex = index,
                    focusedChildIdx = if (isNodeFocused) cursorPath[1] else -1,
                    childCursorPath = if (isNodeFocused) cursorPath.drop(2) else emptyList(),
                    selectionRange = if (isNodeFocused) selectionRange else null,
                    textColor = textColor,
                    accentColor = accentColor,
                    caretGeometry = caretGeometry
                )
                MathNodeView(node, fontSize, slots, isUnaryMinus, groupedDigits[index])
            }
        }

        if (isCursorHere && cursorPath[0] == length && selectionRange == null) {
            BlinkingCursor(fontSize, accentColor, Modifier.centeredMathAxis().alignBy(MathAxis))
        }
    }
}

/**
 * Digit-grouped display text for the number nodes in [nodes], by node index. A number may be
 * one node ("300000", results) or one node per digit (typed input), so grouping looks at each
 * whole run of number nodes. Integer parts with more than four digits get a narrow space
 * between groups of three (300 000; 4-digit numbers stay 1234, following SI conventions).
 */
internal fun groupDigits(nodes: List<MathNode>): Map<Int, String> {
    val result = HashMap<Int, String>()
    var i = 0
    while (i < nodes.size) {
        if (nodes[i] !is NumberNode) {
            i++
            continue
        }
        val start = i
        while (i < nodes.size && nodes[i] is NumberNode) i++
        val run = start until i
        val full = run.joinToString("") { (nodes[it] as NumberNode).text }
        val integerStart = if (full.startsWith("-")) 1 else 0
        var integerEnd = integerStart
        while (integerEnd < full.length && full[integerEnd].isDigit()) integerEnd++
        if (integerEnd - integerStart <= 4) continue
        // Character positions (in the whole run) that start a new group of three
        val breaks = (integerStart + 1 until integerEnd).filter { (integerEnd - it) % 3 == 0 }.toSet()
        var offset = 0
        for (index in run) {
            val text = (nodes[index] as NumberNode).text
            result[index] = buildString {
                text.forEachIndexed { k, c ->
                    if (offset + k in breaks) append(GROUP_SEPARATOR)
                    append(c)
                }
            }
            offset += text.length
        }
    }
    return result
}

/**
 * The math axis: the height of the middle of "+" and of fraction bars. The nodes of a row are
 * aligned on it, so a fraction bar stays level with the operators even when its numerator and
 * denominator differ in height.
 */
private val MathAxis = HorizontalAlignmentLine(::min)

/** The math axis of this placeable, or its vertical centre when it has none (plain text). */
private fun Placeable.mathAxis(): Int =
    this[MathAxis].takeIf { it != AlignmentLine.Unspecified } ?: (height / 2)

/** Provides the math axis of the content, its vertical centre when the content has none. */
private fun Modifier.mathAxisOrCenter() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height, mapOf(MathAxis to placeable.mathAxis())) {
        placeable.place(0, 0)
    }
}

/** Puts the math axis at the vertical centre, ignoring any axis of the content (e.g. scripts). */
private fun Modifier.centeredMathAxis() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height, mapOf(MathAxis to placeable.height / 2)) {
        placeable.place(0, 0)
    }
}

/** Narrow no-break space, the SI digit group separator. */
private const val GROUP_SEPARATOR = '\u202F'

/** Everything a structured node needs to render its nested strings (numerator, exponent, ...). */
private class SlotContext(
    val parentPath: List<Int>,
    val nodeIndex: Int,
    val focusedChildIdx: Int,
    val childCursorPath: List<Int>,
    val selectionRange: IntRange?,
    val textColor: Color,
    val accentColor: Color,
    val caretGeometry: CaretGeometry?
)

/** Renders the nested string [string], which is child [child] of the node (see MathNode.subStrings). */
@Composable
private fun SlotContext.Slot(
    child: Int,
    string: MathString,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 4.dp
) {
    val isFocused = focusedChildIdx == child
    MathExpressionView(
        mathString = string,
        modifier = modifier,
        cursorPath = if (isFocused) childCursorPath else emptyList(),
        selectionRange = if (isFocused) selectionRange else null,
        fontSize = fontSize,
        textColor = textColor,
        accentColor = accentColor,
        caretGeometry = caretGeometry,
        path = parentPath + listOf(nodeIndex, child),
        verticalPadding = verticalPadding
    )
}

/**
 * The blinking animation lives here, so it only runs while a cursor is actually displayed
 * (not in every read-only history entry).
 */
@Composable
private fun BlinkingCursor(fontSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = modifier
            .padding(horizontal = 1.5.dp)
            .width(2.5.dp)
            .height(Dp(fontSize.value * 0.9f))
            .background(color.copy(alpha = alpha))
    )
}

/** Placeholder box for an empty slot (e.g. an empty denominator or exponent). */
@Composable
private fun EmptySlotPlaceholder(fontSize: TextUnit, color: Color) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(width = Dp(fontSize.value * 0.55f), height = Dp(fontSize.value * 0.8f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
    )
}

@Composable
private fun MathNodeView(
    node: MathNode,
    fontSize: TextUnit,
    slots: SlotContext,
    isUnaryMinus: Boolean,
    displayText: String? = null
) {
    val textColor = slots.textColor
    val accentColor = slots.accentColor
    when (node) {
        is NumberNode -> {
            Text(
                text = displayText ?: node.text,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                color = textColor,
                modifier = Modifier.padding(horizontal = 0.5.dp)
            )
        }
        is OperatorNode -> {
            Text(
                text = if (node.op == MathOp.FACTORIAL || isUnaryMinus) node.op.symbol else " ${node.op.symbol} ",
                fontSize = fontSize,
                fontWeight = if (node.op == MathOp.FACTORIAL) FontWeight.Normal else FontWeight.Light,
                color = if (node.op == MathOp.FACTORIAL) textColor else accentColor
            )
        }
        is VariableNode -> {
            Text(
                text = node.name,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                color = textColor,
                modifier = Modifier.padding(horizontal = 1.dp)
            )
        }
        is ConstantNode -> {
            Text(
                text = node.name,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                color = accentColor
            )
        }
        is UnitNode -> {
            // Angle marks attach directly to the number (30°, 5′); other units are spaced (5 km)
            val isAngleMark = node.symbol == "°" || node.symbol == "′" || node.symbol == "″"
            Text(
                text = if (isAngleMark) node.symbol else " ${node.symbol}",
                fontSize = (fontSize.value * 0.85f).sp,
                fontWeight = FontWeight.Normal,
                color = textColor.copy(alpha = 0.75f)
            )
        }
        is TextNode -> {
            Text(
                text = node.text,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                color = textColor
            )
        }
        is FractionNode -> FractionView(node, fontSize, slots)
        is ExponentNode -> ExponentView(node, fontSize, slots)
        is SciExponentNode -> {
            // Part of the number before it: no spacing, same color as digits
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.centeredMathAxis()) {
                Text(text = "×10", fontSize = fontSize, fontWeight = FontWeight.Normal, color = textColor)
                slots.Slot(
                    child = 0,
                    string = node.exponent,
                    fontSize = (fontSize.value * 0.62f).sp,
                    modifier = Modifier.padding(bottom = (fontSize.value * 0.56f).dp)
                )
            }
        }
        is RadicalNode -> RadicalView(node, fontSize, slots)
        is ParenthesesNode -> ParenthesesView(node, fontSize, slots)
        is LogBaseNode -> LogBaseView(node, fontSize, slots)
        is FunctionNode -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val onAxis = Modifier.mathAxisOrCenter().alignBy(MathAxis)
                Text(
                    text = node.name,
                    fontSize = (fontSize.value * 0.9f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    modifier = onAxis
                )
                Text(text = "(", fontSize = fontSize, color = textColor, modifier = onAxis)
                slots.Slot(child = 0, string = node.argument, fontSize = (fontSize.value * 0.9f).sp, modifier = onAxis)
                Text(text = ")", fontSize = fontSize, color = textColor, modifier = onAxis)
            }
        }
        is IntegralNode -> IntegralView(node, fontSize, slots)
        is MatrixNode -> MatrixView(node, fontSize, slots)
    }
}

/** Numerator over denominator; the math axis runs through the middle of the bar. */
@Composable
private fun FractionView(node: FractionNode, fontSize: TextUnit, slots: SlotContext) {
    val childFontSize = (fontSize.value * 0.8f).sp
    Layout(
        content = {
            slots.Slot(
                child = 0,
                string = node.numerator,
                fontSize = childFontSize,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            HorizontalDivider(thickness = 1.8.dp, color = slots.textColor)
            slots.Slot(
                child = 1,
                string = node.denominator,
                fontSize = childFontSize,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        },
        modifier = Modifier.padding(horizontal = 2.dp)
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val numerator = measurables[0].measure(loose)
        val denominator = measurables[2].measure(loose)
        val width = max(numerator.width, denominator.width)
        val bar = measurables[1].measure(Constraints.fixedWidth(width))
        val height = numerator.height + bar.height + denominator.height
        layout(width, height, mapOf(MathAxis to numerator.height + bar.height / 2)) {
            numerator.place((width - numerator.width) / 2, 0)
            bar.place(0, numerator.height)
            denominator.place((width - denominator.width) / 2, numerator.height + bar.height)
        }
    }
}

/**
 * The exponent is raised relative to the base, and the node keeps the base's math axis. Only the
 * space the exponent actually needs is added above the base; nothing is added below it.
 */
@Composable
private fun ExponentView(node: ExponentNode, fontSize: TextUnit, slots: SlotContext) {
    val expFontSize = (fontSize.value * 0.62f).sp
    val base = node.base

    Layout(
        content = {
            // Child indices must match ExponentNode.subStrings: [base, exponent] whenever a base
            // slot exists (even if still empty), otherwise [exponent]. No row padding: the
            // surrounding row already has it, and it would push a fraction bar away from y².
            if (base != null) {
                slots.Slot(child = 0, string = base, fontSize = fontSize, verticalPadding = 0.dp)
            }
            slots.Slot(
                child = if (base != null) 1 else 0,
                string = node.exponent,
                fontSize = expFontSize,
                verticalPadding = 0.dp
            )
        }
    ) { measurables, constraints ->
        val em = fontSize.toPx()
        val raise = 0.28f * em        // exponent centre above the base's axis
        val topOverhang = 0.06f * em  // how far the exponent reaches above a tall base
        // Glyph side bearings already separate base and exponent, so the gap is slightly negative;
        // the trailing gap keeps the next node (the y in 2²y) from crowding the exponent
        val gap = (-0.02f * em).roundToInt()
        val trailingGap = (0.07f * em).roundToInt()

        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val basePlaceable = if (base != null) measurables[0].measure(loose) else null
        val expPlaceable = measurables.last().measure(loose)

        // Vertical positions relative to the top of the base (or of the exponent without a base)
        val axis: Float
        val expTop: Float
        if (basePlaceable != null) {
            axis = basePlaceable.mathAxis().toFloat()
            // Next to one line of text the exponent is centred [raise] above the axis; next to a
            // taller base (a fraction in parentheses) it goes up to the base's top
            expTop = min(axis - raise - expPlaceable.height / 2f, -topOverhang)
        } else {
            expTop = 0f
            axis = expPlaceable.height / 2f + raise
        }
        val top = min(0f, expTop)
        val bottom = max(max(basePlaceable?.height?.toFloat() ?: 0f, expTop + expPlaceable.height), axis + raise)
        val height = (bottom - top).roundToInt()
        val baseWidth = basePlaceable?.let { it.width + gap } ?: 0
        val width = baseWidth + expPlaceable.width + trailingGap

        layout(width, height, mapOf(MathAxis to (axis - top).roundToInt())) {
            basePlaceable?.place(0, (-top).roundToInt())
            expPlaceable.place(baseWidth, (expTop - top).roundToInt())
        }
    }
}

/** Where the radical sign is drawn, in px; computed while measuring [RadicalView]. */
private data class RadicalGeometry(
    val stroke: Float,
    val barStart: Offset,
    val barEnd: Offset,
    val bottom: Offset,
    val top: Offset,
    val overbarEnd: Float
)

/**
 * A radical sign modelled on Roboto's √: a flat bar, a short stroke down and a long stroke up
 * into the overbar. All sizes are relative to the font size (em), so the sign matches the text
 * at any size; only the long stroke grows with the radicand. The degree sits above the bar and
 * pushes the sign to the right when it is wider than the space there.
 */
@Composable
private fun RadicalView(node: RadicalNode, fontSize: TextUnit, slots: SlotContext) {
    val radicandFontSize = (fontSize.value * 0.9f).sp
    val degreeFontSize = (fontSize.value * 0.48f).sp
    val degree = node.degree
    val textColor = slots.textColor
    var geometry by remember { mutableStateOf<RadicalGeometry?>(null) }

    Layout(
        content = {
            // Child indices must match RadicalNode.subStrings: the degree slot is shown whenever
            // it exists, so an empty degree is visible and can be tapped
            if (degree != null) {
                slots.Slot(child = 0, string = degree, fontSize = degreeFontSize)
            }
            slots.Slot(child = if (degree != null) 1 else 0, string = node.radicand, fontSize = radicandFontSize)
        },
        modifier = Modifier.drawBehind {
            val g = geometry ?: return@drawBehind
            val path = Path().apply {
                moveTo(g.barStart.x, g.barStart.y)
                lineTo(g.barEnd.x, g.barEnd.y)
                lineTo(g.bottom.x, g.bottom.y)
                lineTo(g.top.x, g.top.y)
                lineTo(g.overbarEnd, g.top.y)
            }
            drawPath(
                path = path,
                color = textColor,
                style = Stroke(width = g.stroke, cap = StrokeCap.Butt, join = StrokeJoin.Round)
            )
        }
    ) { measurables, constraints ->
        val em = fontSize.toPx()
        val stroke = 0.075f * em
        val barLength = 0.13f * em
        val downWidth = 0.12f * em
        val upWidth = 0.26f * em
        val barHeight = 0.4f * em        // bar centre above the bottom of the sign
        val overbarGap = 0.1f * em       // between overbar and radicand
        val radicandStart = 0.06f * em
        val radicandEnd = 0.12f * em
        val degreeOverhang = 0.18f * em  // how far the degree may reach over the bar
        val degreeGap = 0.08f * em       // between the degree's baseline and the bar

        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val degreePlaceable = if (degree != null) measurables[0].measure(loose) else null
        val radicandPlaceable = measurables.last().measure(loose)

        // Horizontal: the sign starts after whatever part of the degree does not fit over the bar
        val signStart = ((degreePlaceable?.width ?: 0) - degreeOverhang).coerceAtLeast(0f)
        val topX = signStart + barLength + downWidth + upWidth
        val radicandX = topX + radicandStart
        val width = (radicandX + radicandPlaceable.width + radicandEnd).roundToInt()

        // Vertical: the overbar is at the top, unless the degree reaches higher than that. The
        // degree sits on the bar by its baseline, its box also holds padding and descender space.
        val signHeight = stroke + overbarGap + radicandPlaceable.height
        val degreeBaseline = degreePlaceable?.let { placeable ->
            placeable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: placeable.height
        } ?: 0
        val degreeTop = signHeight - barHeight - stroke / 2f - degreeGap - degreeBaseline
        val offsetY = (-degreeTop).coerceAtLeast(0f)
        val height = (offsetY + signHeight).roundToInt()

        val signBottom = offsetY + signHeight - stroke / 2f
        val barY = offsetY + signHeight - barHeight
        geometry = RadicalGeometry(
            stroke = stroke,
            barStart = Offset(signStart, barY),
            barEnd = Offset(signStart + barLength, barY),
            bottom = Offset(signStart + barLength + downWidth, signBottom),
            top = Offset(topX, offsetY + stroke / 2f),
            overbarEnd = width.toFloat()
        )

        val radicandY = (offsetY + stroke + overbarGap).roundToInt()
        layout(width, height, mapOf(MathAxis to radicandY + radicandPlaceable.mathAxis())) {
            degreePlaceable?.place(
                x = (signStart + degreeOverhang - degreePlaceable.width).roundToInt().coerceAtLeast(0),
                y = (offsetY + degreeTop).roundToInt()
            )
            radicandPlaceable.place(x = radicandX.roundToInt(), y = radicandY)
        }
    }
}

@Composable
private fun LogBaseView(node: LogBaseNode, fontSize: TextUnit, slots: SlotContext) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.centeredMathAxis()) {
        Text(
            text = "log",
            fontSize = (fontSize.value * 0.9f).sp,
            fontWeight = FontWeight.SemiBold,
            color = slots.accentColor
        )
        slots.Slot(
            child = 0,
            string = node.base,
            fontSize = (fontSize.value * 0.6f).sp,
            modifier = Modifier.padding(top = (fontSize.value * 0.45f).dp)
        )
        Text(text = "(", fontSize = fontSize, color = slots.textColor)
        slots.Slot(child = 1, string = node.argument, fontSize = (fontSize.value * 0.9f).sp)
        Text(text = ")", fontSize = fontSize, color = slots.textColor)
    }
}

@Composable
private fun ParenthesesView(node: ParenthesesNode, fontSize: TextUnit, slots: SlotContext) {
    val textColor = slots.textColor
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Parenthesis dynamically stretched to match height of contents
        Canvas(
            modifier = Modifier
                .width(9.dp)
                .fillMaxHeight()
                .padding(vertical = 1.dp)
        ) {
            val h = size.height
            val w = size.width
            val strokeW = 1.8.dp.toPx()
            val path = Path().apply {
                moveTo(w, strokeW / 2f)
                cubicTo(w * 0.05f, h * 0.28f, w * 0.05f, h * 0.72f, w, h - strokeW / 2f)
            }
            drawPath(path = path, color = textColor, style = Stroke(width = strokeW, cap = StrokeCap.Round))
        }

        slots.Slot(child = 0, string = node.inner, fontSize = fontSize, modifier = Modifier.padding(horizontal = 2.dp))

        // Right Parenthesis dynamically stretched to match height of contents
        Canvas(
            modifier = Modifier
                .width(9.dp)
                .fillMaxHeight()
                .padding(vertical = 1.dp)
        ) {
            val h = size.height
            val w = size.width
            val strokeW = 1.8.dp.toPx()
            val path = Path().apply {
                moveTo(0f, strokeW / 2f)
                cubicTo(w * 0.95f, h * 0.28f, w * 0.95f, h * 0.72f, 0f, h - strokeW / 2f)
            }
            drawPath(path = path, color = textColor, style = Stroke(width = strokeW, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun IntegralView(node: IntegralNode, fontSize: TextUnit, slots: SlotContext) {
    val boundFontSize = (fontSize.value * 0.55f).sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp).centeredMathAxis()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            slots.Slot(child = 0, string = node.upperBound, fontSize = boundFontSize)
            Text(
                text = "∫",
                fontSize = (fontSize.value * 1.5f).sp,
                color = slots.accentColor,
                fontWeight = FontWeight.ExtraLight,
                modifier = Modifier.offset(y = (-2).dp)
            )
            slots.Slot(child = 1, string = node.lowerBound, fontSize = boundFontSize)
        }
        Spacer(modifier = Modifier.width(2.dp))
        slots.Slot(child = 2, string = node.integrand, fontSize = fontSize)
        Text(
            text = " d${node.variable}",
            fontSize = fontSize,
            fontWeight = FontWeight.Normal,
            color = slots.textColor
        )
    }
}

@Composable
private fun MatrixView(node: MatrixNode, fontSize: TextUnit, slots: SlotContext) {
    val elementFontSize = (fontSize.value * 0.8f).sp
    val textColor = slots.textColor
    Row(
        modifier = Modifier.height(IntrinsicSize.Min).centeredMathAxis(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Bracket Canvas
        Canvas(
            modifier = Modifier
                .width(8.dp)
                .fillMaxHeight()
                .padding(vertical = 1.dp)
        ) {
            val w = size.width
            val h = size.height
            val strokeW = 1.8.dp.toPx()
            drawLine(textColor, Offset(w, 0f), Offset(0f, 0f), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(textColor, Offset(0f, 0f), Offset(0f, h), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(textColor, Offset(0f, h), Offset(w, h), strokeWidth = strokeW, cap = StrokeCap.Round)
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            for (r in 0 until node.rows) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (c in 0 until node.cols) {
                        val idx = r * node.cols + c
                        if (idx < node.elements.size) {
                            slots.Slot(child = idx, string = node.elements[idx], fontSize = elementFontSize)
                        }
                    }
                }
            }
        }

        // Right Bracket Canvas
        Canvas(
            modifier = Modifier
                .width(8.dp)
                .fillMaxHeight()
                .padding(vertical = 1.dp)
        ) {
            val w = size.width
            val h = size.height
            val strokeW = 1.8.dp.toPx()
            drawLine(textColor, Offset(0f, 0f), Offset(w, 0f), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(textColor, Offset(w, 0f), Offset(w, h), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(textColor, Offset(w, h), Offset(0f, h), strokeWidth = strokeW, cap = StrokeCap.Round)
        }
    }
}
