package com.example.phonediary.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.AttachmentListUtil
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.files.MediaResolveUtil
import com.example.phonediary.files.NotePathResolver
import com.example.phonediary.files.SubNoteManager
import com.example.phonediary.files.TimeStampManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** A note, sub-note, or (when locationId is set) one location reminder's note. */
data class NoteTarget(val entryId: Long, val locationId: String? = null) {
    val key: String get() = "$entryId/${locationId.orEmpty()}"
}

interface NoteNavigator {
    fun openSwitcher(self: NoteTarget?)
    fun minimize(self: NoteTarget?)
}

val LocalNoteNavigator = staticCompositionLocalOf<NoteNavigator?> { null }

suspend fun targetLabel(context: Context, t: NoteTarget): String? = withContext(Dispatchers.IO) {
    val e = AppDatabase.getInstance(context).logEntryDao().getById(t.entryId) ?: return@withContext null
    if (t.locationId == null) {
        NotePathResolver.label(e)
    } else {
        val item = LocationReminderListUtil.fromStored(e.locationReminders)
            .firstOrNull { it.id == t.locationId } ?: return@withContext null
        NotePathResolver.locationLabel(item) + "  (in ${NotePathResolver.label(e)})"
    }
}

@Composable
private fun rememberLabel(t: NoteTarget): String {
    val context = LocalContext.current
    return produceState("…", t.key) { value = targetLabel(context, t) ?: "(deleted)" }.value
}

/** Parked notes, shown at the top of Home. */
@Composable
fun NoteTrayRow(
    tray: List<NoteTarget>,
    notice: String?,
    onDismissNotice: () -> Unit,
    onOpen: (NoteTarget) -> Unit,
    onRemove: (NoteTarget) -> Unit,
    onBrowse: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tray.isEmpty()) "Open notes" else "Open notes (${tray.size})",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onBrowse) { Text("📚 Browse notes") }
        }
        if (tray.isNotEmpty()) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                tray.forEach { t ->
                    AssistChip(
                        onClick = { onOpen(t) },
                        label = { Text(rememberLabel(t), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    TextButton(
                        onClick = { onRemove(t) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) { Text("✕") }
                }
            }
        }
        notice?.let {
            TextButton(onClick = onDismissNotice) { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Opened from the ⇄ button inside an editor. */
@Composable
fun NoteSwitcherDialog(
    tray: List<NoteTarget>,
    onOpen: (NoteTarget) -> Unit,
    onRemove: (NoteTarget) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch note") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The note you're editing is saved and stays in this list.",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(8.dp))
                if (tray.isEmpty()) Text("No other open notes.", style = MaterialTheme.typography.bodySmall)
                tray.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(modifier = Modifier.weight(1f), onClick = { onOpen(t) }) {
                            Text(rememberLabel(t), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(onClick = { onRemove(t) }) { Text("✕") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBrowse) { Text("📚 Browse other notes…") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Pick any date or search, then View (read-only) or Edit any note. */
@Composable
fun BrowseNotesDialog(
    initialDate: String?,
    onView: (NoteTarget) -> Unit,
    onEdit: (NoteTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val clock = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    var date by remember { mutableStateOf(initialDate ?: fmt.format(System.currentTimeMillis())) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LogEntry>>(emptyList()) }

    fun shiftDay(by: Int) {
        val cal = Calendar.getInstance().apply { fmt.parse(date)?.let { time = it } }
        cal.add(Calendar.DAY_OF_MONTH, by)
        date = fmt.format(cal.time)
        query = ""
    }

    LaunchedEffect(date, query) {
        results = withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).logEntryDao()
            if (query.isBlank()) {
                dao.getEntriesForDate(date).filter { it.source == "manual_note" }
            } else {
                dao.searchEntries(query.trim())
                    .filter { it.source == "manual_note" || it.source == SubNoteManager.SOURCE }
            }.sortedBy { it.timestampMillis }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Browse notes") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search all notes…") }
                )
                if (query.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { shiftDay(-1) }) { Text("◀") }
                        OutlinedButton(onClick = {
                            val cal = Calendar.getInstance().apply { fmt.parse(date)?.let { time = it } }
                            android.app.DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    cal.set(y, m, d)
                                    date = fmt.format(cal.time)
                                },
                                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }) { Text("📅 $date") }
                        TextButton(onClick = { shiftDay(1) }) { Text("▶") }
                    }
                }
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                ) {
                    if (results.isEmpty()) {
                        Text(
                            if (query.isBlank()) "No notes on this date." else "No matches.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    results.forEach { e ->
                        val target = NoteTarget(e.id)
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(clock.format(e.timestampMillis), style = MaterialTheme.typography.labelSmall)
                            Text(NotePathResolver.label(e), style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = { onView(target) }) { Text("👁 View") }
                                TextButton(onClick = { onEdit(target) }) { Text("✏ Edit") }
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private class RefContent(
    val path: List<String>,
    val title: String,
    val text: String,
    val tags: List<String>,
    val attachments: List<String>
)

/** Read-only view of any note, for looking things up while you edit another. */
@Composable
fun ReferenceViewerDialog(
    initial: NoteTarget,
    onEdit: (NoteTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var stack by remember(initial.key) { mutableStateOf(listOf(initial)) }
    val target = stack.last()
    var loaded by remember(target.key) { mutableStateOf(false) }
    var content by remember(target.key) { mutableStateOf<RefContent?>(null) }

    LaunchedEffect(target.key) {
        content = withContext(Dispatchers.IO) {
            val e = AppDatabase.getInstance(context).logEntryDao().getById(target.entryId)
                ?: return@withContext null
            if (target.locationId == null) {
                RefContent(
                    path = NotePathResolver.pathOfEntry(context, e),
                    title = e.title.orEmpty(),
                    text = e.note.orEmpty(),
                    tags = AttachmentListUtil.toList(e.tags),
                    attachments = AttachmentListUtil.toList(e.attachmentFileName).distinct()
                )
            } else {
                val item = LocationReminderListUtil.fromStored(e.locationReminders)
                    .firstOrNull { it.id == target.locationId } ?: return@withContext null
                RefContent(
                    path = NotePathResolver.pathOfEntry(context, e) + NotePathResolver.locationLabel(item),
                    title = item.title,
                    text = item.text,
                    tags = item.tags,
                    attachments = item.attachmentNames
                )
            }
        }
        loaded = true
    }

    fun openMarker(type: String, name: String) {
        when (type) {
            SubNoteManager.TYPE -> SubNoteManager.parseId(name)?.let { stack = stack + NoteTarget(it) }
            TimeStampManager.TYPE -> {}
            else -> scope.launch {
                val uri = MediaResolveUtil.resolve(context, name) ?: return@launch
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Open with"))
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (stack.size > 1) {
                        TextButton(onClick = { stack = stack.dropLast(1) }) { Text("◀ Back") }
                    }
                    Text("👁 Reference (read-only)", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("✕ Close") }
                }
                val c = content
                if (c == null) {
                    Text(
                        if (loaded) "That note no longer exists." else "Loading…",
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    NoteHierarchyBar(c.path)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (c.title.isNotBlank()) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                        }
                        ScrollableNoteBox(wrapLines = true) { minWidth ->
                            NoteViewRenderer(
                                text = c.text,
                                modifier = Modifier.widthIn(min = minWidth),
                                wrap = true,
                                onMarkerClick = { type, name -> openMarker(type, name) }
                            )
                        }
                        if (c.tags.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(c.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodySmall)
                        }
                        if (c.attachments.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Attachments", style = MaterialTheme.typography.titleSmall)
                            c.attachments.forEach { ResolvingAttachmentPreview(name = it) }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.setText(
                                AnnotatedString(
                                    (if (c.title.isNotBlank()) c.title + "\n\n" else "") +
                                        TimeStampManager.toPlainText(c.text)
                                )
                            )
                        }) { Text("Copy text") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onEdit(target) }) { Text("✏ Edit this note") }
                    }
                }
            }
        }
    }
}
