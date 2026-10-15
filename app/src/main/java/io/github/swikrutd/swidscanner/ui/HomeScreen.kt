package io.github.swikrutd.swidscanner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.swikrutd.swidscanner.data.Doc
import io.github.swikrutd.swidscanner.data.DocStore
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by DocStore.docs.collectAsState()
    var importing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Doc?>(null) }
    var deleting by remember { mutableStateOf<Doc?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val id = importImages(ctx, uris, null)
            importing = false
            if (id != null) nav.navigate(Routes.doc(id))
        }
    }

    val shown = if (query.isBlank()) docs else docs.filter { it.name.contains(query.trim(), ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search scans") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text("SwidScanner", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search")
                    }
                    IconButton(onClick = { nav.navigate(Routes.QR) }) { Icon(Icons.Outlined.QrCodeScanner, "Scan QR code") }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Icon(Icons.Outlined.PhotoLibrary, "Import from gallery") }
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.camera()) },
                    icon = { Icon(Icons.Outlined.CameraAlt, null) },
                    text = { Text("Scan") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (docs.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("No scans yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap Scan to photograph a document, or import pictures from your gallery.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 160.dp)) {
                    items(shown, key = { it.id }) { d ->
                        DocRow(d, onClick = { nav.navigate(Routes.doc(d.id)) }, onRename = { renaming = d }, onDelete = { deleting = d })
                    }
                }
            }
            if (importing) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    renaming?.let { d ->
        RenameDialog(d.name, onDismiss = { renaming = null }) { n -> scope.launch { DocStore.rename(d.id, n) }; renaming = null }
    }
    deleting?.let { d ->
        ConfirmDialog("Delete scan?", "\"${d.name}\" and its ${d.pages.size} page(s) will be removed from this phone.", "Delete",
            onDismiss = { deleting = null }) { scope.launch { DocStore.deleteDoc(d.id) }; deleting = null }
    }
}

@Composable
private fun DocRow(d: Doc, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val first = d.pages.firstOrNull()
        Box(
            Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (first != null) {
                AsyncImage(
                    model = DocStore.thumbFile(d.id, first), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${d.pages.size} page${if (d.pages.size == 1) "" else "s"}  ·  " +
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.updated)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                    onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = { menu = false; onDelete() })
            }
        }
    }
}
