package io.github.swikrutd.swidscanner.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.swikrutd.swidscanner.core.Filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PageSize(val label: String) { FIT("Fit image"), A4("A4"), LETTER("Letter") }

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class Quality(val label: String, val maxSide: Int, val jpeg: Int) {
    HIGH("High", 2600, 90),
    MEDIUM("Medium", 1800, 80),
    SMALL("Small", 1200, 65),
}

object Settings {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _theme.value = enumOr(p.getString("theme", null), ThemeMode.SYSTEM)
    }

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)

    /** Light, dark or follow the system. A flow so the whole app recolours as soon as it changes. */
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(v: ThemeMode) {
        p.edit { putString("theme", v.name) }
        _theme.value = v
    }

    var defaultFilter: Filter
        get() = enumOr(p.getString("filter", null), Filter.MAGIC)
        set(v) = p.edit { putString("filter", v.name) }

    var autoCapture: Boolean
        get() = p.getBoolean("auto", true)
        set(v) = p.edit { putBoolean("auto", v) }

    var batchMode: Boolean
        get() = p.getBoolean("batch", false)
        set(v) = p.edit { putBoolean("batch", v) }

    /** Storage Access Framework tree URI of the chosen save folder. */
    var folderUri: String?
        get() = p.getString("folderUri", null)
        set(v) = p.edit { putString("folderUri", v) }

    var folderName: String?
        get() = p.getString("folderName", null)
        set(v) = p.edit { putString("folderName", v) }

    var driveLinked: Boolean
        get() = p.getBoolean("driveLinked", false)
        set(v) = p.edit { putBoolean("driveLinked", v) }

    var driveFolder: String
        get() = p.getString("driveFolder", null) ?: "SwidScanner"
        set(v) = p.edit { putString("driveFolder", v.trim().ifEmpty { "SwidScanner" }) }

    var pageSize: PageSize
        get() = enumOr(p.getString("pageSize", null), PageSize.FIT)
        set(v) = p.edit { putString("pageSize", v.name) }

    var quality: Quality
        get() = enumOr(p.getString("quality", null), Quality.MEDIUM)
        set(v) = p.edit { putString("quality", v.name) }

    var searchablePdf: Boolean
        get() = p.getBoolean("searchable", true)
        set(v) = p.edit { putBoolean("searchable", v) }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def
}
