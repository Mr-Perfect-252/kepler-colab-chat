package com.kepler.colabchat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val attempt: Int, val note: String) : ConnState
    data class Ready(val endpoint: Endpoint, val latencyMs: Long) : ConnState
    data class Dead(val reason: String) : ConnState
    /** A revival workflow is running; the app is polling for the new endpoint. */
    data class Reviving(val elapsedSec: Int, val note: String) : ConnState
}

/**
 * Owns the connection lifecycle:
 *
 *   1. ping the cached endpoint
 *   2. on 401 (key rotated) or unknown host (tunnel gone), re-read the rendezvous
 *   3. if that also fails, ask Actions to boot a fresh runtime and poll for the
 *      endpoint it publishes
 *
 * The previous version gave up after ~6 min AND overwrote the reviving state on
 * every failed poll, so the UI snapped back to a plain `dead` while a runtime was
 * still booting. Revival now owns the state for its whole duration.
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

    @Volatile
    private var reviving = false

    val isReviving: Boolean get() = reviving

    suspend fun connect(forceRediscovery: Boolean = false): Endpoint? {
        if (forceRediscovery) endpoint = null
        var lastError = "no endpoint"

        for (attempt in 1..maxAttempts) {
            if (!reviving) {
                _state.value = ConnState.Connecting(
                    attempt,
                    when {
                        attempt == 1 && endpoint == null -> "discovering endpoint"
                        attempt == 1 -> "pinging runtime"
                        else -> "re-discovering after failure"
                    },
                )
            }

            val candidate = endpoint ?: runCatching { source.fetch() }
                .onFailure { lastError = "rendezvous: ${it.message ?: "unreachable"}" }
                .getOrNull()

            if (candidate != null) {
                runCatching { client.ping(candidate) }
                    .onSuccess { latency ->
                        endpoint = candidate
                        reviving = false
                        _state.value = ConnState.Ready(candidate, latency)
                        return candidate
                    }
                    .onFailure { e ->
                        lastError = describe(e)
                        endpoint = null
                    }
            }

            if (attempt < maxAttempts) delay(backoffMillis(attempt))
        }

        if (!reviving) _state.value = ConnState.Dead(lastError)
        return null
    }

    /**
     * Dispatch the revive workflow and poll for up to [maxMinutes]. Each poll
     * re-reads the rendezvous, so the app reconnects the moment Actions commits the
     * new endpoint.
     */
    suspend fun reviveAndWait(maxMinutes: Int = 15): Boolean {
        if (reviving) return false
        val reviver = reviver
        if (reviver == null || !reviver.isConfigured) {
            _state.value = ConnState.Dead("revival not configured in this build")
            return false
        }

        reviving = true
        _state.value = ConnState.Reviving(0, "asking Actions to boot a T4")

        val accepted = withContext(Dispatchers.IO) { reviver.trigger() }
        if (!accepted) {
            reviving = false
            _state.value = ConnState.Dead("revival rejected — check the PAT")
            return false
        }

        val deadline = maxMinutes * 60
        var waited = 0
        while (waited < deadline) {
            delay(20_000)
            waited += 20
            _state.value = ConnState.Reviving(waited, "Actions is provisioning a T4")

            endpoint = null
            val candidate = runCatching { source.fetch() }.getOrNull()
            if (candidate != null) {
                val latency = runCatching { client.ping(candidate) }.getOrNull()
                if (latency != null) {
                    endpoint = candidate
                    reviving = false
                    _state.value = ConnState.Ready(candidate, latency)
                    return true
                }
            }
        }

        reviving = false
        _state.value = ConnState.Dead("revival timed out after ${maxMinutes}m")
        return false
    }

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

    private fun describe(e: Throwable): String = when (e) {
        is UnauthorizedException -> "key rotated — fetching new credentials"
        is UnknownHostException -> "tunnel gone (runtime reclaimed)"
        else -> e.message ?: "unreachable"
    }

    private fun backoffMillis(attempt: Int): Long =
        (1_000L shl (attempt - 1)).coerceAtMost(8_000L)
}
