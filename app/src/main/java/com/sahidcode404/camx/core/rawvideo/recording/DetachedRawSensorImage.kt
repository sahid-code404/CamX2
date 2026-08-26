package com.sahidcode404.camx.core.rawvideo.recording

import android.media.Image
import android.media.ImageFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Heap-backed snapshot of one public RAW_SENSOR Image.
 *
 * M10 must not retain ImageReader-owned Image leases while waiting for the matching CaptureResult.
 * This class copies the complete declared RAW plane before closing the source Image, while preserving
 * the Image surface expected by the existing exact-timestamp assembler. The snapshot is not tied to
 * ImageReader/native buffers and therefore does not consume ImageReader.maxImages ownership.
 */
internal class DetachedRawSensorImage private constructor(
    private val detachedFormat: Int,
    private val detachedWidth: Int,
    private val detachedHeight: Int,
    private val detachedTimestampNs: Long,
    private val detachedPlanes: Array<Image.Plane>,
) : Image() {
    private val closed = AtomicBoolean(false)

    override fun getFormat(): Int = requireOpen(detachedFormat)

    override fun getWidth(): Int = requireOpen(detachedWidth)

    override fun getHeight(): Int = requireOpen(detachedHeight)

    override fun getTimestamp(): Long = requireOpen(detachedTimestampNs)

    override fun getPlanes(): Array<Image.Plane> {
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

    private class DetachedPlane(
        private val rowStride: Int,
        private val pixelStride: Int,
        bytes: ByteArray,
    ) : Image.Plane() {
        private val buffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer()

        override fun getRowStride(): Int = rowStride

        override fun getPixelStride(): Int = pixelStride

        override fun getBuffer(): ByteBuffer = buffer.duplicate()
    }

    companion object {
        fun copyAndClose(source: Image): DetachedRawSensorImage {
            try {
                require(source.format == ImageFormat.RAW_SENSOR) {
                    "M10 pairing detachment only accepts public RAW_SENSOR images"
                }
                val width = source.width
                val height = source.height
                val timestampNs = source.timestamp
                require(width > 0 && height > 0)
                require(timestampNs > 0L) { "M10 RAW image timestamp must be positive before detachment" }
                val sourcePlanes = source.planes
                require(sourcePlanes.size == 1) { "M10 public RAW_SENSOR detachment requires exactly one plane" }
                val planes = sourcePlanes.map { plane ->
                    require(plane.pixelStride == M10RawVideoLimits.RAW_SENSOR_BYTES_PER_PIXEL.toInt()) {
                        "M10 RAW_SENSOR detachment requires the public 16-bit unpacked pixel stride"
                    }
                    val meaningfulRowBytes = Math.multiplyExact(width, plane.pixelStride)
                    require(plane.rowStride >= meaningfulRowBytes) {
                        "M10 RAW_SENSOR row stride is smaller than a meaningful row"
                    }
                    val sourceRequiredLong = Math.addExact(
                        Math.multiplyExact((height - 1).toLong(), plane.rowStride.toLong()),
                        meaningfulRowBytes.toLong(),
                    )
                    require(sourceRequiredLong in 1..Int.MAX_VALUE.toLong()) {
                        "M10 detached RAW plane extent exceeds the bounded JVM snapshot"
                    }
                    val sourceBuffer = plane.buffer.duplicate().apply { clear() }
                    require(sourceRequiredLong <= sourceBuffer.capacity().toLong()) {
                        "M10 RAW source buffer is shorter than its declared row layout"
                    }
                    sourceBuffer.limit(sourceRequiredLong.toInt())
                    val bytes = ByteArray(sourceRequiredLong.toInt())
                    sourceBuffer.get(bytes)
                    DetachedPlane(plane.rowStride, plane.pixelStride, bytes)
                }.toTypedArray<Image.Plane>()
                return DetachedRawSensorImage(
                    detachedFormat = source.format,
                    detachedWidth = width,
                    detachedHeight = height,
                    detachedTimestampNs = timestampNs,
                    detachedPlanes = planes,
                )
            } finally {
                source.close()
            }
        }
    }
}
