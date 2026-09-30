package com.example.phonediary.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.example.phonediary.files.FileAttachmentHelper
import com.example.phonediary.files.SavedAttachment
import kotlinx.coroutines.launch

/**
 * A dedicated full-screen text-writing surface with insert-at-cursor
 * support for: finger drawing/handwriting (saved as an image), an
 * inserted image, and an attached file link. Since notes are plain
 * text (not rich HTML), each insertion drops a readable marker at the
 * cursor and adds the real file as a note attachment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedTextEditor(
    initialText: String,
    onDone: (text: String, newAttachments: List<SavedAttachment>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fieldValue by remember {
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(initialText.length)))
    }
    var collectedAttachments by remember { mutableStateOf(listOf<SavedAttachment>()) }
    var showDrawing by remember { mutableStateOf(false) }

    fun insertAtCursor(marker: String) {
        val cursor = fieldValue.selection.start.coerceIn(0, fieldValue.text.length)
        val newText = fieldValue.text.substring(0, cursor) + marker + fieldValue.text.substring(cursor)
        val newCursor = cursor + marker.length
        fieldValue = TextFieldValue(newText, selection = TextRange(newCursor))
    }

    val insertImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = FileAttachmentHelper.copyToDownloads(context, uri)
                if (saved != null) {
                    collectedAttachments = collectedAttachments + saved
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
                    collectedAttachments = collectedAttachments + saved
                    insertAtCursor("🔗[file: ${saved.name}]")
                }
            }
        }
    }

    if (showDrawing) {
        HandDrawingCanvas(
            onSave = { bitmap ->
                val saved = FileAttachmentHelper.saveBitmapAsAttachment(context, bitmap)
                if (saved != null) {
                    collectedAttachments = collectedAttachments + saved
                    insertAtCursor("✍[drawing: ${saved.name}]")
                }
                showDrawing = false
            },
            onCancel = { showDrawing = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Write") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("✕ Cancel") }
                },
                actions = {
                    TextButton(onClick = { onDone(fieldValue.text, collectedAttachments) }) {
                        Text("Done")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showDrawing = true }) { Text("✍ Draw") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { insertImageLauncher.launch("image/*") }) { Text("🖼 Insert image") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { attachFileLauncher.launch(arrayOf("*/*")) }) { Text("🔗 Attach link") }
            }

            if (collectedAttachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Added: ${collectedAttachments.joinToString(", ") { it.name }}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("Write here…") }
            )
        }
    }
}
