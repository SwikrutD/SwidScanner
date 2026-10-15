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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(doc.pages, key = { _, p -> p.id }) { i, p ->
                        Box(Modifier.padding(8.dp).fillMaxWidth().aspectRatio(0.75f).clickable { nav.navigate(Routes.page(docId, p.id)) }) {
                            AsyncImage(
                                model = DocStore.thumbFile(docId, p), contentDescription = "Page ${i + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().shadow(2.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
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
