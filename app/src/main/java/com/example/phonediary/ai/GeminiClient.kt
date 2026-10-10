package com.example.phonediary.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiClient {

    /** If Google reports "model not found", change this to a model name listed in Google AI Studio. */
    const val MODEL = "gemini-2.5-flash"

    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    suspend fun generate(apiKey: String, systemPrompt: String, userPrompt: String): Result<String> =
        withContext(Dispatchers.IO) {
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
                    put(
                        "generationConfig",
                        JSONObject().put("temperature", 0.4).put("maxOutputTokens", 4096)
                    )
                }

                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
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
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    val apiMessage = runCatching {
                        JSONObject(text).optJSONObject("error")?.optString("message")
                    }.getOrNull()
                    return@withContext Result.failure(Exception(friendlyError(code, apiMessage)))
                }

                val root = JSONObject(text)
                val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason")
                if (!blockReason.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("Gemini blocked this request ($blockReason)."))
                }
                val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val out = buildString {
                    if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }.trim()

                if (out.isBlank()) Result.failure(Exception("Gemini returned an empty answer. Try again."))
                else Result.success(out)
            } catch (e: java.net.UnknownHostException) {
                Result.failure(Exception("No internet connection."))
            } catch (e: java.net.SocketTimeoutException) {
                Result.failure(Exception("Gemini took too long to answer. Try again."))
            } catch (e: Exception) {
                Result.failure(Exception("Couldn't reach Gemini: ${e.message}"))
            } finally {
                connection?.disconnect()
            }
        }

    private fun friendlyError(code: Int, apiMessage: String?): String = when (code) {
        400 -> if (apiMessage?.contains("API key", ignoreCase = true) == true)
            "Gemini rejected the API key. Check it in Settings."
        else "Gemini rejected the request: ${apiMessage ?: "bad request"}"
        401, 403 -> "The API key isn't allowed to use Gemini. Check it in Settings."
        404 -> "Model \"$MODEL\" wasn't found. Change MODEL in GeminiClient.kt."
        429 -> "Gemini usage limit reached. Wait a minute and try again."
        in 500..599 -> "Gemini is having problems. Try again shortly."
        else -> "Gemini error $code: ${apiMessage ?: "unknown"}"
    }
}
