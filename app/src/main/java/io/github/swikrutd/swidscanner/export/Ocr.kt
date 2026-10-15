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

    suspend fun docText(doc: Doc, onProgress: (Float) -> Unit = {}): String {
        val parts = doc.pages.mapIndexed { i, p ->
            val t = pageText(doc, p)
            onProgress((i + 1f) / doc.pages.size)
            if (doc.pages.size > 1) "--- Page ${i + 1} ---\n$t" else t
        }
        return parts.joinToString("\n\n")
    }
}
