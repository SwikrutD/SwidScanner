package io.github.swikrutd.swidscanner

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Settings
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
    }
}
