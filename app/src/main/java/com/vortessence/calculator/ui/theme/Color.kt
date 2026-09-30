package com.vortessence.calculator.ui.theme

import androidx.compose.ui.graphics.Color

// Minimalist palette
val OrangePrimary = Color(0xFFFF9500)
val OrangeLight = Color(0xFFFFB340)
val OrangeDark = Color(0xFFCC7700)

val SlateLightBg = Color(0xFFF6F7F9)
val SlateLightSurface = Color(0xFFFFFFFF)
val SlateLightKey = Color(0xFFE8EBF0)
val SlateLightKeyAction = Color(0xFFD6DBE4)
val SlateLightText = Color(0xFF1C1C1E)
val SlateLightTextSecondary = Color(0xFF8E8E93)

val SlateDarkBg = Color(0xFF121316)
val SlateDarkSurface = Color(0xFF1C1E22)
val SlateDarkKey = Color(0xFF282B30)
val SlateDarkKeyAction = Color(0xFF383C44)
val SlateDarkText = Color(0xFFF2F2F7)
val SlateDarkTextSecondary = Color(0xFF98989F)

val AccentBlue = Color(0xFF007AFF)
val AccentGreen = Color(0xFF34C759)
val AccentRed = Color(0xFFFF3B30)
val AccentPurple = Color(0xFFAF52DE)

/** Keypad palette; opaque colours so keys look the same on any background. */
data class KeypadColors(
    val key: Color,
    val operatorKey: Color,
    val operatorText: Color,
    val functionKey: Color,
    val functionText: Color,
    val enterKey: Color,
    val enterContent: Color,
    /** Ans label, long-press labels and the active page dot. */
    val accentText: Color,
    val popup: Color,
    val popupHighlight: Color,
    val popupHighlightText: Color
)

val DarkKeypadColors = KeypadColors(
    key = Color(0xFF29292F),
    operatorKey = Color(0xFF774608),
    operatorText = Color(0xFFF1A340),
    functionKey = Color(0xFF342D52),
    functionText = Color(0xFF998EC3),
    enterKey = Color(0xFFF1A340),
    enterContent = Color(0xFF1C1C1E),
    accentText = Color(0xFFF1A340),
    popup = Color(0xFF3A3A42),
    popupHighlight = Color(0xFFF1A340),
    popupHighlightText = Color(0xFF1C1C1E)
)

val LightKeypadColors = KeypadColors(
    key = Color(0xFFE9E9EE),
    operatorKey = Color(0xFFFBE3C2),
    operatorText = Color(0xFFA45F00),
    functionKey = Color(0xFFE3DFF3),
    functionText = Color(0xFF4F4585),
    enterKey = Color(0xFFF1A340),
    enterContent = Color(0xFF1C1C1E),
    accentText = Color(0xFFA45F00),
    popup = Color(0xFFFFFFFF),
    popupHighlight = Color(0xFFF1A340),
    popupHighlightText = Color(0xFF1C1C1E)
)
