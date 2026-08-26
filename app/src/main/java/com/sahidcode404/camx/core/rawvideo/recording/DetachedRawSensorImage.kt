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
                require(source.width > 0 && source.height > 0)
                require(source.timestamp > 0L) { "M10 RAW image timestamp must be positive before detachment" }
                val sourcePlanes = source.planes
                require(sourcePlanes.size == 1) { "M10 public RAW_SENSOR detachment requires exactly one plane" }
                val planes = sourcePlanes.map { plane ->
                    require(plane.rowStride > 0 && plane.pixelStride > 0)
                    val sourceBuffer = plane.buffer.duplicate().apply { clear() }
                    val bytes = ByteArray(sourceBuffer.remaining())
                    sourceBuffer.get(bytes)
                    DetachedPlane(plane.rowStride, plane.pixelStride, bytes)
                }.toTypedArray<Image.Plane>()
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
