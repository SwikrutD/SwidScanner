package io.github.swikrutd.swidscanner.ui

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import io.github.swikrutd.swidscanner.export.Delivery
import java.util.concurrent.Executors

/** Decodes QR codes and common barcodes from camera frames with ZXing. */
private class CodeAnalyzer(private val onResult: (Result) -> Unit) : ImageAnalysis.Analyzer {
    @Volatile var paused = false
    private var frame = 0
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF,
            ),
        ))
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            val plane = image.planes[0]
            val w = image.width; val h = image.height; val rs = plane.rowStride
            val buf = plane.buffer
            val data = ByteArray(w * h)
            if (rs == w) buf.get(data, 0, w * h) else for (y in 0 until h) { buf.position(y * rs); buf.get(data, y * w, w) }
            // Barcodes held across a portrait screen land sideways in the sensor image, so also try rotated.
            val r = decode(data, w, h) ?: if (frame++ % 2 == 0) decode(rotate(data, w, h), h, w) else null
            if (r != null && !paused) { paused = true; onResult(r) }
        } finally {
            image.close()
        }
    }

    private fun decode(d: ByteArray, w: Int, h: Int): Result? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(d, w, h, 0, 0, w, h, false))))
    } catch (_: ReaderException) { null } finally { reader.reset() }

    private fun rotate(d: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(d.size)
        for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = d[y * w + x]
        return out
    }
}

@Composable
fun QrScreen(nav: NavController) {
    RequireCamera(nav) { QrContent(nav) }
}

@Composable
private fun QrContent(nav: NavController) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    var result by remember { mutableStateOf<Result?>(null) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var copied by remember { mutableStateOf(false) }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analyzer = remember { CodeAnalyzer { r -> ContextCompat.getMainExecutor(ctx).execute { result = r } } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    DisposableEffect(Unit) { onDispose { analysis.clearAnalyzer(); executor.shutdown() } }

    LaunchedEffect(Unit) {
        analysis.setAnalyzer(executor, analyzer)
        val provider = ProcessCameraProvider.awaitInstance(ctx)
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        provider.unbindAll()
        camera = runCatching { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }.getOrNull()
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.68f
            val tl = Offset((size.width - side) / 2, (size.height - side) / 2.4f)
            drawRoundRect(Color.White, tl, androidx.compose.ui.geometry.Size(side, side), CornerRadius(24.dp.toPx()), style = Stroke(3.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
            Text("Point at a QR code or barcode", color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = { torch = !torch }) {
                Icon(if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, "Torch", tint = Color.White)
            }
        }

        result?.let { r ->
            val text = r.text.orEmpty()
            val url = text.takeIf {
                Patterns.WEB_URL.matcher(it).matches() || it.startsWith("http://") || it.startsWith("https://") ||
                    it.startsWith("mailto:") || it.startsWith("tel:") || it.startsWith("geo:")
            }
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).navigationBarsPadding(),
                shape = RoundedCornerShape(20.dp), tonalElevation = 4.dp,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(r.barcodeFormat.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (url != null) {
                            Button(onClick = {
                                val u = if (url.contains(":")) url else "https://$url"
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                            }) { Text("Open") }
                        }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) { Text(if (copied) "Copied" else "Copy") }
                        OutlinedButton(onClick = { Delivery.shareText(ctx, text) }) { Text("Share") }
                    }
                    TextButton(onClick = { result = null; copied = false; analyzer.paused = false }) { Text("Scan another") }
                }
            }
        }
    }
}
