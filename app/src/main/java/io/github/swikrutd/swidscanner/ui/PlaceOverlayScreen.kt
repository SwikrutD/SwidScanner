package io.github.swikrutd.swidscanner.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Approval
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.swikrutd.swidscanner.data.Asset
import io.github.swikrutd.swidscanner.data.AssetKind
import io.github.swikrutd.swidscanner.data.AssetStore
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Overlay
import io.github.swikrutd.swidscanner.data.Page
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MIN_WIDTH = 0.03f
private const val MAX_WIDTH = 1f
private const val MAX_ZOOM = 5f
/** Room around the fitted page so handles on its edge stay reachable. */
private val MARGIN = 28.dp

/** Tint choices under the page. Null keeps the asset's own colours. */
private val TINTS: List<Color?> = listOf(null, InkBlack, InkBlue, StampRed)

/** Height over width of each asset's PNG, read from the file header once. */
private val ratios = ConcurrentHashMap<String, Float>()

fun assetRatio(assetId: String): Float = ratios.getOrPut(assetId) {
    val f = AssetStore.file(assetId) ?: return 1f
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    if (o.outWidth > 0 && o.outHeight > 0) o.outHeight.toFloat() / o.outWidth else 1f
}

/**
 * True when ([u], [v]), in 0..1 of the page, falls on [o]. Works in page units where the page is
 * 1 wide and [pageRatio] (height over width) tall, so rotation is measured the same way renderPage
 * draws it. [slop] widens the box, also in page widths.
 */
fun overlayContains(o: Overlay, u: Float, v: Float, pageRatio: Float, slop: Float = 0f): Boolean {
    val dx = u - o.cx
    val dy = (v - o.cy) * pageRatio
    val a = Math.toRadians(-o.rotation.toDouble())
    val lx = dx * cos(a) - dy * sin(a)
    val ly = dx * sin(a) + dy * cos(a)
    return abs(lx) <= o.width / 2 + slop && abs(ly) <= o.width * assetRatio(o.assetId) / 2 + slop
}

/** Snaps to the nearest right angle within 4 degrees and keeps the result in -180..180. */
private fun snapRotation(deg: Float): Float {
    var r = ((deg % 360f) + 540f) % 360f - 180f
    val right = (r / 90f).roundToInt() * 90f
    if (abs(r - right) < 4f) r = right
    return if (r <= -180f) r + 360f else r
}

private fun Offset.angle() = Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()

private fun rotated(c: Offset, x: Float, y: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return c + Offset((x * cos(a) - y * sin(a)).toFloat(), (x * sin(a) + y * cos(a)).toFloat())
}

// Editor state survives a trip to the library ("Manage") and a process restart.
private fun Overlay.pack(): List<Any?> = listOf(id, assetId, type.name, cx, cy, width, rotation, opacity, tint)

private fun unpack(l: List<Any?>) = Overlay(
    l[0] as String, l[1] as String, AssetKind.valueOf(l[2] as String),
    l[3] as Float, l[4] as Float, l[5] as Float, l[6] as Float, l[7] as Float, l[8] as Int?,
)

private val overlayListSaver = listSaver<SnapshotStateList<Overlay>, List<Any?>>(
    save = { list -> list.map { it.pack() } },
    restore = { items -> mutableStateListOf<Overlay>().apply { addAll(items.map(::unpack)) } },
)

@Suppress("UNCHECKED_CAST")
private val savedListSaver = Saver<List<Overlay>?, Any>(
    save = { list -> list?.let { l -> ArrayList(l.map { it.pack() }) } },
    restore = { (it as List<List<Any?>>).map(::unpack) },
)

private val idListSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { mutableStateListOf<String>().apply { addAll(it) } },
)

private enum class Grab { PAGE, MOVE, PINCH, HANDLE, DELETE, NONE }

/**
 * Places signatures and stamps on one page. The page is rendered without its overlays and every
 * overlay is drawn live on top, so all of them can be moved, resized, recoloured or removed.
 * [assetId] adds a new placement of that asset; [overlayId] selects an existing one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceOverlayScreen(nav: NavController, docId: String, pageId: String, assetId: String?, overlayId: String?) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val docs by DocStore.docs.collectAsState()
    val pages = docs.firstOrNull { it.id == docId }?.pages.orEmpty()

    var pageImg by remember { mutableStateOf<ImageBitmap?>(null) }
    val overlays = rememberSaveable(saver = overlayListSaver) { mutableStateListOf() }
    val assetImgs = remember { mutableStateMapOf<String, ImageBitmap>() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // What the page holds on disk, or null until loaded. Done only writes when the list differs.
    var saved by rememberSaveable(stateSaver = savedListSaver) { mutableStateOf<List<Overlay>?>(null) }
    // Overlays placed or resized here; their final width becomes the asset's lastWidth.
    val touched = rememberSaveable(saver = idListSaver) { mutableStateListOf() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var adding by remember { mutableStateOf<AssetKind?>(null) }
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val margin = with(LocalDensity.current) { MARGIN.toPx() }

    val selected = overlays.firstOrNull { it.id == selectedId }
    val changed = saved != null && overlays.toList() != saved

    fun update(id: String, change: (Overlay) -> Overlay) {
        val i = overlays.indexOfFirst { it.id == id }
        if (i >= 0) overlays[i] = change(overlays[i])
    }

    /** The page as fitted in the canvas at zoom 1, with room around it for the handles. */
    fun fitRect(canvas: Size, img: ImageBitmap, margin: Float): Rect {
        val w = canvas.width - 2 * margin; val h = canvas.height - 2 * margin
        val s = min(w / img.width, h / img.height)
        val dw = img.width * s; val dh = img.height * s
        return Rect(Offset((canvas.width - dw) / 2, (canvas.height - dh) / 2), Size(dw, dh))
    }

    /** Where the page is drawn now, after zoom and pan. */
    fun display(canvas: Size, img: ImageBitmap, margin: Float): Rect {
        val f = fitRect(canvas, img, margin)
        val c = Offset(canvas.width / 2, canvas.height / 2) + pan
        return Rect(Offset(c.x - f.width * zoom / 2, c.y - f.height * zoom / 2), Size(f.width * zoom, f.height * zoom))
    }

    fun clampPan(p: Offset, canvas: Size, img: ImageBitmap, margin: Float): Offset {
        val f = fitRect(canvas, img, margin)
        val mx = max(0f, (f.width * zoom - canvas.width) / 2 + margin)
        val my = max(0f, (f.height * zoom - canvas.height) / 2 + margin)
        return if (zoom <= 1f) Offset.Zero else Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
    }

    fun centre(o: Overlay, d: Rect) = Offset(d.left + o.cx * d.width, d.top + o.cy * d.height)

    /** Screen half size of [o] with [pad] added on each side, so handles clear small overlays. */
    fun halfSize(o: Overlay, d: Rect, pad: Float): Size {
        val w = o.width * d.width
        return Size(w / 2 + pad, w * assetRatio(o.assetId) / 2 + pad)
    }

    /** Delete handle (top left) and the scale and rotate handle (bottom right), on screen. */
    fun handles(o: Overlay, d: Rect, pad: Float): Pair<Offset, Offset> {
        val c = centre(o, d); val h = halfSize(o, d, pad)
        return rotated(c, -h.width, -h.height, o.rotation) to rotated(c, h.width, h.height, o.rotation)
    }

    fun add(a: Asset) {
        val img = pageImg
        // Start in the middle of what is on screen, which matters when the page is zoomed in.
        var cx = 0.5f; var cy = 0.5f
        if (img != null && canvasSize != Size.Zero) {
            val d = display(canvasSize, img, margin)
            cx = ((canvasSize.width / 2 - d.left) / d.width).coerceIn(0f, 1f)
            cy = ((canvasSize.height / 2 - d.top) / d.height).coerceIn(0f, 1f)
        }
        val o = Overlay(DocStore.newId(), a.id, a.kind, cx, cy, a.lastWidth.coerceIn(MIN_WIDTH, MAX_WIDTH))
        overlays.add(o)
        selectedId = o.id
        touched.add(o.id)
    }

    LaunchedEffect(docId, pageId) {
        // A render still running from an earlier edit would give us old overlays.
        DocStore.busy.first { pageId !in it }
        val page = DocStore.get(docId)?.pages?.firstOrNull { it.id == pageId } ?: run { nav.popBackStack(); return@LaunchedEffect }
        pageImg = withContext(Dispatchers.Default) {
            val orig = Images.decodeFile(DocStore.origFile(docId, pageId), 2000)
            val out = Images.renderPage(orig, page.copy(overlays = emptyList()))
            orig.recycle()
            out.asImageBitmap()
        }
        // Coming back from the library keeps the edits made so far.
        if (saved == null) {
            overlays.clear(); overlays.addAll(page.overlays)
            saved = page.overlays
            if (overlayId != null && page.overlays.any { it.id == overlayId }) selectedId = overlayId
            assetId?.let { AssetStore.get(it) }?.let { add(it) }
        }
    }

    val assetIds = overlays.map { it.assetId }.toSet()
    LaunchedEffect(assetIds) {
        for (id in assetIds - assetImgs.keys) {
            val b = withContext(Dispatchers.IO) { AssetStore.file(id)?.let { f -> BitmapFactory.decodeFile(f.path) } } ?: continue
            assetImgs[id] = b.asImageBitmap()
        }
    }

    fun rememberWidths() {
        val widths = touched.mapNotNull { id -> overlays.firstOrNull { it.id == id } }.associate { it.assetId to it.width }
        touched.clear()
        DocStore.work.launch { widths.forEach { (id, w) -> AssetStore.setLastWidth(id, w) } }
    }

    fun done() {
        val list = overlays.toList()
        if (saved != null && list != saved) DocStore.work.launch { DocStore.setOverlays(docId, pageId, list) }
        rememberWidths()
        nav.popBackStack()
    }

    /** Saves this page and adds a copy of the selected overlay, at the same normalised spot, to [targets]. */
    fun applyTo(targets: List<String>) {
        val o = selected ?: return
        val list = overlays.toList()
        val writeThis = list != saved
        saved = list
        rememberWidths()
        // Skip pages that already have this exact placement, so applying twice does not stack copies.
        fun same(a: Overlay) = a.assetId == o.assetId && abs(a.cx - o.cx) < 1e-3f && abs(a.cy - o.cy) < 1e-3f && abs(a.width - o.width) < 1e-3f
        val todo = pages.filter { it.id in targets && it.id != pageId && it.overlays.none(::same) }.map { it.id }
        DocStore.work.launch {
            if (writeThis) DocStore.setOverlays(docId, pageId, list)
            todo.forEach { pid -> DocStore.updatePage(docId, pid) { p -> p.copy(overlays = p.overlays + o.copy(id = DocStore.newId())) } }
        }
        scope.launch {
            snack.showSnackbar(
                when (todo.size) {
                    0 -> "Those pages already have it"
                    1 -> "Added to 1 more page"
                    else -> "Added to ${todo.size} more pages"
                },
            )
        }
    }

    BackHandler { if (changed) discarding = true else nav.popBackStack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign and stamp") },
                navigationIcon = {
                    IconButton(onClick = { if (changed) discarding = true else nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { adding = AssetKind.SIGNATURE }) { Icon(Icons.Outlined.Draw, "Add signature") }
                    IconButton(onClick = { adding = AssetKind.STAMP }) { Icon(Icons.Outlined.Approval, "Add stamp") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        val canApply = selected != null && pages.size > 1
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Apply to all pages") }, enabled = canApply, onClick = {
                                menu = false; applyTo(pages.map { it.id })
                            })
                            DropdownMenuItem({ Text("Apply to pages...") }, enabled = canApply, onClick = {
                                menu = false; picking = true
                            })
                            DropdownMenuItem({ Text("Remove") }, enabled = selected != null, onClick = {
                                menu = false; overlays.removeAll { it.id == selectedId }; selectedId = null
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val img = pageImg
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                if (img == null) CircularProgressIndicator()
                else OverlayCanvas(
                    img, overlays, assetImgs, selectedId,
                    display = { size, m -> display(size, img, m) },
                    handles = ::handles, halfSize = ::halfSize, centre = ::centre,
                    onSize = { canvasSize = it },
                    onSelect = { selectedId = it },
                    onChange = ::update,
                    onTouched = { if (it !in touched) touched.add(it) },
                    onDelete = { id -> overlays.removeAll { it.id == id }; touched.remove(id); selectedId = null },
                    onZoom = { factor, centroid, move, size, m ->
                        val d = display(size, img, m)
                        val nz = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                        val k = nz / zoom
                        // Keep the point under the fingers where it is.
                        val nc = centroid + (d.center - centroid) * k + move
                        zoom = nz
                        pan = clampPan(nc - Offset(size.width / 2, size.height / 2), size, img, m)
                    },
                    zoomed = { zoom > 1f },
                )
            }
            Surface(tonalElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val s = selected
                    if (s != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Colour", Modifier.width(76.dp), style = MaterialTheme.typography.labelLarge)
                            TintRow(s.tint) { t -> update(s.id) { it.copy(tint = t) } }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Opacity", Modifier.width(76.dp), style = MaterialTheme.typography.labelLarge)
                            Slider(s.opacity, { v -> update(s.id) { it.copy(opacity = v) } }, Modifier.weight(1f), valueRange = 0.1f..1f)
                        }
                    } else {
                        Text(
                            if (overlays.isEmpty()) "Add a signature or stamp with the buttons at the top."
                            else "Tap a signature or stamp to change it.",
                            Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = { done() }, enabled = saved != null) { Text("Done") }
                    }
                }
            }
        }
    }

    adding?.let { kind ->
        AssetSheet(
            kind, onDismiss = { adding = null },
            onManage = { adding = null; nav.navigate(Routes.library(kind)) },
        ) { a -> adding = null; add(a) }
    }
    if (picking) {
        PagePickerDialog(docId, pages, pageId, onDismiss = { picking = false }) { ids -> picking = false; applyTo(ids) }
    }
    if (discarding) {
        ConfirmDialog("Discard changes?", "Signatures and stamps you changed on this page will go back to how they were.", "Discard",
            onDismiss = { discarding = false }) { discarding = false; nav.popBackStack() }
    }
}

/**
 * The page with its overlays, the selection box and the two handles, plus every gesture:
 * drag an overlay to move it, the bottom right handle scales and rotates around the centre,
 * the top left handle deletes, two fingers that start on an overlay resize and turn it,
 * two fingers anywhere else zoom the page and one finger pans a zoomed page.
 */
@Composable
private fun OverlayCanvas(
    page: ImageBitmap,
    overlays: List<Overlay>,
    assetImgs: Map<String, ImageBitmap>,
    selectedId: String?,
    display: (Size, Float) -> Rect,
    handles: (Overlay, Rect, Float) -> Pair<Offset, Offset>,
    halfSize: (Overlay, Rect, Float) -> Size,
    centre: (Overlay, Rect) -> Offset,
    onSize: (Size) -> Unit,
    onSelect: (String?) -> Unit,
    onChange: (String, (Overlay) -> Overlay) -> Unit,
    onTouched: (String) -> Unit,
    onDelete: (String) -> Unit,
    onZoom: (Float, Offset, Offset, Size, Float) -> Unit,
    zoomed: () -> Boolean,
) {
    val primary = MaterialTheme.colorScheme.primary
    val danger = MaterialTheme.colorScheme.error
    val closeIcon = rememberVectorPainter(Icons.Outlined.Close)
    val scaleIcon = rememberVectorPainter(Icons.Outlined.OpenInFull)
    // The gesture loop outlives recompositions, so it reads the selection through this.
    val currentSelected by rememberUpdatedState(selectedId)

    Canvas(
        Modifier.fillMaxSize().onSizeChanged { onSize(it.toSize()) }.pointerInput(page) {
            val margin = MARGIN.toPx()
            val pad = 6.dp.toPx()
            val handleHit = 26.dp.toPx()
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val canvas = Size(size.width.toFloat(), size.height.toFloat())
                var d = display(canvas, margin)
                fun current(id: String?) = overlays.firstOrNull { it.id == id }

                var grab = Grab.PAGE
                var target: String? = null
                val sel = current(currentSelected)
                val hs = sel?.let { handles(it, d, pad) }
                if (sel != null && hs != null && (down.position - hs.second).getDistance() < handleHit) {
                    grab = Grab.HANDLE; target = sel.id
                } else if (sel != null && hs != null && (down.position - hs.first).getDistance() < handleHit) {
                    grab = Grab.DELETE; target = sel.id
                } else {
                    val u = (down.position.x - d.left) / d.width
                    val v = (down.position.y - d.top) / d.height
                    val touchSlop = pad / d.width
                    // The selected overlay wins, then the topmost one.
                    val hit = (listOfNotNull(sel) + overlays.asReversed())
                        .firstOrNull { overlayContains(it, u, v, d.height / d.width, touchSlop) }
                    if (hit != null) { grab = Grab.MOVE; target = hit.id; onSelect(hit.id) }
                }
                val start = current(target)
                val startVec = start?.let { down.position - centre(it, d) } ?: Offset.Zero
                var rawRot = start?.rotation ?: 0f
                var moved = false
                var multi = false
                var travel = Offset.Zero
                down.consume()

                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed == 0) break
                    d = display(canvas, margin)
                    if (pressed >= 2) {
                        multi = true; moved = true
                        when (grab) {
                            Grab.MOVE -> { grab = Grab.PINCH; rawRot = current(target)?.rotation ?: 0f }
                            Grab.DELETE -> grab = Grab.NONE
                            else -> {}
                        }
                    }
                    val step = ev.calculatePan()
                    if (!moved) {
                        travel += step
                        if (travel.getDistance() > slop) moved = true
                    }
                    if (moved) when (grab) {
                        Grab.MOVE, Grab.PINCH -> target?.let { id ->
                            val z = if (pressed >= 2) ev.calculateZoom() else 1f
                            if (pressed >= 2) rawRot += ev.calculateRotation()
                            onChange(id) {
                                it.copy(
                                    cx = (it.cx + step.x / d.width).coerceIn(0f, 1f),
                                    cy = (it.cy + step.y / d.height).coerceIn(0f, 1f),
                                    width = (it.width * z).coerceIn(MIN_WIDTH, MAX_WIDTH),
                                    rotation = if (grab == Grab.PINCH) snapRotation(rawRot) else it.rotation,
                                )
                            }
                        }
                        Grab.HANDLE -> {
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            val s = start
                            if (ch != null && ch.pressed && s != null) {
                                val vec = ch.position - centre(s, d)
                                val k = vec.getDistance() / startVec.getDistance().coerceAtLeast(1f)
                                onChange(s.id) {
                                    it.copy(
                                        width = (s.width * k).coerceIn(MIN_WIDTH, MAX_WIDTH),
                                        rotation = snapRotation(s.rotation + vec.angle() - startVec.angle()),
                                    )
                                }
                            }
                        }
                        Grab.PAGE -> {
                            if (pressed >= 2) onZoom(ev.calculateZoom(), ev.calculateCentroid(), step, canvas, margin)
                            else if (zoomed()) onZoom(1f, Offset.Zero, step, canvas, margin)
                        }
                        Grab.DELETE -> grab = Grab.NONE
                        Grab.NONE -> {}
                    }
                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                }

                when {
                    grab == Grab.DELETE -> target?.let(onDelete)
                    grab == Grab.PAGE && !moved && !multi -> onSelect(null)
                    moved && target != null && grab != Grab.NONE -> onTouched(target!!)
                }
            }
        },
    ) {
        val margin = MARGIN.toPx()
        val pad = 6.dp.toPx()
        val d = display(size, margin)
        drawImage(
            page, dstOffset = IntOffset(d.left.roundToInt(), d.top.roundToInt()),
            dstSize = IntSize(d.width.roundToInt(), d.height.roundToInt()), filterQuality = FilterQuality.High,
        )
        // Overlays are cut at the page edge, as they are in the rendered page.
        clipRect(d.left, d.top, d.right, d.bottom) {
            overlays.forEach { o -> assetImgs[o.assetId]?.let { drawOverlay(o, it, centre(o, d), o.width * d.width) } }
        }
        overlays.firstOrNull { it.id == selectedId }?.let { o ->
            val c = centre(o, d); val h = halfSize(o, d, pad)
            withTransform({ translate(c.x, c.y); rotate(o.rotation, Offset.Zero) }) {
                drawRect(
                    primary, Offset(-h.width, -h.height), Size(2 * h.width, 2 * h.height),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
            val (del, scale) = handles(o, d, pad)
            drawHandle(del, danger, closeIcon, 0f)
            // The arrows of OpenInFull run corner to corner; turn them onto this handle's diagonal.
            drawHandle(scale, primary, scaleIcon, o.rotation + 90f)
        }
    }
}

private fun DrawScope.drawOverlay(o: Overlay, img: ImageBitmap, c: Offset, w: Float) {
    val h = w * img.height / img.width
    withTransform({ translate(c.x, c.y); rotate(o.rotation, Offset.Zero) }) {
        drawImage(
            img,
            dstOffset = IntOffset((-w / 2).roundToInt(), (-h / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            alpha = o.opacity.coerceIn(0f, 1f),
            // SrcIn keeps the asset's alpha and replaces its colour, as renderPage does.
            colorFilter = o.tint?.let { ColorFilter.tint(Color(it or 0xFF000000.toInt()), BlendMode.SrcIn) },
            filterQuality = FilterQuality.High,
        )
    }
}

private fun DrawScope.drawHandle(p: Offset, color: Color, icon: Painter, iconRotation: Float) {
    val r = 13.dp.toPx()
    drawCircle(Color.White, r + 2.dp.toPx(), p)
    drawCircle(color, r, p)
    val s = 16.dp.toPx()
    withTransform({ rotate(iconRotation, p); translate(p.x - s / 2, p.y - s / 2) }) {
        with(icon) { draw(Size(s, s), colorFilter = ColorFilter.tint(Color.White)) }
    }
}

@Composable
private fun TintRow(tint: Int?, onChange: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TINTS.forEach { c ->
            val argb = c?.toArgb()
            val sel = argb == tint
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .then(if (c == null) Modifier.checkerboard(10f) else Modifier.background(c))
                    .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClickLabel = if (c == null) "Original colours" else "Colour") { onChange(argb) },
                contentAlignment = Alignment.Center,
            ) {
                if (c == null) Icon(Icons.Outlined.FormatColorReset, "Original colours", Modifier.size(18.dp), tint = Color.DarkGray)
            }
        }
    }
}

/** Bottom sheet with the library of [kind]. Tapping an asset picks it; [onManage] opens the library to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetSheet(kind: AssetKind, onDismiss: () -> Unit, onManage: () -> Unit, onPick: (Asset) -> Unit) {
    val all by AssetStore.assets.collectAsState()
    val items = all.filter { it.kind == kind }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kind.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onManage) { Text(if (items.isEmpty()) "Add ${kind.noun}" else "Manage") }
            }
            if (items.isEmpty()) {
                Text(
                    "No ${kind.noun}s yet.", Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    GridCells.Fixed(2), Modifier.heightIn(max = 460.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { a ->
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { onPick(a) },
                        ) {
                            TransparentPreview(Modifier.fillMaxWidth().aspectRatio(1.6f), file = AssetStore.file(a.id), inset = 10.dp, cell = 14f)
                            Text(
                                a.name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Picks the other pages of the document to copy a placement to. */
@Composable
private fun PagePickerDialog(docId: String, pages: List<Page>, currentId: String, onDismiss: () -> Unit, onApply: (List<String>) -> Unit) {
    val others = pages.filter { it.id != currentId }
    val chosen = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply to pages") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        if (chosen.size == others.size) chosen.clear() else { chosen.clear(); chosen.addAll(others.map { it.id }) }
                    }) { Text(if (chosen.size == others.size) "Clear" else "Select all") }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(others, key = { it.id }) { p ->
                        val n = pages.indexOf(p) + 1
                        val on = p.id in chosen
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { if (on) chosen.remove(p.id) else chosen.add(p.id) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, { if (it) chosen.add(p.id) else chosen.remove(p.id) })
                            AsyncImage(
                                DocStore.thumbFile(docId, p), null, Modifier.size(40.dp, 52.dp).clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Text("Page $n", Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
