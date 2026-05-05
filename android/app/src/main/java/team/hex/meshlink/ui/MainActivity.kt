package team.hex.meshlink.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import team.hex.meshlink.MeshLinkApp
import team.hex.meshlink.service.MeshService
import team.hex.meshlink.service.Notifications
import team.hex.meshlink.ui.theme.MeshLinkTheme

class MainActivity : ComponentActivity() {

    private var meshService: MeshService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            meshService = (binder as? MeshService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
        }
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startServiceAndBind()
    }

    private val openScopeId by lazy { mutableStateOf<String?>(null) }
    private val openScopeKind by lazy { mutableStateOf<String?>(null) }
    private val onboardingDone by lazy {
        val app = applicationContext as MeshLinkApp
        mutableStateOf(app.identityStore.onboardingDone())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDeepLinkExtras(intent)

        setContent {
            val app = applicationContext as MeshLinkApp
            val dynamic = remember { app.identityStore.useDynamicColor() }
            MeshLinkTheme(dynamicColor = dynamic) {
                if (!onboardingDone.value) {
                    OnboardingScreen(
                        modifier = Modifier.fillMaxSize(),
                        onDone = {
                            app.identityStore.setOnboardingDone()
                            onboardingDone.value = true
                            ensurePermissionsAndStart()
                        },
                        onRequestBatteryWhitelist = { requestBatteryWhitelist() },
                    )
                } else {
                    MeshNavHost(
                        modifier = Modifier.fillMaxSize(),
                        getService = { meshService },
                        openScopeId = openScopeId.value,
                        openScopeKind = openScopeKind.value,
                    )
                }
            }
        }
        if (onboardingDone.value) ensurePermissionsAndStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDeepLinkExtras(intent)
    }

    private fun readDeepLinkExtras(intent: Intent?) {
        val id = intent?.getStringExtra(Notifications.EXTRA_OPEN_SCOPE_ID)
        val kind = intent?.getStringExtra(Notifications.EXTRA_OPEN_SCOPE_KIND)
        if (id != null && kind != null) {
            openScopeId.value = id
            openScopeKind.value = kind
        }
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startServiceAndBind()
        else permissionRequest.launch(missing.toTypedArray())
    }

    private fun startServiceAndBind() {
        MeshService.start(this)
        bindService(Intent(this, MeshService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Doze + App Standby kill our foreground service after ~5–10 minutes
     * on most non-Pixel devices unless the user explicitly whitelists us.
     * We surface this in onboarding and Settings — both call into here.
     */
    private fun requestBatteryWhitelist() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }.onFailure {
            // Fallback to general battery-optimization list.
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
