package io.github.swikrutd.swidscanner.ui

import android.graphics.Bitmap
import android.graphics.Matrix
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import io.github.swikrutd.swidscanner.core.DocProcessing
import io.github.swikrutd.swidscanner.core.Filter
import io.github.swikrutd.swidscanner.core.QuadTracker
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.export.AutoName
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "SwidId"

/** Resolution of the review screen's page previews. */
private const val PREVIEW_DPI = 100.0

/** What an ID scan shoots and how it lays the cards out. */
enum class IdPreset(val label: String, val docName: String) {
    /** Front and back of an ID-1 card on one page. */
    ID_CARD("ID card", "ID card"),
    /** One shot of the open passport in a 125 x 176 mm slot. */
    PASSPORT("Passport", "Passport"),
    /** Any number of ID-1 cards, one per page. */
    SINGLE("Single side", "ID card"),
    /** Front and back of a card of the user's size. */
    CUSTOM("Custom", "ID card"),
}

/** A preset with its card size. */
private data class IdSpec(val preset: IdPreset, val wMm: Double, val hMm: Double) {
    /** Shots per document: front and back, one, or as many as the user takes. */
    val sides: Int get() = when (preset) {
        IdPreset.ID_CARD, IdPreset.CUSTOM -> 2
        IdPreset.PASSPORT -> 1
        IdPreset.SINGLE -> Int.MAX_VALUE
    }
    val twoSided: Boolean get() = sides == 2
    /** Cards are cut out big enough to fill a page on their own at 300 dpi, so a blown up card stays sharp. */
    private val cutScale: Double get() = max(1.0, DocProcessing.idMaxScale(wMm, hMm, 1))
    val wPx: Int get() = DocProcessing.mmToPx(wMm * cutScale).roundToInt()
    val hPx: Int get() = DocProcessing.mmToPx(hMm * cutScale).roundToInt()
    val guide: DoubleArray get() = DocProcessing.idGuide(wMm, hMm)

    /** The big label in the guide for shot number [index] (0 based). */
    fun label(index: Int) = when {
        preset == IdPreset.PASSPORT -> "Passport"
        preset == IdPreset.SINGLE -> "Card ${index + 1}"
        index == 0 -> "Front"
        else -> "Back"
    }
}

private fun specFor(p: IdPreset) = when (p) {
    IdPreset.PASSPORT -> IdSpec(p, 125.0, 176.0)
    IdPreset.CUSTOM -> IdSpec(p, Settings.idCustomWidth.toDouble(), Settings.idCustomHeight.toDouble())
    else -> IdSpec(p, DocProcessing.ID1_W_MM, DocProcessing.ID1_H_MM)
}

/** A cut-out card waiting for the user to keep it, plus whether its outline was found. */
private class Shot(val card: Bitmap, val found: Boolean, val fromGallery: Boolean) {
    val image: ImageBitmap = card.asImageBitmap()
}

/**
 * ID card scan: a camera with a card-shaped guide, a review after each shot, then a page review
 * that lays the cards out on A4 (at true size, scaled, or filling the page) and saves them as a new document.
 */
@Composable
fun IdScanScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var spec by remember {
        mutableStateOf(specFor(IdPreset.entries.firstOrNull { it.name == Settings.idPreset } ?: IdPreset.ID_CARD))
    }
    val cards = remember { mutableStateListOf<Bitmap>() }
    var shot by remember { mutableStateOf<Shot?>(null) }
    var finishing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun leave() { if (cards.isEmpty() && shot == null) nav.popBackStack() else confirmLeave = true }

    /** Cuts the card out of [photo] off the main thread and opens the shot review. */
    fun process(photo: Bitmap, fromGallery: Boolean) {
        working = true
        val s = spec
        scope.launch {
            try {
                val (card, found) = withContext(Dispatchers.Default) {
                    Images.cutCard(photo, if (fromGallery) null else s.guide, s.wPx, s.hPx).also { photo.recycle() }
                }
                shot = Shot(card, found, fromGallery)
            } catch (e: Exception) {
                Log.w(TAG, "cut failed", e)
                message = "Could not read the photo"
            } finally {
                working = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            val photo = withContext(Dispatchers.IO) { runCatching { Images.decodeUri(ctx, uri, Images.CAPTURE_MAX) }.getOrNull() }
            if (photo == null) { working = false; message = "Could not read the selected image" } else process(photo, fromGallery = true)
        }
    }

    BackHandler(enabled = !working) { leave() }

    val current = shot
    when {
        finishing -> PageReview(
            spec, cards,
            onBack = { leave() },
            onSaved = { docId -> nav.popBackStack(); nav.navigate(Routes.doc(docId)) },
        )
        current != null -> ShotReview(
            spec, current, label = spec.label(cards.size),
            last = cards.size + 1 >= spec.sides,
            onClose = { leave() },
            onRetake = { shot = null },
            onRotate = { shot = Shot(rotated(current.card), current.found, current.fromGallery) },
            onKeep = { done ->
                cards += current.card
                shot = null
                if (done || cards.size >= spec.sides) finishing = true
            },
        )
        else -> IdCamera(
            spec = spec,
            label = spec.label(cards.size),
            shotsTaken = cards.size,
            working = working,
            onClose = { leave() },
            onPhoto = { process(it, fromGallery = false) },
            onGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPreset = { p ->
                if (p == IdPreset.CUSTOM) customOpen = true
                else { spec = specFor(p); Settings.idPreset = p.name }
            },
            onFinish = { finishing = true },
        )
    }

    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            "Discard this scan?", "The card photos you took will be lost.", "Discard",
            onDismiss = { confirmLeave = false },
        ) { confirmLeave = false; nav.popBackStack() }
    }
    if (customOpen) {
        CustomSizeDialog(onDismiss = { customOpen = false }) { w, h ->
            Settings.idCustomWidth = w; Settings.idCustomHeight = h; Settings.idPreset = IdPreset.CUSTOM.name
            spec = specFor(IdPreset.CUSTOM)
            customOpen = false
        }
    }
}

/** A copy of [b] turned 90 degrees clockwise. [b] is left alone because the screen may still be drawing it. */
private fun rotated(b: Bitmap): Bitmap =
    Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)

/**
 * The camera with the card guide: corner brackets in the card's shape and the side to shoot in
 * big letters. Auto-capture (off by default) shoots once a card has sat still in the guide.
 */
@Composable
private fun IdCamera(
    spec: IdSpec,
    label: String,
    shotsTaken: Int,
    working: Boolean,
    onClose: () -> Unit,
    onPhoto: (Bitmap) -> Unit,
    onGallery: () -> Unit,
    onPreset: (IdPreset) -> Unit,
    onFinish: () -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(Settings.idAutoCapture) }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var liveQuad by remember { mutableStateOf<List<Offset>?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val ratio43 = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val preview = remember { Preview.Builder().setResolutionSelector(ratio43).build() }
    val imageCapture = remember { ImageCapture.Builder().setResolutionSelector(ratio43).build() }
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
    val executor = remember { Executors.newSingleThreadExecutor() }
    val tracker = remember { QuadTracker() }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) {
        onDispose { analysis.clearAnalyzer(); executor.shutdown(); providerRef[0]?.unbindAll() }
    }

    val autoNow by rememberUpdatedState(auto)
    val busyNow by rememberUpdatedState(capturing || working)
    val specNow by rememberUpdatedState(spec)
    val photoNow by rememberUpdatedState(onPhoto)

    fun shoot() {
        if (capturing || working) return
        capturing = true
        progress = 0f
        imageCapture.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val raw = try { image.toBitmap() } finally { image.close() }
                scope.launch {
                    val bmp = withContext(Dispatchers.Default) { Images.rotate(Images.scaleDown(raw, Images.CAPTURE_MAX), rotation) }
                    // The parent shows its own progress while it cuts the card out.
                    photoNow(bmp)
                    capturing = false
                }
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                error = "Capture failed: ${exception.message}"
            }
        })
    }

    // Auto-capture bookkeeping, per frame, so plain fields rather than Compose state.
    val openedAt = remember { SystemClock.uptimeMillis() }
    val st = remember { object { var last: List<Offset>? = null; var stableSince = 0L; var armed = shotsTaken == 0 } }

    fun onFrame(q: List<Offset>?) {
        val now = SystemClock.uptimeMillis()
        liveQuad = if (autoNow) q else null
        // For the back, wait until the front has left the guide (or a few seconds) so it is not shot twice.
        if (!st.armed && (q == null || now - openedAt > 3000)) st.armed = true
        val g = specNow.guide
        val fits = q != null && run {
            val cx = q.sumOf { it.x.toDouble() } / 4; val cy = q.sumOf { it.y.toDouble() } / 4
            val share = DocProcessing.polyArea(q.map { Point(it.x.toDouble(), it.y.toDouble()) }) / ((g[2] - g[0]) * (g[3] - g[1]))
            cx in g[0]..g[2] && cy in g[1]..g[3] && share in 0.35..1.6
        }
        if (!autoNow || busyNow || q == null || !fits || !st.armed) {
            st.last = null; progress = 0f
            return
        }
        val prev = st.last
        val delta = if (prev == null) 1f else prev.indices.maxOf { max(abs(prev[it].x - q[it].x), abs(prev[it].y - q[it].y)) }
        if (delta > 0.015f) st.stableSince = now
        st.last = q
        val held = now - st.stableSince
        progress = (held / 1000f).coerceIn(0f, 1f)
        if (held >= 1000 && now - openedAt > 800) shoot()
    }

    LaunchedEffect(Unit) {
        analysis.setAnalyzer(executor) { image ->
            if (!autoNow) { image.close(); return@setAnalyzer }
            val r = try { analyzeFrame(image, tracker, null) } catch (_: Throwable) { null } finally { image.close() }
            ContextCompat.getMainExecutor(ctx).execute { onFrame(r?.quad) }
        }
        val provider = ProcessCameraProvider.awaitInstance(ctx)
        providerRef[0] = provider
        preview.setSurfaceProvider(previewView.surfaceProvider)
        provider.unbindAll()
        camera = try {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
        } catch (e: Exception) {
            error = "Could not open the camera: ${e.message}"
            null
        }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    LaunchedEffect(auto) { if (!auto) { liveQuad = null; progress = 0f } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(
                    if (spec.preset == IdPreset.PASSPORT) "Fit the open passport in the frame" else "Fit the card in the frame",
                    color = Color.White, fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { torch = !torch }) {
                    Icon(if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, "Torch", tint = Color.White)
                }
            }
            if (shotsTaken == 0) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    IdPreset.entries.forEach { p ->
                        val selected = spec.preset == p
                        Text(
                            if (p == IdPreset.CUSTOM && selected) "Custom ${mm(spec.wMm)} x ${mm(spec.hMm)}" else p.label,
                            color = if (selected) Color.Black else Color.White,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
                                .clickable { onPreset(p) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(3f / 4f)) {
                    AndroidView({ previewView }, Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val q = liveQuad
                        if (q != null) {
                            val pts = q.map { Offset(it.x * size.width, it.y * size.height) }
                            val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until 4) lineTo(pts[i].x, pts[i].y); close() }
                            drawPath(path, if (progress > 0f) Ready else Searching, style = Stroke(width = 2.dp.toPx()))
                        }
                        val g = spec.guide
                        val l = g[0].toFloat() * size.width; val t = g[1].toFloat() * size.height
                        val r = g[2].toFloat() * size.width; val b = g[3].toFloat() * size.height
                        val len = minOf(32.dp.toPx(), (r - l) / 4, (b - t) / 4)
                        val sw = 4.dp.toPx()
                        val c = if (progress > 0f) Ready else Color.White
                        fun bracket(at: Offset, dx: Float, dy: Float) {
                            drawLine(c, at, at + Offset(dx * len, 0f), sw, StrokeCap.Round)
                            drawLine(c, at, at + Offset(0f, dy * len), sw, StrokeCap.Round)
                        }
                        bracket(Offset(l, t), 1f, 1f); bracket(Offset(r, t), -1f, 1f)
                        bracket(Offset(l, b), 1f, -1f); bracket(Offset(r, b), -1f, -1f)
                    }
                    if (working || capturing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                    } else {
                        Text(
                            label,
                            modifier = Modifier.align(Alignment.Center),
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.85f), fontSize = 44.sp, fontWeight = FontWeight.Bold,
                                shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 10f),
                            ),
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                ModeToggle("Auto", "Manual", auto) { auto = it; Settings.idAutoCapture = it }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onGallery, enabled = !working && !capturing) {
                    Icon(Icons.Outlined.PhotoLibrary, "Pick from gallery", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Shutter(progress = progress, busy = capturing || working) { shoot() }
                Box(Modifier.width(88.dp), contentAlignment = Alignment.Center) {
                    when {
                        spec.preset == IdPreset.SINGLE && shotsTaken > 0 ->
                            TextButton(onClick = onFinish, enabled = !working) { Text("Done ($shotsTaken)", color = Color.White) }
                        spec.twoSided && shotsTaken == 1 ->
                            TextButton(onClick = onFinish, enabled = !working) { Text("No back", color = Color.White) }
                    }
                }
            }
        }
        error?.let { m ->
            Text(
                m, color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(10.dp)).padding(16.dp)
                    .clickable { error = null },
            )
        }
    }
}

/** One cut-out card with retake and rotate. [last] is true when keeping it finishes the scan. */
@Composable
private fun ShotReview(
    spec: IdSpec,
    shot: Shot,
    label: String,
    last: Boolean,
    onClose: () -> Unit,
    onRetake: () -> Unit,
    onRotate: () -> Unit,
    onKeep: (done: Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            val radius = (8 * DocProcessing.cardRadiusMm(spec.wMm) / DocProcessing.ID1_RADIUS_MM).dp
            Image(
                shot.image, label, contentScale = ContentScale.Fit,
                modifier = Modifier
                    .aspectRatio(shot.card.width.toFloat() / shot.card.height)
                    .clip(RoundedCornerShape(radius)),
            )
        }
        if (!shot.found) {
            Text(
                if (shot.fromGallery) "No card edge found, so the whole picture is used." else "No card edge found, so the photo is cropped to the frame.",
                color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            ReviewAction(Icons.Outlined.Replay, "Retake", onRetake)
            ReviewAction(Icons.AutoMirrored.Outlined.RotateRight, "Rotate", onRotate)
            if (spec.preset == IdPreset.SINGLE) {
                OutlinedButton(onClick = { onKeep(true) }) { Text("Done", color = Color.White) }
                Button(onClick = { onKeep(false) }) { Text("Next card") }
            } else {
                Button(onClick = { onKeep(false) }) { Text(if (last) "Continue" else "Scan back") }
            }
        }
    }
}

@Composable
private fun ReviewAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White)
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

/** The pages the cards make: front and back together, or one card per page. */
private fun pageGroups(cards: List<Bitmap>, spec: IdSpec, separate: Boolean): List<List<Bitmap>> =
    if (spec.twoSided && cards.size == 2 && !separate) listOf(cards.toList()) else cards.map { listOf(it) }

/** Preview of the finished pages with the page options, and Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageReview(spec: IdSpec, cards: List<Bitmap>, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(if (Settings.defaultFilter == Filter.ORIGINAL) Filter.ORIGINAL else Filter.ENHANCE) }
    var separate by remember { mutableStateOf(false) }
    var watermark by remember { mutableStateOf("") }
    var fit by remember { mutableStateOf(Settings.idFit) }
    var scale by remember { mutableFloatStateOf(Settings.idScale) }
    var previews by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Card size: true size times [scale], or as big as the page allows with [fit].
    val perPage = pageGroups(cards, spec, separate).maxOf { it.size }
    val maxScale = DocProcessing.idMaxScale(spec.wMm, spec.hMm, perPage).toFloat()
    val minScale = minOf(0.5f, maxScale)
    val shown = if (fit) maxScale else scale.coerceIn(minScale, maxScale)

    LaunchedEffect(filter, separate, watermark, shown) {
        // Typing the watermark or dragging the size re-renders once the user pauses.
        delay(if (previews.isEmpty()) 0L else 250L)
        val text = watermark
        previews = withContext(Dispatchers.Default) {
            pageGroups(cards, spec, separate).map { g ->
                Images.renderIdPage(g, spec.wMm, spec.hMm, filter, text, PREVIEW_DPI, shown.toDouble()).asImageBitmap()
            }
        }
    }

    fun save() {
        saving = true
        scope.launch {
            try {
                val docId = withContext(NonCancellable + Dispatchers.Default) {
                    val doc = DocStore.createDoc(AutoName.dated(spec.preset.docName))
                    // One full size page at a time: each is about 35 MB as a bitmap.
                    for (g in pageGroups(cards, spec, separate)) {
                        val page = Images.renderIdPage(g, spec.wMm, spec.hMm, filter, watermark, DocProcessing.ID_DPI, shown.toDouble())
                        // The filter and watermark are already in the image, so the page itself starts unfiltered.
                        DocStore.addPage(doc.id, page, null, Filter.ORIGINAL)
                        page.recycle()
                    }
                    doc.id
                }
                onSaved(docId)
            } catch (e: Exception) {
                Log.w(TAG, "save failed", e)
                error = "Could not save: ${e.message}"
                saving = false
            }
        }
    }

    // Saving finishes even if the screen goes, so stay put until it has.
    BackHandler(enabled = saving) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(spec.preset.docName) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { save() }, enabled = !saving && previews.isNotEmpty()) { Text("Save") } },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(pad)) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (previews.isEmpty()) {
                        CircularProgressIndicator()
                    } else {
                        LazyRow(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            itemsIndexed(previews) { i, p ->
                                Image(
                                    p, "Page ${i + 1}", contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxHeight().aspectRatio(p.width.toFloat() / p.height, matchHeightConstraintsFirst = true)
                                        .shadow(4.dp).background(Color.White),
                                )
                            }
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sizeNote = if (abs(shown - 1f) < 0.005f) "true size" else "${(shown * 100).roundToInt()}%"
                    Text(
                        "${previews.size} page${if (previews.size == 1) "" else "s"}, A4, " +
                            "cards ${mm(spec.wMm * shown)} x ${mm(spec.hMm * shown)} mm ($sizeNote)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Size", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(72.dp))
                        FilterChip(
                            selected = !fit && abs(shown - 1f) < 0.005f, enabled = maxScale >= 1f,
                            onClick = { fit = false; scale = 1f; Settings.idFit = false; Settings.idScale = 1f },
                            label = { Text("True size") },
                        )
                        FilterChip(selected = fit, onClick = { fit = true; Settings.idFit = true }, label = { Text("Fit page") })
                    }
                    if (maxScale - minScale > 0.01f) {
                        Slider(
                            shown,
                            { v ->
                                // Snap to true size, and to fit at the top end.
                                scale = if (abs(v - 1f) < 0.03f) 1f else v
                                fit = scale >= maxScale - 0.005f
                            },
                            valueRange = minScale..maxScale,
                            onValueChangeFinished = { Settings.idScale = scale; Settings.idFit = fit },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Filter", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(72.dp))
                        FilterChip(selected = filter == Filter.ORIGINAL, onClick = { filter = Filter.ORIGINAL }, label = { Text("Colour") })
                        FilterChip(selected = filter == Filter.ENHANCE, onClick = { filter = Filter.ENHANCE }, label = { Text("Enhanced") })
                    }
                    if (spec.twoSided && cards.size == 2) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep front and back as separate pages", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(separate, { separate = it })
                        }
                    }
                    OutlinedTextField(
                        value = watermark, onValueChange = { watermark = it.take(60) }, singleLine = true,
                        label = { Text("Watermark (optional)") }, placeholder = { Text("For KYC only") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (saving) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)) {
                    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
    error?.let { m ->
        AlertDialog(
            onDismissRequest = { error = null },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}

/** Card width and height in mm for the custom preset. */
@Composable
private fun CustomSizeDialog(onDismiss: () -> Unit, onDone: (Float, Float) -> Unit) {
    var w by remember { mutableStateOf(mm(Settings.idCustomWidth.toDouble())) }
    var h by remember { mutableStateOf(mm(Settings.idCustomHeight.toDouble())) }
    val wv = w.replace(',', '.').toFloatOrNull()
    val hv = h.replace(',', '.').toFloatOrNull()
    val ok = wv != null && hv != null && wv in 20f..200f && hv in 20f..280f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Card size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    w, { w = it }, label = { Text("Width (mm)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    h, { h = it }, label = { Text("Height (mm)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    "Width 20 to 200 mm, height 20 to 280 mm. The front and back go on one A4 page, shrunk only if they do not fit.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(wv!!, hv!!) }, enabled = ok) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A size in mm without a trailing ".0". */
private fun mm(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)
