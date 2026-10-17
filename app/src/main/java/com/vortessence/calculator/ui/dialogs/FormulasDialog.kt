package com.vortessence.calculator.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.model.formulas.Formula
import com.vortessence.calculator.model.formulas.FormulasDb
import com.vortessence.calculator.ui.math.DecimalMark
import com.vortessence.calculator.ui.math.MathExpressionView
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vortessence.calculator.R

/** Picker for ready-made functions (statistics, combinatorics, ...), grouped by category. */
@Composable
fun FormulasDialog(
    onDismiss: () -> Unit,
    onFormulaSelected: (MathString) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val hideKeyboard = rememberHideKeyboard()
    val resources = LocalResources.current
    val grouped = remember(searchQuery, resources) {
        FormulasDb.items.filter {
            searchQuery.isBlank() ||
                resources.getString(it.name).contains(searchQuery, ignoreCase = true) ||
                resources.getString(it.description).contains(searchQuery, ignoreCase = true) ||
                resources.getString(it.category).contains(searchQuery, ignoreCase = true)
        }.groupBy { it.category }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.formulas_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.formulas_search)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // The one thing every function of several values needs
                    item(key = "hint") {
                        Text(
                            text = stringResource(R.string.formulas_hint, DecimalMark.argumentSeparator.trim()),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    grouped.forEach { (category, formulas) ->
                        item(key = category) {
                            Text(
                                text = stringResource(category),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                            )
                        }
                        items(formulas, key = { it.name }) { formula ->
                            FormulaItemRow(formula = formula, onClick = { hideKeyboard(); onFormulaSelected(formula.template) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun FormulaItemRow(formula: Formula, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(formula.name),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(formula.description),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // What gets inserted, with boxes for the values to fill in
            MathExpressionView(
                mathString = formula.template,
                fontSize = 18.sp,
                textColor = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
