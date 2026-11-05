package com.kepler.colabchat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class UnauthorizedException : IOException("key rejected (401) — it was probably rotated")

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val temperature: Double = 0.3,
)

@Serializable
private data class ModelList(val data: List<ModelInfo> = emptyList())

@Serializable
private data class ModelInfo(val id: String = "")

/**
 * Thin OpenAI-compatible client. Only what the app needs: a cheap health probe and
 * a streaming completion.
 */
class ColabClient(
    private val pingClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // long generations must not be cut off
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Health probe. Returns round-trip latency in ms.
     * Throws [UnauthorizedException] when the key is stale, IOException otherwise.
     */
    fun ping(endpoint: Endpoint): Long {
        val started = System.nanoTime()
        val req = Request.Builder()
            .url(endpoint.modelsUrl)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .build()

        pingClient.newCall(req).execute().use { resp ->
            when {
                resp.code == 401 || resp.code == 403 -> throw UnauthorizedException()
                !resp.isSuccessful -> throw IOException("health HTTP ${resp.code}")
            }
            // Touch the body so the socket is drained and the call really completed.
            resp.body?.string()
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    /**
     * Streams a completion, invoking [onDelta] per token. Suspends-free: call from
     * Dispatchers.IO.
     */
    fun streamChat(
        endpoint: Endpoint,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
    ) {
        val payload = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model = endpoint.model, messages = messages),
        )

        val req = Request.Builder()
            .url(endpoint.chatUrl)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        streamClient.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw UnauthorizedException()
            if (!resp.isSuccessful) throw IOException("chat HTTP ${resp.code}")

            val source = resp.body?.source() ?: throw IOException("empty body")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                runCatching {
                    val node = json.parseToJsonElement(data).asObj()
                    node["choices"]?.asArr()?.firstOrNull()
                        ?.asObj()?.get("delta")?.asObj()?.get("content")
                        ?.asStr()?.let(onDelta)
                }
            }
        }
    }
}

// Minimal JSON helpers so we don't need a full typed schema for streamed chunks.
private fun kotlinx.serialization.json.JsonElement.asObj() =
    this as? kotlinx.serialization.json.JsonObject ?: error("not an object")

private fun kotlinx.serialization.json.JsonElement.asArr() =
    this as? kotlinx.serialization.json.JsonArray ?: error("not an array")

private fun kotlinx.serialization.json.JsonElement.asStr(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
