package com.sahidcode404.camx.core.rawvideo.recording

import android.hardware.camera2.CaptureResult
import android.media.ImageFormat
import com.sahidcode404.camx.core.camera.acquisition.AcquisitionPlaneDescriptor
import com.sahidcode404.camx.core.camera.acquisition.AcquisitionSourceApi
import com.sahidcode404.camx.core.camera.acquisition.AcquisitionTimebase
import com.sahidcode404.camx.core.camera.acquisition.CalibrationEvidence
import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterDigest
import com.sahidcode404.camx.core.camera.acquisition.MosaicSensorSamples
import com.sahidcode404.camx.core.camera.acquisition.PublicSourceFormat
import com.sahidcode404.camx.core.camera.acquisition.RepresentationDescriptor
import com.sahidcode404.camx.core.camera.acquisition.SamplePacking
import com.sahidcode404.camx.core.camera.acquisition.SensorPixelMode
import com.sahidcode404.camx.core.camera.acquisition.TimebaseEvidence
import com.sahidcode404.camx.core.camera.acquisition.toAcquisitionIdentity
import com.sahidcode404.camx.core.camera.model.RawCaptureContext
import com.sahidcode404.camx.core.rawvideo.container.FrameOrdinal
import com.sahidcode404.camx.core.rawvideo.container.PackedNoneFrame
import com.sahidcode404.camx.core.rawvideo.container.RawVideoGap
import com.sahidcode404.camx.core.rawvideo.container.RawVideoMetadataEntry
import java.security.MessageDigest

internal data class SensorRawVideoFrameBatch(
    val gapBefore: RawVideoGap?,
    val frame: PackedNoneFrame,
)

/** Copies one exact paired Camera2 RAW_SENSOR frame into the canonical padding-free M1 raster. */
internal class SensorRawVideoFrameAssembler(
    private val context: RawCaptureContext,
    private val providerEpoch: Long,
    private val profile: SensorRawVideoProfile,
) {
    private var baseFrameNumber: Long? = null
    private var lastFrameNumber: Long? = null

    init { require(providerEpoch > 0L) }

    fun assemble(
        pair: DetachedRawVideoPair,
        hostTimestampNs: Long,
    ): SensorRawVideoFrameBatch {
        require(hostTimestampNs > 0L)
        val image = pair.takeImage()
        try {
            require(image.format == ImageFormat.RAW_SENSOR) { "M10 received non-RAW_SENSOR image evidence" }
            require(image.width == profile.rawSize.width && image.height == profile.rawSize.height) {
                "M10 RAW image dimensions changed inside the recording epoch"
            }
            require(image.timestamp == pair.timestampNs) { "M10 Image timestamp diverged from its exact pair" }
            require(pair.result.get(CaptureResult.SENSOR_TIMESTAMP) == pair.timestampNs) {
                "M10 CaptureResult timestamp diverged from its exact pair"
            }
            require(image.planes.size == 1) { "M10 public RAW_SENSOR requires exactly one plane" }

            val base = baseFrameNumber ?: pair.frameNumber.also { baseFrameNumber = it }
            val previous = lastFrameNumber
            require(pair.frameNumber >= base) { "M10 Camera2 frame number regressed before the recording base" }
            if (previous != null) require(pair.frameNumber > previous) { "M10 Camera2 frame numbers must strictly increase" }
            val ordinalValue = (pair.frameNumber - base).toULong()
            val ordinal = FrameOrdinal(ordinalValue)
            val gap = if (previous != null && pair.frameNumber > previous + 1L) {
                val firstMissing = (previous + 1L - base).toULong()
                val missingCount = (pair.frameNumber - previous - 1L).toULong()
                RawVideoGap(
                    firstMissingOrdinal = FrameOrdinal(firstMissing),
                    missingCount = missingCount,
                    reason = "camera2-frame-number-gap",
                    discontinuity = true,
                )
            } else {
                null
            }
            lastFrameNumber = pair.frameNumber

            val plane = image.planes[0]
            val meaningfulRowBytesLong = Math.multiplyExact(
                profile.rawSize.width.toLong(),
                M10RawVideoLimits.RAW_SENSOR_BYTES_PER_PIXEL,
            )
            require(meaningfulRowBytesLong <= Int.MAX_VALUE.toLong())
            val meaningfulRowBytes = meaningfulRowBytesLong.toInt()
            require(plane.pixelStride == M10RawVideoLimits.RAW_SENSOR_BYTES_PER_PIXEL.toInt()) {
                "M10 RAW_SENSOR pixel stride is not the public 16-bit unpacked layout"
            }
            require(plane.rowStride >= meaningfulRowBytes) {
                "M10 RAW_SENSOR row stride is smaller than a meaningful row"
            }
            val sourceRequired = Math.addExact(
                Math.multiplyExact((profile.rawSize.height - 1).toLong(), plane.rowStride.toLong()),
                meaningfulRowBytesLong,
            )
            val source = plane.buffer.duplicate()
            source.clear()
            require(sourceRequired <= source.capacity().toLong()) {
                "M10 RAW source buffer is shorter than its declared row layout"
            }
            val canonicalBytesLong = Math.multiplyExact(meaningfulRowBytesLong, profile.rawSize.height.toLong())
            require(canonicalBytesLong <= Int.MAX_VALUE.toLong())
            val canonical = ByteArray(canonicalBytesLong.toInt())
            repeat(profile.rawSize.height) { row ->
                val sourceOffset = Math.multiplyExact(row.toLong(), plane.rowStride.toLong())
                val destinationOffset = Math.multiplyExact(row.toLong(), meaningfulRowBytesLong)
                require(sourceOffset <= Int.MAX_VALUE.toLong() && destinationOffset <= Int.MAX_VALUE.toLong())
                source.position(sourceOffset.toInt())
                source.get(canonical, destinationOffset.toInt(), meaningfulRowBytes)
            }

            val descriptor = RepresentationDescriptor(
                representation = MosaicSensorSamples,
                sourceFormat = PublicSourceFormat.RAW_SENSOR,
                packing = SamplePacking.UNPACKED_16_LE,
                storedBits = 16,
                effectiveBits = profile.effectiveBits,
                size = profile.rawSize,
                activeArea = profile.activeArea,
                planeDescriptors = listOf(
                    AcquisitionPlaneDescriptor(
                        planeIndex = 0,
                        offsetBytes = 0L,
                        rowStrideBytes = plane.rowStride.toLong(),
                        meaningfulRowBytes = meaningfulRowBytesLong,
                        rowCount = profile.rawSize.height,
                        pixelStrideBytes = plane.pixelStride,
                    ),
                ),
                cfaPattern = profile.cfaPattern,
                sensorPixelMode = SensorPixelMode.DEFAULT,
                colorCalibrationIdentity = null,
                calibration = CalibrationEvidence(identity = null, version = null, confidence = 0.0),
                sourceApi = AcquisitionSourceApi.CAMERA2_PUBLIC,
            )
            val timebase = TimebaseEvidence(
                imageTimestampNs = pair.timestampNs,
                captureResultTimestampNs = pair.timestampNs,
                requestIssuedTimestampNs = null,
                declaredTimebase = AcquisitionTimebase.SENSOR,
                normalizedOffsetNs = null,
                mappingUncertaintyNs = null,
            )
            val captureGeneration = if (ordinalValue <= Long.MAX_VALUE.toULong()) ordinalValue.toLong() else null
            val identity = context.toAcquisitionIdentity(
                providerEpoch = providerEpoch,
                representation = descriptor,
                timebase = timebase,
                captureGeneration = captureGeneration,
            )
            val digest = CanonicalRasterDigest(
                sha256 = MessageDigest.getInstance("SHA-256").digest(canonical).toLowerHex(),
                byteCount = canonical.size.toLong(),
            )
            val frame = PackedNoneFrame(
                frameOrdinal = ordinal,
                identity = identity,
                canonicalRaster = digest,
                payload = canonical,
                hostTimestampNs = hostTimestampNs,
                normalizedTimestampNs = null,
                timebaseUncertaintyNs = null,
                metadata = buildList {
                    add(RawVideoMetadataEntry("codec", "PACKED_NONE"))
                    add(RawVideoMetadataEntry("source", "CAMERA2_RAW_SENSOR"))
                    add(RawVideoMetadataEntry("camera2.frameNumber", pair.frameNumber.toString()))
                    pair.result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                        add(RawVideoMetadataEntry("sensor.exposureTimeNs", it.toString()))
                    }
                    pair.result.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                        add(RawVideoMetadataEntry("sensor.sensitivityIso", it.toString()))
                    }
                    pair.result.get(CaptureResult.SENSOR_FRAME_DURATION)?.let {
                        add(RawVideoMetadataEntry("sensor.frameDurationNs", it.toString()))
                    }
                },
                discontinuityBefore = gap != null,
            )
            return SensorRawVideoFrameBatch(gap, frame)
        } finally {
            image.close()
            pair.close()
        }
    }
}

private fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    val out = CharArray(size * 2)
    forEachIndexed { index, value ->
        val byte = value.toInt() and 0xff
        out[index * 2] = alphabet[byte ushr 4]
        out[index * 2 + 1] = alphabet[byte and 0x0f]
    }
    return String(out)
}
