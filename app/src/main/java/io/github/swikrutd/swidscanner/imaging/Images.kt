package io.github.swikrutd.swidscanner.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import io.github.swikrutd.swidscanner.core.DocProcessing
import io.github.swikrutd.swidscanner.data.Corner
import io.github.swikrutd.swidscanner.data.Page
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object Images {

    /** Longest side kept for stored captures. Plenty for A4 at ~300 DPI. */
    const val CAPTURE_MAX = 3200

    fun decodeFile(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: error("Could not read ${file.name}")
        return scaleDown(bmp, maxSide)
    }

    /** Decodes a picked image, honouring its EXIF orientation. */
    fun decodeUri(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Could not read the selected image")
        val orientation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val upright = if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { bmp.recycle() }
        return scaleDown(upright, maxSide)
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    /** Returns [b] or a scaled copy (recycling [b]) so its longest side is at most [maxSide]. */
    fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxSide) return b
        val f = maxSide.toFloat() / m
        val out = Bitmap.createScaledBitmap(b, (b.width * f).roundToInt(), (b.height * f).roundToInt(), true)
        if (out !== b) b.recycle()
        return out
    }

    fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return b
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it !== b) b.recycle() }
    }

    fun saveJpeg(b: Bitmap, file: File, quality: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    fun toMat(b: Bitmap): Mat {
        val src = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
        val m = Mat()
        Utils.bitmapToMat(src, m) // RGBA
        if (src !== b) src.recycle()
        return m
    }

    fun toBitmap(m: Mat): Bitmap {
        val b = Bitmap.createBitmap(m.cols(), m.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(m, b)
        return b
    }

    /** Finds the page outline in [b], normalised, or null. */
    fun detectCorners(b: Bitmap): List<Corner>? {
        val small = Bitmap.createScaledBitmap(b, (b.width * scaleFor(b)).roundToInt().coerceAtLeast(1),
            (b.height * scaleFor(b)).roundToInt().coerceAtLeast(1), true)
        val m = toMat(small)
        val q = DocProcessing.detect(m)
        val w = m.cols().toFloat(); val h = m.rows().toFloat()
        m.release()
        if (small !== b) small.recycle()
        return q?.map { Corner((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) }
    }

    private fun scaleFor(b: Bitmap) = minOf(1f, 1000f / max(b.width, b.height))

    /** Crop, rotate and filter a capture into the final page image. */
    fun renderPage(orig: Bitmap, page: Page): Bitmap {
        val src = toMat(orig)
        val warped = page.corners?.let { c ->
            val pts = DocProcessing.order(c.map { Point(it.x.toDouble() * src.cols(), it.y.toDouble() * src.rows()) })
            DocProcessing.warp(src, pts)
        } ?: src.clone()
        src.release()
        val rotated = DocProcessing.rotate(warped, page.rotation)
        warped.release()
        val filtered = DocProcessing.applyFilter(rotated, page.filter)
        rotated.release()
        return toBitmap(filtered).also { filtered.release() }
    }
}
