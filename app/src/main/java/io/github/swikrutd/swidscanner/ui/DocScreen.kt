package io.github.swikrutd.swidscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.swikrutd.swidscanner.core.Filter
import io.github.swikrutd.swidscanner.data.Page
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
import androidx.compose.ui.unit.sp
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
    // Pages being exported from the sheet; null means the whole document.
    var exportPages by remember { mutableStateOf<List<Page>?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filterMenu by remember { mutableStateOf(false) }
    var deletingSelected by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    var saving by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val drive = rememberDriveAuthorizer()
    val saveStatus by Saver.status.collectAsState()

    // Drag-to-reorder state. dragOrder is the page order shown while dragging (and until the
    // saved order arrives); it is written to the store once, on drop. When a selected page is
    // dragged, dragGroup holds every selected page and, once the finger moves, the grid shows only
    // the dragged one while the others ride behind it.
    val gridState = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var dragGroup by remember { mutableStateOf<List<String>>(emptyList()) }
    var dragBase by remember { mutableStateOf<List<String>>(emptyList()) }
    var dragMoved by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    val shown = remember(doc?.pages, dragOrder, dragId) {
        val pages = doc?.pages ?: emptyList()
        val order = dragOrder
        // After a drop, an order that no longer matches the pages (one was added or removed) is ignored.
        if (order == null || (dragId == null && order.toSet() != pages.mapTo(HashSet()) { it.id })) pages else {
            val byId = pages.associateBy { it.id }
            order.mapNotNull { byId[it] }
        }
    }
    val selectedPages = doc?.pages?.filter { it.id in selected } ?: emptyList()

    fun exitSelection() { selecting = false; selected = emptySet(); filterMenu = false }
    BackHandler(enabled = selecting) { exitSelection() }

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

    fun save(pages: List<Page>? = null) {
        saving = true
        scope.launch {
            runCatching { Saver.savePdf(ctx, doc, auto = false, pages = pages) { drive.token() } }
                .onFailure { Saver.setStatus(docId, "Save failed: ${it.message}") }
            saving = false
        }
    }
    // With no save location on yet, Save asks for a folder first.
    var pendingSave by remember { mutableStateOf<List<Page>?>(null) }
    val pickFolder = rememberFolderPicker { save(pendingSave) }
    fun saveOrPick(pages: List<Page>?) {
        if (Saver.hasDestination) save(pages) else { pendingSave = pages; pickFolder.launch(null) }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                val allSelected = doc.pages.isNotEmpty() && selectedPages.size == doc.pages.size
                TopAppBar(
                    title = { Text("${selectedPages.size} selected", maxLines = 1) },
                    navigationIcon = { IconButton(onClick = { exitSelection() }) { Icon(Icons.Outlined.Close, "Exit selection") } },
                    actions = {
                        TextButton(onClick = { selected = if (allSelected) emptySet() else doc.pages.mapTo(HashSet()) { it.id } }) {
                            Text(if (allSelected) "Clear" else "Select all")
                        }
                    },
                )
            } else TopAppBar(
                title = {
                    Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { renaming = true })
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { selecting = true }, enabled = doc.pages.isNotEmpty()) {
                        Icon(Icons.Outlined.CheckCircle, "Select pages")
                    }
                    IconButton(
                        onClick = { saveOrPick(null) },
                        enabled = doc.pages.isNotEmpty() && !saving,
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Save, "Save as PDF")
                    }
                    IconButton(onClick = { exportPages = null; exporting = true }, enabled = doc.pages.isNotEmpty()) {
                        Icon(Icons.Outlined.Share, "Share or export")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Extract text") }, leadingIcon = { Icon(Icons.Outlined.TextSnippet, null) },
                                enabled = doc.pages.isNotEmpty(),
                                onClick = { menu = false; nav.navigate(Routes.ocr(docId)) })
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Delete document") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (selecting) {
                val none = selectedPages.isEmpty()
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BarAction(Icons.Outlined.Share, "Share", !none) { exportPages = selectedPages; exporting = true }
                        BarAction(Icons.Outlined.Save, "Save", !none && !saving) { saveOrPick(selectedPages) }
                        BarAction(Icons.Outlined.Delete, "Delete", !none) { deletingSelected = true }
                        BarAction(Icons.Outlined.TextSnippet, "OCR", !none) { nav.navigate(Routes.ocr(docId, selectedPages.map { it.id })) }
                        Box {
                            BarAction(Icons.Outlined.Tune, "Filter", !none) { filterMenu = true }
                            DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                                Filter.entries.forEach { f ->
                                    DropdownMenuItem(text = { Text(f.label) }, onClick = {
                                        filterMenu = false
                                        val ids = selectedPages.map { it.id }
                                        scope.launch { DocStore.setFilter(docId, ids, f) }
                                    })
                                }
                            }
                        }
                        BarAction(Icons.Outlined.FileCopy, "Extract", !none) {
                            val ids = selectedPages.map { it.id }
                            scope.launch {
                                val made = runCatching { DocStore.extractPages(docId, ids) }
                                val nd = made.getOrNull()
                                if (nd == null) {
                                    snack.showSnackbar("Could not extract: ${made.exceptionOrNull()?.message ?: "no pages"}")
                                } else {
                                    exitSelection()
                                    val r = snack.showSnackbar("Copied ${nd.pages.size} to \"${nd.name}\"", "Open")
                                    if (r == SnackbarResult.ActionPerformed) nav.navigate(Routes.doc(nd.id))
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selecting) ExtendedFloatingActionButton(
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
                                val id = hit.key as? String ?: return@detectDragGesturesAfterLongPress
                                val base = DocStore.get(docId)?.pages?.map { it.id } ?: return@detectDragGesturesAfterLongPress
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                // A selected page takes the whole selection with it; any other page goes alone.
                                dragGroup = if (selecting && id in selected) base.filter { it in selected } else listOf(id)
                                dragBase = base
                                dragOrder = base
                                dragMoved = false
                                dragStart = off
                                grab = off - Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
                                finger = off
                                dragId = id
                            },
                            onDrag = { change, _ ->
                                val id = dragId
                                if (id != null) {
                                    change.consume()
                                    finger = change.position
                                    if (!dragMoved && (finger - dragStart).getDistance() > viewConfiguration.touchSlop) {
                                        dragMoved = true
                                        // The rest of the selection leaves the grid and rides behind the dragged page.
                                        if (dragGroup.size > 1) dragOrder = dragBase.filter { it == id || it !in dragGroup }
                                    }
                                    retarget()
                                }
                            },
                            onDragEnd = {
                                val id = dragId
                                val order = dragOrder
                                val group = dragGroup
                                dragId = null
                                if (id != null && order != null) {
                                    if (!dragMoved) {
                                        // A long press released without moving selects the page.
                                        dragOrder = null
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selecting = true
                                        selected = selected + id
                                    } else {
                                        val moving = dragBase.filter { it in group }
                                        val rest = dragBase.filter { it !in group }
                                        val at = order.indexOf(id).coerceIn(0, rest.size)
                                        dragOrder = rest.take(at) + moving + rest.drop(at)
                                        scope.launch { DocStore.movePages(docId, moving, at) }
                                    }
                                }
                                dragMoved = false
                                dragGroup = emptyList()
                            },
                            onDragCancel = { dragId = null; dragOrder = null; dragMoved = false; dragGroup = emptyList() },
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
                                .clickable {
                                    if (dragId == null) {
                                        if (selecting) selected = if (p.id in selected) selected - p.id else selected + p.id
                                        else nav.navigate(Routes.page(docId, p.id))
                                    }
                                },
                        ) {
                            val stacked = lifted && dragMoved && dragGroup.size > 1
                            if (stacked) {
                                // Up to two other selected pages, tilted behind the dragged one.
                                val d = density.density
                                dragGroup.filter { it != p.id }.take(2).forEachIndexed { k, oid ->
                                    val op = doc.pages.firstOrNull { it.id == oid } ?: return@forEachIndexed
                                    AsyncImage(
                                        model = DocStore.thumbFile(docId, op), contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                            .graphicsLayer {
                                                rotationZ = if (k == 0) -7f else 6f
                                                translationX = (if (k == 0) -6f else 8f) * d
                                                translationY = 5f * (k + 1) * d
                                            }
                                            .shadow(4.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    )
                                }
                            }
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
                            if (selecting) {
                                val sel = p.id in selected
                                if (sel) Box(Modifier.matchParentSize().clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.3f)))
                                Icon(
                                    if (sel) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    if (sel) "Selected" else "Not selected",
                                    tint = if (sel) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(26.dp)
                                        .background(if (sel) Color.White else Color.Black.copy(alpha = 0.25f), CircleShape),
                                )
                            }
                            if (stacked) {
                                Text(
                                    "${dragGroup.size}", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
                                )
                            }
                            if (p.id in busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }

    if (exporting) ExportSheet(doc, pages = exportPages, onDismiss = { exporting = false })
    if (deletingSelected) {
        val ids = selectedPages.map { it.id }
        ConfirmDialog("Delete ${ids.size} page(s)?", "They will be removed from this phone.", "Delete",
            onDismiss = { deletingSelected = false }) {
            deletingSelected = false
            exitSelection()
            scope.launch { DocStore.deletePages(docId, ids) }
        }
    }
    if (renaming) RenameDialog(doc.name, onDismiss = { renaming = false }) { n -> scope.launch { DocStore.rename(docId, n) }; renaming = false }
    if (deleting) {
        ConfirmDialog("Delete document?", "All ${doc.pages.size} page(s) will be removed from this phone.", "Delete",
            onDismiss = { deleting = false }) {
            deleting = false
            scope.launch { DocStore.deleteDoc(docId) }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
