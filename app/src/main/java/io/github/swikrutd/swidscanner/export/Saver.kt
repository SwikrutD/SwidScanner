package io.github.swikrutd.swidscanner.export

import android.content.Context
import android.net.Uri
import io.github.swikrutd.swidscanner.data.Doc
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Writes exported files to every enabled save location (phone folder and/or Google Drive). */
object Saver {

    /** True if at least one save location is set up and turned on. */
    val hasDestination: Boolean get() = Settings.activeFolder != null || Settings.driveActive

    /** Latest save result per document id, shown on the document screen. */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    fun setStatus(docId: String, text: String) = _status.update { it + (docId to text) }

    /**
     * Copies [files] to the enabled locations. [driveToken] returns an access token, or null
     * if Drive needs the user's consent first. Returns a one-line summary of what happened.
     */
    suspend fun deliver(ctx: Context, files: List<File>, mime: String, replace: Boolean, driveToken: suspend () -> String?): String {
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        Settings.activeFolder?.let { uri ->
            runCatching { Delivery.toFolder(ctx, Uri.parse(uri), files, mime, replace) }
                .onSuccess { done += "\"${Settings.folderName ?: "folder"}\"" }
                .onFailure { failed += "folder: ${it.message}" }
        }
        if (Settings.driveActive) {
            runCatching {
                val token = driveToken() ?: error("Google needs you to allow access again. Tap Save once.")
                files.forEach { Drive.upload(token, Settings.driveFolder, it, mime, replace) }
            }.onSuccess { done += "Google Drive/${Settings.driveFolder}" }
                .onFailure { failed += "Drive: ${it.message}" }
        }
        return buildString {
            if (done.isNotEmpty()) append("Saved to ${done.joinToString(" and ")}.")
            if (failed.isNotEmpty()) { if (isNotEmpty()) append("\n"); append("Failed: ${failed.joinToString("; ")}") }
        }
    }

    /**
     * Saves [doc] as a PDF with the default export settings, replacing the copy saved before,
     * and records the result in [status].
     */
    suspend fun savePdf(ctx: Context, doc: Doc, auto: Boolean, driveToken: suspend () -> String?): String {
        val opts = ExportOptions(Format.PDF, Settings.pageSize, Settings.quality, Settings.searchablePdf, null)
        val files = Exporter.export(ctx, doc, opts)
        val summary = try {
            deliver(ctx, files, Format.PDF.mime, replace = true, driveToken)
        } finally {
            files.firstOrNull()?.parentFile?.deleteRecursively()
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        setStatus(doc.id, (if (auto) "Auto-save $time: " else "$time: ") + summary)
        return summary
    }
}

/**
 * Saves a document a few seconds after it last changed, while auto-save is on. Waits while
 * pages are still rendering or the camera is open, so a batch scan is saved once at the end.
 */
object AutoSave {
    private const val QUIET_MS = 4000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = HashMap<String, Job>()
    private val seen = HashMap<String, Long>()

    /** Set while the camera screen is open. */
    @Volatile var scanning = false

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            var first = true
            DocStore.docs.collect { docs ->
                for (d in docs) {
                    val before = seen.put(d.id, d.updated)
                    // Documents that already existed at startup are not re-saved.
                    if (first || before == d.updated || d.pages.isEmpty()) continue
                    if (Settings.autoSave && Saver.hasDestination) schedule(app, d.id)
                }
                first = false
            }
        }
    }

    private fun schedule(ctx: Context, docId: String) = synchronized(jobs) {
        jobs[docId]?.cancel()
        jobs[docId] = scope.launch {
            delay(QUIET_MS)
            while (scanning || DocStore.get(docId)?.pages?.any { it.id in DocStore.busy.value } == true) delay(1000)
            val doc = DocStore.get(docId) ?: return@launch
            if (!Settings.autoSave || doc.pages.isEmpty()) return@launch
            runCatching { Saver.savePdf(ctx, doc, auto = true) { Drive.silentToken(ctx) } }
                .onFailure { Saver.setStatus(docId, "Auto-save failed: ${it.message}") }
        }
    }
}
