package com.example.phonediary.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.phonediary.ai.DailySummaryGenerator
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.DiaryEntry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private fun sigPrefs(context: Context) =
    context.getSharedPreferences("phone_diary_summary_sig", Context.MODE_PRIVATE)

@Composable
fun DailySummaryCard(dateKey: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val whenFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    var generatedText by remember(dateKey) { mutableStateOf("") }
    var editedText by remember(dateKey) { mutableStateOf("") }
    var generatedAt by remember(dateKey) { mutableStateOf<Long?>(null) }
    var isStale by remember(dateKey) { mutableStateOf(false) }
    var isGenerating by remember(dateKey) { mutableStateOf(false) }
    var error by remember(dateKey) { mutableStateOf<String?>(null) }
    var editing by remember(dateKey) { mutableStateOf(false) }
    var draft by remember(dateKey) { mutableStateOf("") }
    var confirmReplace by remember(dateKey) { mutableStateOf(false) }
    var reloadTick by remember(dateKey) { mutableStateOf(0) }

    val shown = editedText.ifBlank { generatedText }

    LaunchedEffect(dateKey, reloadTick) {
        val saved = AppDatabase.getInstance(context).diaryEntryDao().getEntryForDate(dateKey)
        generatedText = saved?.generatedText.orEmpty()
        editedText = saved?.editedText.orEmpty()
        generatedAt = saved?.generatedAtMillis
        val storedSig = sigPrefs(context).getString(dateKey, null)
        isStale = saved != null && storedSig != null &&
            storedSig != DailySummaryGenerator.buildInput(context, dateKey).signature
    }

    fun runGenerate() {
        isGenerating = true
        error = null
        scope.launch {
            val input = DailySummaryGenerator.buildInput(context, dateKey)
            DailySummaryGenerator.generate(context, input, dateKey)
                .onSuccess { text ->
                    val now = System.currentTimeMillis()
                    AppDatabase.getInstance(context).diaryEntryDao().upsert(
                        DiaryEntry(dateKey = dateKey, generatedText = text, editedText = "", generatedAtMillis = now)
                    )
                    sigPrefs(context).edit().putString(dateKey, input.signature).apply()
                    reloadTick++
                }
                .onFailure { error = it.message ?: "Couldn't generate the summary." }
            isGenerating = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✨ Daily summary", style = MaterialTheme.typography.titleSmall)
                Button(
                    enabled = !isGenerating && !editing,
                    onClick = { if (editedText.isNotBlank()) confirmReplace = true else runGenerate() }
                ) {
                    Text(
                        when {
                            isGenerating -> "Generating…"
                            shown.isBlank() -> "Generate"
                            else -> "Regenerate"
                        }
                    )
                }
            }

            if (isGenerating) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            error?.let {
                Spacer(Modifier.height(6.dp))
                Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (editing) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5
                )
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            AppDatabase.getInstance(context).diaryEntryDao().upsert(
                                DiaryEntry(
                                    dateKey = dateKey,
                                    generatedText = generatedText,
                                    editedText = draft.trim(),
                                    generatedAtMillis = generatedAt ?: System.currentTimeMillis()
                                )
                            )
                            editing = false
                            reloadTick++
                        }
                    }) { Text("Save") }
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                }
            } else if (shown.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(shown, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                val meta = buildList {
                    generatedAt?.let { add("Generated ${whenFmt.format(it)}") }
                    if (editedText.isNotBlank()) add("edited by you")
                }.joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
                if (isStale) {
                    Text(
                        "Notes or usage changed since this was generated — tap Regenerate to update.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row {
                    TextButton(onClick = { draft = shown; editing = true }) { Text("Edit") }
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Diary — $dateKey")
                            putExtra(Intent.EXTRA_TEXT, shown)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share summary"))
                    }) { Text("📤 Share") }
                }
            } else if (!isGenerating && error == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Turns this day's notes and app usage into a short diary entry, in time order.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace your edited summary?") },
            text = { Text("Regenerating writes a new summary and discards the version you edited.") },
            confirmButton = { TextButton(onClick = { confirmReplace = false; runGenerate() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Keep mine") } }
        )
    }
}
