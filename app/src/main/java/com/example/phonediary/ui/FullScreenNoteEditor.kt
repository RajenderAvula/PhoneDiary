package com.example.phonediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.phonediary.files.NotePrintHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenNoteEditor(
    initialText: String,
    initialTags: List<String>,
    highlightQuery: String? = null,
    onSave: (text: String, tags: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initialText) }
    var tagInput by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(initialTags) }

    fun addTagFromInput() {
        val cleaned = tagInput.trim().removePrefix("#")
        if (cleaned.isNotBlank() && cleaned !in tags) {
            tags = tags + cleaned
        }
        tagInput = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = {
                    TextButton(onClick = onDismiss) {
                        Text("← Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        NotePrintHelper.printNote(context, "Phone Diary Note", text, tags)
                    }) {
                        Text("🖨 Print")
                    }
                    TextButton(onClick = { onSave(text, tags) }) {
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
                    HighlightTransformation(highlightQuery)
                } else {
                    VisualTransformation.None
                }
            )

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
        }
    }
}
