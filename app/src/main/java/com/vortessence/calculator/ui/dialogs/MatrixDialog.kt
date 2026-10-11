package com.vortessence.calculator.ui.dialogs

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.vortessence.calculator.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Largest number of rows and columns of a matrix. */
const val MAX_MATRIX_SIZE = 5

/**
 * Picks the size of a new matrix on a grid, like inserting a table in a word processor:
 * touching or dragging over the grid selects rows × columns, lifting the finger inserts it.
 * The grid starts with [initialRows] × [initialCols] selected.
 */
@Composable
fun MatrixDialog(
    initialRows: Int,
    initialCols: Int,
    onDismiss: () -> Unit,
    onSizeSelected: (rows: Int, cols: Int) -> Unit
) {
    var rows by remember { mutableIntStateOf(initialRows.coerceIn(1, MAX_MATRIX_SIZE)) }
    var cols by remember { mutableIntStateOf(initialCols.coerceIn(1, MAX_MATRIX_SIZE)) }
    val view = LocalView.current
    val currentOnSizeSelected by rememberUpdatedState(onSizeSelected)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.matrix_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "$rows × $cols",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                val spacing = 6.dp
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val cellSize = ((maxWidth - spacing * (MAX_MATRIX_SIZE - 1)) / MAX_MATRIX_SIZE).coerceAtMost(48.dp)
                    var gridSize by remember { mutableStateOf(IntSize.Zero) }

                    /** The cell under [position], as (rows, cols) counted from 1. */
                    fun sizeAt(position: Offset): Pair<Int, Int> {
                        val r = (position.y / gridSize.height * MAX_MATRIX_SIZE).toInt().coerceIn(0, MAX_MATRIX_SIZE - 1)
                        val c = (position.x / gridSize.width * MAX_MATRIX_SIZE).toInt().coerceIn(0, MAX_MATRIX_SIZE - 1)
                        return (r + 1) to (c + 1)
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(spacing),
                        modifier = Modifier
                            .onSizeChanged { gridSize = it }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    fun select(position: Offset) {
                                        val (r, c) = sizeAt(position)
                                        if (r != rows || c != cols) {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            rows = r
                                            cols = c
                                        }
                                    }
                                    select(down.position)
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (!change.pressed) {
                                            currentOnSizeSelected(rows, cols)
                                            break
                                        }
                                        select(change.position)
                                    }
                                }
                            }
                    ) {
                        for (r in 0 until MAX_MATRIX_SIZE) {
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                                for (c in 0 until MAX_MATRIX_SIZE) {
                                    val isSelected = r < rows && c < cols
                                    Box(
                                        Modifier
                                            .size(cellSize)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.matrix_drag_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.matrix_store_hint),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSizeSelected(rows, cols) }) {
                Text(stringResource(R.string.matrix_insert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
