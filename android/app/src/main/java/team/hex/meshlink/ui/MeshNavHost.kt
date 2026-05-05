package team.hex.meshlink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.hex.meshlink.MeshLinkApp
import team.hex.meshlink.pairing.PairingPayload
import team.hex.meshlink.service.MeshService
import team.hex.meshlink.service.Notifications
import team.hex.meshlink.storage.ChatMessageRow
import team.hex.meshlink.storage.GroupRow
import team.hex.meshlink.storage.MeshDb
import team.hex.meshlink.storage.PeerRow
import team.hex.meshlink.ui.theme.AuroraBackground
import team.hex.meshlink.ui.theme.GlassChip
import team.hex.meshlink.ui.theme.GlassSurface
import team.hex.meshlink.ui.theme.LivePulse

private sealed class Screen {
    object Home : Screen()
    object Pairing : Screen()
    object Settings : Screen()
    object NewGroup : Screen()
    data class Chat(val scopeId: String, val kind: String, val title: String) : Screen()
}

@Composable
fun MeshNavHost(
    modifier: Modifier = Modifier,
    getService: () -> MeshService?,
    openScopeId: String? = null,
    openScopeKind: String? = null,
) {
    val ctx = LocalContext.current
    val db = remember { MeshDb.get(ctx) }

    var screen by remember(openScopeId, openScopeKind) {
        val initial: Screen = if (openScopeId != null && openScopeKind != null) {
            Screen.Chat(openScopeId, openScopeKind, openScopeKind.replaceFirstChar { it.uppercase() })
        } else Screen.Home
        mutableStateOf(initial)
    }

    AuroraBackground(modifier = modifier.fillMaxSize()) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                db = db,
                getService = getService,
                onPair = { screen = Screen.Pairing },
                onSettings = { screen = Screen.Settings },
                onNewGroup = { screen = Screen.NewGroup },
                onPeer = { p -> screen = Screen.Chat(p.nodeId, MeshService.SCOPE_PEER, p.name) },
                onGroup = { g -> screen = Screen.Chat(g.groupId, MeshService.SCOPE_GROUP, g.name) },
            )
            Screen.Pairing -> PairingScreen(
                getService = getService,
                onBack = { screen = Screen.Home },
            )
            Screen.Settings -> SettingsScreen(
                getService = getService,
                onBack = { screen = Screen.Home },
            )
            Screen.NewGroup -> NewGroupScreen(
                db = db,
                getService = getService,
                onBack = { screen = Screen.Home },
                onCreated = { gid, name ->
                    screen = Screen.Chat(gid, MeshService.SCOPE_GROUP, name)
                },
            )
            is Screen.Chat -> ChatScreen(
                scopeId = s.scopeId, kind = s.kind, title = s.title,
                db = db, getService = getService,
                onBack = {
                    Notifications.cancelForScope(ctx, s.scopeId)
                    screen = Screen.Home
                },
            )
        }
    }
}

// ------------------------------- Home -------------------------------

@Composable
private fun HomeScreen(
    db: MeshDb,
    getService: () -> MeshService?,
    onPair: () -> Unit,
    onSettings: () -> Unit,
    onNewGroup: () -> Unit,
    onPeer: (PeerRow) -> Unit,
    onGroup: (GroupRow) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        GlassHeader(
            title = "MeshLink",
            actions = {
                IconButton(onClick = onPair) {
                    Icon(Icons.Filled.QrCode, contentDescription = "pair")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "settings")
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        GlassPillTabs(
            selected = tab,
            tabs = listOf("Peers", "Groups"),
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> PeerList(db = db, getService = getService, onClick = onPeer)
                1 -> GroupList(db = db, onClick = onGroup, onNewGroup = onNewGroup)
            }
        }
    }
}

@Composable
private fun GlassHeader(
    title: String,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        Row { actions() }
    }
}

@Composable
private fun GlassPillTabs(
    selected: Int,
    tabs: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(modifier = Modifier.padding(6.dp)) {
            tabs.forEachIndexed { i, label ->
                val active = i == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { onSelect(i) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

// ------------------------------- Peer list -------------------------------

@Composable
private fun PeerList(
    db: MeshDb,
    getService: () -> MeshService?,
    onClick: (PeerRow) -> Unit,
) {
    val peers by db.peerDao().streamAll().collectAsState(initial = emptyList())
    if (peers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassSurface(
                modifier = Modifier.padding(24.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text(
                    "Looking for nearby MeshLink nodes…",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(peers, key = { it.nodeId }) { p ->
            PeerCard(p, getService = getService, onClick = { onClick(p) })
        }
    }
}

@Composable
private fun PeerCard(
    p: PeerRow,
    getService: () -> MeshService?,
    onClick: () -> Unit,
) {
    val link = remember(p.nodeId) {
        val r = getService()?.router()
        when {
            r == null -> LinkState.Unknown
            r.graph.distanceTo(p.nodeId)?.let { it == 1 } == true -> LinkState.Direct
            r.graph.distanceTo(p.nodeId) != null -> LinkState.Relay
            else -> LinkState.Unknown
        }
    }
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(seed = p.nodeId)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    if (p.trusted) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "✓",
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Text(
                    p.nodeId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
            LinkBadge(link)
        }
    }
}

private enum class LinkState { Direct, Relay, Unknown }

@Composable
private fun LinkBadge(state: LinkState) {
    val (label, color) = when (state) {
        LinkState.Direct -> "direct" to MaterialTheme.colorScheme.secondary
        LinkState.Relay -> "via relay" to MaterialTheme.colorScheme.tertiary
        LinkState.Unknown -> "no route" to MaterialTheme.colorScheme.outline
    }
    GlassChip(color = color.copy(alpha = 0.22f)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state == LinkState.Direct) {
                LivePulse(color = color, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Avatar(seed: String, sizeDp: Int = 40) {
    val cs = MaterialTheme.colorScheme
    val palette = listOf(cs.primary, cs.secondary, cs.tertiary)
    val color = palette[(seed.hashCode().rem(palette.size) + palette.size).rem(palette.size)]
    val initial = seed.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.45f), RoundedCornerShape(999.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium)
    }
}

// ------------------------------- Group list -------------------------------

@Composable
private fun GroupList(
    db: MeshDb,
    onClick: (GroupRow) -> Unit,
    onNewGroup: () -> Unit,
) {
    val groups by db.groupDao().streamAll().collectAsState(initial = emptyList())
    Box(Modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassSurface(
                    modifier = Modifier.padding(24.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No groups yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onNewGroup) { Text("New group") }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups, key = { it.groupId }) { g ->
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClick(g) },
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(seed = g.name)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(g.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    g.groupId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                        }
                    }
                }
            }
        }
        IconButton(
            onClick = onNewGroup,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
        ) {
            Icon(Icons.Filled.GroupAdd, contentDescription = "new group",
                tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

// ------------------------------- Pairing -------------------------------

@Composable
private fun PairingScreen(
    getService: () -> MeshService?,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as MeshLinkApp
    val ourPayload = remember {
        PairingPayload.forSelf(app.identity, app.identityStore.displayName())
    }
    val ourString = remember { ourPayload.encode() }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        GlassHeader(
            title = "Pairing",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Your pairing code", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    QrPreview(text = ourString)
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        Text(
                            ourString,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Show this QR or share the code out-of-band so the other " +
                            "device can trust your identity without ever joining the mesh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Paste a peer's pairing code", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    GlassTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = "meshlink:1:…",
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val payload = PairingPayload.decodeOrNull(input.trim())
                            if (payload == null) { status = "Invalid pairing code"; return@Button }
                            val svc = getService() ?: return@Button
                            scope.launch {
                                svc.acceptPairing(payload)
                                status = "Trusted ${payload.name} (${payload.nodeId})"
                            }
                        },
                    ) { Text("Trust peer") }
                    status?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun QrPreview(text: String) {
    val matrix = remember(text) {
        runCatching { team.hex.meshlink.pairing.QrEncoder.encode(text) }.getOrNull()
    }
    if (matrix == null) {
        Text("(QR payload too large; share the text below)",
            style = MaterialTheme.typography.bodySmall)
        return
    }
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val n = matrix.w
            val side = minOf(size.width, size.height)
            val module = side / n
            val ox = (size.width - side) / 2f
            val oy = (size.height - side) / 2f
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(ox + x * module, oy + y * module),
                        size = androidx.compose.ui.geometry.Size(module, module),
                    )
                }
            }
        }
    }
}

// ------------------------------- Settings -------------------------------

@Composable
private fun SettingsScreen(
    getService: () -> MeshService?,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as MeshLinkApp
    val db = remember { MeshDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(app.identityStore.displayName()) }
    var dynamicColor by remember { mutableStateOf(app.identityStore.useDynamicColor()) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        GlassHeader(
            title = "Settings",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Display name", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    GlassTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "Your name",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Your node id: ${app.identity.nodeId()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            }
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Material You colours",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = {
                                dynamicColor = it
                                app.identityStore.setUseDynamicColor(it)
                            },
                        )
                    }
                    Text("Match the system wallpaper palette on Android 12+.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            }
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Mesh topology", style = MaterialTheme.typography.titleMedium)
                    val snap = getService()?.router()?.graph?.snapshot()
                    Spacer(Modifier.height(8.dp))
                    if (snap == null) {
                        Text("Service not yet bound.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("${snap.directNeighbours} direct, ${snap.nodes} nodes known, " +
                            "${snap.edges} mesh edges.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Identity backup", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Export a 24-word recovery phrase. Anyone with these words " +
                            "can impersonate you, so keep them somewhere private.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    Spacer(Modifier.height(12.dp))
                    var phrase by remember { mutableStateOf<String?>(null) }
                    Button(onClick = {
                        phrase = team.hex.meshlink.crypto.MnemonicBackup.exportPhrase(app.identity)
                    }) { Text("Show recovery phrase") }
                    val current = phrase
                    if (current != null) {
                        Spacer(Modifier.height(12.dp))
                        SelectionContainer {
                            Text(current, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch {
                            db.chatDao().deleteOlderThan(System.currentTimeMillis())
                            status = "Chat history cleared"
                        }
                    }) { Text("Clear chat history") }
                }
            }
            Button(
                onClick = {
                    app.identityStore.setDisplayName(name.ifBlank { "Anon" })
                    status = "Saved"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

// ------------------------------- New group -------------------------------

@Composable
private fun NewGroupScreen(
    db: MeshDb,
    getService: () -> MeshService?,
    onBack: () -> Unit,
    onCreated: (groupId: String, name: String) -> Unit,
) {
    val peers by db.peerDao().streamAll().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        GlassHeader(
            title = "New group",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Group name", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    GlassTextField(value = name, onValueChange = { name = it }, placeholder = "Awesome group")
                }
            }
            Text("Add members", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(peers, key = { it.nodeId }) { p ->
                    val isOn = p.nodeId in selected.value
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected.value =
                                    if (isOn) selected.value - p.nodeId else selected.value + p.nodeId
                            },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(seed = p.nodeId, sizeDp = 32)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(p.nodeId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            if (isOn) {
                                Icon(
                                    Icons.Filled.Send, contentDescription = "selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val svc = getService() ?: return@Button
                    if (name.isBlank() || selected.value.isEmpty()) return@Button
                    scope.launch {
                        val gid = svc.groupsHelper().createAndInvite(name.trim(), selected.value.toList())
                        onCreated(gid, name.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create group") }
        }
    }
}

// ------------------------------- Chat -------------------------------

@Composable
private fun ChatScreen(
    scopeId: String,
    kind: String,
    title: String,
    db: MeshDb,
    getService: () -> MeshService?,
    onBack: () -> Unit,
) {
    val all by db.chatDao().streamForScope(scopeId, kind).collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var query by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && kind == MeshService.SCOPE_PEER) {
            val svc = getService()
            if (svc != null) scope.launch { svc.offerFile(scopeId, uri, "shared.bin") }
        }
    }

    val messages = remember(all, query) {
        val q = query?.trim()?.lowercase()
        if (q.isNullOrEmpty()) all
        else all.filter { it.body.lowercase().contains(q) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        GlassHeader(
            title = title,
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                }
            },
            actions = {
                IconButton(onClick = { query = if (query == null) "" else null }) {
                    Icon(Icons.Filled.Search, contentDescription = "search")
                }
            },
        )
        if (query != null) {
            GlassTextField(
                value = query!!,
                onValueChange = { query = it },
                placeholder = "Search messages…",
                modifier = Modifier.padding(horizontal = 16.dp),
                trailing = {
                    IconButton(onClick = { query = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "close search")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassSurface(shape = RoundedCornerShape(24.dp)) {
                    Text(
                        if (query != null) "No matches" else "No messages yet — say hi.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { it.msgId }) { row -> ChatBubble(row) }
            }
        }
        ChatComposer(
            value = draft,
            onValueChange = {
                draft = it
                if (it.isNotBlank() && kind == MeshService.SCOPE_PEER) {
                    val svc = getService()
                    if (svc != null) scope.launch { svc.sendTyping(scopeId) }
                }
            },
            onSend = {
                val text = draft.trim()
                if (text.isEmpty()) return@ChatComposer
                val svc = getService() ?: return@ChatComposer
                scope.launch {
                    if (kind == MeshService.SCOPE_PEER) svc.sendText(scopeId, text)
                    else svc.sendGroupText(scopeId, text)
                }
                draft = ""
            },
            onAttach = { pickFile.launch(arrayOf("*/*")) }.takeIf { kind == MeshService.SCOPE_PEER },
        )
    }
}

@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onAttach != null) {
            IconButton(onClick = onAttach) {
                Icon(Icons.Filled.AttachFile, contentDescription = "attach")
            }
        }
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "Message…",
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onSend,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
        ) {
            Icon(Icons.Filled.Send, contentDescription = "send",
                tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun ChatBubble(row: ChatMessageRow) {
    val align = if (row.outgoing) Alignment.CenterEnd else Alignment.CenterStart
    val tint = if (row.outgoing) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    val shape = if (row.outgoing) {
        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        GlassSurface(
            modifier = Modifier
                .align(align)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            shape = shape,
            tint = tint,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(row.body, style = MaterialTheme.typography.bodyMedium,
                    color = if (row.outgoing) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface)
                if (row.outgoing) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        deliveryGlyph(row.delivery),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
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

// ------------------------------- Shared text field -------------------------------

@Composable
private fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = false,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            trailingIcon = trailing,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
