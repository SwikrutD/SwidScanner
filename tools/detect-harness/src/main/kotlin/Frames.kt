import io.github.swikrutd.swidscanner.core.DocProcessing
import io.github.swikrutd.swidscanner.core.QuadTracker
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.hypot

/** One recorded live frame from the app's debug frame dump (ui/FrameDump.kt). */
class Frame(val dir: File, val name: String, val meta: JSONObject) {
    val timestampNs = meta.getLong("timestampNs")
    val deviceQuad: List<Point>? = parseQuad(meta.opt("quad"))
    fun gray(): Mat = Imgcodecs.imread(File(dir, "${name}_gray.png").path, Imgcodecs.IMREAD_GRAYSCALE)
    fun color(): Mat? = File(dir, "$name.jpg").takeIf { it.exists() }?.let { Imgcodecs.imread(it.path) }
}

fun parseQuad(v: Any?): List<Point>? {
    val a = v as? JSONArray ?: return null
    return List(4) { val p = a.getJSONArray(it); Point(p.getDouble(0), p.getDouble(1)) }
}

fun loadFrames(dir: File): List<Frame> =
    dir.listFiles { f -> f.name.matches(Regex("f\\d{4}\\.json")) }!!.sorted()
        .map { Frame(dir, it.nameWithoutExtension, JSONObject(it.readText())) }

/**
 * Per-frame truth from a single "quad" or from "keys" interpolated linearly (held before the first and after the last key).
 * "none" lists inclusive [from, to] frame ranges where no page should be shown (the page is cut off by the frame).
 */
fun loadTruth(j: JSONObject, n: Int): List<List<Point>?> {
    val none = j.optJSONArray("none")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { r -> r.getInt(0)..r.getInt(1) } } }.orEmpty()
    fun blank(i: Int) = none.any { i in it }
    if (j.has("quad")) return List(n) { if (blank(it)) null else parseQuad(j.get("quad"))!! }
    val keysObj = j.optJSONObject("keys") ?: return List(n) { null }
    val keys = keysObj.keySet().map { it.toInt() to parseQuad(keysObj.get(it))!! }.sortedBy { it.first }
    return List(n) { i ->
        val after = keys.indexOfFirst { it.first >= i }
        when {
            blank(i) -> null
            after == 0 -> keys[0].second
            after < 0 -> keys.last().second
            else -> {
                val (i0, q0) = keys[after - 1]; val (i1, q1) = keys[after]
                val t = (i - i0).toDouble() / (i1 - i0)
                List(4) { Point(q0[it].x + t * (q1[it].x - q0[it].x), q0[it].y + t * (q1[it].y - q0[it].y)) }
            }
        }
    }
}

/** Mean corner distance between two ordered quads. */
fun quadDist(a: List<Point>, b: List<Point>) = (0 until 4).sumOf { hypot(a[it].x - b[it].x, a[it].y - b[it].y) } / 4

class Metrics(val frames: Int, val found: Int, val jitter: Double, val jumps: Int, val err: Double?, val hits: Int, val wrong: Int) {
    override fun toString() = "found %3d/%-3d  jitter %5.2f%%  jumps %3d%s".format(
        found, frames, jitter, jumps,
        err?.let { "  err %5.2f%%  correct %3d%%  wrong %3d".format(it, hits * 100 / frames, wrong) } ?: "",
    )
}

/**
 * jitter: mean corner movement between consecutive frames that both have a quad, as % of the frame diagonal.
 * jumps: consecutive frames whose quads differ by more than 5% of the diagonal, or that switch between found and not found.
 * err: mean corner distance to the hand-checked truth (truth.json in the session folder), % of diagonal,
 *   over frames where both have a quad. See [loadTruth] for the format.
 * correct: share of all frames that match the truth: a quad within 3% of it, or no quad where none is expected.
 * wrong: frames showing an outline more than 6% off the truth, or one where none is expected. These are
 *   what looks chaotic on the phone; a missed frame only looks like a short dropout.
 */
fun measure(quads: List<List<Point>?>, diag: Double, truth: List<List<Point>?>?): Metrics {
    var moveSum = 0.0; var moves = 0; var jumps = 0
    for (i in 1 until quads.size) {
        val a = quads[i - 1]; val b = quads[i]
        if (a != null && b != null) {
            val d = quadDist(a, b) / diag * 100
            moveSum += d; moves++
            if (d > 5) jumps++
        } else if ((a == null) != (b == null)) jumps++
    }
    var hits = 0; var wrong = 0
    val errs = ArrayList<Double>()
    if (truth != null) for (i in quads.indices) {
        val q = quads[i]; val t = truth[i]
        when {
            q == null && t == null -> hits++
            q == null -> {}
            t == null -> wrong++
            else -> {
                val e = quadDist(q, t) / diag * 100
                errs += e
                if (e < 3.0) hits++
                if (e > 6.0) wrong++
            }
        }
    }
    return Metrics(quads.size, quads.count { it != null }, if (moves > 0) moveSum / moves else 0.0, jumps,
        if (truth == null) null else if (errs.isEmpty()) Double.NaN else errs.average(), hits, wrong)
}

/** Replays every session under [root] (or [root] itself) through each detector and prints metrics. */
fun replay(root: File, outDir: File?, detectors: (List<Frame>) -> Map<String, List<List<Point>?>>) {
    val sessions = if (File(root, "session.json").exists()) listOf(root)
    else root.listFiles { f -> File(f, "session.json").exists() }!!.sorted()
    for (s in sessions) {
        val frames = loadFrames(s)
        if (frames.isEmpty()) continue
        val size = frames[0].meta.getJSONArray("uprightSize")
        val diag = hypot(size.getDouble(0), size.getDouble(1))
        val truth = File(s, "truth.json").takeIf { it.exists() }?.let { loadTruth(JSONObject(it.readText()), frames.size) }
        val note = File(s, "note.txt").takeIf { it.exists() }?.readText()?.trim() ?: ""
        println("== ${s.name} ${frames.size} frames  $note")
        println("  %-10s %s".format("device", measure(frames.map { it.deviceQuad }, diag, truth)))
        detectNanos = 0
        val results = detectors(frames)
        println("  chroma detect %.1f ms/frame".format(detectNanos / 1e6 / frames.size))
        for ((name, quads) in results) println("  %-10s %s".format(name, measure(quads, diag, truth)))
        if (outDir != null) drawSession(s, frames, results, truth, File(outDir, s.name))
    }
}

private val colors = listOf(Scalar(0.0, 220.0, 0.0), Scalar(255.0, 160.0, 0.0), Scalar(0.0, 160.0, 255.0), Scalar(255.0, 0.0, 255.0))

/** Writes one overlay per frame: each detector in its own colour, truth in white. */
private fun drawSession(s: File, frames: List<Frame>, results: Map<String, List<List<Point>?>>, truth: List<List<Point>?>?, out: File) {
    out.mkdirs()
    // Contact sheet of 8 evenly spaced frames, for a quick look at the whole session.
    val picks = (0 until 8).map { it * (frames.size - 1) / 7 }.toSet()
    val tiles = ArrayList<Mat>()
    frames.forEachIndexed { i, f ->
        val img = f.color() ?: Mat().also { Imgproc.cvtColor(f.gray(), it, Imgproc.COLOR_GRAY2BGR) }
        truth?.get(i)?.let { poly(img, it, Scalar(255.0, 255.0, 255.0), 1) }
        results.values.forEachIndexed { k, quads -> quads[i]?.let { poly(img, it, colors[k % colors.size], 2) } }
        var y = 18.0
        results.keys.forEachIndexed { k, n -> Imgproc.putText(img, n, Point(8.0, y), 0, 0.5, colors[k % colors.size], 1); y += 18 }
        Imgcodecs.imwrite(File(out, "${f.name}.jpg").path, img)
        if (i in picks) {
            val t = Mat(); Imgproc.resize(img, t, org.opencv.core.Size(240.0, 320.0))
            Imgproc.putText(t, f.name, Point(6.0, 312.0), 0, 0.5, Scalar(0.0, 255.0, 255.0), 1)
            tiles += t
        }
        img.release()
    }
    while (tiles.size < 8) tiles += Mat.zeros(320, 240, tiles[0].type())
    val rows = listOf(tiles.subList(0, 4), tiles.subList(4, 8)).map { r -> Mat().also { org.opencv.core.Core.hconcat(r, it) } }
    val sheet = Mat(); org.opencv.core.Core.vconcat(rows, sheet)
    Imgcodecs.imwrite(File(out.parentFile, "sheet_${s.name}.jpg").path, sheet)
}

private fun poly(img: Mat, q: List<Point>, c: Scalar, t: Int) {
    for (i in 0 until 4) Imgproc.line(img, q[i], q[(i + 1) % 4], c, t)
    Imgproc.circle(img, q[0], 4, c, -1)
}

/** The detector exactly as the app ran it live before stabilisation: luminance only, every frame independent. */
fun rawGray(frames: List<Frame>): List<List<Point>?> = frames.map { f ->
    val g = f.gray()
    DocProcessing.detect(g, live = true).also { g.release() }
}

/** Luminance plus a chroma plane rebuilt from the colour frame at half resolution, like the camera's U and V planes. */
var detectNanos = 0L

fun withChroma(frames: List<Frame>): List<List<Point>?> = frames.map { f ->
    val g = f.gray()
    val c = f.color()
    val chroma = c?.let {
        val yuv = Mat(); Imgproc.cvtColor(it, yuv, Imgproc.COLOR_BGR2YUV)
        val planes = ArrayList<Mat>(); org.opencv.core.Core.split(yuv, planes)
        val u = Mat(); val v = Mat()
        Imgproc.resize(planes[1], u, org.opencv.core.Size(), 0.5, 0.5, Imgproc.INTER_AREA)
        Imgproc.resize(planes[2], v, org.opencv.core.Size(), 0.5, 0.5, Imgproc.INTER_AREA)
        DocProcessing.chromaFromUV(u, v).also { _ -> listOf(yuv, u, v, it).plus(planes).forEach { m -> m.release() } }
    }
    val t0 = System.nanoTime()
    DocProcessing.detect(g, chroma, live = true).also { detectNanos += System.nanoTime() - t0; g.release(); chroma?.release() }
}

/** Runs per-frame detections through the app's QuadTracker, as the camera screen does. */
fun tracked(frames: List<Frame>, raw: List<List<Point>?>): List<List<Point>?> {
    val t = QuadTracker()
    val size = frames[0].meta.getJSONArray("uprightSize")
    return frames.indices.map { t.update(raw[it], size.getInt(0), size.getInt(1), frames[it].timestampNs / 1_000_000) }
}
