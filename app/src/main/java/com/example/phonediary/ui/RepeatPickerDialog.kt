package com.example.phonediary.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    var daysInput by remember { mutableStateOf(initial.everyDays.toString()) }
    var hoursInput by remember { mutableStateOf(initial.everyHours.toString()) }
    var minutesInput by remember { mutableStateOf(initial.everyMinutes.toString()) }

    var startMinute by remember { mutableStateOf(initial.startMinuteOfDay) }
    var endMinute by remember { mutableStateOf(initial.endMinuteOfDay) }
    var startDateMillis by remember { mutableStateOf(initial.startDateMillis) }

    fun applyDefaultEveryFor(selectedType: String) {
        val (d, h, m) = RepeatConfig.defaultEveryFor(selectedType)
        daysInput = d.toString()
        hoursInput = h.toString()
        minutesInput = m.toString()
    }

    fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        val cal = Calendar.getInstance()
        TimePickerDialog(
            context,
            { _, h, m -> onPicked(h * 60 + m) },
            current / 60, current % 60, false
        ).show()
    }

    fun pickDate(currentMillis: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = currentMillis }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val chosen = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                onPicked(chosen.timeInMillis)
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Repeat", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("DAILY", "WEEKLY", "FORTNIGHTLY").forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option; applyDefaultEveryFor(option) },
                            label = { Text(repeatTypeLabel(option)) },
                            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("MONTHLY", "SIX_MONTHLY", "YEARLY").forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option; applyDefaultEveryFor(option) },
                            label = { Text(repeatTypeLabel(option)) },
                            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = type == "CUSTOM",
                        onClick = { type = "CUSTOM"; applyDefaultEveryFor("CUSTOM") },
                        label = { Text("Custom") },
                        modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                    )
                }

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

                Spacer(Modifier.height(8.dp))
                Text("Start date", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { pickDate(startDateMillis) { startDateMillis = it } }) {
                    Text(RepeatConfig.formatDate(startDateMillis))
                }
            }
        },
        
        confirmButton = {
            TextButton(onClick = {
                var d = daysInput.toIntOrNull() ?: 0
                var h = hoursInput.toIntOrNull() ?: 0
                var m = minutesInput.toIntOrNull() ?: 0
                if (d == 0 && h == 0 && m == 0) {
                    // Nobody set an interval — fall back to a sane default for the chosen type
                    // rather than saving a zero-interval repeat.
                    val (defD, defH, defM) = RepeatConfig.defaultEveryFor(type)
                    d = defD; h = defH; m = defM
                }
                onConfirm(RepeatConfig(type, d, h, m, startMinute, endMinute, startDateMillis))
            }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
