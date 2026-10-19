package com.vortessence.arithmeticus.ui.math

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A character that is always in [TypingInput]'s field, so that the keyboard's backspace has something to delete. */
private const val SENTINEL = "\u200B"

/** How long the field must be left alone before it is emptied again. */
private const val IDLE_RESET_MILLIS = 600L

/**
 * Lets the system keyboard type into the expression: an invisible text field that takes the
 * keyboard's input and hands it on ([onText], [onBackspace], [onEnter]) instead of showing it.
 *
 * What is handed on is the change since the text the field held last time, so keys that come
 * faster than the field is updated are neither lost nor taken twice. The text only grows while
 * typing; once nothing was typed for a moment the field goes back to [SENTINEL], the one
 * character that is always there so that a backspace on an empty field still deletes something.
 *
 * Give it a [focusRequester] and show the keyboard to start typing. The keyboard is the plain
 * (password) kind, which does not compose words or suggest any: every key arrives on its own.
 */
@Composable
fun TypingInput(
    focusRequester: FocusRequester,
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier
) {
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    // The text the field held when it was last handled
    var handled by remember { mutableStateOf(SENTINEL) }
    val currentOnText by rememberUpdatedState(onText)
    val currentOnBackspace by rememberUpdatedState(onBackspace)
    val currentOnEnter by rememberUpdatedState(onEnter)

    fun emptyField() {
        value = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
        handled = SENTINEL
    }

    LaunchedEffect(value.text) {
        if (value.text == SENTINEL) return@LaunchedEffect
        delay(IDLE_RESET_MILLIS)
        emptyField()
    }

    BasicTextField(
        value = value,
        onValueChange = { input ->
            val old = handled
            val new = input.text
            val common = old.commonPrefixWith(new).length
            // Everything after the common start of the old text was taken back, what follows it in the new text was typed
            repeat(old.length - common) { currentOnBackspace() }
            val typed = new.substring(common).replace(SENTINEL, "")
            if (typed.isNotEmpty()) currentOnText(typed)
            if (new.startsWith(SENTINEL)) {
                handled = new
                value = TextFieldValue(new, TextRange(new.length))
            } else {
                // The always-present character was deleted: it is back, and what is left is typed on top of it
                emptyField()
                if (typed.isNotEmpty()) {
                    handled = SENTINEL + typed
                    value = TextFieldValue(handled, TextRange(handled.length))
                }
            }
        },
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        // Done is Enter here; without an action of its own it would only close the keyboard
        keyboardActions = KeyboardActions(onDone = { currentOnEnter() }),
        modifier = modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
    )
}
