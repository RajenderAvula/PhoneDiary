package com.example.phonediary.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.DiaryEntry
import com.example.phonediary.data.LogEntry
import com.example.phonediary.usage.UsageStatsCollector
import com.example.phonediary.worker.ApiKeyStore
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
    var selectedEntry by remember { mutableStateOf<DiaryEntry?>(null) }
    var dayLogEntries by remember { mutableStateOf(listOf<LogEntry>()) }
    var noteText by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }

    fun refreshDates() {
        scope.launch {
            dates = AppDatabase.getInstance(context).logEntryDao().getAllLoggedDates()
        }
    }

    fun openDate(dateKey: String) {
        selectedDate = dateKey
        isEditing = false
        scope.launch {
            val db = AppDatabase.getInstance(context)
            selectedEntry = db.diaryEntryDao().getEntryForDate(dateKey)
            dayLogEntries = db.logEntryDao().getEntriesForDate(dateKey)
        }
    }

    LaunchedEffect(Unit) { refreshDates() }

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
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {

            if (showSettings) {
                SettingsPanel(
                    context = context,
                    apiKeyInput = apiKeyInput,
                    onApiKeyChange = { apiKeyInput = it },
                    onSaveKey = { ApiKeyStore.saveKey(context, apiKeyInput) }
                )
                Divider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // ---- Manual note entry ----
            Text("Add a note for today", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("What are you doing?") }
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
                            noteText = ""
                            refreshDates()
                            // If we're currently viewing today, refresh the preview live.
                            if (selectedDate == todayKey) openDate(todayKey)
                        }
                    }
                }) { Text("Save") }
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            // ---- Days list ----
            Text("Days logged", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
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

                // ---- Preview of raw logged entries with timestamps ----
                Text("Logged entries — $date", style = MaterialTheme.typography.titleMedium)
                if (dayLogEntries.isEmpty()) {
                    Text("No entries logged for this day yet.")
                } else {
                    dayLogEntries.forEach { entry ->
                        val time = timeFormat.format(entry.timestampMillis)
                        val label = when (entry.source) {
                            "app_usage" -> {
                                val minutes = (entry.durationMillis ?: 0L) / 60000
                                "${entry.appName} — ${minutes}m"
                            }
                            else -> entry.note ?: entry.source
                        }
                        Text("$time  •  $label", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---- Diary text with edit support ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Diary entry", style = MaterialTheme.typography.titleMedium)
                    if (selectedEntry != null && !isEditing) {
                        TextButton(onClick = {
                            editText = selectedEntry?.editedText ?: selectedEntry?.generatedText ?: ""
                            isEditing = true
                        }) { Text("Edit") }
                    }
                }

                if (isEditing) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4
                    )
                    Row {
                        Button(onClick = {
                            scope.launch {
                                val current = selectedEntry
                                if (current != null) {
                                    val updated = current.copy(editedText = editText)
                                    AppDatabase.getInstance(context).diaryEntryDao().upsert(updated)
                                    selectedEntry = updated
                                }
                                isEditing = false
                            }
                        }) { Text("Save changes") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { isEditing = false }) { Text("Cancel") }
                    }
                } else {
                    Text(
                        selectedEntry?.editedText
                            ?: selectedEntry?.generatedText
                            ?: "No diary generated yet for this day. It's created automatically each night."
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    context: Context,
    apiKeyInput: String,
    onApiKeyChange: (String) -> Unit,
    onSaveKey: () -> Unit
) {
    val hasUsagePermission = remember { UsageStatsCollector(context).hasUsagePermission() }

    Column {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))
        Text(if (hasUsagePermission) "Usage access: granted" else "Usage access: not granted")
        if (!hasUsagePermission) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) { Text("Grant usage access") }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = onApiKeyChange,
            label = { Text("Claude API key") },
            placeholder = { Text("sk-ant-...") }
        )
        Button(onClick = onSaveKey) { Text("Save key") }
    }
}
