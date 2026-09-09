package com.example.phonediary.network

import com.example.phonediary.data.LogEntry
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object ClaudeClient {

    private class AuthInterceptor(private val apiKeyProvider: () -> String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .addHeader("x-api-key", apiKeyProvider())
                .build()
            return chain.proceed(request)
        }
    }

    fun create(apiKeyProvider: () -> String): ClaudeApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(apiKeyProvider))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(ClaudeApiService::class.java)
    }

    suspend fun generateDiaryText(api: ClaudeApiService, dateKey: String, entries: List<LogEntry>): String {
        if (entries.isEmpty()) {
            return "No activity was logged for $dateKey."
        }

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = entries.joinToString("\n") { entry ->
            val time = timeFormat.format(entry.timestampMillis)
            when (entry.source) {
                "app_usage" -> {
                    val minutes = (entry.durationMillis ?: 0L) / 60000
                    "$time - used ${entry.appName} for ${minutes}m"
                }
                "manual_note" -> "$time - note: ${entry.note}"
                "calendar" -> "$time - event: ${entry.note}"
                else -> "$time - ${entry.source}: ${entry.note ?: entry.appName ?: ""}"
            }
        }

        val prompt = """
            Here is a raw activity log for $dateKey, one line per event:

            $lines

            Turn this into a short, natural first-person diary entry (3-6 sentences)
            summarizing the day. Group related activity, skip trivial noise, and
            write it the way a person would journal about their own day. Do not
            invent details that aren't in the log.
        """.trimIndent()

        val response = api.sendMessage(
            ClaudeRequest(messages = listOf(ClaudeMessage(role = "user", content = prompt)))
        )

        return response.content.firstOrNull { it.type == "text" }?.text
            ?: "Could not generate a diary entry for $dateKey."
    }
}
