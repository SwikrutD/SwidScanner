package io.github.swikrutd.swidscanner.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Delivery {

    /** Copies [files] into a folder the user granted through the system folder picker. */
    suspend fun toFolder(ctx: Context, tree: Uri, files: List<File>, mime: String) = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(ctx, tree)
        if (dir == null || !dir.canWrite()) error("Can't write to the save folder. Choose it again in Settings.")
        for (f in files) {
            val name = uniqueName(dir, f.name)
            val target = dir.createFile(mime, name) ?: error("Couldn't create $name in the save folder")
            ctx.contentResolver.openOutputStream(target.uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
                ?: error("Couldn't write $name")
        }
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        if (dir.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (dir.findFile("$stem ($n)$ext") != null) n++
        return "$stem ($n)$ext"
    }

    fun folderLabel(ctx: Context, tree: Uri): String =
        DocumentFile.fromTreeUri(ctx, tree)?.name ?: tree.lastPathSegment?.substringAfterLast(':') ?: "Folder"

    fun share(ctx: Context, files: List<File>, mime: String) {
        val uris = files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(ctx: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(intent, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
