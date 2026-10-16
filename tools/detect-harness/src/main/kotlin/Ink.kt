import io.github.swikrutd.swidscanner.core.DocProcessing
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ink <image|synth> <out dir>: runs DocProcessing.inkToTransparent on a photo of a signature or stamp
 * and writes the transparent PNGs (blue ink and original colours) plus a comparison sheet.
 * "synth" first draws samples/signature_shadow.jpg: blue ink on paper with a shadow band across it,
 * uneven light, dust specks and sensor noise.
 */
fun ink(input: String, outDir: File) {
    outDir.mkdirs()
    val file = if (input == "synth") File("samples/signature_shadow.jpg").also { synthSignature(it) } else File(input)
    val bgr = Imgcodecs.imread(file.path)
    require(!bgr.empty()) { "Could not read $file" }
    val rgb = Mat(); Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
    val name = file.nameWithoutExtension

    val sheet = mutableListOf(fit(bgr, 420))
    // What a plain global threshold does with the same photo, for comparison.
    val gray = DocProcessing.toGray(rgb)
    val naive = Mat(); Imgproc.threshold(gray, naive, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
    val naiveBgr = Mat(); Imgproc.cvtColor(naive, naiveBgr, Imgproc.COLOR_GRAY2BGR)
    sheet += fit(naiveBgr, 420)

    for ((tag, color) in listOf("blue" to 0xFF1A3A8C.toInt(), "keep" to null)) {
        val t0 = System.nanoTime()
        val out = DocProcessing.inkToTransparent(rgb, color)
        val ms = (System.nanoTime() - t0) / 1e6
        val bgra = Mat(); Imgproc.cvtColor(out, bgra, Imgproc.COLOR_RGBA2BGRA)
        val png = File(outDir, "${name}_ink_$tag.png")
        Imgcodecs.imwrite(png.path, bgra)
        val alpha = Mat(); Core.extractChannel(out, alpha, 3)
        val solid = Mat(); Imgproc.threshold(alpha, solid, 127.0, 255.0, Imgproc.THRESH_BINARY)
        println("$tag: ${bgr.cols()}x${bgr.rows()} -> ${out.cols()}x${out.rows()} in ${"%.0f".format(ms)} ms, " +
            "opaque px=${Core.countNonZero(solid)} any-alpha px=${Core.countNonZero(alpha)} -> ${png.path}")
        val onChecker = overChecker(bgra)
        Imgcodecs.imwrite(File(outDir, "${name}_ink_${tag}_preview.jpg").path, onChecker)
        sheet += fit(onChecker, 420)
    }
    val row = Mat(); Core.hconcat(sheet.map { pad(it, sheet.maxOf { s -> s.rows() }) }, row)
    Imgcodecs.imwrite(File(outDir, "${name}_ink_sheet.jpg").path, row)
    println("sheet (input | plain Otsu | blue ink | original colours) -> ${File(outDir, "${name}_ink_sheet.jpg").path}")
}

/** Draws a test photo: a looping blue signature under a soft shadow band, with uneven light, specks and noise. */
private fun synthSignature(out: File) {
    out.parentFile.mkdirs()
    val w = 1600; val h = 1000
    val ink = Mat.zeros(h, w, CvType.CV_8UC1)
    // Looping cursive body (a prolate cycloid), a capital loop in front and an underline flourish.
    val body = (0..1400).map { i ->
        val th = i / 1400.0 * 14 * PI
        val t = i / 1400.0
        Point(300 + 26 * th - 40 * sin(th), 470 - 30 * t * 4 - (90 + 30 * sin(t * 5)) * cos(th))
    }
    val capital = (0..400).map { i ->
        val a = i / 400.0 * 2.2 * PI
        Point(220 + 110 * sin(a) + i * 0.15, 470 - 230 * cos(a * 0.5) * 0.9)
    }
    val flourish = (0..300).map { i ->
        val t = i / 300.0
        Point(180 + 1300 * t, 720 - 160 * t + 60 * sin(t * PI) - 40 * t * t)
    }
    for (stroke in listOf(body, capital, flourish))
        Imgproc.polylines(ink, listOf(MatOfPoint(*stroke.toTypedArray())), false, Scalar(255.0), 6, Imgproc.LINE_AA)
    Imgproc.circle(ink, Point(905.0, 255.0), 7, Scalar(255.0), -1, Imgproc.LINE_AA) // an i dot

    // Paper with light falling off towards the bottom right.
    val img = Mat(h, w, CvType.CV_32FC3)
    val paper = doubleArrayOf(226.0, 231.0, 236.0) // BGR, slightly warm
    val inkBgr = doubleArrayOf(115.0, 40.0, 30.0)  // dark blue ballpoint
    val inkPx = ByteArray(w * h).also { ink.get(0, 0, it) }
    // Shadow: a band across the signature with a soft edge, as a hand or phone casts.
    val shadow = Mat.zeros(h, w, CvType.CV_8UC1)
    Imgproc.fillPoly(shadow, listOf(MatOfPoint(Point(0.0, 380.0), Point(1600.0, 120.0), Point(1600.0, 560.0), Point(0.0, 860.0))), Scalar(255.0))
    Imgproc.GaussianBlur(shadow, shadow, Size(0.0, 0.0), 14.0)
    val sh = ByteArray(w * h).also { shadow.get(0, 0, it) }
    val rnd = java.util.Random(7)
    val px = FloatArray(w * h * 3)
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        val light = (1.0 - 0.18 * (x + y) / (w + h)) * (1.0 - 0.5 * (sh[i].toInt() and 0xFF) / 255.0)
        val a = (inkPx[i].toInt() and 0xFF) / 255.0
        for (c in 0 until 3) {
            val v = (paper[c] * (1 - a) + inkBgr[c] * a) * light + rnd.nextGaussian() * 4
            px[i * 3 + c] = v.toFloat()
        }
    }
    img.put(0, 0, px)
    // Dust specks.
    repeat(30) {
        Imgproc.circle(img, Point(rnd.nextInt(w).toDouble(), rnd.nextInt(h).toDouble()), 1 + rnd.nextInt(2), Scalar(90.0, 90.0, 95.0), -1)
    }
    Imgproc.GaussianBlur(img, img, Size(0.0, 0.0), 0.8)
    val out8 = Mat(); img.convertTo(out8, CvType.CV_8UC3)
    Imgcodecs.imwrite(out.path, out8, org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85))
    println("synth sample -> ${out.path}")
}

/** Composites a BGRA image over a gray checkerboard so transparency is visible in a JPEG. */
private fun overChecker(bgra: Mat): Mat {
    val w = bgra.cols(); val h = bgra.rows()
    val src = ByteArray(w * h * 4).also { bgra.get(0, 0, it) }
    val dst = ByteArray(w * h * 3)
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        val bg = if ((x / 16 + y / 16) % 2 == 0) 255 else 204
        val a = (src[i * 4 + 3].toInt() and 0xFF) / 255.0
        for (c in 0 until 3) dst[i * 3 + c] = ((src[i * 4 + c].toInt() and 0xFF) * a + bg * (1 - a)).toInt().toByte()
    }
    return Mat(h, w, CvType.CV_8UC3).also { it.put(0, 0, dst) }
}

private fun fit(m: Mat, width: Int): Mat {
    val out = Mat(); Imgproc.resize(m, out, Size(), width.toDouble() / m.cols(), width.toDouble() / m.cols(), Imgproc.INTER_AREA)
    return out
}

private fun pad(m: Mat, height: Int): Mat {
    val out = Mat(); Core.copyMakeBorder(m, out, 0, height - m.rows(), 0, 4, Core.BORDER_CONSTANT, Scalar(60.0, 60.0, 60.0))
    return out
}
