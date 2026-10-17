package com.vortessence.calculator.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.expressions.UnitCategory
import com.vortessence.calculator.core.math.expressions.UnitFamily
import com.vortessence.calculator.core.math.expressions.UnitRegistry
import androidx.compose.ui.res.stringResource
import com.vortessence.calculator.R
import com.vortessence.calculator.core.math.i18n.Language
import com.vortessence.calculator.core.math.i18n.UnitNames

/**
 * Unit picker: categories (Length, Pressure, ...) are chosen with chips; each category shows
 * its families (e.g. "Metre: nm µm mm cm m km") as rows of tappable units. Searching looks
 * through all unit symbols, unit names, family and category names.
 *
 * @param scrollToFamily Family of the selected category to scroll to when the picker opens,
 * e.g. the units a value can be converted to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitsDialog(
    onDismiss: () -> Unit,
    onUnitSelected: (String) -> Unit,
    selectedCategory: Int = 0,
    onCategorySelected: (Int) -> Unit = {},
    scrollToFamily: Int? = null,
    language: Language = Language.ENGLISH
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hideKeyboard = rememberHideKeyboard()
    var query by rememberSaveable { mutableStateOf("") }
    val categories = UnitRegistry.categories
    val categoryIndex = selectedCategory.coerceIn(categories.indices)

    val sections: List<Pair<String?, List<UnitFamily>>> = remember(query, categoryIndex, language) {
        if (query.isBlank()) {
            listOf(null to categories[categoryIndex].families)
        } else {
            categories.mapNotNull { category ->
                search(category, query.trim(), language)?.let { UnitNames.category(category.name, language) to it }
            }
        }
    }

    val chipListState = rememberLazyListState()
    val unitListState = rememberLazyListState()
    // Show the selected category's chip and the requested family, even if far down the lists
    LaunchedEffect(Unit) {
        chipListState.scrollToItem(categoryIndex)
        if (scrollToFamily != null) unitListState.scrollToItem(scrollToFamily)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.units_insert_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.units_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.units_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (query.isBlank()) {
                LazyRow(state = chipListState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(categories) { index, category ->
                        FilterChip(
                            selected = index == categoryIndex,
                            onClick = { onCategorySelected(index) },
                            label = { Text(UnitNames.category(category.name, language)) }
                        )
                    }
                }
            }

            LazyColumn(
                state = unitListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (sections.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.units_none),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
                sections.forEach { (title, families) ->
                    if (title != null) {
                        item(key = "title-$title") {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    items(families, key = { "$title-${it.name}" }) { family ->
                        UnitFamilyRow(family = family, language = language, onUnitSelected = { hideKeyboard(); onUnitSelected(it) })
                    }
                }
            }
        }
    }
}

/** Families of [category] matching [query], reduced to their matching units; null if none match. */
private fun search(category: UnitCategory, query: String, language: Language): List<UnitFamily>? {
    val q = query.lowercase()
    // The names are found in English as well as in the language of the app
    fun matches(name: String, shown: String) = name.lowercase().contains(q) || shown.lowercase().contains(q)
    if (matches(category.name, UnitNames.category(category.name, language))) return category.families
    val families = category.families.mapNotNull { family ->
        if (matches(family.name, UnitNames.family(family.name, language))) return@mapNotNull family
        val units = family.units.filter { it.symbol.lowercase().contains(q) || matches(it.name, UnitNames.unit(it.name, language)) }
        if (units.isEmpty()) null else family.copy(units = units)
    }
    return families.ifEmpty { null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitFamilyRow(family: UnitFamily, language: Language, onUnitSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            UnitNames.family(family.name, language),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            family.units.forEach { unit ->
                SuggestionChip(
                    onClick = { onUnitSelected(unit.symbol) },
                    label = { Text(unit.symbol, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.semantics { contentDescription = UnitNames.unit(unit.name, language) }
                )
            }
        }
    }
}
