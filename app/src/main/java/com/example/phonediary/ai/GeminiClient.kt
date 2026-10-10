package com.example.phonediary.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Remembers which model worked, so discovery only runs once (and again if that model is retired). */
object GeminiModelStore {
    private const val PREFS = "phone_diary_ai"
    private const val KEY = "gemini_model"

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
        class Success(val text: String) : CallOutcome()
        object ModelNotFound : CallOutcome()
        class Failure(val message: String) : CallOutcome()
    }

    suspend fun generate(
        context: Context,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String
    ): Result<String> {
        val cached = GeminiModelStore.get(context)
        var queue: List<String> = listOfNotNull(cached)
        var discovered = false
        val tried = mutableSetOf<String>()

        while (tried.size < MAX_MODELS_TO_TRY) {
            if (queue.isEmpty()) {
                if (discovered) {
                    return Result.failure(Exception("No usable Gemini model was found for this API key."))
                }
                discovered = true
                queue = discoverModels(apiKey).getOrElse { return Result.failure(it) }
                if (queue.isEmpty()) {
                    return Result.failure(
                        Exception("This API key has no Gemini model that supports text generation.")
                    )
                }
            }
            val model = queue.first()
            queue = queue.drop(1)
            if (!tried.add(model)) continue

            when (val outcome = call(apiKey, model, systemPrompt, userPrompt)) {
                is CallOutcome.Success -> {
                    GeminiModelStore.save(context, model)
                    return Result.success(outcome.text)
                }
                CallOutcome.ModelNotFound -> {
                    // The remembered model is gone. Forget it; the loop moves to the next candidate.
                    if (model == cached) GeminiModelStore.clear(context)
                }
                is CallOutcome.Failure -> return Result.failure(Exception(outcome.message))
            }
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
                        .thenBy { it.name.length }   // shortest name first: the alias beats a dated copy
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

    private suspend fun call(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): CallOutcome = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                    )
                )
                put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 4096))
            }

            connection = (URL("$BASE/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
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
            if (code !in 200..299) return@withContext CallOutcome.Failure(friendlyError(code, apiMessage(text)))

            val root = JSONObject(text)
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason")
            if (!blockReason.isNullOrBlank()) {
                return@withContext CallOutcome.Failure("Gemini blocked this request ($blockReason).")
            }
            val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
            val out = buildString {
                if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }.trim()

            if (out.isBlank()) CallOutcome.Failure("Gemini returned an empty answer. Try again.")
            else CallOutcome.Success(out)
        } catch (e: java.net.UnknownHostException) {
            CallOutcome.Failure("No internet connection.")
        } catch (e: java.net.SocketTimeoutException) {
            CallOutcome.Failure("Gemini took too long to answer. Try again.")
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
