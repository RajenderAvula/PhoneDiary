package com.example.phonediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.phonediary.data.LocationReminderItem

/**
 * Renders the full list of location reminders for a note, each with its
 * own enable/disable toggle, label, radius, and a way to edit (re-pick
 * on the map) or remove it, plus an "Add location" button.
 */
/*@Composable
fun LocationReminderSection(
    items: List<LocationReminderItem>,
    onItemsChanged: (List<LocationReminderItem>) -> Unit
) {*/

@Composable
fun LocationReminderSection(
    items: List<LocationReminderItem>,
    onItemsChanged: (List<LocationReminderItem>) -> Unit,
    registrationStatus: Map<String, Boolean> = emptyMap(),
    registrationError: Map<String, String> = emptyMap()
) {
    var showPicker by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<LocationReminderItem?>(null) }

    Text("Location reminders", style = MaterialTheme.typography.titleSmall)

    if (items.isEmpty()) {
        Text("None yet", style = MaterialTheme.typography.bodySmall)
    }

    items.forEach { item ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = item.enabled,
                onCheckedChange = { checked ->
                    onItemsChanged(items.map { if (it.id == item.id) it.copy(enabled = checked) else it })
                }
            )
            Spacer(Modifier.width(8.dp))
          /*  Column(modifier = Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyMedium)
                val radiusLabel = if (item.radiusMeters >= 200_000f) "≈unlimited radius" else "${item.radiusMeters.toInt()}m radius"
                Text(radiusLabel, style = MaterialTheme.typography.labelSmall)
            }*/
            Column(modifier = Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyMedium)
                val radiusLabel = if (item.radiusMeters >= 200_000f) "≈unlimited radius" else "${item.radiusMeters.toInt()}m radius"
                Text(radiusLabel, style = MaterialTheme.typography.labelSmall)
                if (item.enabled) {
                    val registered = registrationStatus[item.id]
                    when (registered) {
                        true -> Text("● Active — will notify on arrival", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        false -> Text(
                            "⚠ Not registered: ${registrationError[item.id] ?: "unknown error"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        null -> Text("Registering…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            TextButton(onClick = { editingItem = item; showPicker = true }) { Text("Edit") }
            TextButton(onClick = {
                onItemsChanged(items.filterNot { it.id == item.id })
            }) { Text("✕") }
        }
    }

    OutlinedButton(onClick = { editingItem = null; showPicker = true }) {
        Text("+ Add location")
    }

    if (showPicker) {
        MapLocationPickerDialog(
            initial = editingItem,
            onConfirm = { newItem ->
                onItemsChanged(
                    if (editingItem != null) items.map { if (it.id == newItem.id) newItem else it }
                    else items + newItem
                )
                showPicker = false
                editingItem = null
            },
            onDismiss = {
                showPicker = false
                editingItem = null
            }
        )
    }
}
