package com.vortessence.calculator.ui.keypad

import com.vortessence.calculator.ui.math.DecimalMark
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Keyboard
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.res.stringResource
import com.vortessence.calculator.R

sealed class KeyAction {
    data class Number(val digit: String) : KeyAction()
    data class Operator(val opSymbol: String) : KeyAction()
    data class Function(val name: String) : KeyAction()
    data class Variable(val name: String) : KeyAction()
    data class Constant(val symbol: String) : KeyAction()
    /** Logarithm to an arbitrary base (logₙ). */
    data object LogBase : KeyAction()
    data object EE : KeyAction()
    /** A plus or minus as the superscript of the number before it: 0⁺ and 0⁻, the side of a limit. */
    data class SuperscriptSign(val plus: Boolean) : KeyAction()
    data object Parentheses : KeyAction()
    data object Fraction : KeyAction()
    /** A mixed number such as 3½ (whole part and fraction). */
    data object MixedFraction : KeyAction()
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
    /** Opens the formula picker (statistics, combinatorics, rounding, ...). */
    data object OpenFormulas : KeyAction()
    /** The comma between the values of a function with several arguments, e.g. mean(2, 4, 9). */
    data object Separator : KeyAction()
    data object OpenGraph : KeyAction()
    data object OpenSettings : KeyAction()
    data object Integral : KeyAction()
    /** Indefinite integral ∫ … dx (symbolic, without bounds). */
    data object Antiderivative : KeyAction()
    /** Symbolic derivative d/dx. */
    data object Derivative : KeyAction()
    /** Value of the derivative at a point, d/dx(…)|ₓ₌ₐ. */
    data object DerivativeAt : KeyAction()
    /** Opens the matrix size picker. */
    data object Matrix : KeyAction()
    /** Transpose (superscript T) of the matrix before the cursor. */
    data object Transpose : KeyAction()
    /** Σ with the index n running from a lower to an upper bound. */
    data object Sum : KeyAction()
    /** ∏, like [Sum]. */
    data object Product : KeyAction()
    /** lim x→a. */
    data object Limit : KeyAction()
    /** Opens the system keyboard to type any letter as a variable (also capitals, for matrices). */
    data object FreeVariable : KeyAction()
    /** The imaginary unit i (turns complex numbers on if they are off). */
    data object ImaginaryUnit : KeyAction()
    /** A complex number in angle notation r∠θ (also turns complex numbers on). */
    data object Polar : KeyAction()
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
                    KeyButton(
                        text = "!",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("%", KeyAction.Operator("%")), alt("mod", KeyAction.Operator("mod")), alt("Γ", KeyAction.Function("gamma")))
                    ) { onKeyAction(KeyAction.Operator("!")) }
                    KeyButton(
                        text = "□²",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt(scriptLabel("□", sup = "n"), KeyAction.Power), alt(scriptLabel("□", sup = "−1"), KeyAction.Reciprocal))
                    ) { onKeyAction(KeyAction.Square) }
                    KeyButton(
                        text = "√□",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("ⁿ√□", KeyAction.NthRoot))
                    ) { onKeyAction(KeyAction.Sqrt) }
                    KeyButton(
                        text = "log",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("ln", KeyAction.Function("ln")), alt(scriptLabel("log", sub = "n"), KeyAction.LogBase))
                    ) { onKeyAction(KeyAction.Function("log")) }

                    // Page 1 (cols 4..8)
                    KeyButton(text = "7", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("7")) }
                    KeyButton(text = "8", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("8")) }
                    KeyButton(text = "9", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("9")) }
                    KeyButton(
                        text = "( )",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("|□|", KeyAction.Function("abs")))
                    ) { onKeyAction(KeyAction.Parentheses) }
                    KeyButton(
                        icon = { Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.keypad_backspace), tint = AccentRed, modifier = Modifier.size(24.dp)) },
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
                        alternates = listOf(
                            alt(scriptLabel("[M]", sup = "T"), KeyAction.Transpose),
                            alt("det", KeyAction.Function("det")),
                            alt("rref", KeyAction.Function("rref")),
                            alt("eig", KeyAction.Function("eig"))
                        ),
                        // The most common ones; the dots tell that the popup has more (rref, eig)
                        hints = listOf(
                            alt(scriptLabel("[M]", sup = "T"), KeyAction.Transpose),
                            alt("det", KeyAction.Function("det")),
                            KeyAlternate(AnnotatedString("…")) { }
                        )
                    ) { onKeyAction(KeyAction.Matrix) }
                    KeyButton(
                        text = "sin",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(
                            alt("asin", KeyAction.Function("asin")),
                            alt("csc", KeyAction.Function("csc")),
                            alt("acsc", KeyAction.Function("acsc"))
                        ),
                        upperAlternates = listOf(
                            alt("sinh", KeyAction.Function("sinh")),
                            alt("asinh", KeyAction.Function("asinh")),
                            alt("csch", KeyAction.Function("csch")),
                            alt("acsch", KeyAction.Function("acsch"))
                        ),
                        // The inverse; the dots tell that the popup has more
                        hints = listOf(alt("asin", KeyAction.Function("asin")), KeyAlternate(AnnotatedString("…")) { })
                    ) { onKeyAction(KeyAction.Function("sin")) }
                    KeyButton(
                        text = "cos",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(
                            alt("acos", KeyAction.Function("acos")),
                            alt("sec", KeyAction.Function("sec")),
                            alt("asec", KeyAction.Function("asec"))
                        ),
                        upperAlternates = listOf(
                            alt("cosh", KeyAction.Function("cosh")),
                            alt("acosh", KeyAction.Function("acosh")),
                            alt("sech", KeyAction.Function("sech")),
                            alt("asech", KeyAction.Function("asech"))
                        ),
                        // The inverse; the dots tell that the popup has more
                        hints = listOf(alt("acos", KeyAction.Function("acos")), KeyAlternate(AnnotatedString("…")) { })
                    ) { onKeyAction(KeyAction.Function("cos")) }
                    KeyButton(
                        text = "tan",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(
                            alt("atan", KeyAction.Function("atan")),
                            alt("cot", KeyAction.Function("cot")),
                            alt("acot", KeyAction.Function("acot"))
                        ),
                        upperAlternates = listOf(
                            alt("tanh", KeyAction.Function("tanh")),
                            alt("atanh", KeyAction.Function("atanh")),
                            alt("coth", KeyAction.Function("coth")),
                            alt("acoth", KeyAction.Function("acoth"))
                        ),
                        // The inverse; the dots tell that the popup has more
                        hints = listOf(alt("atan", KeyAction.Function("atan")), KeyAlternate(AnnotatedString("…")) { })
                    ) { onKeyAction(KeyAction.Function("tan")) }

                    // Page 1 (cols 4..8)
                    KeyButton(text = "4", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("4")) }
                    KeyButton(text = "5", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("5")) }
                    KeyButton(text = "6", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("6")) }
                    // Long press: a plus or minus as a superscript, for a limit from one side (x → 0⁺)
                    KeyButton(
                        text = "+", buttonWidth = buttonWidth, isOp = true,
                        alternates = listOf(KeyAlternate(scriptLabel("a", sup = "+")) { onKeyAction(KeyAction.SuperscriptSign(plus = true)) })
                    ) { onKeyAction(KeyAction.Operator("+")) }
                    KeyButton(
                        text = "−", buttonWidth = buttonWidth, isOp = true,
                        alternates = listOf(KeyAlternate(scriptLabel("a", sup = "−")) { onKeyAction(KeyAction.SuperscriptSign(plus = false)) })
                    ) { onKeyAction(KeyAction.Operator("−")) }

                    // Page 2 (cols 9..10)
                    // Long press: convert to a unit ("5 km → mi"); the popup shows the arrow
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = { IconLabel(Icons.Default.Straighten, stringResource(R.string.keypad_units), stringResource(R.string.keypad_units)) },
                        popupIcon = Icons.Default.Straighten,
                        alternates = listOf(
                            KeyAlternate(AnnotatedString("→"), Icons.AutoMirrored.Filled.ArrowRightAlt) { onKeyAction(KeyAction.Convert) }
                        )
                    ) { onKeyAction(KeyAction.OpenUnits) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = { IconLabel(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.keypad_formulas), stringResource(R.string.keypad_formulas)) }
                    ) { onKeyAction(KeyAction.OpenFormulas) }
                }

                // Row 3
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..3)
                    // Long press: definite integral, derivative, derivative at a point. In the
                    // popup the definite integral shows its bounds to tell it from the indefinite one.
                    val derivative = alt("d/dx", KeyAction.Derivative)
                    KeyButton(
                        text = "∫dx",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(
                            alt(scriptLabel("∫", sup = "b", sub = "a") + AnnotatedString("dx"), KeyAction.Integral),
                            derivative,
                            alt(scriptLabel("d/dx", sub = "x=a"), KeyAction.DerivativeAt)
                        ),
                        // More labels do not fit on the key
                        hints = listOf(derivative)
                    ) { onKeyAction(KeyAction.Antiderivative) }
                    KeyButton(
                        text = "Σ",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("∏", KeyAction.Product), alt("lim", KeyAction.Limit))
                    ) { onKeyAction(KeyAction.Sum) }
                    KeyButton(
                        text = "i",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        alternates = listOf(alt("r∠θ", KeyAction.Polar))
                    ) { onKeyAction(KeyAction.ImaginaryUnit) }
                    KeyButton(
                        text = "π",
                        buttonWidth = buttonWidth,
                        alternates = listOf(KeyAlternate(AnnotatedString(stringResource(R.string.keypad_infinity)), InfinityIcon) { onKeyAction(KeyAction.Constant("∞")) })
                    ) { onKeyAction(KeyAction.Constant("π")) }

                    // Page 1 (cols 4..8)
                    KeyButton(text = "1", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("1")) }
                    KeyButton(text = "2", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("2")) }
                    KeyButton(text = "3", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("3")) }
                    KeyButton(text = "×", buttonWidth = buttonWidth, isOp = true) { onKeyAction(KeyAction.Operator("×")) }
                    // Long press for a mixed number and an inline "÷"; not hinted on the key, the
                    // popup tells them apart by showing the main function as a fraction
                    KeyButton(
                        text = "÷",
                        buttonWidth = buttonWidth,
                        isOp = true,
                        alternates = listOf(
                            KeyAlternate(AnnotatedString(stringResource(R.string.keypad_mixed_number)), MixedFractionIcon) { onKeyAction(KeyAction.MixedFraction) },
                            alt("÷", KeyAction.InlineDivide)
                        ),
                        popupIcon = FractionIcon,
                        hints = emptyList()
                    ) { onKeyAction(KeyAction.Operator("÷")) }

                    // Page 2 (cols 9..10)
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = { IconLabel(Icons.Default.Science, stringResource(R.string.keypad_constants_short), stringResource(R.string.keypad_constants)) }
                    ) { onKeyAction(KeyAction.OpenConstants) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = { IconLabel(Icons.Default.AutoGraph, stringResource(R.string.keypad_graph), stringResource(R.string.keypad_graph_2d)) }
                    ) { onKeyAction(KeyAction.OpenGraph) }
                }

                // Row 4
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..3)
                    KeyButton(
                        text = "=",
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        // Long press: a definition, and the signs of an inequality
                        alternates = listOf(
                            alt(":=", KeyAction.Operator(":=")),
                            alt("<", KeyAction.Operator("<")),
                            alt(">", KeyAction.Operator(">")),
                            alt("≤", KeyAction.Operator("≤")),
                            alt("≥", KeyAction.Operator("≥"))
                        )
                    ) { onKeyAction(KeyAction.Operator("=")) }
                    KeyButton(
                        text = "x",
                        buttonWidth = buttonWidth,
                        alternates = variableAlternates("y", "z")
                    ) { onKeyAction(KeyAction.Variable("x")) }
                    // Any other letter (capitals too, e.g. for matrices) comes from the system keyboard
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = { IconLabel(Icons.Default.Keyboard, "abc", stringResource(R.string.keypad_variable)) }
                    ) { onKeyAction(KeyAction.FreeVariable) }
                    KeyButton(text = "e", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Constant("e")) }

                    // Page 1 (cols 4..8)
                    KeyButton(text = "0", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("0")) }
                    // The key types a point either way: with a decimal comma it is only shown as a comma
                    KeyButton(
                        text = DecimalMark.display("."),
                        buttonWidth = buttonWidth,
                        alternates = listOf(alt(DecimalMark.argumentSeparator.trim(), KeyAction.Separator))
                    ) { onKeyAction(KeyAction.Number(".")) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        isAccent = true,
                        content = {
                            Text(
                                text = scriptLabel("×10", sup = "□"),
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
                                contentDescription = stringResource(R.string.keypad_enter),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        },
                        buttonWidth = buttonWidth,
                        colSpan = 2,
                        spacing = spacing,
                        bgColor = MaterialTheme.colorScheme.primary,
                        popupIcon = Icons.AutoMirrored.Filled.KeyboardReturn,
                        alternates = listOf(alt(stringResource(R.string.keypad_new_line), KeyAction.NewLine))
                    ) { onKeyAction(KeyAction.Enter) }

                    // Page 2 (cols 9..10)
                    KeyButton(text = "Ans", buttonWidth = buttonWidth, textColor = MaterialTheme.colorScheme.onPrimaryContainer) { onKeyAction(KeyAction.Ans) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = { IconLabel(Icons.Default.Settings, stringResource(R.string.keypad_settings), stringResource(R.string.keypad_settings)) }
                    ) { onKeyAction(KeyAction.OpenSettings) }
                }
            }
        }
    }
}

/**
 * A key with an icon over a short label. The label has the line height of its size: the theme's
 * (24sp) would leave a gap of several dp between icon and text.
 */
@Composable
private fun IconLabel(icon: ImageVector, label: String, contentDescription: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
    /**
     * A second row of the long-press popup, above the first (the main function and [alternates]): the finger
     * slides up to it. Cell n of this row is above cell n of the first.
     */
    upperAlternates: List<KeyAlternate> = emptyList(),
    /** Stands for the main function in the long-press popup (instead of [text]). */
    popupIcon: ImageVector? = null,
    /** Stands for the main function in the long-press popup, instead of [text]. */
    popupLabel: AnnotatedString? = null,
    /** The long-press functions hinted on the key (the popup shows all of them). */
    hints: List<KeyAlternate> = alternates,
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
    // Narrower than the key if the popup has more entries than fit next to the key
    var popupCellPx by remember { mutableFloatStateOf(0f) }
    var keyLeftInWindow by remember { mutableFloatStateOf(0f) }
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val gestureModifier = if (alternates.isEmpty() && upperAlternates.isEmpty()) {
        Modifier.clickable(onClick = onClick)
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        val cellPx = with(LocalDensity.current) { buttonWidth.toPx() }
        val currentOnClick by rememberUpdatedState(onClick)
        // The popup starts with the main function, preselected, so an accidental long press
        // does what a tap would have done
        val lowerEntries by rememberUpdatedState(listOf(KeyAlternate(popupLabel ?: AnnotatedString(text.orEmpty()), popupIcon, onClick)) + alternates)
        val upperEntries by rememberUpdatedState(upperAlternates)
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
                    val lowerCount = lowerEntries.size
                    val upperCount = upperEntries.size
                    val count = maxOf(lowerCount, upperCount)
                    // The upper row starts where the lower one ends: a cell and the gap to the key above the key's top
                    val upperRowTop = -(cellPx * CELL_HEIGHT_FACTOR + POPUP_GAP.toPx())
                    // Opens to the side with more room (right by default); if the entries still do
                    // not fit there, their cells are narrowed so that they all stay reachable
                    val roomRight = windowWidth - keyLeftInWindow
                    val roomLeft = keyLeftInWindow + size.width
                    popupOpensLeft = count * cellPx > roomRight && roomLeft > roomRight
                    popupCellPx = minOf(cellPx, (if (popupOpensLeft) roomLeft else roomRight) / count)
                    popupSelection = 0
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                popupSelection?.let { (lowerEntries + upperEntries).getOrNull(it)?.onSelect?.invoke() }
                                break
                            }
                            val offset = if (popupOpensLeft) size.width - change.position.x else change.position.x
                            val inUpperRow = upperCount > 0 && change.position.y < upperRowTop
                            val column = (offset / popupCellPx).toInt().coerceIn(0, (if (inUpperRow) upperCount else lowerCount) - 1)
                            val selection = if (inUpperRow) lowerCount + column else column
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
        hints.take(HINT_POSITIONS.size).forEachIndexed { index, alternate ->
            val hintModifier = Modifier
                .align(HINT_POSITIONS[index])
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
            if (alternate.icon != null) {
                // Lifted so its centre is level with the middle of the lowercase letters of the
                // text hints beside it (a text box has its descender space below the letters)
                Icon(
                    alternate.icon,
                    contentDescription = alternate.label.text,
                    tint = hintColor,
                    modifier = hintModifier.padding(bottom = 8.dp).size(width = 12.dp, height = 7.dp)
                )
            } else {
                Text(
                    text = alternate.label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = hintColor,
                    modifier = hintModifier
                )
            }
        }

        popupSelection?.let { selected ->
            AlternatesPopup(
                listOf(KeyAlternate(popupLabel ?: AnnotatedString(text.orEmpty()), popupIcon, onClick)) + alternates,
                upperAlternates,
                selected,
                cellWidth = with(LocalDensity.current) { popupCellPx.toDp() },
                cellHeight = buttonWidth * CELL_HEIGHT_FACTOR,
                opensLeft = popupOpensLeft
            )
        }
    }
}

/**
 * The infinity symbol, drawn as a path: font glyphs of ∞ differ in size from device to device
 * (it can be tiny in one system font and huge in a fallback font), a path does not.
 */
private val InfinityIcon: ImageVector = ImageVector.Builder(
    name = "Infinity",
    defaultWidth = 24.dp,
    defaultHeight = 14.dp,
    viewportWidth = 24f,
    viewportHeight = 14f
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        moveTo(12f, 7f)
        curveTo(13.8f, 4.6f, 15.4f, 3f, 17.5f, 3f)
        curveTo(19.8f, 3f, 21.5f, 4.8f, 21.5f, 7f)
        curveTo(21.5f, 9.2f, 19.8f, 11f, 17.5f, 11f)
        curveTo(15.4f, 11f, 13.8f, 9.4f, 12f, 7f)
        curveTo(10.2f, 4.6f, 8.6f, 3f, 6.5f, 3f)
        curveTo(4.2f, 3f, 2.5f, 4.8f, 2.5f, 7f)
        curveTo(2.5f, 9.2f, 4.2f, 11f, 6.5f, 11f)
        curveTo(8.6f, 11f, 10.2f, 9.4f, 12f, 7f)
        close()
    }
}.build()

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

/** A mixed number template: a box for the whole part next to a small fraction. */
private val MixedFractionIcon: ImageVector = ImageVector.Builder(
    name = "MixedFraction",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
        moveTo(1.5f, 7f); lineTo(9.5f, 7f); lineTo(9.5f, 17f); lineTo(1.5f, 17f); close()
        moveTo(15f, 3f); lineTo(21f, 3f); lineTo(21f, 9f); lineTo(15f, 9f); close()
        moveTo(15f, 15f); lineTo(21f, 15f); lineTo(21f, 21f); lineTo(15f, 21f); close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(12.5f, 11.1f); lineTo(23.5f, 11.1f); lineTo(23.5f, 12.9f); lineTo(12.5f, 12.9f); close()
    }
}.build()

/** A popup cell is this much of a key's width high. */
private const val CELL_HEIGHT_FACTOR = 0.8f

/** The space between a key and its popup. */
private val POPUP_GAP = 6.dp

/**
 * The functions of a long-pressed key (main one first), shown above it with one cell each; [upper] is a second
 * row above the first. [selected] counts the cells of [lower] and then those of [upper].
 */
@Composable
private fun AlternatesPopup(
    lower: List<KeyAlternate>,
    upper: List<KeyAlternate>,
    selected: Int,
    cellWidth: Dp,
    cellHeight: Dp,
    opensLeft: Boolean
) {
    val gapPx = with(LocalDensity.current) { POPUP_GAP.roundToPx() }
    val positionProvider = remember(gapPx, opensLeft) { AboveKeyPositionProvider(gapPx, opensLeft) }
    Popup(popupPositionProvider = positionProvider) {
        Column(
            modifier = Modifier
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceBright),
            // Opening to the left, the rows end above the key
            horizontalAlignment = if (opensLeft) Alignment.End else Alignment.Start
        ) {
            // Two rows of function names read evenly with one size; a single row sizes each label by itself
            val sharedSize = if (upper.isNotEmpty()) (lower + upper).maxOf { it.label.length } else null
            if (upper.isNotEmpty()) AlternatesRow(upper, lower.size, selected, cellWidth, cellHeight, opensLeft, sharedSize)
            AlternatesRow(lower, 0, selected, cellWidth, cellHeight, opensLeft, sharedSize)
        }
    }
}

/** One row of the popup; [firstIndex] is the position of its first cell in the popup's [selected] count. */
@Composable
private fun AlternatesRow(
    alternates: List<KeyAlternate>,
    firstIndex: Int,
    selected: Int,
    cellWidth: Dp,
    cellHeight: Dp,
    opensLeft: Boolean,
    /** The length of the longest label of the popup, if all labels are to be the same size. */
    sharedLength: Int? = null
) {
    Row {
        // Opening to the left, the main function is the rightmost cell (above the key)
        val ordered = alternates.withIndex().let { if (opensLeft) it.reversed() else it.toList() }
        ordered.forEach { (index, alternate) ->
            val isSelected = firstIndex + index == selected
            Box(
                modifier = Modifier
                    .size(width = cellWidth, height = cellHeight)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                val color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                if (alternate.icon != null) {
                    Icon(alternate.icon, contentDescription = alternate.label.text, tint = color, modifier = Modifier.size(26.dp))
                } else {
                    // The cells have a fixed size: a larger system font only grows the labels a little, and they never wrap
                    val fontScale = LocalDensity.current.fontScale
                    val compensation = minOf(fontScale, 1.15f) / fontScale
                    val size = when (sharedLength ?: alternate.label.length) {
                        in 6..Int.MAX_VALUE -> 13f
                        4, 5 -> 16f
                        else -> 22f
                    }
                    Text(
                        text = alternate.label,
                        fontSize = (size * compensation).sp,
                        fontWeight = FontWeight.Medium,
                        color = color,
                        maxLines = 1,
                        softWrap = false
                    )
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
