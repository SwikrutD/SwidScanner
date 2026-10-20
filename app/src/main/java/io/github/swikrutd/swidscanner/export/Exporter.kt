package io.github.swikrutd.swidscanner.export

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import io.github.swikrutd.swidscanner.data.Doc
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Page
import io.github.swikrutd.swidscanner.data.pageRangeLabel
import io.github.swikrutd.swidscanner.data.PageSize
import io.github.swikrutd.swidscanner.data.Quality
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.imaging.Images
import io.github.swikrutd.swidscanner.imaging.Watermark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

enum class Format(val label: String, val mime: String) {
    PDF("PDF", "application/pdf"),
    JPEG("Images", "image/jpeg"),
    TEXT("Text", "text/plain"),
}

data class ExportOptions(
    val format: Format,
    val pageSize: PageSize,
    val quality: Quality,
    val searchable: Boolean,
    val password: String?,
)

object Exporter {

    /**
     * Writes the export into a fresh cache folder and returns the files. [pages] limits the export
     * to those pages (always in document order); null means the whole document. A subset adds a
     * suffix such as "_pages_2-5" to the file names.
     */
    suspend fun export(
        ctx: Context, doc: Doc, o: ExportOptions, pages: List<Page>? = null, onProgress: (Float) -> Unit = {},
    ): List<File> =
        withContext(Dispatchers.IO) {
            val picked = pick(doc, pages)
            require(picked.isNotEmpty()) { "There are no pages to export" }
            val outDir = File(ctx.cacheDir, "exports/${System.currentTimeMillis()}").apply { mkdirs() }
            val base = safeName(doc.name) + nameSuffix(doc, picked)
            when (o.format) {
                Format.PDF -> listOf(pdf(ctx, doc, picked, o, File(outDir, "$base.pdf"), onProgress))
                Format.JPEG -> picked.mapIndexed { i, p ->
                    val name = if (picked.size == 1) "$base.jpg" else "${base}_%02d.jpg".format(i + 1)
                    val f = File(outDir, name)
                    val bmp = Images.decodeFile(DocStore.procFile(doc.id, p), o.quality.maxSide)
                    val marked = if (Settings.watermark) Watermark.apply(bmp) else bmp
                    Images.saveJpeg(marked, f, o.quality.jpeg)
                    if (marked !== bmp) marked.recycle()
                    bmp.recycle()
                    onProgress((i + 1f) / picked.size)
                    f
                }
                Format.TEXT -> listOf(File(outDir, "$base.txt").apply { writeText(Ocr.docText(doc, picked, onProgress)) })
            }
        }

    private suspend fun pdf(ctx: Context, doc: Doc, pages: List<Page>, o: ExportOptions, out: File, onProgress: (Float) -> Unit): File {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val font = PDType1Font.HELVETICA
        val encodable = HashMap<Char, Boolean>()
        fun clean(s: String) = s.filter { c ->
            encodable.getOrPut(c) { runCatching { font.encode(c.toString()) }.isSuccess }
        }

        PDDocument().use { pdf ->
            pages.forEachIndexed { i, p ->
                val bmp = Images.decodeFile(DocStore.procFile(doc.id, p), o.quality.maxSide)
                // OCR below reads the clean bitmap; only the embedded image carries the mark.
                val marked = if (Settings.watermark) Watermark.apply(bmp) else bmp
                val bytes = ByteArrayOutputStream().also { marked.compress(Bitmap.CompressFormat.JPEG, o.quality.jpeg, it) }.toByteArray()
                if (marked !== bmp) marked.recycle()
                val img = JPEGFactory.createFromByteArray(pdf, bytes)
                val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
                val landscape = iw > ih
                val box = when (o.pageSize) {
                    PageSize.FIT -> if (landscape) PDRectangle(842f, 842f * ih / iw) else PDRectangle(595f, 595f * ih / iw)
                    PageSize.A4 -> if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                    PageSize.LETTER -> if (landscape) PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width) else PDRectangle.LETTER
                }
                val page = PDPage(box)
                pdf.addPage(page)
                val s = min(box.width / iw, box.height / ih)
                val dw = iw * s; val dh = ih * s
                val x0 = (box.width - dw) / 2; val y0 = (box.height - dh) / 2

                // Run OCR before opening the content stream (it suspends).
                val lines = if (o.searchable) runCatching { Ocr.recognize(bmp) }.getOrNull()
                    ?.textBlocks?.flatMap { it.lines }.orEmpty() else emptyList()

                PDPageContentStream(pdf, page).use { cs ->
                    cs.drawImage(img, x0, y0, dw, dh)
                    if (lines.isNotEmpty()) {
                        // Invisible text placed over each recognised line makes the PDF searchable and selectable.
                        cs.beginText()
                        cs.setRenderingMode(RenderingMode.NEITHER)
                        for (line in lines) {
                            val r = line.boundingBox ?: continue
                            val text = clean(line.text).trim()
                            if (text.isEmpty()) continue
                            val unitWidth = font.getStringWidth(text) / 1000f
                            if (unitWidth <= 0f) continue
                            val size = (r.width() * s / unitWidth).coerceIn(1f, 300f)
                            cs.setFont(font, size)
                            val tx = x0 + r.left * s
                            val ty = y0 + (ih - r.bottom) * s + r.height() * s * 0.2f
                            cs.setTextMatrix(Matrix.getTranslateInstance(tx, ty))
                            runCatching { cs.showText(text) }
                        }
                        cs.endText()
                    }
                }
                bmp.recycle()
                onProgress((i + 1f) / pages.size)
            }
            if (!o.password.isNullOrEmpty()) {
                val policy = StandardProtectionPolicy(o.password, o.password, AccessPermission())
                policy.encryptionKeyLength = 128
                pdf.protect(policy)
            }
            pdf.save(out)
        }
        return out
    }

    /** The pages of [doc] named in [pages], in document order; the whole document when null. */
    private fun pick(doc: Doc, pages: List<Page>?): List<Page> {
        if (pages == null) return doc.pages
        val ids = pages.mapTo(HashSet()) { it.id }
        return doc.pages.filter { it.id in ids }
    }

    /** "_pages_2-5" for a subset, empty when [picked] is the whole document. */
    private fun nameSuffix(doc: Doc, picked: List<Page>): String {
        if (picked.size == doc.pages.size) return ""
        val ids = picked.mapTo(HashSet()) { it.id }
        val nums = doc.pages.indices.filter { doc.pages[it].id in ids }.map { it + 1 }
        return (if (nums.size == 1) "_page_" else "_pages_") + pageRangeLabel(nums)
    }

    fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifEmpty { "Scan" }
}
