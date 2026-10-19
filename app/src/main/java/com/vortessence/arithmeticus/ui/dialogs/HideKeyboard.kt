package com.vortessence.arithmeticus.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Returns an action that drops the focus from the search field and closes the keyboard.
 * A picker that closes itself on selection has to call it first, or the keyboard outlives
 * the field it was opened for.
 */
@Composable
fun rememberHideKeyboard(): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    return {
        focusManager.clearFocus()
        keyboard?.hide()
    }
}
