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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.type.*

/**
 * Natural WYSIWYG mathematical expression layout in Jetpack Compose.
 * Supports fractions, radicals, exponents, parentheses, functions, cursor and selection display.
 *
 * The view itself is not interactive. Inside [MathEditor] it reports the on-screen position
 * of every node to [caretGeometry], which the editor uses for all touch handling.
 *
 * @param path The caret path prefix of [mathString] within the editor's root string.
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
    path: List<Int> = emptyList()
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

    Row(
        modifier = modifier
            .padding(vertical = 4.dp)
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
                BlinkingCursor(fontSize, accentColor)
            }

            val isNodeFocused = cursorPath.size > 1 && cursorPath[0] == index
            val isUnaryMinus = node is OperatorNode && node.op == MathOp.MINUS &&
                (index == 0 || mathString.nodes[index - 1] is OperatorNode)

            Box(
                modifier = Modifier
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
                MathNodeView(node, fontSize, slots, isUnaryMinus)
            }
        }

        if (isCursorHere && cursorPath[0] == length && selectionRange == null) {
            BlinkingCursor(fontSize, accentColor)
        }
    }
}

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
private fun SlotContext.Slot(child: Int, string: MathString, fontSize: TextUnit, modifier: Modifier = Modifier) {
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
        path = parentPath + listOf(nodeIndex, child)
    )
}

/**
 * The blinking animation lives here, so it only runs while a cursor is actually displayed
 * (not in every read-only history entry).
 */
@Composable
private fun BlinkingCursor(fontSize: TextUnit, color: Color) {
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
        modifier = Modifier
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
private fun MathNodeView(node: MathNode, fontSize: TextUnit, slots: SlotContext, isUnaryMinus: Boolean) {
    val textColor = slots.textColor
    val accentColor = slots.accentColor
    when (node) {
        is NumberNode -> {
            Text(
                text = node.text,
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
                fontSize = (fontSize.value * 1.05f).sp,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    text = node.name,
                    fontSize = (fontSize.value * 0.9f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor
                )
                Text(text = "(", fontSize = fontSize, color = textColor)
                slots.Slot(child = 0, string = node.argument, fontSize = (fontSize.value * 0.9f).sp)
                Text(text = ")", fontSize = fontSize, color = textColor)
            }
        }
        is IntegralNode -> IntegralView(node, fontSize, slots)
        is MatrixNode -> MatrixView(node, fontSize, slots)
    }
}

@Composable
private fun FractionView(node: FractionNode, fontSize: TextUnit, slots: SlotContext) {
    val childFontSize = (fontSize.value * 0.8f).sp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .padding(horizontal = 2.dp)
    ) {
        slots.Slot(
            child = 0,
            string = node.numerator,
            fontSize = childFontSize,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.8.dp,
            color = slots.textColor
        )
        slots.Slot(
            child = 1,
            string = node.denominator,
            fontSize = childFontSize,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ExponentView(node: ExponentNode, fontSize: TextUnit, slots: SlotContext) {
    val expFontSize = (fontSize.value * 0.62f).sp
    val base = node.base
    val supOffset = (fontSize.value * 0.28f).dp

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Child indices must match ExponentNode.subStrings: [base, exponent] whenever a base
        // slot exists (even if still empty), otherwise [exponent]
        if (base != null) {
            slots.Slot(child = 0, string = base, fontSize = fontSize, modifier = Modifier.padding(vertical = supOffset))
            Spacer(modifier = Modifier.width(2.5.dp))
        }
        slots.Slot(
            child = if (base != null) 1 else 0,
            string = node.exponent,
            fontSize = expFontSize,
            modifier = Modifier.padding(bottom = supOffset * 2)
        )
    }
}

@Composable
private fun RadicalView(node: RadicalNode, fontSize: TextUnit, slots: SlotContext) {
    val radicandFontSize = (fontSize.value * 0.9f).sp
    val degreeFontSize = (fontSize.value * 0.55f).sp
    val degree = node.degree
    val textColor = slots.textColor

    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .padding(end = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Degree and Radical checkmark Canvas
        Box(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopStart
        ) {
            // Child indices must match RadicalNode.subStrings: the degree slot is shown whenever
            // it exists, so an empty degree is visible and can be tapped
            if (degree != null) {
                slots.Slot(child = 0, string = degree, fontSize = degreeFontSize, modifier = Modifier.offset(y = 1.dp))
            }

            // Continuous vector checkmark matching exact height and meeting overbar at (w, strokeW/2)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val h = size.height
                val w = size.width
                val strokeW = 1.8.dp.toPx()
                val path = Path().apply {
                    moveTo(w * 0.05f, h * 0.62f)
                    lineTo(w * 0.28f, h * 0.54f)
                    lineTo(w * 0.52f, h - strokeW / 2f)
                    lineTo(w, strokeW / 2f)
                }
                drawPath(
                    path = path,
                    color = textColor,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        // Radicand under the overbar
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .fillMaxHeight()
        ) {
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.8.dp,
                color = textColor
            )
            Box(
                modifier = Modifier
                    .padding(top = 3.dp, start = 3.dp, end = 4.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                slots.Slot(child = if (degree != null) 1 else 0, string = node.radicand, fontSize = radicandFontSize)
            }
        }
    }
}

@Composable
private fun LogBaseView(node: LogBaseNode, fontSize: TextUnit, slots: SlotContext) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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
        modifier = Modifier.padding(horizontal = 2.dp)
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
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
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
        modifier = Modifier.height(IntrinsicSize.Min),
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
