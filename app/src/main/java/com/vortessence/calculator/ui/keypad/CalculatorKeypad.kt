package com.vortessence.calculator.ui.keypad

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.vortessence.calculator.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

sealed class KeyAction {
    data class Number(val digit: String) : KeyAction()
    data class Operator(val opSymbol: String) : KeyAction()
    data class Function(val name: String) : KeyAction()
    data class Variable(val name: String) : KeyAction()
    data class Constant(val symbol: String) : KeyAction()
    /** Logarithm to an arbitrary base (logₙ). */
    data object LogBase : KeyAction()
    data object EE : KeyAction()
    data object Parentheses : KeyAction()
    data object Fraction : KeyAction()
    /** Division as an inline "÷" operator instead of a fraction. */
    data object InlineDivide : KeyAction()
    data object Power : KeyAction()
    data object Square : KeyAction()
    /** Power with exponent −1 (x⁻¹). */
    data object Reciprocal : KeyAction()
    data object Sqrt : KeyAction()
    data object NthRoot : KeyAction()
    data object LeftParen : KeyAction()
    data object RightParen : KeyAction()
    data object Backspace : KeyAction()
    data object ClearLine : KeyAction()
    data object ClearAll : KeyAction()
    /** Clears the line, or everything (line and history) if the line is already empty. */
    data object Clear : KeyAction()
    /** Inserts the unit conversion operator "→". */
    data object Convert : KeyAction()
    /** Commits the current line and its result to the history. */
    data object Enter : KeyAction()
    /** Starts a new line, e.g. for the next equation of a system. */
    data object NewLine : KeyAction()
    data object Ans : KeyAction()
    data object ToggleAngleUnit : KeyAction()
    data object MoveLeft : KeyAction()
    data object MoveRight : KeyAction()
    data object OpenUnits : KeyAction()
    data object OpenConstants : KeyAction()
    data object OpenGraph : KeyAction()
    data object OpenSettings : KeyAction()
    data object Integral : KeyAction()
    /** Opens the matrix size picker. */
    data object Matrix : KeyAction()
    /** Transpose (superscript T) of the matrix before the cursor. */
    data object Transpose : KeyAction()
}

/**
 * A second (or third) function of a key, reachable via long press. With an [icon] the popup
 * shows it instead of the [label] (which then only describes it).
 */
private class KeyAlternate(val label: AnnotatedString, val icon: ImageVector? = null, val onSelect: () -> Unit)

/**
 * A label with a raised and/or lowered part, drawn in Roboto: the font lacks most
 * superscript and subscript characters (ᵀ, ⁻, ₙ, ˣ), which would fall back to another font.
 */
private fun scriptLabel(base: String, sup: String = "", sub: String = ""): AnnotatedString = buildAnnotatedString {
    append(base)
    if (sup.isNotEmpty()) withStyle(SpanStyle(fontSize = 0.65.em, baselineShift = BaselineShift(0.6f))) { append(sup) }
    if (sub.isNotEmpty()) withStyle(SpanStyle(fontSize = 0.65.em, baselineShift = BaselineShift(-0.2f))) { append(sub) }
}

// Column counts of the three pages; five columns are visible at once, so the
// narrower side pages also show the adjacent columns of the main page.
private const val LEFT_COLUMNS = 4
private const val VISIBLE_COLUMNS = 5

/** Where the long-press functions of a key are hinted, in order. */
private val HINT_POSITIONS = listOf(Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd)

@Composable
fun CalculatorKeypad(
    modifier: Modifier = Modifier,
    isLineEmpty: Boolean = true,
    onKeyAction: (KeyAction) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    fun alt(label: CharSequence, action: KeyAction) = KeyAlternate(
        label as? AnnotatedString ?: AnnotatedString(label.toString())
    ) { onKeyAction(action) }
    // Letters that do not look like a constant (e, c, h, ...) or a unit (g, d, ...)
    fun variableAlternates(vararg names: String) = names.map { alt(it, KeyAction.Variable(it)) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val spacing = 8.dp
        val buttonWidth = (this.maxWidth - 16.dp - spacing * (VISIBLE_COLUMNS - 1)) / VISIBLE_COLUMNS
        val density = LocalDensity.current
        val colStepPx = with(density) { (buttonWidth + spacing).toPx() }
        val mainPagePx = (LEFT_COLUMNS * colStepPx).roundToInt()
        val scrollState = rememberScrollState()

        // The page the keypad rests on (0 left, 1 main, 2 right); a swipe moves at most one page from it
        var restingPage by remember { mutableIntStateOf(1) }

        var initialScrollDone by remember { mutableStateOf(false) }
        LaunchedEffect(scrollState.maxValue, colStepPx) {
            if (!initialScrollDone && scrollState.maxValue > 0 && colStepPx > 0f) {
                scrollState.scrollTo(mainPagePx)
                initialScrollDone = true
            }
        }

        val flingBehavior = remember(colStepPx) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val current = scrollState.value.toFloat()
                    val p1 = mainPagePx.toFloat()
                    val pages = listOf(0f, p1, scrollState.maxValue.toFloat().coerceAtLeast(p1))

                    // A fast swipe goes one page in its direction, a slow one to the nearest page;
                    // either way never further than the page next to the one the swipe started on
                    val targetPage = if (abs(initialVelocity) > 300f) {
                        restingPage + if (initialVelocity > 0f) 1 else -1
                    } else {
                        pages.indices.minBy { abs(pages[it] - current) }
                    }.coerceIn(restingPage - 1, restingPage + 1).coerceIn(pages.indices)
                    restingPage = targetPage
                    val target = pages[targetPage]

                    var lastValue = current
                    val anim = Animatable(current)
                    anim.animateTo(
                        targetValue = target,
                        initialVelocity = initialVelocity,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) {
                        val delta = value - lastValue
                        scrollBy(delta)
                        lastValue = value
                    }
                    return 0f
                }
            }
        }

        // Determine current page (0, 1, or 2) for indicator dots. derivedStateOf keeps the
        // frequently changing scroll offset out of composition: only page changes recompose.
        val currentPage by remember(colStepPx) {
            derivedStateOf {
                val p1Float = mainPagePx.toFloat()
                val p2Float = scrollState.maxValue.toFloat().coerceAtLeast(p1Float)
                val mid01 = p1Float / 2f
                val mid12 = p1Float + (p2Float - p1Float) / 2f
                when {
                    scrollState.value < mid01 -> 0
                    scrollState.value > mid12 -> 2
                    else -> 1
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Page indicator dots
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                for (page in 0 until 3) {
                    val isSelected = currentPage == page
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isSelected) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                            )
                            .clickable {
                                val target = when (page) {
                                    0 -> 0
                                    2 -> scrollState.maxValue
                                    else -> mainPagePx
                                }
                                restingPage = page
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(target)
                                }
                            }
                    )
                }
            }

            // Continuous horizontal scroll container containing all 11 columns across 4 rows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState, flingBehavior = flingBehavior),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                // Row 1
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..3)
                    KeyButton(text = "!", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Operator("!")) }
                    KeyButton(text = "π", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Constant("π")) }
                    KeyButton(
                        text = "x²",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt(scriptLabel("x", sup = "n"), KeyAction.Power), alt(scriptLabel("x", sup = "−1"), KeyAction.Reciprocal))
                    ) { onKeyAction(KeyAction.Square) }
                    KeyButton(
                        text = "√x",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("ⁿ√x", KeyAction.NthRoot))
                    ) { onKeyAction(KeyAction.Sqrt) }

                    // Page 1 (cols 4..8)
                    KeyButton(text = "7", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("7")) }
                    KeyButton(text = "8", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("8")) }
                    KeyButton(text = "9", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("9")) }
                    KeyButton(text = "( )", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Parentheses) }
                    KeyButton(
                        icon = { Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace", tint = AccentRed, modifier = Modifier.size(24.dp)) },
                        buttonWidth = buttonWidth
                    ) { onKeyAction(KeyAction.Backspace) }

                    // Page 2 (cols 9..10)
                    KeyButton(
                        text = if (isLineEmpty) "AC" else "C",
                        buttonWidth = buttonWidth,
                        colSpan = 2,
                        spacing = spacing,
                        textColor = AccentRed
                    ) { onKeyAction(KeyAction.Clear) }
                }

                // Row 2
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..3)
                    KeyButton(
                        text = "[M]",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt(scriptLabel("[M]", sup = "T"), KeyAction.Transpose), alt("det", KeyAction.Function("det")))
                    ) { onKeyAction(KeyAction.Matrix) }
                    KeyButton(text = "e", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Constant("e")) }
                    KeyButton(text = "ln", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Function("ln")) }
                    KeyButton(
                        text = "log",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt(scriptLabel("log", sub = "n"), KeyAction.LogBase))
                    ) { onKeyAction(KeyAction.Function("log")) }

                    // Page 1 (cols 4..8)
                    KeyButton(text = "4", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("4")) }
                    KeyButton(text = "5", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("5")) }
                    KeyButton(text = "6", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("6")) }
                    KeyButton(text = "+", buttonWidth = buttonWidth, isOp = true) { onKeyAction(KeyAction.Operator("+")) }
                    KeyButton(text = "−", buttonWidth = buttonWidth, isOp = true) { onKeyAction(KeyAction.Operator("−")) }

                    // Page 2 (cols 9..10)
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Straighten, contentDescription = "Units", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Units", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    ) { onKeyAction(KeyAction.OpenUnits) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                // The arrow glyph is thin, so it is drawn larger within the same slot as the other pictograms
                                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowRightAlt, contentDescription = "Convert to unit", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.requiredSize(30.dp))
                                }
                                Spacer(Modifier.height(2.dp))
                                Text("Unit", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    ) { onKeyAction(KeyAction.Convert) }
                }

                // Row 3
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..3)
                    KeyButton(text = "∫dx", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Integral) }
                    KeyButton(
                        text = "sin",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("asin", KeyAction.Function("asin")))
                    ) { onKeyAction(KeyAction.Function("sin")) }
                    KeyButton(
                        text = "cos",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("acos", KeyAction.Function("acos")))
                    ) { onKeyAction(KeyAction.Function("cos")) }
                    KeyButton(
                        text = "tan",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("atan", KeyAction.Function("atan")))
                    ) { onKeyAction(KeyAction.Function("tan")) }

                    // Page 1 (cols 4..8)
                    KeyButton(text = "1", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("1")) }
                    KeyButton(text = "2", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("2")) }
                    KeyButton(text = "3", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("3")) }
                    KeyButton(text = "×", buttonWidth = buttonWidth, isOp = true) { onKeyAction(KeyAction.Operator("×")) }
                    // Long press for an inline "÷"; not hinted on the key, the popup tells the
                    // two apart by showing the main function as a fraction
                    KeyButton(
                        text = "÷",
                        buttonWidth = buttonWidth,
                        isOp = true,
                        alternates = listOf(alt("÷", KeyAction.InlineDivide)),
                        popupIcon = FractionIcon,
                        showHints = false
                    ) { onKeyAction(KeyAction.Operator("÷")) }

                    // Page 2 (cols 9..10)
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Science, contentDescription = "Constants", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Const", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    ) { onKeyAction(KeyAction.OpenConstants) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.AutoGraph, contentDescription = "Graph 2D", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Graph", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    ) { onKeyAction(KeyAction.OpenGraph) }
                }

                // Row 4
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..3)
                    KeyButton(
                        text = "=",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt(":=", KeyAction.Operator(":=")))
                    ) { onKeyAction(KeyAction.Operator("=")) }
                    KeyButton(
                        text = "x",
                        buttonWidth = buttonWidth,
                        alternates = variableAlternates("a", "f", "j")
                    ) { onKeyAction(KeyAction.Variable("x")) }
                    KeyButton(
                        text = "y",
                        buttonWidth = buttonWidth,
                        alternates = variableAlternates("k", "n", "p")
                    ) { onKeyAction(KeyAction.Variable("y")) }
                    KeyButton(
                        text = "z",
                        buttonWidth = buttonWidth,
                        alternates = variableAlternates("q", "r", "u")
                    ) { onKeyAction(KeyAction.Variable("z")) }

                    // Page 1 (cols 4..8)
                    KeyButton(text = "0", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("0")) }
                    KeyButton(text = ".", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number(".")) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        content = {
                            Text(
                                text = scriptLabel("×10", sup = "x"),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    ) { onKeyAction(KeyAction.EE) }
                    KeyButton(
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardReturn,
                                contentDescription = "Enter",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        },
                        buttonWidth = buttonWidth,
                        colSpan = 2,
                        spacing = spacing,
                        bgColor = MaterialTheme.colorScheme.primary,
                        popupIcon = Icons.AutoMirrored.Filled.KeyboardReturn,
                        alternates = listOf(alt("new line", KeyAction.NewLine))
                    ) { onKeyAction(KeyAction.Enter) }

                    // Page 2 (cols 9..10)
                    KeyButton(text = "Ans", buttonWidth = buttonWidth, textColor = MaterialTheme.colorScheme.onPrimaryContainer) { onKeyAction(KeyAction.Ans) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    ) { onKeyAction(KeyAction.OpenSettings) }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    text: String? = null,
    icon: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
    buttonWidth: Dp,
    colSpan: Int = 1,
    spacing: Dp = 8.dp,
    isOp: Boolean = false,
    isAccent: Boolean = false,
    bgColor: Color? = null,
    textColor: Color? = null,
    alternates: List<KeyAlternate> = emptyList(),
    /** Stands for the main function in the long-press popup (instead of [text]). */
    popupIcon: ImageVector? = null,
    /** Whether the long-press functions are hinted on the key. */
    showHints: Boolean = true,
    onClick: () -> Unit
) {
    val defaultBg = when {
        bgColor != null -> bgColor
        isOp -> MaterialTheme.colorScheme.primaryContainer
        isAccent -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val defaultText = when {
        textColor != null -> textColor
        isOp -> MaterialTheme.colorScheme.onPrimaryContainer
        isAccent -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val finalWidth = if (colSpan == 1) buttonWidth else buttonWidth * colSpan + spacing * (colSpan - 1)

    // Like a keyboard, every key gives feedback as soon as it is touched; the system's
    // touch feedback setting decides whether it vibrates
    val view = LocalView.current
    val touchFeedback = Modifier.pointerInput(view) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    // Index of the highlighted alternate while the long-press popup is shown
    var popupSelection by remember { mutableStateOf<Int?>(null) }
    // Near the right screen edge the popup opens to the left (main function above the key,
    // alternates to its left), so the finger can still reach every entry
    var popupOpensLeft by remember { mutableStateOf(false) }
    var keyLeftInWindow by remember { mutableFloatStateOf(0f) }
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val gestureModifier = if (alternates.isEmpty()) {
        Modifier.clickable(onClick = onClick)
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        val cellPx = with(LocalDensity.current) { buttonWidth.toPx() }
        val currentOnClick by rememberUpdatedState(onClick)
        // The popup starts with the main function, preselected, so an accidental long press
        // does what a tap would have done
        val popupEntries by rememberUpdatedState(listOf(KeyAlternate(AnnotatedString(text.orEmpty()), popupIcon, onClick)) + alternates)
        Modifier
            .indication(interactionSource, LocalIndication.current)
            .pointerInput(cellPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)

                    // Tap if released before the long-press timeout; a scroll cancels
                    var cancelled = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation().also { if (it == null) cancelled = true }
                    }
                    if (up != null || cancelled) {
                        if (up != null) {
                            up.consume()
                            interactionSource.tryEmit(PressInteraction.Release(press))
                            currentOnClick()
                        } else {
                            interactionSource.tryEmit(PressInteraction.Cancel(press))
                        }
                        return@awaitEachGesture
                    }

                    // Long press: the finger slides over the alternates, lifting it picks one
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val count = popupEntries.size
                    popupOpensLeft = keyLeftInWindow + count * cellPx > windowWidth
                    popupSelection = 0
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                popupSelection?.let { popupEntries.getOrNull(it)?.onSelect?.invoke() }
                                break
                            }
                            val offset = if (popupOpensLeft) size.width - change.position.x else change.position.x
                            val selection = (offset / cellPx).toInt().coerceIn(0, count - 1)
                            if (selection != popupSelection) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                popupSelection = selection
                            }
                        }
                    } finally {
                        popupSelection = null
                        interactionSource.tryEmit(PressInteraction.Release(press))
                    }
                }
            }
    }

    Box(
        modifier = Modifier
            .size(width = finalWidth, height = buttonWidth)
            .onGloballyPositioned { keyLeftInWindow = it.positionInWindow().x }
            .clip(RoundedCornerShape(16.dp))
            .background(defaultBg)
            .then(touchFeedback)
            .then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        if (content != null) {
            content()
        } else if (text != null) {
            Text(
                text = text,
                fontSize = if (text.length > 3) 14.sp else if (text.length > 2) 16.sp else 24.sp,
                fontWeight = if (isOp || bgColor != null) FontWeight.Bold else FontWeight.Medium,
                color = defaultText
            )
        } else if (icon != null) {
            icon()
        }

        // Second function bottom left, third bottom centre, a fourth would go bottom right.
        // The accent color would vanish on the (accent colored) Enter key.
        val hintColor = if (bgColor == MaterialTheme.colorScheme.primary) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onPrimaryContainer
        if (showHints) alternates.take(HINT_POSITIONS.size).forEachIndexed { index, alternate ->
            Text(
                text = alternate.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = hintColor,
                modifier = Modifier
                    .align(HINT_POSITIONS[index])
                    .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
            )
        }

        popupSelection?.let { selected ->
            AlternatesPopup(
                listOf(KeyAlternate(AnnotatedString(text.orEmpty()), popupIcon, onClick)) + alternates,
                selected,
                cellWidth = buttonWidth,
                opensLeft = popupOpensLeft
            )
        }
    }
}

/** A fraction template (box over box), for the popup of the division key. */
private val FractionIcon: ImageVector = ImageVector.Builder(
    name = "Fraction",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(8f, 2f); lineTo(16f, 2f); lineTo(16f, 9f); lineTo(8f, 9f); close()
        moveTo(8f, 15f); lineTo(16f, 15f); lineTo(16f, 22f); lineTo(8f, 22f); close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(4f, 11.1f); lineTo(20f, 11.1f); lineTo(20f, 12.9f); lineTo(4f, 12.9f); close()
    }
}.build()

/** The functions of a long-pressed key (main one first), shown above it with one cell each. */
@Composable
private fun AlternatesPopup(
    alternates: List<KeyAlternate>,
    selected: Int,
    cellWidth: Dp,
    opensLeft: Boolean
) {
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val positionProvider = remember(gapPx, opensLeft) { AboveKeyPositionProvider(gapPx, opensLeft) }
    Popup(popupPositionProvider = positionProvider) {
        Row(
            modifier = Modifier
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceBright)
        ) {
            // Opening to the left, the main function is the rightmost cell (above the key)
            val ordered = alternates.withIndex().let { if (opensLeft) it.reversed() else it.toList() }
            ordered.forEach { (index, alternate) ->
                val isSelected = index == selected
                Box(
                    modifier = Modifier
                        .size(width = cellWidth, height = cellWidth * 0.8f)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    val color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    if (alternate.icon != null) {
                        Icon(alternate.icon, contentDescription = alternate.label.text, tint = color, modifier = Modifier.size(26.dp))
                    } else {
                        Text(
                            text = alternate.label,
                            fontSize = when {
                                alternate.label.length > 5 -> 13.sp
                                alternate.label.length > 3 -> 16.sp
                                else -> 22.sp
                            },
                            fontWeight = FontWeight.Medium,
                            color = color
                        )
                    }
                }
            }
        }
    }
}

/**
 * Places the popup directly above the key, left-aligned with it (right-aligned if it
 * [opensLeft]), so cells line up with the finger.
 */
private class AboveKeyPositionProvider(private val gapPx: Int, private val opensLeft: Boolean) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val left = if (opensLeft) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val x = left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
