package com.example.phonediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.phonediary.data.LocationReminderItem

@Composable
fun LocationReminderSection(
    items: List<LocationReminderItem>,
    onItemsChanged: (List<LocationReminderItem>) -> Unit,
    registrationStatus: Map<String, Boolean> = emptyMap(),
    registrationError: Map<String, String> = emptyMap(),
    onToggle: ((LocationReminderItem) -> Unit)? = null
) {
    val localContext = LocalContext.current
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
                    val updated = item.copy(enabled = checked)
                    onItemsChanged(items.map { if (it.id == item.id) updated else it })
                    // Re-register immediately on toggle (not just on Save) —
                    // Android/Play Services can silently drop a geofence when
                    // the system Location toggle is cycled, so re-registering
                    // every time the switch is turned on is the only way to
                    // recover without requiring a full note save.
                    onToggle?.invoke(updated)
                }
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyMedium)
                val radiusLabel = if (item.radiusMeters >= 200_000f) "≈unlimited radius" else "${item.radiusMeters.toInt()}m radius"
                Text(radiusLabel, style = MaterialTheme.typography.labelSmall)
                if (item.enabled) {
                    val registered = registrationStatus[item.id]
                    when (registered) {
                        true -> Text("● Active — will notify on arrival", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        false -> {
                            val errorMsg = registrationError[item.id] ?: "unknown error"
                            Text(
                                "⚠ Not registered: $errorMsg",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            if (errorMsg.contains("Location is turned off", ignoreCase = true)) {
                                TextButton(onClick = {
                                    localContext.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                    )
                                }) { Text("Turn on Location") }
                            }
                        }
                        null -> Text(
                            "Will register when you Save",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
