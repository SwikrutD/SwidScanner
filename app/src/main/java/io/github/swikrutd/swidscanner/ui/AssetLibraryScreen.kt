package io.github.swikrutd.swidscanner.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.swikrutd.swidscanner.core.DocProcessing
import io.github.swikrutd.swidscanner.data.Asset
import io.github.swikrutd.swidscanner.data.AssetFile
import io.github.swikrutd.swidscanner.data.AssetKind
import io.github.swikrutd.swidscanner.data.AssetStore
import io.github.swikrutd.swidscanner.data.hasTransparency
import io.github.swikrutd.swidscanner.export.Delivery
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** The photo an asset is being made from, kept in the cache so it survives a process restart. */
object SigDraft {
    fun file(ctx: android.content.Context) = File(ctx.cacheDir, "sig-source.jpg")

    fun save(ctx: android.content.Context, bmp: Bitmap) = Images.saveJpeg(bmp, file(ctx), 92)
}

/** The fixed window of the asset camera, in 0..1 of the upright 3:4 photo. The crop box starts here. */
object SigWindow {
    private const val WIDTH = 0.9f
    private const val ASPECT = 4f / 3f // window width over height
    private const val PHOTO_ASPECT = 3f / 4f // photo width over height

    val rect: Rect = run {
        val h = WIDTH * PHOTO_ASPECT / ASPECT
        Rect((1f - WIDTH) / 2, (1f - h) / 2, (1f + WIDTH) / 2, (1f + h) / 2)
    }
}

val AssetKind.title get() = noun.replaceFirstChar { it.uppercase() } + "s"

// ---------------------------------------------------------------------------------------------
// Shared pieces: the source picker and the transparent preview
// ---------------------------------------------------------------------------------------------

/** The ways to add an asset. Which of them a kind offers is in [AssetKind.sources]. */
enum class AssetSource(val label: String, val icon: ImageVector) {
    DRAW("Draw", Icons.Outlined.Draw),
    TEXT("Text stamp", Icons.Outlined.TextFields),
    IMAGE("From image", Icons.Outlined.Image),
    SCAN("From scan", Icons.Outlined.PhotoCamera),
    FILE("From file", Icons.Outlined.FileOpen),
}

val AssetKind.sources: List<AssetSource>
    get() = when (this) {
        AssetKind.SIGNATURE -> listOf(AssetSource.DRAW, AssetSource.IMAGE, AssetSource.SCAN, AssetSource.FILE)
        AssetKind.STAMP -> listOf(AssetSource.TEXT, AssetSource.IMAGE, AssetSource.SCAN, AssetSource.FILE)
    }

/** Bottom sheet that lists how to add an asset of [kind]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetPicker(kind: AssetKind, onDismiss: () -> Unit, onPick: (AssetSource) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            kind.sources.forEach { src ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(src) }.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(src.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Text(src.label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * An asset on a checkerboard so the transparent parts show. Give it a [bitmap] or a [file]. With
 * neither it shows a spinner, or [emptyText] when that is set. [busy] adds a small spinner in the
 * corner while the picture is being redone.
 */
@Composable
fun TransparentPreview(
    modifier: Modifier = Modifier,
    bitmap: Bitmap? = null,
    file: File? = null,
    emptyText: String? = null,
    busy: Boolean = false,
    inset: androidx.compose.ui.unit.Dp = 16.dp,
    cell: Float = 18f,
) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).checkerboard(cell), contentAlignment = Alignment.Center) {
        when {
            emptyText != null -> Text(emptyText, Modifier.padding(24.dp), color = Color.DarkGray, textAlign = TextAlign.Center)
            bitmap != null -> Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().padding(inset), contentScale = ContentScale.Fit)
            file != null -> AsyncImage(file, null, Modifier.fillMaxSize().padding(inset), contentScale = ContentScale.Fit)
            else -> CircularProgressIndicator()
        }
        if (busy && (bitmap != null || file != null)) {
            CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetLibraryScreen(nav: NavController, kind: AssetKind) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by AssetStore.assets.collectAsState()
    val items = all.filter { it.kind == kind }
    val snack = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Asset?>(null) }
    var deleting by remember { mutableStateOf<Asset?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val bmp = Images.decodeUri(ctx, uri, 2400)
                    SigDraft.save(ctx, bmp)
                    bmp.recycle()
                }
                nav.navigate(Routes.assetImport(kind, window = false))
            } catch (e: Exception) {
                snack.showSnackbar("Could not read that image")
            } finally { busy = false }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                if (AssetFile.looksLikeAssetFile(ctx, uri)) {
                    AssetFile.import(ctx, uri, kind)
                    snack.showSnackbar("${kind.noun.replaceFirstChar { it.uppercase() }} added")
                } else {
                    val bmp = withContext(Dispatchers.IO) { Images.decodeUri(ctx, uri, 2400) }
                    if (hasTransparency(bmp)) {
                        // A PNG with a cut-out is used as it is.
                        AssetStore.add(kind, bmp, "")
                        bmp.recycle()
                        snack.showSnackbar("${kind.noun.replaceFirstChar { it.uppercase() }} added")
                    } else {
                        withContext(Dispatchers.IO) { SigDraft.save(ctx, bmp) }
                        bmp.recycle()
                        nav.navigate(Routes.assetImport(kind, window = false))
                    }
                }
            } catch (e: Exception) {
                snack.showSnackbar(e.message ?: "Could not read that file")
            } finally { busy = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind.title) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { sheet = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add") })
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (items.isEmpty()) {
                Text(
                    when (kind) {
                        AssetKind.SIGNATURE -> "No signatures yet. Tap Add to draw one or use a photo of your signature."
                        AssetKind.STAMP -> "No stamps yet. Tap Add to make a text stamp or use a photo of a stamp."
                    },
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                LazyVerticalGrid(
                    GridCells.Fixed(2), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { a ->
                        AssetCard(
                            a,
                            onRename = { renaming = a },
                            onShare = {
                                scope.launch {
                                    try { Delivery.share(ctx, listOf(AssetFile.export(ctx, a)), "application/octet-stream") }
                                    catch (e: Exception) { snack.showSnackbar(e.message ?: "Could not share") }
                                }
                            },
                            onDelete = { deleting = a },
                        )
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    if (sheet) {
        AssetPicker(kind, onDismiss = { sheet = false }) { src ->
            sheet = false
            when (src) {
                AssetSource.DRAW -> nav.navigate(Routes.SIGNATURE_DRAW)
                AssetSource.TEXT -> nav.navigate(Routes.STAMP_TEXT)
                AssetSource.IMAGE -> pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                AssetSource.SCAN -> nav.navigate(Routes.assetCamera(kind))
                AssetSource.FILE -> pickFile.launch(arrayOf("*/*"))
            }
        }
    }
    renaming?.let { a ->
        RenameDialog(a.name, onDismiss = { renaming = null }) { n -> scope.launch { AssetStore.rename(a.id, n) }; renaming = null }
    }
    deleting?.let { a ->
        ConfirmDialog(
            "Delete ${kind.noun}", "Delete \"${a.name}\"? Pages that already use it keep it.", "Delete",
            onDismiss = { deleting = null },
        ) { scope.launch { AssetStore.delete(a.id) }; deleting = null }
    }
}

@Composable
private fun AssetCard(a: Asset, onRename: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        TransparentPreview(Modifier.fillMaxWidth().aspectRatio(1.6f), file = AssetStore.file(a.id), inset = 10.dp, cell = 14f)
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                    DropdownMenuItem({ Text("Share file") }, { menu = false; onShare() }, leadingIcon = { Icon(Icons.Outlined.Share, null) })
                    DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Image, scan and file: rectangle crop, then ink cut-out
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetImportScreen(nav: NavController, kind: AssetKind, window: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stamp = kind == AssetKind.STAMP
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var box by remember { mutableStateOf(if (window) SigWindow.rect else Rect(0.05f, 0.05f, 0.95f, 0.95f)) }
    var cropped by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var ink by remember { mutableStateOf(if (stamp) StampRed else InkBlack) }
    // Stamps are often red or blue, so they start with their own colours.
    var keepColours by remember { mutableStateOf(stamp) }
    var working by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val finishing = cropped != null

    LaunchedEffect(Unit) {
        val bmp = withContext(Dispatchers.IO) { runCatching { Images.decodeFile(SigDraft.file(ctx), 2400) }.getOrNull() }
        if (bmp == null) { nav.popBackStack(); return@LaunchedEffect }
        source = bmp
        image = bmp.asImageBitmap()
    }

    // Cut the ink out again whenever the crop is confirmed or the colour choice changes.
    LaunchedEffect(cropped, ink, keepColours) {
        val c = cropped ?: return@LaunchedEffect
        working = true
        val out = withContext(Dispatchers.Default) {
            val mat = Images.toMat(c)
            val res = DocProcessing.inkToTransparent(mat, if (keepColours) null else ink.toArgb())
            mat.release()
            Images.toBitmap(res).also { res.release() }
        }
        result = out
        working = false
    }

    fun next() {
        val s = source ?: return
        val x = (box.left * s.width).roundToInt().coerceIn(0, s.width - 1)
        val y = (box.top * s.height).roundToInt().coerceIn(0, s.height - 1)
        val w = ((box.right - box.left) * s.width).roundToInt().coerceIn(1, s.width - x)
        val h = ((box.bottom - box.top) * s.height).roundToInt().coerceIn(1, s.height - y)
        result = null
        cropped = Bitmap.createBitmap(s, x, y, w, h)
    }

    BackHandler(enabled = finishing && !saving) { cropped = null; result = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (finishing) "Preview" else "Crop ${kind.noun}") },
                navigationIcon = {
                    IconButton(onClick = { if (finishing && !saving) { cropped = null; result = null } else nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val img = image
            if (!finishing) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    if (img == null) CircularProgressIndicator() else RectCrop(img, box, { box = it })
                }
                Text(
                    "Drag the edges to frame just the ${kind.noun}.",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { next() }, enabled = img != null) { Text("Next") }
                }
            } else {
                val r = result
                val empty = r != null && r.width <= 1 && r.height <= 1
                TransparentPreview(
                    Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                    bitmap = r.takeIf { !empty },
                    emptyText = if (empty) "No ink found. Go back and crop closer to the ${kind.noun}." else null,
                    busy = working,
                )
                Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (stamp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep original colours", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            Switch(keepColours, { keepColours = it })
                        }
                    }
                    if (!keepColours) {
                        Text("Ink colour", style = MaterialTheme.typography.labelLarge)
                        InkColorRow(ink, if (stamp) StampInks else SignatureInks) { ink = it }
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { cropped = null; result = null }, enabled = !saving) { Text("Back") }
                        Button(enabled = r != null && !empty && !saving, onClick = {
                            saving = true
                            scope.launch {
                                AssetStore.add(kind, r!!, "")
                                SigDraft.file(ctx).delete()
                                nav.popBackStack()
                            }
                        }) { Text("Save") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
