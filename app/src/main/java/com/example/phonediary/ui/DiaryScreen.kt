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
import com.example.phonediary.files.FileAttachmentHelper
import com.example.phonediary.files.MediaResolveUtil
import com.example.phonediary.files.SavedAttachment
import com.example.phonediary.files.VideoCaptureHelper
import com.example.phonediary.usage.UsageStatsCollector
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
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
    var showSettings by remember { mutableStateOf(false) }
    var newBlockedPackage by remember { mutableStateOf("") }
    var blockedApps by remember { mutableStateOf(setOf<String>()) }
    var calendarStatus by remember { mutableStateOf<String?>(null) }

    var editingEntryId by remember { mutableStateOf<Long?>(null) }
    var editingNoteText by remember { mutableStateOf("") }
    var editingLocationText by remember { mutableStateOf("") }
    var editingAttachments by remember { mutableStateOf(listOf<String>()) }

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
            if (saved != null) {
                pendingAttachments = pendingAttachments + saved
            }
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
        scope.launch {
            dayLogEntries = AppDatabase.getInstance(context).logEntryDao().getEntriesForDate(dateKey)
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
                IconButton(onClick = { startVoiceInput() }) {
                    Text("🎤")
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = locationText,
                onValueChange = { locationText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Location URL (optional, e.g. maps link)") },
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                    Text("Attach files")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { toggleAudioRecording() }) {
                    Text(if (isRecordingAudio) "⏹ Stop" else "🎙 Record voice")
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { startVideoCapture() }) {
                Text("📹 Record video")
            }

            if (pendingAttachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Attachments to save:", style = MaterialTheme.typography.bodySmall)
                pendingAttachments.forEach { attachment ->
                    AttachmentPreview(
                        name = attachment.name,
                        uri = attachment.uri,
                        onRemove = {
                            pendingAttachments = pendingAttachments.filterNot { it.name == attachment.name }
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (noteText.isNotBlank() || locationText.isNotBlank() || pendingAttachments.isNotEmpty()) {
                    scope.launch {
                        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(System.currentTimeMillis())
                        AppDatabase.getInstance(context).logEntryDao().insert(
                            LogEntry(
                                timestampMillis = System.currentTimeMillis(),
                                dateKey = todayKey,
                                source = "manual_note",
                                note = noteText.ifBlank { null },
                                locationUrl = locationText.ifBlank { null },
                                attachmentFileName = AttachmentListUtil.toStored(pendingAttachments.map { it.name })
                            )
                        )
                        CalendarWriter.refreshToday(context)
                        noteText = ""
                        locationText = ""
                        pendingAttachments = emptyList()
                        refreshDates()
                        if (selectedDate == todayKey) openDate(todayKey)
                    }
                }
            }) { Text("Save entry") }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Text("Calendar", style = MaterialTheme.typography.titleMedium)
            CalendarMonthView(
                loggedDates = dates.toSet(),
                selectedDate = selectedDate,
                onDayClick = { dateKey -> openDate(dateKey) }
            )

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
                                Text("Attachments:", style = MaterialTheme.typography.bodySmall)
                                if (editingAttachments.isEmpty()) {
                                    Text("None", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    editingAttachments.forEach { name ->
                                        var resolvedUri by remember(name) { mutableStateOf<Uri?>(null) }
                                        LaunchedEffect(name) {
                                            resolvedUri = MediaResolveUtil.resolve(context, name)
                                        }
                                        AttachmentPreview(
                                            name = name,
                                            uri = resolvedUri,
                                            onRemove = {
                                                editingAttachments = editingAttachments.filterNot { it == name }
                                            }
                                        )
                                    }
                                }
                                Row {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val updated = entry.copy(
                                                note = editingNoteText.ifBlank { null },
                                                locationUrl = editingLocationText.ifBlank { null },
                                                attachmentFileName = AttachmentListUtil.toStored(editingAttachments)
                                            )
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
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
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
                                        editingNoteText = entry.note ?: ""
                                        editingLocationText = entry.locationUrl ?: ""
                                        editingAttachments = AttachmentListUtil.toList(entry.attachmentFileName)
                                    }) { Text("Edit") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            AppDatabase.getInstance(context).logEntryDao().delete(entry)
                                            openDate(date)
                                            CalendarWriter.refreshToday(context)
                                        }
                                    }) { Text("Delete") }
                                }
                                entry.locationUrl?.let {
                                    Text("📍 $it", style = MaterialTheme.typography.bodySmall)
                                }
                                AttachmentListUtil.toList(entry.attachmentFileName).forEach { name ->
                                    var resolvedUri by remember(name) { mutableStateOf<Uri?>(null) }
                                    LaunchedEffect(name) {
                                        resolvedUri = MediaResolveUtil.resolve(context, name)
                                    }
                                    AttachmentPreview(name = name, uri = resolvedUri)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row {
                    Button(onClick = { sendToCalendar(date, dayLogEntries) }) {
                        Text("Send this day to Calendar")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { openCalendarApp() }) {
                        Text("Open Calendar app")
                    }
                }
                calendarStatus?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
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
    onRemoveBlocked: (String) -> Unit
) {
    val hasUsagePermission = remember { UsageStatsCollector(context).hasUsagePermission() }
    val hasCalendarPermission = remember { CalendarWriter.hasCalendarPermission(context) }

    Column {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))
        Text(if (hasUsagePermission) "Usage access: granted" else "Usage access: not granted")
        if (!hasUsagePermission) {
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                Text("Grant usage access")
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(if (hasCalendarPermission) "Calendar access: granted" else "Calendar access: not granted")

        Spacer(Modifier.height(8.dp))
        Text("Screen-content logging (Accessibility)")
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
            Text("Open Accessibility settings")
        }
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
