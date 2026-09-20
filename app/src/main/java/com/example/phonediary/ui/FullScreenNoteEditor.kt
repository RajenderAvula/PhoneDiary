package com.example.phonediary.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.phonediary.files.AudioRecorderHelper
import com.example.phonediary.files.FileAttachmentHelper
import com.example.phonediary.files.LocationPinHelper
import com.example.phonediary.files.MediaResolveUtil
import com.example.phonediary.files.NotePrintHelper
import com.example.phonediary.files.SavedAttachment
import com.example.phonediary.files.VideoCaptureHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** What the caller gets back when Save is tapped. */
data class FullScreenNoteResult(
    val title: String,
    val text: String,
    val locationUrl: String,
    val tags: List<String>,
    val existingAttachmentNames: List<String>,
    val newAttachments: List<SavedAttachment>,
    val reminderAtMillis: Long?,
    val dueAtMillis: Long?,
    val repeatRule: String
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
    highlightQuery: String? = null,
    onSave: (FullScreenNoteResult) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateTimeFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val audioRecorder = remember { AudioRecorderHelper(context) }

    var title by remember { mutableStateOf(initialTitle) }
    var text by remember { mutableStateOf(initialText) }
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
        if (uris.isNotEmpty()) {
            scope.launch {
                val saved = uris.mapNotNull { uri -> FileAttachmentHelper.copyToDownloads(context, uri) }
                newAttachments = newAttachments + saved
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
                        NotePrintHelper.printNote(context, title.ifBlank { "Phone Diary Note" }, text, tags)
                    }) {
                        Text("🖨 Print")
                    }
                    TextButton(onClick = {
                        onSave(
                            FullScreenNoteResult(
                                title = title,
                                text = text,
                                locationUrl = locationUrl,
                                tags = tags,
                                existingAttachmentNames = existingAttachmentNames,
                                newAttachments = newAttachments,
                                reminderAtMillis = reminderAtMillis,
                                dueAtMillis = dueAtMillis,
                                repeatRule = repeatRule
                            )
                        )
                    }) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Note title (optional) — shown in Calendar & searchable") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))

            if (!highlightQuery.isNullOrBlank()) {
                Text(
                    "Showing match for \"$highlightQuery\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("Write your note…") },
                visualTransformation = if (!highlightQuery.isNullOrBlank()) {
                    rememberThemedHighlight(highlightQuery)
                } else {
                    VisualTransformation.None
                }
            )

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
            Text("Attachments", style = MaterialTheme.typography.titleSmall)
            if (existingAttachmentNames.isNotEmpty()) {
                existingAttachmentNames.forEach { name ->
                    var resolvedUri by remember(name) { mutableStateOf<Uri?>(null) }
                    LaunchedEffect(name) { resolvedUri = MediaResolveUtil.resolve(context, name) }
                    AttachmentPreview(
                        name = name,
                        uri = resolvedUri,
                        onRemove = { existingAttachmentNames = existingAttachmentNames.filterNot { it == name } }
                    )
                }
            }
            if (newAttachments.isNotEmpty()) {
                newAttachments.forEach { attachment ->
                    AttachmentPreview(
                        name = attachment.name,
                        uri = attachment.uri,
                        onRemove = { newAttachments = newAttachments.filterNot { it.name == attachment.name } }
                    )
                }
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
}
