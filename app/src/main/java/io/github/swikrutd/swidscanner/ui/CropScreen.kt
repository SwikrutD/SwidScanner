package io.github.swikrutd.swidscanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.swikrutd.swidscanner.data.Corner
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private val FULL = listOf(Corner(0f, 0f), Corner(1f, 0f), Corner(1f, 1f), Corner(0f, 1f))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(nav: NavController, docId: String, pageId: String, thenDoc: Boolean) {
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var source by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val corners = remember { mutableStateListOf<Corner>() }
    var saving by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(-1) }

    LaunchedEffect(pageId) {
        val page = DocStore.get(docId)?.pages?.firstOrNull { it.id == pageId } ?: run { nav.popBackStack(); return@LaunchedEffect }
        val bmp = withContext(Dispatchers.IO) { Images.decodeFile(DocStore.origFile(docId, pageId), 1600) }
        source = bmp
        image = bmp.asImageBitmap()
        corners.clear(); corners.addAll(page.corners ?: FULL)
    }

    fun done() {
        saving = true
        val c = corners.toList()
        val isFull = c == FULL
        scope.launch {
            DocStore.updatePage(docId, pageId) { it.copy(corners = if (isFull) null else c) }
            if (thenDoc) {
                nav.popBackStack()
                nav.navigate(Routes.doc(docId))
            } else nav.popBackStack()
        }
    }

    BackHandler(enabled = !saving) { done() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adjust corners") },
                navigationIcon = { IconButton(onClick = { done() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                val img = image
                if (img == null || corners.size != 4) {
                    CircularProgressIndicator()
                } else {
                    val handle = MaterialTheme.colorScheme.secondary
                    val line = MaterialTheme.colorScheme.primary
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(img) {
                            fun rect(): Rect {
                                val s = min(size.width / img.width.toFloat(), size.height / img.height.toFloat())
                                val w = img.width * s; val h = img.height * s
                                return Rect(Offset((size.width - w) / 2, (size.height - h) / 2), androidx.compose.ui.geometry.Size(w, h))
                            }
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val r = rect()
                                    val i = corners.indices.minByOrNull {
                                        (Offset(r.left + corners[it].x * r.width, r.top + corners[it].y * r.height) - pos).getDistance()
                                    }!!
                                    val d = (Offset(r.left + corners[i].x * r.width, r.top + corners[i].y * r.height) - pos).getDistance()
                                    active = if (d < 56.dp.toPx()) i else -1
                                },
                                onDragEnd = { active = -1 },
                                onDragCancel = { active = -1 },
                                onDrag = { change, drag ->
                                    if (active >= 0) {
                                        change.consume()
                                        val r = rect()
                                        val c = corners[active]
                                        corners[active] = Corner(
                                            (c.x + drag.x / r.width).coerceIn(0f, 1f),
                                            (c.y + drag.y / r.height).coerceIn(0f, 1f),
                                        )
                                    }
                                },
                            )
                        },
                    ) {
                        val s = min(size.width / img.width, size.height / img.height)
                        val w = img.width * s; val h = img.height * s
                        val left = (size.width - w) / 2; val top = (size.height - h) / 2
                        drawImage(img, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
                        val pts = corners.map { Offset(left + it.x * w, top + it.y * h) }
                        val quad = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until 4) lineTo(pts[i].x, pts[i].y); close() }
                        // Dim everything outside the crop.
                        val outside = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(left, top, left + w, top + h))
                            addPath(quad)
                        }
                        drawPath(outside, Color.Black.copy(alpha = 0.45f))
                        drawPath(quad, line, style = Stroke(2.5.dp.toPx()))
                        pts.forEachIndexed { i, p ->
                            drawCircle(Color.White, (if (i == active) 15 else 12).dp.toPx(), p)
                            drawCircle(handle, (if (i == active) 11 else 9).dp.toPx(), p)
                        }
                        // Magnifier while dragging, placed in the corner away from the finger.
                        if (active >= 0) {
                            val p = pts[active]
                            val radius = 56.dp.toPx()
                            val zoom = 2.5f
                            val center = Offset(
                                if (p.x < size.width / 2) size.width - radius - 8.dp.toPx() else radius + 8.dp.toPx(),
                                radius + 8.dp.toPx(),
                            )
                            val srcSide = (2 * radius / zoom / s)
                            val sx = (corners[active].x * img.width - srcSide / 2).roundToInt()
                            val sy = (corners[active].y * img.height - srcSide / 2).roundToInt()
                            val circle = Path().apply { addOval(Rect(center, radius)) }
                            clipPath(circle) {
                                drawRect(Color.Black, Offset(center.x - radius, center.y - radius), androidx.compose.ui.geometry.Size(2 * radius, 2 * radius))
                                val cx = sx.coerceIn(0, img.width - 1); val cy = sy.coerceIn(0, img.height - 1)
                                val sw = srcSide.roundToInt().coerceAtMost(img.width - cx).coerceAtLeast(1)
                                val sh = srcSide.roundToInt().coerceAtMost(img.height - cy).coerceAtLeast(1)
                                val k = 2 * radius / srcSide
                                drawImage(
                                    img, srcOffset = IntOffset(cx, cy), srcSize = IntSize(sw, sh),
                                    dstOffset = IntOffset(
                                        (center.x - radius + (cx - sx) * k).roundToInt(),
                                        (center.y - radius + (cy - sy) * k).roundToInt(),
                                    ),
                                    dstSize = IntSize((sw * k).roundToInt(), (sh * k).roundToInt()),
                                )
                            }
                            drawCircle(Color.White, radius, center, style = Stroke(2.dp.toPx()))
                            drawLine(handle, center - Offset(10.dp.toPx(), 0f), center + Offset(10.dp.toPx(), 0f), 2.dp.toPx())
                            drawLine(handle, center - Offset(0f, 10.dp.toPx()), center + Offset(0f, 10.dp.toPx()), 2.dp.toPx())
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = {
                    val b = source ?: return@OutlinedButton
                    scope.launch {
                        val c = withContext(Dispatchers.Default) { Images.detectCorners(b) }
                        corners.clear(); corners.addAll(c ?: FULL)
                    }
                }) { Icon(Icons.Outlined.AutoFixHigh, null); Text(" Auto") }
                OutlinedButton(onClick = { corners.clear(); corners.addAll(FULL) }) { Icon(Icons.Outlined.CropFree, null); Text(" Full") }
                Box(Modifier.weight(1f))
                Button(onClick = { done() }, enabled = !saving && image != null) {
                    if (saving) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                    Text("Done")
                }
            }
        }
    }
}
