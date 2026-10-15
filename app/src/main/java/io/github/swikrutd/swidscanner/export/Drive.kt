package io.github.swikrutd.swidscanner.export

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * Minimal Google Drive client using the REST API.
 * Uses the drive.file scope, so the app can only see files and folders it created itself.
 */
object Drive {
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.file")
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    suspend fun authorize(ctx: Context): AuthorizationResult =
        try {
            Identity.getAuthorizationClient(ctx).authorize(request()).await()
        } catch (e: ApiException) {
            throw IOException(explain(e), e)
        }

    fun explain(e: ApiException): String = when (e.statusCode) {
        10 -> "Google Drive isn't set up for this build yet. Register the package name and SHA-1 shown in Settings as an Android OAuth client in Google Cloud (see README)."
        7 -> "No internet connection."
        else -> "Google sign-in failed (code ${e.statusCode})."
    }

    suspend fun upload(token: String, folderName: String, file: File, mime: String) = withContext(Dispatchers.IO) {
        val parent = folderId(token, folderName)
        val boundary = "swid" + UUID.randomUUID().toString().replace("-", "")
        val conn = open("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", token).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setChunkedStreamingMode(64 * 1024)
        }
        val meta = JSONObject().put("name", file.name).put("parents", JSONArray().put(parent))
        conn.outputStream.use { o ->
            o.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            o.write("--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(o) }
            o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        readJson(conn)
    }

    private fun folderId(token: String, name: String): String {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = "mimeType='$FOLDER_MIME' and name='$escaped' and trashed=false"
        val find = open(
            "https://www.googleapis.com/drive/v3/files?fields=files(id)&spaces=drive&q=" + URLEncoder.encode(q, "UTF-8"),
            token,
        )
        val files = readJson(find).optJSONArray("files")
        if (files != null && files.length() > 0) return files.getJSONObject(0).getString("id")

        val create = open("https://www.googleapis.com/drive/v3/files?fields=id", token).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        create.outputStream.use { it.write(JSONObject().put("name", name).put("mimeType", FOLDER_MIME).toString().toByteArray()) }
        return readJson(create).getString("id")
    }

    private fun open(url: String, token: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("Authorization", "Bearer $token")
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull() ?: body.take(200)
            throw IOException("Google Drive error $code: $msg")
        }
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    /** SHA-1 of the signing certificate, needed once for the Google Cloud OAuth client. */
    fun signingSha1(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners[0]
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures!![0]
        }
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("unavailable")
}

/**
 * Gets a Drive access token, showing Google's consent screen when needed.
 * Must be created in composition via [rememberDriveAuthorizer].
 */
class DriveAuthorizer internal constructor(private val ctx: Context) {
    internal var pending: CompletableDeferred<String?>? = null
    internal lateinit var launcher: ManagedActivityResultLauncher<IntentSenderRequest, ActivityResult>

    suspend fun token(): String {
        val res = Drive.authorize(ctx)
        if (!res.hasResolution()) return res.accessToken ?: throw IOException("Google didn't return an access token")
        val d = CompletableDeferred<String?>()
        pending = d
        launcher.launch(IntentSenderRequest.Builder(res.pendingIntent!!.intentSender).build())
        return d.await() ?: throw IOException("Google Drive access wasn't granted. If the Google consent screen never appeared, Drive isn't set up for this build yet (see README).")
    }
}

@Composable
fun rememberDriveAuthorizer(): DriveAuthorizer {
    val ctx = LocalContext.current
    val auth = remember { DriveAuthorizer(ctx) }
    auth.launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val token = if (r.resultCode == Activity.RESULT_OK) {
            runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(r.data).accessToken }.getOrNull()
        } else null
        auth.pending?.complete(token)
        auth.pending = null
    }
    return auth
}
