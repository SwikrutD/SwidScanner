package io.github.swikrutd.swidscanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.export.Delivery
import io.github.swikrutd.swidscanner.export.Ocr
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(nav: NavController, docId: String, pageId: String) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var text by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    val ids = remember(pageId) { pageId.split(",").toSet() }
    val many = pageId == "all" || ids.size > 1

    LaunchedEffect(docId, pageId) {
        val doc = DocStore.get(docId) ?: return@LaunchedEffect
        text = try {
            if (pageId == "all") Ocr.docText(doc, onProgress = { progress = it })
            else if (ids.size > 1) Ocr.docText(doc, doc.pages.filter { it.id in ids }, onProgress = { progress = it })
            else Ocr.pageText(doc, doc.pages.first { it.id == pageId })
        } catch (e: Exception) {
            error = e.message; ""
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(if (pageId == "all") "Document text" else if (many) "Selected text" else "Page text") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    val t = text
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(t.orEmpty()))
                        scope.launch { snack.showSnackbar("Copied") }
                    }, enabled = !t.isNullOrEmpty()) { Icon(Icons.Outlined.ContentCopy, "Copy") }
                    IconButton(onClick = { Delivery.shareText(ctx, t.orEmpty()) }, enabled = !t.isNullOrEmpty()) {
                        Icon(Icons.Outlined.Share, "Share")
                    }
                },
            )
        },
    ) { pad ->
        val t = text
        Box(Modifier.fillMaxSize().padding(pad).imePadding()) {
            when {
                t == null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Reading text...")
                    Spacer(Modifier.height(12.dp))
                    if (many) LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp))
                    else LinearProgressIndicator(Modifier.width(200.dp))
                }
                t.isBlank() -> Text(
                    error?.let { "Text recognition failed: $it" } ?: "No text found on this page.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                else -> OutlinedTextField(
                    value = t, onValueChange = { text = it },
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    supportingText = { Text("You can fix recognition mistakes here before copying or sharing.") },
                )
            }
        }
    }
}
