package com.kepler.colabchat.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Asks GitHub Actions to boot a fresh Colab runtime.
 *
 * The app cannot mint Colab credentials, and it cannot start a VM — but a workflow
 * runner can do both: it holds the refresh token, runs `colab new --gpu T4`,
 * starts the server and publishes the new endpoint. The app's only job is to fire
 * `repository_dispatch` with a PAT baked in at build time.
 */
class Reviver(
    private val pat: String,
    private val repo: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = pat.isNotBlank() && repo.isNotBlank()

    /** True if GitHub accepted the dispatch. */
    fun trigger(): Boolean {
        if (!isConfigured) return false

        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/dispatches")
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .post("""{"event_type":"revive"}""".toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
