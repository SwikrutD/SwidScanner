import io.github.swikrutd.swidscanner.core.DocProcessing
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

/** Mean corner distance between two ordered quads. */
fun quadDist(a: List<Point>, b: List<Point>) = (0 until 4).sumOf { hypot(a[it].x - b[it].x, a[it].y - b[it].y) } / 4

class Metrics(val frames: Int, val found: Int, val jitter: Double, val jumps: Int, val err: Double?, val errFrames: Int) {
    override fun toString() = "found %3d/%-3d  jitter %5.2f%%  jumps %2d%s".format(
        found, frames, jitter, jumps,
        err?.let { "  err %5.2f%% (%d)".format(it, errFrames) } ?: "",
    )
}

/**
 * jitter: mean corner movement between consecutive frames that both have a quad, as % of the frame diagonal.
 * jumps: consecutive frames whose quads differ by more than 5% of the diagonal, or that switch between found and not found.
 * err: mean corner distance to the hand-checked truth quad (truth.json in the session folder), % of diagonal.
 */
fun measure(quads: List<List<Point>?>, diag: Double, truth: List<Point>?): Metrics {
    var moveSum = 0.0; var moves = 0; var jumps = 0
    for (i in 1 until quads.size) {
        val a = quads[i - 1]; val b = quads[i]
        if (a != null && b != null) {
            val d = quadDist(a, b) / diag * 100
            moveSum += d; moves++
            if (d > 5) jumps++
        } else if ((a == null) != (b == null)) jumps++
    }
    val errs = if (truth == null) emptyList() else quads.filterNotNull().map { quadDist(it, truth) / diag * 100 }
    return Metrics(quads.size, quads.count { it != null }, if (moves > 0) moveSum / moves else 0.0, jumps,
        if (truth == null) null else if (errs.isEmpty()) Double.NaN else errs.average(), errs.size)
}

/** Replays every session under [root] (or [root] itself) through each detector and prints metrics. */
fun replay(root: File, outDir: File?, detectors: Map<String, (List<Frame>) -> List<List<Point>?>>) {
    val sessions = if (File(root, "session.json").exists()) listOf(root)
    else root.listFiles { f -> File(f, "session.json").exists() }!!.sorted()
    for (s in sessions) {
        val frames = loadFrames(s)
        if (frames.isEmpty()) continue
        val size = frames[0].meta.getJSONArray("uprightSize")
        val diag = hypot(size.getDouble(0), size.getDouble(1))
        val truth = File(s, "truth.json").takeIf { it.exists() }?.let { parseQuad(JSONObject(it.readText()).get("quad")) }
        val note = File(s, "note.txt").takeIf { it.exists() }?.readText()?.trim() ?: ""
        println("== ${s.name} ${frames.size} frames  $note")
        println("  %-10s %s".format("device", measure(frames.map { it.deviceQuad }, diag, truth)))
        val results = detectors.mapValues { (_, d) -> d(frames) }
        for ((name, quads) in results) println("  %-10s %s".format(name, measure(quads, diag, truth)))
        if (outDir != null) drawSession(s, frames, results, truth, File(outDir, s.name))
    }
}

private val colors = listOf(Scalar(0.0, 220.0, 0.0), Scalar(255.0, 160.0, 0.0), Scalar(0.0, 160.0, 255.0))

/** Writes one overlay per frame: each detector in its own colour, truth in white. */
private fun drawSession(s: File, frames: List<Frame>, results: Map<String, List<List<Point>?>>, truth: List<Point>?, out: File) {
    out.mkdirs()
    // Contact sheet of 8 evenly spaced frames, for a quick look at the whole session.
    val picks = (0 until 8).map { it * (frames.size - 1) / 7 }.toSet()
    val tiles = ArrayList<Mat>()
    frames.forEachIndexed { i, f ->
        val img = f.color() ?: Mat().also { Imgproc.cvtColor(f.gray(), it, Imgproc.COLOR_GRAY2BGR) }
        truth?.let { poly(img, it, Scalar(255.0, 255.0, 255.0), 1) }
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

/** The detector exactly as the app runs it live today: luminance only, every frame independent. */
fun rawGray(frames: List<Frame>): List<List<Point>?> = frames.map { f ->
    val g = f.gray()
    DocProcessing.detect(g).also { g.release() }
}
