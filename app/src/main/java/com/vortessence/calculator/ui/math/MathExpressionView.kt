package com.vortessence.calculator.ui.math

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * Supports fractions, radicals, exponents, parentheses, functions, selection highlighting,
 * and deep path cursor navigation.
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
    onCursorPathSelected: ((List<Int>) -> Unit)? = null,
    onSelectToken: ((path: List<Int>, index: Int) -> Unit)? = null,
    onExtendSelection: ((path: List<Int>, index: Int) -> Unit)? = null,
    onSelectRange: ((List<Int>, IntRange) -> Unit)? = null,
    onNodeClicked: ((Int) -> Unit)? = null
) {
    val effectiveCursorCallback: ((List<Int>) -> Unit)? = onCursorPathSelected ?: onNodeClicked?.let { legacy ->
        { path -> legacy(path.firstOrNull() ?: 0) }
    }
    // Read-only views (results, history) must not consume taps meant for their parent
    val isInteractive = effectiveCursorCallback != null || onSelectToken != null ||
        onExtendSelection != null || onSelectRange != null

    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        if (mathString.isEmpty) {
            if (cursorPath.size == 1 && cursorPath[0] == 0) {
                BlinkingCursor(fontSize, accentColor)
            } else {
                EmptySlotPlaceholder(
                    fontSize = fontSize,
                    color = textColor,
                    onClick = effectiveCursorCallback?.let { cb -> { cb(listOf(0)) } }
                )
            }
            return@Row
        }

        mathString.nodes.forEachIndexed { index, node ->
            if (cursorPath.size == 1 && cursorPath[0] == index && selectionRange == null) {
                BlinkingCursor(fontSize, accentColor)
            }

            val isNodeFocused = cursorPath.size > 1 && cursorPath[0] == index
            val childIdx = if (isNodeFocused) cursorPath[1] else -1
            val childPath = if (isNodeFocused) cursorPath.drop(2) else emptyList()
            val isSelected = selectionRange != null && index in selectionRange
            val isUnaryMinus = node is OperatorNode && node.op == MathOp.MINUS &&
                (index == 0 || mathString.nodes[index - 1] is OperatorNode)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (isSelected) Modifier.background(accentColor.copy(alpha = 0.25f)) else Modifier
                    )
                    .then(
                        if (!isInteractive) Modifier else Modifier.combinedClickable(
                            onClick = {
                                if (selectionRange != null) {
                                    if (onExtendSelection != null) {
                                        onExtendSelection(emptyList(), index)
                                    } else if (onSelectRange != null) {
                                        val newRange = minOf(selectionRange.first, index)..maxOf(selectionRange.last, index)
                                        onSelectRange(emptyList(), newRange)
                                    }
                                } else {
                                    effectiveCursorCallback?.invoke(listOf(index + 1))
                                }
                            },
                            onLongClick = {
                                if (onSelectToken != null) {
                                    onSelectToken(emptyList(), index)
                                } else if (onSelectRange != null) {
                                    onSelectRange(emptyList(), index..index)
                                }
                            }
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                MathNodeView(
                    node = node,
                    nodeIndex = index,
                    fontSize = fontSize,
                    textColor = textColor,
                    accentColor = accentColor,
                    focusedChildIdx = childIdx,
                    childCursorPath = childPath,
                    selectionRange = if (isNodeFocused) selectionRange else null,
                    onCursorPathSelected = effectiveCursorCallback,
                    onSelectToken = onSelectToken,
                    onExtendSelection = onExtendSelection,
                    onSelectRange = onSelectRange,
                    isUnaryMinus = isUnaryMinus
                )
            }
        }

        if (cursorPath.size == 1 && cursorPath[0] == mathString.length && selectionRange == null) {
            BlinkingCursor(fontSize, accentColor)
        }
    }
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
private fun EmptySlotPlaceholder(fontSize: TextUnit, color: Color, onClick: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(width = Dp(fontSize.value * 0.55f), height = Dp(fontSize.value * 0.8f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    )
}

@Composable
fun MathNodeView(
    node: MathNode,
    nodeIndex: Int,
    fontSize: TextUnit,
    textColor: Color,
    accentColor: Color,
    focusedChildIdx: Int = -1,
    childCursorPath: List<Int> = emptyList(),
    selectionRange: IntRange? = null,
    onCursorPathSelected: ((List<Int>) -> Unit)? = null,
    onSelectToken: ((path: List<Int>, index: Int) -> Unit)? = null,
    onExtendSelection: ((path: List<Int>, index: Int) -> Unit)? = null,
    onSelectRange: ((List<Int>, IntRange) -> Unit)? = null,
    isUnaryMinus: Boolean = false
) {
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
            Text(
                text = " ${node.symbol}",
                fontSize = (fontSize.value * 0.85f).sp,
                fontWeight = FontWeight.Normal,
                color = textColor.copy(alpha = 0.75f)
            )
        }
        is FractionNode -> {
            FractionView(
                node = node,
                nodeIndex = nodeIndex,
                fontSize = fontSize,
                textColor = textColor,
                accentColor = accentColor,
                focusedChildIdx = focusedChildIdx,
                childCursorPath = childCursorPath,
                selectionRange = selectionRange,
                onCursorPathSelected = onCursorPathSelected,
                onSelectToken = onSelectToken,
                onExtendSelection = onExtendSelection,
                onSelectRange = onSelectRange
            )
        }
        is ExponentNode -> {
            ExponentView(
                node = node,
                nodeIndex = nodeIndex,
                fontSize = fontSize,
                textColor = textColor,
                accentColor = accentColor,
                focusedChildIdx = focusedChildIdx,
                childCursorPath = childCursorPath,
                selectionRange = selectionRange,
                onCursorPathSelected = onCursorPathSelected,
                onSelectToken = onSelectToken,
                onExtendSelection = onExtendSelection,
                onSelectRange = onSelectRange
            )
        }
        is RadicalNode -> {
            RadicalView(
                node = node,
                nodeIndex = nodeIndex,
                fontSize = fontSize,
                textColor = textColor,
                accentColor = accentColor,
                focusedChildIdx = focusedChildIdx,
                childCursorPath = childCursorPath,
                selectionRange = selectionRange,
                onCursorPathSelected = onCursorPathSelected,
                onSelectToken = onSelectToken,
                onExtendSelection = onExtendSelection,
                onSelectRange = onSelectRange
            )
        }
        is ParenthesesNode -> {
            ParenthesesView(
                node = node,
                nodeIndex = nodeIndex,
                fontSize = fontSize,
                textColor = textColor,
                accentColor = accentColor,
                focusedChildIdx = focusedChildIdx,
                childCursorPath = childCursorPath,
                selectionRange = selectionRange,
                onCursorPathSelected = onCursorPathSelected,
                onSelectToken = onSelectToken,
                onExtendSelection = onExtendSelection,
                onSelectRange = onSelectRange
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
        is LogBaseNode -> {
            LogBaseView(
                node = node,
                nodeIndex = nodeIndex,
                fontSize = fontSize,
                textColor = textColor,
                accentColor = accentColor,
                focusedChildIdx = focusedChildIdx,
                childCursorPath = childCursorPath,
                selectionRange = selectionRange,
                onCursorPathSelected = onCursorPathSelected,
                onSelectToken = onSelectToken,
                onExtendSelection = onExtendSelection,
                onSelectRange = onSelectRange
            )
        }
        is FunctionNode -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.name,
                    fontSize = (fontSize.value * 0.9f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor
                )
                Text(text = "(", fontSize = fontSize, color = textColor)
                MathExpressionView(
                    mathString = node.argument,
                    cursorPath = if (focusedChildIdx == 0) childCursorPath else emptyList(),
                    fontSize = (fontSize.value * 0.9f).sp,
                    textColor = textColor,
                    accentColor = accentColor,
                    selectionRange = if (focusedChildIdx == 0) selectionRange else null,
                    onCursorPathSelected = onCursorPathSelected?.let { cb ->
                        { subPath -> cb(listOf(nodeIndex, 0) + subPath) }
                    },
                    onSelectToken = onSelectToken?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                    },
                    onExtendSelection = onExtendSelection?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                    },
                    onSelectRange = onSelectRange?.let { cb ->
                        { subPath, range -> cb(listOf(nodeIndex, 0) + subPath, range) }
                    }
                )
                Text(text = ")", fontSize = fontSize, color = textColor)
            }
        }
        is IntegralNode -> {
            IntegralView(
                node = node,
                nodeIndex = nodeIndex,
                fontSize = fontSize,
                textColor = textColor,
                accentColor = accentColor,
                focusedChildIdx = focusedChildIdx,
                childCursorPath = childCursorPath,
                selectionRange = selectionRange,
                onCursorPathSelected = onCursorPathSelected,
                onSelectToken = onSelectToken,
                onExtendSelection = onExtendSelection,
                onSelectRange = onSelectRange
            )
        }
        is MatrixNode -> {
            MatrixView(
                node = node,
                nodeIndex = nodeIndex,
                fontSize = fontSize,
                textColor = textColor,
                accentColor = accentColor,
                focusedChildIdx = focusedChildIdx,
                childCursorPath = childCursorPath,
                selectionRange = selectionRange,
                onCursorPathSelected = onCursorPathSelected,
                onSelectToken = onSelectToken,
                onExtendSelection = onExtendSelection,
                onSelectRange = onSelectRange
            )
        }
    }
}

@Composable
private fun FractionView(
    node: FractionNode,
    nodeIndex: Int,
    fontSize: TextUnit,
    textColor: Color,
    accentColor: Color,
    focusedChildIdx: Int = -1,
    childCursorPath: List<Int> = emptyList(),
    selectionRange: IntRange? = null,
    onCursorPathSelected: ((List<Int>) -> Unit)? = null,
    onSelectToken: ((path: List<Int>, index: Int) -> Unit)? = null,
    onExtendSelection: ((path: List<Int>, index: Int) -> Unit)? = null,
    onSelectRange: ((List<Int>, IntRange) -> Unit)? = null
) {
    val childFontSize = (fontSize.value * 0.8f).sp
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .padding(horizontal = 2.dp)
        ) {
            // Numerator
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .then(
                        if (onCursorPathSelected != null) {
                            Modifier.clickable {
                                onCursorPathSelected(listOf(nodeIndex, 0, node.numerator.length))
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                MathExpressionView(
                    mathString = node.numerator,
                    cursorPath = if (focusedChildIdx == 0) childCursorPath else emptyList(),
                    fontSize = childFontSize,
                    textColor = textColor,
                    accentColor = accentColor,
                    selectionRange = if (focusedChildIdx == 0) selectionRange else null,
                    onCursorPathSelected = onCursorPathSelected?.let { cb ->
                        { subPath -> cb(listOf(nodeIndex, 0) + subPath) }
                    },
                    onSelectToken = onSelectToken?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                    },
                    onExtendSelection = onExtendSelection?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                    },
                    onSelectRange = onSelectRange?.let { cb ->
                        { subPath, range -> cb(listOf(nodeIndex, 0) + subPath, range) }
                    }
                )
            }

            // Fraction bar
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.8.dp,
                color = textColor
            )

            // Denominator
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .then(
                        if (onCursorPathSelected != null) {
                            Modifier.clickable {
                                onCursorPathSelected(listOf(nodeIndex, 1, node.denominator.length))
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                MathExpressionView(
                    mathString = node.denominator,
                    cursorPath = if (focusedChildIdx == 1) childCursorPath else emptyList(),
                    fontSize = childFontSize,
                    textColor = textColor,
                    accentColor = accentColor,
                    selectionRange = if (focusedChildIdx == 1) selectionRange else null,
                    onCursorPathSelected = onCursorPathSelected?.let { cb ->
                        { subPath -> cb(listOf(nodeIndex, 1) + subPath) }
                    },
                    onSelectToken = onSelectToken?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, 1) + subPath, idx) }
                    },
                    onExtendSelection = onExtendSelection?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, 1) + subPath, idx) }
                    },
                    onSelectRange = onSelectRange?.let { cb ->
                        { subPath, range -> cb(listOf(nodeIndex, 1) + subPath, range) }
                    }
                )
            }
        }

        // Dedicated clickable target to the right of the entire fraction to exit after it
        if (onCursorPathSelected != null) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(Dp(fontSize.value * 1.6f))
                    .clickable {
                        onCursorPathSelected(listOf(nodeIndex + 1))
                    }
            )
        }
    }
}

@Composable
private fun ExponentView(
    node: ExponentNode,
    nodeIndex: Int,
    fontSize: TextUnit,
    textColor: Color,
    accentColor: Color,
    focusedChildIdx: Int = -1,
    childCursorPath: List<Int> = emptyList(),
    selectionRange: IntRange? = null,
    onCursorPathSelected: ((List<Int>) -> Unit)? = null,
    onSelectToken: ((path: List<Int>, index: Int) -> Unit)? = null,
    onExtendSelection: ((path: List<Int>, index: Int) -> Unit)? = null,
    onSelectRange: ((List<Int>, IntRange) -> Unit)? = null
) {
    val expFontSize = (fontSize.value * 0.62f).sp
    val base = node.base
    val supOffset = (fontSize.value * 0.28f).dp

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Child indices must match ExponentNode.subStrings: [base, exponent] whenever a base
        // slot exists (even if still empty), otherwise [exponent]
        if (base != null) {
            Box(
                modifier = Modifier.padding(vertical = supOffset),
                contentAlignment = Alignment.Center
            ) {
                MathExpressionView(
                    mathString = base,
                    cursorPath = if (focusedChildIdx == 0) childCursorPath else emptyList(),
                    fontSize = fontSize,
                    textColor = textColor,
                    accentColor = accentColor,
                    selectionRange = if (focusedChildIdx == 0) selectionRange else null,
                    onCursorPathSelected = onCursorPathSelected?.let { cb ->
                        { subPath -> cb(listOf(nodeIndex, 0) + subPath) }
                    },
                    onSelectToken = onSelectToken?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                    },
                    onExtendSelection = onExtendSelection?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                    },
                    onSelectRange = onSelectRange?.let { cb ->
                        { subPath, range -> cb(listOf(nodeIndex, 0) + subPath, range) }
                    }
                )
            }
            Spacer(modifier = Modifier.width(2.5.dp))
        }
        Box(
            modifier = Modifier
                .padding(bottom = supOffset * 2)
                .align(Alignment.CenterVertically),
            contentAlignment = Alignment.Center
        ) {
            val childIdx = if (base != null) 1 else 0
            MathExpressionView(
                mathString = node.exponent,
                cursorPath = if (focusedChildIdx == childIdx) childCursorPath else emptyList(),
                fontSize = expFontSize,
                textColor = textColor,
                accentColor = accentColor,
                selectionRange = if (focusedChildIdx == childIdx) selectionRange else null,
                onCursorPathSelected = onCursorPathSelected?.let { cb ->
                    { subPath -> cb(listOf(nodeIndex, childIdx) + subPath) }
                },
                onSelectToken = onSelectToken?.let { cb ->
                    { subPath, idx -> cb(listOf(nodeIndex, childIdx) + subPath, idx) }
                },
                onExtendSelection = onExtendSelection?.let { cb ->
                    { subPath, idx -> cb(listOf(nodeIndex, childIdx) + subPath, idx) }
                },
                onSelectRange = onSelectRange?.let { cb ->
                    { subPath, range -> cb(listOf(nodeIndex, childIdx) + subPath, range) }
                }
            )
        }
        // Clickable target after exponent to step down
        if (onCursorPathSelected != null) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(Dp(fontSize.value * 1.2f))
                    .clickable {
                        onCursorPathSelected(listOf(nodeIndex + 1))
                    }
            )
        }
    }
}

@Composable
private fun RadicalView(
    node: RadicalNode,
    nodeIndex: Int,
    fontSize: TextUnit,
    textColor: Color,
    accentColor: Color,
    focusedChildIdx: Int = -1,
    childCursorPath: List<Int> = emptyList(),
    selectionRange: IntRange? = null,
    onCursorPathSelected: ((List<Int>) -> Unit)? = null,
    onSelectToken: ((path: List<Int>, index: Int) -> Unit)? = null,
    onExtendSelection: ((path: List<Int>, index: Int) -> Unit)? = null,
    onSelectRange: ((List<Int>, IntRange) -> Unit)? = null
) {
    val radicandFontSize = (fontSize.value * 0.9f).sp
    val degreeFontSize = (fontSize.value * 0.55f).sp
    val degree = node.degree

    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
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
                Box(modifier = Modifier.offset(x = 0.dp, y = 1.dp)) {
                    MathExpressionView(
                        mathString = degree,
                        cursorPath = if (focusedChildIdx == 0) childCursorPath else emptyList(),
                        fontSize = degreeFontSize,
                        textColor = textColor,
                        accentColor = accentColor,
                        selectionRange = if (focusedChildIdx == 0) selectionRange else null,
                        onCursorPathSelected = onCursorPathSelected?.let { cb ->
                            { subPath -> cb(listOf(nodeIndex, 0) + subPath) }
                        },
                        onSelectToken = onSelectToken?.let { cb ->
                            { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                        },
                        onExtendSelection = onExtendSelection?.let { cb ->
                            { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                        },
                        onSelectRange = onSelectRange?.let { cb ->
                            { subPath, range -> cb(listOf(nodeIndex, 0) + subPath, range) }
                        }
                    )
                }
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
                    style = Stroke(
                        width = strokeW,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
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
                val childIdx = if (degree != null) 1 else 0
                MathExpressionView(
                    mathString = node.radicand,
                    cursorPath = if (focusedChildIdx == childIdx) childCursorPath else emptyList(),
                    fontSize = radicandFontSize,
                    textColor = textColor,
                    accentColor = accentColor,
                    selectionRange = if (focusedChildIdx == childIdx) selectionRange else null,
                    onCursorPathSelected = onCursorPathSelected?.let { cb ->
                        { subPath -> cb(listOf(nodeIndex, childIdx) + subPath) }
                    },
                    onSelectToken = onSelectToken?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, childIdx) + subPath, idx) }
                    },
                    onExtendSelection = onExtendSelection?.let { cb ->
                        { subPath, idx -> cb(listOf(nodeIndex, childIdx) + subPath, idx) }
                    },
                    onSelectRange = onSelectRange?.let { cb ->
                        { subPath, range -> cb(listOf(nodeIndex, childIdx) + subPath, range) }
                    }
                )
            }
        }

        // Clickable target after radical to exit
        if (onCursorPathSelected != null) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .fillMaxHeight()
                    .clickable {
                        onCursorPathSelected(listOf(nodeIndex + 1))
                    }
            )
        }
    }
}

@Composable
private fun LogBaseView(
    node: LogBaseNode,
    nodeIndex: Int,
    fontSize: TextUnit,
    textColor: Color,
    accentColor: Color,
    focusedChildIdx: Int = -1,
    childCursorPath: List<Int> = emptyList(),
    selectionRange: IntRange? = null,
    onCursorPathSelected: ((List<Int>) -> Unit)? = null,
    onSelectToken: ((path: List<Int>, index: Int) -> Unit)? = null,
    onExtendSelection: ((path: List<Int>, index: Int) -> Unit)? = null,
    onSelectRange: ((List<Int>, IntRange) -> Unit)? = null
) {
    @Composable
    fun Slot(child: Int, string: MathString, size: TextUnit) {
        MathExpressionView(
            mathString = string,
            cursorPath = if (focusedChildIdx == child) childCursorPath else emptyList(),
            fontSize = size,
            textColor = textColor,
            accentColor = accentColor,
            selectionRange = if (focusedChildIdx == child) selectionRange else null,
            onCursorPathSelected = onCursorPathSelected?.let { cb ->
                { subPath -> cb(listOf(nodeIndex, child) + subPath) }
            },
            onSelectToken = onSelectToken?.let { cb ->
                { subPath, idx -> cb(listOf(nodeIndex, child) + subPath, idx) }
            },
            onExtendSelection = onExtendSelection?.let { cb ->
                { subPath, idx -> cb(listOf(nodeIndex, child) + subPath, idx) }
            },
            onSelectRange = onSelectRange?.let { cb ->
                { subPath, range -> cb(listOf(nodeIndex, child) + subPath, range) }
            }
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "log",
            fontSize = (fontSize.value * 0.9f).sp,
            fontWeight = FontWeight.SemiBold,
            color = accentColor
        )
        Box(modifier = Modifier.padding(top = (fontSize.value * 0.45f).dp)) {
            Slot(child = 0, string = node.base, size = (fontSize.value * 0.6f).sp)
        }
        Text(text = "(", fontSize = fontSize, color = textColor)
        Slot(child = 1, string = node.argument, size = (fontSize.value * 0.9f).sp)
        Text(text = ")", fontSize = fontSize, color = textColor)
    }
}

@Composable
private fun ParenthesesView(
    node: ParenthesesNode,
    nodeIndex: Int,
    fontSize: TextUnit,
    textColor: Color,
    accentColor: Color,
    focusedChildIdx: Int = -1,
    childCursorPath: List<Int> = emptyList(),
    selectionRange: IntRange? = null,
    onCursorPathSelected: ((List<Int>) -> Unit)? = null,
    onSelectToken: ((path: List<Int>, index: Int) -> Unit)? = null,
    onExtendSelection: ((path: List<Int>, index: Int) -> Unit)? = null,
    onSelectRange: ((List<Int>, IntRange) -> Unit)? = null
) {
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
            drawPath(
                path = path,
                color = textColor,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }

        Box(
            modifier = Modifier.padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            MathExpressionView(
                mathString = node.inner,
                cursorPath = if (focusedChildIdx == 0) childCursorPath else emptyList(),
                fontSize = fontSize,
                textColor = textColor,
                accentColor = accentColor,
                selectionRange = if (focusedChildIdx == 0) selectionRange else null,
                onCursorPathSelected = onCursorPathSelected?.let { cb ->
                    { subPath -> cb(listOf(nodeIndex, 0) + subPath) }
                },
                onSelectToken = onSelectToken?.let { cb ->
                    { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                },
                onExtendSelection = onExtendSelection?.let { cb ->
                    { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                },
                onSelectRange = onSelectRange?.let { cb ->
                    { subPath, range -> cb(listOf(nodeIndex, 0) + subPath, range) }
                }
            )
        }

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
            drawPath(
                path = path,
                color = textColor,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }

        // Clickable target after parentheses to exit
        if (onCursorPathSelected != null) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .fillMaxHeight()
                    .clickable {
                        onCursorPathSelected(listOf(nodeIndex + 1))
                    }
            )
        }
    }
}

@Composable
private fun IntegralView(
    node: IntegralNode,
    nodeIndex: Int,
    fontSize: TextUnit,
    textColor: Color,
    accentColor: Color,
    focusedChildIdx: Int = -1,
    childCursorPath: List<Int> = emptyList(),
    selectionRange: IntRange? = null,
    onCursorPathSelected: ((List<Int>) -> Unit)? = null,
    onSelectToken: ((path: List<Int>, index: Int) -> Unit)? = null,
    onExtendSelection: ((path: List<Int>, index: Int) -> Unit)? = null,
    onSelectRange: ((List<Int>, IntRange) -> Unit)? = null
) {
    val boundFontSize = (fontSize.value * 0.55f).sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            MathExpressionView(
                mathString = node.upperBound,
                cursorPath = if (focusedChildIdx == 0) childCursorPath else emptyList(),
                fontSize = boundFontSize,
                textColor = textColor,
                accentColor = accentColor,
                selectionRange = if (focusedChildIdx == 0) selectionRange else null,
                onCursorPathSelected = onCursorPathSelected?.let { cb ->
                    { subPath -> cb(listOf(nodeIndex, 0) + subPath) }
                },
                onSelectToken = onSelectToken?.let { cb ->
                    { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                },
                onExtendSelection = onExtendSelection?.let { cb ->
                    { subPath, idx -> cb(listOf(nodeIndex, 0) + subPath, idx) }
                },
                onSelectRange = onSelectRange?.let { cb ->
                    { subPath, range -> cb(listOf(nodeIndex, 0) + subPath, range) }
                }
            )
            Text(
                text = "∫",
                fontSize = (fontSize.value * 1.5f).sp,
                color = accentColor,
                fontWeight = FontWeight.ExtraLight,
                modifier = Modifier.offset(y = (-2).dp)
            )
            MathExpressionView(
                mathString = node.lowerBound,
                cursorPath = if (focusedChildIdx == 1) childCursorPath else emptyList(),
                fontSize = boundFontSize,
                textColor = textColor,
                accentColor = accentColor,
                selectionRange = if (focusedChildIdx == 1) selectionRange else null,
                onCursorPathSelected = onCursorPathSelected?.let { cb ->
                    { subPath -> cb(listOf(nodeIndex, 1) + subPath) }
                },
                onSelectToken = onSelectToken?.let { cb ->
                    { subPath, idx -> cb(listOf(nodeIndex, 1) + subPath, idx) }
                },
                onExtendSelection = onExtendSelection?.let { cb ->
                    { subPath, idx -> cb(listOf(nodeIndex, 1) + subPath, idx) }
                },
                onSelectRange = onSelectRange?.let { cb ->
                    { subPath, range -> cb(listOf(nodeIndex, 1) + subPath, range) }
                }
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
        MathExpressionView(
            mathString = node.integrand,
            cursorPath = if (focusedChildIdx == 2) childCursorPath else emptyList(),
            fontSize = fontSize,
            textColor = textColor,
            accentColor = accentColor,
            selectionRange = if (focusedChildIdx == 2) selectionRange else null,
            onCursorPathSelected = onCursorPathSelected?.let { cb ->
                { subPath -> cb(listOf(nodeIndex, 2) + subPath) }
            },
            onSelectToken = onSelectToken?.let { cb ->
                { subPath, idx -> cb(listOf(nodeIndex, 2) + subPath, idx) }
            },
            onExtendSelection = onExtendSelection?.let { cb ->
                { subPath, idx -> cb(listOf(nodeIndex, 2) + subPath, idx) }
            },
            onSelectRange = onSelectRange?.let { cb ->
                { subPath, range -> cb(listOf(nodeIndex, 2) + subPath, range) }
            }
        )
        Text(
            text = " d${node.variable}",
            fontSize = fontSize,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Normal,
            color = textColor
        )
        if (onCursorPathSelected != null) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height(Dp(fontSize.value * 1.2f))
                    .clickable {
                        onCursorPathSelected(listOf(nodeIndex + 1))
                    }
            )
        }
    }
}

@Composable
private fun MatrixView(
    node: MatrixNode,
    nodeIndex: Int,
    fontSize: TextUnit,
    textColor: Color,
    accentColor: Color,
    focusedChildIdx: Int = -1,
    childCursorPath: List<Int> = emptyList(),
    selectionRange: IntRange? = null,
    onCursorPathSelected: ((List<Int>) -> Unit)? = null,
    onSelectToken: ((path: List<Int>, index: Int) -> Unit)? = null,
    onExtendSelection: ((path: List<Int>, index: Int) -> Unit)? = null,
    onSelectRange: ((List<Int>, IntRange) -> Unit)? = null
) {
    val elementFontSize = (fontSize.value * 0.8f).sp
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
                            MathExpressionView(
                                mathString = node.elements[idx],
                                cursorPath = if (focusedChildIdx == idx) childCursorPath else emptyList(),
                                fontSize = elementFontSize,
                                textColor = textColor,
                                accentColor = accentColor,
                                selectionRange = if (focusedChildIdx == idx) selectionRange else null,
                                onCursorPathSelected = onCursorPathSelected?.let { cb ->
                                    { subPath -> cb(listOf(nodeIndex, idx) + subPath) }
                                },
                                onSelectToken = onSelectToken?.let { cb ->
                                    { subPath, tokenIdx -> cb(listOf(nodeIndex, idx) + subPath, tokenIdx) }
                                },
                                onExtendSelection = onExtendSelection?.let { cb ->
                                    { subPath, tokenIdx -> cb(listOf(nodeIndex, idx) + subPath, tokenIdx) }
                                },
                                onSelectRange = onSelectRange?.let { cb ->
                                    { subPath, range -> cb(listOf(nodeIndex, idx) + subPath, range) }
                                }
                            )
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

        if (onCursorPathSelected != null) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .fillMaxHeight()
                    .clickable {
                        onCursorPathSelected(listOf(nodeIndex + 1))
                    }
            )
        }
    }
}
