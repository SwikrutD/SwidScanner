package io.github.swikrutd.swidscanner.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.security.MessageDigest

/** The "SwidScanner" mark stamped on exported pages, and the code that is needed to turn it off. */
object Watermark {
    private const val TEXT = "SwidScanner"

    // SHA-256 of the unlock code, so the code itself is not a string in the app.
    private const val CODE_HASH = "5e943f0b639afcd3b10b0ea637402617fb1e40407c6a58dac8c0848cf834c99e"

    fun codeOk(input: String): Boolean =
        MessageDigest.getInstance("SHA-256").digest(input.trim().toByteArray())
            .joinToString("") { "%02x".format(it) } == CODE_HASH

    /** A copy of [src] with the mark in the bottom right corner. [src] is left as it is. */
    fun apply(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val size = (minOf(out.width, out.height) * 0.028f).coerceAtLeast(14f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            color = 0x99_606060.toInt()
            setShadowLayer(size * 0.08f, 0f, 0f, 0x88_FFFFFF.toInt())
        }
        val margin = size * 0.9f
        c.drawText(TEXT, out.width - margin, out.height - margin, paint)
        return out
    }
}
