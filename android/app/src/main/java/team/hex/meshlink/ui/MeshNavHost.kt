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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import android.content.pm.PackageManager
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.hex.meshlink.MeshLinkApp
import team.hex.meshlink.R
import team.hex.meshlink.pairing.PairingPayload
import team.hex.meshlink.pairing.SoundPairing
import team.hex.meshlink.service.MeshService
import team.hex.meshlink.service.Notifications
import team.hex.meshlink.storage.ChatMessageRow
import team.hex.meshlink.storage.ConversationSummary
import team.hex.meshlink.storage.GroupRow
import team.hex.meshlink.storage.MeshDb
import team.hex.meshlink.storage.PeerRow
import team.hex.meshlink.ui.theme.AuroraBackground
import team.hex.meshlink.ui.theme.GlassChip
import team.hex.meshlink.ui.theme.GlassSurface
import team.hex.meshlink.ui.theme.GradientText
import team.hex.meshlink.ui.theme.LivePulse

private sealed class Screen {
    object Home : Screen()
    object Pairing : Screen()
    object Settings : Screen()
    object NewGroup : Screen()
    data class Chat(val scopeId: String, val kind: String, val title: String) : Screen()
    data class GroupInfo(val groupId: String) : Screen()
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
                onOpenInfo = {
                    if (s.kind == MeshService.SCOPE_GROUP) {
                        screen = Screen.GroupInfo(s.scopeId)
                    }
                },
            )
            is Screen.GroupInfo -> GroupInfoScreen(
                groupId = s.groupId,
                db = db, getService = getService,
                onBack = { screen = Screen.Home },
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
            useGradientTitle = true,
            actions = {
                IconButton(onClick = onPair) {
                    Icon(Icons.Filled.QrCode, contentDescription = stringResource(R.string.action_pair))
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        GlassPillTabs(
            selected = tab,
            tabs = listOf(
                stringResource(R.string.tab_chats),
                stringResource(R.string.tab_peers),
                stringResource(R.string.tab_groups),
            ),
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ChatList(
                    db = db,
                    onPeer = onPeer,
                    onGroup = onGroup,
                    onNewGroup = onNewGroup,
                    onPair = onPair,
                )
                1 -> PeerList(db = db, getService = getService, onClick = onPeer, onPair = onPair)
                2 -> GroupList(db = db, onClick = onGroup, onNewGroup = onNewGroup)
            }
        }
    }
}

@Composable
private fun ChatList(
    db: MeshDb,
    onPeer: (PeerRow) -> Unit,
    onGroup: (GroupRow) -> Unit,
    onNewGroup: () -> Unit,
    onPair: () -> Unit,
) {
    val convs by db.chatDao().streamConversations().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        if (convs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassSurface(
                    modifier = Modifier.padding(24.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.empty_chats),
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.empty_chats_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onPair) { Text(stringResource(R.string.action_pair_peer)) }
                            Button(onClick = onNewGroup) { Text(stringResource(R.string.action_new_group)) }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(convs, key = { it.scopeId + it.scopeKind }) { conv ->
                    ConversationCard(
                        conv = conv,
                        onClick = {
                            scope.launch {
                                if (conv.scopeKind == MeshService.SCOPE_PEER) {
                                    val p = db.peerDao().byId(conv.scopeId)
                                    if (p != null) onPeer(p)
                                } else {
                                    val g = db.groupDao().byId(conv.scopeId)
                                    if (g != null) onGroup(g)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conv: ConversationSummary,
    onClick: () -> Unit,
) {
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
            Avatar(seed = conv.title.ifEmpty { conv.scopeId })
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conv.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        relativeTime(conv.lastTs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
                Spacer(Modifier.height(2.dp))
                val preview = (if (conv.lastOutgoing) stringResource(R.string.chat_you_prefix) else "") + conv.lastBody
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (conv.unread > 0) {
                Spacer(Modifier.width(8.dp))
                UnreadBadge(conv.unread)
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .heightIn(min = 22.dp)
            .widthIn(min = 22.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) stringResource(R.string.chat_unread_overflow) else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun relativeTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000L -> stringResource(R.string.chat_now)
        diff < 60 * 60_000L -> stringResource(R.string.chat_minutes, (diff / 60_000L).toInt())
        diff < 24 * 60 * 60_000L -> stringResource(R.string.chat_hours, (diff / (60 * 60_000L)).toInt())
        diff < 7 * 24 * 60 * 60_000L -> stringResource(R.string.chat_days, (diff / (24 * 60 * 60_000L)).toInt())
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            "%02d.%02d".format(
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.MONTH) + 1,
            )
        }
    }
}

@Composable
private fun GlassHeader(
    title: String,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    useGradientTitle: Boolean = false,
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
        if (useGradientTitle) {
            GradientText(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
        }
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
    onPair: () -> Unit,
) {
    val peers by db.peerDao().streamAll().collectAsState(initial = emptyList())
    val ctx = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("status") {
            DiscoveryStatusCard(getService = getService, onPair = onPair)
        }
        if (peers.isEmpty()) {
            item("empty") {
                GlassSurface(shape = RoundedCornerShape(20.dp)) {
                    Text(
                        stringResource(R.string.empty_peers),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            items(peers, key = { it.nodeId }) { p ->
                PeerCard(p, getService = getService, onClick = { onClick(p) })
            }
        }
    }
}

/**
 * Per-transport health card surfaced at the top of the Nearby list.
 * Tells the user which radios are up and lets them re-trigger the
 * Bluetooth permission prompt — the most common reason peers don't
 * show up is that the user dismissed the system dialog earlier.
 */
@Composable
private fun DiscoveryStatusCard(
    getService: () -> MeshService?,
    onPair: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val health = remember(getService()) {
        getService()?.transportHealth() ?: emptyList()
    }
    val direct = remember(getService()) {
        getService()?.directNeighbourCount() ?: 0
    }
    val anyFailed = health.any { it.state == team.hex.meshlink.transport.TransportState.Failed }
    GlassSurface(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LivePulse(
                    color = if (anyFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        R.string.discovery_status, direct,
                        health.count { it.state == team.hex.meshlink.transport.TransportState.Running },
                        health.size,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.discovery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            for (h in health) {
                Spacer(Modifier.height(4.dp))
                TransportHealthRow(h)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { activity?.requestMeshPermissions() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.discovery_grant)) }
                OutlinedButton(
                    onClick = onPair,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.discovery_verify)) }
            }
        }
    }
}

@Composable
private fun TransportHealthRow(h: MeshService.TransportHealth) {
    val labelRes = when (h.name) {
        "ble" -> R.string.transport_ble
        "lan" -> R.string.transport_lan
        "wifi-direct" -> R.string.transport_wifi_direct
        "lora" -> R.string.transport_lora
        else -> R.string.transport_unknown
    }
    val color = when (h.state) {
        team.hex.meshlink.transport.TransportState.Running -> MaterialTheme.colorScheme.secondary
        team.hex.meshlink.transport.TransportState.Starting -> MaterialTheme.colorScheme.tertiary
        team.hex.meshlink.transport.TransportState.Failed -> MaterialTheme.colorScheme.error
        team.hex.meshlink.transport.TransportState.Stopped -> MaterialTheme.colorScheme.outline
    }
    val stateRes = when (h.state) {
        team.hex.meshlink.transport.TransportState.Running -> R.string.transport_state_running
        team.hex.meshlink.transport.TransportState.Starting -> R.string.transport_state_starting
        team.hex.meshlink.transport.TransportState.Failed -> R.string.transport_state_failed
        team.hex.meshlink.transport.TransportState.Stopped -> R.string.transport_state_stopped
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color, RoundedCornerShape(999.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(stateRes) + " · " + h.liveLinks,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
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
                Text(
                    stringResource(R.string.peer_detail_last_seen, relativeTime(p.lastSeenMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
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
        LinkState.Direct -> stringResource(R.string.link_direct) to MaterialTheme.colorScheme.secondary
        LinkState.Relay -> stringResource(R.string.link_relay) to MaterialTheme.colorScheme.tertiary
        LinkState.Unknown -> stringResource(R.string.link_unknown) to MaterialTheme.colorScheme.outline
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
                        Text(stringResource(R.string.empty_groups),
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onNewGroup) { Text(stringResource(R.string.action_new_group)) }
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
            Icon(Icons.Filled.GroupAdd, contentDescription = stringResource(R.string.action_new_group),
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
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (scanning) {
        QrScannerScreen(
            onBack = { scanning = false },
            onScanned = { payload ->
                val svc = getService()
                if (svc != null) {
                    scope.launch {
                        svc.acceptPairing(payload)
                        status = ctx.getString(R.string.status_trusted, payload.name, payload.nodeId)
                        scanning = false
                    }
                } else {
                    status = ctx.getString(R.string.status_service_not_bound)
                    scanning = false
                }
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        GlassHeader(
            title = stringResource(R.string.pairing_title),
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.pairing_your_code),
                        style = MaterialTheme.typography.titleMedium)
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
                        stringResource(R.string.pairing_share_hint),
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
                    Text(stringResource(R.string.pairing_trust_a_peer),
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scanning = true },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.pairing_scan_qr)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    SoundPairingControls(
                        ourPayload = ourString,
                        onPairedSilently = { decoded ->
                            val payload = PairingPayload.decodeOrNull(decoded)
                            if (payload == null) {
                                status = ctx.getString(R.string.sound_pairing_failed)
                            } else {
                                val svc = getService()
                                if (svc != null) {
                                    scope.launch {
                                        svc.acceptPairing(payload)
                                        status = ctx.getString(R.string.status_trusted,
                                            payload.name, payload.nodeId)
                                    }
                                }
                            }
                        },
                        onStatus = { status = it },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.pairing_or_paste),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    Spacer(Modifier.height(8.dp))
                    val invalid = stringResource(R.string.status_invalid_pairing)
                    val trustedFmt = stringResource(R.string.status_trusted)
                    val submit: () -> Unit = submit@{
                        val payload = PairingPayload.decodeOrNull(input.trim())
                        if (payload == null) { status = invalid; return@submit }
                        val svc = getService() ?: return@submit
                        scope.launch {
                            svc.acceptPairing(payload)
                            status = trustedFmt.format(payload.name, payload.nodeId)
                            input = ""
                        }
                    }
                    GlassTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = stringResource(R.string.placeholder_pairing),
                        singleLine = true,
                        imeAction = ImeAction.Done,
                        onImeAction = submit,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = submit,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_trust_peer)) }
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
        Text(stringResource(R.string.pairing_qr_too_large),
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
            title = stringResource(R.string.settings_title),
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.settings_display_name),
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    GlassTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = stringResource(R.string.placeholder_your_name),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_node_id, app.identity.nodeId()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            }
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_dynamic_color),
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
                    Text(stringResource(R.string.settings_dynamic_color_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            }
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.settings_topology),
                        style = MaterialTheme.typography.titleMedium)
                    val snap = getService()?.router()?.graph?.snapshot()
                    Spacer(Modifier.height(8.dp))
                    if (snap == null) {
                        Text(stringResource(R.string.settings_topology_unbound),
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            stringResource(R.string.settings_topology_summary,
                                snap.directNeighbours, snap.nodes, snap.edges),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.settings_identity_backup),
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_identity_backup_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    Spacer(Modifier.height(12.dp))
                    var phrase by remember { mutableStateOf<String?>(null) }
                    Button(onClick = {
                        phrase = team.hex.meshlink.crypto.MnemonicBackup.exportPhrase(app.identity)
                    }) { Text(stringResource(R.string.action_show_recovery)) }
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
                    Text(stringResource(R.string.settings_storage),
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val clearedMsg = stringResource(R.string.status_history_cleared)
                    Button(onClick = {
                        scope.launch {
                            db.chatDao().deleteOlderThan(System.currentTimeMillis())
                            status = clearedMsg
                        }
                    }) { Text(stringResource(R.string.action_clear_history)) }
                }
            }
            val savedMsg = stringResource(R.string.status_saved)
            Button(
                onClick = {
                    val newName = name.trim().ifBlank { "Anon" }
                    // Route through the service so the router's announce
                    // payload picks up the change without a restart.
                    getService()?.setDisplayName(newName) ?: run {
                        app.identityStore.setDisplayName(newName)
                    }
                    name = newName
                    status = savedMsg
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }
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
            title = stringResource(R.string.new_group_title),
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                    Text(stringResource(R.string.new_group_name),
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    GlassTextField(value = name, onValueChange = { name = it },
                        placeholder = stringResource(R.string.placeholder_group_name))
                }
            }
            Text(stringResource(R.string.new_group_add_members),
                style = MaterialTheme.typography.titleMedium)
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
                                    Icons.Filled.Send, contentDescription = null,
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
            ) { Text(stringResource(R.string.action_create_group)) }
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
    onOpenInfo: () -> Unit = {},
) {
    val all by db.chatDao().streamForScope(scopeId, kind).collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var query by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // Mark conversation read on first composition + whenever a fresh batch
    // of incoming messages lands.
    androidx.compose.runtime.LaunchedEffect(scopeId, kind, all.size) {
        getService()?.markScopeRead(scopeId, kind)
    }

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
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                IconButton(onClick = { query = if (query == null) "" else null }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                }
                if (kind == MeshService.SCOPE_GROUP) {
                    IconButton(onClick = onOpenInfo) {
                        Icon(Icons.Filled.GroupAdd, contentDescription = stringResource(R.string.action_settings))
                    }
                }
            },
        )
        if (query != null) {
            GlassTextField(
                value = query!!,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.placeholder_search_messages),
                modifier = Modifier.padding(horizontal = 16.dp),
                trailing = {
                    IconButton(onClick = { query = null }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlassSurface(shape = RoundedCornerShape(24.dp)) {
                    Text(
                        if (query != null) stringResource(R.string.empty_no_matches)
                        else stringResource(R.string.empty_messages),
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
            onVoiceStart = if (kind == MeshService.SCOPE_PEER) {
                { getService()?.startVoiceNote() }
            } else null,
            onVoiceStop = if (kind == MeshService.SCOPE_PEER) {
                { scope.launch { getService()?.stopAndSendVoiceNote(scopeId) } }
            } else null,
            onVoiceCancel = { getService()?.cancelVoiceNote() },
        )
    }
}

@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: (() -> Unit)? = null,
    onVoiceStart: (() -> Unit)? = null,
    onVoiceStop: (() -> Unit)? = null,
    onVoiceCancel: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onAttach != null) {
            IconButton(onClick = onAttach) {
                Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.action_attach))
            }
        }
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = stringResource(R.string.placeholder_message),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (value.isBlank() && onVoiceStart != null && onVoiceStop != null) {
            VoiceHoldButton(
                onStart = onVoiceStart,
                onStop = onVoiceStop,
                onCancel = onVoiceCancel ?: {},
            )
        } else {
            IconButton(
                onClick = onSend,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
            ) {
                Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.action_send),
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun VoiceHoldButton(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val bg = if (pressed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg, RoundedCornerShape(999.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    onStart()
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        // Slide up >= 80px to cancel — common pattern.
                        if (change.position.y - down.position.y < -80f) {
                            cancelled = true
                            break
                        }
                    }
                    pressed = false
                    if (cancelled) onCancel() else onStop()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = stringResource(R.string.action_voice_record),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
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
    singleLine: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { onImeAction?.invoke(); keyboard?.hide() },
                onGo = { onImeAction?.invoke(); keyboard?.hide() },
                onSearch = { onImeAction?.invoke(); keyboard?.hide() },
                onSend = { onImeAction?.invoke(); keyboard?.hide() },
            ),
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

// ------------------------------- Group info -------------------------------

@Composable
private fun GroupInfoScreen(
    groupId: String,
    db: MeshDb,
    getService: () -> MeshService?,
    onBack: () -> Unit,
) {
    val groups by db.groupDao().streamAll().collectAsState(initial = emptyList())
    val peers by db.peerDao().streamAll().collectAsState(initial = emptyList())
    val group = groups.firstOrNull { it.groupId == groupId }
    val members = remember(group) {
        group?.membersCsv?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }
    val toAdd = remember { mutableStateOf(setOf<String>()) }
    val toRemove = remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val rekeyDoneMsg = stringResource(R.string.status_rekey_done)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        GlassHeader(
            title = group?.name ?: stringResource(R.string.tab_groups),
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        if (group == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.group_info_not_found))
            }
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.group_info_members, members.size),
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.group_info_rekey_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(peers, key = { it.nodeId }) { p ->
                    val isMember = p.nodeId in members
                    val markedAdd = p.nodeId in toAdd.value
                    val markedRemove = p.nodeId in toRemove.value
                    val onClick = {
                        if (isMember) {
                            toRemove.value = if (markedRemove) toRemove.value - p.nodeId
                            else toRemove.value + p.nodeId
                        } else {
                            toAdd.value = if (markedAdd) toAdd.value - p.nodeId
                            else toAdd.value + p.nodeId
                        }
                    }
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClick() },
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
                                Text(
                                    when {
                                        markedRemove -> stringResource(R.string.group_info_will_remove)
                                        markedAdd -> stringResource(R.string.group_info_will_add)
                                        isMember -> stringResource(R.string.group_info_member)
                                        else -> p.nodeId
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                            if (isMember && !markedRemove) {
                                GlassChip(
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f),
                                ) {
                                    Text(
                                        stringResource(R.string.group_info_in),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val svc = getService() ?: return@Button
                    scope.launch {
                        if (toAdd.value.isNotEmpty()) {
                            svc.groupsHelper().addMembers(groupId, toAdd.value.toList())
                        }
                        if (toRemove.value.isNotEmpty()) {
                            svc.groupsHelper().removeMembers(groupId, toRemove.value.toList())
                        }
                        toAdd.value = emptySet()
                        toRemove.value = emptySet()
                        status = rekeyDoneMsg
                    }
                },
                enabled = toAdd.value.isNotEmpty() || toRemove.value.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_apply_rekey)) }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

// ------------------------------- Sound pairing -------------------------------

@Composable
private fun SoundPairingControls(
    ourPayload: String,
    onPairedSilently: (String) -> Unit,
    onStatus: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val emittingMsg = stringResource(R.string.sound_pairing_emitting)
    val listeningMsg = stringResource(R.string.sound_pairing_listening)
    val failedMsg = stringResource(R.string.sound_pairing_failed)

    val micRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            busy = true
            onStatus(listeningMsg)
            scope.launch {
                val decoded = withContextIO { SoundPairing.listen() }
                busy = false
                if (decoded != null) onPairedSilently(decoded)
                else onStatus(failedMsg)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                busy = true
                onStatus(emittingMsg)
                scope.launch {
                    withContextIO { SoundPairing.emit(ourPayload) }
                    busy = false
                    onStatus("")
                }
            },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.sound_pairing_emit)) }
        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                if (ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED) {
                    busy = true
                    onStatus(listeningMsg)
                    scope.launch {
                        val decoded = withContextIO { SoundPairing.listen() }
                        busy = false
                        if (decoded != null) onPairedSilently(decoded)
                        else onStatus(failedMsg)
                    }
                } else {
                    micRequest.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.sound_pairing_listen)) }
    }
}

private suspend fun <T> withContextIO(block: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
