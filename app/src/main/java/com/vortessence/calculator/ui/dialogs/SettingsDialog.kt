package com.vortessence.calculator.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortessence.calculator.core.math.context.AngleUnit
import com.vortessence.calculator.core.math.context.CalculationMode
import com.vortessence.calculator.core.math.context.UnitSystem

@Composable
fun SettingsDialog(
    mode: CalculationMode,
    angleUnit: AngleUnit,
    unitSystem: UnitSystem,
    temperatureInKelvin: Boolean,
    onToggleMode: () -> Unit,
    onToggleAngleUnit: () -> Unit,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onTemperatureInKelvinChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Calculator Settings", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Calculation Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Calculation Mode", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            if (mode == CalculationMode.EXACT) "Exact (fractions & radicals)" else "Numeric (decimal)",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilterChip(
                        selected = mode == CalculationMode.EXACT,
                        onClick = onToggleMode,
                        label = { Text(if (mode == CalculationMode.EXACT) "Exact" else "Numeric") }
                    )
                }

                HorizontalDivider()

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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Temperatures in kelvin", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text(
                                "Scientific absolute scale instead of °C/°F",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = temperatureInKelvin, onCheckedChange = onTemperatureInKelvinChange)
                    }
                }

                HorizontalDivider()

                // About / Archimedes Info
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("About Archimedes", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "Modernized Archimedes Calculator for Android with exact symbolic math, natural WYSIWYG input, and interactive function plotting.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

private fun unitSystemDescription(system: UnitSystem): String = when (system) {
    UnitSystem.METRIC -> "SI units: m, kg, L, N, Pa, J, °C, …"
    UnitSystem.IMPERIAL -> "British imperial: ft, lb, imperial gallon, psi, °F, …"
    UnitSystem.US_CUSTOMARY -> "US customary: ft, lb, US gallon, psi, °F, …"
}
