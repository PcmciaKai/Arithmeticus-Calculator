package com.vortessence.calculator.ui.dialogs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.UnitSystem
import com.vortessence.calculator.R
import com.vortessence.calculator.i18n.LanguageChoice
import com.vortessence.calculator.ui.theme.ThemeMode
import java.util.Locale

@Composable
fun SettingsDialog(
    angleUnit: AngleUnit,
    unitSystem: UnitSystem,
    decimalPlaces: Int?,
    digitGrouping: Boolean,
    complexNumbers: Boolean,
    themeMode: ThemeMode,
    languageChoice: LanguageChoice,
    onToggleAngleUnit: () -> Unit,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onDecimalPlacesChange: (Int?) -> Unit,
    onDigitGroupingChange: (Boolean) -> Unit,
    onComplexNumbersChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLanguageChange: (LanguageChoice) -> Unit,
    onDismiss: () -> Unit
) {
    var showSupport by remember { mutableStateOf(false) }
    if (showSupport) {
        SupportDialog(onDismiss = { showSupport = false })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Angle Unit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.settings_angle_unit), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            stringResource(if (angleUnit == AngleUnit.RADIANS) R.string.settings_radians else R.string.settings_degrees),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilterChip(
                        selected = angleUnit == AngleUnit.RADIANS,
                        onClick = onToggleAngleUnit,
                        label = { Text(stringResource(if (angleUnit == AngleUnit.RADIANS) R.string.settings_radians_short else R.string.settings_degrees_short)) }
                    )
                }

                HorizontalDivider()

                // Complex numbers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_complex), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            stringResource(if (complexNumbers) R.string.settings_complex_on else R.string.settings_complex_off),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = complexNumbers, onCheckedChange = onComplexNumbersChange)
                }

                HorizontalDivider()

                // Unit system for results
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(stringResource(R.string.settings_units_for_results), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            stringResource(unitSystemDescription(unitSystem)),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val options = listOf(
                        UnitSystem.METRIC to stringResource(R.string.settings_metric),
                        UnitSystem.IMPERIAL to stringResource(R.string.settings_imperial),
                        UnitSystem.US_CUSTOMARY to stringResource(R.string.settings_us)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, (system, label) ->
                            SegmentedButton(
                                selected = unitSystem == system,
                                onClick = { onUnitSystemChange(system) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                            ) {
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Number display
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_number_display), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_decimal_places), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(
                                if (decimalPlaces == null) stringResource(R.string.settings_decimal_auto)
                                else stringResource(R.string.settings_decimal_fixed, decimalPlaces, "%.${decimalPlaces}f".format(Locale.US, 2.5)),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DecimalPlacesPicker(decimalPlaces, onDecimalPlacesChange)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_digit_grouping), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(
                                stringResource(R.string.settings_digit_grouping_desc),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = digitGrouping, onCheckedChange = onDigitGroupingChange)
                    }
                }

                HorizontalDivider()

                // Appearance
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_appearance), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    val options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                        ThemeMode.DARK to stringResource(R.string.settings_theme_dark)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                            ) {
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Language
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_language), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    val options = listOf(
                        LanguageChoice.SYSTEM to stringResource(R.string.settings_language_system),
                        LanguageChoice.ENGLISH to stringResource(R.string.settings_language_english),
                        LanguageChoice.GERMAN to stringResource(R.string.settings_language_german)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, (choice, label) ->
                            SegmentedButton(
                                selected = languageChoice == choice,
                                onClick = { onLanguageChange(choice) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                            ) {
                                Text(label, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = { showSupport = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_support))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun SupportDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_support), fontWeight = FontWeight.Bold)
        },
        text = {
            Text(stringResource(R.string.support_text))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@StringRes
private fun unitSystemDescription(system: UnitSystem): Int = when (system) {
    UnitSystem.METRIC -> R.string.settings_units_si
    UnitSystem.IMPERIAL -> R.string.settings_units_imperial
    UnitSystem.US_CUSTOMARY -> R.string.settings_units_us
}

/** "Auto" or a fixed number of decimal places from 0 to [MAX_DECIMAL_PLACES]. */
@Composable
private fun DecimalPlacesPicker(value: Int?, onChange: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(value?.toString() ?: stringResource(R.string.settings_auto))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf<Int?>(null) + (0..MAX_DECIMAL_PLACES)).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option?.toString() ?: stringResource(R.string.settings_auto)) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private const val MAX_DECIMAL_PLACES = 12
