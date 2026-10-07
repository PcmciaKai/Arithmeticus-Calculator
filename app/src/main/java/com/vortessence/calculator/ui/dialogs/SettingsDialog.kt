package com.vortessence.calculator.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.UnitSystem
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
    onToggleAngleUnit: () -> Unit,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onDecimalPlacesChange: (Int?) -> Unit,
    onDigitGroupingChange: (Boolean) -> Unit,
    onComplexNumbersChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    var showSupport by remember { mutableStateOf(false) }
    if (showSupport) {
        SupportDialog(onDismiss = { showSupport = false })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Calculator Settings", fontWeight = FontWeight.Bold)
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
                        Text("Angle Unit", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            if (angleUnit == AngleUnit.RADIANS) "Radians" else "Degrees",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilterChip(
                        selected = angleUnit == AngleUnit.RADIANS,
                        onClick = onToggleAngleUnit,
                        label = { Text(if (angleUnit == AngleUnit.RADIANS) "RAD" else "DEG") }
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
                        Text("Complex numbers", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            if (complexNumbers) "Allow i and complex results, e.g. √−4 = 2i"
                            else "Real numbers only, √−4 is an error",
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
                        Text("Units for results", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            unitSystemDescription(unitSystem),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val options = listOf(
                        UnitSystem.METRIC to "Metric",
                        UnitSystem.IMPERIAL to "Imperial",
                        UnitSystem.US_CUSTOMARY to "US"
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
                    Text("Number display", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Decimal places", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(
                                if (decimalPlaces == null) "Automatic, up to 10 significant digits"
                                else "Always $decimalPlaces, e.g. ${"%.${decimalPlaces}f".format(Locale.US, 2.5)}",
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
                            Text("Digit grouping", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(
                                "Show large numbers as 300\u202F000",
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
                    Text("Appearance", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    val options = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark"
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

                OutlinedButton(
                    onClick = { showSupport = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("♥︎ Support this app")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
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
            Text("♥︎ Support this app", fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                "I'm very glad that you like this app. This app is open-source and will always be free, " +
                    "but I'd be happy if you were to buy me a coffee.\n\n" +
                    "Unfortunately, due to Google's policies, I can't just add a button that would link to " +
                    "a page where you can easily donate. I'm sure that if you can find this project on " +
                    "my GitHub (PcmciaKai), you can also find a way to leave a tip."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

private fun unitSystemDescription(system: UnitSystem): String = when (system) {
    UnitSystem.METRIC -> "SI units: m, kg, m³, N, Pa, J, K, …"
    UnitSystem.IMPERIAL -> "British imperial: ft, lb, imperial gallon, psi, …"
    UnitSystem.US_CUSTOMARY -> "US customary: ft, lb, US gallon, psi, …"
}

/** "Auto" or a fixed number of decimal places from 0 to [MAX_DECIMAL_PLACES]. */
@Composable
private fun DecimalPlacesPicker(value: Int?, onChange: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(value?.toString() ?: "Auto")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf<Int?>(null) + (0..MAX_DECIMAL_PLACES)).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option?.toString() ?: "Auto") },
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
