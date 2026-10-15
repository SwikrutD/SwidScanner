package io.github.swikrutd.swidscanner.core

import org.opencv.core.Point
import kotlin.math.hypot
import kotlin.math.max

/**
 * Steadies the live page outline. Single-frame detection can flip between similar
 * candidates (page, page plus shadow, table edge) and drop out for a frame or two.
 * The tracker only switches to a different quad once it wins a vote over the last
 * few frames, smooths small movements and holds the last quad through short dropouts.
 * Pure Kotlin so the desktop harness can measure it on recorded frames.
 */
class QuadTracker(
    /** Two quads are "the same" if no corner moved further than this, as a fraction of the frame diagonal. */
    private val matchTol: Double = 0.05,
    /** How many of the last [window] detections must agree before a new quad is shown. */
    private val votes: Int = 3,
    private val window: Int = 6,
    /** Keep showing the last quad this long after detection stops agreeing with it. */
    private val holdMs: Long = 300,
) {
    private val history = ArrayDeque<List<Point>?>()
    private var shown: List<Point>? = null
    private var lastSeen = 0L

    fun reset() {
        history.clear(); shown = null
    }

    /** Feeds one frame's raw detection (TL, TR, BR, BL in frame pixels, or null) and returns the quad to display. */
    fun update(q: List<Point>?, width: Int, height: Int, timeMs: Long): List<Point>? {
        val diag = hypot(width.toDouble(), height.toDouble())
        val tol = matchTol * diag
        history.addLast(q)
        if (history.size > window) history.removeFirst()
        fun near(a: List<Point>, b: List<Point>) = maxCornerDist(a, b) < tol

        val cur = shown
        if (q != null && cur != null && near(q, cur)) {
            shown = smooth(cur, q, diag)
            lastSeen = timeMs
            return shown
        }
        if (q != null) {
            val support = history.count { it != null && near(it, q) }
            val curSupport = if (cur == null) 0 else history.count { it != null && near(it, cur) }
            if (support >= votes && support > curSupport) {
                shown = q
                lastSeen = timeMs
                return shown
            }
        }
        if (cur != null && timeMs - lastSeen > holdMs) shown = null
        return shown
    }

    /**
     * Exponential smoothing whose weight follows the size of the move: sub-pixel noise on a
     * still page is averaged away, while a real move is followed within a frame or two.
     */
    private fun smooth(cur: List<Point>, q: List<Point>, diag: Double): List<Point> {
        val d = (0 until 4).sumOf { hypot(cur[it].x - q[it].x, cur[it].y - q[it].y) } / 4 / diag
        val a = (0.3 + 0.7 * d / 0.02).coerceAtMost(1.0)
        return List(4) { Point(cur[it].x + a * (q[it].x - cur[it].x), cur[it].y + a * (q[it].y - cur[it].y)) }
    }

    private fun maxCornerDist(a: List<Point>, b: List<Point>): Double {
        var m = 0.0
        for (i in 0 until 4) m = max(m, hypot(a[i].x - b[i].x, a[i].y - b[i].y))
        return m
    }
}
