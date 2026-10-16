package io.github.swikrutd.swidscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A ".swidsig" (signature) or ".swidstamp" (stamp) file moves an asset between phones. It is a zip
 * with two entries named after the kind: signature.png and signature.json, or stamp.png and
 * stamp.json (transparent PNG; name and created).
 */
object AssetFile {
    private fun png(kind: AssetKind) = "${kind.noun}.png"
    private fun meta(kind: AssetKind) = "${kind.noun}.json"
    /** Refuse entries larger than this so a hostile file can't exhaust memory. */
    private const val MAX_ENTRY = 20L * 1024 * 1024

    /** Writes [asset] to a new file in the shared exports folder and returns it. */
    suspend fun export(ctx: Context, asset: Asset): File = withContext(Dispatchers.IO) {
        val png = AssetStore.file(asset.id) ?: error("The ${asset.kind.noun} file is missing")
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val safe = asset.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { asset.kind.noun.replaceFirstChar { it.uppercase() } }
        val out = File(dir, "$safe.${asset.kind.ext}")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry(png(asset.kind))); png.inputStream().use { it.copyTo(z) }; z.closeEntry()
            z.putNextEntry(ZipEntry(meta(asset.kind)))
            z.write(JSONObject().put("name", asset.name).put("created", asset.created).toString().toByteArray())
            z.closeEntry()
        }
        out
    }

    /** True if the first bytes of [uri] are a zip header. */
    suspend fun looksLikeAssetFile(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { val a = ByteArray(2); it.read(a) == 2 && a[0] == 'P'.code.toByte() && a[1] == 'K'.code.toByte() }
        }.getOrNull() == true
    }

    /** Reads a file of [kind] and adds it to the library. Throws with a short message if the file is not valid. */
    suspend fun import(ctx: Context, uri: Uri, kind: AssetKind): Asset = withContext(Dispatchers.IO) {
        var bytes: ByteArray? = null
        var meta: JSONObject? = null
        var other: AssetKind? = null
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name) {
                        png(kind) -> bytes = readLimited(z, kind)
                        meta(kind) -> meta = runCatching { JSONObject(String(readLimited(z, kind))) }.getOrNull()
                        else -> AssetKind.entries.firstOrNull { it != kind && e.name == png(it) }?.let { other = it }
                    }
                }
            }
        } ?: error("Could not open the file")
        val data = bytes ?: error(
            other?.let { "This is a ${it.noun} file. Add it from the ${it.noun}s library." } ?: "This is not a ${kind.noun} file",
        )
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("This is not a ${kind.noun} file")
        val asset = AssetStore.add(kind, bmp, meta?.optString("name").orEmpty())
        bmp.recycle()
        asset
    }

    private fun readLimited(z: ZipInputStream, kind: AssetKind): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = z.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY) error("This is not a ${kind.noun} file")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** True if [b] has any pixel that is not fully opaque. Samples a small copy, which is enough to tell a cut-out from a photo. */
fun hasTransparency(b: Bitmap): Boolean {
    if (!b.hasAlpha()) return false
    val s = 64
    val small = Bitmap.createScaledBitmap(b, minOf(b.width, s), minOf(b.height, s), false)
    val px = IntArray(small.width * small.height)
    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
    if (small !== b) small.recycle()
    return px.any { (it ushr 24) < 250 }
}
