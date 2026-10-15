import io.github.swikrutd.swidscanner.core.DocProcessing
import io.github.swikrutd.swidscanner.core.Filter
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Stills: for each image in args[0], detect the page, draw it, warp it and apply every filter into args[1].
 * Live frames: `frames <dir> [out]` replays sessions recorded with the app's debug REC button
 * and prints detection rate, jitter and accuracy per detector (see Frames.kt).
 */
fun main(args: Array<String>) {
    nu.pattern.OpenCV.loadLocally()
    if (args[0] == "frames") {
        replay(java.io.File(args[1]), args.getOrNull(2)?.let { java.io.File(it) }, mapOf("raw" to ::rawGray))
        return
    }
    val inDir = java.io.File(args[0]); val outDir = java.io.File(args[1]); outDir.mkdirs()
    for (f in inDir.listFiles()!!.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }.sorted()) {
        val img = Imgcodecs.imread(f.path)
        val t0 = System.nanoTime()
        val q = DocProcessing.detect(img)
        val ms = (System.nanoTime() - t0) / 1e6
        println("${f.name} ${img.cols()}x${img.rows()} detect=${"%.1f".format(ms)}ms quad=${q?.map { "(${it.x.toInt()},${it.y.toInt()})" }}")
        val vis = img.clone()
        if (q != null) for (i in 0 until 4) Imgproc.line(vis, q[i], q[(i + 1) % 4], Scalar(0.0, 255.0, 0.0), maxOf(2, img.cols() / 250))
        val small = Mat(); Imgproc.resize(vis, small, Size(), 600.0 / vis.cols(), 600.0 / vis.cols())
        Imgcodecs.imwrite("$outDir/${f.nameWithoutExtension}_0detect.jpg", small)
        val warped = if (q != null) DocProcessing.warp(img, q) else img
        for (flt in Filter.entries) {
            val out = DocProcessing.applyFilter(warped, flt)
            val s2 = Mat(); Imgproc.resize(out, s2, Size(), 700.0 / out.cols(), 700.0 / out.cols(), Imgproc.INTER_AREA)
            Imgcodecs.imwrite("$outDir/${f.nameWithoutExtension}_${flt.ordinal + 1}$flt.jpg", s2)
        }
    }
}
