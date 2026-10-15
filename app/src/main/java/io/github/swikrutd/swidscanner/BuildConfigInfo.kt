package io.github.swikrutd.swidscanner

import android.content.Context

object BuildConfigInfo {
    /** Debug and bench builds: timing logs and opening a screen from adb. */
    val isDev = BuildConfig.BUILD_TYPE != "release"

    fun versionName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
}
