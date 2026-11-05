package com.kepler.colabchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kepler.colabchat.data.ChatMessage
import com.kepler.colabchat.data.ColabClient
import com.kepler.colabchat.data.ConnState
import com.kepler.colabchat.data.ConnectionManager
import com.kepler.colabchat.data.Endpoint
import com.kepler.colabchat.data.EndpointSource
import com.kepler.colabchat.data.FallbackEndpointSource
import com.kepler.colabchat.data.RemoteEndpointSource
import com.kepler.colabchat.data.Reviver
import com.kepler.colabchat.data.StaticEndpointSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiMessage(val role: String, val text: String)

class ChatViewModel(
    private val source: EndpointSource = defaultSource(),
) : ViewModel() {

    private val manager = ConnectionManager(
        source = source,
        client = ColabClient(),
        reviver = Reviver(BuildConfig.GH_PAT, BuildConfig.GH_REPO),
    )

    val conn: StateFlow<ConnState> = manager.state

    private val _messages = MutableStateFlow(
        listOf(UiMessage("assistant", "Tap the status pill to connect, then ask anything."))
    )
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun connect(force: Boolean = false) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { manager.connect(forceRediscovery = force) }
        }
    }

    /** Ask Actions to boot a runtime, then keep retrying until it answers. */
    fun reviveAndReconnect() {
        viewModelScope.launch {
            val started = withContext(Dispatchers.IO) { manager.revive() }
            if (!started) return@launch
            // A T4 takes ~3-5 min: model download + load. Poll patiently.
            repeat(24) {
                kotlinx.coroutines.delay(15_000)
                if (withContext(Dispatchers.IO) { manager.connect(forceRediscovery = true) } != null) {
                    return@launch
                }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank() || _busy.value) return
        _busy.value = true
        _messages.value = _messages.value + UiMessage("user", text)
        val replyIndex = _messages.value.size
        _messages.value = _messages.value + UiMessage("assistant", "")

        viewModelScope.launch {
            val history = _messages.value
                .filter { it.text.isNotBlank() }
                .map { ChatMessage(it.role, it.text) }

            withContext(Dispatchers.IO) {
                runCatching {
                    manager.streamWithRecovery(history) { delta ->
                        val updated = _messages.value.toMutableList()
                        updated[replyIndex] = updated[replyIndex].let {
                            it.copy(text = it.text + delta)
                        }
                        _messages.value = updated
                    }
                }.onFailure { e ->
                    val updated = _messages.value.toMutableList()
                    updated[replyIndex] = UiMessage("assistant", "⚠️ ${e.message}")
                    _messages.value = updated
                }
            }
            _busy.value = false
        }
    }

    companion object {
        /**
         * Rendezvous first (survives a key/tunnel rotation), APK-baked creds as the
         * bootstrap. Single-user app — creds are intentionally not hidden.
         */
        private fun defaultSource(): EndpointSource = FallbackEndpointSource(
            primary = RemoteEndpointSource(
                url = BuildConfig.RENDEZVOUS_URL,
                token = BuildConfig.RENDEZVOUS_TOKEN.ifBlank { null },
            ),
            fallback = StaticEndpointSource(
                Endpoint(
                    baseUrl = BuildConfig.BOOTSTRAP_URL,
                    apiKey = BuildConfig.BOOTSTRAP_KEY,
                    model = BuildConfig.BOOTSTRAP_MODEL,
                )
            ),
        )
    }
}
