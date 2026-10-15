package io.github.swikrutd.swidscanner.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.CvType
import kotlin.math.abs
import kotlin.math.atan2
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

    /** Harness hook: called for every candidate quad (detection-size pixels) with its per-side support. */
    var debugCandidate: ((List<Point>, DoubleArray, Int) -> Unit)? = null
    var debugSupport: ((Mat) -> Unit)? = null

    private const val DETECT_SIZE = 640.0
    private const val SUPPORT_CANNY_LO = 30.0
    /** Every side must sit on an edge for at least this share of its length. */
    private const val MIN_SIDE_SUPPORT = 0.35
    private const val MIN_SUPPORT = 0.6
    private const val REFINE_PX = 5
    /** Degrees an edge's gradient may differ from a side's normal and still support it. */
    private const val ANGLE_TOL = 20
    private const val LINES_PER_AXIS = 8
    private const val MAX_SEGMENTS = 200
    /** Most of the strip [grow] adds may be darker than paper (a coloured band is fine, a table is not). */
    private const val MAX_GROW_DARK = 0.3
    /** Share of edge pixels in a 15 px window above which edges count as texture, not borders. */
    private const val MAX_EDGE_DENSITY = 0.2
    /** How far either side of an outline to compare the two regions, and the mean difference needed. */
    private val STEP_PX = doubleArrayOf(4.0, 10.0)
    private const val MIN_STEP = 12
    /** Share of points along a side that must step the same way. */
    private const val STEP_SHARE = 0.6

    /**
     * Finds the document outline. Returns four corners ordered TL, TR, BR, BL in
     * [src] pixel coordinates, or null if no convincing page was found.
     *
     * [live] selects the camera preview detector, which is stricter about what counts as a
     * page border and finds pages that don't form a clean outline (a book page, clutter).
     * Its output is meant to go through [QuadTracker]. [chroma] is an optional
     * saturation-like plane for gray live frames (see [chromaFromUV]). Stills keep the
     * original detector, which was tuned on photos.
     */
    fun detect(src: Mat, chroma: Mat? = null, live: Boolean = false): List<Point>? =
        if (live) detectLive(src, chroma) else detectStill(src)

    private fun detectStill(src: Mat): List<Point>? {
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
            candidates += outlines(edges, imgArea)

            // Pass 2: Otsu segmentation (paper vs whatever it sits on).
            val bin = Mat()
            Imgproc.threshold(closed, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            candidates += outlines(bin, imgArea)
            val inv = Mat()
            Core.bitwise_not(bin, inv)
            candidates += outlines(inv, imgArea)

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
            val sup = stillSupport(support, q)
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
    private fun stillSupport(edgeMap: Mat, q: List<Point>): Double {
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

    /** Clean four-corner outlines only, as the still detector was tuned with. */
    private fun outlines(binary: Mat, imgArea: Double) = quadsFrom(binary, imgArea).filter { !it.second }.map { it.first }

    /**
     * Live detector. Same candidate passes as for stills plus a few more, then a stricter
     * score: every side must lie on edges running along it, sides on the frame border are
     * rejected (in the preview the user can step back, and a cut-off outline is usually the
     * page merged with its surroundings) and the inside must look like paper.
     */
    private fun detectLive(src: Mat, chroma: Mat?): List<Point>? {
        val scale = DETECT_SIZE / max(src.cols(), src.rows())
        val small = Mat()
        if (scale < 1.0) Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA) else src.copyTo(small)
        val gray = toGray(small)
        // Live frames are gray, so the caller passes a saturation-like plane built from the
        // camera's U and V planes.
        val sat = when {
            chroma != null -> Mat().also { Imgproc.resize(chroma, it, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR) }
            small.channels() >= 3 -> saturation(small)
            else -> null
        }
        small.release()

        val imgArea = gray.cols().toDouble() * gray.rows()
        // Each candidate carries whether it needs the step check (see stepsEverywhere).
        val candidates = ArrayList<Pair<List<Point>, Boolean>>()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val edgeMap = EdgeMap(gray.cols(), gray.rows())

        val masks = ArrayList<Mat>()
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
            masks += bin

            // Pass 2b: a second Otsu split within the bright and the dark half, for scenes with
            // three tones (paper, a mid-tone surface and something dark beside it).
            val hist = IntArray(256)
            val px = ByteArray(closed.cols() * closed.rows()).also { closed.get(0, 0, it) }
            for (b in px) hist[b.toInt() and 0xFF]++
            val t = otsu(hist, 0, 255)
            for (level in intArrayOf(otsu(hist, 0, t), otsu(hist, t + 1, 255))) {
                if (level <= 0 || level >= 255) continue
                val m = Mat()
                Imgproc.threshold(closed, m, level.toDouble(), 255.0, Imgproc.THRESH_BINARY)
                candidates += quadsFrom(m, imgArea)
                Core.bitwise_not(m, m)
                candidates += quadsFrom(m, imgArea)
                m.release()
            }

            // Support map from the lightly blurred channel, not the closed one: closing turns
            // crisp page borders into soft ramps.
            edgeMap.add(channel)
            closed.release(); edges.release(); inv.release()
        }
        val planes = listOfNotNull(gray, sat).map { m -> ByteArray(m.cols() * m.rows()).also { m.get(0, 0, it) } }
        sat?.release()
        // Pass 3: paper that is only separable using both channels, such as cream paper on a
        // white surface (bright in gray like the surface, but not neutral like it).
        if (masks.size == 2) {
            val (g, c) = masks
            val nc = Mat(); Core.bitwise_not(c, nc)
            for (other in listOf(c, nc)) {
                val both = Mat()
                Core.bitwise_and(g, other, both)
                Imgproc.morphologyEx(both, both, Imgproc.MORPH_OPEN, kernel)
                candidates += quadsFrom(both, imgArea)
                both.release()
            }
            nc.release()
        }
        masks.forEach { it.release() }

        // Score: big, every side lies on a real edge, not glued to the frame border, roughly
        // central, and the inside looks like paper.
        edgeMap.finish()
        val edgeMat = edgeMap.toMat()
        debugSupport?.invoke(edgeMat)
        // Lines find pages whose outline never forms a clean contour.
        val lineQuads = quadsFromLines(houghLines(edgeMat, sparseOnly = true), edgeMat.cols(), edgeMat.rows(), imgArea)
        edgeMat.release()
        val w = gray.cols(); val h = gray.rows()
        var best: List<Point>? = null
        var bestScore = 0.0
        val accepted = ArrayList<Pair<List<Point>, Double>>()

        fun score(q: List<Point>, needsStep: Boolean): Double? {
            val s = edgeSupport(edgeMap, q) ?: return null
            debugCandidate?.invoke(q, s.sides, s.borderSides)
            if (s.borderSides > 0 || s.sides.min() < MIN_SIDE_SUPPORT || s.sides.average() < MIN_SUPPORT) return null
            if (needsStep && !stepsEverywhere(planes, w, h, q)) return null
            val cx = q.sumOf { it.x } / 4 - w / 2.0; val cy = q.sumOf { it.y } / 4 - h / 2.0
            val offCentre = hypot(cx, cy) / hypot(w / 2.0, h / 2.0)
            return (polyArea(q) / imgArea) * s.sides.average() * (1.0 - 0.3 * offCentre) * (1.0 - darkShare(gray, q))
        }
        // Contour quads need snapping onto the edges; quads built from lines already sit on them.
        // Several passes often find the same outline; refine each one once.
        val unique = ArrayList<Pair<List<Point>, Boolean>>()
        for (c in candidates) if (unique.none { (u, _) -> u.all { p -> c.first.any { dist(it, p) < 3 } } }) unique += c
        // Contour quads are region boundaries already. Quads from lines may be rules drawn on a
        // page, so they (and contour quads with a corner dropped) must show a step on every side.
        val all = unique.asSequence().mapNotNull { (c, step) -> refine(edgeMap, c)?.let { it to step } } +
            lineQuads.asSequence().map { it to true }
        for ((q, needsStep) in all) {
            val sc = score(q, needsStep) ?: continue
            accepted += q to sc
            if (sc > bestScore) { bestScore = sc; best = q }
        }
        // A page split by a coloured band, a table rule or a header bar is often found as just
        // one part. Try pushing each side of the best few out to a parallel line further out;
        // if the bigger quad passes every check it wins on area.
        for ((q, _) in accepted.sortedByDescending { it.second }.take(3)) {
            for ((grown, strip) in grow(q, edgeMap)) {
                // Can't win even with perfect edges: not worth snapping.
                if (polyArea(grown) / imgArea <= bestScore) continue
                // The added strip must be more of the same paper, not the surface it lies on.
                if (darkShare(gray, strip) > MAX_GROW_DARK) continue
                val r = refine(edgeMap, grown) ?: continue
                val sc = score(r, needsStep = true) ?: continue
                if (sc > bestScore) { bestScore = sc; best = r }
            }
        }

        gray.release(); kernel.release()

        best ?: return null
        val inv = if (scale < 1.0) 1.0 / scale else 1.0
        return order(best.map { Point(it.x * inv, it.y * inv) })
    }

    /**
     * Thin edges with their gradient direction, so a side only counts edge pixels that run
     * along it. Texture (wood grain, carpet, a photo on the facing page) has edges in every
     * direction, so it rarely supports a straight side the way a real page border does.
     */
    private class EdgeMap(val w: Int, val h: Int) {
        /** 0 = no edge, otherwise the gradient direction in whole degrees mod 180, plus 1. */
        private val dir = ByteArray(w * h)
        /** Per pixel, a bit for each 10 degree direction bin seen within 1 px. Built by [finish]. */
        private val near = IntArray(w * h)

        fun add(channel: Mat) {
            val blur = Mat(); val e = Mat(); val gx = Mat(); val gy = Mat()
            Imgproc.GaussianBlur(channel, blur, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blur, e, SUPPORT_CANNY_LO, SUPPORT_CANNY_LO * 3)
            Imgproc.Sobel(blur, gx, CvType.CV_16S, 1, 0)
            Imgproc.Sobel(blur, gy, CvType.CV_16S, 0, 1)
            val n = w * h
            val eb = ByteArray(n).also { e.get(0, 0, it) }
            val xs = ShortArray(n).also { gx.get(0, 0, it) }
            val ys = ShortArray(n).also { gy.get(0, 0, it) }
            for (i in 0 until n) {
                if (eb[i].toInt() == 0 || dir[i].toInt() != 0) continue
                var deg = Math.toDegrees(atan2(ys[i].toDouble(), xs[i].toDouble()))
                if (deg < 0) deg += 180.0
                dir[i] = (deg.toInt() % 180 + 1).toByte()
            }
            blur.release(); e.release(); gx.release(); gy.release()
        }

        /** Spreads each edge's direction bit to its 3x3 neighbourhood, so lookups tolerate 1 px. */
        fun finish() {
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val d = dir[y * w + x].toInt() and 0xFF
                if (d == 0) continue
                val bit = 1 shl ((d - 1) / 10)
                for (dy in -1..1) {
                    val row = (y + dy) * w + x
                    near[row - 1] = near[row - 1] or bit
                    near[row] = near[row] or bit
                    near[row + 1] = near[row + 1] or bit
                }
            }
        }

        fun toMat(): Mat {
            val m = Mat(h, w, CvType.CV_8UC1)
            m.put(0, 0, ByteArray(w * h) { if (dir[it].toInt() != 0) -1 else 0 })
            return m
        }

        fun inside(x: Int, y: Int) = x > 1 && y > 1 && x < w - 2 && y < h - 2

        /** Direction bins within [ANGLE_TOL] of a side whose normal points at [normalDeg]. */
        private fun binsFor(normalDeg: Int): Int {
            var mask = 0
            var d = normalDeg - ANGLE_TOL
            while (d <= normalDeg + ANGLE_TOL) {
                mask = mask or (1 shl (((d % 180) + 180) % 180 / 10))
                d += 5
            }
            return mask
        }

        /**
         * Share of in-frame points along a..b that sit on an edge running the same way,
         * or -1 if fewer than 8 points are in frame.
         */
        fun along(a: Point, b: Point): Double {
            var normal = Math.toDegrees(atan2(b.x - a.x, -(b.y - a.y))).roundToInt() % 180
            if (normal < 0) normal += 180
            val mask = binsFor(normal)
            var hit = 0; var total = 0
            val n = max(8, (dist(a, b) / 3).toInt())
            for (k in 0..n) {
                val t = k.toDouble() / n
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                if (!inside(x, y)) continue
                total++
                if (near[y * w + x] and mask != 0) hit++
            }
            return if (total < 8) -1.0 else hit.toDouble() / total
        }

        fun onBorder(a: Point, b: Point): Boolean {
            val m = 0.015 * max(w, h)
            return (a.x < m && b.x < m) || (a.y < m && b.y < m) || (a.x > w - 1 - m && b.x > w - 1 - m) || (a.y > h - 1 - m && b.y > h - 1 - m)
        }
    }

    /**
     * Share of the quad's interior that is much darker than its paper, read as the 90th
     * percentile. Text is a small share; a quad that swallows part of a photo or the dark
     * surface beside the page has a large one.
     */
    private fun darkShare(gray: Mat, q: List<Point>): Double {
        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        Imgproc.fillConvexPoly(mask, MatOfPoint(*q.toTypedArray()), org.opencv.core.Scalar(255.0))
        val hist = Mat()
        Imgproc.calcHist(listOf(gray), MatOfInt(0), mask, hist, MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val h = FloatArray(256).also { hist.get(0, 0, it) }
        mask.release(); hist.release()
        val total = h.sum()
        if (total <= 0f) return 1.0
        var acc = 0f; var p90 = 255
        for (i in 255 downTo 0) { acc += h[i]; if (acc >= total * 0.1f) { p90 = i; break } }
        val cut = (p90 * 0.6).toInt()
        var dark = 0f
        for (i in 0 until cut) dark += h[i]
        return (dark / total).toDouble()
    }

    /** Otsu's threshold over histogram bins [from, to]. Pixels above the result are the bright class. */
    private fun otsu(hist: IntArray, from: Int, to: Int): Int {
        var total = 0L; var sum = 0.0
        for (i in from..to) { total += hist[i]; sum += i.toDouble() * hist[i] }
        if (total == 0L) return from
        var wB = 0L; var sumB = 0.0; var best = -1.0; var bestT = from
        for (i in from..to) {
            wB += hist[i]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; bestT = i }
        }
        return bestT
    }

    /**
     * True if every side is a step between two different regions (paper on one side,
     * background on the other) in at least one plane, rather than a line drawn on the page
     * such as a table rule or a form box.
     */
    private fun stepsEverywhere(planes: List<ByteArray>, w: Int, h: Int, q: List<Point>): Boolean {
        val cx = q.sumOf { it.x } / 4; val cy = q.sumOf { it.y } / 4
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            val len = dist(a, b)
            if (len < 1) return false
            var nx = -(b.y - a.y) / len; var ny = (b.x - a.x) / len
            // Point the normal outwards, away from the quad's centre.
            if ((a.x - cx) * nx + (a.y - cy) * ny < 0) { nx = -nx; ny = -ny }
            val n = max(8, (len / 4).toInt())
            // Two distances, so a thin strip along the border (the page block under a book
            // page, a shadow line) doesn't hide the step.
            var stepped = false; var checked = false
            for (d in STEP_PX) {
                var counted = 0
                // Per plane, how many points step up and how many step down by MIN_STEP or more.
                // Text beside a cut through the page steps both ways; a real border mostly one way.
                val up = IntArray(planes.size); val down = IntArray(planes.size)
                for (k in 1 until n) {
                    val t = k.toDouble() / n
                    val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                    val xi = (x - nx * d).roundToInt(); val yi = (y - ny * d).roundToInt()
                    val xo = (x + nx * d).roundToInt(); val yo = (y + ny * d).roundToInt()
                    if (xi !in 0 until w || yi !in 0 until h || xo !in 0 until w || yo !in 0 until h) continue
                    counted++
                    for (p in planes.indices) {
                        val diff = (planes[p][yi * w + xi].toInt() and 0xFF) - (planes[p][yo * w + xo].toInt() and 0xFF)
                        if (diff >= MIN_STEP) up[p]++ else if (diff <= -MIN_STEP) down[p]++
                    }
                }
                // A side mostly outside the frame can't be checked.
                if (counted < 6) continue
                checked = true
                if (planes.indices.any { max(up[it], down[it]) >= counted * STEP_SHARE }) stepped = true
            }
            if (checked && !stepped) return false
        }
        return true
    }

    private class Support(val sides: DoubleArray, val borderSides: Int)

    /**
     * For each side, the share of points sampled along it that land on an edge pixel.
     * Points outside the frame are skipped. A side running along the frame border can't
     * show an edge; it counts as supported but is reported in [Support.borderSides] so the
     * score can penalise it. Returns null as soon as one side falls below [MIN_SIDE_SUPPORT].
     */
    private fun edgeSupport(edges: EdgeMap, q: List<Point>): Support? {
        val sides = DoubleArray(4)
        var border = 0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            if (edges.onBorder(a, b)) { border++; sides[i] = 1.0; continue }
            val frac = edges.along(a, b)
            // Mostly out of view: nothing to check.
            sides[i] = if (frac < 0) 1.0 else frac
            if (sides[i] < MIN_SIDE_SUPPORT) return null
        }
        return Support(sides, border)
    }

    /**
     * Snaps each side of a candidate onto the nearest edge. Contours come from blurred,
     * closed images, so their sides sit a few pixels off the real page border. Each end of
     * a side may move up to [REFINE_PX] across the side; the corners are then the
     * intersections of the snapped sides. Returns null if the result is no longer convex.
     */
    private fun refine(edges: EdgeMap, q: List<Point>): List<Point>? {
        val lines = (0 until 4).map { i ->
            val a = q[i]; val b = q[(i + 1) % 4]
            val len = dist(a, b)
            if (len < 1 || edges.onBorder(a, b)) return@map Pair(a, b)
            val nx = -(b.y - a.y) / len; val ny = (b.x - a.x) / len
            var best = Pair(a, b); var bestS = -1.0; var bestMove = 0; var bestA = 0; var bestB = 0
            fun tryShift(oa: Int, ob: Int) {
                val a2 = Point(a.x + nx * oa, a.y + ny * oa); val b2 = Point(b.x + nx * ob, b.y + ny * ob)
                val s = edges.along(a2, b2)
                if (s < 0) return
                val move = abs(oa) + abs(ob)
                if (s > bestS + 1e-9 || (s > bestS - 1e-9 && move < bestMove)) {
                    bestS = s; best = Pair(a2, b2); bestMove = move; bestA = oa; bestB = ob
                }
            }
            // Coarse: move the whole side. Fine: tilt it by moving each end a little.
            for (o in -REFINE_PX..REFINE_PX) tryShift(o, o)
            val ca = bestA; val cb = bestB
            for (oa in ca - 2..ca + 2) for (ob in cb - 2..cb + 2) tryShift(oa, ob)
            best
        }
        val out = (0 until 4).map { i -> intersect(lines[(i + 3) % 4], lines[i]) ?: return null }
        if (!isConvex(out)) return null
        return out
    }

/** True if the quad turns the same way at every corner. */
    private fun isConvex(q: List<Point>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

        private fun intersect(l1: Pair<Point, Point>, l2: Pair<Point, Point>): Point? {
        val (p, p2) = l1; val (q, q2) = l2
        val rx = p2.x - p.x; val ry = p2.y - p.y; val sx = q2.x - q.x; val sy = q2.y - q.y
        val d = rx * sy - ry * sx
        if (abs(d) < 1e-9) return null
        val t = ((q.x - p.x) * sy - (q.y - p.y) * sx) / d
        return Point(p.x + t * rx, p.y + t * ry)
    }

    /**
     * Saturation-like plane from a camera frame's chroma planes: distance from neutral gray.
     * [u] and [v] are 8-bit single channel (any size); the result matches their size.
     */
    fun chromaFromUV(u: Mat, v: Mat): Mat {
        val du = Mat(); val dv = Mat(); val out = Mat()
        Core.absdiff(u, org.opencv.core.Scalar(128.0), du)
        Core.absdiff(v, org.opencv.core.Scalar(128.0), dv)
        Core.add(du, dv, out)
        // Paper and most surfaces sit close to neutral, so stretch the low end.
        out.convertTo(out, -1, 4.0, 0.0)
        du.release(); dv.release()
        return out
    }

    private fun quadsFrom(binary: Mat, imgArea: Double): List<Pair<List<Point>, Boolean>> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)
        val found = ArrayList<Pair<List<Point>, Boolean>>()
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
                    if (area >= imgArea * 0.1 && area <= imgArea * 0.985) found += q to false
                    break
                }
                if (a.size < 4) break
                // The page plus a notch of whatever touches it: try every 4 of the 5 or 6 corners.
                // Dropping a corner cuts a new side across the region, so these need the step check.
                if (a.size <= 6) {
                    for (skip in fourOf(a.size)) {
                        val q = a.filterIndexed { i, _ -> i !in skip }
                        val area = polyArea(q)
                        if (area >= imgArea * 0.1 && area <= imgArea * 0.985) found += q to true
                    }
                }
            }
            hull.release(); hullIdx.release()
        }
        contours.forEach { it.release() }
        return found
    }

    /**
     * Quads made from straight edge segments: the longest roughly horizontal and roughly
     * vertical lines are paired up and intersected. Finds pages whose outline never forms a
     * clean contour, such as a book page joined to the facing page or a page touching a
     * same-coloured object.
     */
    private fun segLen(l: DoubleArray) = hypot(l[2] - l[0], l[3] - l[1])
    private fun segAngle(l: DoubleArray) = Math.toDegrees(atan2(l[3] - l[1], l[2] - l[0])).let { if (it < 0) it + 180 else it }
    private fun segLine(l: DoubleArray) = Pair(Point(l[0], l[1]), Point(l[2], l[3]))

    /**
     * Straight edge segments (x1, y1, x2, y2), merged so each line appears once, longest first.
     * [sparseOnly] first drops edges in densely edged areas (fabric, carpet, text), which
     * otherwise make plenty of long chance alignments and crowd out the page's own borders,
     * but also lose a border running right beside such an area.
     */
    private fun houghLines(edges: Mat, sparseOnly: Boolean): List<DoubleArray> {
        val w = edges.cols(); val h = edges.rows()
        val input = if (sparseOnly) {
            val sparse = Mat(); val dense = Mat()
            Imgproc.blur(edges, dense, Size(15.0, 15.0))
            Imgproc.threshold(dense, dense, 255 * MAX_EDGE_DENSITY, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(edges, dense, sparse)
            dense.release()
            sparse
        } else edges
        val segs = Mat()
        Imgproc.HoughLinesP(input, segs, 1.0, Math.PI / 180, 40, 0.12 * min(w, h), 6.0)
        if (sparseOnly) input.release()
        val lines = ArrayList<DoubleArray>()
        for (i in 0 until segs.rows()) lines += segs.get(i, 0)
        segs.release()
        // Merge segments on the same line (same direction, close together) into the longest one,
        // adding up their lengths: a page border with uneven contrast, such as a book's spine,
        // comes out of Hough as several short pieces.
        val len = ::segLen; val angle = ::segAngle
        val kept = ArrayList<DoubleArray>()
        val total = ArrayList<Double>()
        // Short pieces of texture add nothing but cost; the merge is quadratic.
        for (l in lines.sortedByDescending { len(it) }.take(MAX_SEGMENTS)) {
            val a = angle(l)
            val mx = (l[0] + l[2]) / 2; val my = (l[1] + l[3]) / 2
            val same = kept.indexOfFirst { k ->
                val d = abs(angle(k) - a).let { min(it, 180 - it) }
                val nx = -(k[3] - k[1]) / len(k); val ny = (k[2] - k[0]) / len(k)
                d < 6 && abs((mx - k[0]) * nx + (my - k[1]) * ny) < 8
            }
            if (same < 0) { kept += l; total += len(l) } else total[same] += len(l)
        }
        return kept.indices.sortedByDescending { total[it] }.map { kept[it] }
    }

    /**
     * Quads made from straight edge segments: the longest roughly horizontal and roughly
     * vertical lines are paired up and intersected.
     */
    private fun quadsFromLines(ranked: List<DoubleArray>, w: Int, h: Int, imgArea: Double): List<List<Point>> {
        val horiz = ranked.filter { segAngle(it).let { a -> a < 45 || a > 135 } }.take(LINES_PER_AXIS)
        val vert = ranked.filter { segAngle(it).let { a -> a in 45.0..135.0 } }.take(LINES_PER_AXIS)
        val line = ::segLine
        val out = ArrayList<List<Point>>()
        val slack = 0.05 * max(w, h)
        for (i in horiz.indices) for (j in i + 1 until horiz.size) {
            val (top, bottom) = if (horiz[i][1] + horiz[i][3] < horiz[j][1] + horiz[j][3]) Pair(horiz[i], horiz[j]) else Pair(horiz[j], horiz[i])
            for (k in vert.indices) for (m in k + 1 until vert.size) {
                val (left, right) = if (vert[k][0] + vert[k][2] < vert[m][0] + vert[m][2]) Pair(vert[k], vert[m]) else Pair(vert[m], vert[k])
                val q = listOf(
                    intersect(line(top), line(left)), intersect(line(top), line(right)),
                    intersect(line(bottom), line(right)), intersect(line(bottom), line(left)),
                )
                if (q.any { it == null }) continue
                val p = q.filterNotNull()
                if (p.any { it.x < -slack || it.y < -slack || it.x > w + slack || it.y > h + slack }) continue
                val area = polyArea(p)
                if (area < imgArea * 0.1 || area > imgArea * 0.985) continue
                if (!isConvex(p)) continue
                out += p
            }
        }
        return out
    }

    /**
     * Variants of [q] with one side slid outwards, parallel to itself, onto another edge
     * (corners re-intersected with the neighbouring sides), each with the strip it adds.
     * The caller snaps them, which also corrects a small difference in angle.
     */
    private fun grow(q: List<Point>, edges: EdgeMap): List<Pair<List<Point>, List<Point>>> {
        val cx = q.sumOf { it.x } / 4; val cy = q.sumOf { it.y } / 4
        val out = ArrayList<Pair<List<Point>, List<Point>>>()
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]
            val len = dist(a, b)
            if (len < 1) continue
            var nx = -(b.y - a.y) / len; var ny = (b.x - a.x) / len
            if ((a.x - cx) * nx + (a.y - cy) * ny < 0) { nx = -nx; ny = -ny }
            val prev = Pair(q[(i + 3) % 4], a); val next = Pair(b, q[(i + 2) % 4])
            val peaks = ArrayList<Pair<List<Point>, Double>>()
            var before = 0.0; var last: List<Point>? = null; var lastS = 0.0
            var off = 8
            while (true) {
                val sa = Point(a.x + nx * off, a.y + ny * off); val sb = Point(b.x + nx * off, b.y + ny * off)
                val na = intersect(prev, Pair(sa, sb)) ?: break
                val nb = intersect(Pair(sa, sb), next) ?: break
                if (!edges.inside(na.x.roundToInt(), na.y.roundToInt()) || !edges.inside(nb.x.roundToInt(), nb.y.roundToInt())) break
                val sup = edges.along(na, nb)
                // Keep each local peak of support that is high enough to be a border.
                if (last != null && lastS >= MIN_SIDE_SUPPORT && lastS >= before && lastS > sup) peaks += last to lastS
                before = lastS
                lastS = sup
                last = q.toMutableList().also { it[i] = na; it[(i + 1) % 4] = nb }.takeIf { isConvex(it) }
                off += 2
            }
            peaks.sortedByDescending { it.second }.take(2).forEach { (g, _) -> out += g to listOf(a, b, g[(i + 1) % 4], g[i]) }
        }
        return out
    }

    /** The index sets to drop from an n-gon (n = 5 or 6) to leave 4 corners. */
    private fun fourOf(n: Int): List<Set<Int>> =
        if (n == 5) (0 until 5).map { setOf(it) }
        else (0 until 6).flatMap { i -> (i + 1 until 6).map { j -> setOf(i, j) } }

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
