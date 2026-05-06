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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import team.hex.meshlink.MeshLinkApp
import team.hex.meshlink.R
import team.hex.meshlink.ui.theme.AuroraBackground
import team.hex.meshlink.ui.theme.GlassSurface
import team.hex.meshlink.ui.theme.GradientText
import kotlin.random.Random

/**
 * First-run intro. Layout: scrollable card list with a sticky CTA pinned
 * to the bottom, including a nickname picker that suggests a random
 * handle the user can re-roll or overwrite. The nickname is what every
 * other device sees in their nearby list, so we ask up-front instead
 * of stamping "Anon" on every announce.
 */
@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    onDone: (nickname: String) -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as MeshLinkApp
    var nickname by remember {
        mutableStateOf(
            app.identityStore.displayName().takeUnless { it.isBlank() || it == "Anon" }
                ?: NicknameGenerator.random()
        )
    }

    AuroraBackground(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues())
                .imePadding(),
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
                GradientText(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.displayMedium,
                )
                Text(
                    stringResource(R.string.onboarding_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(8.dp))
                NicknameCard(
                    value = nickname,
                    onValueChange = { nickname = it },
                    onRandomize = { nickname = NicknameGenerator.random() },
                )
                FeatureRow(
                    icon = Icons.Filled.Bluetooth,
                    title = stringResource(R.string.onboarding_ble_title),
                    body = stringResource(R.string.onboarding_ble_body),
                )
                FeatureRow(
                    icon = Icons.Filled.Wifi,
                    title = stringResource(R.string.onboarding_wifi_title),
                    body = stringResource(R.string.onboarding_wifi_body),
                )
                FeatureRow(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.onboarding_e2e_title),
                    body = stringResource(R.string.onboarding_e2e_body),
                )
                FeatureRow(
                    icon = Icons.Filled.BatteryFull,
                    title = stringResource(R.string.onboarding_battery_title),
                    body = stringResource(R.string.onboarding_battery_body),
                    trailing = {
                        OutlinedButton(onClick = onRequestBatteryWhitelist) {
                            Text(stringResource(R.string.action_whitelist))
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
                        onClick = {
                            val final = nickname.trim().ifBlank { NicknameGenerator.random() }
                            app.identityStore.setDisplayName(final)
                            onDone(final)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_continue)) }
                }
            }
        }
    }
}

@Composable
private fun NicknameCard(
    value: String,
    onValueChange: (String) -> Unit,
    onRandomize: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.nickname_title),
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.nickname_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NicknameField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onRandomize) {
                    Icon(Icons.Filled.Casino,
                        contentDescription = stringResource(R.string.nickname_random))
                }
            }
        }
    }
}

@Composable
private fun NicknameField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.placeholder_nickname)) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Done,
        ),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = modifier,
    )
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

/**
 * Friendly two-word nicknames so the first-run user has *something* to
 * land on instead of "Anon". Words are chosen to be easy to pronounce
 * out loud (since users will be reading them off a peer's screen during
 * QR pairing) and short enough that two of them + a 2-digit suffix fit
 * inside the 24-char display-name budget on the smallest phones.
 */
internal object NicknameGenerator {
    private val adjectives = listOf(
        "Aurora", "Brave", "Bright", "Cosmic", "Crimson", "Crystal",
        "Echo", "Electric", "Frosted", "Glass", "Glow", "Hazel",
        "Indigo", "Jade", "Lunar", "Mango", "Mint", "Misty",
        "Nebula", "Onyx", "Pixel", "Quartz", "Ruby", "Silver",
        "Solar", "Sunny", "Tidal", "Vivid", "Wild", "Zen",
    )
    private val animals = listOf(
        "Ant", "Bear", "Cat", "Dolphin", "Eagle", "Fox", "Gecko",
        "Hawk", "Ibis", "Jay", "Koi", "Lynx", "Moth", "Newt",
        "Otter", "Panda", "Quokka", "Raven", "Seal", "Tiger",
        "Urchin", "Viper", "Wolf", "Yak", "Zebra",
    )

    fun random(rng: Random = Random.Default): String {
        val a = adjectives.random(rng)
        val b = animals.random(rng)
        val suffix = rng.nextInt(10, 100)
        return "$a$b$suffix"
    }
}
