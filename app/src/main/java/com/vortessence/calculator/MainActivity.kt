package com.vortessence.calculator

import android.content.Context
import android.content.pm.ActivityInfo
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
        // Phones stay upright (the manifest); a tablet or an unfolded foldable turns freely. Android 16 and later
        // do that by themselves, older ones need it here. The activity is made again when a foldable folds or unfolds
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
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

/** The smallest width of a tablet or an unfolded foldable, in dp: such screens are not held upright only. */
private const val LARGE_SCREEN_MIN_WIDTH_DP = 600
