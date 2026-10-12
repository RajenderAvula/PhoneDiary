package com.example.phonediary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.phonediary.ai.AskAiEngine
import com.example.phonediary.ai.AskChat
import com.example.phonediary.ai.AskChatStore
import com.example.phonediary.ai.AskMessage
import com.example.phonediary.ai.GeminiKeyStore
import com.example.phonediary.files.StreamingSpeechHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private class Suggestion(val label: String, val prompt: String, val sendNow: Boolean)

private val suggestions = listOf(
    Suggestion("What was I doing yesterday at 21:40?", "What was I doing yesterday at 21:40?", true),
    Suggestion("Did I open PhoneDiary today?", "Did I open the Phone Diary app today?", true),
    Suggestion("How many notes do I have?", "How many notes do I have?", true),
    Suggestion("App usage this week", "Give me statistics of my time spent on each app this week, with app names.", true),
    Suggestion("Summarize a note by title", "Give me a summary of my note titled ", false),
    Suggestion("What's special today?", "What's special about today's date?", true),
    Suggestion("How do I make coffee?", "How do I make a good cup of coffee?", true)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskAiTab(isVisible: Boolean, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val timeFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    val codeBg = MaterialTheme.colorScheme.surface
    val listState = rememberLazyListState()

    var chatId by remember { mutableStateOf<String?>(null) }
    var chatCreatedAt by remember { mutableStateOf(0L) }
    var messages by remember { mutableStateOf(listOf<AskMessage>()) }
    var chats by remember { mutableStateOf(listOf<AskChat>()) }
    var showHistory by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    var confirmDeleteChat by remember { mutableStateOf<AskChat?>(null) }
    var confirmDeleteCurrent by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    // Re-checked each time the tab is shown, so a key saved in Settings is noticed.
    val hasKey = remember(isVisible) { GeminiKeyStore.hasKey(context) }

    // ---- Voice: 🎤 online one-shot, 🎙️ offline-first with live text ----
    val speech = remember { StreamingSpeechHelper(context) }
    var oneShotListening by remember { mutableStateOf(false) }
    var streamListening by remember { mutableStateOf(false) }
    var partialText by remember { mutableStateOf("") }
    var speechError by remember { mutableStateOf<String?>(null) }

    fun appendToInput(text: String) {
        input = if (input.isBlank()) text else "${input.trimEnd()} $text"
    }

    fun stopMics() {
        speech.forceStop { }
        oneShotListening = false
        streamListening = false
        partialText = ""
    }

    fun toggleOneShot() {
        if (oneShotListening) { speech.forceStop { oneShotListening = it }; return }
        if (streamListening) { speech.forceStop { streamListening = it }; partialText = "" }
        speechError = null
        speech.startOneShot(
            onResult = { appendToInput(it) },
            onError = { speechError = it },
            onListeningStateChanged = { oneShotListening = it }
        )
    }

    fun toggleStreaming() {
        if (streamListening) { speech.forceStop { streamListening = it }; return }
        if (oneShotListening) speech.forceStop { oneShotListening = it }
        speechError = null
        partialText = ""
        speech.start(
            onPartialResult = { partialText = it },
            onFinalResult = { appendToInput(it); partialText = "" },
            onError = { speechError = it; partialText = "" },
            onListeningStateChanged = { streamListening = it }
        )
    }

    DisposableEffect(Unit) { onDispose { speech.stop() } }
    LaunchedEffect(isVisible) { if (!isVisible) stopMics() }

    // ---- Chats ----
    LaunchedEffect(Unit) {
        chats = AskChatStore.loadAll(context)
        chats.firstOrNull()?.let {
            chatId = it.id
            chatCreatedAt = it.createdAt
            messages = it.messages
        }
    }

    fun startNewChat() {
        stopMics()
        chatId = null
        chatCreatedAt = 0L
        messages = emptyList()
        input = ""
        status = null
        showHistory = false
    }

    fun openChat(chat: AskChat) {
        stopMics()
        chatId = chat.id
        chatCreatedAt = chat.createdAt
        messages = chat.messages
        showHistory = false
    }

    fun refreshChats() {
        scope.launch { chats = AskChatStore.loadAll(context) }
    }

    fun persist(updated: List<AskMessage>) {
        val id = chatId ?: AskChatStore.newId().also {
            chatId = it
            chatCreatedAt = System.currentTimeMillis()
        }
        val chat = AskChat(
            id = id,
            title = AskChatStore.titleFrom(updated.firstOrNull { it.role == "user" }?.text ?: "New chat"),
            createdAt = chatCreatedAt,
            updatedAt = System.currentTimeMillis(),
            messages = updated
        )
        scope.launch {
            AskChatStore.save(context, chat)
            chats = AskChatStore.loadAll(context)
        }
    }

    fun deleteChat(chat: AskChat) {
        scope.launch {
            AskChatStore.delete(context, chat.id)
            chats = AskChatStore.loadAll(context)
            if (chatId == chat.id) startNewChat()
        }
    }

    fun deleteAllChats() {
        scope.launch {
            AskChatStore.deleteAll(context)
            chats = emptyList()
            startNewChat()
        }
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || isSending) return
        stopMics()
        input = ""
        speechError = null
        val withUser = messages + AskMessage(AskChatStore.newMessageId(), "user", text, System.currentTimeMillis())
        messages = withUser
        persist(withUser)
        isSending = true
        status = "Thinking…"
        scope.launch {
            val result = AskAiEngine.answer(context, withUser) { status = it }
            val reply = result.fold(
                onSuccess = { AskMessage(AskChatStore.newMessageId(), "model", it, System.currentTimeMillis()) },
                onFailure = {
                    AskMessage(AskChatStore.newMessageId(), "error", it.message ?: "Something went wrong.", System.currentTimeMillis())
                }
            )
            val finished = withUser + reply
            messages = finished
            persist(finished)
            isSending = false
            status = null
        }
    }

    // Keep the newest message in view.
    LaunchedEffect(messages.size, isSending, isVisible) {
        val last = messages.size + (if (isSending) 1 else 0) - 1
        if (isVisible && last >= 0) listState.animateScrollToItem(last)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .imePadding()
    ) {
        // ---- Header ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✨ Ask AI", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(
                enabled = !isSending,
                onClick = { if (!showHistory) refreshChats(); showHistory = !showHistory }
            ) { Text(if (showHistory) "Back" else "History") }
            TextButton(enabled = !isSending, onClick = { startNewChat() }) { Text("＋ New") }
            if (chatId != null && !showHistory) {
                TextButton(enabled = !isSending, onClick = { confirmDeleteCurrent = true }) { Text("🗑") }
            }
        }

        if (showHistory) {
            // ---- Chat history ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Saved chats (${chats.size})", style = MaterialTheme.typography.titleSmall)
                if (chats.isNotEmpty()) {
                    TextButton(enabled = !isSending, onClick = { confirmDeleteAll = true }) { Text("Delete all") }
                }
            }
            if (chats.isEmpty()) {
                Text("No saved chats yet.", style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(chats, key = { it.id }) { chat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isSending) { openChat(chat) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                chat.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (chat.id == chatId) FontWeight.Bold else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${chat.messages.size} message(s) · ${timeFmt.format(chat.updatedAt)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(enabled = !isSending, onClick = { confirmDeleteChat = chat }) { Text("🗑") }
                    }
                    Divider()
                }
            }
        } else {
            // ---- Key missing ----
            if (!hasKey) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Add your Gemini API key first.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                    }
                }
            }

            // ---- Conversation ----
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                "Ask about your notes, apps and time — or anything else.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                suggestions.forEach { s ->
                                    SuggestionChip(
                                        onClick = { if (s.sendNow) send(s.prompt) else input = s.prompt },
                                        label = { Text(s.label) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Your question and the notes or usage it needs are sent to Google Gemini to answer. " +
                                    "Chats are saved only on this phone.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        msg = msg,
                        timeLabel = timeFmt.format(msg.timeMillis),
                        codeBg = codeBg,
                        onCopy = { clipboard.setText(AnnotatedString(msg.text)) }
                    )
                }
                if (isSending) {
                    item {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(status ?: "Thinking…", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // ---- Voice feedback ----
            if (streamListening || partialText.isNotBlank()) {
                Text(
                    if (partialText.isNotBlank()) "🎙️ $partialText" else "🎙️ Listening…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (oneShotListening) {
                Text("🎤 Listening…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            speechError?.let {
                Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // ---- Input ----
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                placeholder = { Text("Ask about your notes, apps, or anything…") },
                maxLines = 5
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { toggleOneShot() }) { Text(if (oneShotListening) "🔴" else "🎤") }
                IconButton(onClick = { toggleStreaming() }) { Text(if (streamListening) "🔴" else "🎙️") }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = input.isNotBlank() && !isSending,
                    onClick = { send(input) }
                ) { Text("Send") }
            }
        }
    }

    // ---- Delete confirmations ----
    confirmDeleteChat?.let { chat ->
        ConfirmDeleteDialog(
            message = "Delete the chat \"${chat.title}\"? This cannot be undone.",
            onConfirm = { confirmDeleteChat = null; deleteChat(chat) },
            onDismiss = { confirmDeleteChat = null }
        )
    }
    if (confirmDeleteCurrent) {
        ConfirmDeleteDialog(
            message = "Delete this whole chat? This cannot be undone.",
            onConfirm = {
                confirmDeleteCurrent = false
                chats.firstOrNull { it.id == chatId }?.let { deleteChat(it) } ?: startNewChat()
            },
            onDismiss = { confirmDeleteCurrent = false }
        )
    }
    if (confirmDeleteAll) {
        ConfirmDeleteDialog(
            message = "Delete all ${chats.size} saved chats? This cannot be undone.",
            onConfirm = { confirmDeleteAll = false; deleteAllChats() },
            onDismiss = { confirmDeleteAll = false }
        )
    }
}

@Composable
private fun MessageBubble(msg: AskMessage, timeLabel: String, codeBg: Color, onCopy: () -> Unit) {
    val isUser = msg.role == "user"
    val isError = msg.role == "error"
    val container = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        isError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                SelectionContainer {
                    Text(
                        text = if (msg.role == "model") markdownLite(msg.text, codeBg)
                        else AnnotatedString(if (isError) "⚠ ${msg.text}" else msg.text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeLabel, style = MaterialTheme.typography.labelSmall)
                    if (msg.role == "model") {
                        Spacer(Modifier.width(6.dp))
                        TextButton(
                            onClick = onCopy,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) { Text("Copy", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

private val boldOrCode = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
private val headingRe = Regex("""^\s{0,3}#{1,6}\s+(.*)$""")
private val bulletRe = Regex("""^(\s*)[*\-+]\s+(.*)$""")

/** Minimal markdown for Gemini's answers: headings, bullets, **bold** and `code`. */
private fun markdownLite(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val lines = text.replace("\r", "").split("\n")
    lines.forEachIndexed { index, rawLine ->
        var line = rawLine
        var isHeading = false
        headingRe.matchEntire(line)?.let { line = it.groupValues[1]; isHeading = true }
        if (!isHeading) bulletRe.matchEntire(line)?.let { line = it.groupValues[1] + "• " + it.groupValues[2] }

        if (isHeading) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        var cursor = 0
        boldOrCode.findAll(line).forEach { m ->
            append(line.substring(cursor, m.range.first))
            val bold = m.groups[1]?.value
            val code = m.groups[2]?.value
            if (bold != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            } else if (code != null) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(code) }
            }
            cursor = m.range.last + 1
        }
        append(line.substring(cursor))
        if (isHeading) pop()
        if (index < lines.lastIndex) append("\n")
    }
}
