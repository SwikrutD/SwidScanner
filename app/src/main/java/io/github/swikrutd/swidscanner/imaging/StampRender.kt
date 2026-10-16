package io.github.swikrutd.swidscanner.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws a text stamp: a bordered rounded rectangle around bold text, tilted a few degrees, on a transparent background. */
object StampRender {
    const val MAX_TILT = 10f

    private const val MAIN_SIZE = 120f
    private const val DATE_SIZE = 62f

    /**
     * [lines] are the bold lines (blank ones are skipped) and [dateLine] is an optional smaller line
     * below them. [border] is the border width in pixels at the stamp's drawing scale (about 2 to 20).
     * [tilt] is in degrees, clockwise when positive. Returns null when there is no text to draw.
     */
    fun render(lines: List<String>, dateLine: String?, color: Int, border: Float, tilt: Float): Bitmap? {
        val main = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val date = dateLine?.trim()?.takeIf { it.isNotEmpty() }
        if (main.isEmpty() && date == null) return null

        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = MAIN_SIZE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.06f
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = DATE_SIZE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.04f
        }
        val rows = main.map { it to big } + listOfNotNull(date?.let { it to small })

        val gap = MAIN_SIZE * 0.12f
        val textW = rows.maxOf { (t, p) -> p.measureText(t) }
        val textH = rows.sumOf { (_, p) -> (p.fontMetrics.descent - p.fontMetrics.ascent).toDouble() }.toFloat() + gap * (rows.size - 1)
        val w = textW + MAIN_SIZE * 0.9f + border
        val h = textH + MAIN_SIZE * 0.6f + border
        val radius = min(MAIN_SIZE * 0.5f, min(w, h) * 0.22f)

        val rad = Math.toRadians(abs(tilt).toDouble())
        val bw = (w * cos(rad) + h * sin(rad)).toFloat() + 4f
        val bh = (w * sin(rad) + h * cos(rad)).toFloat() + 4f
        val bmp = Bitmap.createBitmap(ceil(bw).toInt().coerceAtLeast(1), ceil(bh).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(bmp.width / 2f, bmp.height / 2f)
        c.rotate(tilt)
        c.translate(-w / 2f, -h / 2f)

        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = max(1f, border)
        }
        c.drawRoundRect(RectF(border / 2, border / 2, w - border / 2, h - border / 2), radius, radius, frame)

        var y = (h - textH) / 2f
        for ((text, paint) in rows) {
            paint.textAlign = Paint.Align.CENTER
            c.drawText(text, w / 2f, y - paint.fontMetrics.ascent, paint)
            y += paint.fontMetrics.descent - paint.fontMetrics.ascent + gap
        }
        return bmp
    }
}
