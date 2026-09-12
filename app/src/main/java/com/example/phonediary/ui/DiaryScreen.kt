package com.example.phonediary.ui

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.phonediary.accessibility.BlockedAppsStore
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.files.AudioRecorderHelper
import com.example.phonediary.files.BackupHelper
import com.example.phonediary.files.EmailBackupHelper
import com.example.phonediary.files.FileAttachmentHelper
import com.example.phonediary.files.MediaResolveUtil
import com.example.phonediary.files.RestoreHelper
import com.example.phonediary.files.SavedAttachment
import com.example.phonediary.files.VideoCaptureHelper
import com.example.phonediary.reminders.NoteReminderScheduler
import com.example.phonediary.usage.UsageStatsCollector
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class DiaryTab { HOME, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    currentTheme: AppTheme = AppTheme.DARK,
    onThemeChange: (AppTheme) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(DiaryTab.HOME) }

    var newBlockedPackage by remember { mutableStateOf("") }
    var blockedApps by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        blockedApps = BlockedAppsStore.getBlockedPackages(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Phone Diary") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == DiaryTab.HOME,
                    onClick = { selectedTab = DiaryTab.HOME },
                    icon = { Text("🏠") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == DiaryTab.SETTINGS,
                    onClick = { selectedTab = DiaryTab.SETTINGS },
                    icon = { Text("⚙") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                DiaryTab.HOME -> HomeTabContent()
                DiaryTab.SETTINGS -> Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
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
                        },
                        currentTheme = currentTheme,
                        onThemeChange = onThemeChange
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTabContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateTimeFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val audioRecorder = remember { AudioRecorderHelper(context) }

    var dates by remember { mutableStateOf(listOf<String>()) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var dayLogEntries by remember { mutableStateOf(listOf<LogEntry>()) }

    var noteText by remember { mutableStateOf("") }
    var locationText by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<SavedAttachment>()) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingVideoName by remember { mutableStateOf<String?>(null) }

    var reminderAtMillis by remember { mutableStateOf<Long?>(null) }
    var dueAtMillis by remember { mutableStateOf<Long?>(null) }
    var repeatRule by remember { mutableStateOf("NONE") }
    var showRepeatMenu by remember { mutableStateOf(false) }

    var calendarStatus by remember { mutableStateOf<String?>(null) }

    var editingEntryId by remember { mutableStateOf<Long?>(null) }
    var editingNoteText by remember { mutableStateOf("") }
    var editingLocationText by remember { mutableStateOf("") }
    var editingAttachments by remember { mutableStateOf(listOf<String>()) }
    var editingReminderAtMillis by remember { mutableStateOf<Long?>(null) }
    var editingDueAtMillis by remember { mutableStateOf<Long?>(null) }
    var editingRepeatRule by remember { mutableStateOf("NONE") }
    var showEditRepeatMenu by remember { mutableStateOf(false) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedEntryIds by remember { mutableStateOf(setOf<Long>()) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LogEntry>?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val saved = uris.mapNotNull { uri -> FileAttachmentHelper.copyToDownloads(context, uri) }
                pendingAttachments = pendingAttachments + saved
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                noteText = if (noteText.isBlank()) spokenText else "$noteText $spokenText"
            }
        }
    }

    val videoCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = pendingVideoUri
            val name = pendingVideoName
            if (uri != null && name != null) {
                pendingAttachments = pendingAttachments + SavedAttachment(name, uri)
            }
        }
        pendingVideoUri = null
        pendingVideoName = null
    }

    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your entry")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            voiceLauncher.launch(intent)
        }
    }

    fun toggleAudioRecording() {
        if (isRecordingAudio) {
            val saved = audioRecorder.stopRecordingAndSave()
            isRecordingAudio = false
            if (saved != null) pendingAttachments = pendingAttachments + saved
        } else {
            try {
                audioRecorder.startRecording()
                isRecordingAudio = true
            } catch (e: Exception) {
                isRecordingAudio = false
            }
        }
    }

    fun startVideoCapture() {
        val result = VideoCaptureHelper.createVideoOutputUri(context) ?: return
        val (uri, name) = result
        pendingVideoUri = uri
        pendingVideoName = name
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            videoCaptureLauncher.launch(intent)
        }
    }

    fun refreshDates() {
        scope.launch {
            dates = AppDatabase.getInstance(context).logEntryDao().getAllLoggedDates()
        }
    }

    fun openDate(dateKey: String) {
        selectedDate = dateKey
        calendarStatus = null
        editingEntryId = null
        selectionMode = false
        selectedEntryIds = emptySet()
        searchResults = null
        searchQuery = ""
        scope.launch {
            dayLogEntries = AppDatabase.getInstance(context).logEntryDao().getEntriesForDate(dateKey)
        }
    }

    fun runSearch(keyword: String) {
        if (keyword.isBlank()) {
            searchResults = null
            return
        }
        isSearching = true
        scope.launch {
            searchResults = AppDatabase.getInstance(context).logEntryDao().searchEntries(keyword.trim())
            isSearching = false
        }
    }

    fun sendToCalendar(dateKey: String, entries: List<LogEntry>) {
        scope.launch {
            val wrote = CalendarWriter.writeDayLog(context, dateKey, entries)
            val targetInfo = CalendarWriter.getTargetCalendarInfo(context)
            calendarStatus = if (wrote) {
                "Saved to Calendar ✓\n$targetInfo"
            } else if (!CalendarWriter.hasCalendarPermission(context)) {
                "Failed: Calendar permission not granted"
            } else {
                "Failed: no writable calendar found on this device"
            }
        }
    }

    fun openCalendarApp() {
        val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
        ContentUris.appendId(builder, System.currentTimeMillis())
        context.startActivity(Intent(Intent.ACTION_VIEW).setData(builder.build()))
    }

    fun deleteSelectedEntries(dateKey: String) {
        scope.launch {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            dayLogEntries.filter { it.id in selectedEntryIds }.forEach {
                NoteReminderScheduler.cancel(context, it.id)
                dao.delete(it)
            }
            selectedEntryIds = emptySet()
            selectionMode = false
            openDate(dateKey)
            CalendarWriter.refreshDate(context, dateKey)
        }
    }

    LaunchedEffect(Unit) { refreshDates() }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {

        // ---- 1. Search ----
        Text("Search", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it; runSearch(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search notes, apps, locations, files…") },
            singleLine = true,
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    TextButton(onClick = { searchQuery = ""; searchResults = null }) { Text("✕") }
                }
            }
        )

        searchResults?.let { results ->
            Spacer(Modifier.height(8.dp))
            Text(if (isSearching) "Searching…" else "${results.size} result(s)", style = MaterialTheme.typography.bodySmall)
            results.forEach { entry ->
                val entryDateTime = remember(entry.timestampMillis) { dateTimeFormat.format(entry.timestampMillis) }
                val label = entry.note ?: entry.appName ?: entry.source
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { openDate(entry.dateKey) }.padding(vertical = 6.dp)
                ) {
                    Column {
                        Text(entryDateTime, style = MaterialTheme.typography.bodySmall)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Divider()
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))

        // ---- 2. Calendar ----
        Text("Calendar", style = MaterialTheme.typography.titleMedium)
        CalendarMonthView(
            loggedDates = dates.toSet(),
            selectedDate = selectedDate,
            onDayClick = { dateKey -> openDate(dateKey) }
        )

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))

        // ---- 3. Add note with attachments + reminder/due/repeat ----
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
            IconButton(onClick = { startVoiceInput() }) { Text("🎤") }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = locationText,
            onValueChange = { locationText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Location URL (optional)") },
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))
        Text("Schedule (optional)", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                DateTimePickerUtil.pick(context) { picked -> reminderAtMillis = picked }
            }) {
                Text(reminderAtMillis?.let { "⏰ ${dateTimeFormat.format(it)}" } ?: "⏰ Reminder")
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = {
                DateTimePickerUtil.pick(context) { picked -> dueAtMillis = picked }
            }) {
                Text(dueAtMillis?.let { "📅 ${dateTimeFormat.format(it)}" } ?: "📅 Due date")
            }
        }
        if (reminderAtMillis != null) {
            Spacer(Modifier.height(6.dp))
            Box {
                OutlinedButton(onClick = { showRepeatMenu = true }) {
                    Text("🔁 Repeat: ${repeatRule.lowercase().replaceFirstChar { it.uppercase() }}")
                }
                DropdownMenu(expanded = showRepeatMenu, onDismissRequest = { showRepeatMenu = false }) {
                    listOf("NONE", "DAILY", "WEEKLY", "MONTHLY").forEach { rule ->
                        DropdownMenuItem(
                            text = { Text(rule.lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = { repeatRule = rule; showRepeatMenu = false }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) { Text("Attach files") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { toggleAudioRecording() }) {
                Text(if (isRecordingAudio) "⏹ Stop" else "🎙 Record voice")
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { startVideoCapture() }) { Text("📹 Record video") }

        if (pendingAttachments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Attachments to save:", style = MaterialTheme.typography.bodySmall)
            pendingAttachments.forEach { attachment ->
                AttachmentPreview(
                    name = attachment.name,
                    uri = attachment.uri,
                    onRemove = { pendingAttachments = pendingAttachments.filterNot { it.name == attachment.name } }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (noteText.isNotBlank() || locationText.isNotBlank() || pendingAttachments.isNotEmpty()) {
                scope.launch {
                    val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(System.currentTimeMillis())
                    val newId = AppDatabase.getInstance(context).logEntryDao().insert(
                        LogEntry(
                            timestampMillis = System.currentTimeMillis(),
                            dateKey = todayKey,
                            source = "manual_note",
                            note = noteText.ifBlank { null },
                            locationUrl = locationText.ifBlank { null },
                            attachmentFileName = AttachmentListUtil.toStored(pendingAttachments.map { it.name }),
                            reminderAtMillis = reminderAtMillis,
                            dueAtMillis = dueAtMillis,
                            repeatRule = if (reminderAtMillis != null) repeatRule else null
                        )
                    )
                    reminderAtMillis?.let { NoteReminderScheduler.schedule(context, newId, it) }

                    CalendarWriter.refreshDate(context, todayKey)
                    noteText = ""
                    locationText = ""
                    pendingAttachments = emptyList()
                    reminderAtMillis = null
                    dueAtMillis = null
                    repeatRule = "NONE"
                    refreshDates()
                    if (selectedDate == todayKey) openDate(todayKey)
                }
            }
        }) { Text("Save entry") }

        // ---- 4. Day details: entries, then Send/Open Calendar ----
        selectedDate?.let { date ->
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Logged entries — $date", style = MaterialTheme.typography.titleMedium)
                if (dayLogEntries.isNotEmpty()) {
                    TextButton(onClick = { selectionMode = !selectionMode; selectedEntryIds = emptySet() }) {
                        Text(if (selectionMode) "Cancel" else "Select")
                    }
                }
            }

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
                                value = editingNoteText,
                                onValueChange = { editingNoteText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Note") },
                                minLines = 1,
                                maxLines = 6
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = editingLocationText,
                                onValueChange = { editingLocationText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Location URL") },
                                singleLine = true
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = {
                                    DateTimePickerUtil.pick(context) { picked -> editingReminderAtMillis = picked }
                                }) {
                                    Text(editingReminderAtMillis?.let { "⏰ ${dateTimeFormat.format(it)}" } ?: "⏰ Reminder")
                                }
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton(onClick = {
                                    DateTimePickerUtil.pick(context) { picked -> editingDueAtMillis = picked }
                                }) {
                                    Text(editingDueAtMillis?.let { "📅 ${dateTimeFormat.format(it)}" } ?: "📅 Due date")
                                }
                            }
                            if (editingReminderAtMillis != null) {
                                Spacer(Modifier.height(6.dp))
                                Box {
                                    OutlinedButton(onClick = { showEditRepeatMenu = true }) {
                                        Text("🔁 ${editingRepeatRule.lowercase().replaceFirstChar { it.uppercase() }}")
                                    }
                                    DropdownMenu(expanded = showEditRepeatMenu, onDismissRequest = { showEditRepeatMenu = false }) {
                                        listOf("NONE", "DAILY", "WEEKLY", "MONTHLY").forEach { rule ->
                                            DropdownMenuItem(
                                                text = { Text(rule.lowercase().replaceFirstChar { it.uppercase() }) },
                                                onClick = { editingRepeatRule = rule; showEditRepeatMenu = false }
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Attachments:", style = MaterialTheme.typography.bodySmall)
                            if (editingAttachments.isEmpty()) {
                                Text("None", style = MaterialTheme.typography.bodySmall)
                            } else {
                                editingAttachments.forEach { name ->
                                    var resolvedUri by remember(name) { mutableStateOf<Uri?>(null) }
                                    LaunchedEffect(name) { resolvedUri = MediaResolveUtil.resolve(context, name) }
                                    AttachmentPreview(
                                        name = name,
                                        uri = resolvedUri,
                                        onRemove = { editingAttachments = editingAttachments.filterNot { it == name } }
                                    )
                                }
                            }
                            Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        val updated = entry.copy(
                                            note = editingNoteText.ifBlank { null },
                                            locationUrl = editingLocationText.ifBlank { null },
                                            attachmentFileName = AttachmentListUtil.toStored(editingAttachments),
                                            reminderAtMillis = editingReminderAtMillis,
                                            dueAtMillis = editingDueAtMillis,
                                            repeatRule = if (editingReminderAtMillis != null) editingRepeatRule else null
                                        )
                                        AppDatabase.getInstance(context).logEntryDao().update(updated)
                                        NoteReminderScheduler.cancel(context, entry.id)
                                        editingReminderAtMillis?.let { NoteReminderScheduler.schedule(context, entry.id, it) }
                                        editingEntryId = null
                                        openDate(date)
                                        CalendarWriter.refreshDate(context, date)
                                    }
                                }) { Text("Save") }
                                TextButton(onClick = { editingEntryId = null }) { Text("Cancel") }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (selectionMode) {
                                Checkbox(
                                    checked = entry.id in selectedEntryIds,
                                    onCheckedChange = { checked ->
                                        selectedEntryIds = if (checked) selectedEntryIds + entry.id else selectedEntryIds - entry.id
                                    }
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("$time  •  $displayLabel", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    if (!selectionMode) {
                                        TextButton(onClick = {
                                            editingEntryId = entry.id
                                            editingNoteText = entry.note ?: ""
                                            editingLocationText = entry.locationUrl ?: ""
                                            editingAttachments = AttachmentListUtil.toList(entry.attachmentFileName)
                                            editingReminderAtMillis = entry.reminderAtMillis
                                            editingDueAtMillis = entry.dueAtMillis
                                            editingRepeatRule = entry.repeatRule ?: "NONE"
                                        }) { Text("Edit") }
                                        TextButton(onClick = {
                                            scope.launch {
                                                NoteReminderScheduler.cancel(context, entry.id)
                                                AppDatabase.getInstance(context).logEntryDao().delete(entry)
                                                openDate(date)
                                                CalendarWriter.refreshDate(context, date)
                                            }
                                        }) { Text("Delete") }
                                    }
                                }
                                entry.locationUrl?.let { Text("📍 $it", style = MaterialTheme.typography.bodySmall) }
                                entry.reminderAtMillis?.let {
                                    val repeatSuffix = entry.repeatRule?.takeIf { r -> r != "NONE" }?.let { r -> " (repeats ${r.lowercase()})" } ?: ""
                                    Text("⏰ ${dateTimeFormat.format(it)}$repeatSuffix", style = MaterialTheme.typography.bodySmall)
                                }
                                entry.dueAtMillis?.let { Text("📅 Due ${dateTimeFormat.format(it)}", style = MaterialTheme.typography.bodySmall) }
                                AttachmentListUtil.toList(entry.attachmentFileName).forEach { name ->
                                    var resolvedUri by remember(name) { mutableStateOf<Uri?>(null) }
                                    LaunchedEffect(name) { resolvedUri = MediaResolveUtil.resolve(context, name) }
                                    AttachmentPreview(name = name, uri = resolvedUri)
                                }
                            }
                        }
                    }
                }

                if (selectionMode && selectedEntryIds.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { deleteSelectedEntries(date) }) {
                        Text("Delete selected (${selectedEntryIds.size})")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = { sendToCalendar(date, dayLogEntries) }) { Text("Send this day to Calendar") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { openCalendarApp() }) { Text("Open Calendar") }
            }
            calendarStatus?.let { status -> Text(status, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CalendarMonthView(
    loggedDates: Set<String>,
    selectedDate: String?,
    onDayClick: (String) -> Unit
) {
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    val dateKeyFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) { Text("◀") }
            val monthName = currentMonth.month.name.lowercase().replaceFirstChar { it.uppercase() }
            Text("$monthName ${currentMonth.year}", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) { Text("▶") }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
        }

        val firstDay = currentMonth.atDay(1)
        val daysInMonth = currentMonth.lengthOfMonth()
        val startOffset = firstDay.dayOfWeek.value % 7
        val totalCells = startOffset + daysInMonth
        val rows = (totalCells + 6) / 7

        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIndex = row * 7 + col
                    val dayNum = cellIndex - startOffset + 1
                    if (dayNum in 1..daysInMonth) {
                        val date = currentMonth.atDay(dayNum)
                        val dateKey = date.format(dateKeyFormatter)
                        val hasEntry = loggedDates.contains(dateKey)
                        val isSelected = dateKey == selectedDate

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clickable { onDayClick(dateKey) }
                                .then(
                                    if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(dayNum.toString(), style = MaterialTheme.typography.bodySmall)
                                if (hasEntry) {
                                    Box(modifier = Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
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
    onRemoveBlocked: (String) -> Unit,
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit
) {
    val hasUsagePermission = remember { UsageStatsCollector(context).hasUsagePermission() }
    val hasCalendarPermission = remember { CalendarWriter.hasCalendarPermission(context) }
    val scope = rememberCoroutineScope()

    var backupStatus by remember { mutableStateOf<String?>(null) }
    var isBackingUp by remember { mutableStateOf(false) }
    var lastBackupUri by remember { mutableStateOf<Uri?>(null) }
    var lastBackupName by remember { mutableStateOf<String?>(null) }

    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }

    val restorePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isRestoring = true
            restoreStatus = null
            scope.launch {
                val result = RestoreHelper.restoreFromZip(context, uri)
                restoreStatus = if (result != null) {
                    "Restored ${result.entriesRestored} entries, ${result.attachmentsRestored} files ✓"
                } else {
                    "Restore failed — make sure you picked a Phone Diary backup zip"
                }
                isRestoring = false
            }
        }
    }

    Column {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = currentTheme == AppTheme.DARK, onClick = { onThemeChange(AppTheme.DARK) }, label = { Text("Dark") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = currentTheme == AppTheme.COLORFUL, onClick = { onThemeChange(AppTheme.COLORFUL) }, label = { Text("Colourful") })
        }

        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Backup & Restore", style = MaterialTheme.typography.titleSmall)
        Text(
            "Backup saves all entries and files into one dated zip, references it in Calendar, and can email it to you.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(4.dp))
        Button(
            enabled = !isBackingUp,
            onClick = {
                isBackingUp = true
                backupStatus = null
                scope.launch {
                    val result = BackupHelper.createBackup(context)
                    if (result != null) {
                        backupStatus = "Saved: ${result.fileName} ✓ (also recorded in Calendar)"
                        lastBackupUri = result.uri
                        lastBackupName = result.fileName
                    } else {
                        backupStatus = "Backup failed"
                    }
                    isBackingUp = false
                }
            }
        ) {
            Text(if (isBackingUp) "Backing up…" else "Backup now")
        }
        backupStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (lastBackupUri != null && lastBackupName != null) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { EmailBackupHelper.shareBackupViaEmail(context, lastBackupUri!!, lastBackupName!!) }) {
                Text("Email latest backup")
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = !isRestoring,
            onClick = { restorePickerLauncher.launch(arrayOf("application/zip", "*/*")) }
        ) {
            Text(if (isRestoring) "Restoring…" else "Restore from backup zip")
        }
        Text("Pick a .zip from Downloads, or one you saved from a Gmail attachment.", style = MaterialTheme.typography.bodySmall)
        restoreStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        Text(if (hasUsagePermission) "Usage access: granted" else "Usage access: not granted")
        if (!hasUsagePermission) {
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Grant usage access") }
        }

        Spacer(Modifier.height(8.dp))
        Text(if (hasCalendarPermission) "Calendar access: granted" else "Calendar access: not granted")

        Spacer(Modifier.height(8.dp))
        Text("Screen-content logging (Accessibility)")
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility settings") }
        Text("Find 'Phone Diary' in the list and turn it on there.", style = MaterialTheme.typography.bodySmall)

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
