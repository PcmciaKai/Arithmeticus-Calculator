package com.vortessence.calculator

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vortessence.calculator.ui.screen.CalculatorScreen
import com.vortessence.calculator.ui.theme.CalculatorTheme
import com.vortessence.calculator.ui.theme.isDark
import com.vortessence.calculator.viewmodel.CalculatorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: CalculatorViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val darkTheme = uiState.themeMode.isDark()

            // Status and navigation bar icons follow the app's theme, not only the system's
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                )
                onDispose {}
            }

            CalculatorTheme(darkTheme = darkTheme) {
                CalculatorScreen(viewModel)
            }
        }
    }
}
