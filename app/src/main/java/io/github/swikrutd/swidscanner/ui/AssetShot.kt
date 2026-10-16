package io.github.swikrutd.swidscanner.ui

import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import io.github.swikrutd.swidscanner.data.AssetKind
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SwidAsset"

/**
 * Camera for scanning a signature or stamp: a fixed window in the middle with the rest dimmed. No page
 * detection and no auto-capture, the shutter does the work. The photo goes to [SigDraft] and the
 * crop screen opens with its box on the window.
 */
@Composable
fun AssetShot(nav: NavController, kind: AssetKind) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val ratio43 = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val imageCapture = remember { ImageCapture.Builder().setResolutionSelector(ratio43).build() }
    val preview = remember { Preview.Builder().setResolutionSelector(ratio43).build() }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(ctx)
        preview.setSurfaceProvider(previewView.surfaceProvider)
        provider.unbindAll()
        camera = try {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: Exception) {
            message = "Could not open the camera: ${e.message}"
            null
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    fun shoot() {
        if (capturing) return
        capturing = true
        imageCapture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val raw = try { image.toBitmap() } finally { image.close() }
                scope.launch {
                    try {
                        withContext(Dispatchers.Default) {
                            val bmp = Images.rotate(Images.scaleDown(raw, 2400), rotation)
                            SigDraft.save(ctx, bmp)
                            bmp.recycle()
                        }
                        nav.popBackStack()
                        nav.navigate(Routes.assetImport(kind, window = true))
                    } catch (e: Exception) {
                        Log.w(TAG, "save failed", e)
                        message = "Could not save the photo"
                        capturing = false
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                message = "Capture failed: ${exception.message}"
            }
        })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Text("Fit your ${kind.noun} in the box", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { torch = !torch }) {
                    Icon(if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, "Torch", tint = Color.White)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(3f / 4f)) {
                    AndroidView({ previewView }, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val w = SigWindow.rect
                        val r = Rect(w.left * size.width, w.top * size.height, w.right * size.width, w.bottom * size.height)
                        val outside = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, size.height)))
                            addRoundRect(RoundRect(r, CornerRadius(10.dp.toPx())))
                        }
                        drawPath(outside, Color.Black.copy(alpha = 0.6f))
                        val len = 24.dp.toPx()
                        val sw = 3.dp.toPx()
                        fun bracket(c: Offset, dx: Float, dy: Float) {
                            drawLine(Color.White, c, c + Offset(dx * len, 0f), sw, StrokeCap.Round)
                            drawLine(Color.White, c, c + Offset(0f, dy * len), sw, StrokeCap.Round)
                        }
                        bracket(r.topLeft, 1f, 1f); bracket(r.topRight, -1f, 1f)
                        bracket(r.bottomLeft, 1f, -1f); bracket(r.bottomRight, -1f, -1f)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
                Shutter(progress = 0f, busy = capturing) { shoot() }
            }
        }
        message?.let { m ->
            Text(
                m, color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(10.dp)).padding(16.dp),
            )
        }
    }
}
