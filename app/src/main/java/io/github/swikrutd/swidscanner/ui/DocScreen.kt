package io.github.swikrutd.swidscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.export.Saver
import io.github.swikrutd.swidscanner.export.rememberDriveAuthorizer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocScreen(nav: NavController, docId: String) {
    val scope = rememberCoroutineScope()
    val docs by DocStore.docs.collectAsState()
    val busy by DocStore.busy.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val drive = rememberDriveAuthorizer()
    val saveStatus by Saver.status.collectAsState()

    // Drag-to-reorder state. dragOrder is the page order shown while dragging (and until the
    // saved order arrives); it is written to the store once, on drop.
    val gridState = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    val shown = remember(doc?.pages, dragOrder) {
        val pages = doc?.pages ?: emptyList()
        val order = dragOrder
        if (order == null) pages else {
            val byId = pages.associateBy { it.id }
            order.mapNotNull { byId[it] }
        }
    }

    // Moves the dragged page into the slot under the finger.
    fun retarget() {
        val id = dragId ?: return
        val order = dragOrder ?: return
        val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            finger.x >= it.offset.x && finger.x < it.offset.x + it.size.width &&
                finger.y >= it.offset.y && finger.y < it.offset.y + it.size.height
        }?.key as? String ?: return
        if (hit == id) return
        val to = order.indexOf(hit)
        if (to < 0) return
        dragOrder = order.toMutableList().apply { remove(id); add(to, id) }
    }

    LaunchedEffect(dragId) {
        if (dragId == null) return@LaunchedEffect
        val edge = with(density) { 80.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val h = gridState.layoutInfo.viewportSize.height
            val y = finger.y
            val step = when {
                y < edge -> -((edge - y) / edge).coerceIn(0f, 1f) * maxStep
                y > h - edge -> ((y - (h - edge)) / edge).coerceIn(0f, 1f) * maxStep
                else -> 0f
            }
            if (step != 0f) gridState.scrollBy(step)
            retarget()
        }
    }

    // Drop the local order once the store has caught up with it.
    LaunchedEffect(doc?.pages, dragId) {
        val order = dragOrder
        if (dragId == null && order != null && doc?.pages?.map { it.id } == order) dragOrder = null
    }

    LaunchedEffect(doc == null) { if (doc == null) nav.popBackStack() }
    if (doc == null) return

    fun save() {
        saving = true
        scope.launch {
            runCatching { Saver.savePdf(ctx, doc, auto = false) { drive.token() } }
                .onFailure { Saver.setStatus(docId, "Save failed: ${it.message}") }
            saving = false
        }
    }
    // With no save location on yet, Save asks for a folder first.
    val pickFolder = rememberFolderPicker { save() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { renaming = true })
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.ocr(docId)) }, enabled = doc.pages.isNotEmpty()) {
                        Icon(Icons.Outlined.TextSnippet, "Extract text")
                    }
                    IconButton(
                        onClick = { if (Saver.hasDestination) save() else pickFolder.launch(null) },
                        enabled = doc.pages.isNotEmpty() && !saving,
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Save, "Save as PDF")
                    }
                    IconButton(onClick = { exporting = true }, enabled = doc.pages.isNotEmpty()) {
                        Icon(Icons.Outlined.Share, "Share or export")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Delete document") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.camera(docId)) },
                icon = { Icon(Icons.Outlined.AddAPhoto, null) },
                text = { Text("Add pages") },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            saveStatus[docId]?.let { line ->
                Text(
                    line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }
            if (doc.pages.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("No pages. Tap Add pages to scan some.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
                    modifier = Modifier.fillMaxSize().pointerInput(docId) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { off ->
                                val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    off.x >= it.offset.x && off.x < it.offset.x + it.size.width &&
                                        off.y >= it.offset.y && off.y < it.offset.y + it.size.height
                                } ?: return@detectDragGesturesAfterLongPress
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                dragOrder = DocStore.get(docId)?.pages?.map { it.id }
                                grab = off - Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
                                finger = off
                                dragId = hit.key as? String
                            },
                            onDrag = { change, _ ->
                                if (dragId != null) {
                                    change.consume()
                                    finger = change.position
                                    retarget()
                                }
                            },
                            onDragEnd = {
                                val id = dragId
                                val order = dragOrder
                                dragId = null
                                if (id != null && order != null) {
                                    if (order == DocStore.get(docId)?.pages?.map { it.id }) dragOrder = null
                                    else scope.launch { DocStore.movePages(docId, listOf(id), order.indexOf(id)) }
                                }
                                // A long press released without moving is reserved for selection (Step 6).
                            },
                            onDragCancel = { dragId = null; dragOrder = null },
                        )
                    },
                ) {
                    itemsIndexed(shown, key = { _, p -> p.id }) { i, p ->
                        val lifted = dragId == p.id
                        val scale by animateFloatAsState(if (lifted) 1.08f else 1f, label = "lift")
                        Box(
                            Modifier
                                .then(if (lifted) Modifier else Modifier.animateItem())
                                .zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                    if (lifted) {
                                        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == p.id }
                                        if (info != null) {
                                            translationX = finger.x - grab.x - info.offset.x
                                            translationY = finger.y - grab.y - info.offset.y
                                        }
                                    }
                                }
                                .padding(8.dp).fillMaxWidth().aspectRatio(0.75f)
                                .clickable { if (dragId == null) nav.navigate(Routes.page(docId, p.id)) },
                        ) {
                            AsyncImage(
                                model = DocStore.thumbFile(docId, p), contentDescription = "Page ${i + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().shadow(if (lifted) 14.dp else 2.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            )
                            Text(
                                "${i + 1}", fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                            if (p.id in busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }

    if (exporting) ExportSheet(doc, onDismiss = { exporting = false })
    if (renaming) RenameDialog(doc.name, onDismiss = { renaming = false }) { n -> scope.launch { DocStore.rename(docId, n) }; renaming = false }
    if (deleting) {
        ConfirmDialog("Delete document?", "All ${doc.pages.size} page(s) will be removed from this phone.", "Delete",
            onDismiss = { deleting = false }) {
            deleting = false
            scope.launch { DocStore.deleteDoc(docId) }
        }
    }
}
