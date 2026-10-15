package io.github.swikrutd.swidscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionInfo
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import io.github.swikrutd.swidscanner.BuildConfig
import io.github.swikrutd.swidscanner.BuildConfigInfo
import io.github.swikrutd.swidscanner.core.DocProcessing
import io.github.swikrutd.swidscanner.core.QuadTracker
import io.github.swikrutd.swidscanner.data.Corner
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.export.AutoSave
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Point
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

private val Ready = Color(0xFF4CD787)
private val Searching = Color(0xFFF2A541)
private val DebugCyan = Color(0xFF3FD0FF)
private const val TAG = "SwidCam"

@Composable
fun CameraScreen(nav: NavController, existingDocId: String?, retakePageId: String? = null, retakeThenDoc: Boolean = false) {
    RequireCamera(nav) { CameraContent(nav, existingDocId, retakePageId, retakeThenDoc) }
}

/** Shows [content] once camera permission is granted, asking for it first. */
@Composable
fun RequireCamera(nav: NavController, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (granted) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera access is needed for scanning.", color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = { nav.popBackStack() }) { Text("Cancel", color = Color.White) }
        }
    }
}

/** Auto-capture bookkeeping. Lives outside Compose state because it changes every frame. */
private class AutoState {
    var last: List<Offset>? = null
    var stableSince = 0L
    var armed = true
    var lastShot = 0L
}

/** One analysed frame: the steadied quad to show and the raw detection, normalised to the upright frame, plus what the debug overlay needs. */
private class FrameResult(val quad: List<Offset>?, val raw: List<Offset>?, val w: Float, val h: Float, val source: OutputTransform?)

/**
 * With [retakePageId] set, the next shot replaces that page's photo and the crop screen
 * opens again; batch mode and gallery import are off.
 */
@Composable
private fun CameraContent(nav: NavController, existingDocId: String?, retakePageId: String?, retakeThenDoc: Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var auto by remember { mutableStateOf(Settings.autoCapture) }
    var batch by remember { mutableStateOf(Settings.batchMode && retakePageId == null) }
    var book by remember { mutableStateOf(Settings.bookMode && retakePageId == null) }
    var torch by remember { mutableStateOf(false) }
    var docId by remember { mutableStateOf(existingDocId) }
    var captured by remember { mutableIntStateOf(0) }
    var pending by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var quad by remember { mutableStateOf<List<Offset>?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val flash = remember { Animatable(0f) }
    val autoState = remember { AutoState() }
    val docMutex = remember { Mutex() }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val ratio43 = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setResolutionSelector(ratio43)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    val preview = remember { Preview.Builder().setResolutionSelector(ratio43).build() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val tracker = remember { QuadTracker() }
    val analysisMs = remember { AtomicLong() }
    val analysisFrames = remember { AtomicLong() }
    val dump = remember { if (BuildConfig.DEBUG) FrameDump(File(ctx.filesDir, "frames")) else null }
    var dumping by remember { mutableStateOf(false) }
    var dumpSaved by remember { mutableIntStateOf(0) }
    var debugFrame by remember { mutableStateOf<List<Offset>?>(null) }
    var debugQuad by remember { mutableStateOf<List<Offset>?>(null) }
    var lastMapLog by remember { mutableStateOf(0L) }
    var lastTimingLog by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        // A batch scan is auto-saved once, after the camera closes.
        AutoSave.scanning = true
        onDispose { AutoSave.scanning = false; analysis.clearAnalyzer(); executor.shutdown(); dump?.shutdown() }
    }

    fun describe(r: ResolutionInfo?) = r?.let { "${it.resolution} crop=${it.cropRect.toShortString()} rot=${it.rotationDegrees}" } ?: "null"

    fun cameraInfo() = JSONObject()
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) Android ${Build.VERSION.RELEASE}")
        .put("preview", describe(preview.resolutionInfo))
        .put("analysis", describe(analysis.resolutionInfo))
        .put("capture", describe(imageCapture.resolutionInfo))
        .put("sensorRotation", camera?.cameraInfo?.sensorRotationDegrees ?: -1)
        .put("previewView", "${previewView.width}x${previewView.height} mode=${previewView.implementationMode}")
        .put("analysisFrameInView", debugFrame?.let { f -> JSONArray(f.map { "%.1f,%.1f".format(it.x, it.y) }) } ?: JSONObject.NULL)

    /** Debug: maps the analysis frame and quad into the view with CameraX's own transform, to check our overlay maths. */
    fun debugMap(r: FrameResult) {
        val src = r.source ?: return
        val target = previewView.outputTransform ?: return
        val ct = CoordinateTransform(src, target)
        fun map(pts: List<Offset>): List<Offset> {
            val a = FloatArray(8)
            pts.forEachIndexed { i, p -> a[2 * i] = p.x; a[2 * i + 1] = p.y }
            ct.mapPoints(a)
            return List(4) { Offset(a[2 * it], a[2 * it + 1]) }
        }
        debugFrame = map(listOf(Offset(0f, 0f), Offset(r.w, 0f), Offset(r.w, r.h), Offset(0f, r.h)))
        debugQuad = r.raw?.let { q -> map(q.map { Offset(it.x * r.w, it.y * r.h) }) }
        val now = SystemClock.uptimeMillis()
        if (now - lastMapLog > 3000) {
            lastMapLog = now
            Log.i(TAG, "camera ${cameraInfo()}")
        }
    }

    suspend fun ensureDoc(): String = docMutex.withLock {
        docId ?: DocStore.createDoc().id.also { docId = it }
    }

    fun finish() {
        if (retakePageId != null && existingDocId != null) {
            // Retake cancelled: back to the page as it was.
            nav.popBackStack()
            nav.navigate(Routes.crop(existingDocId, retakePageId, thenDoc = retakeThenDoc))
            return
        }
        scope.launch {
            while (pending > 0) delay(100)
            val id = docId
            nav.popBackStack()
            if (id != null && existingDocId == null) nav.navigate(Routes.doc(id))
        }
    }

    fun capture() {
        if (capturing) return
        capturing = true
        autoState.armed = false
        autoState.lastShot = SystemClock.uptimeMillis()
        val liveQuad = quad
        imageCapture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val raw = try { image.toBitmap() } finally { image.close() }
                capturing = false
                pending++
                scope.launch { flash.snapTo(0.7f); flash.animateTo(0f, tween(260)) }
                scope.launch {
                    try {
                        val (id, pageId, thumb) = withContext(NonCancellable + Dispatchers.Default) {
                            val bmp = Images.rotate(Images.scaleDown(raw, Images.CAPTURE_MAX), rotation)
                            val id = ensureDoc()
                            val page = if (book) {
                                // Book mode: one photo, two pages that share it with different corners.
                                val (left, right) = Images.splitBook(bmp)
                                DocStore.addPage(id, bmp, left, Settings.defaultFilter)
                                    .also { DocStore.addPage(id, bmp, right, Settings.defaultFilter) }
                            } else {
                                val corners = Images.detectCorners(bmp) ?: liveQuad?.map { Corner(it.x, it.y) }
                                if (retakePageId != null) DocStore.replaceImage(id, retakePageId, bmp, corners)
                                else DocStore.addPage(id, bmp, corners, Settings.defaultFilter)
                            }
                            bmp.recycle()
                            val t = Images.decodeFile(DocStore.thumbFile(id, page), 240).asImageBitmap()
                            Triple(id, page.id, t)
                        }
                        lastThumb = thumb
                        captured += if (book) 2 else 1
                        if (!batch) {
                            nav.popBackStack()
                            val thenDoc = if (retakePageId != null) retakeThenDoc else existingDocId == null
                            nav.navigate(Routes.crop(id, pageId, thenDoc = thenDoc))
                        }
                    } catch (e: Exception) {
                        message = "Couldn't save the page: ${e.message}"
                    } finally {
                        pending--
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                message = "Capture failed: ${exception.message}"
            }
        })
    }

    fun onFrame(q: List<Offset>?) {
        quad = q
        if (dump != null) dumpSaved = dump.saved.get()
        val now = SystemClock.uptimeMillis()
        val st = autoState
        if (q == null) {
            st.last = null
            if (now - st.lastShot > 700) st.armed = true
            progress = 0f
            return
        }
        val prev = st.last
        val delta = if (prev == null) 1f else prev.indices.maxOf { max(abs(prev[it].x - q[it].x), abs(prev[it].y - q[it].y)) }
        if (delta > 0.02f) st.stableSince = now
        // Moving the page (or turning to the next one) re-arms auto-capture.
        if (delta > 0.06f && now - st.lastShot > 700) st.armed = true
        st.last = q
        val area = DocProcessing.polyArea(q.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) })
        val eligible = auto && st.armed && !capturing && (batch || pending == 0) && area > 0.12
        val held = now - st.stableSince
        progress = if (eligible) (held / 1000f).coerceIn(0f, 1f) else 0f
        if (eligible && held >= 1000 && now - st.lastShot > 1200) {
            progress = 0f
            capture()
        }
    }

    LaunchedEffect(Unit) {
        analysis.setAnalyzer(executor) { image ->
            val t0 = SystemClock.uptimeMillis()
            val r = try { analyzeFrame(image, tracker, dump) } catch (_: Throwable) { FrameResult(null, null, 0f, 0f, null) } finally { image.close() }
            analysisMs.addAndGet(SystemClock.uptimeMillis() - t0); analysisFrames.incrementAndGet()
            ContextCompat.getMainExecutor(ctx).execute {
                if (BuildConfigInfo.isDev) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTimingLog > 3000) {
                        lastTimingLog = now
                        val n = analysisFrames.getAndSet(0)
                        Log.i(TAG, "analysis ${if (n > 0) analysisMs.getAndSet(0) / n else 0} ms/frame, $n frames")
                    }
                }
                onFrame(r.quad)
                if (BuildConfig.DEBUG) debugMap(r)
            }
        }
        val provider = ProcessCameraProvider.awaitInstance(ctx)
        preview.setSurfaceProvider(previewView.surfaceProvider)
        provider.unbindAll()
        camera = try {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
        } catch (e: Exception) {
            message = "Couldn't open the camera: ${e.message}"
            null
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        pending++
        scope.launch {
            try {
                val id = ensureDoc()
                importImages(ctx, uris, id)
                captured += uris.size
            } finally { pending-- }
            finish()
        }
    }

    BackHandler { finish() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        book -> "Line up the spine with the line"
                        quad == null -> "Looking for a page"
                        auto && progress > 0f -> "Hold steady"
                        else -> "Page found"
                    },
                    color = Color.White, fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                if (dump != null) {
                    Text(
                        if (dumping) "REC $dumpSaved" else "REC",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (dumping) Color.Red else Color.White.copy(alpha = 0.15f))
                            .clickable {
                                dumping = !dumping
                                if (dumping) dump.start(cameraInfo()) else dump.stop()
                                Log.i(TAG, if (dumping) "dump started in ${dump.dir}" else "dump stopped: ${dump.saved.get()} saved, ${dump.dropped.get()} dropped")
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (retakePageId == null) {
                    IconButton(onClick = { book = !book; Settings.bookMode = book }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.MenuBook, if (book) "Book mode on" else "Book mode off",
                            tint = if (book) Ready else Color.White,
                        )
                    }
                }
                IconButton(onClick = { torch = !torch }) {
                    Icon(if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, "Torch", tint = Color.White)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(3f / 4f)) {
                    AndroidView({ previewView }, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        // Debug: where CameraX says the analysis frame sits in the view, and the raw
                        // (unsteadied) detection.
                        fun outline(p: List<Offset>, c: Color) = drawPath(
                            Path().apply { moveTo(p[0].x, p[0].y); for (i in 1 until 4) lineTo(p[i].x, p[i].y); close() },
                            c, style = Stroke(width = 1.5.dp.toPx()),
                        )
                        debugFrame?.let { outline(it, DebugCyan) }
                        debugQuad?.let { outline(it, DebugCyan) }
                        if (book) {
                            // Spine guide. The single-page outline would only confuse here.
                            drawLine(
                                Color.White.copy(alpha = 0.8f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
                                strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx())),
                            )
                            return@Canvas
                        }
                        val q = quad ?: return@Canvas
                        val pts = q.map { Offset(it.x * size.width, it.y * size.height) }
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                            close()
                        }
                        val c = if (progress > 0f) Ready else Searching
                        drawPath(path, c.copy(alpha = 0.18f + 0.22f * progress))
                        drawPath(path, c, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
                        pts.forEach { drawCircle(c, 6.dp.toPx(), it) }
                    }
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                ModeToggle("Auto", "Manual", auto) { auto = it; Settings.autoCapture = it }
                if (retakePageId == null) {
                    Spacer(Modifier.width(12.dp))
                    ModeToggle("Batch", "Single", batch) { batch = it; Settings.batchMode = it }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (retakePageId == null) {
                    IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Outlined.PhotoLibrary, "Import from gallery", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Shutter(progress = progress, busy = capturing || (!batch && pending > 0)) { if (batch || pending == 0) capture() }
                Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                    val t = lastThumb
                    if (t != null && captured > 0) {
                        Box(Modifier.clickable { finish() }) {
                            Image(
                                t, "Finish", contentScale = ContentScale.Crop,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Color.White, RoundedCornerShape(8.dp)),
                            )
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(22.dp)
                                    .background(MaterialTheme.colorScheme.secondary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("$captured", color = MaterialTheme.colorScheme.onSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        }
                    } else if (pending > 0) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (batch && captured > 0) {
                Text(
                    "Tap the thumbnail when you're done", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                )
            }
        }
        message?.let { m ->
            Text(
                m, color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(10.dp)).padding(16.dp)
                    .clickable { message = null },
            )
        }
    }
}

@Composable
private fun ModeToggle(on: String, off: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).padding(3.dp)) {
        listOf(on to true, off to false).forEach { (label, v) ->
            val selected = value == v
            Text(
                label,
                color = if (selected) Color.Black else Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onChange(v) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Shutter(progress: Float, busy: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(78.dp).clickable(enabled = !busy, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, style = Stroke(4.dp.toPx()))
            if (progress > 0f) {
                drawArc(Ready, -90f, 360f * progress, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
            drawCircle(if (busy) Color.White.copy(alpha = 0.4f) else Color.White, radius = size.minDimension / 2 - 9.dp.toPx())
        }
    }
}

/**
 * Runs live page detection on a preview frame and steadies the result with [tracker].
 * Quads are normalised to the upright frame.
 */
private fun analyzeFrame(image: ImageProxy, tracker: QuadTracker, dump: FrameDump?): FrameResult {
    val rotation = image.imageInfo.rotationDegrees
    val luma = planeMat(image.planes[0], image.width, image.height)
    val up = DocProcessing.rotate(luma, rotation)
    luma.release()
    val chroma = if (image.planes.size >= 3) {
        val u = planeMat(image.planes[1], image.width / 2, image.height / 2)
        val v = planeMat(image.planes[2], image.width / 2, image.height / 2)
        val c = DocProcessing.chromaFromUV(u, v)
        u.release(); v.release()
        DocProcessing.rotate(c, rotation).also { c.release() }
    } else null
    val raw: List<Point>? = DocProcessing.detect(up, chroma, live = true)
    chroma?.release()
    val uw = up.cols(); val uh = up.rows()
    val shown = tracker.update(raw, uw, uh, image.imageInfo.timestamp / 1_000_000)
    var source: OutputTransform? = null
    if (BuildConfig.DEBUG) {
        source = ImageProxyTransformFactory().apply { isUsingRotationDegrees = true }.getOutputTransform(image)
        dump?.save(image, up, raw)
    }
    up.release()
    fun norm(q: List<Point>?) = q?.map { Offset((it.x / uw).toFloat(), (it.y / uh).toFloat()) }
    return FrameResult(norm(shown), norm(raw), uw.toFloat(), uh.toFloat(), source)
}

/** Copies one plane of a YUV_420_888 image into a tightly packed 8-bit Mat, whatever its row and pixel stride. */
private fun planeMat(plane: ImageProxy.PlaneProxy, w: Int, h: Int): Mat {
    val buf = plane.buffer
    val rs = plane.rowStride; val ps = plane.pixelStride
    val bytes = ByteArray(w * h)
    if (ps == 1 && rs == w) {
        buf.position(0); buf.get(bytes, 0, w * h)
    } else if (ps == 1) {
        for (y in 0 until h) { buf.position(y * rs); buf.get(bytes, y * w, w) }
    } else {
        val row = ByteArray(rs)
        for (y in 0 until h) {
            buf.position(y * rs)
            buf.get(row, 0, minOf(rs, buf.remaining()))
            for (x in 0 until w) bytes[y * w + x] = row[x * ps]
        }
    }
    val m = Mat(h, w, CvType.CV_8UC1)
    m.put(0, 0, bytes)
    return m
}
