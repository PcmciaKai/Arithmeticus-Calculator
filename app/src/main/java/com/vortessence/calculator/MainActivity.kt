package com.vortessence.calculator

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortessence.calculator.i18n.AppLanguage
import com.vortessence.calculator.ui.screen.CalculatorScreen
import com.vortessence.calculator.ui.theme.CalculatorTheme
import com.vortessence.calculator.ui.theme.isDark
import com.vortessence.calculator.viewmodel.CalculatorViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: CalculatorViewModel by viewModels()

    // Before Android 13 the language chosen in the settings is applied here
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The activity is made again when the language changes, the view model is not: its texts follow
        viewModel.refreshLanguage()
        enableEdgeToEdge()
        setContent {
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

    override fun onStop() {
        super.onStop()
        // The app may be closed from here on without further notice: keep the history and the line
        viewModel.saveSession()
    }
}
