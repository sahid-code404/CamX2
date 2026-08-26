package com.sahidcode404.camx.core.camera.raw

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.sahidcode404.camx.core.camera.diagnostics.DngWriteFailure
import com.sahidcode404.camx.core.camera.diagnostics.MediaStoreFailure
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Android MediaStore + DngCreator boundary for one already-paired sensor RAW image.
 *
 * Camera ownership never crosses this class: it receives immutable shutter identity, immutable
 * characteristics/result metadata and one image whose ownership remains with the caller. The writer
 * creates a pending MediaStore row, writes the DNG, publishes it only after success, and deletes the
 * row on every recoverable failure through [MediaStoreTransaction].
 */
internal class AndroidDngWriter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun write(
        context: RawCaptureContext,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
    ): RawCaptureOutcome = withContext(Dispatchers.IO) {
        var writtenBytes = 0L
        val transaction = MediaStoreTransaction(
            insertPending = {
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    pendingValues(context),
                )
            },
            write = { uri ->
                try {
                    val output = checkNotNull(resolver.openOutputStream(uri, "w")) {
                        "MediaStore returned no output stream"
                    }
                    output.use { rawOutput ->
                        val counted = CountingOutputStream(rawOutput)
                        DngCreator(characteristics, result).use { creator ->
                            creator.setOrientation(
                                DngOrientation.tiffOrientation(
                                    sensorOrientationDegrees = context.sensorOrientationDegrees,
                                    lensFacing = context.lensFacing,
                                    displayRotationAtShutter = context.displayRotationAtShutter,
                                ),
                            )
                            creator.writeImage(counted, image)
                        }
                        counted.flush()
                        writtenBytes = counted.byteCount
                    }
                    check(writtenBytes > 0L) { "DNG writer produced an empty output" }
                } catch (failure: Throwable) {
                    throw DngEncodingException(failure)
                }
            },
            publish = { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    check(resolver.update(uri, values, null, null) == 1) {
                        "MediaStore failed to publish pending DNG row"
                    }
                }
            },
            delete = { uri -> resolver.delete(uri, null, null) },
        )

        val resultRow = try {
            transaction.execute()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (fatal: ThreadDeath) {
            throw fatal
        } catch (failure: Throwable) {
            return@withContext RawCaptureOutcome.Failed(
                if (failure is DngEncodingException) {
                    DngWriteFailure(failure.cause?.message ?: "DNG encoding failed")
                } else {
                    MediaStoreFailure(failure.message ?: "MediaStore transaction failed")
                },
            )
        }

        resultRow.fold(
            onSuccess = { uri -> RawCaptureOutcome.Saved(uri.toString(), writtenBytes) },
            onFailure = { failure ->
                val mapped = if (failure is DngEncodingException) {
                    DngWriteFailure(failure.cause?.message ?: "DNG encoding failed")
                } else {
                    MediaStoreFailure(failure.message ?: "MediaStore transaction failed")
                }
                RawCaptureOutcome.Failed(mapped)
            },
        )
    }

    private fun pendingValues(context: RawCaptureContext): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName(context))
        put(MediaStore.Images.Media.MIME_TYPE, DNG_MIME_TYPE)
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + "/Camera",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    private fun fileName(context: RawCaptureContext): String =
        "CamX2_${System.currentTimeMillis()}_${context.captureToken.value}.dng"

    private class DngEncodingException(cause: Throwable) : Exception(cause)

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var byteCount: Long = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            byteCount = Math.addExact(byteCount, 1L)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            byteCount = Math.addExact(byteCount, length.toLong())
        }
    }

    private companion object {
        const val DNG_MIME_TYPE = "image/x-adobe-dng"
    }
}
