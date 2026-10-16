package io.github.swikrutd.swidscanner.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class AssetKind(val dir: String, val noun: String, val ext: String) {
    SIGNATURE("signatures", "signature", "swidsig"),
    STAMP("stamps", "stamp", "swidstamp"),
}

data class Asset(
    val id: String,
    val kind: AssetKind,
    val name: String,
    val created: Long,
    /** Width the asset was last placed at, as a share of the page width. New placements start here. */
    val lastWidth: Float,
)

/**
 * The signature and stamp library, shared by all documents. Each asset is a PNG with a
 * transparent background in filesDir/assets/<kind>/<id>.png, listed in filesDir/assets/assets.json.
 */
object AssetStore {
    /** Longest side kept for a stored asset. Plenty for a signature a third of an A4 page wide. */
    const val MAX_SIDE = 1600
    const val DEFAULT_WIDTH = 0.3f

    private lateinit var root: File
    private val _assets = MutableStateFlow<List<Asset>>(emptyList())
    val assets: StateFlow<List<Asset>> = _assets
    private val lock = Mutex()

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "assets").apply { mkdirs() }
        AssetKind.entries.forEach { File(root, it.dir).mkdirs() }
        _assets.value = read().sortedByDescending { it.created }
    }

    fun get(id: String): Asset? = _assets.value.firstOrNull { it.id == id }

    /**
     * The asset's PNG, or null if it is gone. Also finds files of assets that were removed
     * from the library but are still placed on a page (see [delete]).
     */
    fun file(id: String): File? {
        get(id)?.let { a -> return File(File(root, a.kind.dir), "$id.png").takeIf { it.exists() } }
        return AssetKind.entries.map { File(File(root, it.dir), "$id.png") }.firstOrNull { it.exists() }
    }

    /** Stores [bitmap] (transparent background, already cropped) as a new asset. */
    suspend fun add(kind: AssetKind, bitmap: Bitmap, name: String): Asset = withContext(Dispatchers.IO) {
        val a = Asset(newId(), kind, name.trim().ifEmpty { defaultName(kind) }, System.currentTimeMillis(), DEFAULT_WIDTH)
        val out = File(File(root, kind.dir).apply { mkdirs() }, "${a.id}.png")
        val tmp = File(out.path + ".tmp")
        val big = maxOf(bitmap.width, bitmap.height)
        val scaled = if (big <= MAX_SIDE) bitmap else Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * MAX_SIDE / big).coerceAtLeast(1), (bitmap.height * MAX_SIDE / big).coerceAtLeast(1), true,
        )
        FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (scaled !== bitmap) scaled.recycle()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        lock.withLock { save(listOf(a) + _assets.value) }
        a
    }

    suspend fun rename(id: String, name: String) = edit(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    suspend fun setLastWidth(id: String, width: Float) = edit(id) { it.copy(lastWidth = width.coerceIn(0.01f, 1f)) }

    /**
     * Removes the asset from the library. Its PNG is kept while any page still shows it,
     * so deleting a signature never changes a signed document.
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val a = get(id) ?: return@withLock
            save(_assets.value - a)
            if (!DocStore.usesAsset(id)) File(File(root, a.kind.dir), "$id.png").delete()
        }
    }

    private suspend fun edit(id: String, change: (Asset) -> Asset) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (get(id) == null) return@withLock
            save(_assets.value.map { if (it.id == id) change(it).copy(id = it.id, kind = it.kind) else it })
        }
    }

    private fun defaultName(kind: AssetKind) = kind.noun.replaceFirstChar { it.uppercase() } + " " + (_assets.value.count { it.kind == kind } + 1)

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    // ---- JSON ----

    private fun save(list: List<Asset>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id); put("kind", a.kind.name); put("name", a.name)
                put("created", a.created); put("lastWidth", a.lastWidth.toDouble())
            })
        }
        val tmp = File(root, "assets.json.tmp")
        tmp.writeText(JSONObject().put("assets", arr).toString())
        if (!tmp.renameTo(File(root, "assets.json"))) { tmp.copyTo(File(root, "assets.json"), overwrite = true); tmp.delete() }
        _assets.value = list.sortedByDescending { it.created }
    }

    private fun read(): List<Asset> {
        val f = File(root, "assets.json")
        if (!f.exists()) return emptyList()
        val arr = runCatching { JSONObject(f.readText()).getJSONArray("assets") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Asset(
                    id = o.getString("id"),
                    kind = AssetKind.valueOf(o.getString("kind")),
                    name = o.getString("name"),
                    created = o.getLong("created"),
                    lastWidth = o.optDouble("lastWidth", DEFAULT_WIDTH.toDouble()).toFloat(),
                )
            }.getOrNull()
        }
    }
}
