package com.vortessence.calculator.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = OrangePrimary,
    onPrimary = SlateLightText,
    primaryContainer = OrangeContainerDark,
    onPrimaryContainer = OrangePrimary,
    secondary = LavenderDark,
    onSecondary = SlateLightText,
    secondaryContainer = LavenderContainerDark,
    onSecondaryContainer = LavenderDark,
    background = SlateDarkBg,
    onBackground = SlateDarkText,
    surface = SlateDarkSurface,
    onSurface = SlateDarkText,
    surfaceVariant = SlateDarkKey,
    onSurfaceVariant = SlateDarkTextSecondary,
    surfaceBright = SlateDarkKeyAction
)

private val LightColorScheme = lightColorScheme(
    primary = OrangePrimary,
    onPrimary = SlateLightText,
    primaryContainer = OrangeContainerLight,
    onPrimaryContainer = OrangeTextLight,
    secondary = LavenderLight,
    onSecondary = SlateLightSurface,
    secondaryContainer = LavenderContainerLight,
    onSecondaryContainer = LavenderLight,
    background = SlateLightBg,
    onBackground = SlateLightText,
    surface = SlateLightSurface,
    onSurface = SlateLightText,
    surfaceVariant = SlateLightKey,
    onSurfaceVariant = SlateLightTextSecondary,
    surfaceBright = SlateLightSurface
)

/** Light/dark appearance chosen in the settings. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun CalculatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
