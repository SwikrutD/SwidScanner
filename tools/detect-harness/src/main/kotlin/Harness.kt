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
        replay(java.io.File(args[1]), args.getOrNull(2)?.let { java.io.File(it) }, { frames ->
            val raw = rawGray(frames)
            val chroma = withChroma(frames)
            mapOf("gray" to raw, "gray+trk" to tracked(frames, raw), "chroma" to chroma, "chroma+trk" to tracked(frames, chroma))
        })
        return
    }
    if (args[0] == "cands") {
        // cands <frame dir> <frame name>: prints every candidate quad and its per-side edge support.
        val f = loadFrames(java.io.File(args[1])).first { it.name == args[2] }
        DocProcessing.debugCandidate = { q, sides, border ->
            println("  ${q.map { "(${it.x.toInt()},${it.y.toInt()})" }} sides=${sides.map { "%.2f".format(it) }} border=$border")
        }
        DocProcessing.debugSupport = { Imgcodecs.imwrite("support.png", it) }
        val g = f.gray()
        println("gray -> ${DocProcessing.detect(g, live = true)?.map { "(${it.x.toInt()},${it.y.toInt()})" }}")
        println("chroma -> ${withChroma(listOf(f))[0]?.map { "(${it.x.toInt()},${it.y.toInt()})" }}")
        return
    }
    if (args[0] == "book") {
        // book <frames dir> <out>: book mode split on 4 frames per session, drawn into one sheet per session.
        val out = java.io.File(args[2]).apply { mkdirs() }
        val sessions = java.io.File(args[1]).listFiles { f -> java.io.File(f, "session.json").exists() }!!.sorted()
        for (s in sessions) {
            val frames = loadFrames(s)
            val tiles = (0 until 4).map { frames[it * (frames.size - 1) / 3] }.map { f ->
                val img = f.color()!!
                val b = DocProcessing.splitBook(img)
                Imgproc.line(img, b.spineTop, b.spineBottom, Scalar(0.0, 0.0, 255.0), 2)
                for ((q, c) in listOf(b.left to Scalar(0.0, 220.0, 0.0), b.right to Scalar(255.0, 160.0, 0.0)))
                    for (i in 0 until 4) Imgproc.line(img, q[i], q[(i + 1) % 4], c, 2)
                img
            }
            val sheet = Mat(); org.opencv.core.Core.hconcat(tiles, sheet)
            Imgcodecs.imwrite(java.io.File(out, "book_${s.name}.jpg").path, sheet)
        }
        return
    }
    if (args[0] == "grid") {
        // grid <image> <out> [x,y x,y x,y x,y]: overlays a 20 px grid (labels every 100) and an optional quad, for hand labelling truth.
        val img = Imgcodecs.imread(args[1])
        for (x in 0 until img.cols() step 20) Imgproc.line(img, org.opencv.core.Point(x.toDouble(), 0.0), org.opencv.core.Point(x.toDouble(), img.rows().toDouble()), if (x % 100 == 0) Scalar(0.0, 255.0, 255.0) else Scalar(90.0, 90.0, 90.0), 1)
        for (y in 0 until img.rows() step 20) Imgproc.line(img, org.opencv.core.Point(0.0, y.toDouble()), org.opencv.core.Point(img.cols().toDouble(), y.toDouble()), if (y % 100 == 0) Scalar(0.0, 255.0, 255.0) else Scalar(90.0, 90.0, 90.0), 1)
        for (x in 0 until img.cols() step 100) for (y in 0 until img.rows() step 100)
            Imgproc.putText(img, "$x,$y", org.opencv.core.Point(x + 2.0, y + 12.0), 0, 0.35, Scalar(0.0, 255.0, 255.0), 1)
        if (args.size >= 7) {
            val q = args.toList().subList(3, 7).map { s: String -> s.split(",").let { org.opencv.core.Point(it[0].toDouble(), it[1].toDouble()) } }
            for (i in 0 until 4) Imgproc.line(img, q[i], q[(i + 1) % 4], Scalar(255.0, 0.0, 255.0), 1)
        }
        val big = Mat(); Imgproc.resize(img, big, Size(), 2.0, 2.0, Imgproc.INTER_NEAREST)
        Imgcodecs.imwrite(args[2], big)
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
