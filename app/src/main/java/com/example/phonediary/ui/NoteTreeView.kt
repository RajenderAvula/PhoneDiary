package com.example.phonediary.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.phonediary.data.AppDatabase
import com.example.phonediary.data.LocationReminderListUtil
import com.example.phonediary.data.LogEntry
import com.example.phonediary.files.SubNoteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NodeKind { SUB_NOTE, LOCATION_NOTE }

class NoteNode(
    val key: String,
    val kind: NodeKind,
    val label: String,
    val detail: String,
    val entryId: Long?,          // sub-note id
    val parentEntryId: Long?,    // for a location note: the note that owns it
    val locationId: String?,
    val children: List<NoteNode>
) {
    fun descendantCount(): Int = children.sumOf { 1 + it.descendantCount() }
}

object NoteTree {
    private const val MAX_DEPTH = 12

    suspend fun build(context: Context, root: LogEntry): List<NoteNode> = withContext(Dispatchers.IO) {
        childrenOf(context, root, mutableSetOf(root.id), 1)
    }

    private suspend fun childrenOf(
        context: Context, entry: LogEntry, visited: MutableSet<Long>, depth: Int
    ): List<NoteNode> {
        if (depth > MAX_DEPTH) return emptyList()
        val nodes = mutableListOf<NoteNode>()
        nodes += subNodes(context, SubNoteManager.idsIn(entry.note), visited, depth)
        LocationReminderListUtil.fromStored(entry.locationReminders).forEach { item ->
            val detail = buildList {
                if (!item.enabled) add("off")
                if (item.title.isNotBlank()) add(item.title.trim())
                if (item.hasRepeat()) add("🔁")
            }.joinToString(" · ")
            nodes += NoteNode(
                key = "l${entry.id}_${item.id}",
                kind = NodeKind.LOCATION_NOTE,
                label = "📍 ${item.label}",
                detail = detail,
                entryId = null,
                parentEntryId = entry.id,
                locationId = item.id,
                children = subNodes(context, SubNoteManager.idsIn(item.text), visited, depth + 1)
            )
        }
        return nodes
    }

    private suspend fun subNodes(
        context: Context, ids: Set<Long>, visited: MutableSet<Long>, depth: Int
    ): List<NoteNode> {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        val result = mutableListOf<NoteNode>()
        for (id in ids) {
            if (!visited.add(id)) continue
            val e = dao.getById(id) ?: continue
            if (e.source != SubNoteManager.SOURCE) continue
            val label = e.title?.takeIf { it.isNotBlank() }
                ?: markerRegex.replace(e.note.orEmpty(), "").trim().take(40).ifBlank { "Untitled sub-note" }
            result += NoteNode(
                key = "s$id",
                kind = NodeKind.SUB_NOTE,
                label = "🗒 $label",
                detail = "",
                entryId = id,
                parentEntryId = null,
                locationId = null,
                children = childrenOf(context, e, visited, depth + 1)
            )
        }
        return result
    }
}

@Composable
fun NoteTreeView(
    entry: LogEntry,
    refreshKey: Int,
    collapsed: Set<String>,
    onToggleBranch: (String) -> Unit,
    onOpen: (NoteNode) -> Unit
) {
    val context = LocalContext.current
    var nodes by remember(entry.id) { mutableStateOf<List<NoteNode>?>(null) }
    var localTick by remember { mutableStateOf(0) }

    LaunchedEffect(entry.id, entry.lastModifiedMillis, refreshKey, localTick) {
        nodes = NoteTree.build(context, entry)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
        val total = nodes?.sumOf { 1 + it.descendantCount() } ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (nodes == null) "Loading…" else "Inside this note ($total)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { localTick++ }) { Text("⟳") }
        }
        val list = nodes
        if (list != null && list.isEmpty()) {
            Text("No sub-notes or location notes inside.", style = MaterialTheme.typography.bodySmall)
        }
        list?.forEach { NodeItem(it, 0, collapsed, onToggleBranch, onOpen) }
    }
}

@Composable
private fun NodeItem(
    node: NoteNode,
    depth: Int,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (NoteNode) -> Unit
) {
    val isCollapsed = node.key in collapsed
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = (minOf(depth, 8) * 14).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.children.isNotEmpty()) {
            TextButton(
                onClick = { onToggle(node.key) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text(if (isCollapsed) "▸" else "▾") }
        } else {
            Spacer(Modifier.width(32.dp))
        }
        TextButton(modifier = Modifier.weight(1f), onClick = { onOpen(node) }) {
            val hidden = if (isCollapsed && node.children.isNotEmpty()) "  (${node.descendantCount()} hidden)" else ""
            Text(
                text = node.label + (if (node.detail.isNotBlank()) " — ${node.detail}" else "") + hidden,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }
    }
    if (!isCollapsed) {
        node.children.forEach { NodeItem(it, depth + 1, collapsed, onToggle, onOpen) }
    }
}
