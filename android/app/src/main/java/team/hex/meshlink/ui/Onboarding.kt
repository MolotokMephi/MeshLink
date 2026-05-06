package team.hex.meshlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import team.hex.meshlink.ui.theme.AuroraBackground
import team.hex.meshlink.ui.theme.GlassSurface

/**
 * First-run intro. Sets the tone with the same liquid-glass language and
 * walks the user through the runtime permissions and battery-whitelist
 * step that the mesh stack needs to survive Doze.
 *
 * Layout: scrollable card list + sticky CTA bar pinned to the bottom so
 * the "Continue" button is always visible regardless of screen size.
 */
@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
) {
    AuroraBackground(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues()),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Welcome to MeshLink",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "An offline-first messenger that routes through every Bluetooth LE, " +
                        "Wi-Fi LAN and Wi-Fi Direct radio nearby — no servers, no cell data.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(8.dp))
                FeatureRow(
                    icon = Icons.Filled.Bluetooth,
                    title = "Bluetooth LE + Wi-Fi peers",
                    body = "We need scan/advertise/connect permissions so your phone " +
                        "can find and talk to nearby MeshLink devices. We'll ask once " +
                        "you tap Continue.",
                )
                FeatureRow(
                    icon = Icons.Filled.Wifi,
                    title = "Nearby Wi-Fi (Android 13+)",
                    body = "Opens up Wi-Fi Direct fat-pipe transfers without exposing " +
                        "physical location.",
                )
                FeatureRow(
                    icon = Icons.Filled.Lock,
                    title = "End-to-end encryption",
                    body = "Every chat is signed and encrypted on-device. Your private " +
                        "key never leaves the device — back it up from Settings.",
                )
                FeatureRow(
                    icon = Icons.Filled.BatteryFull,
                    title = "Always-on mesh",
                    body = "Whitelist MeshLink from battery optimization so the mesh " +
                        "keeps relaying messages while your screen is locked.",
                    trailing = {
                        OutlinedButton(onClick = onRequestBatteryWhitelist) {
                            Text("Whitelist")
                        }
                    },
                )
                Spacer(Modifier.height(16.dp))
            }

            // Sticky CTA bar — frosted to match the rest of the surface
            // language and to stay legible if the scroll content slides
            // under it on small phones.
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Box(modifier = Modifier.padding(12.dp)) {
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Continue") }
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    body: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}
