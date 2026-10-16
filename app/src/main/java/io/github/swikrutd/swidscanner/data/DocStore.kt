package io.github.swikrutd.swidscanner.data

import android.content.Context
import android.graphics.Bitmap
import io.github.swikrutd.swidscanner.core.Filter
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** A crop corner, normalised to 0..1 of the original image. */
data class Corner(val x: Float, val y: Float)

data class Page(
    val id: String,
    /** TL, TR, BR, BL, or null for "use the whole image". */
    val corners: List<Corner>?,
    val filter: Filter,
    /** Clockwise rotation applied after cropping. */
    val rotation: Int,
    /** Bumped on every edit; part of the rendered file name so image caches refresh. */
    val rev: Int,
    /** Signatures and stamps drawn on top of the rendered page, in order. */
    val overlays: List<Overlay> = emptyList(),
)

/**
 * A signature or stamp placed on a page. Position and size are normalised to 0..1 of the
 * rendered page (after crop and rotation), so the result is the same at any resolution.
 */
data class Overlay(
    val id: String,
    val assetId: String,
    val type: AssetKind,
    /** Centre of the overlay. */
    val cx: Float,
    val cy: Float,
    /** Width as a share of the page width. The height follows the asset's aspect ratio. */
    val width: Float,
    /** Clockwise degrees around the centre. */
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    /** Colour that replaces the ink while keeping its alpha, or null to draw the asset as stored. */
    val tint: Int? = null,
)

data class Doc(
    val id: String,
    val name: String,
    val created: Long,
    val updated: Long,
    val pages: List<Page>,
)

/** Page numbers as short ranges: [2, 3, 4, 5] gives "2-5" and [1, 2, 3, 6] gives "1-3,6". */
fun pageRangeLabel(numbers: List<Int>): String {
    val n = numbers.distinct().sorted()
    val parts = ArrayList<String>()
    var i = 0
    while (i < n.size) {
        var j = i
        while (j + 1 < n.size && n[j + 1] == n[j] + 1) j++
        parts += if (j == i) "${n[i]}" else "${n[i]}-${n[j]}"
        i = j + 1
    }
    return parts.joinToString(",")
}

/**
 * Documents live in filesDir/docs/<docId>/ as doc.json plus, per page,
 * the untouched capture (<page>-orig.jpg), the rendered page (<page>-r<rev>.jpg)
 * and a thumbnail (<page>-t<rev>.jpg).
 */
object DocStore {
    private lateinit var root: File
    private val _docs = MutableStateFlow<List<Doc>>(emptyList())
    val docs: StateFlow<List<Doc>> = _docs

    /** Page ids currently being re-rendered. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    /** Runs edits that must finish even when the screen that started them closes. */
    val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val meta = Mutex()
    // Rendering full-size pages is memory heavy, so do one at a time.
    private val render = Mutex()

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "docs").apply { mkdirs() }
        _docs.value = root.listFiles()?.mapNotNull { read(it) }?.sortedByDescending { it.updated } ?: emptyList()
    }

    fun get(id: String): Doc? = _docs.value.firstOrNull { it.id == id }
    fun dir(docId: String) = File(root, docId)
    fun origFile(docId: String, pageId: String) = File(dir(docId), "$pageId-orig.jpg")
    fun procFile(docId: String, p: Page) = File(dir(docId), "${p.id}-r${p.rev}.jpg")
    fun thumbFile(docId: String, p: Page) = File(dir(docId), "${p.id}-t${p.rev}.jpg")

    fun defaultName(): String =
        "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())

    suspend fun createDoc(name: String = defaultName()): Doc = withContext(Dispatchers.IO) {
        meta.withLock {
            val now = System.currentTimeMillis()
            val d = Doc(newId(), name, now, now, emptyList())
            dir(d.id).mkdirs()
            write(d); publish(d)
            d
        }
    }

    /** Stores [image] (already upright) as a new page at the end of the document. */
    suspend fun addPage(docId: String, image: Bitmap, corners: List<Corner>?, filter: Filter): Page =
        withContext(Dispatchers.IO) {
            val page = Page(newId(), corners, filter, 0, 0)
            render.withLock {
                Images.saveJpeg(image, origFile(docId, page.id), 92)
                renderFiles(docId, page, image)
            }
            meta.withLock {
                val d = get(docId) ?: error("Document was deleted")
                val nd = d.copy(pages = d.pages + page, updated = System.currentTimeMillis())
                write(nd); publish(nd)
            }
            page
        }

    suspend fun updatePage(docId: String, pageId: String, change: (Page) -> Page) = withContext(Dispatchers.IO) {
        render.withLock {
            val old = get(docId)?.pages?.firstOrNull { it.id == pageId } ?: return@withLock
            val np = change(old).copy(id = old.id, rev = old.rev + 1)
            _busy.update { it + pageId }
            try {
                renderFiles(docId, np, null)
            } finally {
                _busy.update { it - pageId }
            }
            meta.withLock {
                val d = get(docId) ?: return@withLock
                val nd = d.copy(
                    pages = d.pages.map { if (it.id == pageId) np else it },
                    updated = System.currentTimeMillis(),
                )
                write(nd); publish(nd)
            }
            procFile(docId, old).delete(); thumbFile(docId, old).delete()
        }
    }

    /**
     * Replaces a page's photo with a new one (a retake), keeping its place in the document
     * and its filter. [image] is already upright, so any earlier rotation is dropped.
     */
    suspend fun replaceImage(docId: String, pageId: String, image: Bitmap, corners: List<Corner>?): Page =
        withContext(Dispatchers.IO) {
            render.withLock {
                val old = get(docId)?.pages?.firstOrNull { it.id == pageId } ?: error("Page was deleted")
                val np = old.copy(corners = corners, rotation = 0, rev = old.rev + 1)
                _busy.update { it + pageId }
                try {
                    Images.saveJpeg(image, origFile(docId, pageId), 92)
                    renderFiles(docId, np, image)
                } finally {
                    _busy.update { it - pageId }
                }
                meta.withLock {
                    val d = get(docId) ?: error("Document was deleted")
                    val nd = d.copy(
                        pages = d.pages.map { if (it.id == pageId) np else it },
                        updated = System.currentTimeMillis(),
                    )
                    write(nd); publish(nd)
                }
                procFile(docId, old).delete(); thumbFile(docId, old).delete()
                np
            }
        }

    /** Replaces the page's overlays and re-renders it from the original capture. */
    suspend fun setOverlays(docId: String, pageId: String, overlays: List<Overlay>) =
        updatePage(docId, pageId) { it.copy(overlays = overlays) }

    /** True when any page of any document uses [assetId]. */
    fun usesAsset(assetId: String): Boolean =
        _docs.value.any { d -> d.pages.any { p -> p.overlays.any { it.assetId == assetId } } }

    suspend fun setFilterForAll(docId: String, filter: Filter) {
        get(docId)?.pages?.filter { it.filter != filter }?.forEach { p ->
            updatePage(docId, p.id) { it.copy(filter = filter) }
        }
    }

    suspend fun setFilter(docId: String, pageIds: Collection<String>, filter: Filter) {
        get(docId)?.pages?.filter { it.id in pageIds && it.filter != filter }?.forEach { p ->
            updatePage(docId, p.id) { it.copy(filter = filter) }
        }
    }

    suspend fun deletePages(docId: String, pageIds: Collection<String>) = withContext(Dispatchers.IO) {
        meta.withLock {
            val d = get(docId) ?: return@withLock
            val gone = d.pages.filter { it.id in pageIds }
            if (gone.isEmpty()) return@withLock
            val nd = d.copy(pages = d.pages.filter { it.id !in pageIds }, updated = System.currentTimeMillis())
            write(nd); publish(nd)
            gone.forEach { p -> origFile(docId, p.id).delete(); procFile(docId, p).delete(); thumbFile(docId, p).delete() }
        }
    }

    /**
     * Copies the pages in [pageIds] (in document order) into a new document named after the
     * original and returns it, or null if none of them exist. The original is left alone.
     */
    suspend fun extractPages(docId: String, pageIds: Collection<String>): Doc? = withContext(Dispatchers.IO) {
        // Same lock order as updatePage, so a page that is re-rendering finishes first.
        render.withLock {
            meta.withLock {
                val d = get(docId) ?: return@withLock null
                val picked = d.pages.withIndex().filter { it.value.id in pageIds }
                if (picked.isEmpty()) return@withLock null
                val label = pageRangeLabel(picked.map { it.index + 1 })
                val now = System.currentTimeMillis()
                val id = newId()
                try {
                    dir(id).mkdirs()
                    val pages = picked.map { (_, p) ->
                        val np = p.copy(id = newId())
                        origFile(docId, p.id).copyTo(origFile(id, np.id))
                        procFile(docId, p).copyTo(procFile(id, np))
                        thumbFile(docId, p).copyTo(thumbFile(id, np))
                        np
                    }
                    val nd = Doc(id, "${d.name} (${if (picked.size == 1) "page" else "pages"} $label)", now, now, pages)
                    write(nd); publish(nd)
                    nd
                } catch (e: Exception) {
                    dir(id).deleteRecursively()
                    throw e
                }
            }
        }
    }

    suspend fun deletePage(docId: String, pageId: String) = withContext(Dispatchers.IO) {
        meta.withLock {
            val d = get(docId) ?: return@withLock
            val p = d.pages.firstOrNull { it.id == pageId } ?: return@withLock
            val nd = d.copy(pages = d.pages - p, updated = System.currentTimeMillis())
            write(nd); publish(nd)
            origFile(docId, p.id).delete(); procFile(docId, p).delete(); thumbFile(docId, p).delete()
        }
    }

    suspend fun movePage(docId: String, pageId: String, delta: Int) = withContext(Dispatchers.IO) {
        meta.withLock {
            val d = get(docId) ?: return@withLock
            val i = d.pages.indexOfFirst { it.id == pageId }
            val j = i + delta
            if (i < 0 || j !in d.pages.indices) return@withLock
            val list = d.pages.toMutableList()
            list.add(j, list.removeAt(i))
            val nd = d.copy(pages = list, updated = System.currentTimeMillis())
            write(nd); publish(nd)
        }
    }

    /**
     * Takes [pageIds] out of the document and puts them back as one block so the first of them
     * ends up at [toIndex] in the new order (counted after the removal, clamped to the end).
     * The pages keep the order they had in the document. Writes once.
     */
    suspend fun movePages(docId: String, pageIds: List<String>, toIndex: Int) = withContext(Dispatchers.IO) {
        meta.withLock {
            val d = get(docId) ?: return@withLock
            val ids = pageIds.toSet()
            val moving = d.pages.filter { it.id in ids }
            if (moving.isEmpty()) return@withLock
            val rest = d.pages.filter { it.id !in ids }
            val at = toIndex.coerceIn(0, rest.size)
            val list = rest.take(at) + moving + rest.drop(at)
            if (list.map { it.id } == d.pages.map { it.id }) return@withLock
            val nd = d.copy(pages = list, updated = System.currentTimeMillis())
            write(nd); publish(nd)
        }
    }

    suspend fun rename(docId: String, name: String) = withContext(Dispatchers.IO) {
        meta.withLock {
            val d = get(docId) ?: return@withLock
            val nd = d.copy(name = name.trim().ifEmpty { d.name }, updated = System.currentTimeMillis())
            write(nd); publish(nd)
        }
    }

    suspend fun deleteDoc(docId: String) = withContext(Dispatchers.IO) {
        meta.withLock {
            dir(docId).deleteRecursively()
            _docs.update { list -> list.filter { it.id != docId } }
        }
    }

    private fun renderFiles(docId: String, page: Page, origIn: Bitmap?) {
        val orig = origIn ?: Images.decodeFile(origFile(docId, page.id), 4000)
        val out = Images.renderPage(orig, page)
        if (origIn == null) orig.recycle()
        Images.saveJpeg(out, procFile(docId, page), 90)
        val thumb = Images.scaleDown(out, 480)
        Images.saveJpeg(thumb, thumbFile(docId, page), 80)
        thumb.recycle()
        if (!out.isRecycled) out.recycle()
    }

    private fun publish(d: Doc) {
        _docs.update { list -> (list.filter { it.id != d.id } + d).sortedByDescending { it.updated } }
    }

    fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    // ---- JSON ----

    private fun write(d: Doc) {
        val pages = JSONArray()
        d.pages.forEach { p ->
            pages.put(JSONObject().apply {
                put("id", p.id)
                put("filter", p.filter.name)
                put("rotation", p.rotation)
                put("rev", p.rev)
                p.corners?.let { c ->
                    put("corners", JSONArray().apply { c.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })
                }
                if (p.overlays.isNotEmpty()) put("overlays", JSONArray().apply { p.overlays.forEach { put(overlayJson(it)) } })
            })
        }
        val json = JSONObject().apply {
            put("id", d.id); put("name", d.name); put("created", d.created); put("updated", d.updated)
            put("pages", pages)
        }
        val dir = dir(d.id).apply { mkdirs() }
        val tmp = File(dir, "doc.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(dir, "doc.json"))
    }

    private fun read(dir: File): Doc? = runCatching {
        val j = JSONObject(File(dir, "doc.json").readText())
        val pa = j.getJSONArray("pages")
        val pages = (0 until pa.length()).map { i ->
            val o = pa.getJSONObject(i)
            val c = o.optJSONArray("corners")
            Page(
                id = o.getString("id"),
                corners = c?.let { a -> (0 until 4).map { k -> Corner(a.getDouble(k * 2).toFloat(), a.getDouble(k * 2 + 1).toFloat()) } },
                filter = runCatching { Filter.valueOf(o.getString("filter")) }.getOrDefault(Filter.ORIGINAL),
                rotation = o.optInt("rotation"),
                rev = o.optInt("rev"),
                overlays = o.optJSONArray("overlays")?.let { a -> (0 until a.length()).mapNotNull { k -> readOverlay(a.optJSONObject(k)) } }
                    ?: emptyList(),
            )
        }
        Doc(j.getString("id"), j.getString("name"), j.getLong("created"), j.getLong("updated"), pages)
    }.getOrNull()

    private fun overlayJson(o: Overlay) = JSONObject().apply {
        put("id", o.id); put("asset", o.assetId); put("type", o.type.name)
        put("cx", o.cx.toDouble()); put("cy", o.cy.toDouble()); put("width", o.width.toDouble())
        put("rotation", o.rotation.toDouble()); put("opacity", o.opacity.toDouble())
        o.tint?.let { put("tint", it) }
    }

    /** A damaged overlay is dropped on its own so the rest of the document still loads. */
    private fun readOverlay(o: JSONObject?): Overlay? = o?.let {
        runCatching {
            Overlay(
                id = o.getString("id"),
                assetId = o.getString("asset"),
                type = AssetKind.valueOf(o.getString("type")),
                cx = o.getDouble("cx").toFloat(),
                cy = o.getDouble("cy").toFloat(),
                width = o.getDouble("width").toFloat(),
                rotation = o.optDouble("rotation", 0.0).toFloat(),
                opacity = o.optDouble("opacity", 1.0).toFloat(),
                tint = if (o.has("tint") && !o.isNull("tint")) o.getInt("tint") else null,
            )
        }.getOrNull()
    }
}
