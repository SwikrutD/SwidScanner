package io.github.swikrutd.swidscanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.swikrutd.swidscanner.data.AssetKind
import io.github.swikrutd.swidscanner.data.AssetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Draw
// ---------------------------------------------------------------------------------------------

/** One sampled point. [w] scales the pen width: pressure for a stylus, 1 for a finger. */
private class InkPoint(val x: Float, val y: Float, val w: Float)

private class Ink(val pts: List<InkPoint>, val color: Color, val width: Float)

private fun stylusWidth(pressure: Float) = 0.3f + 1.1f * pressure.coerceIn(0f, 1f)

/** Draws one stroke as quadratic curves from midpoint to midpoint, with the sampled points as controls. */
private fun DrawScope.drawInk(ink: Ink) {
    val p = ink.pts
    if (p.isEmpty()) return
    if (p.size == 1) {
        drawCircle(ink.color, ink.width * p[0].w / 2, Offset(p[0].x, p[0].y))
        return
    }
    fun mid(a: InkPoint, b: InkPoint) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    val variable = p.any { it.w != p[0].w }
    if (!variable) {
        val path = Path().apply {
            moveTo(p[0].x, p[0].y)
            for (i in 1 until p.size - 1) {
                val m = mid(p[i], p[i + 1])
                quadraticTo(p[i].x, p[i].y, m.x, m.y)
            }
            lineTo(p.last().x, p.last().y)
        }
        drawPath(path, ink.color, style = Stroke(ink.width * p[0].w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        return
    }
    var from = Offset(p[0].x, p[0].y)
    for (i in 1 until p.size) {
        val to = if (i == p.lastIndex) Offset(p[i].x, p[i].y) else mid(p[i], p[i + 1])
        val path = Path().apply { moveTo(from.x, from.y); quadraticTo(p[i].x, p[i].y, to.x, to.y) }
        drawPath(path, ink.color, style = Stroke(ink.width * p[i].w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        from = to
    }
}

/** Renders the strokes to a transparent bitmap cropped to the ink. Null if there is nothing to render. */
private fun renderInk(strokes: List<Ink>, density: Density): Bitmap? {
    val pts = strokes.flatMap { s -> s.pts.map { it to s.width } }
    if (pts.isEmpty()) return null
    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
    for ((p, w) in pts) {
        val pad = w * p.w / 2 + 2f
        x0 = min(x0, p.x - pad); y0 = min(y0, p.y - pad); x1 = max(x1, p.x + pad); y1 = max(y1, p.y + pad)
    }
    val bw = x1 - x0; val bh = y1 - y0
    val k = min(2f, AssetStore.MAX_SIDE / max(bw, bh))
    val img = ImageBitmap(ceil(bw * k).toInt().coerceAtLeast(1), ceil(bh * k).toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(img.width.toFloat(), img.height.toFloat())) {
        scale(k, k, pivot = Offset.Zero) { translate(-x0, -y0) { strokes.forEach { drawInk(it) } } }
    }
    return img.asAndroidBitmap()
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SignatureDrawScreen(nav: NavController) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<Ink>() }
    val current = remember { mutableStateListOf<InkPoint>() }
    var color by remember { mutableStateOf(InkBlack) }
    var widthDp by remember { mutableFloatStateOf(4f) }
    var saving by remember { mutableStateOf(false) }
    val colorNow by rememberUpdatedState(color)
    val widthPx by rememberUpdatedState(with(density) { widthDp.dp.toPx() })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Draw signature") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            ) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val stylus = down.type == PointerType.Stylus
                            var w = 1f
                            fun add(pos: Offset, pressure: Float) {
                                if (stylus) w = if (current.isEmpty()) stylusWidth(pressure) else w * 0.6f + stylusWidth(pressure) * 0.4f
                                val last = current.lastOrNull()
                                if (last != null && abs(last.x - pos.x) + abs(last.y - pos.y) < 0.8f) return
                                current.add(InkPoint(pos.x, pos.y, w))
                            }
                            current.clear()
                            val width = widthPx; val c = colorNow
                            add(down.position, down.pressure)
                            down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                ch.historical.forEach { add(it.position, ch.pressure) }
                                add(ch.position, ch.pressure)
                                ch.consume()
                            }
                            if (current.isNotEmpty()) strokes.add(Ink(current.toList(), c, width))
                            current.clear()
                        }
                    },
                ) {
                    // Baseline guide.
                    drawLine(Color(0xFFE0E0E0), Offset(24.dp.toPx(), size.height * 0.72f), Offset(size.width - 24.dp.toPx(), size.height * 0.72f), 1.dp.toPx())
                    strokes.forEach { drawInk(it) }
                    if (current.isNotEmpty()) drawInk(Ink(current.toList(), color, widthDp.dp.toPx()))
                }
                if (strokes.isEmpty() && current.isEmpty()) {
                    Text("Sign here", Modifier.align(Alignment.Center), color = Color(0xFFB0B0B0), style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InkColorRow(color) { color = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Width", style = MaterialTheme.typography.labelLarge)
                    Slider(widthDp, { widthDp = it }, Modifier.weight(1f).padding(horizontal = 12.dp), valueRange = 1.5f..14f)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, null); Text(" Undo")
                    }
                    OutlinedButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = strokes.isNotEmpty() && !saving, onClick = {
                        saving = true
                        val list = strokes.toList()
                        scope.launch {
                            val bmp = withContext(Dispatchers.Default) { renderInk(list, density) }
                            if (bmp != null) AssetStore.add(AssetKind.SIGNATURE, bmp, "")
                            nav.popBackStack()
                        }
                    }) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

