package team.hex.meshlink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import team.hex.meshlink.R
import team.hex.meshlink.pairing.PairingPayload
import team.hex.meshlink.ui.theme.GlassSurface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live camera QR scanner.
 *
 * Specifically engineered to lock on to a `meshlink:1:…` payload on the
 * cheapest current Android hardware:
 *   - Y-plane row stride is honoured by *copying out* a tightly packed
 *     luminance buffer (rather than passing the padded stride to ZXing,
 *     which used to misalign every row past the first on Samsung devices).
 *   - Format hint pinned to QR_CODE so MultiFormatReader doesn't waste
 *     decode attempts on 1D barcodes the app would reject anyway.
 *   - Inverted-luminance fallback handles white-on-black QRs printed by
 *     dark-mode pairing screens.
 *   - Tap-to-focus: a tap on the preview triggers a manual AF/AE round
 *     so the user can fix soft-focus frames that the continuous-AF mode
 *     ignores when the phone is held very still.
 *   - Camera-init / decode counters surface visibly so the user knows
 *     the scanner is actually trying when nothing happens within a
 *     second of opening.
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                        error = null
                        onScanned(parsed)
                    } else {
                        error = errNotMeshlink
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
    var status by remember { mutableStateOf("Запуск камеры…") }
    var framesAnalyzed by remember { mutableIntStateOf(0) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

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
                        // COMPATIBLE backs the preview with a SurfaceView,
                        // which avoids the black-frame bug on some Samsung
                        // ROMs that ship a quirky TextureView pipeline.
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    previewViewRef = previewView
                    val providerFuture = ProcessCameraProvider.getInstance(c)
                    providerFuture.addListener({
                        val provider = try {
                            providerFuture.get()
                        } catch (t: Throwable) {
                            status = "Камера недоступна: ${t.message}"
                            Log.w(TAG, "camera provider failed: $t")
                            return@addListener
                        }
                        // 4:3 frames keep finder-pattern detection happy on
                        // most rear cameras; CameraX picks the closest match
                        // the device actually supports.
                        val resolutionSelector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .build()
                        val preview = androidx.camera.core.Preview.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .build().apply {
                                setSurfaceProvider(previewView.surfaceProvider)
                            }
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetRotation(previewView.display?.rotation ?: 0)
                            .build()
                        analysis.setAnalyzer(analyzerExecutor) { proxy: ImageProxy ->
                            try {
                                framesAnalyzed += 1
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
                            camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, analysis,
                            )
                            status = "Сканирую QR-код…"
                        }.onFailure {
                            status = "Не удалось привязать камеру"
                            Log.w(TAG, "bindToLifecycle failed: $it")
                        }
                    }, ContextCompat.getMainExecutor(c))
                    previewView
                },
                modifier = Modifier
                    .fillMaxSize()
                    // Tap-to-focus: ask the camera to AF/AE on the touched
                    // point. CameraX caps the metering action at five
                    // seconds, which is plenty to lock on a stationary QR.
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val pv = previewViewRef ?: return@detectTapGestures
                            val factory = pv.meteringPointFactory
                            val point = factory.createPoint(offset.x, offset.y)
                            val action = FocusMeteringAction.Builder(
                                point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
                            runCatching { camera?.cameraControl?.startFocusAndMetering(action) }
                        }
                    },
            )
            ScannerReticle(modifier = Modifier.fillMaxSize())
        }
        // Status pill — explicit feedback so the user can tell the
        // scanner is actually running. "Сканирую QR-код… N кадров"
        // beats blank screen when nothing matches.
        GlassSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            shape = RoundedCornerShape(999.dp),
        ) {
            Text(
                "$status · $framesAnalyzed кадров",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
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
        drawLine(accent, Offset(left, top + corner), Offset(left, top), w)
        drawLine(accent, Offset(left, top), Offset(left + corner, top), w)
        drawLine(accent, Offset(left + side - corner, top), Offset(left + side, top), w)
        drawLine(accent, Offset(left + side, top), Offset(left + side, top + corner), w)
        drawLine(accent, Offset(left, top + side - corner), Offset(left, top + side), w)
        drawLine(accent, Offset(left, top + side), Offset(left + corner, top + side), w)
        drawLine(accent, Offset(left + side - corner, top + side), Offset(left + side, top + side), w)
        drawLine(accent, Offset(left + side, top + side), Offset(left + side, top + side - corner), w)
    }
}

private const val TAG = "QrScanner"

/**
 * Decode a QR from a CameraX [ImageProxy].
 *
 * We deliberately *unpad* the Y plane into a fresh `width*height` buffer
 * instead of trusting `PlanarYUVLuminanceSource` to honour `rowStride` —
 * on most Samsung devices the latter path silently misaligns rows past
 * the first and ZXing never finds a finder pattern. Cost is one extra
 * memcpy per frame which the hot loop can absorb.
 *
 * Tries the unrotated frame first, then the inverted luminance for
 * white-on-black QRs (dark-mode pairing screens).
 */
private fun decodeImage(proxy: ImageProxy): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val width = proxy.width
    val height = proxy.height
    if (width <= 0 || height <= 0) return null

    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val rawBuffer = plane.buffer
    val raw = ByteArray(rawBuffer.remaining()).also { rawBuffer.get(it) }

    // Pack into a tight luminance buffer regardless of stride / pixel
    // stride. YUV_420_888's Y plane usually has pixelStride==1, but the
    // contract allows any value, so handle both.
    val packed = ByteArray(width * height)
    if (pixelStride == 1 && rowStride == width) {
        System.arraycopy(raw, 0, packed, 0, packed.size)
    } else if (pixelStride == 1) {
        var src = 0
        var dst = 0
        for (row in 0 until height) {
            System.arraycopy(raw, src, packed, dst, width)
            src += rowStride
            dst += width
        }
    } else {
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                packed[row * width + col] = raw[rowStart + col * pixelStride]
            }
        }
    }

    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            )
        )
    }
    val source = PlanarYUVLuminanceSource(
        packed, width, height,
        0, 0, width, height, false,
    )
    runCatching {
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }
    reader.reset()
    return runCatching {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
    }.getOrNull()
}
