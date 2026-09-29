package com.vortessence.calculator.ui.keypad

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.context.AngleUnit
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
    data object Power : KeyAction()
    data object Square : KeyAction()
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
    data object Ans : KeyAction()
    data object ToggleAngleUnit : KeyAction()
    data object MoveLeft : KeyAction()
    data object MoveRight : KeyAction()
    data object OpenUnits : KeyAction()
    data object OpenConstants : KeyAction()
    data object OpenGraph : KeyAction()
    data object OpenSettings : KeyAction()
    data object Integral : KeyAction()
    data object Matrix : KeyAction()
}

@Composable
fun CalculatorKeypad(
    modifier: Modifier = Modifier,
    angleUnit: AngleUnit = AngleUnit.RADIANS,
    isLineEmpty: Boolean = true,
    onKeyAction: (KeyAction) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val spacing = 8.dp
        val buttonWidth = (this.maxWidth - 16.dp - spacing * 4) / 5
        val density = LocalDensity.current
        val colStepPx = with(density) { (buttonWidth + spacing).toPx() }
        val scrollState = rememberScrollState()

        var initialScrollDone by remember { mutableStateOf(false) }
        LaunchedEffect(scrollState.maxValue, colStepPx) {
            if (!initialScrollDone && scrollState.maxValue > 0 && colStepPx > 0f) {
                scrollState.scrollTo((5f * colStepPx).roundToInt())
                initialScrollDone = true
            }
        }

        val flingBehavior = remember(colStepPx) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val current = scrollState.value.toFloat()
                    val p0 = 0f
                    val p1 = (5f * colStepPx).roundToInt().toFloat()
                    val p2 = scrollState.maxValue.toFloat().coerceAtLeast(p1)

                    val mid01 = p1 / 2f
                    val mid12 = p1 + (p2 - p1) / 2f

                    val target = if (abs(initialVelocity) > 300f) {
                        if (initialVelocity > 0f) {
                            if (current < p1 - 20f) p1 else p2
                        } else {
                            if (current > p1 + 20f) p1 else p0
                        }
                    } else {
                        when {
                            current < mid01 -> p0
                            current > mid12 -> p2
                            else -> p1
                        }
                    }

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
                val p1Float = 5f * colStepPx
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
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                            )
                            .clickable {
                                val target = when (page) {
                                    0 -> 0
                                    1 -> (5f * colStepPx).roundToInt()
                                    2 -> scrollState.maxValue
                                    else -> (5f * colStepPx).roundToInt()
                                }
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(target)
                                }
                            }
                    )
                }
            }

            // Continuous horizontal scroll container containing all 13 columns across 4 rows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState, flingBehavior = flingBehavior),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                // Row 1
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..4)
                    KeyButton(text = "=", buttonWidth = buttonWidth, isOp = true) { onKeyAction(KeyAction.Operator("=")) }
                    KeyButton(text = "x²", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Square) }
                    KeyButton(text = "xʸ", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Power) }
                    KeyButton(text = "√x", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Sqrt) }
                    KeyButton(text = "ⁿ√x", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.NthRoot) }

                    // Page 1 (cols 5..9)
                    KeyButton(text = "7", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("7")) }
                    KeyButton(text = "8", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("8")) }
                    KeyButton(text = "9", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("9")) }
                    KeyButton(text = "( )", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Parentheses) }
                    KeyButton(
                        icon = { Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace", tint = AccentRed, modifier = Modifier.size(24.dp)) },
                        buttonWidth = buttonWidth
                    ) { onKeyAction(KeyAction.Backspace) }

                    // Page 2 (cols 10..12)
                    KeyButton(
                        text = if (isLineEmpty) "AC" else "C",
                        buttonWidth = buttonWidth,
                        textColor = AccentRed
                    ) { onKeyAction(KeyAction.Clear) }
                    KeyButton(text = "→", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Convert) }
                    KeyButton(text = "Ans", buttonWidth = buttonWidth, textColor = MaterialTheme.colorScheme.primary) { onKeyAction(KeyAction.Ans) }
                }

                // Row 2
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..4)
                    KeyButton(text = "x", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Variable("x")) }
                    KeyButton(text = "π", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Constant("π")) }
                    KeyButton(text = "sin", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Function("sin")) }
                    KeyButton(text = "cos", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Function("cos")) }
                    KeyButton(text = "tan", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Function("tan")) }

                    // Page 1 (cols 5..9)
                    KeyButton(text = "4", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("4")) }
                    KeyButton(text = "5", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("5")) }
                    KeyButton(text = "6", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("6")) }
                    KeyButton(text = "+", buttonWidth = buttonWidth, isOp = true) { onKeyAction(KeyAction.Operator("+")) }
                    KeyButton(text = "−", buttonWidth = buttonWidth, isOp = true) { onKeyAction(KeyAction.Operator("−")) }

                    // Page 2 (cols 10..12)
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Straighten, contentDescription = "Units", tint = AccentBlue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Units", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    ) { onKeyAction(KeyAction.OpenUnits) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Science, contentDescription = "Constants", tint = AccentPurple, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Const", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    ) { onKeyAction(KeyAction.OpenConstants) }
                    KeyButton(
                        buttonWidth = buttonWidth,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.AutoGraph, contentDescription = "Graph 2D", tint = AccentGreen, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.height(2.dp))
                                Text("Graph", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    ) { onKeyAction(KeyAction.OpenGraph) }
                }

                // Row 3
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..4)
                    KeyButton(text = "y", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Variable("y")) }
                    KeyButton(
                        text = if (angleUnit == AngleUnit.RADIANS) "RAD" else "DEG",
                        buttonWidth = buttonWidth,
                        textColor = MaterialTheme.colorScheme.primary
                    ) { onKeyAction(KeyAction.ToggleAngleUnit) }
                    KeyButton(text = "asin", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Function("asin")) }
                    KeyButton(text = "acos", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Function("acos")) }
                    KeyButton(text = "atan", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Function("atan")) }

                    // Page 1 (cols 5..9)
                    KeyButton(text = "1", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("1")) }
                    KeyButton(text = "2", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("2")) }
                    KeyButton(text = "3", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("3")) }
                    KeyButton(text = "×", buttonWidth = buttonWidth, isOp = true) { onKeyAction(KeyAction.Operator("×")) }
                    KeyButton(text = "÷", buttonWidth = buttonWidth, isOp = true) { onKeyAction(KeyAction.Operator("÷")) }

                    // Page 2 (cols 10..12)
                    KeyButton(text = "∫dx", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Integral) }
                    KeyButton(text = "[M]", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Matrix) }
                    KeyButton(text = "!", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Operator("!")) }
                }

                // Row 4
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    // Page 0 (cols 0..4)
                    KeyButton(text = "z", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Variable("z")) }
                    KeyButton(text = "e", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.Constant("e")) }
                    KeyButton(text = "ln", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Function("ln")) }
                    KeyButton(text = "log", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Function("log")) }
                    KeyButton(text = "logₙ", buttonWidth = buttonWidth) { onKeyAction(KeyAction.LogBase) }

                    // Page 1 (cols 5..9)
                    KeyButton(text = "0", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number("0")) }
                    KeyButton(text = ".", buttonWidth = buttonWidth) { onKeyAction(KeyAction.Number(".")) }
                    KeyButton(text = "EE", buttonWidth = buttonWidth, isAccent = true) { onKeyAction(KeyAction.EE) }
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
                        bgColor = MaterialTheme.colorScheme.primary
                    ) { onKeyAction(KeyAction.Enter) }

                    // Page 2 (cols 10..12)
                    KeyButton(
                        buttonWidth = buttonWidth,
                        colSpan = 3,
                        spacing = spacing,
                        content = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Settings",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
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
    onClick: () -> Unit
) {
    val defaultBg = when {
        bgColor != null -> bgColor
        isOp -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        isAccent -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    }

    val defaultText = when {
        textColor != null -> textColor
        isOp -> MaterialTheme.colorScheme.primary
        isAccent -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val finalWidth = if (colSpan == 1) buttonWidth else buttonWidth * colSpan + spacing * (colSpan - 1)

    Box(
        modifier = Modifier
            .size(width = finalWidth, height = buttonWidth)
            .clip(RoundedCornerShape(16.dp))
            .background(defaultBg)
            .clickable(onClick = onClick),
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
    }
}
