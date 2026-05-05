package team.hex.meshlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import team.hex.meshlink.service.MeshService
import team.hex.meshlink.storage.ChatMessageRow
import team.hex.meshlink.storage.MeshDb
import team.hex.meshlink.storage.PeerRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshNavHost(
    modifier: Modifier = Modifier,
    getService: () -> MeshService?,
) {
    var openChatPeer by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val db = remember { MeshDb.get(ctx) }

    if (openChatPeer == null) {
        Column(modifier = modifier.fillMaxSize()) {
            TopAppBar(title = { Text("MeshLink — peers") })
            PeerList(
                db = db,
                onClick = { openChatPeer = it },
            )
        }
    } else {
        val peerId = openChatPeer!!
        Column(modifier = modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(peerId) },
                navigationIcon = {
                    IconButton(onClick = { openChatPeer = null }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                    }
                }
            )
            ChatScreen(peerId = peerId, db = db, getService = getService)
        }
    }
}

@Composable
private fun PeerList(db: MeshDb, onClick: (String) -> Unit) {
    val peers by db.peerDao().streamAll().collectAsState(initial = emptyList())
    if (peers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Looking for nearby MeshLink nodes…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(peers, key = { it.nodeId }) { p ->
            PeerCard(p, onClick)
        }
    }
}

@Composable
private fun PeerCard(p: PeerRow, onClick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = { onClick(p.nodeId) },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text(p.nodeId, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChatScreen(peerId: String, db: MeshDb, getService: () -> MeshService?) {
    val messages by db.chatDao().streamForPeer(peerId).collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.msgId }) { row ->
                ChatBubble(row)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("message…") }
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isEmpty()) return@Button
                    val svc = getService() ?: return@Button
                    scope.launch { svc.sendText(peerId, text) }
                    draft = ""
                },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Send") }
        }
    }
}

@Composable
private fun ChatBubble(row: ChatMessageRow) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .align(if (row.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(4.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(row.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
