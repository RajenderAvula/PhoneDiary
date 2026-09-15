package com.example.phonediary.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatPickerDialog(
    initial: RepeatConfig,
    onConfirm: (RepeatConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(initial.type.takeIf { it != "NONE" } ?: "DAILY") }

    val initDays = initial.intervalMinutes / 1440
    val initHours = (initial.intervalMinutes % 1440) / 60
    val initMinutes = initial.intervalMinutes % 60
    var daysInput by remember { mutableStateOf(initDays.toString()) }
    var hoursInput by remember { mutableStateOf(initHours.toString()) }
    var minutesInput by remember { mutableStateOf(initMinutes.toString()) }

    var startMinute by remember { mutableStateOf(initial.startMinuteOfDay) }
    var endMinute by remember { mutableStateOf(initial.endMinuteOfDay) }

    fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        val cal = Calendar.getInstance()
        val hour = current / 60
        val minute = current % 60
        TimePickerDialog(
            context,
            { _, h, m -> onPicked(h * 60 + m) },
            hour, minute, false
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Repeat", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("DAILY", "WEEKLY", "MONTHLY").forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("YEARLY", "CUSTOM").forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                        )
                    }
                }

                if (type == "CUSTOM") {
                    Spacer(Modifier.height(8.dp))
                    Text("Every", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = daysInput,
                            onValueChange = { daysInput = it.filter { c -> c.isDigit() }.take(3) },
                            modifier = Modifier.width(70.dp),
                            singleLine = true,
                            label = { Text("days") }
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = hoursInput,
                            onValueChange = { hoursInput = it.filter { c -> c.isDigit() }.take(2) },
                            modifier = Modifier.width(70.dp),
                            singleLine = true,
                            label = { Text("hrs") }
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = minutesInput,
                            onValueChange = { minutesInput = it.filter { c -> c.isDigit() }.take(2) },
                            modifier = Modifier.width(70.dp),
                            singleLine = true,
                            label = { Text("min") }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Start time", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { pickTime(startMinute) { startMinute = it } }) {
                    Text(RepeatConfig.formatTimeOfDay(startMinute))
                }

                Spacer(Modifier.height(8.dp))
                Text("End time", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { pickTime(endMinute) { endMinute = it } }) {
                    Text(RepeatConfig.formatTimeOfDay(endMinute))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val totalMinutes = if (type == "CUSTOM") {
                    val d = daysInput.toIntOrNull() ?: 0
                    val h = hoursInput.toIntOrNull() ?: 0
                    val m = minutesInput.toIntOrNull() ?: 0
                    (d * 1440 + h * 60 + m).coerceAtLeast(1)
                } else 0
                onConfirm(RepeatConfig(type, totalMinutes, startMinute, endMinute))
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
