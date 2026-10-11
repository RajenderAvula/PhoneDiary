package com.example.phonediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.phonediary.files.TimeStampManager
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeStampDialog(
    initialMillis: Long,
    initialFormat: String,
    onApply: (millis: Long, format: String, applyToAll: Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var millis by remember { mutableStateOf(initialMillis) }
    var pattern by remember { mutableStateOf(initialFormat) }
    var applyToAll by remember { mutableStateOf(false) }

    val cleaned = TimeStampManager.cleanPattern(pattern)
    val preview = TimeStampManager.render(millis, cleaned)
    val dateTimeFormat = remember { SimpleDateFormat("EEE, d MMM yyyy HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timestamp") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    preview ?: "Invalid format",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (preview == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { DateTimePickerUtil.pick(context) { picked -> millis = picked } }
                ) { Text("📅 ${dateTimeFormat.format(millis)}") }
                TextButton(onClick = { millis = System.currentTimeMillis() }) { Text("Set to now") }

                Spacer(Modifier.height(4.dp))
                Text("Format", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TimeStampManager.PRESETS.forEach { preset ->
                        FilterChip(
                            selected = cleaned == preset,
                            onClick = { pattern = preset },
                            label = { Text(TimeStampManager.render(millis, preset) ?: preset) }
                        )
                    }
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Custom format") },
                    singleLine = true,
                    isError = preview == null
                )
                Text(
                    "yyyy year · MM month · MMM Oct · dd day · EEE Fri · HH 24-hour · hh 12-hour · mm minutes · a AM/PM. " +
                        "Put words in single quotes, e.g. 'Date:' dd MMM",
                    style = MaterialTheme.typography.labelSmall
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Use this format for every stamp in this note", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = preview != null, onClick = { onApply(millis, cleaned, applyToAll) }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Delete stamp", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
