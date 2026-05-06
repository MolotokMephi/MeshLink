package team.hex.meshlink.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.width
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import team.hex.meshlink.R
import team.hex.meshlink.pairing.PairingPayload
import team.hex.meshlink.ui.theme.GlassSurface
import java.util.concurrent.Executors

/**
 * Live camera QR scanner. Drives a [CameraX] preview and feeds frames
 * into ZXing's MultiFormatReader; once we recognise a `meshlink:1:…`
 * payload we hand it back via [onScanned] and stop the camera.
 *
 * Camera permission is requested inline — denial keeps the scanner on
 * a static fallback that lets the user paste instead.
 */
@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    onScanned: (PairingPayload) -> Unit,
) {
    val ctx = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }
    LaunchedEffect(Unit) {
        if (!cameraGranted) launcher.launch(Manifest.permission.CAMERA)
    }
    var error by remember { mutableStateOf<String?>(null) }
    val errNotMeshlink = stringResource(R.string.status_qr_not_meshlink)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.qr_scan_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f))
        }
        if (cameraGranted) {
            CameraScanner(
                onPayload = { payload ->
                    val parsed = PairingPayload.decodeOrNull(payload)
                    if (parsed != null) onScanned(parsed)
                    else error = errNotMeshlink
                },
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassSurface(
                    modifier = Modifier.padding(24.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.pairing_camera_perm_title),
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pairing_camera_perm_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            launcher.launch(Manifest.permission.CAMERA)
                        }) { Text(stringResource(R.string.action_grant_camera)) }
                    }
                }
            }
        }
        error?.let {
            Text(it, modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CameraScanner(onPayload: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }
    }
    var lastSeen by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(20.dp))) {
        AndroidView(
            factory = { c ->
                val previewView = PreviewView(c)
                val providerFuture = ProcessCameraProvider.getInstance(c)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = androidx.camera.core.Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor) { proxy: ImageProxy ->
                        try {
                            val payload = decodeImage(reader, proxy)
                            if (payload != null && payload != lastSeen) {
                                lastSeen = payload
                                onPayload(payload)
                            }
                        } finally { proxy.close() }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, analysis,
                        )
                    }
                }, ContextCompat.getMainExecutor(c))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
            ProcessCameraProvider.getInstance(ctx).get().unbindAll()
        }
    }
}

private fun decodeImage(reader: MultiFormatReader, proxy: ImageProxy): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    // CameraX delivers YUV_420_888 with rowStride that is usually >=
    // image width on real devices (the Y-plane is padded). Passing
    // proxy.width as dataWidth misaligns every row past the first and
    // ZXing never locks onto the finder pattern.
    val rowStride = plane.rowStride.coerceAtLeast(proxy.width)
    val source = PlanarYUVLuminanceSource(
        data, rowStride, proxy.height,
        0, 0, proxy.width, proxy.height, false,
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decodeWithState(bitmap).text
    } catch (_: Throwable) {
        null
    } finally {
        reader.reset()
    }
}
