package com.example.phonediary.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
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
import com.example.phonediary.data.AttachmentListUtil as TagListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.files.AudioRecorderHelper
import com.example.phonediary.files.BackupHelper
import com.example.phonediary.files.EmailBackupHelper
import com.example.phonediary.files.FileAttachmentHelper
import com.example.phonediary.files.LocationOpenHelper
import com.example.phonediary.files.LocationPinHelper
import com.example.phonediary.files.MediaResolveUtil
import com.example.phonediary.files.NoteShareHelper
import com.example.phonediary.files.RestoreHelper
import com.example.phonediary.files.SavedAttachment
import com.example.phonediary.files.VideoCaptureHelper
import com.example.phonediary.reminders.NoteReminderScheduler
import com.example.phonediary.usage.UsageStatsCollector
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

private enum class DiaryTab { HOME, SETTINGS }
private enum class FilterMode { ALL, REMINDERS, DUE_DATES }

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
        topBar = { TopAppBar(title = { Text("Phone Diary") }) },
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

    val highlightBg = MaterialTheme.colorScheme.primary
    val highlightFg = MaterialTheme.colorScheme.onPrimary

    var dates by remember { mutableStateOf(listOf<String>()) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var dayLogEntries by remember { mutableStateOf(listOf<LogEntry>()) }

    var noteTitle by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var locationText by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<SavedAttachment>()) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingVideoName by remember { mutableStateOf<String?>(null) }

    var selectedCalendarDateTimeMillis by remember { mutableStateOf<Long?>(null) }

    var reminderAtMillis by remember { mutableStateOf<Long?>(null) }
    var dueAtMillis by remember { mutableStateOf<Long?>(null) }
    var repeatConfig by remember { mutableStateOf(RepeatConfig.NONE) }
    var showRepeatDialog by remember { mutableStateOf(false) }

    var calendarStatus by remember { mutableStateOf<String?>(null) }

    var editingEntryId by remember { mutableStateOf<Long?>(null) }
    var editingTitle by remember { mutableStateOf("") }
    var editingNoteText by remember { mutableStateOf("") }
    var editingLocationText by remember { mutableStateOf("") }
    var editingAttachments by remember { mutableStateOf(listOf<String>()) }
    var editingReminderAtMillis by remember { mutableStateOf<Long?>(null) }
    var editingDueAtMillis by remember { mutableStateOf<Long?>(null) }
    var editingRepeatConfig by remember { mutableStateOf(RepeatConfig.NONE) }
    var showEditRepeatDialog by remember { mutableStateOf(false) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedEntryIds by remember { mutableStateOf(setOf<Long>()) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LogEntry>?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    var filterMode by remember { mutableStateOf(FilterMode.ALL) }
    var filteredResults by remember { mutableStateOf<List<LogEntry>>(emptyList()) }

    var showFullScreenEditor by remember { mutableStateOf(false) }
    var fullScreenEditingEntryId by remember { mutableStateOf<Long?>(null) }
    var fullScreenHighlightQuery by remember { mutableStateOf<String?>(null) }
    var noteTags by remember { mutableStateOf(listOf<String>()) }
    var mainTagInput by remember { mutableStateOf("") }

    var tagFilter by remember { mutableStateOf<String?>(null) }
    var tagFilterResults by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var allKnownTags by remember { mutableStateOf(listOf<String>()) }

    var confirmDeleteEntry by remember { mutableStateOf<LogEntry?>(null) }
    var confirmDeleteSelectedForDate by remember { mutableStateOf<String?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = RequestPermission()
    ) { }

    fun pinCurrentLocation(onResult: (String) -> Unit) {
        if (!LocationPinHelper.hasLocationPermission(context)) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        scope.launch {
            val url = LocationPinHelper.getCurrentLocationUrl(context)
            if (url != null) onResult(url)
        }
    }

    fun loadAllTags() {
        scope.launch {
            val raw = AppDatabase.getInstance(context).logEntryDao().getAllTagStrings()
            allKnownTags = raw.flatMap { TagListUtil.toList(it) }.distinct().sorted()
        }
    }

    fun runTagFilter(tag: String?) {
        tagFilter = tag
        if (tag == null) {
            tagFilterResults = emptyList()
            return
        }
        scope.launch {
            tagFilterResults = AppDatabase.getInstance(context).logEntryDao().getEntriesByTag(tag)
        }
    }

    fun addMainTag(rawTag: String) {
        val cleaned = rawTag.trim().removePrefix("#")
        if (cleaned.isNotBlank() && cleaned !in noteTags) {
            noteTags = noteTags + cleaned
        }
        mainTagInput = ""
    }

    fun resetMainEntryFields() {
        noteTitle = ""
        noteText = ""
        locationText = ""
        pendingAttachments = emptyList()
        reminderAtMillis = null
        dueAtMillis = null
        repeatConfig = RepeatConfig.NONE
        selectedCalendarDateTimeMillis = null
        noteTags = emptyList()
        mainTagInput = ""
    }

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

    fun onCalendarDayClicked(dateKey: String) {
        openDate(dateKey)

        val parts = dateKey.split("-")
        if (parts.size != 3) return
        val year = parts[0].toIntOrNull() ?: return
        val month = parts[1].toIntOrNull() ?: return
        val day = parts[2].toIntOrNull() ?: return

        val now = Calendar.getInstance()
        TimePickerDialog(
            context,
            { _, hour, minute ->
                val combined = Calendar.getInstance().apply {
                    set(year, month - 1, day, hour, minute, 0)
                }
                selectedCalendarDateTimeMillis = combined.timeInMillis
            },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            false
        ).show()
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

    fun runFilter(mode: FilterMode) {
        filterMode = mode
        scope.launch {
            filteredResults = when (mode) {
                FilterMode.ALL -> emptyList()
                FilterMode.REMINDERS -> AppDatabase.getInstance(context).logEntryDao().getEntriesWithReminders()
                FilterMode.DUE_DATES -> AppDatabase.getInstance(context).logEntryDao().getEntriesWithDueDates()
            }
        }
    }

    fun sendToCalendar(dateKey: String) {
        scope.launch {
            val wrote = CalendarWriter.refreshDate(context, dateKey)
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

    fun performDeleteSelectedEntries(dateKey: String) {
        scope.launch {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            dayLogEntries.filter { it.id in selectedEntryIds }.forEach {
                NoteReminderScheduler.cancelReminder(context, it.id)
                NoteReminderScheduler.cancelDue(context, it.id)
                NoteReminderScheduler.cancelRepeat(context, it.id)
                CalendarWriter.deleteEntryEvent(context, it.id)
                dao.delete(it)
            }
            selectedEntryIds = emptySet()
            selectionMode = false
            openDate(dateKey)
        }
    }

    fun performDeleteEntry(entry: LogEntry, dateKey: String) {
        scope.launch {
            NoteReminderScheduler.cancelReminder(context, entry.id)
            NoteReminderScheduler.cancelDue(context, entry.id)
            NoteReminderScheduler.cancelRepeat(context, entry.id)
            CalendarWriter.deleteEntryEvent(context, entry.id)
            AppDatabase.getInstance(context).logEntryDao().delete(entry)
            openDate(dateKey)
            loadAllTags()
        }
    }

    fun openNoteInFullScreen(entryId: Long, highlight: String? = null) {
        fullScreenEditingEntryId = entryId
        fullScreenHighlightQuery = highlight
        showFullScreenEditor = true
    }

    LaunchedEffect(Unit) { refreshDates(); loadAllTags() }

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
            placeholder = { Text("Search title, notes, apps, locations, files…") },
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
                val label = entry.title?.takeIf { it.isNotBlank() } ?: entry.note ?: entry.appName ?: entry.source
                val highlighted = remember(label, searchQuery) {
                    buildHighlightedString(label, searchQuery, highlightBg, highlightFg)
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { openNoteInFullScreen(entry.id, searchQuery) }
                        .padding(vertical = 6.dp)
                ) {
                    Column {
                        Text(entryDateTime, style = MaterialTheme.typography.bodySmall)
                        Text(highlighted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Divider()
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text("Filter", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = filterMode == FilterMode.ALL, onClick = { runFilter(FilterMode.ALL) }, label = { Text("All") })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = filterMode == FilterMode.REMINDERS, onClick = { runFilter(FilterMode.REMINDERS) }, label = { Text("⏰ Reminders") })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = filterMode == FilterMode.DUE_DATES, onClick = { runFilter(FilterMode.DUE_DATES) }, label = { Text("📅 Due dates") })
        }

        if (filterMode != FilterMode.ALL) {
            Spacer(Modifier.height(8.dp))
            if (filteredResults.isEmpty()) {
                Text("No entries with this set.", style = MaterialTheme.typography.bodySmall)
            } else {
                filteredResults.forEach { entry ->
                    val relevantTime = if (filterMode == FilterMode.REMINDERS) entry.reminderAtMillis else entry.dueAtMillis
                    val label = entry.title?.takeIf { it.isNotBlank() } ?: entry.note ?: entry.appName ?: entry.source
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { openNoteInFullScreen(entry.id) }.padding(vertical = 6.dp)
                    ) {
                        Column {
                            Text(relevantTime?.let { dateTimeFormat.format(it) } ?: "", style = MaterialTheme.typography.bodySmall)
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Divider()
                }
            }
        }

        if (allKnownTags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Tags", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = tagFilter == null, onClick = { runTagFilter(null) }, label = { Text("All") })
                Spacer(Modifier.width(6.dp))
                allKnownTags.forEach { tag ->
                    FilterChip(
                        selected = tagFilter == tag,
                        onClick = { runTagFilter(tag) },
                        label = { Text("#$tag") },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
        }

        if (tagFilter != null) {
            Spacer(Modifier.height(8.dp))
            if (tagFilterResults.isEmpty()) {
                Text("No entries with this tag.", style = MaterialTheme.typography.bodySmall)
            } else {
                tagFilterResults.forEach { entry ->
                    val label = entry.title?.takeIf { it.isNotBlank() } ?: entry.note ?: entry.appName ?: entry.source
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { openNoteInFullScreen(entry.id) }.padding(vertical = 6.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                    Divider()
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))

        // ---- 2. Calendar ----
        Text("Calendar", style = MaterialTheme.typography.titleMedium)
        CalendarMonthView(
            loggedDates = dates.toSet(),
            selectedDate = selectedDate,
            onDayClick = { dateKey -> onCalendarDayClicked(dateKey) }
        )

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))

        // ---- 3. Add note ----
        Text("Add a note for today", style = MaterialTheme.typography.titleMedium)

        selectedCalendarDateTimeMillis?.let { picked ->
            Text(
                "Selected: ${dateTimeFormat.format(picked)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
        }

        OutlinedTextField(
            value = noteTitle,
            onValueChange = { noteTitle = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Note title (optional) — shown in Calendar & searchable") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

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
            IconButton(onClick = {
                fullScreenEditingEntryId = null
                fullScreenHighlightQuery = null
                showFullScreenEditor = true
            }) { Text("⛶") }
        }

        Spacer(Modifier.height(8.dp))
        Text("Tags", style = MaterialTheme.typography.bodySmall)
        if (noteTags.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                noteTags.forEach { tag ->
                    AssistChip(
                        onClick = { noteTags = noteTags.filterNot { it == tag } },
                        label = { Text("#$tag ✕") },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = mainTagInput,
                onValueChange = { mainTagInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add tag…") },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { addMainTag(mainTagInput) }) { Text("Add") }
        }
        val matchingTagSuggestions = remember(mainTagInput, allKnownTags, noteTags) {
            if (mainTagInput.isBlank()) emptyList()
            else allKnownTags.filter {
                it.contains(mainTagInput, ignoreCase = true) && it !in noteTags
            }
        }
        if (matchingTagSuggestions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                matchingTagSuggestions.take(5).forEach { suggestion ->
                    AssistChip(
                        onClick = { addMainTag(suggestion) },
                        label = { Text("#$suggestion") },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = locationText,
                onValueChange = { locationText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Location URL (optional)") },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { pinCurrentLocation { url -> locationText = url } }) { Text("📍") }
            if (locationText.isNotBlank()) {
                IconButton(onClick = { LocationOpenHelper.open(context, locationText) }) { Text("🔗") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Schedule (optional)", style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { DateTimePickerUtil.pick(context) { picked -> reminderAtMillis = picked } }
            ) {
                Text(reminderAtMillis?.let { "⏰ ${dateTimeFormat.format(it)}" } ?: "⏰ Reminder")
            }
            if (reminderAtMillis != null) {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { reminderAtMillis = null }) { Text("✕") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { DateTimePickerUtil.pick(context) { picked -> dueAtMillis = picked } }
            ) {
                Text(dueAtMillis?.let { "📅 ${dateTimeFormat.format(it)}" } ?: "📅 Due date")
            }
            if (dueAtMillis != null) {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { dueAtMillis = null }) { Text("✕") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { showRepeatDialog = true }
            ) {
                Text(if (repeatConfig.type != "NONE") "🔁 ${repeatDisplayLabel2(repeatConfig.toStored())}" else "🔁 Repeat")
            }
            if (repeatConfig.type != "NONE") {
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { repeatConfig = RepeatConfig.NONE }) { Text("✕") }
            }
        }
        if (showRepeatDialog) {
            RepeatPickerDialog(
                initial = repeatConfig,
                onConfirm = { config -> repeatConfig = config; showRepeatDialog = false },
                onDismiss = { showRepeatDialog = false }
            )
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
        Row {
            Button(onClick = {
                if (noteTitle.isNotBlank() || noteText.isNotBlank() || locationText.isNotBlank() || pendingAttachments.isNotEmpty()) {
                    scope.launch {
                        val nowMillis = System.currentTimeMillis()
                        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(nowMillis)
                        val targetDateKey = selectedDate ?: todayKey
                        val entryTimestamp = selectedCalendarDateTimeMillis ?: nowMillis

                        val newId = AppDatabase.getInstance(context).logEntryDao().insert(
                            LogEntry(
                                timestampMillis = entryTimestamp,
                                dateKey = targetDateKey,
                                source = "manual_note",
                                title = noteTitle.ifBlank { null },
                                note = noteText.ifBlank { null },
                                locationUrl = locationText.ifBlank { null },
                                attachmentFileName = AttachmentListUtil.toStored(pendingAttachments.map { it.name }),
                                reminderAtMillis = reminderAtMillis,
                                dueAtMillis = dueAtMillis,
                                repeatRule = if (repeatConfig.type != "NONE") repeatConfig.toStored() else null,
                                lastModifiedMillis = entryTimestamp,
                                tags = TagListUtil.toStored(noteTags)
                            )
                        )
                        reminderAtMillis?.let { NoteReminderScheduler.scheduleReminder(context, newId, it) }
                        dueAtMillis?.let { NoteReminderScheduler.scheduleDue(context, newId, it) }
                        if (repeatConfig.type != "NONE") {
                            RepeatScheduling.firstTrigger(repeatConfig.toStored())?.let {
                                NoteReminderScheduler.scheduleRepeat(context, newId, it)
                            }
                        }

                        AppDatabase.getInstance(context).logEntryDao().getById(newId)?.let {
                            CalendarWriter.refreshEntry(context, it)
                        }

                        resetMainEntryFields()
                        refreshDates()
                        loadAllTags()
                        openDate(targetDateKey)
                    }
                }
            }) { Text("Save entry") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { resetMainEntryFields() }) { Text("Cancel") }
        }

        // ---- 4. Day details ----
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
                    val titledLabel = entry.title?.takeIf { it.isNotBlank() }?.let { "$it — $displayLabel" } ?: displayLabel

                    if (editingEntryId == entry.id) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            OutlinedTextField(
                                value = editingTitle,
                                onValueChange = { editingTitle = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Note title") },
                                singleLine = true
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = editingNoteText,
                                onValueChange = { editingNoteText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Note") },
                                minLines = 1,
                                maxLines = 6
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = editingLocationText,
                                    onValueChange = { editingLocationText = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Location URL") },
                                    singleLine = true
                                )
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { pinCurrentLocation { url -> editingLocationText = url } }) { Text("📍") }
                                if (editingLocationText.isNotBlank()) {
                                    IconButton(onClick = { LocationOpenHelper.open(context, editingLocationText) }) { Text("🔗") }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { DateTimePickerUtil.pick(context) { picked -> editingReminderAtMillis = picked } }
                                ) {
                                    Text(editingReminderAtMillis?.let { "⏰ ${dateTimeFormat.format(it)}" } ?: "⏰ Reminder")
                                }
                                if (editingReminderAtMillis != null) {
                                    Spacer(Modifier.width(6.dp))
                                    OutlinedButton(onClick = { editingReminderAtMillis = null }) { Text("✕") }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { DateTimePickerUtil.pick(context) { picked -> editingDueAtMillis = picked } }
                                ) {
                                    Text(editingDueAtMillis?.let { "📅 ${dateTimeFormat.format(it)}" } ?: "📅 Due date")
                                }
                                if (editingDueAtMillis != null) {
                                    Spacer(Modifier.width(6.dp))
                                    OutlinedButton(onClick = { editingDueAtMillis = null }) { Text("✕") }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { showEditRepeatDialog = true }
                                ) {
                                    Text(if (editingRepeatConfig.type != "NONE") "🔁 ${repeatDisplayLabel2(editingRepeatConfig.toStored())}" else "🔁 Repeat")
                                }
                                if (editingRepeatConfig.type != "NONE") {
                                    Spacer(Modifier.width(6.dp))
                                    OutlinedButton(onClick = { editingRepeatConfig = RepeatConfig.NONE }) { Text("✕") }
                                }
                            }
                            if (showEditRepeatDialog) {
                                RepeatPickerDialog(
                                    initial = editingRepeatConfig,
                                    onConfirm = { config -> editingRepeatConfig = config; showEditRepeatDialog = false },
                                    onDismiss = { showEditRepeatDialog = false }
                                )
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
                                            title = editingTitle.ifBlank { null },
                                            note = editingNoteText.ifBlank { null },
                                            locationUrl = editingLocationText.ifBlank { null },
                                            attachmentFileName = AttachmentListUtil.toStored(editingAttachments),
                                            reminderAtMillis = editingReminderAtMillis,
                                            dueAtMillis = editingDueAtMillis,
                                            repeatRule = if (editingRepeatConfig.type != "NONE") editingRepeatConfig.toStored() else null,
                                            lastModifiedMillis = System.currentTimeMillis()
                                        )
                                        AppDatabase.getInstance(context).logEntryDao().update(updated)
                                        NoteReminderScheduler.cancelReminder(context, entry.id)
                                        NoteReminderScheduler.cancelDue(context, entry.id)
                                        NoteReminderScheduler.cancelRepeat(context, entry.id)
                                        editingReminderAtMillis?.let { NoteReminderScheduler.scheduleReminder(context, entry.id, it) }
                                        editingDueAtMillis?.let { NoteReminderScheduler.scheduleDue(context, entry.id, it) }
                                        if (editingRepeatConfig.type != "NONE") {
                                            RepeatScheduling.firstTrigger(editingRepeatConfig.toStored())?.let {
                                                NoteReminderScheduler.scheduleRepeat(context, entry.id, it)
                                            }
                                        }
                                        CalendarWriter.refreshEntry(context, updated)
                                        editingEntryId = null
                                        openDate(date)
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
                                    Text("$time  •  $titledLabel", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    if (!selectionMode) {
                                        TextButton(onClick = { openNoteInFullScreen(entry.id) }) { Text("⛶") }
                                        TextButton(onClick = {
                                            editingEntryId = entry.id
                                            editingTitle = entry.title ?: ""
                                            editingNoteText = entry.note ?: ""
                                            editingLocationText = entry.locationUrl ?: ""
                                            editingAttachments = AttachmentListUtil.toList(entry.attachmentFileName)
                                            editingReminderAtMillis = entry.reminderAtMillis
                                            editingDueAtMillis = entry.dueAtMillis
                                            editingRepeatConfig = RepeatConfig.fromStored(entry.repeatRule)
                                        }) { Text("Edit") }
                                        TextButton(onClick = { NoteShareHelper.shareNote(context, entry) }) { Text("📤") }
                                        TextButton(onClick = { confirmDeleteEntry = entry }) { Text("Delete") }
                                    }
                                }
                                entry.locationUrl?.let {
                                    Text(
                                        "📍 $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { LocationOpenHelper.open(context, it) }
                                    )
                                }
                                val entryTagList = TagListUtil.toList(entry.tags)
                                if (entryTagList.isNotEmpty()) {
                                    Text(entryTagList.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodySmall)
                                }
                                entry.reminderAtMillis?.let {
                                    Text("⏰ ${dateTimeFormat.format(it)}", style = MaterialTheme.typography.bodySmall)
                                }
                                entry.dueAtMillis?.let { Text("📅 Due ${dateTimeFormat.format(it)}", style = MaterialTheme.typography.bodySmall) }
                                entry.repeatRule?.takeIf { it != "NONE" }?.let {
                                    Text("🔁 ${repeatDisplayLabel2(it)}", style = MaterialTheme.typography.bodySmall)
                                }
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
                    Button(onClick = { confirmDeleteSelectedForDate = date }) {
                        Text("Delete selected (${selectedEntryIds.size})")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = { sendToCalendar(date) }) { Text("Send this day to Calendar") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { openCalendarApp() }) { Text("Open Calendar") }
            }
            calendarStatus?.let { status -> Text(status, style = MaterialTheme.typography.bodySmall) }
        }
    }

    confirmDeleteEntry?.let { entry ->
        ConfirmDeleteDialog(
            message = "Delete this entry? This cannot be undone.",
            onConfirm = {
                selectedDate?.let { performDeleteEntry(entry, it) }
                confirmDeleteEntry = null
            },
            onDismiss = { confirmDeleteEntry = null }
        )
    }

    confirmDeleteSelectedForDate?.let { dateKey ->
        ConfirmDeleteDialog(
            message = "Delete ${selectedEntryIds.size} selected entries? This cannot be undone.",
            onConfirm = {
                performDeleteSelectedEntries(dateKey)
                confirmDeleteSelectedForDate = null
            },
            onDismiss = { confirmDeleteSelectedForDate = null }
        )
    }

    if (showFullScreenEditor) {
        val editingId = fullScreenEditingEntryId
        var loadedEntry by remember(editingId) { mutableStateOf<LogEntry?>(null) }
        var isLoaded by remember(editingId) { mutableStateOf(editingId == null) }

        LaunchedEffect(editingId) {
            if (editingId != null) {
                loadedEntry = AppDatabase.getInstance(context).logEntryDao().getById(editingId)
                isLoaded = true
            }
        }

        if (isLoaded) {
            val initialTitle = if (editingId == null) noteTitle else (loadedEntry?.title ?: "")
            val initialText = if (editingId == null) noteText else (loadedEntry?.note ?: "")
            val initialLocation = if (editingId == null) locationText else (loadedEntry?.locationUrl ?: "")
            val initialTags = if (editingId == null) noteTags else TagListUtil.toList(loadedEntry?.tags)
            val initialAttachments = if (editingId == null) emptyList() else AttachmentListUtil.toList(loadedEntry?.attachmentFileName)
            val initialReminder = if (editingId == null) reminderAtMillis else loadedEntry?.reminderAtMillis
            val initialDue = if (editingId == null) dueAtMillis else loadedEntry?.dueAtMillis
            val initialRepeat = if (editingId == null) repeatConfig.toStored() else (loadedEntry?.repeatRule ?: "NONE")

            FullScreenNoteEditor(
                initialTitle = initialTitle,
                initialText = initialText,
                initialLocationUrl = initialLocation,
                initialTags = initialTags,
                initialAttachmentNames = initialAttachments,
                initialReminderAtMillis = initialReminder,
                initialDueAtMillis = initialDue,
                initialRepeatRule = initialRepeat,
                highlightQuery = if (editingId != null) fullScreenHighlightQuery else null,
                onSave = { result ->
                    if (editingId == null) {
                        noteTitle = result.title
                        noteText = result.text
                        locationText = result.locationUrl
                        noteTags = result.tags
                        pendingAttachments = pendingAttachments + result.newAttachments
                        reminderAtMillis = result.reminderAtMillis
                        dueAtMillis = result.dueAtMillis
                        repeatConfig = RepeatConfig.fromStored(result.repeatRule)
                    } else {
                        scope.launch {
                            loadedEntry?.let { entry ->
                                val finalAttachments = result.existingAttachmentNames + result.newAttachments.map { it.name }
                                val updated = entry.copy(
                                    title = result.title.ifBlank { null },
                                    note = result.text.ifBlank { null },
                                    locationUrl = result.locationUrl.ifBlank { null },
                                    tags = TagListUtil.toStored(result.tags),
                                    attachmentFileName = AttachmentListUtil.toStored(finalAttachments),
                                    reminderAtMillis = result.reminderAtMillis,
                                    dueAtMillis = result.dueAtMillis,
                                    repeatRule = result.repeatRule,
                                    lastModifiedMillis = System.currentTimeMillis()
                                )
                                AppDatabase.getInstance(context).logEntryDao().update(updated)
                                NoteReminderScheduler.cancelReminder(context, entry.id)
                                NoteReminderScheduler.cancelDue(context, entry.id)
                                NoteReminderScheduler.cancelRepeat(context, entry.id)
                                result.reminderAtMillis?.let { NoteReminderScheduler.scheduleReminder(context, entry.id, it) }
                                result.dueAtMillis?.let { NoteReminderScheduler.scheduleDue(context, entry.id, it) }
                                if (result.repeatRule != "NONE") {
                                    RepeatScheduling.firstTrigger(result.repeatRule)?.let {
                                        NoteReminderScheduler.scheduleRepeat(context, entry.id, it)
                                    }
                                }
                                CalendarWriter.refreshEntry(context, updated)
                                loadAllTags()
                                selectedDate?.let { openDate(it) }
                            }
                        }
                    }
                    showFullScreenEditor = false
                    fullScreenHighlightQuery = null
                },
                onCancel = {
                    showFullScreenEditor = false
                    fullScreenHighlightQuery = null
                }
            )
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

    val canScheduleExactAlarms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }

    var backupStatus by remember { mutableStateOf<String?>(null) }
    var isBackingUp by remember { mutableStateOf(false) }
    var lastBackupUri by remember { mutableStateOf<Uri?>(null) }
    var lastBackupName by remember { mutableStateOf<String?>(null) }

    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var confirmRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val restorePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) confirmRestoreUri = uri
    }

    Column {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Notifications", style = MaterialTheme.typography.titleSmall)
        Text(
            if (canScheduleExactAlarms) "Exact alarms: allowed ✓" else "Exact alarms: NOT allowed — reminders may not fire on time",
            style = MaterialTheme.typography.bodySmall
        )
        if (!canScheduleExactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }) {
                Text("Allow exact alarms")
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
        }) {
            Text("Check notification settings")
        }
        Text(
            "If reminders still don't fire after allowing both, some phone brands (Xiaomi, Oppo, etc.) require 'Autostart' or battery-saver exemption too.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))
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

        confirmRestoreUri?.let { uri ->
            ConfirmDeleteDialog(
                message = "Restoring will add all entries from this backup into your current data. Continue?",
                onConfirm = {
                    confirmRestoreUri = null
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
                },
                onDismiss = { confirmRestoreUri = null }
            )
        }

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
