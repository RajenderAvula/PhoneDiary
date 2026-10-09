package com.example.phonediary.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.phonediary.data.LocationReminderItem

/** Moves every selected item one step up, keeping their relative order. */
private fun moveUp(list: List<LocationReminderItem>, selected: Set<String>): List<LocationReminderItem> {
    val result = list.toMutableList()
    for (i in 1 until result.size) {
        if (result[i].id in selected && result[i - 1].id !in selected) {
            val tmp = result[i - 1]; result[i - 1] = result[i]; result[i] = tmp
        }
    }
    return result
}

/** Moves every selected item one step down, keeping their relative order. */
private fun moveDown(list: List<LocationReminderItem>, selected: Set<String>): List<LocationReminderItem> {
    val result = list.toMutableList()
    for (i in result.size - 2 downTo 0) {
        if (result[i].id in selected && result[i + 1].id !in selected) {
            val tmp = result[i + 1]; result[i + 1] = result[i]; result[i] = tmp
        }
    }
    return result
}

/**
 * The list of location reminders for a note. Each one has its own on/off
 * switch, repeat schedule, note editor, and pin; items can be reordered one
 * at a time (▲▼) or several at once (Select & move). Used identically in the
 * main composer, the inline editor, and the full-screen editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationReminderSection(
    items: List<LocationReminderItem>,
    onItemsChanged: (List<LocationReminderItem>) -> Unit,
    registrationStatus: Map<String, Boolean> = emptyMap(),
    registrationError: Map<String, String> = emptyMap(),
    onToggle: ((LocationReminderItem) -> Unit)? = null
) {
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    var showPicker by remember { mutableStateOf(false) }
    var editingPinItem by remember { mutableStateOf<LocationReminderItem?>(null) }
    var repeatEditingItem by remember { mutableStateOf<LocationReminderItem?>(null) }
    var noteEditingItem by remember { mutableStateOf<LocationReminderItem?>(null) }
    var confirmRemoveItem by remember { mutableStateOf<LocationReminderItem?>(null) }

    var reorderMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val showReorder = reorderMode && items.size > 1
    val activeSelected = selectedIds.filter { id -> items.any { it.id == id } }.toSet()

    fun updateItem(updated: LocationReminderItem) {
        onItemsChanged(items.map { if (it.id == updated.id) updated else it })
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Location reminders", style = MaterialTheme.typography.titleSmall)
        if (items.size > 1) {
            TextButton(onClick = {
                reorderMode = !reorderMode
                selectedIds = emptySet()
            }) { Text(if (reorderMode) "Done" else "Select & move") }
        }
    }

    if (showReorder) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                enabled = activeSelected.isNotEmpty(),
                onClick = { onItemsChanged(moveUp(items, activeSelected)) }
            ) { Text("▲ Up") }
            OutlinedButton(
                enabled = activeSelected.isNotEmpty(),
                onClick = { onItemsChanged(moveDown(items, activeSelected)) }
            ) { Text("▼ Down") }
            TextButton(onClick = { selectedIds = items.map { it.id }.toSet() }) { Text("All") }
            TextButton(onClick = { selectedIds = emptySet() }) { Text("None") }
        }
        Text("${activeSelected.size} selected", style = MaterialTheme.typography.labelSmall)
    }

    if (items.isEmpty()) {
        Text("None yet", style = MaterialTheme.typography.bodySmall)
    }

    items.forEachIndexed { index, item ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showReorder) {
                    Checkbox(
                        checked = item.id in activeSelected,
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                        }
                    )
                }
                Switch(
                    checked = item.enabled,
                    onCheckedChange = { checked ->
                        val updated = item.copy(enabled = checked)
                        updateItem(updated)
                        onToggle?.invoke(updated)
                    }
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                    val radiusLabel = if (item.radiusMeters >= 200_000f) "≈unlimited radius" else "${item.radiusMeters.toInt()}m radius"
                    Text(radiusLabel, style = MaterialTheme.typography.labelSmall)

                    if (item.hasRepeat()) {
                        Text(
                            "🔁 Repeats while here: ${repeatDisplayLabel2(item.repeatRule)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (item.hasNote()) {
                        val preview = item.title.ifBlank { markerRegex.replace(item.text, "").trim() }.take(50)
                        val extras = buildList {
                            if (item.attachmentNames.isNotEmpty()) add("${item.attachmentNames.size} file(s)")
                            if (item.tags.isNotEmpty()) add(item.tags.joinToString(" ") { "#$it" })
                        }.joinToString(" · ")
                        Text(
                            "📝 " + listOf(preview, extras).filter { it.isNotBlank() }.joinToString(" — "),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    if (item.enabled) {
                        when (registrationStatus[item.id]) {
                            true -> Text(
                                "● Active — will notify on arrival",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            false -> {
                                val errorMsg = registrationError[item.id] ?: "unknown error"
                                Text(
                                    "⚠ Not registered: $errorMsg",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                if (errorMsg.contains("Location is turned off", ignoreCase = true)) {
                                    TextButton(onClick = {
                                        settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
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
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                    enabled = index > 0,
                    onClick = { onItemsChanged(moveUp(items, setOf(item.id))) }
                ) { Text("▲") }
                TextButton(
                    enabled = index < items.lastIndex,
                    onClick = { onItemsChanged(moveDown(items, setOf(item.id))) }
                ) { Text("▼") }
                TextButton(onClick = { repeatEditingItem = item }) {
                    Text(if (item.hasRepeat()) "🔁 Change repeat" else "🔁 Add repeat")
                }
                if (item.hasRepeat()) {
                    TextButton(onClick = { updateItem(item.copy(repeatRule = "NONE")) }) { Text("No repeat") }
                }
                TextButton(onClick = { noteEditingItem = item }) {
                    Text(if (item.hasNote()) "📝 Edit note •" else "📝 Note")
                }
                TextButton(onClick = { editingPinItem = item; showPicker = true }) { Text("📍 Edit pin") }
                TextButton(onClick = { confirmRemoveItem = item }) { Text("✕ Remove") }
            }
            Divider()
        }
    }

    OutlinedButton(onClick = { editingPinItem = null; showPicker = true }) {
        Text("+ Add location")
    }

    // ---- Pin picker (new location, or re-pick an existing one) ----
    if (showPicker) {
        MapLocationPickerDialog(
            initial = editingPinItem,
            onConfirm = { newItem ->
                onItemsChanged(
                    if (editingPinItem != null) items.map { if (it.id == newItem.id) newItem else it }
                    else items + newItem
                )
                showPicker = false
                editingPinItem = null
            },
            onDismiss = {
                showPicker = false
                editingPinItem = null
            }
        )
    }

    // ---- Per-location repeat ----
    repeatEditingItem?.let { item ->
        RepeatPickerDialog(
            initial = RepeatConfig.fromStored(item.repeatRule),
            onConfirm = { config ->
                updateItem(item.copy(repeatRule = config.toStored()))
                repeatEditingItem = null
            },
            onDismiss = { repeatEditingItem = null }
        )
    }

    // ---- Per-location full note editor (its own Dialog window, so the
    //      editor's Scaffold never sits inside the parent's scrolling column) ----
    noteEditingItem?.let { item ->
        Dialog(
            onDismissRequest = { noteEditingItem = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FullScreenNoteEditor(
                        initialTitle = item.title,
                        initialText = item.text,
                        initialLocationUrl = item.locationUrl,
                        initialTags = item.tags,
                        initialAttachmentNames = item.attachmentNames,
                        initialReminderAtMillis = null,
                        initialDueAtMillis = null,
                        initialRepeatRule = item.repeatRule,
                        initialNoteDateTimeMillis = null,
                        isLocationNote = true,
                        locationNoteName = item.label,
                        onSave = { result ->
                            updateItem(
                                item.copy(
                                    title = result.title.trim(),
                                    text = result.text,
                                    locationUrl = result.locationUrl.trim(),
                                    tags = result.tags,
                                    attachmentNames = result.existingAttachmentNames + result.newAttachments.map { it.name },
                                    repeatRule = result.repeatRule
                                )
                            )
                            noteEditingItem = null
                        },
                        onCancel = { noteEditingItem = null }
                    )
                }
            }
        }
    }

    // ---- Confirm before removing (a location can now hold a note) ----
    confirmRemoveItem?.let { item ->
        ConfirmDeleteDialog(
            message = "Remove \"${item.label}\" and its note? This cannot be undone.",
            onConfirm = {
                onItemsChanged(items.filterNot { it.id == item.id })
                confirmRemoveItem = null
            },
            onDismiss = { confirmRemoveItem = null }
        )
    }
}
