package com.kepler.colabchat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Where the app finds the current Colab endpoint.
 *
 * The VM rewrites this document on every `serve.sh` boot (new tunnel URL + new
 * API key), so the app can survive a rotation without the user touching it.
 */
@Serializable
data class Endpoint(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("model") val model: String = "mistral-small-3.1-24b",
    @SerialName("updated_at") val updatedAt: Long = 0L,
    @SerialName("session") val session: String? = null,
) {
    val modelsUrl: String get() = baseUrl.trimEnd('/') + "/models"
    val chatUrl: String get() = baseUrl.trimEnd('/') + "/chat/completions"
}

interface EndpointSource {
    /** Fetch the live endpoint document. Throws if unreachable. */
    suspend fun fetch(): Endpoint
}

/** Hardcoded creds — fine for a fixed deployment, useless once the key rotates. */
class StaticEndpointSource(private val endpoint: Endpoint) : EndpointSource {
    override suspend fun fetch(): Endpoint = endpoint
}

/**
 * Reads a JSON document the VM publishes (gist, object store, any HTTPS URL).
 *
 * A *secret* gist is unlisted but readable without auth; pass [token] only if your
 * rendezvous requires one. Keep such a token in local storage, never in source.
 */
class RemoteEndpointSource(
    private val url: String,
    private val token: String? = null,
) : EndpointSource {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(): Endpoint {
        val req = Request.Builder()
            .url(url)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("rendezvous HTTP ${resp.code}")
            }
            return json.decodeFromString(Endpoint.serializer(), body)
        }
    }
}

/**
 * Try the published rendezvous first, fall back to credentials baked into the APK.
 *
 * This is the single-user path: the bootstrap works when the app is fresh and the
 * gist is stale, and the rendezvous wins once the VM has rotated.
 */
class FallbackEndpointSource(
    private val primary: EndpointSource,
    private val fallback: EndpointSource,
) : EndpointSource {
    override suspend fun fetch(): Endpoint =
        runCatching { primary.fetch() }.getOrElse { fallback.fetch() }
}
