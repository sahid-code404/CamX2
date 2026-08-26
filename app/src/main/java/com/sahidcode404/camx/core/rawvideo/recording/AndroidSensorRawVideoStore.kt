package com.sahidcode404.camx.core.rawvideo.recording

import android.content.Context
import android.os.Environment
import com.sahidcode404.camx.core.rawvideo.container.CxrbWriterConfig
import com.sahidcode404.camx.core.rawvideo.container.StorageCapabilityDeclaration
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle-scoped app storage factory. It performs no camera work and requests no broad storage permission. */
class AndroidSensorRawVideoStore(context: Context) : SensorRawVideoSpoolFactory {
    private val appContext = context.applicationContext
    private val sequence = AtomicLong(0L)

    override fun create(maxFrameBytes: Long, queueFrames: Int): CxrbSensorRawVideoSpool {
        val root = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(appContext.filesDir, "raw-video")
        val directory = File(root, "CamX2/RAW").apply {
            require(isDirectory || mkdirs()) { "CamX2 RAW-video storage directory is unavailable" }
        }
        val usable = directory.usableSpace
        require(usable > M10RawVideoLimits.STORAGE_RESERVE_BYTES + 1024L * 1024L) {
            "Insufficient free storage for a bounded RAW-video transaction"
        }
        val maxFileBytes = usable - M10RawVideoLimits.STORAGE_RESERVE_BYTES
        val id = sequence.incrementAndGet()
        val output = File(directory, "CamX2-RAW-${System.currentTimeMillis()}-$id.cxrb")
        output.requireEmptyRegularTarget()
        val config = CxrbWriterConfig(
            storage = StorageCapabilityDeclaration(
                storageClass = "android-app-external-files",
                maxFileBytes = maxFileBytes,
                declaredSustainedWriteBytesPerSecond = null,
                supportsDurableSync = true,
                supports64BitOffsets = true,
            ),
            maxFrameBytes = maxFrameBytes,
            maxMetadataBytes = 16 * 1024,
            maxSegmentRecords = M10RawVideoLimits.DEFAULT_SEGMENT_RECORDS,
        )
        return CxrbSensorRawVideoSpool(
            outputFile = output,
            writerConfig = config,
            queueFrames = queueFrames,
            segmentRecordLimit = M10RawVideoLimits.DEFAULT_SEGMENT_RECORDS,
        )
    }
}
