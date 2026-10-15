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
import java.text.SimpleDateFormat
import java.util.Locale

enum class NodeKind { SUB_NOTE, LOCATION_NOTE, INFO }

class NoteNode(
    val key: String,
    val kind: NodeKind,
    val label: String,
    val detail: String,
    val entryId: Long?,          // sub-note id
    val parentEntryId: Long?,    // for a location note: the row that owns it
    val locationId: String?,
    val children: List<NoteNode>
) {
    fun descendantCount(): Int = children.sumOf { 1 + it.descendantCount() }
}

object NoteTree {
    private const val MAX_DEPTH = 12

    private fun info(key: String, label: String, detail: String = "", children: List<NoteNode> = emptyList()) =
        NoteNode(key, NodeKind.INFO, label, detail, null, null, null, children)

    private fun minutes(millis: Long?): String {
        val m = (millis ?: 0L) / 60_000
        return if (m >= 60) "${m / 60}h ${"%02d".format(m % 60)}m" else "${m}m"
    }

    private fun clock(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(millis)

    /** Tree for any row: notes get their sub-notes; usage and screen rows get detail lines plus their own sub-notes. */
    suspend fun buildAny(context: Context, entry: LogEntry): List<NoteNode> = withContext(Dispatchers.IO) {
        when (entry.source) {
            "app_usage" -> usageNodes(context, entry) + childrenOf(context, entry, mutableSetOf(entry.id), 1)
            "screen_content" -> screenNodes(context, entry) + childrenOf(context, entry, mutableSetOf(entry.id), 1)
            else -> childrenOf(context, entry, mutableSetOf(entry.id), 1)
        }
    }

    private suspend fun usageNodes(context: Context, entry: LogEntry): List<NoteNode> {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        val sameDay = dao.getEntriesForDate(entry.dateKey).filter { it.source == "app_usage" }
        val dayTotal = sameDay.sumOf { it.durationMillis ?: 0L }
        val mine = entry.durationMillis ?: 0L
        val share = if (dayTotal > 0) (mine * 100 / dayTotal) else 0L
        val nodes = mutableListOf(
            info("u${entry.id}_t", "⏱ Total today", minutes(mine)),
            info("u${entry.id}_s", "📊 Share of the day's usage", "$share% of ${minutes(dayTotal)}"),
            info("u${entry.id}_l", "🕒 Last used", clock(entry.timestampMillis))
        )
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val base = runCatching { fmt.parse(entry.dateKey) }.getOrNull()
        if (base != null) {
            val earlier = (1..6).mapNotNull { back ->
                val key = fmt.format(base.time - back * 24L * 60 * 60 * 1000)
                val row = dao.getEntriesForDate(key)
                    .filter { it.source == "app_usage" && it.appName == entry.appName }
                    .maxByOrNull { it.durationMillis ?: 0L }
                row?.let { info("u${entry.id}_d$key", key, minutes(it.durationMillis)) }
            }
            if (earlier.isNotEmpty()) {
                nodes += info("u${entry.id}_h", "📅 Previous days", "${earlier.size} day(s)", earlier)
            }
        }
        return nodes
    }

    private suspend fun screenNodes(context: Context, entry: LogEntry): List<NoteNode> {
        val dao = AppDatabase.getInstance(context).logEntryDao()
        val opens = dao.getEntriesForDate(entry.dateKey)
            .filter { it.source == "screen_content" && it.appName == entry.appName }
            .sortedBy { it.timestampMillis }
        val nodes = mutableListOf<NoteNode>()
        nodes += info("c${entry.id}_a", "📱 App", entry.appName.orEmpty())
        nodes += info("c${entry.id}_t", "🕒 Opened at", clock(entry.timestampMillis))
        entry.note?.takeIf { it.isNotBlank() }?.let { nodes += info("c${entry.id}_n", "🖥 Screen", it) }
        val others = opens.filter { it.id != entry.id }
        if (others.isNotEmpty()) {
            nodes += info(
                "c${entry.id}_o", "↻ Other opens today", "${others.size}",
                others.map { info("c${entry.id}_o${it.id}", clock(it.timestampMillis), it.note.orEmpty()) }
            )
        }
        return nodes
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
    onOpen: (NoteNode) -> Unit,
    onDelete: (NoteNode) -> Unit = {}
) {
    val context = LocalContext.current
    var nodes by remember(entry.id) { mutableStateOf<List<NoteNode>?>(null) }
    var localTick by remember { mutableStateOf(0) }

    LaunchedEffect(entry.id, entry.lastModifiedMillis, refreshKey, localTick) {
        nodes = NoteTree.buildAny(context, entry)
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
            Text("Nothing inside.", style = MaterialTheme.typography.bodySmall)
        }
        list?.forEach { NodeItem(it, 0, collapsed, onToggleBranch, onOpen, onDelete) }
    }
}

@Composable
private fun NodeItem(
    node: NoteNode,
    depth: Int,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (NoteNode) -> Unit,
    onDelete: (NoteNode) -> Unit
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
        if (node.kind != NodeKind.INFO) {
            TextButton(
                onClick = { onDelete(node) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("🗑") }
        }
    }
    if (!isCollapsed) {
        node.children.forEach { NodeItem(it, depth + 1, collapsed, onToggle, onOpen, onDelete) }
    }
}

