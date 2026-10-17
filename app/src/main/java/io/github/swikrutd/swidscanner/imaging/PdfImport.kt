package io.github.swikrutd.swidscanner.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import io.github.swikrutd.swidscanner.core.Filter
import io.github.swikrutd.swidscanner.data.DocStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Imports a PDF as a new document. Each PDF page is rendered to an image and stored like a scanned page, so
 * rotating, reordering, signing, stamping, filters, OCR and export all work on it. Text and links in the PDF are
 * flattened to pixels. The work runs on [DocStore.work], so it finishes even if the screen that started it closes.
 */
object PdfImport {
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short messages for a snackbar. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Ids of finished imports, for the UI to open. */
    val opened: SharedFlow<String> = _opened.asSharedFlow()

    private val _busy = MutableStateFlow(false)

    /** True while a PDF is being rendered into a document. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Renders the PDF at [uri] into a new document, then emits its id on [opened]. */
    fun start(ctx: Context, uri: Uri) {
        val app = ctx.applicationContext
        if (!_busy.compareAndSet(false, true)) {
            _notices.tryEmit("Another PDF is still importing")
            return
        }
        DocStore.work.launch {
            var docId: String? = null
            try {
                val result = run(app, uri) { docId = it }
                result.error?.let { _notices.tryEmit(it) }
                if (result.failed > 0) _notices.tryEmit("${result.failed} of ${result.total} pages could not be read")
            } catch (e: Exception) {
                _notices.tryEmit("Could not import the PDF")
            } finally {
                _busy.value = false
            }
            docId?.let { _opened.tryEmit(it) }
        }
    }

    private class Result(val total: Int = 0, val failed: Int = 0, val error: String? = null)

    private suspend fun run(ctx: Context, uri: Uri, onDoc: (String) -> Unit): Result {
        val fd = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) { null } ?: return Result(error = "Could not open the PDF")
        fd.use {
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                return Result(error = "This PDF is password protected. Remove the password first")
            } catch (e: Exception) {
                return Result(error = "This file is not a readable PDF")
            }
            renderer.use { r ->
                val total = r.pageCount
                if (total == 0) return Result(error = "This PDF has no pages")
                val doc = DocStore.createDoc(docName(ctx, uri))
                onDoc(doc.id)
                var failed = 0
                for (i in 0 until total) {
                    try {
                        val bmp = renderPage(r, i)
                        try {
                            // Original keeps crisp digital PDFs unaltered. Whole page, no edge detection.
                            DocStore.addPage(doc.id, bmp, null, Filter.ORIGINAL)
                        } finally { bmp.recycle() }
                    } catch (e: Exception) { failed++ }
                }
                if (failed == total) {
                    DocStore.deleteDoc(doc.id)
                    return Result(total, failed, "Could not read any page of this PDF")
                }
                return Result(total, failed)
            }
        }
    }

    /** Page [index] on a white background, about 200 dpi but never past [Images.CAPTURE_MAX] on the long side. */
    private fun renderPage(r: PdfRenderer, index: Int): Bitmap = r.openPage(index).use { page ->
        // Page size is in points (1/72 inch).
        val scale = min(DPI / 72f, Images.CAPTURE_MAX / max(page.width, page.height).toFloat())
        val w = max(1, (page.width * scale).roundToInt())
        val h = max(1, (page.height * scale).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }

    private fun docName(ctx: Context, uri: Uri): String {
        val shown = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        return shown?.substringAfterLast('/')?.removeSuffix(".pdf")?.removeSuffix(".PDF")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DocStore.defaultName()
    }

    private const val DPI = 200f
}
