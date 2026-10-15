package io.github.swikrutd.swidscanner.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Scan filters, in the order they are shown to the user. */
enum class Filter(val label: String) {
    ORIGINAL("Original"),
    MAGIC("Magic"),
    ENHANCE("Enhance"),
    GRAYSCALE("Grayscale"),
    BW("B&W"),
}

/**
 * Pure OpenCV document processing: page detection, perspective correction and filters.
 * Has no Android dependencies so it can be unit tested on a desktop JVM.
 *
 * Mats may be 1, 3 or 4 channel 8-bit. Colour order (RGB vs BGR) does not matter
 * for anything here beyond a negligible difference in grayscale weights.
 */
object DocProcessing {

    private const val DETECT_SIZE = 640.0

    /**
     * Finds the document outline. Returns four corners ordered TL, TR, BR, BL in
     * [src] pixel coordinates, or null if no convincing page was found.
     */
    fun detect(src: Mat): List<Point>? {
        val scale = DETECT_SIZE / max(src.cols(), src.rows())
        val small = Mat()
        if (scale < 1.0) Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA) else src.copyTo(small)
        val gray = toGray(small)
        // Saturation-like channel separates white paper from wood, carpet and other coloured surfaces.
        val sat = if (small.channels() >= 3) saturation(small) else null
        small.release()

        val imgArea = gray.cols().toDouble() * gray.rows()
        val candidates = ArrayList<List<Point>>()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val support = Mat(gray.size(), gray.type(), org.opencv.core.Scalar(0.0))

        for (channel in listOfNotNull(gray, sat)) {
            // Closing erases text and lines on the page so the sheet reads as one blob.
            val closed = Mat()
            Imgproc.morphologyEx(channel, closed, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 3)
            Imgproc.GaussianBlur(closed, closed, Size(5.0, 5.0), 0.0)

            // Pass 1: edges.
            val edges = Mat()
            Imgproc.Canny(closed, edges, 20.0, 60.0)
            Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            candidates += quadsFrom(edges, imgArea)

            // Pass 2: Otsu segmentation (paper vs whatever it sits on).
            val bin = Mat()
            Imgproc.threshold(closed, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            candidates += quadsFrom(bin, imgArea)
            val inv = Mat()
            Core.bitwise_not(bin, inv)
            candidates += quadsFrom(inv, imgArea)

            // Support map: any noticeable brightness step on the de-texted image.
            val grad = Mat()
            Imgproc.morphologyEx(closed, grad, Imgproc.MORPH_GRADIENT,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
            Imgproc.threshold(grad, grad, 14.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_or(support, grad, support)
            closed.release(); edges.release(); bin.release(); inv.release(); grad.release()
        }
        sat?.release()

        // Score: big, outline actually lies on edges, not glued to the frame border.
        Imgproc.dilate(support, support, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        val w = gray.cols(); val h = gray.rows()
        var best: List<Point>? = null
        var bestScore = 0.0
        for (q in candidates) {
            val sup = edgeSupport(support, q)
            if (sup < 0.5) continue
            val onBorder = q.count { it.x < w * 0.01 || it.y < h * 0.01 || it.x > w * 0.99 || it.y > h * 0.99 }
            if (onBorder >= 3) continue
            val score = (polyArea(q) / imgArea) * sup * (1.0 - 0.25 * onBorder)
            if (score > bestScore) { bestScore = score; best = q }
        }

        gray.release(); support.release(); kernel.release()

        best ?: return null
        val inv = if (scale < 1.0) 1.0 / scale else 1.0
        return order(best.map { Point(it.x * inv, it.y * inv) })
    }

    /** Fraction of points sampled along the quad outline that land on an edge pixel. */
    private fun edgeSupport(edgeMap: Mat, q: List<Point>): Double {
        var hit = 0; var total = 0
        val w = edgeMap.cols(); val h = edgeMap.rows()
        val px = ByteArray(1)
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            val n = max(8, (dist(a, b) / 3).toInt())
            for (k in 0..n) {
                val t = k.toDouble() / n
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                total++
                // Outline running along the frame edge counts as supported (page cut off by the frame).
                if (x <= 1 || y <= 1 || x >= w - 2 || y >= h - 2) { hit++; continue }
                edgeMap.get(y, x, px)
                if (px[0].toInt() != 0) hit++
            }
        }
        return hit.toDouble() / total
    }

    private fun quadsFrom(binary: Mat, imgArea: Double): List<List<Point>> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)
        val found = ArrayList<List<Point>>()
        for (c in sorted) {
            if (Imgproc.contourArea(c) < imgArea * 0.1) break
            val hullIdx = MatOfInt()
            Imgproc.convexHull(c, hullIdx)
            val pts = c.toArray()
            val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
            val peri = Imgproc.arcLength(hull, true)
            for (eps in doubleArrayOf(0.02, 0.03, 0.045, 0.06, 0.08)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(hull, approx, eps * peri, true)
                val a = approx.toArray()
                approx.release()
                if (a.size == 4) {
                    val q = a.toList()
                    val area = polyArea(q)
                    // Ignore a quad that is just the image border.
                    if (area >= imgArea * 0.1 && area <= imgArea * 0.985) found += q
                    break
                }
                if (a.size < 4) break
            }
            hull.release(); hullIdx.release()
        }
        contours.forEach { it.release() }
        return found
    }

    /** Orders four points as TL, TR, BR, BL. */
    fun order(p: List<Point>): List<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!
        val bl = p.maxByOrNull { it.y - it.x }!!
        val set = listOf(tl, tr, br, bl)
        if (set.distinct().size == 4) return set
        // Degenerate (e.g. a diamond): sort by angle around the centroid instead.
        val cx = p.sumOf { it.x } / 4; val cy = p.sumOf { it.y } / 4
        val byAngle = p.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
        val start = byAngle.indices.minByOrNull { byAngle[it].x + byAngle[it].y }!!
        return List(4) { byAngle[(start + it) % 4] }
    }

    /** Perspective-corrects the region inside [quad] (TL, TR, BR, BL). */
    fun warp(src: Mat, quad: List<Point>): Mat {
        val (tl, tr, br, bl) = quad
        val w = max(dist(tl, tr), dist(bl, br)).roundToInt().coerceAtLeast(1)
        val h = max(dist(tl, bl), dist(tr, br)).roundToInt().coerceAtLeast(1)
        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0)
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(src, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        srcPts.release(); dstPts.release(); m.release()
        return out
    }

    /** Rotates clockwise by a multiple of 90 degrees. Returns a new Mat. */
    fun rotate(src: Mat, degrees: Int): Mat {
        val out = Mat()
        when (((degrees % 360) + 360) % 360) {
            90 -> Core.rotate(src, out, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, out, Core.ROTATE_180)
            270 -> Core.rotate(src, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> src.copyTo(out)
        }
        return out
    }

    /** Applies [filter]. The result has the same channel count as [src]. */
    fun applyFilter(src: Mat, filter: Filter): Mat {
        val channels = src.channels()
        return when (filter) {
            Filter.ORIGINAL -> src.clone()
            Filter.MAGIC -> {
                val color = toColor3(src)
                val planes = ArrayList<Mat>()
                Core.split(color, planes)
                val outPlanes = planes.map { normalizeBackground(it) }
                val merged = Mat()
                Core.merge(outPlanes, merged)
                // A little extra contrast so ink looks like ink.
                merged.convertTo(merged, -1, 1.12, -18.0)
                planes.forEach { it.release() }; outPlanes.forEach { it.release() }; color.release()
                fromColor3(merged, channels)
            }
            Filter.ENHANCE -> {
                val color = toColor3(src)
                val blur = Mat()
                Imgproc.GaussianBlur(color, blur, Size(0.0, 0.0), 3.0)
                val sharp = Mat()
                Core.addWeighted(color, 1.6, blur, -0.6, 0.0, sharp)
                sharp.convertTo(sharp, -1, 1.15, 12.0)
                color.release(); blur.release()
                fromColor3(sharp, channels)
            }
            Filter.GRAYSCALE -> {
                val gray = toGray(src)
                val clahe = Imgproc.createCLAHE(1.6, Size(8.0, 8.0))
                val out = Mat()
                clahe.apply(gray, out)
                gray.release()
                fromGray(out, channels)
            }
            Filter.BW -> {
                val gray = toGray(src)
                val norm = normalizeBackground(gray)
                gray.release()
                val block = (max(norm.cols(), norm.rows()) / 50).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(15)
                val bw = Mat()
                Imgproc.adaptiveThreshold(norm, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 18.0)
                // Also keep anything clearly dark, so solid blocks and thick strokes do not hollow out.
                val dark = Mat()
                Imgproc.threshold(norm, dark, 110.0, 255.0, Imgproc.THRESH_BINARY)
                Core.bitwise_and(bw, dark, bw)
                Imgproc.medianBlur(bw, bw, 3)
                norm.release(); dark.release()
                fromGray(bw, channels)
            }
        }
    }

    /**
     * Flattens uneven lighting: estimates the paper colour with a blurred dilation
     * (computed at low resolution for speed) and divides it out, so paper becomes white.
     */
    private fun normalizeBackground(plane: Mat): Mat {
        val f = 0.25
        val small = Mat()
        Imgproc.resize(plane, small, Size(), f, f, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        Imgproc.dilate(small, small, k)
        val ksize = (max(small.cols(), small.rows()) / 12).let { if (it % 2 == 0) it + 1 else it }.coerceIn(5, 255)
        Imgproc.medianBlur(small, small, ksize)
        val bg = Mat()
        Imgproc.resize(small, bg, plane.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val out = Mat()
        Core.divide(plane, bg, out, 255.0)
        small.release(); bg.release(); k.release()
        return out
    }

    private fun saturation(src: Mat): Mat {
        val c = toColor3(src)
        val planes = ArrayList<Mat>()
        Core.split(c, planes)
        val mx = Mat(); val mn = Mat(); val out = Mat()
        Core.max(planes[0], planes[1], mx); Core.max(mx, planes[2], mx)
        Core.min(planes[0], planes[1], mn); Core.min(mn, planes[2], mn)
        Core.subtract(mx, mn, out)
        Core.normalize(out, out, 0.0, 255.0, Core.NORM_MINMAX)
        planes.forEach { it.release() }; c.release(); mx.release(); mn.release()
        return out
    }

    fun toGray(src: Mat): Mat {
        val g = Mat()
        when (src.channels()) {
            1 -> src.copyTo(g)
            3 -> Imgproc.cvtColor(src, g, Imgproc.COLOR_RGB2GRAY)
            else -> Imgproc.cvtColor(src, g, Imgproc.COLOR_RGBA2GRAY)
        }
        return g
    }

    private fun toColor3(src: Mat): Mat {
        val c = Mat()
        when (src.channels()) {
            1 -> Imgproc.cvtColor(src, c, Imgproc.COLOR_GRAY2RGB)
            3 -> src.copyTo(c)
            else -> Imgproc.cvtColor(src, c, Imgproc.COLOR_RGBA2RGB)
        }
        return c
    }

    private fun fromColor3(c: Mat, channels: Int): Mat {
        if (channels == 3) return c
        val out = Mat()
        if (channels == 4) Imgproc.cvtColor(c, out, Imgproc.COLOR_RGB2RGBA)
        else Imgproc.cvtColor(c, out, Imgproc.COLOR_RGB2GRAY)
        c.release()
        return out
    }

    private fun fromGray(g: Mat, channels: Int): Mat {
        if (channels == 1) return g
        val out = Mat()
        Imgproc.cvtColor(g, out, if (channels == 4) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_GRAY2RGB)
        g.release()
        return out
    }

    fun polyArea(p: List<Point>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

}
