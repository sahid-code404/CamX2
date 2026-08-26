package com.sahidcode404.camx.core.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.sahidcode404.camx.core.update.verification.VerifiedApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApkInstaller(private val context: Context) {
    suspend fun requestInstall(verifiedApk: VerifiedApk) {
        val apk = withContext(Dispatchers.IO) { verifiedApk.revalidateForInstall() }
        withContext(Dispatchers.Main.immediate) {
            launchInstaller(apk)
        }
    }

    @Suppress("InlinedApi")
    private fun launchInstaller(apk: java.io.File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.update-files",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
