package io.github.swikrutd.swidscanner.imaging

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.github.swikrutd.swidscanner.data.Corner
import io.github.swikrutd.swidscanner.data.DocStore
import io.github.swikrutd.swidscanner.data.Settings
import io.github.swikrutd.swidscanner.ui.Routes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The optional Google ML Kit Document Scanner. It runs its own screens inside Google Play services and hands back
 * cropped JPEG pages, which are copied into a new document like any other scan. Create one per activity, before
 * the activity starts, because it registers the result launcher.
 *
 * [openRoute] opens a screen on top of the current one. The result handler lives here, not in a screen, so a
 * result is never lost if the screen that started the scan is gone.
 */
class MlKitScan(private val activity: ComponentActivity, private val openRoute: (String) -> Unit) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult(), ::onResult)

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short messages for a snackbar. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val _busy = MutableStateFlow(false)

    /** True while the returned pages are being copied into a document. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Starts the scanner. Anything that stops it from starting shows a notice and opens our camera instead. */
    fun start() {
        if (!available(activity)) return fallback("Google scanner is not available. Using the SwidScanner camera.")
        try {
            GmsDocumentScanning.getClient(options()).getStartScanIntent(activity)
                .addOnSuccessListener { sender ->
                    try { launcher.launch(IntentSenderRequest.Builder(sender).build()) }
                    catch (e: Exception) { fallback("Google scanner could not open. Using the SwidScanner camera.") }
                }
                .addOnFailureListener { fallback("Google scanner could not start. Using the SwidScanner camera.") }
        } catch (e: Exception) {
            fallback("Google scanner could not start. Using the SwidScanner camera.")
        }
    }

    private fun fallback(message: String) {
        _notices.tryEmit(message)
        openRoute(Routes.camera())
    }

    private fun onResult(result: ActivityResult) {
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri }
                if (uris.isNullOrEmpty()) fallback("Google scanner returned no pages. Using the SwidScanner camera.")
                else import(uris)
            }
            Activity.RESULT_CANCELED -> {} // The user backed out: stay where they are.
            else -> fallback("Google scanner failed. Using the SwidScanner camera.")
        }
    }

    private fun import(uris: List<Uri>) {
        _busy.value = true
        // DocStore.work outlives the activity, so pages already returned are not dropped on a restart.
        DocStore.work.launch {
            var docId: String? = null
            var failed = 0
            try {
                for (uri in uris) {
                    val bmp = runCatching { Images.decodeUri(activity.applicationContext, uri, Images.CAPTURE_MAX) }.getOrNull()
                    if (bmp == null) { failed++; continue }
                    try {
                        val id = docId ?: DocStore.createDoc().id.also { docId = it }
                        DocStore.addPage(id, bmp, cornersFor(bmp), Settings.defaultFilter)
                    } catch (e: Exception) { failed++ }
                    finally { bmp.recycle() }
                }
            } finally { _busy.value = false }
            val id = docId
            if (id == null) fallback("Could not read the scanned pages. Using the SwidScanner camera.")
            else {
                if (failed > 0) _notices.tryEmit("$failed of ${uris.size} pages could not be added")
                openRoute(Routes.doc(id))
            }
        }
    }

    companion object {
        /** True when Google Play services is installed, enabled and up to date enough to run the scanner. */
        fun available(ctx: Context): Boolean =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS

        fun options(): GmsDocumentScannerOptions = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        private val FULL = listOf(Corner(0f, 0f), Corner(1f, 0f), Corner(1f, 1f), Corner(0f, 1f))

        /**
         * Crop corners for a page Google has already cropped. Our detector finds the paper edge when a margin is
         * left and otherwise nothing. A small hit is more likely a block inside the page than the page, so only an
         * outline covering most of the frame is used. Otherwise the whole frame.
         */
        internal fun cornersFor(page: Bitmap): List<Corner> {
            val found = Images.detectCorners(page) ?: return FULL
            var area = 0f
            for (i in 0 until 4) {
                val a = found[i]; val b = found[(i + 1) % 4]
                area += a.x * b.y - b.x * a.y
            }
            return if (abs(area) / 2f >= MIN_SHARE) found else FULL
        }

        private const val MIN_SHARE = 0.85f
    }
}
