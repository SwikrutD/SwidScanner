package io.github.swikrutd.swidscanner

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Settings
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.swikrutd.swidscanner.export.AutoName
import io.github.swikrutd.swidscanner.export.AutoSave
import org.opencv.android.OpenCVLoader

class SwidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
        Settings.init(this)
        DocStore.init(this)
        PDFBoxResourceLoader.init(this)
        AutoSave.start(this)
        AutoName.start(this)
        // Long-press the app icon: Scan opens the camera straight away.
        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(
            ShortcutInfoCompat.Builder(this, "scan")
                .setShortLabel("Scan")
                .setLongLabel("Scan a document")
                .setIcon(IconCompat.createWithResource(this, R.drawable.ic_scan))
                .setIntent(MainActivity.scanIntent(this))
                .build(),
        ))
    }
}
