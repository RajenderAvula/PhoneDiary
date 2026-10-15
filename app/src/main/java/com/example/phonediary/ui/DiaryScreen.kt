package com.example.phonediary.ui

import android.Manifest
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.phonediary.CrashLogger
import com.example.phonediary.accessibility.BlockedAppsStore
import com.example.phonediary.ai.GeminiClient
import com.example.phonediary.ai.GeminiKeyStore
import com.example.phonediary.ai.GeminiModelStore
import com.example.phonediary.calendar.CalendarWriter
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.AttachmentListUtil as TagListUtil
import com.example.phonediary.data.LocationReminderItem
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.files.AttachmentCleanup
import com.example.phonediary.files.AudioRecorderHelper
import com.example.phonediary.files.BackupHelper
import com.example.phonediary.files.EmailBackupHelper
import com.example.phonediary.files.FileAttachmentHelper
import com.example.phonediary.files.LocationOpenHelper
import com.example.phonediary.files.LocationPinHelper
import com.example.phonediary.files.NoteSaver
import com.example.phonediary.files.NoteShareHelper
import com.example.phonediary.files.RestoreHelper
import com.example.phonediary.files.SavedAttachment
import com.example.phonediary.files.StreamingSpeechHelper
import com.example.phonediary.files.SubNoteManager
import com.example.phonediary.files.UsageCleanup
import com.example.phonediary.files.VideoCaptureHelper
import com.example.phonediary.reminders.GeofenceHelper
import com.example.phonediary.reminders.NoteReminderScheduler
import com.example.phonediary.usage.UsageStatsCollector
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

private enum class DiaryTab { HOME, ASK, SEARCH, SETTINGS }
private enum class FilterMode { ALL, REMINDERS, DUE_DATES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    currentTheme: AppTheme = AppTheme.DARK,
    onThemeChange: (AppTheme) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(DiaryTab.HOME) }

    // The Google page is a native view, so the Search tab is composed only while selected;
    // this holder keeps its page and history between visits.
    val searchState = remember { WebSearchState(context) }
    DisposableEffect(Unit) { onDispose { searchState.destroy() } }

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
                    selected = selectedTab == DiaryTab.ASK,
                    onClick = { selectedTab = DiaryTab.ASK },
                    icon = { Text("✨") },
                    label = { Text("Ask AI") }
                )
                NavigationBarItem(
                    selected = selectedTab == DiaryTab.SEARCH,
                    onClick = { selectedTab = DiaryTab.SEARCH },
                    icon = { Text("🔍") },
                    label = { Text("Search") }
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
            Box(
                modifier = if (selectedTab == DiaryTab.HOME) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()
            ) {
                HomeTabContent()
            }
            Box(
                modifier = if (selectedTab == DiaryTab.ASK) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()
            ) {
                AskAiTab(
                    isVisible = selectedTab == DiaryTab.ASK,
                    onOpenSettings = { selectedTab = DiaryTab.SETTINGS }
                )
            }
            if (selectedTab == DiaryTab.SEARCH) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WebSearchTab(state = searchState)
                }
            }
            Box(
                modifier = if (selectedTab == DiaryTab.SETTINGS) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
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

    // ---- Quick-add composer (the only place a note starts as typed text) ----
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

    var mainLocationReminders by remember { mutableStateOf(listOf<LocationReminderItem>()) }
    var mainGeofenceStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var mainGeofenceErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var calendarStatus by remember { mutableStateOf<String?>(null) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedEntryIds by remember { mutableStateOf(setOf<Long>()) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LogEntry>?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    var filterMode by remember { mutableStateOf(FilterMode.ALL) }
    var filteredResults by remember { mutableStateOf<List<LogEntry>>(emptyList()) }

    // ---- Full-screen editor (every edit goes through it, and autosaves) ----
    var showFullScreenEditor by remember { mutableStateOf(false) }
    var fullScreenEditingEntryId by remember { mutableStateOf<Long?>(null) }
    var fullScreenHighlightQuery by remember { mutableStateOf<String?>(null) }
    var freshNoteId by remember { mutableStateOf<Long?>(null) }   // created by ⛶; removed if closed empty
    var noteTags by remember { mutableStateOf(listOf<String>()) }
    var mainTagInput by remember { mutableStateOf("") }

    var tagFilter by remember { mutableStateOf<String?>(null) }
    var tagFilterResults by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var allKnownTags by remember { mutableStateOf(listOf<String>()) }

    var confirmDeleteEntry by remember { mutableStateOf<LogEntry?>(null) }
    var confirmDeleteSelectedForDate by remember { mutableStateOf<String?>(null) }
    var confirmAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    // ---- Tree / hierarchical view in the day list ----
    var treeShownIds by remember { mutableStateOf(setOf<Long>()) }
    var collapsedNodeKeys by remember { mutableStateOf(setOf<String>()) }
    var treeRefresh by remember { mutableStateOf(0) }
    var locationNoteTarget by remember { mutableStateOf<Pair<LogEntry, LocationReminderItem>?>(null) }

    // ---- OCR image scan ----
    var scanImageUri by remember { mutableStateOf<Uri?>(null) }
    val scanImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) scanImageUri = uri }

    // ---- Speech: 🎤 online one-shot, 🎙️ offline-first with live text ----
    val streamingSpeechHelper = remember { StreamingSpeechHelper(context) }
    var isOneShotListening by remember { mutableStateOf(false) }
    var oneShotError by remember { mutableStateOf<String?>(null) }
    var isStreamingListening by remember { mutableStateOf(false) }
    var streamingPartialText by remember { mutableStateOf("") }
    var streamingError by remember { mutableStateOf<String?>(null) }

    fun startVoiceInput() {
        if (isOneShotListening) {
            streamingSpeechHelper.forceStop { listening -> isOneShotListening = listening }
            return
        }
        oneShotError = null
        streamingSpeechHelper.startOneShot(
            onResult = { finalText ->
                noteText = if (noteText.isBlank()) finalText else "$noteText $finalText"
            },
            onError = { message -> oneShotError = message },
            onListeningStateChanged = { listening -> isOneShotListening = listening }
        )
    }

    fun toggleStreamingSpeech() {
        if (isStreamingListening) {
            streamingSpeechHelper.forceStop { listening -> isStreamingListening = listening }
            return
        }
        streamingError = null
        streamingPartialText = ""
        streamingSpeechHelper.start(
            onPartialResult = { partial -> streamingPartialText = partial },
            onFinalResult = { finalText ->
                noteText = if (noteText.isBlank()) finalText else "$noteText $finalText"
                streamingPartialText = ""
            },
            onError = { message -> streamingError = message; streamingPartialText = "" },
            onListeningStateChanged = { listening -> isStreamingListening = listening }
        )
    }

    DisposableEffect(Unit) {
        onDispose { streamingSpeechHelper.stop() }
    }

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
        mainLocationReminders = emptyList()
        mainGeofenceStatus = emptyMap()
        mainGeofenceErrors = emptyMap()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val distinctUris = uris.distinct()
        if (distinctUris.isNotEmpty()) {
            scope.launch {
                val saved = distinctUris.mapNotNull { uri -> FileAttachmentHelper.copyToDownloads(context, uri) }
                pendingAttachments = (pendingAttachments + saved).distinctBy { it.name }
            }
        }
    }

    val videoCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = pendingVideoUri
            val name = pendingVideoName
            if (uri != null && name != null) {
                pendingAttachments = (pendingAttachments + SavedAttachment(name, uri)).distinctBy { it.name }
            }
        }
        pendingVideoUri = null
        pendingVideoName = null
    }

    fun toggleAudioRecording() {
        if (isRecordingAudio) {
            val saved = audioRecorder.stopRecordingAndSave()
            isRecordingAudio = false
            if (saved != null) pendingAttachments = (pendingAttachments + saved).distinctBy { it.name }
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
        selectionMode = false
        selectedEntryIds = emptySet()
        searchResults = null
        searchQuery = ""
        scope.launch {
            // Sub-notes live inside their parent note, so they aren't listed as separate entries.
            dayLogEntries = AppDatabase.getInstance(context).logEntryDao().getEntriesForDate(dateKey)
                .filter { it.source != SubNoteManager.SOURCE }
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
            val removedNames = mutableSetOf<String>()
            dayLogEntries.filter { it.id in selectedEntryIds }.forEach {
                NoteReminderScheduler.cancelReminder(context, it.id)
                NoteReminderScheduler.cancelDue(context, it.id)
                NoteReminderScheduler.cancelRepeat(context, it.id)
                GeofenceHelper.removeAllGeofencesForEntry(context, it.id, LocationReminderListUtil.fromStored(it.locationReminders))
                CalendarWriter.deleteEntryEvent(context, it.id)
                removedNames += AttachmentCleanup.namesOf(it)
                SubNoteManager.deleteAllInEntry(context, it)
                dao.delete(it)
            }
            AttachmentCleanup.deleteIfUnreferenced(context, removedNames)
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
            GeofenceHelper.removeAllGeofencesForEntry(context, entry.id, LocationReminderListUtil.fromStored(entry.locationReminders))
            CalendarWriter.deleteEntryEvent(context, entry.id)
            SubNoteManager.deleteAllInEntry(context, entry)
            AppDatabase.getInstance(context).logEntryDao().delete(entry)
            AttachmentCleanup.deleteIfUnreferenced(context, AttachmentCleanup.namesOf(entry))
            openDate(dateKey)
            loadAllTags()
        }
    }

    fun openNoteInFullScreen(entryId: Long, highlight: String? = null) {
        freshNoteId = null
        fullScreenEditingEntryId = entryId
        fullScreenHighlightQuery = highlight
        showFullScreenEditor = true
    }

    /** Creates the note immediately (moving whatever is in the quick-add box into it) and opens it. */
    fun openNewNoteFullScreen() {
        scope.launch {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            val now = System.currentTimeMillis()
            val ts = selectedCalendarDateTimeMillis ?: now
            val key = selectedDate ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
            val id = dao.insert(
                LogEntry(
                    timestampMillis = ts,
                    dateKey = key,
                    source = "manual_note",
                    title = noteTitle.ifBlank { null },
                    note = noteText.ifBlank { null },
                    locationUrl = locationText.ifBlank { null },
                    attachmentFileName = AttachmentListUtil.toStored(pendingAttachments.map { it.name }),
                    reminderAtMillis = reminderAtMillis,
                    dueAtMillis = dueAtMillis,
                    repeatRule = if (repeatConfig.type != "NONE") repeatConfig.toStored() else null,
                    lastModifiedMillis = ts,
                    tags = TagListUtil.toStored(noteTags),
                    locationReminders = LocationReminderListUtil.toStored(mainLocationReminders)
                )
            )
            dao.getById(id)?.let { if (!NoteSaver.isEmpty(it)) NoteSaver.armAll(context, it) }
            resetMainEntryFields()
            refreshDates()
            openDate(key)
            openNoteInFullScreen(id)
            freshNoteId = id
        }
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
        Text(
            "Tap ⛶ to create the note and edit it with automatic saving.",
            style = MaterialTheme.typography.labelSmall
        )

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
            IconButton(onClick = { startVoiceInput() }) {
                Text(if (isOneShotListening) "🔴" else "🎤")
            }
            IconButton(onClick = { toggleStreamingSpeech() }) {
                Text(if (isStreamingListening) "🔴" else "🎙️")
            }
            IconButton(onClick = { scanImagePickerLauncher.launch("image/*") }) { Text("📷") }
            IconButton(onClick = { openNewNoteFullScreen() }) { Text("⛶") }
        }

        if (isStreamingListening || streamingPartialText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (streamingPartialText.isNotBlank()) "🎙️ $streamingPartialText" else "🎙️ Listening…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        streamingError?.let {
            Spacer(Modifier.height(4.dp))
            Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        oneShotError?.let {
            Spacer(Modifier.height(4.dp))
            Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(8.dp))
        Text("Tags", style = MaterialTheme.typography.bodySmall)
        if (noteTags.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                noteTags.forEach { tag ->
                    AssistChip(
                        onClick = { confirmAction = "Remove the tag #$tag?" to { noteTags = noteTags.filterNot { it == tag } } },
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
                OutlinedButton(onClick = {
                    confirmAction = "Remove this reminder?" to { reminderAtMillis = null }
                }) { Text("✕") }
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
                OutlinedButton(onClick = {
                    confirmAction = "Remove the due date?" to { dueAtMillis = null }
                }) { Text("✕") }
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
                OutlinedButton(onClick = {
                    confirmAction = "Remove the repeat?" to { repeatConfig = RepeatConfig.NONE }
                }) { Text("✕") }
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
        LocationReminderSection(
           // items = mainLocationReminders,
            parentPath = listOf("📝 " + noteTitle.trim().ifBlank { "New note" }),
            items = mainLocationReminders,
            onItemsChanged = { mainLocationReminders = it },
            registrationStatus = mainGeofenceStatus,
            registrationError = mainGeofenceErrors,
            onToggle = { item ->
                // The note has no id until it's saved, so just clear any stale status.
                mainGeofenceStatus = mainGeofenceStatus - item.id
                mainGeofenceErrors = mainGeofenceErrors - item.id
            }
        )

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
                    onRemove = {
                        confirmAction = "Remove this attachment? Its file is deleted if no note uses it." to {
                            pendingAttachments = pendingAttachments.filterNot { it.name == attachment.name }
                            AttachmentCleanup.launchDeleteIfUnreferenced(context, listOf(attachment.name))
                        }
                    }
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
                                tags = TagListUtil.toStored(noteTags),
                                locationReminders = LocationReminderListUtil.toStored(mainLocationReminders)
                            )
                        )
                        AppDatabase.getInstance(context).logEntryDao().getById(newId)?.let {
                            NoteSaver.armAll(context, it)
                        }

                        resetMainEntryFields()
                        refreshDates()
                        loadAllTags()
                        openDate(targetDateKey)
                    }
                }
            }) { Text("Save entry") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                confirmAction = "Discard what you typed? Attached files are deleted if no note uses them." to {
                    AttachmentCleanup.launchDeleteIfUnreferenced(context, pendingAttachments.map { it.name })
                    SubNoteManager.launchDeleteWithCascade(context, SubNoteManager.idsInItems(mainLocationReminders))
                    resetMainEntryFields()
                }
            }) { Text("Discard") }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectionMode) {
                            val allSelected = selectedEntryIds.size == dayLogEntries.size
                            TextButton(onClick = {
                                selectedEntryIds = if (allSelected) emptySet() else dayLogEntries.map { it.id }.toSet()
                            }) { Text(if (allSelected) "None" else "All") }
                        }
                        TextButton(onClick = { selectionMode = !selectionMode; selectedEntryIds = emptySet() }) {
                            Text(if (selectionMode) "Cancel" else "Select")
                        }
                    }
                }
            }

            DailySummaryCard(dateKey = date)

            if (dayLogEntries.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        treeShownIds = dayLogEntries.map { it.id }.toSet()
                    }) { Text("▾ Show all note trees") }
                    TextButton(onClick = {
                        treeShownIds = emptySet()
                        collapsedNodeKeys = emptySet()
                    }) { Text("▸ Hide all") }
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
                            "${entry.appName} — ${minutes}m" + (entry.note?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                        }
                        "screen_content" -> "${entry.appName}${entry.note?.let { " — $it" } ?: ""}"
                        else -> entry.note ?: entry.source
                    }
                    val titledLabel = entry.title?.takeIf { it.isNotBlank() }?.let { "$it — $displayLabel" } ?: displayLabel

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
                                // Every row (note, usage, screen log) can open its tree.
                                val treeOpen = entry.id in treeShownIds
                                Text(
                                    (if (treeOpen) "▾ " else "▸ ") + "$time  •  $titledLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f).then(
                                        if (!selectionMode) Modifier.clickable {
                                            treeShownIds = if (treeOpen) treeShownIds - entry.id else treeShownIds + entry.id
                                        } else Modifier
                                    )
                                )
                                if (!selectionMode) {
                                    TextButton(onClick = { openNoteInFullScreen(entry.id) }) { Text("⛶") }
                                    TextButton(onClick = { openNoteInFullScreen(entry.id) }) { Text("Edit") }
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
                            val entryLocReminders = LocationReminderListUtil.fromStored(entry.locationReminders)
                            if (entryLocReminders.isNotEmpty()) {
                                val enabledCount = entryLocReminders.count { it.enabled }
                                Text(
                                    "📍🔔 ${entryLocReminders.size} location reminder(s), $enabledCount on",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            AttachmentListUtil.toList(entry.attachmentFileName).distinct().forEach { name ->
                                ResolvingAttachmentPreview(name = name)
                            }
                            if (entry.id in treeShownIds) {
                                NoteTreeView(
                                    entry = entry,
                                    refreshKey = treeRefresh,
                                    collapsed = collapsedNodeKeys,
                                    onToggleBranch = { k ->
                                        collapsedNodeKeys = if (k in collapsedNodeKeys) collapsedNodeKeys - k else collapsedNodeKeys + k
                                    },
                                    onOpen = { node ->
                                        if (node.kind == NodeKind.INFO) {
                                            // read-only detail line
                                        } else if (node.kind == NodeKind.SUB_NOTE) {
                                            node.entryId?.let { openNoteInFullScreen(it) }
                                        } else {
                                            scope.launch {
                                                val parent = AppDatabase.getInstance(context).logEntryDao()
                                                    .getById(node.parentEntryId ?: -1L)
                                                val item = parent?.let {
                                                    LocationReminderListUtil.fromStored(it.locationReminders)
                                                        .firstOrNull { i -> i.id == node.locationId }
                                                }
                                                if (parent != null && item != null) locationNoteTarget = parent to item
                                            }
                                        }
                                    }
                                )
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

    // ---- Location note opened from the tree: autosaves straight into its parent row ----
    locationNoteTarget?.let { (parent, item) ->
        Dialog(
            onDismissRequest = { locationNoteTarget = null; treeRefresh++ },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
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
                  //  isLocationNote = true,
                   // locationNoteName = item.label,
                    isLocationNote = true,
                    locationNoteName = item.label,
                    parentPath = listOf("📝 " + (parent.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled note")),
                    onAutoSave = { r ->
                        scope.launch {
                            val dao = AppDatabase.getInstance(context).logEntryDao()
                            val latest = dao.getById(parent.id) ?: return@launch
                            val newItem = item.copy(
                                title = r.title.trim(),
                                text = r.text,
                                locationUrl = r.locationUrl.trim(),
                                tags = r.tags,
                                attachmentNames = (r.existingAttachmentNames + r.newAttachments.map { it.name }).distinct(),
                                repeatRule = r.repeatRule
                            )
                            val items = LocationReminderListUtil.fromStored(latest.locationReminders)
                                .map { if (it.id == item.id) newItem else it }
                            NoteSaver.update(
                                context, latest,
                                FullScreenNoteResult(
                                    title = latest.title.orEmpty(),
                                    text = latest.note.orEmpty(),
                                    locationUrl = latest.locationUrl.orEmpty(),
                                    tags = AttachmentListUtil.toList(latest.tags),
                                    existingAttachmentNames = AttachmentListUtil.toList(latest.attachmentFileName),
                                    newAttachments = emptyList(),
                                    reminderAtMillis = latest.reminderAtMillis,
                                    dueAtMillis = latest.dueAtMillis,
                                    repeatRule = latest.repeatRule ?: "NONE",
                                    noteDateTimeMillis = null,
                                    locationReminders = items
                                )
                            )
                        }
                    },
                    onSave = { locationNoteTarget = null; treeRefresh++ },
                    onCancel = { locationNoteTarget = null; treeRefresh++ }
                )
            }
        }
    }

    confirmDeleteEntry?.let { entry ->
        ConfirmDeleteDialog(
            message = "Delete this entry, including its sub-notes, attachments and reminders? This cannot be undone.",
            onConfirm = {
                selectedDate?.let { performDeleteEntry(entry, it) }
                confirmDeleteEntry = null
            },
            onDismiss = { confirmDeleteEntry = null }
        )
    }

    confirmDeleteSelectedForDate?.let { dateKey ->
        ConfirmDeleteDialog(
            message = "Delete ${selectedEntryIds.size} selected entries, including their sub-notes, " +
                "attachments and reminders? This cannot be undone.",
            onConfirm = {
                performDeleteSelectedEntries(dateKey)
                confirmDeleteSelectedForDate = null
            },
            onDismiss = { confirmDeleteSelectedForDate = null }
        )
    }

    confirmAction?.let { (message, action) ->
        ConfirmDeleteDialog(
            message = message,
            onConfirm = { confirmAction = null; action() },
            onDismiss = { confirmAction = null }
        )
    }

    // ---- The full-screen editor: every change is saved as you go ----
    val editingId = fullScreenEditingEntryId
    if (showFullScreenEditor && editingId != null) {
        var loadedEntry by remember(editingId) { mutableStateOf<LogEntry?>(null) }

        LaunchedEffect(editingId) {
            loadedEntry = AppDatabase.getInstance(context).logEntryDao().getById(editingId)
        }

        val entry = loadedEntry
        if (entry != null) {
            FullScreenNoteEditor(
                initialTitle = entry.title ?: "",
                initialText = entry.note ?: "",
                initialLocationUrl = entry.locationUrl ?: "",
                initialTags = TagListUtil.toList(entry.tags),
                initialAttachmentNames = AttachmentListUtil.toList(entry.attachmentFileName).distinct(),
                initialReminderAtMillis = entry.reminderAtMillis,
                initialDueAtMillis = entry.dueAtMillis,
                initialRepeatRule = entry.repeatRule ?: "NONE",
                initialNoteDateTimeMillis = entry.timestampMillis,
                initialLocationReminders = LocationReminderListUtil.fromStored(entry.locationReminders),
                existingEntryId = entry.id,
                isSubNote = entry.source == SubNoteManager.SOURCE,
                highlightQuery = fullScreenHighlightQuery,
                onAutoSave = { result ->
                    scope.launch { NoteSaver.update(context, entry, result) }
                },
                onSave = { result ->
                    scope.launch {
                        NoteSaver.update(context, entry, result)
                        // Only a note created by ⛶ and left completely empty is removed.
                        if (freshNoteId == entry.id) NoteSaver.deleteIfEmpty(context, entry.id)
                        freshNoteId = null
                        refreshDates()
                        loadAllTags()
                        selectedDate?.let { openDate(it) }
                        treeRefresh++
                    }
                    showFullScreenEditor = false
                    fullScreenHighlightQuery = null
                },
                onCancel = {
                    showFullScreenEditor = false
                    fullScreenHighlightQuery = null
                    treeRefresh++
                }
            )
        }
    }

    scanImageUri?.let { uri ->
        ImageCropScanner(
            imageUri = uri,
            onExtractedText = { recognizedText ->
                noteText = if (noteText.isBlank()) recognizedText else "$noteText\n$recognizedText"
                scanImageUri = null
            },
            onDismiss = { scanImageUri = null }
        )
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
    val scope = rememberCoroutineScope()

    // Bumped whenever the app resumes or a system settings screen returns.
    var refreshTick by remember { mutableStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
                scope.launch { GeofenceHelper.resyncAllEntries(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { refreshTick++ }

    val hasUsagePermission = remember(refreshTick) { UsageStatsCollector(context).hasUsagePermission() }
    val hasCalendarPermission = remember(refreshTick) { CalendarWriter.hasCalendarPermission(context) }

    val canScheduleExactAlarms = remember(refreshTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }

    var confirmAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    var backupStatus by remember { mutableStateOf<String?>(null) }
    var isBackingUp by remember { mutableStateOf(false) }
    var lastBackupUri by remember { mutableStateOf<Uri?>(null) }
    var lastBackupName by remember { mutableStateOf<String?>(null) }

    var restoreStatus by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var confirmRestoreUri by remember { mutableStateOf<Uri?>(null) }

    var unusedFiles by remember { mutableStateOf<List<String>?>(null) }
    var cleanupStatus by remember { mutableStateOf<String?>(null) }
    var orphanSubNotes by remember { mutableStateOf<Int?>(null) }

    var geminiKeyInput by remember { mutableStateOf("") }
    var geminiKeyTick by remember { mutableStateOf(0) }
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf<String?>(null) }
    var manualModel by remember(geminiKeyTick) { mutableStateOf(GeminiModelStore.getManual(context)) }

    val restorePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) confirmRestoreUri = uri
    }

    Column {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        val lastCrash = remember(refreshTick) { CrashLogger.readLastCrash(context) }
        if (lastCrash != null) {
            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            Text("Last crash log", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            var expanded by remember { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show crash details")
            }
            if (expanded) {
                SelectionContainer {
                    Text(lastCrash, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row {
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Phone Diary crash log")
                        putExtra(Intent.EXTRA_TEXT, lastCrash)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share crash log"))
                }) { Text("Share") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    confirmAction = "Clear the saved crash log?" to {
                        CrashLogger.clearLastCrash(context)
                        refreshTick++
                    }
                }) { Text("Clear") }
            }
        }

        // ---- Notifications ----
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
                settingsLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }) {
                Text("Allow exact alarms")
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = {
            settingsLauncher.launch(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
        }) {
            Text("Check notification settings")
        }
        Text(
            "If reminders still don't fire after allowing both, some phone brands (Xiaomi, Oppo, etc.) require 'Autostart' or battery-saver exemption too.",
            style = MaterialTheme.typography.bodySmall
        )

        // ---- Location reminders ----
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Location reminders", style = MaterialTheme.typography.titleSmall)

        val hasBgLocation = remember(refreshTick) { GeofenceHelper.hasBackgroundLocationPermission(context) }
        Text(
            if (hasBgLocation) "Background location: granted ✓" else "Background location: NOT granted — location reminders won't fire while the app is closed",
            style = MaterialTheme.typography.bodySmall
        )
        if (!hasBgLocation) {
            Button(onClick = {
                settingsLauncher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                )
            }) { Text("Grant background location") }
            Text(
                "Open the app's permission page and set Location to 'Allow all the time'.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        val locationServiceOn = remember(refreshTick) { GeofenceHelper.isLocationServiceEnabled(context) }
        Text(
            if (locationServiceOn) "Device Location: on ✓" else "Device Location: OFF — location reminders can't work until it's turned on",
            style = MaterialTheme.typography.bodySmall
        )
        if (!locationServiceOn) {
            Button(onClick = {
                settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }) { Text("Turn on Location") }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = {
            scope.launch {
                GeofenceHelper.resyncAllEntries(context)
                refreshTick++
            }
        }) { Text("Re-register all location reminders") }

        // ---- Cleanup tools ----
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Cleanup", style = MaterialTheme.typography.titleSmall)

        OutlinedButton(onClick = {
            confirmAction = "Rewrite your calendar events and remove leftover usage events?" to {
                scope.launch {
                    val n = CalendarWriter.refreshAll(context)
                    cleanupStatus = "Updated $n calendar event(s)"
                }
            }
        }) { Text("Fix all calendar events") }

        OutlinedButton(onClick = {
            confirmAction = "Delete duplicate usage and screen-log rows? Rows you added notes to are kept." to {
                scope.launch {
                    val removed = UsageCleanup.run(context)
                    val events = CalendarWriter.refreshAll(context)
                    cleanupStatus = "Removed $removed duplicate usage row(s); tidied $events calendar event(s)"
                }
            }
        }) { Text("Clean up usage clutter") }

        OutlinedButton(onClick = {
            scope.launch {
                val found = AttachmentCleanup.findUnreferencedFiles(context)
                if (found.isEmpty()) {
                    cleanupStatus = "No unused files found"
                    unusedFiles = null
                } else {
                    cleanupStatus = null
                    unusedFiles = found
                }
            }
        }) { Text("Find unused attachment files") }

        OutlinedButton(onClick = {
            scope.launch {
                val n = SubNoteManager.findOrphans(context).size
                if (n == 0) { cleanupStatus = "No unused sub-notes found" } else { orphanSubNotes = n }
            }
        }) { Text("Find unused sub-notes") }

        cleanupStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        unusedFiles?.let { files ->
            ConfirmDeleteDialog(
                message = "${files.size} file(s) in Downloads/PhoneDiary aren't used by any note. " +
                    "Close any note you have open first. Delete them?",
                onConfirm = {
                    unusedFiles = null
                    scope.launch {
                        val deleted = AttachmentCleanup.deleteUnreferencedFiles(context)
                        cleanupStatus = "Deleted $deleted of ${files.size} file(s)"
                    }
                },
                onDismiss = { unusedFiles = null }
            )
        }

        orphanSubNotes?.let { n ->
            ConfirmDeleteDialog(
                message = "$n sub-note(s) aren't linked from any note. Close any note you have open first. Delete them?",
                onConfirm = {
                    orphanSubNotes = null
                    scope.launch {
                        val deleted = SubNoteManager.deleteOrphans(context)
                        cleanupStatus = "Deleted $deleted unused sub-note(s)"
                    }
                },
                onDismiss = { orphanSubNotes = null }
            )
        }

        // ---- Gemini ----
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Gemini (daily summary & Ask AI)", style = MaterialTheme.typography.titleSmall)
        val keyHint = remember(geminiKeyTick) { GeminiKeyStore.hint(context) }
        Text(
            if (keyHint != null) "API key saved ($keyHint)" else "No API key saved",
            style = MaterialTheme.typography.bodySmall
        )
        val modelInUse = remember(geminiKeyTick) { GeminiModelStore.get(context) }
        Text(
            if (modelInUse != null) "Last working model: $modelInUse" else "Model: chosen automatically on first use",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = geminiKeyInput,
            onValueChange = { geminiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Paste your Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Row {
            Button(
                enabled = geminiKeyInput.isNotBlank(),
                onClick = {
                    GeminiKeyStore.save(context, geminiKeyInput)
                    GeminiModelStore.clear(context)
                    geminiKeyInput = ""
                    geminiKeyTick++
                }
            ) { Text("Save key") }
            Spacer(Modifier.width(8.dp))
            if (keyHint != null) {
                OutlinedButton(onClick = {
                    confirmAction = "Remove the Gemini API key from this phone?" to {
                        GeminiKeyStore.clear(context)
                        GeminiModelStore.clear(context)
                        modelList = emptyList()
                        geminiKeyTick++
                    }
                }) { Text("Remove key") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Gemini model", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(
                    onClick = { modelMenuOpen = true },
                    enabled = modelList.isNotEmpty() || manualModel != null
                ) {
                    Text(manualModel ?: "Auto (recommended)")
                }
                DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Auto (recommended)") },
                        onClick = {
                            GeminiModelStore.setManual(context, null)
                            manualModel = null
                            modelMenuOpen = false
                        }
                    )
                    modelList.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                GeminiModelStore.setManual(context, name)
                                manualModel = name
                                modelMenuOpen = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = GeminiKeyStore.hasKey(context),
                onClick = {
                    modelStatus = "Loading…"
                    scope.launch {
                        GeminiClient.listAvailableModels(GeminiKeyStore.get(context)!!)
                            .onSuccess { modelList = it; modelStatus = "${it.size} model(s) available" }
                            .onFailure { modelStatus = it.message }
                    }
                }
            ) { Text("Load models") }
        }
        modelStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        Text(
            if (manualModel != null) "Using only \"$manualModel\" — no automatic switching."
            else "Auto picks a working Flash model and switches if one stalls.",
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            "Used only when you tap Generate or ask a question in Ask AI. The notes and app usage needed to answer " +
                "(and web search queries, if 🌐 Web is on) are sent to Google, so it needs internet. On Google's free " +
                "tier, submitted content may be used to improve their products. Check Google's terms before sending " +
                "anything private.",
            style = MaterialTheme.typography.labelSmall
        )

        // ---- Theme ----
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = currentTheme == AppTheme.DARK, onClick = { onThemeChange(AppTheme.DARK) }, label = { Text("Dark") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = currentTheme == AppTheme.COLORFUL, onClick = { onThemeChange(AppTheme.COLORFUL) }, label = { Text("Colourful") })
        }

        // ---- Backup & Restore ----
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

        // ---- Usage access / Calendar / Accessibility ----
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        Text(if (hasUsagePermission) "Usage access: granted" else "Usage access: not granted")
        if (!hasUsagePermission) {
            Button(onClick = {
                settingsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) { Text("Grant usage access") }
        }

        Spacer(Modifier.height(8.dp))
        Text(if (hasCalendarPermission) "Calendar access: granted" else "Calendar access: not granted")

        Spacer(Modifier.height(8.dp))
        Text("Screen-content logging (Accessibility)")
        Button(onClick = {
            settingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("Open Accessibility settings") }
        Text("Find 'Phone Diary' in the list and turn it on there.", style = MaterialTheme.typography.bodySmall)

        // ---- Blocked apps ----
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
                TextButton(onClick = {
                    confirmAction = "Stop blocking $pkg? It will be logged again." to { onRemoveBlocked(pkg) }
                }) { Text("Remove") }
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

        confirmAction?.let { (message, action) ->
            ConfirmDeleteDialog(
                message = message,
                onConfirm = { confirmAction = null; action() },
                onDismiss = { confirmAction = null }
            )
        }
    }
}
