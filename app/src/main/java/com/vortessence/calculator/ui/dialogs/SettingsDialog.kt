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

@Composable
fun SettingsDialog(
    mode: CalculationMode,
    angleUnit: AngleUnit,
    onToggleMode: () -> Unit,
    onToggleAngleUnit: () -> Unit,
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
