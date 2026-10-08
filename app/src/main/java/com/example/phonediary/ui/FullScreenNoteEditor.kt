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
import androidx.compose.ui.unit.dp
import com.example.phonediary.data.LocationReminderItem
import com.example.phonediary.files.AudioRecorderHelper
import com.example.phonediary.files.FileAttachmentHelper
import com.example.phonediary.files.LocationOpenHelper
import com.example.phonediary.files.LocationPinHelper
import com.example.phonediary.files.MediaResolveUtil
import com.example.phonediary.files.NotePrintHelper
import com.example.phonediary.files.NoteShareHelper
import com.example.phonediary.files.SavedAttachment
import com.example.phonediary.files.StreamingSpeechHelper
import com.example.phonediary.files.TextScanHelper
import com.example.phonediary.files.VideoCaptureHelper
import com.example.phonediary.reminders.GeofenceHelper
import kotlinx.coroutines.launch
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
    highlightQuery: String? = null,
    existingEntryId: Long? = null,
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
    var existingAttachmentNames by remember { mutableStateOf(initialAttachmentNames) }
    var newAttachments by remember { mutableStateOf(listOf<SavedAttachment>()) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingVideoName by remember { mutableStateOf<String?>(null) }

    var reminderAtMillis by remember { mutableStateOf(initialReminderAtMillis) }
    var dueAtMillis by remember { mutableStateOf(initialDueAtMillis) }
    var repeatRule by remember { mutableStateOf(initialRepeatRule) }
    var showRepeatDialogFS by remember { mutableStateOf(false) }

    var noteDateTimeMillis by remember { mutableStateOf(initialNoteDateTimeMillis) }

    var locationReminders by remember { mutableStateOf(initialLocationReminders) }
    var geofenceStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var geofenceErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var isOneShotListening by remember { mutableStateOf(false) }
    var isStreamingListening by remember { mutableStateOf(false) }
    var streamingPartialText by remember { mutableStateOf("") }
    var speechError by remember { mutableStateOf<String?>(null) }

    var showScribblePad by remember { mutableStateOf(false) }
    var isScanningText by remember { mutableStateOf(false) }
    var isViewMode by remember { mutableStateOf(false) }

    fun insertAtCursor(marker: String) {
        val cursor = textFieldValue.selection.start.coerceIn(0, textFieldValue.text.length)
        val newText = textFieldValue.text.substring(0, cursor) + marker + textFieldValue.text.substring(cursor)
        textFieldValue = TextFieldValue(newText, selection = TextRange(cursor + marker.length))
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
                val saved = FileAttachmentHelper.copyToDownloads(context, uri)
                if (saved != null) {
                    newAttachments = newAttachments + saved
                    insertAtCursor("📎[image: ${saved.name}]")
                }
            }
        }
    }

    val attachFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = FileAttachmentHelper.copyToDownloads(context, uri)
                if (saved != null) {
                    newAttachments = newAttachments + saved
                    insertAtCursor("🔗[file: ${saved.name}]")
                }
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
                val saved = distinctUris.mapNotNull { uri -> FileAttachmentHelper.copyToDownloads(context, uri) }
                newAttachments = newAttachments + saved
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
                newAttachments = newAttachments + SavedAttachment(name, uri)
            }
        }
        pendingVideoUri = null
        pendingVideoName = null
    }

    fun toggleAudioRecording() {
        if (isRecordingAudio) {
            val saved = audioRecorder.stopRecordingAndSave()
            isRecordingAudio = false
            if (saved != null) newAttachments = newAttachments + saved
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
                title = { Text("Note") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("✕ Cancel") }
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
                placeholder = { Text("Note title (optional) — shown in Calendar & searchable") },
                singleLine = true
            )

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
                        onMarkerClick = { _, name -> openMarkerAttachment(name) }
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
                    onMarkerClick = { _, name -> openMarkerAttachment(name) }
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
            Text("Schedule", style = MaterialTheme.typography.titleSmall)
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
            if (showRepeatDialogFS) {
                RepeatPickerDialog(
                    initial = RepeatConfig.fromStored(repeatRule),
                    onConfirm = { config -> repeatRule = config.toStored(); showRepeatDialogFS = false },
                    onDismiss = { showRepeatDialogFS = false }
                )
            }

            Spacer(Modifier.height(12.dp))
         /*   LocationReminderSection(
                items = locationReminders,
                onItemsChanged = { locationReminders = it },
                registrationStatus = geofenceStatus,
                registrationError = geofenceErrors
            )*/
            LocationReminderSection(
                items = locationReminders,
                onItemsChanged = { locationReminders = it },
                registrationStatus = geofenceStatus,
                registrationError = geofenceErrors,
                onToggle = { item ->
                    if (existingEntryId != null) {
                        GeofenceHelper.registerGeofence(context, existingEntryId, item) { success, error ->
                            geofenceStatus = geofenceStatus + (item.id to success)
                            if (error != null) geofenceErrors = geofenceErrors + (item.id to error)
                        }
                    } else {
                        geofenceStatus = geofenceStatus - item.id
                        geofenceErrors = geofenceErrors - item.id
                    }
                }
            )

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
                    Text("↳ referenced in note text", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                ResolvingAttachmentPreview(
                    name = name,
                    onRemove = { existingAttachmentNames = existingAttachmentNames.filterNot { it == name } }
                )
            }
            newAttachments.forEach { attachment ->
                if (attachment.name in markerNames) {
                    Text("↳ referenced in note text", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                AttachmentPreview(
                    name = attachment.name,
                    uri = attachment.uri,
                    onRemove = { newAttachments = newAttachments.filterNot { it.name == attachment.name } }
                )
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
                if (saved != null) {
                    newAttachments = newAttachments + saved
                    insertAtCursor("✍[drawing: ${saved.name}]")
                }
                showScribblePad = false
            },
            onCancel = { showScribblePad = false }
        )
    }
}
