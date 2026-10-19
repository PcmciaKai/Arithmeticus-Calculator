package com.vortessence.arithmeticus.ui.dialogs

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.arithmeticus.R
import com.vortessence.arithmeticus.core.math.type.VariableNameProblem
import com.vortessence.arithmeticus.core.math.type.variableNameProblem

/**
 * Asks for a variable: one letter from the system keyboard. The first letter that can be a
 * variable is taken at once ([onLetter]); one that cannot (e, i, π, T) stays in the field with
 * the reason, and anything that is no letter is ignored. [initial] is the current variable, if any.
 */
@Composable
fun VariableDialog(
    initial: String,
    onDismiss: () -> Unit,
    onLetter: (name: String) -> Unit
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val problem = variableNameProblem(text.text)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.variable_title), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    val letter = input.text.lastOrNull { it.isLetter() }?.toString() ?: ""
                    text = TextFieldValue(letter, selection = TextRange(letter.length))
                    if (letter.isNotEmpty() && variableNameProblem(letter) == null) onLetter(letter)
                },
                singleLine = true,
                isError = problem == VariableNameProblem.CONSTANT || problem == VariableNameProblem.TRANSPOSE,
                supportingText = {
                    Text(
                        when (problem) {
                            VariableNameProblem.CONSTANT -> stringResource(R.string.variable_is_constant, text.text)
                            VariableNameProblem.TRANSPOSE -> stringResource(R.string.variable_is_transpose)
                            else -> stringResource(R.string.variable_prompt)
                        }
                    )
                },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
