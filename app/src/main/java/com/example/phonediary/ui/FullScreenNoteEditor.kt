package com.example.phonediary.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LocationReminderItem
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.files.AttachmentCleanup
import com.example.phonediary.files.AudioRecorderHelper
import com.example.phonediary.files.FileAttachmentHelper
import com.example.phonediary.files.LocationOpenHelper
import com.example.phonediary.files.LocationPinHelper
import com.example.phonediary.files.MediaResolveUtil
import com.example.phonediary.files.NotePrintHelper
import com.example.phonediary.files.NoteSaver
import com.example.phonediary.files.NoteShareHelper
import com.example.phonediary.files.SavedAttachment
import com.example.phonediary.files.StreamingSpeechHelper
import com.example.phonediary.files.SubNoteManager
import com.example.phonediary.files.TextScanHelper
import com.example.phonediary.files.TimeStampManager
import com.example.phonediary.files.VideoCaptureHelper
import com.example.phonediary.reminders.GeofenceStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

data class FullScreenNoteResult(
    val title: String,
    val text: String,
    val locationUrl: String,
    val tags: List<String>,
    val existingAttachmentNames: List<String>,
    val newAttachments: List<SavedAttachment>,
    val reminderAtMillis: Long?,
    val dueAtMillis: Long?,
    val repeatRule: String,
    val noteDateTimeMillis: Long?,
    val locationReminders: List<LocationReminderItem>
)

/** Which sub-note the nested editor is showing. */
private class SubEditorTarget(val entry: LogEntry)

/** The timestamp stamp being edited: its current marker label plus the time/format to start from. */
private class StampTarget(val oldLabel: String, val millis: Long, val format: String)

/**
 * Full-screen editor. There is no draft stage: every change is saved through
 * [onAutoSave] about 0.7 s after you stop changing something, and again when
 * the editor closes. [onSave] (Done / ✕ Close / Back) just closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenNoteEditor(
    initialTitle: String,
    initialText: String,
    initialLocationUrl: String,
    initialTags: List<String>,
    initialAttachmentNames: List<String>,
    initialReminderAtMillis: Long?,
    initialDueAtMillis: Long?,
    initialRepeatRule: String,
    initialNoteDateTimeMillis: Long?,
    initialLocationReminders: List<LocationReminderItem> = emptyList(),
    existingEntryId: Long? = null,
    /** Editing the note that belongs to one location reminder (Repeat means "repeat while I'm inside"). */
    isLocationNote: Boolean = false,
    locationNoteName: String = "",
    /** Editing a sub-note (only changes the title bar). */
    isSubNote: Boolean = false,
    highlightQuery: String? = null,
    onAutoSave: (FullScreenNoteResult) -> Unit = {},
    onSave: (FullScreenNoteResult) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateTimeFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    val audioRecorder = remember { AudioRecorderHelper(context) }
    val streamingSpeechHelper = remember { StreamingSpeechHelper(context) }

    var title by remember { mutableStateOf(initialTitle) }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(initialText.length)))
    }

    var locationUrl by remember { mutableStateOf(initialLocationUrl) }
    var tagInput by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(initialTags) }

    // The attachment list is the single source of truth: each file appears once.
    var existingAttachmentNames by remember { mutableStateOf(initialAttachmentNames.distinct()) }
    var newAttachments by remember { mutableStateOf(listOf<SavedAttachment>()) }

    // Attachments that exist only because of an inline link.
    var inlineOwned by remember {
        mutableStateOf(
            markerRegex.findAll(initialText).map { it.groupValues[2] }.toSet()
                .intersect(initialAttachmentNames.toSet())
        )
    }
    var attachmentNotice by remember { mutableStateOf<String?>(null) }

    var isRecordingAudio by remember { mutableStateOf(false) }
    var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingVideoName by remember { mutableStateOf<String?>(null) }

    var reminderAtMillis by remember { mutableStateOf(initialReminderAtMillis) }
    var dueAtMillis by remember { mutableStateOf(initialDueAtMillis) }
    var repeatRule by remember { mutableStateOf(initialRepeatRule) }
    var showRepeatDialogFS by remember { mutableStateOf(false) }

    var noteDateTimeMillis by remember { mutableStateOf(initialNoteDateTimeMillis) }

    var locationReminders by remember { mutableStateOf(initialLocationReminders) }
    fun storedStatus(): Map<String, Boolean> = initialLocationReminders.mapNotNull { item ->
        existingEntryId?.let { id -> GeofenceStatusStore.getStatus(context, id, item.id)?.let { item.id to it } }
    }.toMap()
    fun storedErrors(): Map<String, String> = initialLocationReminders.mapNotNull { item ->
        existingEntryId?.let { id -> GeofenceStatusStore.getError(context, id, item.id)?.let { item.id to it } }
    }.toMap()
    var geofenceStatus by remember { mutableStateOf(storedStatus()) }
    var geofenceErrors by remember { mutableStateOf(storedErrors()) }

    var isOneShotListening by remember { mutableStateOf(false) }
    var isStreamingListening by remember { mutableStateOf(false) }
    var streamingPartialText by remember { mutableStateOf("") }
    var speechError by remember { mutableStateOf<String?>(null) }

    var showScribblePad by remember { mutableStateOf(false) }
    var isScanningText by remember { mutableStateOf(false) }
    var isViewMode by remember { mutableStateOf(false) }

    var wrapLines by remember { mutableStateOf(NoteBoxPrefs.wrapLines(context)) }
    var stampEditor by remember { mutableStateOf<StampTarget?>(null) }

    // ---- One confirmation dialog for every delete ----
    var confirmAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun confirm(message: String, action: () -> Unit) { confirmAction = message to action }

    // ---- Sub-notes ----
    var knownSubIds by remember { mutableStateOf(SubNoteManager.idsIn(initialText)) }
    var subGone by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var linkGone by remember { mutableStateOf<Set<String>>(emptySet()) }
    val freshSubIds = remember { mutableSetOf<Long>() }   // created in this session; removed if left empty
    var subEditor by remember { mutableStateOf<SubEditorTarget?>(null) }
    var subNoteNotice by remember { mutableStateOf<String?>(null) }
    var subSelectMode by remember { mutableStateOf(false) }
    var selectedSubIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmSubRemove by remember { mutableStateOf<Set<Long>?>(null) }
    var subNoteRefresh by remember { mutableStateOf(0) }
    var subNoteTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val subNoteIds = remember(textFieldValue.text) { SubNoteManager.idsIn(textFieldValue.text).toList() }

    LaunchedEffect(subNoteIds, subNoteRefresh) {
        subNoteTitles = withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            subNoteIds.associateWith { id ->
                val e = dao.getById(id)
                when {
                    e == null || e.source != SubNoteManager.SOURCE -> "(deleted)"
                    !e.title.isNullOrBlank() -> e.title!!
                    else -> markerRegex.replace(e.note.orEmpty(), "").trim().take(40)
                        .ifBlank { "Untitled sub-note" }
                }
            }
        }
    }

    fun insertAtCursor(marker: String) {
        val cursor = textFieldValue.selection.start.coerceIn(0, textFieldValue.text.length)
        val newText = textFieldValue.text.substring(0, cursor) + marker + textFieldValue.text.substring(cursor)
        textFieldValue = TextFieldValue(newText, selection = TextRange(cursor + marker.length))
    }

    fun setText(text: String) {
        textFieldValue = TextFieldValue(text, selection = TextRange(textFieldValue.selection.start.coerceIn(0, text.length)))
    }

    fun buildResult() = FullScreenNoteResult(
        title = title, text = textFieldValue.text, locationUrl = locationUrl, tags = tags,
        existingAttachmentNames = existingAttachmentNames, newAttachments = newAttachments,
        reminderAtMillis = reminderAtMillis, dueAtMillis = dueAtMillis, repeatRule = repeatRule,
        noteDateTimeMillis = noteDateTimeMillis, locationReminders = locationReminders
    )

    // ---- Autosave: no draft stage ----
    val latestResult by rememberUpdatedState(buildResult())
    val currentAutoSave by rememberUpdatedState(onAutoSave)
    var autoReady by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(
        title, textFieldValue.text, locationUrl, tags, existingAttachmentNames, newAttachments,
        reminderAtMillis, dueAtMillis, repeatRule, noteDateTimeMillis, locationReminders
    ) {
        if (!autoReady) { autoReady = true; return@LaunchedEffect }
        dirty = true
        delay(700)
        currentAutoSave(buildResult())
        dirty = false
        // Show the real registration result once the saver has applied it.
        if (existingEntryId != null && !isLocationNote) {
            delay(3000)
            geofenceStatus = locationReminders.mapNotNull { item ->
                GeofenceStatusStore.getStatus(context, existingEntryId, item.id)?.let { item.id to it }
            }.toMap()
            geofenceErrors = locationReminders.mapNotNull { item ->
                GeofenceStatusStore.getError(context, existingEntryId, item.id)?.let { item.id to it }
            }.toMap()
        }
    }
    DisposableEffect(Unit) { onDispose { if (dirty) currentAutoSave(latestResult) } }

    fun finish() { onSave(buildResult()) }
    if (!isSubNote && !isLocationNote) BackHandler { finish() }

    // ---- Attachments ----
    /**
     * The only way an attachment is added. marker == null: attached separately.
     * marker != null: inserted inline at the cursor. A file already listed is never added twice.
     */
    fun addAttachment(saved: SavedAttachment, marker: String?) {
        val alreadyListed = saved.name in existingAttachmentNames || newAttachments.any { it.name == saved.name }
        if (!alreadyListed) newAttachments = newAttachments + saved
        if (marker != null) {
            if (!alreadyListed) inlineOwned = inlineOwned + saved.name
            insertAtCursor(marker)
        } else {
            inlineOwned = inlineOwned - saved.name
            if (alreadyListed) attachmentNotice = "\"${saved.name}\" is already attached"
        }
    }

    /** Removes an attachment from the list AND strips its inline links from the text (call after confirming). */
    fun removeAttachment(name: String) {
        existingAttachmentNames = existingAttachmentNames.filterNot { it == name }
        newAttachments = newAttachments.filterNot { it.name == name }
        inlineOwned = inlineOwned - name
        val stripped = markerRegex.replace(textFieldValue.text) {
            if (it.groupValues[2] == name) "" else it.value
        }
        if (stripped != textFieldValue.text) setText(stripped)
        // Added and removed before the first autosave: nothing references the file yet.
        if (name !in initialAttachmentNames) AttachmentCleanup.launchDeleteIfUnreferenced(context, listOf(name))
    }

    // Deleting an inline file link by typing: ask whether to remove the file too.
    LaunchedEffect(textFieldValue.text) {
        val present = markerRegex.findAll(textFieldValue.text).map { it.groupValues[2] }.toSet()
        val gone = inlineOwned.filter { it !in present }.toSet()
        if (gone.isNotEmpty() && linkGone.isEmpty()) linkGone = gone
    }

    // Deleting a sub-note link by typing: ask whether to delete the sub-note.
    LaunchedEffect(textFieldValue.text) {
        val now = SubNoteManager.idsIn(textFieldValue.text)
        val gone = knownSubIds - now
        knownSubIds = now
        if (gone.isNotEmpty() && subGone.isEmpty()) subGone = gone
    }

    // ---- Sub-note actions ----
    fun openSubNote(id: Long?) {
        subNoteNotice = null
        scope.launch {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            val entry: LogEntry? = if (id == null) {
                // Created right away: there is no unsaved sub-note.
                val newId = SubNoteManager.save(
                    context, null,
                    FullScreenNoteResult(
                        title = "", text = "", locationUrl = "", tags = emptyList(),
                        existingAttachmentNames = emptyList(), newAttachments = emptyList(),
                        reminderAtMillis = null, dueAtMillis = null, repeatRule = "NONE",
                        noteDateTimeMillis = null, locationReminders = emptyList()
                    )
                )
                freshSubIds += newId
                knownSubIds = knownSubIds + newId
                insertAtCursor(SubNoteManager.marker(newId, "Sub-note"))
                withContext(Dispatchers.IO) { dao.getById(newId) }
            } else {
                withContext(Dispatchers.IO) { dao.getById(id) }
            }
            if (entry == null || entry.source != SubNoteManager.SOURCE) {
                subNoteNotice = "That sub-note no longer exists"
            } else {
                subEditor = SubEditorTarget(entry)
            }
        }
    }

    /** Closes the sub-note editor: drops a sub-note left empty, otherwise refreshes its link label. */
    fun closeSubEditor(id: Long) {
        subEditor = null
        scope.launch {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            if (id in freshSubIds && NoteSaver.deleteIfEmpty(context, id)) {
                freshSubIds -= id
                knownSubIds = knownSubIds - id
                setText(markerRegex.replace(textFieldValue.text) { m ->
                    if (m.groupValues[1] == SubNoteManager.TYPE && SubNoteManager.parseId(m.groupValues[2]) == id) "" else m.value
                })
            } else {
                val e = withContext(Dispatchers.IO) { dao.getById(id) }
                if (e != null) {
                    val label = e.title?.takeIf { it.isNotBlank() }
                        ?: markerRegex.replace(e.note.orEmpty(), "").trim().ifBlank { "Sub-note" }
                    setText(markerRegex.replace(textFieldValue.text) { m ->
                        if (m.groupValues[1] == SubNoteManager.TYPE && SubNoteManager.parseId(m.groupValues[2]) == id)
                            SubNoteManager.marker(id, label) else m.value
                    })
                }
            }
            subNoteRefresh++
        }
    }

    /** Removes the links of sub-notes and deletes them (call after confirming). */
    fun removeSubNoteLinks(ids: Set<Long>) {
        knownSubIds = knownSubIds - ids
        setText(markerRegex.replace(textFieldValue.text) { m ->
            val id = if (m.groupValues[1] == SubNoteManager.TYPE) SubNoteManager.parseId(m.groupValues[2]) else null
            if (id != null && id in ids) "" else m.value
        })
        SubNoteManager.launchDeleteWithCascade(context, ids)
        selectedSubIds = emptySet()
        subSelectMode = false
    }

    fun openMarkerAttachment(name: String) {
        scope.launch {
            val uri = MediaResolveUtil.resolve(context, name)
            if (uri != null) {
                try {
                    val mime = context.contentResolver.getType(uri) ?: "*/*"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Open with"))
                } catch (e: Exception) {
                    // No app can open it — ignore.
                }
            }
        }
    }

    fun handleMarkerClick(type: String, name: String) {
        when (type) {
            TimeStampManager.TYPE -> {
                val stamp = TimeStampManager.parse(name)
                if (stamp != null) stampEditor = StampTarget(name, stamp.millis, stamp.format)
            }
            SubNoteManager.TYPE -> {
                val id = SubNoteManager.parseId(name)
                if (id != null) openSubNote(id) else subNoteNotice = "That sub-note link is damaged"
            }
            else -> openMarkerAttachment(name)
        }
    }

    // ---- Voice, scan, pickers ----
    fun startOneShotSpeech() {
        if (isOneShotListening) {
            streamingSpeechHelper.forceStop { listening -> isOneShotListening = listening }
            return
        }
        speechError = null
        streamingSpeechHelper.startOneShot(
            onResult = { finalText -> insertAtCursor(finalText) },
            onError = { message -> speechError = message },
            onListeningStateChanged = { listening -> isOneShotListening = listening }
        )
    }

    fun toggleStreamingSpeech() {
        if (isStreamingListening) {
            streamingSpeechHelper.forceStop { listening -> isStreamingListening = listening }
            return
        }
        speechError = null
        streamingPartialText = ""
        streamingSpeechHelper.start(
            onPartialResult = { partial -> streamingPartialText = partial },
            onFinalResult = { finalText ->
                insertAtCursor(finalText)
                streamingPartialText = ""
            },
            onError = { message -> speechError = message; streamingPartialText = "" },
            onListeningStateChanged = { listening -> isStreamingListening = listening }
        )
    }

    DisposableEffect(Unit) {
        onDispose { streamingSpeechHelper.stop() }
    }

    val scanImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            isScanningText = true
            scope.launch {
                val recognizedText = TextScanHelper.recognizeTextFromImage(context, uri)
                if (recognizedText != null) insertAtCursor(recognizedText)
                isScanningText = false
            }
        }
    }

    val insertImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { FileAttachmentHelper.copyToDownloads(context, uri) }
                if (saved != null) addAttachment(saved, "📎[image: ${saved.name}]")
            }
        }
    }

    val attachFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { FileAttachmentHelper.copyToDownloads(context, uri) }
                if (saved != null) addAttachment(saved, "🔗[file: ${saved.name}]")
            }
        }
    }

    val locationPermissionLauncherFS = rememberLauncherForActivityResult(
        contract = RequestPermission()
    ) { }

    fun pinCurrentLocationFS() {
        if (!LocationPinHelper.hasLocationPermission(context)) {
            locationPermissionLauncherFS.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        scope.launch {
            val url = LocationPinHelper.getCurrentLocationUrl(context)
            if (url != null) locationUrl = url
        }
    }

    fun addTagFromInput() {
        val cleaned = tagInput.trim().removePrefix("#")
        if (cleaned.isNotBlank() && cleaned !in tags) {
            tags = tags + cleaned
        }
        tagInput = ""
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val distinctUris = uris.distinct()
        if (distinctUris.isNotEmpty()) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    distinctUris.mapNotNull { uri -> FileAttachmentHelper.copyToDownloads(context, uri) }
                }
                saved.forEach { addAttachment(it, null) }
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
                addAttachment(SavedAttachment(name, uri), null)
            }
        }
        pendingVideoUri = null
        pendingVideoName = null
    }

    fun toggleAudioRecording() {
        if (isRecordingAudio) {
            val saved = audioRecorder.stopRecordingAndSave()
            isRecordingAudio = false
            if (saved != null) addAttachment(saved, null)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isLocationNote -> "Note: ${locationNoteName.ifBlank { "location" }}"
                            isSubNote -> "Sub-note"
                            else -> "Note"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    TextButton(onClick = { finish() }) { Text("✕ Close") }
                },
                actions = {
                    TextButton(onClick = {
                        NoteShareHelper.shareFields(
                            context = context,
                            title = title.ifBlank { null },
                            note = TimeStampManager.toPlainText(textFieldValue.text).ifBlank { null },
                            timestampMillis = noteDateTimeMillis ?: System.currentTimeMillis(),
                            locationUrl = locationUrl.ifBlank { null },
                            tags = tags,
                            reminderAtMillis = reminderAtMillis,
                            dueAtMillis = dueAtMillis,
                            repeatRule = repeatRule,
                            attachmentNames = existingAttachmentNames + newAttachments.map { it.name }
                        )
                    }) { Text("📤") }
                    TextButton(onClick = {
                        NotePrintHelper.printNote(
                            context = context,
                            title = title.ifBlank { "Phone Diary Note" },
                            noteText = TimeStampManager.toPlainText(textFieldValue.text),
                            tags = tags,
                            locationUrl = locationUrl.ifBlank { null },
                            reminderAtMillis = reminderAtMillis,
                            dueAtMillis = dueAtMillis,
                            repeatRule = repeatRule,
                            attachmentNames = existingAttachmentNames + newAttachments.map { it.name }
                        )
                    }) { Text("🖨") }
                    TextButton(onClick = { finish() }) { Text("Done") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text("✓ Changes save automatically", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        if (isLocationNote) "Title shown in the notification (optional)"
                        else "Note title (optional) — shown in Calendar & searchable"
                    )
                },
                singleLine = true
            )

            if (!isLocationNote) {
                Spacer(Modifier.height(8.dp))
                Text("Note date & time", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { DateTimePickerUtil.pick(context) { picked -> noteDateTimeMillis = picked } }
                    ) {
                        Text(noteDateTimeMillis?.let { dateTimeFormat.format(it) } ?: "Set date & time (defaults to now)")
                    }
                    if (noteDateTimeMillis != null) {
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { noteDateTimeMillis = null }) { Text("✕") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (!highlightQuery.isNullOrBlank()) {
                Text(
                    "Showing match for \"$highlightQuery\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
            }

            // ---- View / Edit toggle, plus the wrap / scroll-sideways toggle ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Note", style = MaterialTheme.typography.bodySmall)
                Row {
                    FilterChip(selected = !isViewMode, onClick = { isViewMode = false }, label = { Text("Edit") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = isViewMode, onClick = { isViewMode = true }, label = { Text("View") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = !wrapLines,
                        onClick = {
                            wrapLines = !wrapLines
                            NoteBoxPrefs.setWrapLines(context, wrapLines)
                        },
                        label = { Text("↔") }
                    )
                }
            }
            if (!wrapLines) {
                Text(
                    "↔ Long lines don't wrap — scroll sideways. Tap ↔ to wrap again.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(4.dp))

            if (isViewMode) {
                ScrollableNoteBox(wrapLines = wrapLines) { minWidth ->
                    NoteViewRenderer(
                        text = textFieldValue.text,
                        modifier = Modifier.widthIn(min = minWidth),
                        wrap = wrapLines,
                        onMarkerClick = { type, name -> handleMarkerClick(type, name) }
                    )
                }
            } else {
                ScrollableNoteBox(wrapLines = wrapLines) { minWidth ->
                    MarkerTextField(
                        value = textFieldValue,
                        onValueChange = { textFieldValue = it },
                        modifier = Modifier.widthIn(min = minWidth),
                        placeholderText = "Write your note…",
                        minLines = 6,
                        softWrap = wrapLines,
                        onMarkerClick = { type, name -> handleMarkerClick(type, name) }
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { startOneShotSpeech() }) {
                        Text(if (isOneShotListening) "🔴" else "🎤")
                    }
                    IconButton(onClick = { toggleStreamingSpeech() }) {
                        Text(if (isStreamingListening) "🔴" else "🎙️")
                    }
                    IconButton(enabled = !isScanningText, onClick = { scanImagePickerLauncher.launch("image/*") }) {
                        Text(if (isScanningText) "⏳" else "📷")
                    }
                    IconButton(onClick = { insertImageLauncher.launch("image/*") }) { Text("🖼") }
                    IconButton(onClick = { attachFileLauncher.launch(arrayOf("*/*")) }) { Text("🔗") }
                    IconButton(onClick = { showScribblePad = true }) { Text("✍") }
                    IconButton(onClick = {
                        insertAtCursor(
                            TimeStampManager.marker(System.currentTimeMillis(), TimeStampManager.lastFormat(context))
                        )
                    }) { Text("🕒") }
                    IconButton(onClick = { openSubNote(null) }) { Text("🗒") }
                }
            }

            if (isStreamingListening || streamingPartialText.isNotBlank()) {
                Text(
                    if (streamingPartialText.isNotBlank()) "🎙️ $streamingPartialText" else "🎙️ Listening…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            speechError?.let {
                Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // ---- Sub-notes ----
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sub-notes", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subNoteIds.size > 1) {
                        TextButton(onClick = {
                            subSelectMode = !subSelectMode
                            selectedSubIds = emptySet()
                        }) { Text(if (subSelectMode) "Done" else "Select") }
                    }
                    OutlinedButton(onClick = { openSubNote(null) }) { Text("+ Sub-note") }
                }
            }
            if (subSelectMode && subNoteIds.isNotEmpty()) {
                val activeSelected = selectedSubIds.filter { it in subNoteIds }.toSet()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selectedSubIds = if (activeSelected.size == subNoteIds.size) emptySet() else subNoteIds.toSet()
                    }) { Text(if (activeSelected.size == subNoteIds.size) "None" else "All") }
                    Text("${activeSelected.size} selected", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = activeSelected.isNotEmpty(),
                        onClick = { confirmSubRemove = activeSelected }
                    ) { Text("Delete selected") }
                }
            }
            if (subNoteIds.isEmpty()) {
                Text(
                    "None yet — a sub-note is a clickable note inside this one, with all the same features.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            subNoteIds.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subSelectMode) {
                        Checkbox(
                            checked = id in selectedSubIds,
                            onCheckedChange = { checked ->
                                selectedSubIds = if (checked) selectedSubIds + id else selectedSubIds - id
                            }
                        )
                    }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (subSelectMode) {
                                selectedSubIds = if (id in selectedSubIds) selectedSubIds - id else selectedSubIds + id
                            } else {
                                openSubNote(id)
                            }
                        }
                    ) {
                        Text(
                            "🗒 ${subNoteTitles[id] ?: "…"}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!subSelectMode) {
                        TextButton(onClick = {
                            confirm("Delete this sub-note with its attachments and reminders?") {
                                removeSubNoteLinks(setOf(id))
                            }
                        }) { Text("✕") }
                    }
                }
            }
            subNoteNotice?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = locationUrl,
                    onValueChange = { locationUrl = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Location URL (optional)") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { pinCurrentLocationFS() }) { Text("📍") }
                if (locationUrl.isNotBlank()) {
                    IconButton(onClick = { LocationOpenHelper.open(context, locationUrl) }) { Text("🔗") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Tags", style = MaterialTheme.typography.titleSmall)
            if (tags.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    tags.forEach { tag ->
                        AssistChip(
                            onClick = { confirm("Remove the tag #$tag?") { tags = tags.filterNot { it == tag } } },
                            label = { Text("#$tag ✕") },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add tag…") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { addTagFromInput() }) { Text("Add") }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (isLocationNote) "Repeat while here" else "Schedule",
                style = MaterialTheme.typography.titleSmall
            )
            if (!isLocationNote) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { DateTimePickerUtil.pick(context) { picked -> reminderAtMillis = picked } }
                    ) {
                        Text(reminderAtMillis?.let { "⏰ ${dateTimeFormat.format(it)}" } ?: "⏰ Reminder")
                    }
                    if (reminderAtMillis != null) {
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = {
                            confirm("Remove this reminder?") { reminderAtMillis = null }
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
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = {
                            confirm("Remove the due date?") { dueAtMillis = null }
                        }) { Text("✕") }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { showRepeatDialogFS = true }
                ) {
                    Text(if (repeatRule != "NONE") "🔁 ${repeatDisplayLabel2(repeatRule)}" else "🔁 Repeat")
                }
                if (repeatRule != "NONE") {
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = {
                        confirm("Remove the repeat?") { repeatRule = "NONE" }
                    }) { Text("✕") }
                }
            }
            if (isLocationNote) {
                Text(
                    "Notifies again on this schedule while you stay inside this location's area. Stops when you leave.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (showRepeatDialogFS) {
                RepeatPickerDialog(
                    initial = RepeatConfig.fromStored(repeatRule),
                    onConfirm = { config -> repeatRule = config.toStored(); showRepeatDialogFS = false },
                    onDismiss = { showRepeatDialogFS = false }
                )
            }

            if (!isLocationNote) {
                Spacer(Modifier.height(12.dp))
                LocationReminderSection(
                    items = locationReminders,
                    onItemsChanged = { locationReminders = it },
                    registrationStatus = geofenceStatus,
                    registrationError = geofenceErrors,
                    onToggle = { item ->
                        // The autosave registers or removes the geofence; just clear the stale status.
                        geofenceStatus = geofenceStatus - item.id
                        geofenceErrors = geofenceErrors - item.id
                    }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("Attachments", style = MaterialTheme.typography.titleSmall)
            val markerNames = remember(textFieldValue.text) {
                markerRegex.findAll(textFieldValue.text).map { it.groupValues[2] }.toSet()
            }
            if (existingAttachmentNames.isEmpty() && newAttachments.isEmpty()) {
                Text("None yet", style = MaterialTheme.typography.bodySmall)
            }
            existingAttachmentNames.forEach { name ->
                if (name in markerNames) {
                    Text("↳ linked in note text", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                ResolvingAttachmentPreview(
                    name = name,
                    onRemove = {
                        confirm("Remove this attachment? Its file is deleted from your phone if no other note uses it.") {
                            removeAttachment(name)
                        }
                    }
                )
            }
            newAttachments.forEach { attachment ->
                if (attachment.name in markerNames) {
                    Text("↳ linked in note text", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                AttachmentPreview(
                    name = attachment.name,
                    uri = attachment.uri,
                    onRemove = {
                        confirm("Remove this attachment? Its file is deleted from your phone if no other note uses it.") {
                            removeAttachment(attachment.name)
                        }
                    }
                )
            }
            attachmentNotice?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) { Text("Attach files") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { toggleAudioRecording() }) {
                    Text(if (isRecordingAudio) "⏹ Stop" else "🎙 Voice")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { startVideoCapture() }) { Text("📹 Video") }
            }
        }
    }

    if (showScribblePad) {
        InlineScribblePad(
            onInsert = { bitmap ->
                val saved = FileAttachmentHelper.saveBitmapAsAttachment(context, bitmap)
                if (saved != null) addAttachment(saved, "✍[drawing: ${saved.name}]")
                showScribblePad = false
            },
            onCancel = { showScribblePad = false }
        )
    }

    // ---- Edit / delete one timestamp stamp ----
    stampEditor?.let { target ->
        TimeStampDialog(
            initialMillis = target.millis,
            initialFormat = target.format,
            onApply = { millis, format, applyToAll ->
                val key = TimeStampManager.parse(target.oldLabel)?.key
                var text = TimeStampManager.replaceStamp(
                    textFieldValue.text, target.oldLabel, TimeStampManager.marker(millis, format, key)
                )
                if (applyToAll) text = TimeStampManager.restyleAll(text, format)
                setText(text)
                TimeStampManager.saveLastFormat(context, format)
                stampEditor = null
            },
            onDelete = {
                val label = target.oldLabel
                stampEditor = null
                confirm("Delete this timestamp?") {
                    setText(TimeStampManager.replaceStamp(textFieldValue.text, label, ""))
                }
            },
            onDismiss = { stampEditor = null }
        )
    }

    // ---- Generic delete confirmation ----
    confirmAction?.let { (message, action) ->
        ConfirmDeleteDialog(
            message = message,
            onConfirm = { confirmAction = null; action() },
            onDismiss = { confirmAction = null }
        )
    }

    // ---- Delete several sub-notes at once ----
    confirmSubRemove?.let { ids ->
        ConfirmDeleteDialog(
            message = "Delete ${ids.size} sub-note(s) now, with their attachments and reminders? This cannot be undone.",
            onConfirm = { confirmSubRemove = null; removeSubNoteLinks(ids) },
            onDismiss = { confirmSubRemove = null }
        )
    }

    // ---- A file link was deleted from the text ----
    if (linkGone.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { inlineOwned = inlineOwned - linkGone; linkGone = emptySet() },
            title = { Text("Link removed from the note") },
            text = {
                Text("Also remove ${linkGone.joinToString(", ")} from the attachments? The file is deleted if no other note uses it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val names = linkGone
                    existingAttachmentNames = existingAttachmentNames.filterNot { it in names }
                    newAttachments = newAttachments.filterNot { it.name in names }
                    inlineOwned = inlineOwned - names
                    linkGone = emptySet()
                    names.filter { it !in initialAttachmentNames }.let {
                        AttachmentCleanup.launchDeleteIfUnreferenced(context, it)
                    }
                }) { Text("Remove file") }
            },
            dismissButton = {
                TextButton(onClick = { inlineOwned = inlineOwned - linkGone; linkGone = emptySet() }) { Text("Keep attached") }
            }
        )
    }

    // ---- A sub-note link was deleted from the text ----
    if (subGone.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { subGone = emptySet() },
            title = { Text("Sub-note link removed") },
            text = { Text("Delete ${subGone.size} sub-note(s) with their attachments and reminders?") },
            confirmButton = {
                TextButton(onClick = {
                    SubNoteManager.launchDeleteWithCascade(context, subGone)
                    subGone = emptySet()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val restore = subGone
                    subGone = emptySet()
                    restore.forEach { id ->
                        insertAtCursor(SubNoteManager.marker(id, subNoteTitles[id] ?: "Sub-note"))
                    }
                }) { Text("Keep (restore link)") }
            }
        )
    }

    // ---- Nested editor for one sub-note, in its own Dialog window ----
    subEditor?.let { target ->
        val e = target.entry
        Dialog(
            onDismissRequest = { closeSubEditor(e.id) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                FullScreenNoteEditor(
                    initialTitle = e.title ?: "",
                    initialText = e.note ?: "",
                    initialLocationUrl = e.locationUrl ?: "",
                    initialTags = AttachmentListUtil.toList(e.tags),
                    initialAttachmentNames = AttachmentListUtil.toList(e.attachmentFileName).distinct(),
                    initialReminderAtMillis = e.reminderAtMillis,
                    initialDueAtMillis = e.dueAtMillis,
                    initialRepeatRule = e.repeatRule ?: "NONE",
                    initialNoteDateTimeMillis = e.timestampMillis,
                    initialLocationReminders = LocationReminderListUtil.fromStored(e.locationReminders),
                    existingEntryId = e.id,
                    isSubNote = true,
                    onAutoSave = { r -> scope.launch { SubNoteManager.save(context, e, r) } },
                    onSave = { r ->
                        scope.launch {
                            SubNoteManager.save(context, e, r)
                            closeSubEditor(e.id)
                        }
                    },
                    onCancel = { closeSubEditor(e.id) }
                )
            }
        }
    }
}
