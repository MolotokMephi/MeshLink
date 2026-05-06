package team.hex.meshlink.ui

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import team.hex.meshlink.MeshLinkApp
import team.hex.meshlink.pairing.NfcPairing
import team.hex.meshlink.service.MeshService
import team.hex.meshlink.service.Notifications
import team.hex.meshlink.ui.theme.MeshLinkTheme

class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
    ) {
        // Always proceed: optional perms (POST_NOTIFICATIONS, NEARBY_WIFI)
        // shouldn't block the messenger, and refusing core BLE just leaves
        // us with a degraded transport set we can still operate on.
        startServiceAndBind()
    }

    private val openScopeId by lazy { mutableStateOf<String?>(null) }
    private val openScopeKind by lazy { mutableStateOf<String?>(null) }
    private val onboardingDone by lazy {
        val app = applicationContext as MeshLinkApp
        mutableStateOf(app.identityStore.onboardingDone())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge with auto status/nav bar contrast. Samsung's One UI
        // 6+ refuses to render translucent system bars unless the activity
        // opts in here — without it the app draws *behind* an opaque grey
        // strip the OS overlays on top of our aurora gradient.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0),
        )
        super.onCreate(savedInstanceState)
        readDeepLinkExtras(intent)
        consumeNfcIntent(intent)

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
        consumeNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Foreground dispatch grabs NFC tags while the activity is visible
        // so they don't bounce out to the system NDEF chooser.
        val adapter = NfcPairing.adapter(this) ?: return
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE,
        )
        val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED))
        runCatching { adapter.enableForegroundDispatch(this, pi, filters, null) }
    }

    override fun onPause() {
        super.onPause()
        runCatching { NfcPairing.adapter(this)?.disableForegroundDispatch(this) }
    }

    private fun consumeNfcIntent(intent: Intent?) {
        val payload = NfcPairing.payloadFromIntent(intent) ?: return
        // Trust the scanned identity once the service is bound. Schedule a
        // retry on the activity scope so we don't drop the tag if the
        // user opens us cold from an NFC tap.
        activityScope.launch {
            for (attempt in 0 until 20) {
                val svc = meshService
                if (svc != null) {
                    svc.acceptPairing(payload)
                    return@launch
                }
                kotlinx.coroutines.delay(150)
            }
        }
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
        activityScope.cancel()
        super.onDestroy()
    }

    /** Public hook for the UI: re-prompt for whatever's still missing. */
    fun requestMeshPermissions() {
        ensurePermissionsAndStart()
        // Even if the OS perms are already granted, transports may have
        // started in Failed state earlier (Bluetooth off at the time, etc.).
        // Kick them to retry now that the user has come back to fix things.
        meshService?.restartTransports()
    }

    /**
     * Pop the system "Turn on Bluetooth" prompt. Some Samsung devices
     * (M55 in particular) ship with BLE off by default in privacy mode;
     * the discovery banner uses this to give the user a one-tap fix.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun requestEnableBluetooth() {
        runCatching {
            startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
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
