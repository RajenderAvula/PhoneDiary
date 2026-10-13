package com.example.phonediary.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Remembers which model worked, so discovery only runs once (and again if that model is retired or stalls). */
object GeminiModelStore {
    private const val PREFS = "phone_diary_ai"
    private const val KEY = "gemini_model"
    private const val MANUAL_KEY = "gemini_model_manual"
    fun getManual(context: Context): String? = prefs(context).getString(MANUAL_KEY, null)?.takeIf { it.isNotBlank() }
    fun setManual(context: Context, model: String?) {
        if (model == null) prefs(context).edit().remove(MANUAL_KEY).apply()
        else prefs(context).edit().putString(MANUAL_KEY, model).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): String? = prefs(context).getString(KEY, null)?.takeIf { it.isNotBlank() }
    fun save(context: Context, model: String) = prefs(context).edit().putString(KEY, model).apply()
    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()
}

object GeminiClient {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val MAX_MODELS_TO_TRY = 4

    private val excluded = listOf(
        "image", "tts", "embedding", "audio", "live", "robotics",
        "computer-use", "vision", "aqa", "imagen", "veo", "learnlm"
    )
    private val versionRegex = Regex("""gemini-(\d+(?:\.\d+)?)""")

    private class Candidate(val name: String) {
        val isFlash = name.contains("flash")
        val isLite = name.contains("lite")
        val isStable = listOf("preview", "exp", "experimental").none { name.contains(it) }
        val version = versionRegex.find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    private sealed class CallOutcome {
        class Success(val root: JSONObject) : CallOutcome()
        object ModelNotFound : CallOutcome()
        object TimedOut : CallOutcome()
        class Failure(val message: String) : CallOutcome()
    }

    class FunctionCall(val name: String, val args: JSONObject)

    /** One model reply: its raw content (to send back verbatim), plain text, and any tool calls. */
    class ChatTurn(val content: JSONObject, val text: String, val calls: List<FunctionCall>)
    /** Every model this key can call generateContent on, newest-looking first. */
    suspend fun listAvailableModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val names = mutableListOf<String>()
            var pageToken: String? = null
            var pages = 0
            do {
                val url = "$BASE/models?pageSize=200" + (pageToken?.let { "&pageToken=$it" } ?: "")
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 20_000
                    setRequestProperty("x-goog-api-key", apiKey)
                }
                try {
                    val code = c.responseCode
                    val text = (if (code in 200..299) c.inputStream else c.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) return@withContext Result.failure(Exception(friendlyError(code, apiMessage(text))))
                    val root = JSONObject(text)
                    val models = root.optJSONArray("models") ?: JSONArray()
                    for (i in 0 until models.length()) {
                        val m = models.optJSONObject(i) ?: continue
                        val name = m.optString("name").removePrefix("models/")
                        val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                        if ((0 until methods.length()).any { methods.optString(it) == "generateContent" }) names += name
                    }
                    pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
                } finally { c.disconnect() }
                pages++
            } while (pageToken != null && pages < 5)
            Result.success(names.distinct().sortedDescending())
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("No internet connection."))
        } catch (e: Exception) {
            Result.failure(Exception("Couldn't load models: ${e.message}"))
        }
    }

    /** Single-prompt convenience used by the daily summary. */
    suspend fun generate(
        context: Context,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String
    ): Result<String> {
        val contents = JSONArray().put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
        )
        return chat(context, apiKey, systemPrompt, contents, null).map { it.text }
    }

    /** One request/response with optional tool declarations. */
    suspend fun chat(
        context: Context,
        apiKey: String,
        systemPrompt: String,
        contents: JSONArray,
        tools: JSONArray?,
        temperature: Double = 0.4,
        maxOutputTokens: Int = 4096
    ): Result<ChatTurn> {
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            put("contents", contents)
            if (tools != null && tools.length() > 0) put("tools", tools)
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", temperature)
                    .put("maxOutputTokens", maxOutputTokens)
                    // A small thinking budget keeps answers fast. Models that reject it are skipped.
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 512))
            )
        }
        val root = requestWithModelDiscovery(context, apiKey, body).getOrElse { return Result.failure(it) }
        return parseTurn(root)
    }

    class WebAnswer(val text: String, val sources: List<String>)

    /** Answers one question using Google Search grounding. */
    suspend fun searchWeb(context: Context, apiKey: String, query: String): Result<WebAnswer> {
        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            "Answer using Google Search. Be factual and concise. " +
                                "Include dates for anything time-sensitive."
                        )
                    )
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", query)))
                )
            )
            put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 2048))
        }
        val root = requestWithModelDiscovery(context, apiKey, body).getOrElse { return Result.failure(it) }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.has("text") && !p.optBoolean("thought", false)) append(p.optString("text"))
            }
        }.trim()
        if (text.isBlank()) return Result.failure(Exception("The web search returned nothing."))

        val chunks = candidate?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        val sources = mutableListOf<String>()
        if (chunks != null) for (i in 0 until chunks.length()) {
            val title = chunks.optJSONObject(i)?.optJSONObject("web")?.optString("title").orEmpty()
            if (title.isNotBlank() && title !in sources) sources += title
        }
        return Result.success(WebAnswer(text, sources.take(5)))
    }

    private fun parseTurn(root: JSONObject): Result<ChatTurn> {
        val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason")
        if (!blockReason.isNullOrBlank()) {
            return Result.failure(Exception("Gemini blocked this request ($blockReason)."))
        }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        val content = candidate?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")

        val text = StringBuilder()
        val calls = mutableListOf<FunctionCall>()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                part.optJSONObject("functionCall")?.let { fc ->
                    calls += FunctionCall(fc.optString("name"), fc.optJSONObject("args") ?: JSONObject())
                }
                if (part.has("text") && !part.optBoolean("thought", false)) text.append(part.optString("text"))
            }
        }

        if (text.isBlank() && calls.isEmpty()) {
            val finish = candidate?.optString("finishReason").orEmpty()
            return Result.failure(
                Exception(
                    when (finish) {
                        "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT" -> "Gemini declined to answer that."
                        "MAX_TOKENS" -> "The answer was too long to finish. Try a narrower question."
                        else -> "Gemini returned an empty answer. Try again."
                    }
                )
            )
        }
        // The model's content must be sent back unchanged on the next step (it can carry hidden signatures).
        val raw = content ?: JSONObject().put("parts", JSONArray())
        raw.put("role", "model")
        return Result.success(ChatTurn(raw, text.toString().trim(), calls))
    }

    private suspend fun requestWithModelDiscovery(
        context: Context,
        apiKey: String,
        body: JSONObject
    ): Result<JSONObject> {
        val manual = GeminiModelStore.getManual(context)
        if (manual != null) {
            var outcome = call(apiKey, manual, body)
            if (outcome is CallOutcome.TimedOut) outcome = call(apiKey, manual, body)
            if (outcome is CallOutcome.ModelNotFound) {
                // Some models reject the thinking setting — retry once without it.
                val plain = JSONObject(body.toString())
                plain.optJSONObject("generationConfig")?.remove("thinkingConfig")
                outcome = call(apiKey, manual, plain)
            }
            return when (outcome) {
                is CallOutcome.Success -> Result.success(outcome.root)
                CallOutcome.ModelNotFound -> Result.failure(
                    Exception("The selected model \"$manual\" isn't available or can't do this. Pick another in Settings.")
                )
                CallOutcome.TimedOut -> Result.failure(
                    Exception("\"$manual\" is responding very slowly. Try again or pick another model in Settings.")
                )
                is CallOutcome.Failure -> Result.failure(Exception(outcome.message))
            }
        }
        val cached = GeminiModelStore.get(context)
        var queue: List<String> = listOfNotNull(cached)
        var discovered = false
        val tried = mutableSetOf<String>()
        var timedOutOnce = false

        while (tried.size < MAX_MODELS_TO_TRY) {
            if (queue.isEmpty()) {
                if (discovered) {
                    return Result.failure(Exception("No usable Gemini model was found for this API key."))
                }
                discovered = true
                queue = discoverModels(apiKey).getOrElse { return Result.failure(it) }
                if (queue.isEmpty()) {
                    return Result.failure(Exception("This API key has no Gemini model that supports text generation."))
                }
            }
            val model = queue.first()
            queue = queue.drop(1)
            if (!tried.add(model)) continue

            var outcome = call(apiKey, model, body)
            // One quiet retry: a first slow answer is often just a cold start.
            if (outcome is CallOutcome.TimedOut) outcome = call(apiKey, model, body)

            when (outcome) {
                is CallOutcome.Success -> {
                    GeminiModelStore.save(context, model)
                    return Result.success(outcome.root)
                }
                CallOutcome.ModelNotFound -> if (model == cached) GeminiModelStore.clear(context)
                CallOutcome.TimedOut -> {
                    // Stalled twice: forget this model and try the next candidate.
                    if (model == cached) GeminiModelStore.clear(context)
                    timedOutOnce = true
                }
                is CallOutcome.Failure -> return Result.failure(Exception(outcome.message))
            }
        }
        if (timedOutOnce) {
            return Result.failure(Exception("Gemini is responding very slowly right now. Wait a minute and try again."))
        }
        return Result.failure(Exception("Couldn't find a Gemini model that works. Try again later."))
    }

    /** Asks Google which models this key can use, best candidate first. */
    private suspend fun discoverModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val found = mutableListOf<Candidate>()
            var pageToken: String? = null
            var pages = 0
            do {
                val url = "$BASE/models?pageSize=200" + (pageToken?.let { "&pageToken=$it" } ?: "")
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("x-goog-api-key", apiKey)
                }
                try {
                    val code = connection.responseCode
                    val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        return@withContext Result.failure(Exception(friendlyError(code, apiMessage(text))))
                    }
                    val root = JSONObject(text)
                    val models = root.optJSONArray("models") ?: JSONArray()
                    for (i in 0 until models.length()) {
                        val m = models.optJSONObject(i) ?: continue
                        val name = m.optString("name").removePrefix("models/")
                        if (!name.startsWith("gemini-")) continue
                        if (excluded.any { name.contains(it) }) continue
                        val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                        val supportsGenerate = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                        if (supportsGenerate) found += Candidate(name)
                    }
                    pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
                } finally {
                    connection.disconnect()
                }
                pages++
            } while (pageToken != null && pages < 5)

            val ranked = found
                .sortedWith(
                    compareByDescending<Candidate> { it.isFlash && !it.isLite }
                        .thenByDescending { it.isStable }
                        .thenByDescending { it.version }
                        .thenBy { it.name.length }
                )
                .map { it.name }
                .distinct()
            Result.success(ranked)
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("No internet connection."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("Gemini took too long to answer. Try again."))
        } catch (e: Exception) {
            Result.failure(Exception("Couldn't reach Gemini: ${e.message}"))
        }
    }

    private suspend fun call(apiKey: String, model: String, body: JSONObject): CallOutcome =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$BASE/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    // Sent in a header, not the URL, so it never lands in logs or URLs.
                    setRequestProperty("x-goog-api-key", apiKey)
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code == 404) return@withContext CallOutcome.ModelNotFound
                if (code !in 200..299) {
                    val message = apiMessage(text)
                    // This model can't do something we need (tools, thinking, system prompt) — try the next one.
                    if (code == 400 && message != null &&
                        (message.contains("not enabled", true) || message.contains("not supported", true) ||
                            message.contains("thinking", true))
                    ) return@withContext CallOutcome.ModelNotFound
                    return@withContext CallOutcome.Failure(friendlyError(code, message))
                }
                CallOutcome.Success(JSONObject(text))
            } catch (e: java.net.UnknownHostException) {
                CallOutcome.Failure("No internet connection.")
            } catch (e: java.net.SocketTimeoutException) {
                CallOutcome.TimedOut
            } catch (e: Exception) {
                CallOutcome.Failure("Couldn't reach Gemini: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }

    private fun apiMessage(body: String): String? =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()

    private fun friendlyError(code: Int, apiMessage: String?): String = when (code) {
        400 -> if (apiMessage?.contains("API key", ignoreCase = true) == true)
            "Gemini rejected the API key. Check it in Settings."
        else "Gemini rejected the request: ${apiMessage ?: "bad request"}"
        401, 403 -> "The API key isn't allowed to use Gemini. Check it in Settings."
        429 -> "Gemini usage limit reached. Wait a minute and try again."
        in 500..599 -> "Gemini is having problems. Try again shortly."
        else -> "Gemini error $code: ${apiMessage ?: "unknown"}"
    }
}
