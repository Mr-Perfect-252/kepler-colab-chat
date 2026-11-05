package com.kepler.colabchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kepler.colabchat.data.ConnState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0D10)) {
                    ChatScreen()
                }
            }
        }
    }
}

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val conn by vm.conn.collectAsState()
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-connect on first composition.
    LaunchedEffect(Unit) { vm.connect() }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        containerColor = Color(0xFF0B0D10),
        bottomBar = {
            Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message Mistral Small…") },
                        maxLines = 6,
                        enabled = !busy,
                    )
                    TextButton(
                        enabled = draft.isNotBlank() && !busy,
                        onClick = { vm.send(draft); draft = "" },
                    ) { Text("Send", fontWeight = FontWeight.SemiBold) }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            StatusBar(
                state = conn,
                onRetry = { vm.connect(force = true) },
                onRevive = { vm.reviveAndReconnect() },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages) { m ->
                    val isUser = m.role == "user"
                    Row(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier
                                .fillMaxWidth(if (isUser) 0.85f else 1f)
                                .background(
                                    if (isUser) Color(0xFF1B2431) else Color.Transparent,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(10.dp)
                                .then(if (isUser) Modifier.align(Alignment.CenterEnd) else Modifier),
                        ) {
                            Column {
                                Text(
                                    if (isUser) "YOU" else "ASSISTANT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isUser) Color(0xFF8B95A5) else Color(0xFF5B9DFF),
                                )
                                Text(
                                    m.text.ifBlank { "…" },
                                    color = Color(0xFFE6E9EE),
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBar(state: ConnState, onRetry: () -> Unit, onRevive: () -> Unit) {
    val (color, label) = when (state) {
        is ConnState.Ready -> Color(0xFF3CCF91) to
            "connected · ${state.latencyMs} ms · ${state.endpoint.model}"
        is ConnState.Connecting -> Color(0xFFF5A524) to
            "${state.note} (attempt ${state.attempt})"
        is ConnState.Dead -> Color(0xFFFF6B6B) to when {
            state.revivalRequested -> "reviving · Actions is provisioning a T4 (~3-5 min)"
            else -> "dead · ${state.reason}"
        }
        ConnState.Idle -> Color(0xFF8B95A5) to "idle"
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            label,
            color = Color(0xFF8B95A5),
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            maxLines = 2,
        )
        if (state is ConnState.Dead && !state.revivalRequested) {
            TextButton(onClick = onRevive) { Text("revive", fontSize = 12.sp) }
        }
        TextButton(onClick = onRetry) { Text("retry", fontSize = 12.sp) }
        Box(Modifier.width(2.dp))
    }
}
