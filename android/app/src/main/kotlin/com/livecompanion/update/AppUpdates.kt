package com.livecompanion.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * The Android half of "check for updates": installed version, where the APK is cached, and the
 * hand-off to the system package installer (needs "install unknown apps" granted to this app).
 */
object AppUpdates {
    private const val CACHE_DIR = "updates"

    /** `versionName` from the app's own manifest (set in app/build.gradle.kts). */
    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** "0.1.0-test5 (build 5)", plus " - debug build" for a debuggable (locally built) APK. */
    fun installedLabel(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)
        val debug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        "${pi.versionName ?: "0"} (build $code)" + if (debug) " - debug build" else ""
    }.getOrElse { installedVersion(context) }

    /** Blocking; call off the main thread. */
    fun check(context: Context, channel: UpdateCheck.Channel): UpdateCheck.Result =
        UpdateCheck.check(installedVersion(context), channel)

    /** Blocking download into the cache; sweeps older downloads first. */
    fun download(
        context: Context,
        asset: UpdateCheck.Asset,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        dir.listFiles()?.forEach { it.delete() }
        return UpdateCheck.download(asset, File(dir, "LiveCompanion-update.apk"), shouldContinue, onProgress)
    }

    fun canInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** Settings screen where "allow from this source" is turned on for this app. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands [apk] to Android's package installer, which shows its own confirmation. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openInBrowser(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
