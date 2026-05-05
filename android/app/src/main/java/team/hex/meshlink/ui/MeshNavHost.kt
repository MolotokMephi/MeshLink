package team.hex.meshlink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.hex.meshlink.MeshLinkApp
import team.hex.meshlink.pairing.PairingPayload
import team.hex.meshlink.service.MeshService
import team.hex.meshlink.storage.ChatMessageRow
import team.hex.meshlink.storage.GroupRow
import team.hex.meshlink.storage.MeshDb
import team.hex.meshlink.storage.PeerRow

private sealed class Screen {
    object Home : Screen()
    object Pairing : Screen()
    data class Chat(val scopeId: String, val kind: String, val title: String) : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshNavHost(
    modifier: Modifier = Modifier,
    getService: () -> MeshService?,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val ctx = LocalContext.current
    val db = remember { MeshDb.get(ctx) }

    when (val s = screen) {
        Screen.Home -> Column(modifier = modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("MeshLink") },
                actions = {
                    IconButton(onClick = { screen = Screen.Pairing }) {
                        Icon(Icons.Filled.QrCode, contentDescription = "pairing")
                    }
                }
            )
            HomeTabs(
                db = db,
                onPeer = { p -> screen = Screen.Chat(p.nodeId, MeshService.SCOPE_PEER, p.name) },
                onGroup = { g -> screen = Screen.Chat(g.groupId, MeshService.SCOPE_GROUP, g.name) },
            )
        }
        Screen.Pairing -> Column(modifier = modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Pairing") },
                navigationIcon = {
                    IconButton(onClick = { screen = Screen.Home }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                    }
                }
            )
            PairingScreen(getService = getService)
        }
        is Screen.Chat -> Column(modifier = modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(s.title) },
                navigationIcon = {
                    IconButton(onClick = { screen = Screen.Home }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                    }
                }
            )
            ChatScreen(scopeId = s.scopeId, kind = s.kind, db = db, getService = getService)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTabs(
    db: MeshDb,
    onPeer: (PeerRow) -> Unit,
    onGroup: (GroupRow) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Peers") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Groups") })
        }
        when (tab) {
            0 -> PeerList(db = db, onClick = onPeer)
            1 -> GroupList(db = db, onClick = onGroup)
        }
    }
}

@Composable
private fun PeerList(db: MeshDb, onClick: (PeerRow) -> Unit) {
    val peers by db.peerDao().streamAll().collectAsState(initial = emptyList())
    if (peers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Looking for nearby MeshLink nodes…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(peers, key = { it.nodeId }) { p ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onClick(p) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        if (p.trusted) Text("  ✓", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(p.nodeId, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun GroupList(db: MeshDb, onClick: (GroupRow) -> Unit) {
    val groups by db.groupDao().streamAll().collectAsState(initial = emptyList())
    if (groups.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No groups yet", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(groups, key = { it.groupId }) { g ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onClick(g) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                    Text(g.groupId, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PairingScreen(getService: () -> MeshService?) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as MeshLinkApp
    val ourPayload = remember {
        PairingPayload.forSelf(app.identity, app.identityStore.displayName())
    }
    val ourString = remember { ourPayload.encode() }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Your pairing code", fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(
                ourString,
                modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Share this string out-of-band (QR / NFC / read-aloud) so the other " +
                "device can trust your identity without ever joining the mesh.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Paste a peer's pairing code", fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("meshlink:1:…") },
        )
        Button(onClick = {
            val payload = PairingPayload.decodeOrNull(input.trim())
            if (payload == null) { status = "Invalid pairing code"; return@Button }
            val svc = getService() ?: return@Button
            scope.launch {
                svc.acceptPairing(payload)
                status = "Trusted ${payload.name} (${payload.nodeId})"
            }
        }) { Text("Trust peer") }
        status?.let {
            Text(it, modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChatScreen(scopeId: String, kind: String, db: MeshDb, getService: () -> MeshService?) {
    val messages by db.chatDao().streamForScope(scopeId, kind).collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && kind == MeshService.SCOPE_PEER) {
            val svc = getService()
            if (svc != null) scope.launch { svc.offerFile(scopeId, uri, "shared.bin") }
        }
    }

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
            if (kind == MeshService.SCOPE_PEER) {
                IconButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "attach")
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    if (it.isNotBlank() && kind == MeshService.SCOPE_PEER) {
                        val svc = getService()
                        if (svc != null) scope.launch { svc.sendTyping(scopeId) }
                    }
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("message…") },
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isEmpty()) return@Button
                    val svc = getService() ?: return@Button
                    scope.launch {
                        if (kind == MeshService.SCOPE_PEER) svc.sendText(scopeId, text)
                        else svc.sendGroupText(scopeId, text)
                    }
                    draft = ""
                },
                modifier = Modifier.padding(start = 8.dp),
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
                if (row.outgoing) {
                    Text(
                        deliveryGlyph(row.delivery),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun deliveryGlyph(state: String): String = when (state) {
    "pending" -> "…"
    "sent" -> "✓"
    "delivered" -> "✓✓"
    "read" -> "✓✓ read"
    "failed" -> "!"
    else -> ""
}
