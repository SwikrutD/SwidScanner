package io.github.swikrutd.swidscanner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Approval
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.swikrutd.swidscanner.core.Filter
import io.github.swikrutd.swidscanner.data.AssetKind
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Overlay
import io.github.swikrutd.swidscanner.data.Page
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(nav: NavController, docId: String, pageId: String) {
    val scope = rememberCoroutineScope()
    val docs by DocStore.docs.collectAsState()
    val busy by DocStore.busy.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    val pages = doc?.pages.orEmpty()
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)) { pages.size }
    var showFilters by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf<AssetKind?>(null) }

    LaunchedEffect(pages.isEmpty()) { if (pages.isEmpty()) nav.popBackStack() }
    if (doc == null || pages.isEmpty()) return
    val index = pager.currentPage.coerceIn(0, pages.size - 1)
    val page = pages[index]

    fun move(delta: Int) {
        val target = index + delta
        if (target !in pages.indices) return
        scope.launch { DocStore.movePage(docId, page.id, delta); pager.scrollToPage(target) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Page ${index + 1} of ${pages.size}") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { move(-1) }, enabled = index > 0) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Move earlier") }
                    IconButton(onClick = { move(1) }, enabled = index < pages.size - 1) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Move later") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "Delete page") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { pages[it].id }) { i ->
                val p = pages[i]
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    ZoomableImage(DocStore.procFile(docId, p), p) { o -> nav.navigate(Routes.place(docId, p.id, overlay = o.id)) }
                    if (p.id in busy) CircularProgressIndicator()
                }
            }
            if (showFilters) FilterStrip(docId, page)
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Action(Icons.Outlined.Crop, "Crop") { nav.navigate(Routes.crop(docId, page.id)) }
                    Action(Icons.AutoMirrored.Outlined.RotateRight, "Rotate") {
                        scope.launch { DocStore.updatePage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) } }
                    }
                    Action(Icons.Outlined.FilterVintage, "Filters", selected = showFilters) { showFilters = !showFilters }
                    Action(Icons.Outlined.TextFields, "Text") { nav.navigate(Routes.ocr(docId, page.id)) }
                    Action(Icons.Outlined.Draw, "Sign") { placing = AssetKind.SIGNATURE }
                    Action(Icons.Outlined.Approval, "Stamp") { placing = AssetKind.STAMP }
                }
            }
        }
    }

    placing?.let { kind ->
        AssetSheet(
            kind, onDismiss = { placing = null },
            onManage = { placing = null; nav.navigate(Routes.library(kind)) },
        ) { a -> placing = null; nav.navigate(Routes.place(docId, page.id, asset = a.id)) }
    }

    if (deleting) {
        ConfirmDialog("Delete this page?", "Page ${index + 1} will be removed.", "Delete", onDismiss = { deleting = false }) {
            deleting = false
            scope.launch { DocStore.deletePage(docId, page.id) }
        }
    }
}

@Composable
private fun RowScope.Action(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(model: Any, page: Page, onOverlayTap: (Overlay) -> Unit) {
    var scale by remember(model) { mutableFloatStateOf(1f) }
    var offset by remember(model) { mutableStateOf(Offset.Zero) }
    var imageSize by remember(model) { mutableStateOf(Size.Unspecified) }
    val current by rememberUpdatedState(page)
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    AsyncImage(
        model = model, contentDescription = null, contentScale = ContentScale.Fit,
        onSuccess = { imageSize = it.painter.intrinsicSize },
        modifier = Modifier.fillMaxSize()
            .pointerInput(model) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    },
                    onTap = { pos ->
                        // Undo the zoom, then find the tap inside the fitted image (it is letterboxed).
                        val img = imageSize
                        if (!img.isSpecified || img.width <= 0f || img.height <= 0f) return@detectTapGestures
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val p = c + (pos - c - offset) / scale
                        val s = min(size.width / img.width, size.height / img.height)
                        val w = img.width * s; val h = img.height * s
                        val u = (p.x - (size.width - w) / 2) / w
                        val v = (p.y - (size.height - h) / 2) / h
                        val slop = 8.dp.toPx() / (w * scale)
                        current.overlays.asReversed().firstOrNull { overlayContains(it, u, v, h / w, slop) }?.let(onOverlayTap)
                    },
                )
            }
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}

@Composable
private fun FilterStrip(docId: String, page: Page) {
    val scope = rememberCoroutineScope()
    var previews by remember { mutableStateOf<Map<Filter, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(page.id, page.corners, page.rotation) {
        previews = withContext(Dispatchers.Default) {
            val small = Images.decodeFile(DocStore.origFile(docId, page.id), 420)
            val out = Filter.entries.associateWith { f -> Images.renderPage(small, page.copy(filter = f, overlays = emptyList())).asImageBitmap() }
            small.recycle()
            out
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Filter.entries) { f ->
                val sel = f == page.filter
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !sel) {
                        scope.launch { DocStore.updatePage(docId, page.id) { it.copy(filter = f) } }
                    }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(width = 64.dp, height = 84.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .then(if (sel) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        val b = previews[f]
                        if (b != null) Image(b, f.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text(f.label, style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { scope.launch { DocStore.setFilterForAll(docId, page.filter) } }) {
                Text("Apply ${page.filter.label} to all pages")
            }
        }
        Box(Modifier.height(2.dp).width(1.dp))
    }
}
