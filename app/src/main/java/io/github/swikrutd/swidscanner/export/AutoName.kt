package io.github.swikrutd.swidscanner.export

import android.content.Context
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.imaging.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Names new documents after the largest line of text on their first page (usually the
 * heading), while "Name from text" is on. Only documents that still have their default
 * "Scan <date>" name are renamed, so a name the user typed is never replaced.
 */
object AutoName {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tried = HashSet<String>()
    private val defaultName = Regex("""Scan \d{4}-\d{2}-\d{2} \d{2}\.\d{2}""")

    /**
     * "<prefix> <date>" with the date written like a new scan's default name, for example
     * "ID card 2026-10-16 14.05". Only "Scan <date>" is renamed from the page text, so a
     * document named this way keeps its name.
     */
    fun dated(prefix: String): String =
        "$prefix " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())

    fun start(ctx: Context) {
        scope.launch {
            var first = true
            DocStore.docs.collect { docs ->
                for (d in docs) {
                    // Documents that existed at startup keep their names.
                    if (first) { tried += d.id; continue }
                    if (d.id in tried || d.pages.isEmpty() || !defaultName.matches(d.name) || !Settings.autoName) continue
                    tried += d.id
                    scope.launch { runCatching { name(d.id) } }
                }
                first = false
            }
        }
    }

    private suspend fun name(docId: String) {
        // Wait for the first page to finish rendering.
        while (DocStore.get(docId)?.pages?.firstOrNull()?.id?.let { it in DocStore.busy.value } == true) delay(300)
        val doc = DocStore.get(docId) ?: return
        val page = doc.pages.firstOrNull() ?: return
        val bmp = Images.decodeFile(DocStore.procFile(docId, page), 1600)
        val text = try { Ocr.recognize(bmp) } finally { bmp.recycle() }
        val title = titleFrom(text.textBlocks.flatMap { b -> b.lines.map { it.text to (it.boundingBox?.height() ?: 0) } }) ?: return
        // The user may have renamed it while text recognition ran.
        if (DocStore.get(docId)?.name?.let { defaultName.matches(it) } == true) DocStore.rename(docId, title)
    }

    /**
     * Picks the title from (line text, line height) pairs: the tallest line with at least three
     * letters, which is the heading on most pages. All-caps headings become Title Case.
     */
    internal fun titleFrom(lines: List<Pair<String, Int>>): String? {
        val (text, _) = lines.filter { (t, _) -> t.count { it.isLetter() } >= 3 }.maxByOrNull { it.second } ?: return null
        var t = text.replace(Regex("\\s+"), " ").trim().trim('-', ':', '.', ',', ';')
        if (t.none { it.isLowerCase() }) {
            t = t.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                .replace(Regex("\"(\\p{Ll})")) { "\"" + it.groupValues[1].uppercase() }
        }
        if (t.length > 50) t = t.take(50).substringBeforeLast(' ').ifEmpty { t.take(50) }
        return t.ifBlank { null }
    }
}
