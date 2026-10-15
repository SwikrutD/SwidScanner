package io.github.swikrutd.swidscanner.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.swikrutd.swidscanner.BuildConfigInfo
import io.github.swikrutd.swidscanner.core.Filter
import io.github.swikrutd.swidscanner.data.PageSize
import io.github.swikrutd.swidscanner.data.Quality
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.data.ThemeMode
import io.github.swikrutd.swidscanner.export.Drive
import io.github.swikrutd.swidscanner.export.rememberDriveAuthorizer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val drive = rememberDriveAuthorizer()

    var filter by remember { mutableStateOf(Settings.defaultFilter) }
    var auto by remember { mutableStateOf(Settings.autoCapture) }
    var batch by remember { mutableStateOf(Settings.batchMode) }
    var folderName by remember { mutableStateOf(Settings.folderName.takeIf { Settings.folderUri != null }) }
    var linked by remember { mutableStateOf(Settings.driveLinked) }
    var driveFolder by remember { mutableStateOf(Settings.driveFolder) }
    var driveMsg by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf(false) }
    var pageSize by remember { mutableStateOf(Settings.pageSize) }
    var quality by remember { mutableStateOf(Settings.quality) }
    var searchable by remember { mutableStateOf(Settings.searchablePdf) }
    val theme by Settings.theme.collectAsState()

    var saveFolder by remember { mutableStateOf(Settings.saveToFolder) }
    var saveDrive by remember { mutableStateOf(Settings.saveToDrive) }
    var autoSave by remember { mutableStateOf(Settings.autoSave) }
    var autoName by remember { mutableStateOf(Settings.autoName) }
    val pickFolder = rememberFolderPicker { folderName = Settings.folderName; saveFolder = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Scanning")
            Text("Default filter", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                items(Filter.entries) { f ->
                    FilterChip(selected = f == filter, onClick = { filter = f; Settings.defaultFilter = f }, label = { Text(f.label) })
                }
            }
            SwitchRow("Auto-capture", "Takes the photo once the page is steady for a second", auto) { auto = it; Settings.autoCapture = it }
            SwitchRow("Batch mode", "Keep scanning page after page, review at the end", batch) { batch = it; Settings.batchMode = it }
            SwitchRow(
                "Name from text",
                "Names a new document after the largest text on its first page, such as a heading. Off: Scan and the date",
                autoName,
            ) { autoName = it; Settings.autoName = it }

            HorizontalDivider()
            Section("Save locations")
            Text(
                "Scans stay inside the app. The Save button on a document writes it as a PDF to every location turned on here (both, if both are on). Share always works too.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Phone folder")
                    Text(folderName ?: "Not set", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(saveFolder && folderName != null, { saveFolder = it; Settings.saveToFolder = it }, enabled = folderName != null)
            }
            Row(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(if (folderName == null) "Choose" else "Change") }
                if (folderName != null) {
                    TextButton(onClick = {
                        Settings.folderUri?.let { old ->
                            runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                        }
                        Settings.folderUri = null; Settings.folderName = null; folderName = null
                    }) { Text("Remove") }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Google Drive")
                    Text(if (linked) "Linked" else "Not linked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(saveDrive && linked, { saveDrive = it; Settings.saveToDrive = it }, enabled = linked)
            }
            Row(Modifier.padding(start = 36.dp)) {
                if (linked) {
                    OutlinedButton(onClick = {
                        Settings.driveLinked = false; linked = false
                        driveMsg = "Unlinked. To revoke access completely, remove SwidScanner at myaccount.google.com/permissions."
                    }) { Text("Unlink") }
                } else {
                    Button(enabled = !linking, onClick = {
                        linking = true; driveMsg = null
                        scope.launch {
                            try {
                                drive.token()
                                Settings.driveLinked = true; linked = true
                                driveMsg = "Linked. Exports will upload to the \"${Settings.driveFolder}\" folder in your Drive."
                            } catch (e: Exception) {
                                driveMsg = e.message
                            } finally { linking = false }
                        }
                    }) { Text("Link") }
                }
            }
            if (linked) {
                OutlinedTextField(
                    value = driveFolder,
                    onValueChange = { driveFolder = it; if (it.isNotBlank()) Settings.driveFolder = it },
                    label = { Text("Drive folder name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            driveMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            SwitchRow(
                "Auto-save",
                "Saves each document as a PDF to the locations above a few seconds after you scan or edit it, replacing the earlier copy",
                autoSave,
            ) { autoSave = it; Settings.autoSave = it }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Google Drive needs a one-time setup in Google Cloud for your build (see the README). You'll need these two values:",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CopyRow("Package", ctx.packageName) { clipboard.setText(AnnotatedString(it)) }
                CopyRow("SHA-1", Drive.signingSha1(ctx)) { clipboard.setText(AnnotatedString(it)) }
            }

            HorizontalDivider()
            Section("Export defaults")
            Text("PDF page size", style = MaterialTheme.typography.labelLarge)
            Segmented(PageSize.entries, pageSize, { it.label }) { pageSize = it; Settings.pageSize = it }
            Text("Quality", style = MaterialTheme.typography.labelLarge)
            Segmented(Quality.entries, quality, { it.label }) { quality = it; Settings.quality = it }
            SwitchRow("Searchable PDF", "Add an invisible OCR text layer to PDFs", searchable) { searchable = it; Settings.searchablePdf = it }

            HorizontalDivider()
            Section("Appearance")
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            Segmented(ThemeMode.entries, theme, { it.label }) { Settings.setTheme(it) }

            HorizontalDivider()
            Section("About")
            Text(
                "SwidScanner ${BuildConfigInfo.versionName(ctx)}. Open source under the MIT License. " +
                    "Scans and text recognition stay on your phone unless you save to Google Drive.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun SwitchRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = { onCopy(value) }) { Icon(Icons.Outlined.ContentCopy, "Copy $label") }
    }
}
