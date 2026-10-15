package io.github.swikrutd.swidscanner.ui

import android.content.Context
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(value.text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Adds picked gallery images as pages. Creates a document when [docId] is null. Returns the doc id. */
suspend fun importImages(ctx: Context, uris: List<Uri>, docId: String?): String? = withContext(Dispatchers.Default) {
    if (uris.isEmpty()) return@withContext docId
    val id = docId ?: DocStore.createDoc().id
    for (uri in uris) {
        val bmp = runCatching { Images.decodeUri(ctx, uri, Images.CAPTURE_MAX) }.getOrNull() ?: continue
        val corners = Images.detectCorners(bmp)
        DocStore.addPage(id, bmp, corners, Settings.defaultFilter)
        bmp.recycle()
    }
    id
}

object Routes {
    const val HOME = "home"
    const val QR = "qr"
    const val SETTINGS = "settings"
    fun camera(docId: String? = null) = if (docId == null) "camera" else "camera?docId=$docId"
    fun crop(docId: String, pageId: String, thenDoc: Boolean = false) = "crop/$docId/$pageId?thenDoc=$thenDoc"
    fun doc(docId: String) = "doc/$docId"
    fun page(docId: String, pageId: String) = "page/$docId/$pageId"
    fun ocr(docId: String, pageId: String = "all") = "ocr/$docId/$pageId"
}
