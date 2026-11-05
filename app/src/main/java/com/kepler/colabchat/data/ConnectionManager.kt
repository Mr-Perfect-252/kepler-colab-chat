package com.kepler.colabchat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val attempt: Int, val note: String) : ConnState
    data class Ready(val endpoint: Endpoint, val latencyMs: Long) : ConnState
    data class Dead(val reason: String, val revivalRequested: Boolean = false) : ConnState
}

/**
 * Owns the connection lifecycle:
 *
 *   1. ping the cached endpoint
 *   2. on 401 (key rotated) or transport error, re-read the rendezvous and ping again
 *   3. back off and repeat, then surface [ConnState.Dead] with the real reason
 *
 * It cannot mint credentials — rotation happens on the VM when `serve.sh` boots.
 * What it *can* do is notice the rotation and reconnect without user involvement.
 */
class ConnectionManager(
    private val source: EndpointSource,
    private val client: ColabClient = ColabClient(),
    private val reviver: Reviver? = null,
    private val maxAttempts: Int = 3,
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    @Volatile
    var endpoint: Endpoint? = null
        private set

    /** Non-blocking view for the UI badge. */
    val isReady: Boolean get() = _state.value is ConnState.Ready

    suspend fun connect(forceRediscovery: Boolean = false): Endpoint? {
        if (forceRediscovery) endpoint = null
        var lastError = "no endpoint"

        for (attempt in 1..maxAttempts) {
            val note = when {
                attempt == 1 && endpoint == null -> "discovering endpoint"
                attempt == 1 -> "pinging runtime"
                else -> "re-discovering after failure"
            }
            _state.value = ConnState.Connecting(attempt, note)

            val candidate = endpoint ?: runCatching { source.fetch() }
                .onFailure { lastError = "rendezvous: ${it.message ?: "unreachable"}" }
                .getOrNull()

            if (candidate != null) {
                runCatching { client.ping(candidate) }
                    .onSuccess { latency ->
                        endpoint = candidate
                        _state.value = ConnState.Ready(candidate, latency)
                        return candidate
                    }
                    .onFailure { e ->
                        lastError = when (e) {
                            is UnauthorizedException -> "key rotated — fetching new credentials"
                            else -> e.message ?: "unreachable"
                        }
                        // Drop the stale creds so the next round fetches fresh ones.
                        endpoint = null
                    }
            }

            if (attempt < maxAttempts) delay(backoffMillis(attempt))
        }

        _state.value = ConnState.Dead(lastError)
        return null
    }

    /**
     * Kick off a remote revival: ask Actions to boot a runtime and publish fresh
     * credentials. The UI keeps polling after this returns, so the app reconnects
     * on its own once the workflow has written the new endpoint.
     */
    suspend fun revive(): Boolean {
        val reviver = reviver ?: return false
        if (!reviver.isConfigured) return false
        val accepted = withContext(Dispatchers.IO) { reviver.trigger() }
        _state.value = ConnState.Dead(
            reason = if (accepted) "runtime down — revival started" else "runtime down — revival failed",
            revivalRequested = accepted,
        )
        return accepted
    }

    /**
     * Send with one automatic recovery: a 401 mid-stream means the VM restarted
     * between ping and send, so rediscover once and retry.
     */
    suspend fun streamWithRecovery(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
    ) {
        val first = endpoint ?: connect() ?: throw IOException("not connected")
        try {
            client.streamChat(first, messages, onDelta)
        } catch (e: UnauthorizedException) {
            _state.value = ConnState.Connecting(1, "key rotated — reconnecting")
            val fresh = connect(forceRediscovery = true) ?: throw e
            client.streamChat(fresh, messages, onDelta)
        }
    }

    private fun backoffMillis(attempt: Int): Long =
        (1_000L shl (attempt - 1)).coerceAtMost(8_000L)   // 1s, 2s, 4s, 8s
}
