package io.github.swikrutd.swidscanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val L = 1
private const val T = 2
private const val R = 4
private const val B = 8
private const val MOVE = 16
private const val MIN_SIDE = 0.04f

/**
 * An axis-aligned crop box over [image]. [box] is in 0..1 image space. Drag an edge or a corner to
 * resize it and drag inside to move it.
 */
@Composable
fun RectCrop(image: ImageBitmap, box: Rect, onBox: (Rect) -> Unit, modifier: Modifier = Modifier) {
    val boxNow by rememberUpdatedState(box)
    val onBoxNow by rememberUpdatedState(onBox)
    var grab by remember { mutableIntStateOf(0) }
    val line = MaterialTheme.colorScheme.primary
    val handle = MaterialTheme.colorScheme.secondary

    Canvas(
        modifier.fillMaxSize().pointerInput(image) {
            fun fit(): Rect {
                val s = min(size.width / image.width.toFloat(), size.height / image.height.toFloat())
                val w = image.width * s; val h = image.height * s
                return Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
            }
            val slop = 32.dp.toPx()
            detectDragGestures(
                onDragStart = { pos ->
                    val f = fit(); val b = boxNow
                    val l = f.left + b.left * f.width; val r = f.left + b.right * f.width
                    val t = f.top + b.top * f.height; val bt = f.top + b.bottom * f.height
                    val inX = pos.x in (l - slop)..(r + slop)
                    val inY = pos.y in (t - slop)..(bt + slop)
                    var g = 0
                    // Edges only count when the touch is near their span, so a corner needs both.
                    if (inY && abs(pos.x - l) < slop) g = g or L
                    if (inY && abs(pos.x - r) < slop) g = g or R
                    if (inX && abs(pos.y - t) < slop) g = g or T
                    if (inX && abs(pos.y - bt) < slop) g = g or B
                    // A box narrower than two slops: the nearer edge wins.
                    if (g and L != 0 && g and R != 0) g = g and (if (abs(pos.x - l) <= abs(pos.x - r)) R.inv() else L.inv())
                    if (g and T != 0 && g and B != 0) g = g and (if (abs(pos.y - t) <= abs(pos.y - bt)) B.inv() else T.inv())
                    if (g == 0 && pos.x in l..r && pos.y in t..bt) g = MOVE
                    grab = g
                },
                onDragEnd = { grab = 0 },
                onDragCancel = { grab = 0 },
                onDrag = { change, drag ->
                    if (grab == 0) return@detectDragGestures
                    change.consume()
                    val f = fit(); val b = boxNow
                    val dx = drag.x / f.width; val dy = drag.y / f.height
                    var l = b.left; var t = b.top; var r = b.right; var bt = b.bottom
                    if (grab == MOVE) {
                        val w = r - l; val h = bt - t
                        l = (l + dx).coerceIn(0f, 1f - w); t = (t + dy).coerceIn(0f, 1f - h)
                        r = l + w; bt = t + h
                    } else {
                        if (grab and L != 0) l = (l + dx).coerceIn(0f, r - MIN_SIDE)
                        if (grab and R != 0) r = (r + dx).coerceIn(l + MIN_SIDE, 1f)
                        if (grab and T != 0) t = (t + dy).coerceIn(0f, bt - MIN_SIDE)
                        if (grab and B != 0) bt = (bt + dy).coerceIn(t + MIN_SIDE, 1f)
                    }
                    onBoxNow(Rect(l, t, r, bt))
                },
            )
        },
    ) {
        val s = min(size.width / image.width, size.height / image.height)
        val w = image.width * s; val h = image.height * s
        val left = (size.width - w) / 2; val top = (size.height - h) / 2
        drawImage(image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
        val r = Rect(left + box.left * w, top + box.top * h, left + box.right * w, top + box.bottom * h)
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(left, top, left + w, top + h))
            addRect(r)
        }
        drawPath(outside, Color.Black.copy(alpha = 0.5f))
        drawRect(line, r.topLeft, r.size, style = Stroke(2.5.dp.toPx()))
        val corners = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)
        val mids = listOf(r.topCenter, r.centerRight, r.bottomCenter, r.centerLeft)
        mids.forEach { drawCircle(Color.White, 7.dp.toPx(), it); drawCircle(handle, 5.dp.toPx(), it) }
        corners.forEach { drawCircle(Color.White, 12.dp.toPx(), it); drawCircle(handle, 9.dp.toPx(), it) }
    }
}
