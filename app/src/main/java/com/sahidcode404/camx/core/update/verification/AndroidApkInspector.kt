package com.sahidcode404.camx.core.update.verification

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

class AndroidApkInspector(private val context: Context) {
    internal fun verifiedUpdateDirectory(): File =
        File(context.cacheDir, DevOtaTrust.VERIFIED_UPDATE_RELATIVE_DIRECTORY).canonicalFile

    fun inspectInstalled(): InstalledAppIdentity {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            signingCertificateFlags(),
        )
        val signers = currentSigners(info)
        check(signers.size == 1) { "Installed development app must have exactly one current signer" }
        return InstalledAppIdentity(
            applicationId = info.packageName,
            versionCode = versionCode(info),
            signingCertSha256 = sha256Hex(signers.single().toByteArray().inputStream()),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    fun inspect(apk: File): DownloadedApkIdentity {
        require(apk.isFile) { "APK does not exist" }
        require(apk.length() in 1..DevOtaTrust.MAX_APK_BYTES) { "APK size is outside bounds" }
        @Suppress("DEPRECATION")
        val info = checkNotNull(
            context.packageManager.getPackageArchiveInfo(
                apk.path,
                signingCertificateFlags(),
            ),
        ) {
            "Android could not inspect the downloaded APK"
        }
        val signers = currentSigners(info)
        check(signers.size == 1) { "Development APK must have exactly one current signer" }
        return DownloadedApkIdentity(
            applicationId = info.packageName,
            versionCode = versionCode(info),
            versionName = checkNotNull(info.versionName) { "APK version name is missing" },
            minSdk = declaredMinSdk(info),
            sha256 = apk.inputStream().use(::sha256Hex),
            signingCertSha256 = sha256Hex(signers.single().toByteArray().inputStream()),
        )
    }

    @Suppress("DEPRECATION", "InlinedApi")
    private fun signingCertificateFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Array<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            checkNotNull(info.signingInfo) { "APK signing metadata is missing" }.apkContentsSigners
        } else {
            checkNotNull(info.signatures) { "APK signing metadata is missing" }
        }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }

    private fun declaredMinSdk(info: PackageInfo): Int {
        val applicationInfo = checkNotNull(info.applicationInfo) { "APK application metadata is missing" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            applicationInfo.minSdkVersion
        } else {
            // API 23 does not expose ApplicationInfo.minSdkVersion. The fixed development channel
            // accepts only API-23 artifacts, and PackageManager must still parse the candidate APK.
            DevOtaTrust.APPLICATION_MIN_SDK
        }
    }

    private fun sha256Hex(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
