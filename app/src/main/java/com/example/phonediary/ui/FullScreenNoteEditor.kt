package com.example.phonediary.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.phonediary.files.NoteShareHelper
import com.example.phonediary.files.SavedAttachment
import com.example.phonediary.files.StreamingSpeechHelper
import com.example.phonediary.files.SubNoteManager
import com.example.phonediary.files.TextScanHelper
import com.example.phonediary.files.VideoCaptureHelper
import com.example.phonediary.reminders.GeofenceHelper
import com.example.phonediary.reminders.GeofenceStatusStore
import kotlinx.coroutines.Dispatchers
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

/** Which sub-note the nested editor is showing. entry == null means a brand-new, unsaved sub-note. */
private class SubEditorTarget(val entry: LogEntry?)

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
    /**
     * True when this editor is editing the note that belongs to one location
     * reminder. The location itself is the trigger, so note date/time,
     * Reminder, Due, nested location reminders and sub-notes are hidden, and
     * Repeat means "repeat while I'm inside this location".
     */
    isLocationNote: Boolean = false,
    locationNoteName: String = "",
    /** True when this editor is editing a sub-note (only changes the title bar). */
    isSubNote: Boolean = false,
    highlightQuery: String? = null,
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
    // Notes saved earlier with duplicate names are de-duplicated on open.
    var existingAttachmentNames by remember { mutableStateOf(initialAttachmentNames.distinct()) }
    var newAttachments by remember { mutableStateOf(listOf<SavedAttachment>()) }

    // Attachments that exist only because of an inline link. Deleting the last
    // link removes them. A file that was also attached separately is not in here,
    // so deleting its link leaves it attached.
    var inlineOwned by remember {
        mutableStateOf(
            markerRegex.findAll(initialText).map { it.groupValues[2] }.toSet()
                .intersect(initialAttachmentNames.toSet())
        )
    }
    // Files freshly added during this editing session (used to clean up on cancel).
    val sessionAddedNames = remember { mutableSetOf<String>() }
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
    // Initialised from the persisted store so reopening a note shows real
    // registration status instead of resetting to "unknown".
    var geofenceStatus by remember {
        mutableStateOf(
            initialLocationReminders.mapNotNull { item ->
                existingEntryId?.let { id ->
                    GeofenceStatusStore.getStatus(context, id, item.id)?.let { item.id to it }
                }
            }.toMap()
        )
    }
    var geofenceErrors by remember {
        mutableStateOf(
            initialLocationReminders.mapNotNull { item ->
                existingEntryId?.let { id ->
                    GeofenceStatusStore.getError(context, id, item.id)?.let { item.id to it }
                }
            }.toMap()
        )
    }

    var isOneShotListening by remember { mutableStateOf(false) }
    var isStreamingListening by remember { mutableStateOf(false) }
    var streamingPartialText by remember { mutableStateOf("") }
    var speechError by remember { mutableStateOf<String?>(null) }

    var showScribblePad by remember { mutableStateOf(false) }
    var isScanningText by remember { mutableStateOf(false) }
    var isViewMode by remember { mutableStateOf(false) }

    // ---- Sub-notes ----
    val initialSubIds = remember { SubNoteManager.idsIn(initialText) }
    // Sub-notes created in this editing session; removed again if this editor is cancelled.
    val sessionCreatedSubIds = remember { mutableSetOf<Long>() }
    var subEditor by remember { mutableStateOf<SubEditorTarget?>(null) }
    var subNoteNotice by remember { mutableStateOf<String?>(null) }
    var confirmSubDelete by remember { mutableStateOf<Set<Long>?>(null) }
    var subNoteRefresh by remember { mutableStateOf(0) }
    var subNoteTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val subNoteIds = remember(textFieldValue.text) { SubNoteManager.idsIn(textFieldValue.text).toList() }

    // Live titles for the Sub-notes list; reloads when links change or a sub-note is saved.
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

    /**
     * The only way an attachment is added. marker == null: attached separately
     * (Attach files / voice / video). marker != null: inserted inline at the cursor.
     * A file already in the list is never added a second time.
     */
    fun addAttachment(saved: SavedAttachment, marker: String?) {
        val alreadyListed = saved.name in existingAttachmentNames || newAttachments.any { it.name == saved.name }
        if (!alreadyListed) {
            sessionAddedNames += saved.name
            newAttachments = newAttachments + saved
        }
        if (marker != null) {
            // Only a file that exists purely because of an inline link is owned by that link.
            if (!alreadyListed) inlineOwned = inlineOwned + saved.name
            insertAtCursor(marker)
        } else {
            inlineOwned = inlineOwned - saved.name
            if (alreadyListed) attachmentNotice = "\"${saved.name}\" is already attached"
        }
    }

    /** Removes an attachment from the list AND strips its inline links from the text. */
    fun removeAttachment(name: String) {
        existingAttachmentNames = existingAttachmentNames.filterNot { it == name }
        newAttachments = newAttachments.filterNot { it.name == name }
        inlineOwned = inlineOwned - name
        val stripped = markerRegex.replace(textFieldValue.text) {
            if (it.groupValues[2] == name) "" else it.value
        }
        if (stripped != textFieldValue.text) {
            val cursor = textFieldValue.selection.start.coerceIn(0, stripped.length)
            textFieldValue = TextFieldValue(stripped, selection = TextRange(cursor))
        }
    }

    // Deleting the last inline link of an inline-owned attachment removes that attachment too.
    LaunchedEffect(textFieldValue.text) {
        val present = markerRegex.findAll(textFieldValue.text).map { it.groupValues[2] }.toSet()
        val gone = inlineOwned.filter { it !in present }.toSet()
        if (gone.isNotEmpty()) {
            existingAttachmentNames = existingAttachmentNames.filterNot { it in gone }
            newAttachments = newAttachments.filterNot { it.name in gone }
            inlineOwned = inlineOwned - gone
            attachmentNotice = "Removed ${gone.joinToString(", ")} — its link was deleted from the note"
        }
    }

    fun openSubNote(id: Long?) {
        subNoteNotice = null
        if (id == null) {
            subEditor = SubEditorTarget(null)
            return
        }
        scope.launch {
            val entry = withContext(Dispatchers.IO) { AppDatabase.getInstance(context).logEntryDao().getById(id) }
            if (entry == null || entry.source != SubNoteManager.SOURCE) {
                subNoteNotice = "That sub-note no longer exists"
            } else {
                subEditor = SubEditorTarget(entry)
            }
        }
    }

    /** Removes a sub-note's link from the text. The sub-note itself is deleted when this note is saved. */
    fun removeSubNoteLink(id: Long) {
        val stripped = markerRegex.replace(textFieldValue.text) { m ->
            if (m.groupValues[1] == SubNoteManager.TYPE && SubNoteManager.parseId(m.groupValues[2]) == id) "" else m.value
        }
        if (stripped != textFieldValue.text) {
            val cursor = textFieldValue.selection.start.coerceIn(0, stripped.length)
            textFieldValue = TextFieldValue(stripped, selection = TextRange(cursor))
        }
    }

    fun cancelAndCleanup() {
        val initialNames = initialAttachmentNames.toSet()
        val initialLocationNames = initialLocationReminders.flatMap { it.attachmentNames }.toSet()
        val newLocationNames = locationReminders.flatMap { it.attachmentNames }.toSet() - initialLocationNames
        AttachmentCleanup.launchDeleteIfUnreferenced(
            context,
            (sessionAddedNames - initialNames) + newLocationNames
        )
        // Sub-notes created in this session were saved to the database already — remove them.
        SubNoteManager.launchDeleteWithCascade(context, sessionCreatedSubIds.toSet())
        onCancel()
    }

    fun performSave(removedSubIds: Set<Long>) {
        val finalSubIds = SubNoteManager.idsIn(textFieldValue.text)
        SubNoteManager.launchDeleteWithCascade(
            context,
            removedSubIds + (sessionCreatedSubIds - finalSubIds)
        )
        // Files added this session but removed before saving are never referenced
        // by anything — delete them now. (The check against other notes happens
        // inside, so shared files are safe.)
        val finalNames = (existingAttachmentNames + newAttachments.map { it.name }).toSet()
        AttachmentCleanup.launchDeleteIfUnreferenced(
            context,
            sessionAddedNames - finalNames - initialAttachmentNames.toSet()
        )
        onSave(
            FullScreenNoteResult(
                title = title,
                text = textFieldValue.text,
                locationUrl = locationUrl,
                tags = tags,
                existingAttachmentNames = existingAttachmentNames,
                newAttachments = newAttachments,
                reminderAtMillis = reminderAtMillis,
                dueAtMillis = dueAtMillis,
                repeatRule = repeatRule,
                noteDateTimeMillis = noteDateTimeMillis,
                locationReminders = locationReminders
            )
        )
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
        if (type == SubNoteManager.TYPE) {
            val id = SubNoteManager.parseId(name)
            if (id != null) openSubNote(id) else subNoteNotice = "That sub-note link is damaged"
        } else {
            openMarkerAttachment(name)
        }
    }

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
                    TextButton(onClick = { cancelAndCleanup() }) { Text("✕ Cancel") }
                },
                actions = {
                    TextButton(onClick = {
                        NoteShareHelper.shareFields(
                            context = context,
                            title = title.ifBlank { null },
                            note = textFieldValue.text.ifBlank { null },
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
                            noteText = textFieldValue.text,
                            tags = tags,
                            locationUrl = locationUrl.ifBlank { null },
                            reminderAtMillis = reminderAtMillis,
                            dueAtMillis = dueAtMillis,
                            repeatRule = repeatRule,
                            attachmentNames = existingAttachmentNames + newAttachments.map { it.name }
                        )
                    }) { Text("🖨") }
                    TextButton(onClick = {
                        // Sub-notes whose links were deleted are removed on save, after confirming.
                        val removed = initialSubIds - SubNoteManager.idsIn(textFieldValue.text)
                        if (removed.isNotEmpty()) confirmSubDelete = removed else performSave(emptySet())
                    }) { Text("Save") }
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

            // ---- View / Edit toggle for the note box ----
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
                }
            }
            Spacer(Modifier.height(4.dp))

            if (isViewMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .padding(12.dp)
                ) {
                    NoteViewRenderer(
                        text = textFieldValue.text,
                        modifier = Modifier.fillMaxWidth(),
                        onMarkerClick = { type, name -> handleMarkerClick(type, name) }
                    )
                }
            } else {
                MarkerTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholderText = "Write your note…",
                    minLines = 6,
                    maxLines = 14,
                    onMarkerClick = { type, name -> handleMarkerClick(type, name) }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    if (!isLocationNote) {
                        IconButton(onClick = { openSubNote(null) }) { Text("🗒") }
                    }
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
            if (!isLocationNote) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sub-notes", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { openSubNote(null) }) { Text("+ Sub-note") }
                }
                if (subNoteIds.isEmpty()) {
                    Text(
                        "None yet — a sub-note is a clickable note inside this one, with all the same features.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                subNoteIds.forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            onClick = { openSubNote(id) }
                        ) {
                            Text(
                                "🗒 ${subNoteTitles[id] ?: "…"}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { removeSubNoteLink(id) }) { Text("✕") }
                    }
                }
                subNoteNotice?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
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
                            onClick = { tags = tags.filterNot { it == tag } },
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
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = { dueAtMillis = null }) { Text("✕") }
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
                    OutlinedButton(onClick = { repeatRule = "NONE" }) { Text("✕") }
                }
            }
            if (isLocationNote) {
                Text(
                    "Notifies again on this schedule while you stay inside this location's area. Stops when you leave. Takes effect when the parent note is saved.",
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
                        if (existingEntryId != null) {
                            if (item.enabled) {
                                GeofenceHelper.registerGeofence(context, existingEntryId, item) { success, error ->
                                    geofenceStatus = geofenceStatus + (item.id to success)
                                    geofenceErrors = if (error != null) {
                                        geofenceErrors + (item.id to error)
                                    } else {
                                        geofenceErrors - item.id
                                    }
                                }
                            } else {
                                GeofenceHelper.removeGeofence(context, existingEntryId, item.id)
                                geofenceStatus = geofenceStatus - item.id
                                geofenceErrors = geofenceErrors - item.id
                            }
                        } else {
                            // New note: no id yet, so nothing to register until Save.
                            geofenceStatus = geofenceStatus - item.id
                            geofenceErrors = geofenceErrors - item.id
                        }
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
                    onRemove = { removeAttachment(name) }
                )
            }
            newAttachments.forEach { attachment ->
                if (attachment.name in markerNames) {
                    Text("↳ linked in note text", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                AttachmentPreview(
                    name = attachment.name,
                    uri = attachment.uri,
                    onRemove = { removeAttachment(attachment.name) }
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

    // ---- Confirm before deleting sub-notes whose links were removed ----
    confirmSubDelete?.let { ids ->
        ConfirmDeleteDialog(
            message = "${ids.size} sub-note(s) will be permanently deleted, with their attachments and reminders, " +
                "because their links were removed from this note. Continue?",
            onConfirm = {
                confirmSubDelete = null
                performSave(ids)
            },
            onDismiss = { confirmSubDelete = null }
        )
    }

    // ---- Nested editor for one sub-note. Its own Dialog window, so its Scaffold
    //      never sits inside this editor's scrolling column. ----
    subEditor?.let { target ->
        val e = target.entry
        Dialog(
            onDismissRequest = { subEditor = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                FullScreenNoteEditor(
                    initialTitle = e?.title ?: "",
                    initialText = e?.note ?: "",
                    initialLocationUrl = e?.locationUrl ?: "",
                    initialTags = AttachmentListUtil.toList(e?.tags),
                    initialAttachmentNames = AttachmentListUtil.toList(e?.attachmentFileName).distinct(),
                    initialReminderAtMillis = e?.reminderAtMillis,
                    initialDueAtMillis = e?.dueAtMillis,
                    initialRepeatRule = e?.repeatRule ?: "NONE",
                    initialNoteDateTimeMillis = e?.timestampMillis ?: noteDateTimeMillis,
                    initialLocationReminders = LocationReminderListUtil.fromStored(e?.locationReminders),
                    existingEntryId = e?.id,
                    isSubNote = true,
                    onSave = { r ->
                        val wasNew = e == null
                        scope.launch {
                            val id = SubNoteManager.save(context, e, r)
                            if (wasNew) {
                                sessionCreatedSubIds += id
                                val label = r.title.ifBlank { markerRegex.replace(r.text, "").trim() }
                                insertAtCursor(SubNoteManager.marker(id, label))
                            }
                            subNoteRefresh++
                            subEditor = null
                        }
                    },
                    onCancel = { subEditor = null }
                )
            }
        }
    }
}
