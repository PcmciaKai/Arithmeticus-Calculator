package com.vortessence.calculator.ui.math

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
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
import com.vortessence.calculator.core.math.MathEngine
import com.vortessence.calculator.core.math.type.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The variables defined with := (name to value). A derivative whose body only has a defined
 * variable besides its own is an ordinary one: the definition is substituted before differentiating.
 */
val LocalVariableDefinitions = staticCompositionLocalOf<Map<String, MathString>> { emptyMap() }

/** Whether numbers are shown with digit groups (300 000). Display only, the text is unchanged. */
val LocalDigitGrouping = staticCompositionLocalOf { false }

/**
 * Natural WYSIWYG mathematical expression layout in Jetpack Compose.
 * Supports fractions, radicals, exponents, parentheses, functions, cursor and selection display.
 * A string with line breaks (several equations) is shown as one row per line.
 *
 * The view itself is not interactive. Inside [MathEditor] it reports the on-screen position
 * of every node to [caretGeometry], which the editor uses for all touch handling.
 *
 * @param path The caret path prefix of [mathString] within the editor's root string.
 * @param verticalPadding Space above and below the row(s).
 * @param hidePlaceholder Draw no placeholder box for an empty string: it is empty on purpose (the () of rand()).
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
    verticalPadding: Dp = 4.dp,
    hidePlaceholder: Boolean = false
) {
    // The theme's line height (24sp) would give small text, e.g. a fraction in an exponent, a
    // line far taller than its letters: wide gaps, and a minus that is not level with the bar.
    // Below 24sp the line is as tall as the font size, as for 24sp text.
    val style = LocalTextStyle.current
    val lineHeight = if (fontSize.value < MAX_SCALED_LINE_HEIGHT_SP) fontSize else style.lineHeight
    CompositionLocalProvider(LocalTextStyle provides style.copy(lineHeight = lineHeight)) {
        MathExpressionLayout(mathString, modifier, cursorPath, selectionRange, fontSize, textColor, accentColor, caretGeometry, path, verticalPadding, hidePlaceholder)
    }
}

/** Text from this size (sp) up keeps the theme's line height. */
private const val MAX_SCALED_LINE_HEIGHT_SP = 24f

@Composable
private fun MathExpressionLayout(
    mathString: MathString,
    modifier: Modifier,
    cursorPath: List<Int>,
    selectionRange: IntRange?,
    fontSize: TextUnit,
    textColor: Color,
    accentColor: Color,
    caretGeometry: CaretGeometry?,
    path: List<Int>,
    verticalPadding: Dp,
    hidePlaceholder: Boolean
) {
    if (caretGeometry != null) {
        DisposableEffect(caretGeometry, path) {
            onDispose { caretGeometry.removeString(path) }
        }
    }
    val length = mathString.length
    val row = ExpressionRow(
        mathString = mathString,
        cursorPath = cursorPath,
        selectionRange = selectionRange,
        fontSize = fontSize,
        textColor = textColor,
        accentColor = accentColor,
        caretGeometry = caretGeometry,
        path = path,
        groupedDigits = if (LocalDigitGrouping.current) groupDigits(mathString.nodes) else emptyMap(),
        hidePlaceholder = hidePlaceholder
    )
    val breaks = mathString.nodes.indices.filter { mathString.nodes[it] is LineBreakNode }
    val stringModifier = modifier
        .padding(vertical = verticalPadding)
        .then(
            if (caretGeometry == null) Modifier
            else Modifier.onGloballyPositioned { caretGeometry.setString(path, length, breaks.size + 1, it) }
        )

    if (breaks.isEmpty()) {
        row.Line(start = 0, end = length, isOnlyLine = true, modifier = stringModifier)
        return
    }
    // Line i covers the nodes before its line break; caret positions start..end (the break)
    val starts = listOf(0) + breaks.map { it + 1 }
    val ends = breaks + length
    Column(modifier = stringModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in starts.indices) {
            row.Line(
                start = starts[line],
                end = ends[line],
                isOnlyLine = false,
                modifier = if (caretGeometry == null) Modifier else Modifier.onGloballyPositioned {
                    caretGeometry.setLine(path, line, starts.size, starts[line], ends[line], it)
                }
            )
        }
    }
}

/** Renders the lines of one string; all node indices and caret positions are those of the whole string. */
private class ExpressionRow(
    val mathString: MathString,
    val cursorPath: List<Int>,
    val selectionRange: IntRange?,
    val fontSize: TextUnit,
    val textColor: Color,
    val accentColor: Color,
    val caretGeometry: CaretGeometry?,
    val path: List<Int>,
    val groupedDigits: Map<Int, String>,
    val hidePlaceholder: Boolean = false
) {
    /**
     * The nodes start until end, followed by the line break at [end] unless this is the last
     * line. [isOnlyLine]: a string without line breaks; empty, it shows a placeholder box.
     */
    @Composable
    fun Line(start: Int, end: Int, isOnlyLine: Boolean, modifier: Modifier) {
        // The cursor/selection is in this string (not in a nested one) when only a position is left
        val isCursorHere = cursorPath.size == 1
        // Node bounds within this row, used to draw the selection as one continuous band
        val nodeBounds = remember { mutableStateMapOf<Int, Rect>() }
        val activeSelection = if (isCursorHere) selectionRange else null
        val selectionColor = accentColor.copy(alpha = 0.25f)
        val hasBreak = end < mathString.length

        Row(
            modifier = modifier.drawBehind {
                if (activeSelection == null) return@drawBehind
                // One highlight spanning the selected nodes of this line, as tall as the tallest of them
                val rects = activeSelection.mapNotNull { if (it in start..end) nodeBounds[it] else null }
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
            val showCursorAtStart = isCursorHere && cursorPath[0] == start && selectionRange == null
            if (start == end) {
                when {
                    showCursorAtStart ->
                        BlinkingCursor(fontSize, accentColor, Modifier.centeredMathAxis().alignBy(MathAxis))
                    isOnlyLine && !hidePlaceholder -> EmptySlotPlaceholder(fontSize = fontSize, color = textColor)
                    // An empty line keeps the height of a line
                    else -> Spacer(Modifier.size(width = 0.dp, height = Dp(fontSize.value * 0.9f)).centeredMathAxis().alignBy(MathAxis))
                }
                if (hasBreak) LineBreakMarker(end, nodeBounds)
                return@Row
            }

            for (index in start until end) {
                val node = mathString.nodes[index]
                if (isCursorHere && cursorPath[0] == index && selectionRange == null) {
                    BlinkingCursor(fontSize, accentColor, Modifier.centeredMathAxis().alignBy(MathAxis))
                }

                val isNodeFocused = cursorPath.size > 1 && cursorPath[0] == index
                val isUnaryMinus = node is OperatorNode && node.op == MathOp.MINUS &&
                    (index == start || (mathString.nodes[index - 1] as? OperatorNode)?.op?.isPostfix == false ||
                        // (−∞, −2): after the bracket or comma of an interval
                        (mathString.nodes[index - 1] as? TextNode)?.text?.trimEnd()?.let { it.endsWith("(") || it.endsWith("[") || it.endsWith("{") || it.endsWith(",") || it.endsWith("∪") } == true)
                // −½: a gap, so that the minus does not run into the fraction bar
                val isSignOfFraction = isUnaryMinus && !LocalInExponent.current && mathString.nodes.getOrNull(index + 1).let {
                    it is FractionNode || (it is HighlightNode && it.content.nodes.firstOrNull() is FractionNode)
                }

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
                    MathNodeView(node, fontSize, slots, isUnaryMinus, groupedDigits[index], isSignOfFraction)
                }
            }

            if (isCursorHere && cursorPath[0] == end && selectionRange == null) {
                BlinkingCursor(fontSize, accentColor, Modifier.centeredMathAxis().alignBy(MathAxis))
            }
            if (hasBreak) LineBreakMarker(end, nodeBounds)
        }
    }

    /**
     * The line break node at [index]: invisible, at the end of its line, so that it has a
     * position like every other node (for hit testing and selection handles).
     */
    @Composable
    private fun RowScope.LineBreakMarker(index: Int, nodeBounds: MutableMap<Int, Rect>) {
        Spacer(
            modifier = Modifier
                .size(width = 0.dp, height = Dp(fontSize.value * 0.9f))
                .centeredMathAxis()
                .alignBy(MathAxis)
                .onPlaced { nodeBounds[index] = Rect(it.positionInParent(), it.size.toSize()) }
                .then(
                    if (caretGeometry == null) Modifier
                    else Modifier.onGloballyPositioned { caretGeometry.setNode(path, index, it) }
                )
        )
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

/**
 * Puts the math axis where the bar of a minus is drawn, [OPERATOR_AXIS_EM] above the baseline,
 * so that −, + and = are level with fraction bars. (In small text the centre of the line is
 * noticeably higher.)
 */
private fun Modifier.operatorAxis(fontSize: TextUnit) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = placeable[FirstBaseline]
    val axis = if (baseline == AlignmentLine.Unspecified) placeable.height / 2 else baseline - (OPERATOR_AXIS_EM * fontSize.toPx()).roundToInt()
    layout(placeable.width, placeable.height, mapOf(MathAxis to axis)) {
        placeable.place(0, 0)
    }
}

/** Height of the minus bar above the baseline, in em (Roboto). */
private const val OPERATOR_AXIS_EM = 0.37f

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
    verticalPadding: Dp = 4.dp,
    textColor: Color = this.textColor,
    accentColor: Color = this.accentColor,
    hidePlaceholder: Boolean = false
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
        verticalPadding = verticalPadding,
        hidePlaceholder = hidePlaceholder
    )
}

/**
 * Makes the variable label of a node (the x of dx) tappable in the editor: reports where it is to
 * the [CaretGeometry], which the editor asks first on a tap, and tints it so that it shows. Plain
 * outside of an editor (history, steps). [part] tells the labels of one node apart.
 */
@Composable
private fun SlotContext.variableLabel(part: Int, tint: Color = accentColor): Modifier {
    val geometry = caretGeometry ?: return Modifier
    val path = parentPath
    val index = nodeIndex
    DisposableEffect(geometry, path, index, part) {
        onDispose { geometry.removeVariableLabel(path, index, part) }
    }
    return Modifier
        .onGloballyPositioned { geometry.setVariableLabel(path, index, part, it) }
        .background(tint.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
        .padding(horizontal = 2.dp)
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
    displayText: String? = null,
    isSignOfFraction: Boolean = false
) {
    val textColor = slots.textColor
    val accentColor = slots.accentColor
    when (node) {
        is NumberNode -> {
            Text(
                text = DecimalMark.display(displayText ?: node.text),
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                color = textColor,
                modifier = Modifier.padding(horizontal = 0.5.dp)
            )
        }
        is OperatorNode -> {
            if (node.op == MathOp.CONVERT) {
                ConversionArrow(fontSize, accentColor)
            } else {
                Text(
                    text = if (node.op.isPostfix || isUnaryMinus) node.op.symbol else " ${node.op.symbol} ",
                    fontSize = fontSize,
                    fontWeight = when {
                        node.op.isPostfix -> FontWeight.Normal
                        // Light strokes all but vanish in small text, e.g. the minus in x^(−1/2)
                        fontSize.value < SMALL_TEXT_SP -> FontWeight.Medium
                        else -> FontWeight.Light
                    },
                    color = if (node.op.isPostfix) textColor else accentColor,
                    modifier = (if (isSignOfFraction) Modifier.padding(end = (fontSize.value * 0.15f).dp) else Modifier)
                        .then(if (fontSize.value < MAX_SCALED_LINE_HEIGHT_SP && !node.op.isPostfix) Modifier.operatorAxis(fontSize) else Modifier)
                )
            }
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
        is AnsNode -> {
            Text(
                text = "Ans",
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                color = accentColor,
                modifier = Modifier.padding(horizontal = 1.dp)
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
        // Line breaks are laid out by MathExpressionView, see ExpressionRow.LineBreakMarker
        is LineBreakNode -> {}
        is SeparatorNode -> {
            Text(
                text = DecimalMark.argumentSeparator,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                color = textColor
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
        is FractionNode -> if (LocalInExponent.current) InlineFractionView(node, fontSize, slots) else FractionView(node, fontSize, slots)
        is PolarNode -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.centeredMathAxis()) {
            slots.Slot(child = 0, string = node.magnitude, fontSize = fontSize)
            Text(text = "∠", fontSize = fontSize, fontWeight = FontWeight.Light, color = accentColor)
            slots.Slot(child = 1, string = node.angle, fontSize = fontSize)
        }
        is MixedFractionNode -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.centeredMathAxis()) {
            slots.Slot(child = 0, string = node.whole, fontSize = fontSize)
            FractionLayout(node.numerator, 1, node.denominator, 2, fontSize, slots)
        }
        is ExponentNode -> ExponentView(node, fontSize, slots)
        is SciExponentNode -> {
            // Part of the number before it: no spacing, same color as digits
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.centeredMathAxis()) {
                Text(text = "×10", fontSize = fontSize, fontWeight = FontWeight.Normal, color = textColor)
                CompositionLocalProvider(LocalInExponent provides true) {
                    slots.Slot(
                        child = 0,
                        string = node.exponent,
                        fontSize = (fontSize.value * 0.62f).sp,
                        modifier = Modifier.padding(bottom = (fontSize.value * 0.56f).dp)
                    )
                }
            }
        }
        is RadicalNode -> RadicalView(node, fontSize, slots)
        is ParenthesesNode -> ParenthesesView(node, fontSize, slots)
        is HighlightNode -> HighlightView(node, fontSize, slots)
        is EvaluationNode -> EvaluationView(node, fontSize, slots)
        is LogBaseNode -> LogBaseView(node, fontSize, slots)
        // The absolute value (e.g. in ln|x|) is written with bars, not as a named function
        is FunctionNode if node.name == "abs" -> slots.Slot(
            child = 0,
            string = node.argument,
            fontSize = fontSize,
            modifier = Modifier.absoluteValueBars(textColor)
        )
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
                // ln|x| rather than ln(|x|): the bars already enclose the argument
                val isAbsoluteValue = (node.argument.nodes.singleOrNull() as? FunctionNode)?.name == "abs"
                val argumentFontSize = (fontSize.value * 0.9f).sp
                slots.Slot(
                    child = 0,
                    string = node.argument,
                    fontSize = argumentFontSize,
                    modifier = if (isAbsoluteValue) onAxis else onAxis.stretchyParentheses(textColor, argumentFontSize, isFlat(node.argument)),
                    // Room above and below for the parentheses, in proportion to the text
                    verticalPadding = (argumentFontSize.value * 0.04f).dp,
                    // rand() has no argument: an empty slot is how it is written, not a gap to fill
                    hidePlaceholder = node.name.lowercase() == "rand" && node.argument.isEmpty
                )
            }
        }
        is IntegralNode -> IntegralView(node, fontSize, slots)
        is DerivativeNode -> DerivativeView(node, fontSize, slots)
        is AntiderivativeNode -> AntiderivativeView(node, fontSize, slots)
        is BigOperatorNode -> BigOperatorView(node, fontSize, slots)
        is LimitNode -> LimitView(node, fontSize, slots)
        is MatrixNode -> MatrixView(node, fontSize, slots)
    }
}

/** Below this size (sp), text is small, e.g. in an exponent, and needs heavier strokes. */
private const val SMALL_TEXT_SP = 20f

/**
 * Whether the content is in an exponent, where fractions are written inline (x^(1/2)): stacked,
 * they would be too small to read.
 */
private val LocalInExponent = compositionLocalOf { false }

/**
 * Numerator/denominator on one line, for an exponent; a numerator or denominator of several
 * parts in parentheses: x^((n + 1)/2).
 */
@Composable
private fun InlineFractionView(node: FractionNode, fontSize: TextUnit, slots: SlotContext) {
    @Composable
    fun Part(child: Int, string: MathString) = slots.Slot(
        child = child,
        string = string,
        fontSize = fontSize,
        modifier = if (MathText.isGroupedBase(string)) Modifier.stretchyParentheses(slots.textColor, fontSize, isFlat(string)) else Modifier,
        verticalPadding = 0.dp
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.centeredMathAxis()) {
        Part(0, node.numerator)
        Text(text = "/", fontSize = fontSize, fontWeight = FontWeight.Normal, color = slots.textColor)
        Part(1, node.denominator)
    }
}

/** Numerator over denominator; the math axis runs through the middle of the bar. */
@Composable
private fun FractionView(node: FractionNode, fontSize: TextUnit, slots: SlotContext) =
    FractionLayout(node.numerator, 0, node.denominator, 1, fontSize, slots)

/**
 * [numerator] (slot [numeratorChild]) over [denominator] (slot [denominatorChild]). Spacing and
 * bar scale with [fontSize] (as at 32sp in the input), so that a small fraction, e.g. 1/2 in an
 * exponent, is not spread out by gaps meant for a large one.
 */
@Composable
private fun FractionLayout(
    numerator: MathString,
    numeratorChild: Int,
    denominator: MathString,
    denominatorChild: Int,
    fontSize: TextUnit,
    slots: SlotContext
) {
    val childFontSize = (fontSize.value * 0.8f).sp
    val em = fontSize.value / 32f
    val slotPadding = Modifier.padding(horizontal = (4 * em).dp, vertical = (2 * em).dp)
    Layout(
        content = {
            slots.Slot(
                child = numeratorChild,
                string = numerator,
                fontSize = childFontSize,
                modifier = slotPadding,
                verticalPadding = (4 * em).dp
            )
            HorizontalDivider(thickness = max(1f, 1.8f * em).dp, color = slots.textColor)
            slots.Slot(
                child = denominatorChild,
                string = denominator,
                fontSize = childFontSize,
                modifier = slotPadding,
                verticalPadding = (4 * em).dp
            )
        },
        modifier = Modifier.padding(horizontal = (2 * em).dp)
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
                // A base of several parts is squared as a whole, so it is shown so: (2x)², not 2x²
                if (MathText.isGroupedBase(base)) {
                    slots.Slot(
                        child = 0,
                        string = base,
                        fontSize = fontSize,
                        modifier = Modifier.stretchyParentheses(slots.textColor, fontSize, isFlat(base)),
                        verticalPadding = (fontSize.value * 0.125f).dp
                    )
                } else {
                    slots.Slot(child = 0, string = base, fontSize = fontSize, verticalPadding = 0.dp)
                }
            }
            // Fractions in the exponent are written inline, x^(1/2)
            CompositionLocalProvider(LocalInExponent provides true) {
                slots.Slot(
                    child = if (base != null) 1 else 0,
                    string = node.exponent,
                    fontSize = expFontSize,
                    verticalPadding = 0.dp
                )
            }
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
        slots.Slot(
            child = 1,
            string = node.argument,
            fontSize = (fontSize.value * 0.9f).sp,
            modifier = Modifier.stretchyParentheses(slots.textColor, (fontSize.value * 0.9f).sp, isFlat(node.argument))
        )
    }
}

/**
 * The unit conversion arrow "→", drawn rather than typed: Roboto has no arrow glyph, and the
 * fallback font's taller line box would put a typed one below the other operators (nodes are
 * aligned by their vertical centre). Centred in its own box, it sits on the operator axis.
 */
@Composable
private fun ConversionArrow(fontSize: TextUnit, color: Color) {
    val em = fontSize.value
    Canvas(
        modifier = Modifier
            // The same footprint as " → ": a space on either side of the arrow
            .padding(horizontal = (em * 0.25f).dp)
            .size(width = (em * 0.75f).dp, height = (em * 0.9f).dp)
    ) {
        // About the stem width of the light "+" and "−"
        val strokeW = (em * 0.055f).dp.toPx()
        val midY = size.height / 2f
        val start = strokeW / 2f
        val tip = size.width - strokeW / 2f
        val head = (em * 0.2f).dp.toPx()
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(start, midY), Offset(tip, midY), strokeWidth = strokeW, cap = StrokeCap.Round)
        val arrowHead = Path().apply {
            moveTo(tip - head, midY - head)
            lineTo(tip, midY)
            lineTo(tip - head, midY + head)
        }
        drawPath(path = arrowHead, color = color, style = stroke)
    }
}

@Composable
private fun ParenthesesView(node: ParenthesesNode, fontSize: TextUnit, slots: SlotContext) {
    slots.Slot(
        child = 0,
        string = node.inner,
        fontSize = fontSize,
        modifier = Modifier.stretchyParentheses(slots.textColor, fontSize, isFlat(node.inner)),
        verticalPadding = (fontSize.value * 0.125f).dp
    )
}

/**
 * The colors that mark the parts of a step-by-step solution, by [HighlightNode.colorIndex]
 * (they repeat for long solutions).
 */
private val HighlightColors = listOf(
    Color(0xFFE07B53), // orange
    Color(0xFF4A90D9), // blue
    Color(0xFF4CAF7A), // green
    Color(0xFFAF52DE), // purple
    Color(0xFFD4A017), // yellow
    Color(0xFFE0557A), // pink
)

fun highlightColor(index: Int): Color = HighlightColors[Math.floorMod(index - 1, HighlightColors.size)]

/**
 * A part of a solution step: a box in its color around what the last step produced, or the
 * part the next step changes in its color.
 */
@Composable
private fun HighlightView(node: HighlightNode, fontSize: TextUnit, slots: SlotContext) {
    val color = highlightColor(node.colorIndex)
    if (node.boxed) {
        slots.Slot(
            child = 0,
            string = node.content,
            fontSize = fontSize,
            modifier = Modifier
                .border(1.5.dp, color, RoundedCornerShape(6.dp))
                .padding(horizontal = max(3f, fontSize.value * 0.2f).dp),
            // Room for the top line of a root, which would otherwise run along the border
            verticalPadding = max(4f, fontSize.value * 0.25f).dp
        )
    } else {
        slots.Slot(child = 0, string = node.content, fontSize = fontSize, verticalPadding = 0.dp, textColor = color, accentColor = color)
    }
}

/** Vertical bars around the content, as tall as it is: |x|. */
private fun Modifier.absoluteValueBars(color: Color) = this
    .drawBehind {
        val strokeW = 1.6.dp.toPx()
        val inset = 2.dp.toPx()
        val x = 3.dp.toPx()
        drawLine(color, Offset(x, inset), Offset(x, size.height - inset), strokeWidth = strokeW)
        drawLine(color, Offset(size.width - x, inset), Offset(size.width - x, size.height - inset), strokeWidth = strokeW)
    }
    .padding(horizontal = 7.dp)

/**
 * Whether [string] is a single line of plain text (no fractions, powers, roots, ...), around
 * which parentheses are only as tall as the letters.
 */
private fun isFlat(string: MathString): Boolean = string.nodes.all { node ->
    when (node) {
        is FractionNode, is MixedFractionNode, is ExponentNode, is SciExponentNode, is RadicalNode, is LogBaseNode,
        is IntegralNode, is AntiderivativeNode, is DerivativeNode, is MatrixNode, is LineBreakNode, is EvaluationNode,
        is BigOperatorNode, is LimitNode -> false
        else -> node.subStrings.all { isFlat(it) }
    }
}

/**
 * Parentheses around the content. Around [flat] content (a line of text) they are as tall as
 * the letters, 1.2 em, however much spacing the font adds above and below a line; around taller
 * content (a fraction, a power) they stretch to its full height. Width and stroke scale with
 * [fontSize] (as at 32sp in the input), so they do not dwarf a small argument, e.g. in an exponent.
 */
private fun Modifier.stretchyParentheses(color: Color, fontSize: TextUnit, flat: Boolean) = this
    .drawBehind {
        val em = fontSize.value / 32f
        val w = (7f * em).dp.toPx()
        val fontPx = fontSize.toPx()
        val inset = if (flat) maxOf((size.height - 1.2f * fontPx) / 2f, 0f) else 0.1f * fontPx
        val strokeW = maxOf(1f, 1.8f * em).dp.toPx()
        val top = inset + strokeW / 2f
        val bottom = size.height - inset - strokeW / 2f
        val h = bottom - top
        val left = Path().apply {
            moveTo(w, top)
            cubicTo(w * 0.05f, top + h * 0.28f, w * 0.05f, top + h * 0.72f, w, bottom)
        }
        val right = Path().apply {
            val x0 = size.width - w
            moveTo(x0, top)
            cubicTo(x0 + w * 0.95f, top + h * 0.28f, x0 + w * 0.95f, top + h * 0.72f, x0, bottom)
        }
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round)
        drawPath(path = left, color = color, style = stroke)
        drawPath(path = right, color = color, style = stroke)
    }
    .padding(horizontal = (8.5f * fontSize.value / 32f).dp)

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
        Spacer(modifier = Modifier.width((fontSize.value * 0.2f).dp))
        Text(
            text = "d${node.variable}",
            fontSize = fontSize,
            fontWeight = FontWeight.Normal,
            color = slots.textColor,
            modifier = slots.variableLabel(part = 0)
        )
    }
}

/** Σ or ∏ with "n=1" below and the upper bound above, followed by the summand. */
@Composable
private fun BigOperatorView(node: BigOperatorNode, fontSize: TextUnit, slots: SlotContext) {
    val boundFontSize = (fontSize.value * 0.55f).sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp).centeredMathAxis()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            slots.Slot(child = 1, string = node.upper, fontSize = boundFontSize)
            Text(
                text = node.operator.symbol,
                fontSize = (fontSize.value * 1.3f).sp,
                color = slots.accentColor,
                fontWeight = FontWeight.Light,
                modifier = Modifier.offset(y = (-2).dp)
            )
            slots.Slot(child = 0, string = node.lower, fontSize = boundFontSize)
        }
        Spacer(modifier = Modifier.width(2.dp))
        slots.Slot(child = 2, string = node.body, fontSize = fontSize)
    }
}

/** "lim" with "x→a" below it, then the expression in parentheses. */
@Composable
private fun LimitView(node: LimitNode, fontSize: TextUnit, slots: SlotContext) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp).centeredMathAxis()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "lim",
                fontSize = (fontSize.value * 0.9f).sp,
                fontWeight = FontWeight.SemiBold,
                color = slots.accentColor
            )
            slots.Slot(child = 0, string = node.approach, fontSize = (fontSize.value * 0.55f).sp)
        }
        slots.Slot(
            child = 1,
            string = node.body,
            fontSize = fontSize,
            modifier = Modifier.stretchyParentheses(slots.textColor, fontSize, isFlat(node.body))
        )
    }
}

/** ∫ integrand dx, like [IntegralView] without bounds. */
@Composable
private fun AntiderivativeView(node: AntiderivativeNode, fontSize: TextUnit, slots: SlotContext) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp).centeredMathAxis()
    ) {
        Text(
            text = "∫",
            fontSize = (fontSize.value * 1.5f).sp,
            color = slots.accentColor,
            fontWeight = FontWeight.ExtraLight
        )
        Spacer(modifier = Modifier.width(2.dp))
        slots.Slot(child = 0, string = node.integrand, fontSize = fontSize)
        Spacer(modifier = Modifier.width((fontSize.value * 0.2f).dp))
        Text(
            text = "d${node.variable}",
            fontSize = fontSize,
            fontWeight = FontWeight.Normal,
            color = slots.textColor,
            modifier = slots.variableLabel(part = 0)
        )
    }
}

/**
 * d/dx as a small fraction, the expression in parentheses and, at a point, the bar |ₓ₌ₐ. A
 * partial derivative (the expression has more variables than x) is written with ∂.
 */
@Composable
private fun DerivativeView(node: DerivativeNode, fontSize: TextUnit, slots: SlotContext) {
    val labelFontSize = (fontSize.value * 0.7f).sp
    val subscriptFontSize = (fontSize.value * 0.55f).sp
    val definitions = LocalVariableDefinitions.current
    val isPartial = remember(node, definitions) { MathEngine.isPartialDerivative(node, definitions) }
    val d = if (isPartial) "∂" else "d"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp).centeredMathAxis()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(IntrinsicSize.Max)
        ) {
            Text(text = d, fontSize = labelFontSize, color = slots.accentColor, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(thickness = 1.5.dp, color = slots.accentColor)
            Text(
                text = "$d${node.variable}",
                fontSize = labelFontSize,
                color = slots.accentColor,
                fontWeight = FontWeight.SemiBold,
                modifier = slots.variableLabel(part = 0)
            )
        }
        slots.Slot(child = 0, string = node.body, fontSize = fontSize, modifier = Modifier.stretchyParentheses(slots.textColor, fontSize, isFlat(node.body)))
        val point = node.point
        if (point != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = "|", fontSize = (fontSize.value * 1.4f).sp, fontWeight = FontWeight.ExtraLight, color = slots.textColor)
                Text(text = "${node.variable}=", fontSize = subscriptFontSize, color = slots.textColor, modifier = slots.variableLabel(part = 1))
                slots.Slot(child = 1, string = point, fontSize = subscriptFontSize)
            }
        }
    }
}

/** [F]ₐᵇ: the content in square brackets, the upper bound top right, the lower bound bottom right. */
@Composable
private fun EvaluationView(node: EvaluationNode, fontSize: TextUnit, slots: SlotContext) {
    val boundFontSize = (fontSize.value * 0.6f).sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp).centeredMathAxis().height(IntrinsicSize.Min)
    ) {
        slots.Slot(child = 0, string = node.content, fontSize = fontSize, modifier = Modifier.squareBrackets(slots.textColor))
        Column(
            modifier = Modifier.fillMaxHeight().padding(start = 1.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            slots.Slot(child = 2, string = node.upper, fontSize = boundFontSize, verticalPadding = 0.dp)
            slots.Slot(child = 1, string = node.lower, fontSize = boundFontSize, verticalPadding = 0.dp)
        }
    }
}

@Composable
private fun MatrixView(node: MatrixNode, fontSize: TextUnit, slots: SlotContext) {
    val elementFontSize = (fontSize.value * 0.8f).sp
    MatrixGrid(
        rows = node.rows,
        cols = node.cols,
        // The outer padding keeps what comes next to the matrix (an exponent, another matrix) off the brackets
        modifier = Modifier.centeredMathAxis().padding(horizontal = 5.dp).squareBrackets(slots.textColor)
    ) {
        node.elements.forEachIndexed { idx, element ->
            slots.Slot(child = idx, string = element, fontSize = elementFontSize)
        }
    }
}

/**
 * Lays out [rows] × [cols] entries (row by row) as a table: each column is as wide as its widest
 * entry and the entries are centred in it, so a "−" or a long number does not shift the others.
 * The entries of a row share their math axis, like the nodes of an expression.
 */
@Composable
private fun MatrixGrid(
    rows: Int,
    cols: Int,
    modifier: Modifier = Modifier,
    columnGap: Dp = 12.dp,
    rowGap: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        fun cell(r: Int, c: Int) = placeables[r * cols + c]
        val colWidths = IntArray(cols) { c -> (0 until rows).maxOf { r -> cell(r, c).width } }
        val aboveAxis = IntArray(rows) { r -> (0 until cols).maxOf { c -> cell(r, c).mathAxis() } }
        val belowAxis = IntArray(rows) { r -> (0 until cols).maxOf { c -> cell(r, c).let { it.height - it.mathAxis() } } }
        val colGapPx = columnGap.roundToPx()
        val rowGapPx = rowGap.roundToPx()
        val width = colWidths.sum() + colGapPx * (cols - 1)
        val height = (0 until rows).sumOf { aboveAxis[it] + belowAxis[it] } + rowGapPx * (rows - 1)
        layout(width, height) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until cols) {
                    val p = cell(r, c)
                    p.place(x + (colWidths[c] - p.width) / 2, y + aboveAxis[r] - p.mathAxis())
                    x += colWidths[c] + colGapPx
                }
                y += aboveAxis[r] + belowAxis[r] + rowGapPx
            }
        }
    }
}

/** Square brackets around the content, as tall as it is. */
private fun Modifier.squareBrackets(color: Color) = this
    .drawBehind {
        val w = 8.dp.toPx()
        val inset = 1.dp.toPx()
        val strokeW = 1.8.dp.toPx()
        val top = inset
        val bottom = size.height - inset
        val right = size.width
        drawLine(color, Offset(w, top), Offset(0f, top), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color, Offset(0f, top), Offset(0f, bottom), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color, Offset(0f, bottom), Offset(w, bottom), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color, Offset(right - w, top), Offset(right, top), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color, Offset(right, top), Offset(right, bottom), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right - w, bottom), strokeWidth = strokeW, cap = StrokeCap.Round)
    }
    .padding(horizontal = 14.dp, vertical = 1.dp)
