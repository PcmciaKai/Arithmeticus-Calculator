package com.vortessence.arithmeticus.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vortessence.arithmeticus.R
import com.vortessence.arithmeticus.viewmodel.ClearTip
import com.vortessence.arithmeticus.viewmodel.ClearTipAnswer

/**
 * Shown once, the first time C is pressed the way other calculators need it: on a finished calculation ([ClearTip.LINE],
 * which Enter would take into the history) or on the empty line after Enter ([ClearTip.HISTORY], which clears the history).
 */
@Composable
fun ClearTipDialog(tip: ClearTip, onAnswer: (ClearTipAnswer) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(ClearTipAnswer.NOTHING) },
        title = { Text(stringResource(R.string.clear_tip_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.clear_tip_text))
                if (tip == ClearTip.HISTORY) Text(stringResource(R.string.clear_tip_history))
            }
        },
        confirmButton = {
            when (tip) {
                ClearTip.LINE -> TextButton(onClick = { onAnswer(ClearTipAnswer.ENTER) }) { Text(stringResource(R.string.clear_tip_enter)) }
                ClearTip.HISTORY -> TextButton(onClick = { onAnswer(ClearTipAnswer.NOTHING) }) { Text(stringResource(R.string.clear_tip_ok)) }
            }
        },
        dismissButton = {
            val label = if (tip == ClearTip.LINE) R.string.clear_tip_clear_line else R.string.clear_tip_clear_history
            TextButton(onClick = { onAnswer(ClearTipAnswer.CLEAR) }) { Text(stringResource(label)) }
        }
    )
}
