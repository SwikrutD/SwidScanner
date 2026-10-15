package io.github.swikrutd.swidscanner.ui

import androidx.camera.core.ImageProxy
import io.github.swikrutd.swidscanner.core.DocProcessing
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug builds only. Saves live analysis frames to filesDir/frames/sNN so the desktop
 * harness (tools/detect-harness) can replay them. Per frame: fNNNN_gray.png (the exact
 * upright luminance the detector saw), fNNNN.jpg (upright colour) and fNNNN.json
 * (timestamp, rotation, crop rect and the detected quad in upright pixels).
 */
class FrameDump(private val root: File) {
    @Volatile var dir: File? = null
        private set
    val saved = AtomicInteger()
    val dropped = AtomicInteger()
    private val queued = AtomicInteger()
    private val writer = Executors.newSingleThreadExecutor()
    private var index = 0

    fun start(meta: JSONObject) {
        root.mkdirs()
        val n = (root.listFiles()?.mapNotNull { it.name.removePrefix("s").toIntOrNull() }?.maxOrNull() ?: 0) + 1
        val d = File(root, "s%02d".format(n)).apply { mkdirs() }
        File(d, "session.json").writeText(meta.toString(2))
        index = 0; saved.set(0); dropped.set(0)
        dir = d
    }

    fun stop() { dir = null }

    fun shutdown() { stop(); writer.shutdown() }

    /** Called on the analysis thread before [image] is closed. [upright] is the gray frame the detector used. */
    fun save(image: ImageProxy, upright: Mat, quad: List<Point>?) {
        val d = dir ?: return
        val i = index++
        // The writer is slower than the camera; skip frames rather than build a backlog.
        if (queued.get() >= 4) { dropped.incrementAndGet(); return }
        val gray = upright.clone()
        val color = runCatching { image.toBitmap() }.getOrNull()
        val info = JSONObject()
            .put("index", i)
            .put("timestampNs", image.imageInfo.timestamp)
            .put("rotation", image.imageInfo.rotationDegrees)
            .put("bufferSize", JSONArray(listOf(image.width, image.height)))
            .put("cropRect", image.cropRect.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
            .put("uprightSize", JSONArray(listOf(upright.cols(), upright.rows())))
            .put("quad", quad?.let { q -> JSONArray(q.map { JSONArray(listOf(it.x, it.y)) }) } ?: JSONObject.NULL)
        val rotation = image.imageInfo.rotationDegrees
        queued.incrementAndGet()
        writer.execute {
            try {
                val name = "f%04d".format(i)
                Imgcodecs.imwrite(File(d, "${name}_gray.png").path, gray, MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 1))
                if (color != null) {
                    val rgba = Mat(); val bgr = Mat()
                    Utils.bitmapToMat(color, rgba)
                    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                    val up = DocProcessing.rotate(bgr, rotation)
                    Imgcodecs.imwrite(File(d, "$name.jpg").path, up, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95))
                    rgba.release(); bgr.release(); up.release(); color.recycle()
                }
                File(d, "$name.json").writeText(info.toString())
                saved.incrementAndGet()
            } finally {
                gray.release()
                queued.decrementAndGet()
            }
        }
    }
}
