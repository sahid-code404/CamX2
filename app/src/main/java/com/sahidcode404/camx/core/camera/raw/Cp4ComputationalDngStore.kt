package com.sahidcode404.camx.core.camera.raw

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.imaging.interchange.Cp4ComputationalDngReceipt
import com.sahidcode404.camx.core.imaging.interchange.Cp4ComputationalDngWriter
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FusedCfa
import com.sahidcode404.camx.core.imaging.reconstruction.Cp3FusionReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android storage boundary for the CP4 computational negative. The fused sensor raster and immutable
 * CP2 calibration arrive after Camera2/Image ownership has ended. A MediaStore row is published only
 * after the complete DNG has been written; failures delete the pending row instead of exposing a
 * partial file.
 */
internal class Cp4ComputationalDngStore(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val writer = Cp4ComputationalDngWriter()

    suspend fun save(
        captureContext: RawCaptureContext,
        fused: Cp3FusedCfa,
        fusionReport: Cp3FusionReport,
        calibration: Cp2CalibrationBundle,
    ): Cp4SaveReport = withContext(Dispatchers.IO) {
        var receipt: Cp4ComputationalDngReceipt? = null
        val transaction = MediaStoreTransaction(
            insertPending = {
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    pendingValues(captureContext),
                )
            },
            write = { uri ->
                val output = checkNotNull(resolver.openOutputStream(uri, "w")) {
                    "MediaStore returned no CP4 output stream"
                }
                output.use { stream ->
                    receipt = writer.write(
                        fused = fused,
                        fusionReport = fusionReport,
                        calibration = calibration,
                        uniqueCameraModel = uniqueCameraModel(captureContext),
                        orientation = DngOrientation.tiffOrientation(
                            sensorOrientationDegrees = captureContext.sensorOrientationDegrees,
                            lensFacing = captureContext.lensFacing,
                            displayRotationAtShutter = captureContext.displayRotationAtShutter,
                        ),
                        output = stream,
                        maxOutputBytes = MAX_OUTPUT_BYTES,
                    )
                }
            },
            publish = { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    check(resolver.update(uri, values, null, null) == 1) {
                        "MediaStore failed to publish the CP4 DNG row"
                    }
                }
            },
            delete = { uri -> resolver.delete(uri, null, null) },
        )

        val result = try {
            transaction.execute()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (fatal: ThreadDeath) {
            throw fatal
        } catch (failure: Throwable) {
            return@withContext Cp4SaveReport.failed(
                fusionReport = fusionReport,
                calibration = calibration,
                detail = failure.message ?: "CP4 storage transaction failed",
            )
        }

        result.fold(
            onSuccess = { uri ->
                val completed = receipt
                    ?: return@fold Cp4SaveReport.failed(
                        fusionReport = fusionReport,
                        calibration = calibration,
                        detail = "CP4 storage completed without a DNG receipt",
                    )
                Cp4SaveReport.saved(
                    uri = uri.toString(),
                    receipt = completed,
                )
            },
            onFailure = { failure ->
                Cp4SaveReport.failed(
                    fusionReport = fusionReport,
                    calibration = calibration,
                    detail = failure.message ?: "CP4 MediaStore transaction failed",
                )
            },
        )
    }

    private fun pendingValues(context: RawCaptureContext): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName(context))
        put(MediaStore.Images.Media.MIME_TYPE, DNG_MIME_TYPE)
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    private fun fileName(context: RawCaptureContext): String =
        "CamX2_CR_${System.currentTimeMillis()}_${context.captureToken.value}.dng"

    private fun uniqueCameraModel(context: RawCaptureContext): String {
        val raw = "CamX2 ${Build.MANUFACTURER} ${Build.MODEL} ${context.cameraProfileFingerprint.value.take(24)}"
        val printable = buildString(raw.length) {
            raw.forEach { char ->
                append(if (char.code in 0x20..0x7e) char else '_')
            }
        }.trim().ifBlank { "CamX2 Computational RAW" }
        val bytes = printable.toByteArray(Charsets.US_ASCII)
        return if (bytes.size <= MAX_MODEL_BYTES) printable else printable.take(MAX_MODEL_BYTES)
    }

    private companion object {
        const val DNG_MIME_TYPE = "image/x-adobe-dng"
        const val MAX_MODEL_BYTES = 255
        const val MAX_OUTPUT_BYTES = 1024L * 1024L * 1024L
    }
}

data class Cp4SaveReport(
    val success: Boolean,
    val uri: String?,
    val byteCount: Long,
    val dngSha256: String?,
    val cp3OutputSha256: String,
    val calibrationFingerprintSha256: String,
    val contributingFrames: Int,
    val failureDetail: String?,
) {
    init {
        require(byteCount >= 0L)
        require(cp3OutputSha256.length == 64)
        require(calibrationFingerprintSha256.length == 64)
        require(contributingFrames >= 0)
        require(dngSha256 == null || dngSha256.length == 64)
        require(success == (uri != null && byteCount > 0L && dngSha256 != null && failureDetail == null))
        require(!success || contributingFrames >= 2)
    }

    companion object {
        fun saved(uri: String, receipt: Cp4ComputationalDngReceipt) = Cp4SaveReport(
            success = true,
            uri = uri,
            byteCount = receipt.byteCount,
            dngSha256 = receipt.sha256,
            cp3OutputSha256 = receipt.cp3OutputSha256,
            calibrationFingerprintSha256 = receipt.calibrationFingerprintSha256,
            contributingFrames = receipt.includedOrdinals.size,
            failureDetail = null,
        )

        fun failed(
            fusionReport: Cp3FusionReport,
            calibration: Cp2CalibrationBundle,
            detail: String,
        ) = Cp4SaveReport(
            success = false,
            uri = null,
            byteCount = 0L,
            dngSha256 = null,
            cp3OutputSha256 = fusionReport.outputSha256 ?: ZERO_SHA256,
            calibrationFingerprintSha256 = calibration.report.calibrationFingerprintSha256,
            contributingFrames = fusionReport.contributingFrames,
            failureDetail = detail.ifBlank { "CP4 computational DNG save failed" },
        )

        private const val ZERO_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
