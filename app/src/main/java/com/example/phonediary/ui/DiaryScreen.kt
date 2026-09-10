package com.example.phonediary.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.phonediary.accessibility.BlockedAppsStore
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LogEntry
import com.example.phonediary.usage.UsageStatsCollector
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var dates by remember { mutableStateOf(listOf<String>()) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var dayLogEntries by remember { mutableStateOf(listOf<LogEntry>()) }
    var noteText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var newBlockedPackage by remember { mutableStateOf("") }
    var blockedApps by remember { mutableStateOf(setOf<String>()) }
    var calendarStatus by remember { mutableStateOf<String?>(null) }
    var editingEntryId by remember { mutableStateOf<Long?>(null) }
    var editingEntryText by remember { mutableStateOf("") }

    fun refreshDates() {
        scope.launch {
            dates = AppDatabase.getInstance(context).logEntryDao().getAllLoggedDates()
        }
    }

    fun openDate(dateKey: String) {
        selectedDate = dateKey
        calendarStatus = null
        scope.launch {
            dayLogEntries = AppDatabase.getInstance(context).logEntryDao().getEntriesForDate(dateKey)
        }
    }

    fun sendToCalendar(dateKey: String, entries: List<LogEntry>) {
        scope.launch {
            val wrote = CalendarWriter.writeDayLog(context, dateKey, entries)
            calendarStatus = if (wrote) {
                "Saved to Calendar ✓"
            } else if (!CalendarWriter.hasCalendarPermission(context)) {
                "Failed: Calendar permission not granted"
            } else {
                "Failed: no writable calendar found on this device"
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshDates()
        blockedApps = BlockedAppsStore.getBlockedPackages(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Diary") },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Text("⚙")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {

            if (showSettings) {
                SettingsPanel(
                    context = context,
                    blockedApps = blockedApps,
                    newBlockedPackage = newBlockedPackage,
                    onNewBlockedPackageChange = { newBlockedPackage = it },
                    onAddBlocked = {
                        if (newBlockedPackage.isNotBlank()) {
                            BlockedAppsStore.addBlockedPackage(context, newBlockedPackage.trim())
                            blockedApps = BlockedAppsStore.getBlockedPackages(context)
                            newBlockedPackage = ""
                        }
                    },
                    onRemoveBlocked = { pkg ->
                        BlockedAppsStore.removeBlockedPackage(context, pkg)
                        blockedApps = BlockedAppsStore.getBlockedPackages(context)
                    }
                )
                Divider(modifier = Modifier.padding(vertical = 12.dp))
            }

            Text("Add a note for today", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("What are you doing?") },
                    minLines = 1,
                    maxLines = 6
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (noteText.isNotBlank()) {
                        scope.launch {
                            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                .format(System.currentTimeMillis())
                            AppDatabase.getInstance(context).logEntryDao().insert(
                                LogEntry(
                                    timestampMillis = System.currentTimeMillis(),
                                    dateKey = todayKey,
                                    source = "manual_note",
                                    note = noteText
                                )
                            )
                            CalendarWriter.refreshToday(context)
                            noteText = ""
                            refreshDates()
                            if (selectedDate == todayKey) openDate(todayKey)
                        }
                    }
                }) { Text("Save") }
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Text("Days logged", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                items(dates) { date ->
                    ListItem(
                        headlineContent = { Text(date) },
                        modifier = Modifier.clickable { openDate(date) }
                    )
                    Divider()
                }
            }

            selectedDate?.let { date ->
                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(12.dp))

                Text("Logged entries — $date", style = MaterialTheme.typography.titleMedium)
                if (dayLogEntries.isEmpty()) {
                    Text("No entries logged for this day yet.")
                } else {
                    dayLogEntries.forEach { entry ->
                        val time = timeFormat.format(entry.timestampMillis)
                        val displayLabel = when (entry.source) {
                            "app_usage" -> {
                                val minutes = (entry.durationMillis ?: 0L) / 60000
                                "${entry.appName} — ${minutes}m"
                            }
                            "screen_content" -> "${entry.appName}${entry.note?.let { " — $it" } ?: ""}"
                            else -> entry.note ?: entry.source
                        }

                        if (editingEntryId == entry.id) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                OutlinedTextField(
                                    value = editingEntryText,
                                    onValueChange = { editingEntryText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 1,
                                    maxLines = 6
                                )
                                Row {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val updated = entry.copy(note = editingEntryText)
                                            AppDatabase.getInstance(context).logEntryDao().update(updated)
                                            editingEntryId = null
                                            openDate(date)
                                            CalendarWriter.refreshToday(context)
                                        }
                                    }) { Text("Save") }
                                    TextButton(onClick = { editingEntryId = null }) { Text("Cancel") }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "$time  •  $displayLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    editingEntryId = entry.id
                                    editingEntryText = entry.note ?: entry.appName ?: ""
                                }) { Text("Edit") }
                                TextButton(onClick = {
                                    scope.launch {
                                        AppDatabase.getInstance(context).logEntryDao().delete(entry)
                                        openDate(date)
                                        CalendarWriter.refreshToday(context)
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(onClick = { sendToCalendar(date, dayLogEntries) }) {
                    Text("Send this day to Calendar")
                }
                calendarStatus?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    context: Context,
    blockedApps: Set<String>,
    newBlockedPackage: String,
    onNewBlockedPackageChange: (String) -> Unit,
    onAddBlocked: () -> Unit,
    onRemoveBlocked: (String) -> Unit
) {
    val hasUsagePermission = remember { UsageStatsCollector(context).hasUsagePermission() }
    val hasCalendarPermission = remember { CalendarWriter.hasCalendarPermission(context) }

    Column {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))
        Text(if (hasUsagePermission) "Usage access: granted" else "Usage access: not granted")
        if (!hasUsagePermission) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) { Text("Grant usage access") }
        }

        Spacer(Modifier.height(8.dp))
        Text(if (hasCalendarPermission) "Calendar access: granted" else "Calendar access: not granted")

        Spacer(Modifier.height(8.dp))
        Text("Screen-content logging (Accessibility)")
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("Open Accessibility settings") }
        Text(
            "Find 'Phone Diary' in the list and turn it on there.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Blocked apps (never logged)", style = MaterialTheme.typography.titleSmall)
        blockedApps.forEach { pkg ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(pkg, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onRemoveBlocked(pkg) }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newBlockedPackage,
                onValueChange = onNewBlockedPackageChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. com.bank.app") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAddBlocked) { Text("Block") }
        }
    }
}
