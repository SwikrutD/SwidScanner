package io.github.swikrutd.swidscanner.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.swikrutd.swidscanner.data.Doc
import io.github.swikrutd.swidscanner.data.PageSize
import io.github.swikrutd.swidscanner.data.Quality
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.export.Delivery
import io.github.swikrutd.swidscanner.export.Drive
import io.github.swikrutd.swidscanner.export.ExportOptions
import io.github.swikrutd.swidscanner.export.Exporter
import io.github.swikrutd.swidscanner.export.Format
import io.github.swikrutd.swidscanner.export.rememberDriveAuthorizer
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(doc: Doc, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val drive = rememberDriveAuthorizer()

    var format by remember { mutableStateOf(Format.PDF) }
    var pageSize by remember { mutableStateOf(Settings.pageSize) }
    var quality by remember { mutableStateOf(Settings.quality) }
    var searchable by remember { mutableStateOf(Settings.searchablePdf) }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }
    var folderUri by remember { mutableStateOf(Settings.folderUri) }

    fun options() = ExportOptions(format, pageSize, quality, searchable && format == Format.PDF, password.ifBlank { null })

    suspend fun build(): List<File> {
        Settings.pageSize = pageSize; Settings.quality = quality; Settings.searchablePdf = searchable
        progress = 0f
        return Exporter.export(ctx, doc, options()) { progress = it }
    }

    fun save() {
        working = true; status = null
        scope.launch {
            val done = mutableListOf<String>()
            val failed = mutableListOf<String>()
            try {
                val files = build()
                val mime = format.mime
                folderUri?.let { uri ->
                    runCatching { Delivery.toFolder(ctx, Uri.parse(uri), files, mime) }
                        .onSuccess { done += "\"${Settings.folderName ?: "folder"}\"" }
                        .onFailure { failed += "folder: ${it.message}" }
                }
                if (Settings.driveLinked) {
                    runCatching {
                        val token = drive.token()
                        files.forEach { Drive.upload(token, Settings.driveFolder, it, mime) }
                    }.onSuccess { done += "Google Drive/${Settings.driveFolder}" }
                        .onFailure { failed += "Drive: ${it.message}" }
                }
                status = buildString {
                    if (done.isNotEmpty()) append("Saved to ${done.joinToString(" and ")}.")
                    if (failed.isNotEmpty()) { if (isNotEmpty()) append("\n"); append("Failed: ${failed.joinToString("; ")}") }
                }
            } catch (e: Exception) {
                status = "Export failed: ${e.message}"
            } finally {
                working = false
            }
        }
    }

    // With no destination set, the first Save asks for a folder and remembers it.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            Settings.folderUri = uri.toString()
            Settings.folderName = Delivery.folderLabel(ctx, uri)
            folderUri = uri.toString()
            save()
        }
    }

    fun share() {
        working = true; status = null
        scope.launch {
            try {
                Delivery.share(ctx, build(), format.mime)
            } catch (e: Exception) {
                status = "Export failed: ${e.message}"
            } finally { working = false }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Save or share", style = MaterialTheme.typography.titleLarge)

            Segmented(Format.entries, format, { it.label }) { format = it }

            if (format != Format.TEXT) {
                Label("Quality")
                Segmented(Quality.entries, quality, { it.label }) { quality = it }
            }
            if (format == Format.PDF) {
                Label("Page size")
                Segmented(PageSize.entries, pageSize, { it.label }) { pageSize = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Searchable PDF")
                        Text("Adds an invisible text layer so you can search and copy text", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(searchable, { searchable = it })
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Password (optional)") },
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(if (showPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (format == Format.TEXT) {
                Text("Recognises the text on every page and saves it as a .txt file.", style = MaterialTheme.typography.bodyMedium)
            }

            val dest = buildList {
                Settings.folderName?.takeIf { folderUri != null }?.let { add("folder \"$it\"") }
                if (Settings.driveLinked) add("Google Drive/${Settings.driveFolder}")
            }
            Text(
                if (dest.isEmpty()) "No save location set yet. Save will ask you to pick a folder."
                else "Save goes to ${dest.joinToString(" and ")}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (working) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { share() }, enabled = !working, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, null); Text("  Share")
                }
                Button(
                    onClick = { if (folderUri == null && !Settings.driveLinked) pickFolder.launch(null) else save() },
                    enabled = !working, modifier = Modifier.weight(1f),
                ) { Icon(Icons.Outlined.Save, null); Text("  Save") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(o), maxLines = 1) }
        }
    }
}
