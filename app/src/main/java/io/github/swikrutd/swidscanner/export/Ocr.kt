package io.github.swikrutd.swidscanner.export

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.swikrutd.swidscanner.data.Doc
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Page
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.tasks.await

/** On-device text recognition (Latin script). The model ships inside the APK; no network needed. */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bmp: Bitmap): Text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()

    suspend fun pageText(doc: Doc, page: Page): String {
        val bmp = Images.decodeFile(DocStore.procFile(doc.id, page), 2400)
        return try { recognize(bmp).text } finally { bmp.recycle() }
    }

    /** Text of [pages] (the whole document when null), headed with each page's number in the document. */
    suspend fun docText(doc: Doc, pages: List<Page>? = null, onProgress: (Float) -> Unit = {}): String {
        val ids = pages?.mapTo(HashSet()) { it.id }
        val picked = doc.pages.withIndex().filter { ids == null || it.value.id in ids }
        val parts = picked.mapIndexed { n, (i, p) ->
            val t = pageText(doc, p)
            onProgress((n + 1f) / picked.size)
            if (picked.size > 1) "--- Page ${i + 1} ---\n$t" else t
        }
        return parts.joinToString("\n\n")
    }
}
