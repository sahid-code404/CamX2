package com.sahidcode404.camx.core.rawvideo.recording

import android.media.Image
import android.media.ImageFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Heap-backed snapshot of one public RAW_SENSOR Image.
 *
 * M10 must not retain ImageReader-owned Image leases while waiting for the matching CaptureResult.
 * This class copies the declared RAW plane extent before closing the source Image. The snapshot is
 * not tied to ImageReader/native buffers and therefore does not consume ImageReader.maxImages
 * ownership.
 */
internal class DetachedRawSensorImage private constructor(
    private val detachedFormat: Int,
    private val detachedWidth: Int,
    private val detachedHeight: Int,
    private val detachedTimestampNs: Long,
    private val detachedPlanes: Array<DetachedRawSensorPlane>,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val format: Int get() = requireOpen(detachedFormat)
    val width: Int get() = requireOpen(detachedWidth)
    val height: Int get() = requireOpen(detachedHeight)
    val timestamp: Long get() = requireOpen(detachedTimestampNs)

    val planes: Array<DetachedRawSensorPlane>
        get() {
            check(!closed.get()) { "Detached RAW image is closed" }
            return detachedPlanes.copyOf()
        }

    override fun close() {
        closed.set(true)
    }

    private fun <T> requireOpen(value: T): T {
        check(!closed.get()) { "Detached RAW image is closed" }
        return value
    }

    companion object {
        fun copyAndClose(source: Image): DetachedRawSensorImage {
            try {
                require(source.format == ImageFormat.RAW_SENSOR) {
                    "M10 pairing detachment only accepts public RAW_SENSOR images"
                }
                require(source.width > 0 && source.height > 0)
                require(source.timestamp > 0L) { "M10 RAW image timestamp must be positive before detachment" }
                val sourcePlanes = source.planes
                require(sourcePlanes.size == 1) { "M10 public RAW_SENSOR detachment requires exactly one plane" }
                val planes = sourcePlanes.map { plane ->
                    val meaningfulRowBytes = Math.multiplyExact(
                        source.width.toLong(),
                        M10RawVideoLimits.RAW_SENSOR_BYTES_PER_PIXEL,
                    )
                    require(plane.pixelStride == M10RawVideoLimits.RAW_SENSOR_BYTES_PER_PIXEL.toInt()) {
                        "M10 RAW_SENSOR pixel stride is not the public 16-bit unpacked layout"
                    }
                    require(plane.rowStride >= meaningfulRowBytes) {
                        "M10 RAW_SENSOR row stride is smaller than a meaningful row"
                    }
                    require(meaningfulRowBytes <= Int.MAX_VALUE.toLong())
                    val sourceRequiredBytes = Math.addExact(
                        Math.multiplyExact((source.height - 1).toLong(), plane.rowStride.toLong()),
                        meaningfulRowBytes,
                    )
                    require(sourceRequiredBytes <= Int.MAX_VALUE.toLong()) {
                        "M10 RAW source extent cannot be represented by a JVM byte array"
                    }
                    val sourceBuffer = plane.buffer.duplicate().apply { clear() }
                    require(sourceRequiredBytes <= sourceBuffer.remaining().toLong()) {
                        "M10 RAW source buffer is shorter than its declared row layout"
                    }
                    sourceBuffer.limit(sourceRequiredBytes.toInt())
                    val bytes = ByteArray(sourceRequiredBytes.toInt())
                    sourceBuffer.get(bytes)
                    DetachedRawSensorPlane(plane.rowStride, plane.pixelStride, bytes)
                }.toTypedArray()
                return DetachedRawSensorImage(
                    detachedFormat = source.format,
                    detachedWidth = source.width,
                    detachedHeight = source.height,
                    detachedTimestampNs = source.timestamp,
                    detachedPlanes = planes,
                )
            } finally {
                source.close()
            }
        }
    }
}

internal class DetachedRawSensorPlane(
    val rowStride: Int,
    val pixelStride: Int,
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer()

    val buffer: ByteBuffer
        get() = buffer.duplicate()
}
