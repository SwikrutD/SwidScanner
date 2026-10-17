import io.github.swikrutd.swidscanner.core.DocProcessing
import io.github.swikrutd.swidscanner.core.DocProcessing.PxRect
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * idcard <front|synth> <back|synth> <out dir>: runs the ID card path of the app on two photos.
 * Each side is cut out with DocProcessing.cutCard (a "synth" side is drawn first: a card in
 * perspective on wood, framed like the camera guide frames it), then laid out on A4 at 300 dpi
 * with renderIdPage. Prints the slots and measures the cards on the rendered page, which should
 * come out at 85.6 x 54 mm with 20 mm from the centre line. Also writes the one-card layouts.
 */
fun idCard(frontArg: String, backArg: String, outDir: File) {
    outDir.mkdirs()
    val wMm = DocProcessing.ID1_W_MM; val hMm = DocProcessing.ID1_H_MM
    val cw = DocProcessing.mmToPx(wMm).roundToInt(); val ch = DocProcessing.mmToPx(hMm).roundToInt()
    println("card target $cw x $ch px, A4 ${DocProcessing.a4Px()} px")

    val cards = listOf("front" to frontArg, "back" to backArg).map { (side, arg) ->
        val (photo, guide) = if (arg == "synth") {
            val f = File("samples/id_${side}_synth.jpg")
            synthCardPhoto(f, side, if (side == "front") 1 else 2)
            val img = Imgcodecs.imread(f.path)
            val g = DocProcessing.idGuide(wMm, hMm)
            img to PxRect((g[0] * img.cols()).roundToInt(), (g[1] * img.rows()).roundToInt(),
                ((g[2] - g[0]) * img.cols()).roundToInt(), ((g[3] - g[1]) * img.rows()).roundToInt())
        } else {
            Imgcodecs.imread(arg).also { require(!it.empty()) { "Could not read $arg" } } to null
        }
        DocProcessing.findCard(photo, guide, cw.toDouble() / ch)?.let { q ->
            println("$side: card outline ${q.map { "(${it.x.roundToInt()},${it.y.roundToInt()})" }}")
        }
        val t0 = System.nanoTime()
        val (card, found) = DocProcessing.cutCard(photo, guide, cw, ch)
        println("$side: ${photo.cols()}x${photo.rows()} guide=$guide -> ${card.cols()}x${card.rows()} " +
            "${if (found) "card found" else "no card, fallback crop"} in ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms")
        Imgcodecs.imwrite(File(outDir, "card_$side.jpg").path, card)
        photo.release()
        card
    }

    val slots = DocProcessing.idSlots(wMm, hMm, 2)
    slots.forEachIndexed { i, s -> println("slot ${i + 1}: $s = ${mm(s.w)} x ${mm(s.h)} mm at (${mm(s.x)}, ${mm(s.y)}) mm") }
    val page = DocProcessing.renderIdPage(cards, wMm, hMm)
    Imgcodecs.imwrite(File(outDir, "idcard_page.jpg").path, page, org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92))
    measure(page, "two-sided")

    // Blown up to fill the page, as the review screen's "Fit page" does.
    val fitScale = DocProcessing.idMaxScale(wMm, hMm, 2)
    val fit = DocProcessing.renderIdPage(cards, wMm, hMm, scale = fitScale)
    Imgcodecs.imwrite(File(outDir, "idcard_fit.jpg").path, fit)
    println("fit page at ${"%.2f".format(fitScale)}x, slots ${DocProcessing.idSlots(wMm, hMm, 2, scale = fitScale)}")
    measure(fit, "fit")

    val single = DocProcessing.renderIdPage(cards.take(1), wMm, hMm)
    Imgcodecs.imwrite(File(outDir, "idcard_single.jpg").path, single)
    measure(single, "one card")

    val passport = DocProcessing.renderIdPage(cards.take(1), 125.0, 176.0)
    Imgcodecs.imwrite(File(outDir, "passport_page.jpg").path, passport)
    println("passport slot ${DocProcessing.idSlots(125.0, 176.0, 1)[0]} (card is landscape, so it fits the slot's width)")

    // A low resolution render, as the review screen previews it.
    val preview = DocProcessing.renderIdPage(cards, wMm, hMm, 100.0)
    Imgcodecs.imwrite(File(outDir, "idcard_preview_100dpi.jpg").path, preview)
    println("preview at 100 dpi: ${preview.cols()}x${preview.rows()}")
    println("pages -> ${outDir.path}")
}

/** Prints the cards measured on a saved 300 dpi page. */
fun idMeasure(page: File) {
    val img = Imgcodecs.imread(page.path)
    require(!img.empty()) { "Could not read $page" }
    println("${page.name}: ${img.cols()}x${img.rows()} px = ${mm(img.cols())} x ${mm(img.rows())} mm at 300 dpi")
    measure(img, page.nameWithoutExtension)
}

private fun mm(px: Int) = "%.2f".format(px * 25.4 / DocProcessing.ID_DPI)

/** Finds each non-white block on [page] and prints its size and its distance from the centre line in mm. */
private fun measure(page: Mat, label: String) {
    val gray = DocProcessing.toGray(page)
    val ink = Mat(); Imgproc.threshold(gray, ink, 200.0, 255.0, Imgproc.THRESH_BINARY_INV) // below the 10% watermark
    val contours = ArrayList<org.opencv.core.MatOfPoint>()
    Imgproc.findContours(ink, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
    val mid = page.rows() / 2.0
    val boxes = contours.map { Imgproc.boundingRect(it) }.filter { it.width > 300 && it.height > 300 }.sortedBy { it.y }
    for (b in boxes) {
        val gap = when {
            b.y + b.height <= mid -> "${"%.2f".format((mid - b.y - b.height) * 25.4 / DocProcessing.ID_DPI)} mm above the centre line"
            b.y >= mid -> "${"%.2f".format((b.y - mid) * 25.4 / DocProcessing.ID_DPI)} mm below the centre line"
            else -> "centre ${"%.2f".format((b.y + b.height / 2.0 - mid) * 25.4 / DocProcessing.ID_DPI)} mm off the centre line"
        }
        println("$label: card ${mm(b.width)} x ${mm(b.height)} mm, $gap, " +
            "left margin ${mm(b.x)} mm, right margin ${mm(page.cols() - b.x - b.width)} mm")
    }
    gray.release(); ink.release()
}

/**
 * Draws a phone photo (2400 x 3200, upright) of a made up ID card lying on a wood table, a little
 * tilted and in perspective, roughly where the camera guide would frame it.
 */
private fun synthCardPhoto(out: File, side: String, seed: Int) {
    out.parentFile.mkdirs()
    val rnd = Random(seed)
    val pw = 2400; val ph = 3200
    // Wood: brown with streaks along x and noise.
    val photo = Mat(ph, pw, CvType.CV_8UC3, Scalar(70.0, 105.0, 150.0))
    for (y in 0 until ph step 6) {
        val v = rnd.nextDouble(-18.0, 18.0)
        Imgproc.line(photo, Point(0.0, y.toDouble()), Point(pw.toDouble(), y + rnd.nextDouble(-40.0, 40.0)),
            Scalar(70.0 + v, 105.0 + v, 150.0 + v), rnd.nextInt(2, 6))
    }
    addNoise(photo, 6.0)

    // The card itself at 300 dpi.
    val cw = 1011; val ch = 638
    val card = Mat(ch, cw, CvType.CV_8UC3, if (side == "front") Scalar(235.0, 225.0, 205.0) else Scalar(215.0, 235.0, 230.0))
    Imgproc.rectangle(card, Point(0.0, 0.0), Point(cw.toDouble(), 120.0), Scalar(140.0, 70.0, 20.0), -1)
    Imgproc.putText(card, if (side == "front") "IDENTITY CARD" else "ADDRESS", Point(40.0, 85.0), Imgproc.FONT_HERSHEY_DUPLEX, 2.0, Scalar.all(255.0), 3)
    if (side == "front") {
        Imgproc.rectangle(card, Point(50.0, 170.0), Point(300.0, 520.0), Scalar(160.0, 160.0, 160.0), -1)
        Imgproc.circle(card, Point(175.0, 290.0), 70, Scalar(110.0, 110.0, 110.0), -1)
    }
    val x0 = if (side == "front") 360.0 else 60.0
    for ((i, line) in listOf("NAME  A. SAMPLE", "DOB   01/01/1990", "NO    1234 5678 9012", "VALID 2035").withIndex())
        Imgproc.putText(card, line, Point(x0, 220.0 + i * 80), Imgproc.FONT_HERSHEY_SIMPLEX, 1.3, Scalar(30.0, 30.0, 30.0), 2)
    Imgproc.rectangle(card, Point(40.0, 560.0), Point(970.0, 600.0), Scalar(90.0, 160.0, 90.0), -1)

    // Place it where the guide is (80% of the width), shrunk a little, tilted and in perspective.
    val g = DocProcessing.idGuide(DocProcessing.ID1_W_MM, DocProcessing.ID1_H_MM)
    val gw = (g[2] - g[0]) * pw * 0.92; val gh = gw * ch / cw
    val cx = pw / 2.0 + rnd.nextDouble(-60.0, 60.0); val cy = ph / 2.0 + rnd.nextDouble(-60.0, 60.0)
    val a = Math.toRadians(rnd.nextDouble(-6.0, 6.0))
    fun at(dx: Double, dy: Double) = Point(cx + dx * Math.cos(a) - dy * Math.sin(a), cy + dx * Math.sin(a) + dy * Math.cos(a))
    val k = 0.06 // top edge shorter than the bottom: the phone leans back
    val dst = listOf(at(-gw / 2 * (1 - k), -gh / 2), at(gw / 2 * (1 - k), -gh / 2), at(gw / 2, gh / 2), at(-gw / 2, gh / 2))
    val m = Imgproc.getPerspectiveTransform(
        MatOfPoint2f(Point(0.0, 0.0), Point(cw - 1.0, 0.0), Point(cw - 1.0, ch - 1.0), Point(0.0, ch - 1.0)),
        MatOfPoint2f(*dst.toTypedArray()),
    )
    // Round the card's corners in the mask too, like a real card.
    val mask = Mat.zeros(ch, cw, CvType.CV_8UC1)
    Imgproc.rectangle(mask, Point(37.0, 0.0), Point(cw - 38.0, ch - 1.0), Scalar(255.0), -1)
    Imgproc.rectangle(mask, Point(0.0, 37.0), Point(cw - 1.0, ch - 38.0), Scalar(255.0), -1)
    for ((x, y) in listOf(37 to 37, cw - 38 to 37, cw - 38 to ch - 38, 37 to ch - 38))
        Imgproc.circle(mask, Point(x.toDouble(), y.toDouble()), 37, Scalar(255.0), -1, Imgproc.LINE_AA)
    val warped = Mat(); val warpedMask = Mat()
    Imgproc.warpPerspective(card, warped, m, Size(pw.toDouble(), ph.toDouble()))
    Imgproc.warpPerspective(mask, warpedMask, m, Size(pw.toDouble(), ph.toDouble()))
    // A soft shadow under the card, then the card.
    val shadow = Mat(); Imgproc.GaussianBlur(warpedMask, shadow, Size(0.0, 0.0), 18.0)
    val shifted = Mat(); Imgproc.warpAffine(shadow, shifted, Mat(2, 3, CvType.CV_64F).apply { put(0, 0, 1.0, 0.0, 14.0, 0.0, 1.0, 22.0) }, shadow.size())
    val dark = Mat(); Core.multiply(shifted, Scalar(0.45), dark)
    val dark3 = Mat(); Core.merge(listOf(dark, dark, dark), dark3)
    Core.subtract(photo, dark3, photo)
    warped.copyTo(photo, warpedMask)
    addNoise(photo, 3.0)
    Imgcodecs.imwrite(out.path, photo)
    println("synth $side photo -> ${out.path} (card corners ${dst.map { "(${it.x.roundToInt()},${it.y.roundToInt()})" }})")
}

/** Adds gaussian noise with standard deviation [sigma] to every channel of the 8-bit [img]. */
private fun addNoise(img: Mat, sigma: Double) {
    val noise = Mat(img.size(), img.type())
    Core.randn(noise, 128.0, sigma)
    Core.addWeighted(img, 1.0, noise, 1.0, -128.0, img)
    noise.release()
}
