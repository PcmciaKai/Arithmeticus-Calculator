package com.vortessence.arithmeticus.ui.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.arithmeticus.R
import com.vortessence.arithmeticus.core.math.MathEngine
import com.vortessence.arithmeticus.core.math.solvers.Function1D
import com.vortessence.arithmeticus.core.math.type.MathString
import com.vortessence.arithmeticus.core.math.type.MathText
import com.vortessence.arithmeticus.ui.math.DecimalMark
import com.vortessence.arithmeticus.ui.math.MathExpressionView

/** One column of the table of values: a function of the graph, its line and its colour. */
internal class TableColumn(val line: MathString, val color: Color, val function: Function1D)

/** How many rows the table has, from the start value on. */
private const val TABLE_ROWS = 201

/**
 * The values of the [columns] in a table: x from a start value in steps, one row each, and the y of every function beside it.
 * Start and step are typed above the table and may be expressions (π/6). Without a function there is nothing to list.
 */
@Composable
internal fun ValueTable(columns: List<TableColumn>, modifier: Modifier = Modifier) {
    var startText by rememberSaveable { mutableStateOf("0") }
    var stepText by rememberSaveable { mutableStateOf("1") }
    var start by remember { mutableDoubleStateOf(0.0) }
    var step by remember { mutableDoubleStateOf(1.0) }
    // A value that cannot be read (yet, while typing) keeps the last one; the field says so
    var startValid by remember { mutableStateOf(true) }
    var stepValid by remember { mutableStateOf(true) }
    LaunchedEffect(startText) { valueOf(startText).let { startValid = it != null; if (it != null) start = it } }
    LaunchedEffect(stepText) { valueOf(stepText)?.takeIf { it != 0.0 }.let { stepValid = it != null; if (it != null) step = it } }

    // The graph screen has no Surface around it, so the text colour is set here: the default is black, also in dark mode
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for ((label, text, valid, onChange) in listOf(
                TableField(R.string.graph_table_start, startText, startValid) { startText = it },
                TableField(R.string.graph_table_step, stepText, stepValid) { stepText = it }
            )) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onChange,
                    label = { Text(stringResource(label)) },
                    isError = !valid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (columns.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.graph_table_no_function),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
            return@Column
        }
        // The first column is named after the variable of the functions (x, unless they all use another)
        val variable = columns.map { it.function.variable.identifier }.distinct().singleOrNull() ?: "x"
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            // The columns share the width; with many functions they keep a width that is readable, and the table scrolls sideways
            val cellWidth = maxOf(MIN_CELL_WIDTH, maxWidth / (columns.size + 1))
            Box(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Column(modifier = Modifier.width(cellWidth * (columns.size + 1))) {
                    Row(modifier = Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(variable, modifier = Modifier.width(cellWidth), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        for (column in columns) {
                            Row(modifier = Modifier.width(cellWidth).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                                Box(modifier = Modifier.size(8.dp).background(column.color, CircleShape))
                                Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                    MathExpressionView(mathString = column.line, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(TABLE_ROWS) { i ->
                            // Each x from the start, not by adding up steps, so that the rounding does not grow down the table
                            val x = start + i * step
                            val rowColor = if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.Transparent
                            Row(modifier = Modifier.background(rowColor).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(formatGraphNumber(x), modifier = Modifier.width(cellWidth), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                                for (column in columns) {
                                    val y = remember(column.function, x) { column.function.evaluateAt(x) }
                                    Text(formatGraphNumber(y), modifier = Modifier.width(cellWidth), textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

private val MIN_CELL_WIDTH = 120.dp

private data class TableField(val label: Int, val text: String, val valid: Boolean, val onChange: (String) -> Unit)

/** The number [text] stands for: a number, or an expression such as π/6 or 2^10; null if it is none. */
private suspend fun valueOf(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    trimmed.replace(',', '.').replace('−', '-').toDoubleOrNull()?.let { return it }
    val input = MathText.fromPlainText(trimmed, DecimalMark.comma) ?: return null
    val result = MathEngine.calculate(input)
    return result.numericValue.takeIf { result.isSuccess && it.isFinite() && result.numericImaginary == 0.0 && result.unit == null }
}
