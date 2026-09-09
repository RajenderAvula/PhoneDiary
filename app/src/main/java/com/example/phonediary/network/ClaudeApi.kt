package com.example.phonediary.network

import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

data class ClaudeMessage(val role: String, val content: String)

data class ClaudeRequest(
    val model: String = "claude-sonnet-4-6",
    val max_tokens: Int = 1000,
    val messages: List<ClaudeMessage>
)

data class ClaudeContentBlock(val type: String, val text: String?)
data class ClaudeResponse(val content: List<ClaudeContentBlock>)

interface ClaudeApiService {
    @Headers(
        "Content-Type: application/json",
        "anthropic-version: 2023-06-01"
    )
    @POST("v1/messages")
    suspend fun sendMessage(@Body request: ClaudeRequest): ClaudeResponse
}
