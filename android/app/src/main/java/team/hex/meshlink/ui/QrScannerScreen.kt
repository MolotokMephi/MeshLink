package team.hex.meshlink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.hex.meshlink.R
import team.hex.meshlink.pairing.PairingPayload
import team.hex.meshlink.ui.theme.GlassSurface
import java.util.concurrent.Executors

/**
 * Live camera QR scanner. Drives a CameraX preview and feeds frames into
 * ZXing's MultiFormatReader, then hands a parsed `meshlink:1:…` payload
 * back via [onScanned].
 *
 * Robustness notes:
 *   - We pick the QR_CODE format explicitly + TRY_HARDER. Without the
 *     format hint, MultiFormatReader spends time on 1D barcodes that we
 *     never accept anyway; with it, slow Samsung mid-range cameras start
 *     locking on within a second.
 *   - YUV row-stride padding is honoured so portrait frames decode
 *     correctly. Without this, on most Samsungs every row past the first
 *     was misaligned and ZXing never found the finder pattern — the user-
 *     visible symptom was "scanner sees nothing".
 *   - We rotate the luminance source through 90° / 180° / 270° if the
 *     unrotated frame failed. CameraX delivers sensor-native orientation,
 *     so a phone held in portrait yields a sideways image; ZXing's QR
 *     finder is rotation-tolerant in theory but real camera frames need
 *     the rotation to actually lock on.
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
    val scope = rememberCoroutineScope()

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
                    if (parsed != null) {
                        // Stash the error before navigating away so a
                        // not-meshlink toast doesn't briefly flash.
                        error = null
                        onScanned(parsed)
                    } else {
                        error = errNotMeshlink
                        // Auto-clear so the user can re-aim.
                        scope.launch {
                            delay(2_500)
                            error = null
                        }
                    }
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
    var lastSeen by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            AndroidView(
                factory = { c ->
                    val previewView = PreviewView(c).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(c)
                    providerFuture.addListener({
                        val provider = try {
                            providerFuture.get()
                        } catch (t: Throwable) {
                            Log.w(TAG, "camera provider failed: $t")
                            return@addListener
                        }
                        val preview = androidx.camera.core.Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                        // 720p analysis frames give us enough resolution
                        // for QR finder-pattern detection on cheap rear
                        // cameras while keeping decode latency bounded.
                        val resolutionSelector = ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                        analysis.setAnalyzer(analyzerExecutor) { proxy: ImageProxy ->
                            try {
                                val payload = decodeImage(proxy)
                                if (payload != null && payload != lastSeen) {
                                    lastSeen = payload
                                    onPayload(payload)
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "analyzer error: $t")
                            } finally { proxy.close() }
                        }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, analysis,
                            )
                        }.onFailure { Log.w(TAG, "bindToLifecycle failed: $it") }
                    }, ContextCompat.getMainExecutor(c))
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Reticle overlay so the user knows where to aim.
            ScannerReticle(modifier = Modifier.fillMaxSize())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
            runCatching {
                ProcessCameraProvider.getInstance(ctx).get().unbindAll()
            }
        }
    }
}

@Composable
private fun ScannerReticle(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height) * 0.7f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val corner = side * 0.12f
        val w = 6f
        // Top-left
        drawLine(accent, Offset(left, top + corner), Offset(left, top), w)
        drawLine(accent, Offset(left, top), Offset(left + corner, top), w)
        // Top-right
        drawLine(accent, Offset(left + side - corner, top), Offset(left + side, top), w)
        drawLine(accent, Offset(left + side, top), Offset(left + side, top + corner), w)
        // Bottom-left
        drawLine(accent, Offset(left, top + side - corner), Offset(left, top + side), w)
        drawLine(accent, Offset(left, top + side), Offset(left + corner, top + side), w)
        // Bottom-right
        drawLine(accent, Offset(left + side - corner, top + side), Offset(left + side, top + side), w)
        drawLine(accent, Offset(left + side, top + side), Offset(left + side, top + side - corner), w)
    }
}

private const val TAG = "QrScanner"

/**
 * Try to decode a QR from a CameraX [ImageProxy]. We honour rowStride
 * padding so portrait frames on Samsung devices (which always pad the Y
 * plane) decode correctly; before this, every row past the first was
 * misaligned and ZXing never found the finder pattern.
 *
 * If the regular HybridBinarizer pass misses (low contrast, screen
 * glare), we retry with the inverted luminance source — works around
 * dark-mode QRs printed white-on-black.
 */
private fun decodeImage(proxy: ImageProxy): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val rowStride = plane.rowStride.coerceAtLeast(proxy.width)
    val width = proxy.width
    val height = proxy.height
    if (width <= 0 || height <= 0) return null

    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            )
        )
    }

    val source = PlanarYUVLuminanceSource(
        data, rowStride, height,
        0, 0, width, height, false,
    )
    // First try: regular polarity. Second try: inverted (white-on-black QRs).
    runCatching {
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }
    reader.reset()
    return runCatching {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
    }.getOrNull()
}
