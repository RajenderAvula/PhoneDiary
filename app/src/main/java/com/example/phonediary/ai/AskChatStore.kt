package com.example.phonediary.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** role is "user", "model" or "error" (errors are shown in the chat but never sent to Gemini). */
data class AskMessage(val id: String, val role: String, val text: String, val timeMillis: Long)

data class AskChat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<AskMessage>
)

object AskChatStore {

    private const val DIR = "ask_ai_chats"

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '_' }
    private fun fileFor(context: Context, id: String) = File(dir(context), "${safe(id)}.json")

    fun newId(): String = "chat_${System.currentTimeMillis()}_${(0..9999).random()}"
    fun newMessageId(): String = "m_${System.currentTimeMillis()}_${(0..99999).random()}"

    fun titleFrom(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().take(40).ifBlank { "New chat" }

    suspend fun loadAll(context: Context): List<AskChat> = withContext(Dispatchers.IO) {
        dir(context).listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { parse(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    suspend fun save(context: Context, chat: AskChat) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("id", chat.id)
            put("title", chat.title)
            put("createdAt", chat.createdAt)
            put("updatedAt", chat.updatedAt)
            put("messages", JSONArray().also { arr ->
                chat.messages.forEach { m ->
                    arr.put(
                        JSONObject().put("id", m.id).put("role", m.role)
                            .put("text", m.text).put("time", m.timeMillis)
                    )
                }
            })
        }
        val target = fileFor(context, chat.id)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(json.toString())
            tmp.delete()
        }
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        fileFor(context, id).delete()
        Unit
    }

    suspend fun deleteAll(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun parse(file: File): AskChat? = try {
        val o = JSONObject(file.readText())
        val arr = o.optJSONArray("messages") ?: JSONArray()
        AskChat(
            id = o.getString("id"),
            title = o.optString("title", "Chat"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            messages = (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                AskMessage(m.optString("id"), m.optString("role"), m.optString("text"), m.optLong("time"))
            }
        )
    } catch (e: Exception) {
        null
    }
}
