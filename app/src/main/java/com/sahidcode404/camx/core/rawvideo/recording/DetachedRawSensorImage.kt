package com.sahidcode404.camx.core.rawvideo.recording

import android.media.Image
import android.media.ImageFormat

/**
 * App-owned snapshot of one public RAW_SENSOR Image.
 *
 * Android's Image/Image.Plane constructors are framework-private, so detached evidence must not
 * subclass either framework type. The factory copies the meaningful RAW raster row-by-row and
 * closes the source Image before returning. The resulting object owns only JVM bytes and therefore
 * consumes no ImageReader.maxImages lease while Camera2 result/image callbacks are skewed.
 */
internal class DetachedRawSensorImage private constructor(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val sourceRowStrideBytes: Int,
    val sourcePixelStrideBytes: Int,
    canonicalRaster: ByteArray,
) : AutoCloseable {
    private var canonicalRaster: ByteArray? = canonicalRaster

    val retainedByteCount: Long
        @Synchronized get() = canonicalRaster?.size?.toLong() ?: 0L

    @Synchronized
    fun takeCanonicalRaster(): ByteArray {
        val value = checkNotNull(canonicalRaster) { "Detached RAW raster ownership already moved or closed" }
        canonicalRaster = null
        return value
    }

    @Synchronized
    override fun close() {
        canonicalRaster = null
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
                val plane = sourcePlanes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                require(rowStride > 0 && pixelStride > 0)
                require(pixelStride == M10RawVideoLimits.RAW_SENSOR_BYTES_PER_PIXEL.toInt()) {
                    "M10 RAW_SENSOR pixel stride is not the public 16-bit unpacked layout"
                }

                val meaningfulRowBytesLong = Math.multiplyExact(width.toLong(), pixelStride.toLong())
                require(meaningfulRowBytesLong <= Int.MAX_VALUE.toLong()) {
                    "M10 RAW meaningful row exceeds JVM array addressing"
                }
                val meaningfulRowBytes = meaningfulRowBytesLong.toInt()
                require(rowStride >= meaningfulRowBytes) {
                    "M10 RAW_SENSOR row stride is smaller than a meaningful row"
                }

                val sourceRequired = Math.addExact(
                    Math.multiplyExact((height - 1).toLong(), rowStride.toLong()),
                    meaningfulRowBytesLong,
                )
                val canonicalBytesLong = Math.multiplyExact(meaningfulRowBytesLong, height.toLong())
                require(canonicalBytesLong in 1..Int.MAX_VALUE.toLong()) {
                    "M10 detached canonical RAW raster exceeds JVM array addressing"
                }

                val sourceBuffer = plane.buffer.duplicate().apply { clear() }
                require(sourceRequired <= sourceBuffer.capacity().toLong()) {
                    "M10 RAW source buffer is shorter than its declared row layout"
                }

                val canonical = ByteArray(canonicalBytesLong.toInt())
                repeat(height) { row ->
                    val sourceOffset = Math.multiplyExact(row.toLong(), rowStride.toLong())
                    val destinationOffset = Math.multiplyExact(row.toLong(), meaningfulRowBytesLong)
                    require(sourceOffset <= Int.MAX_VALUE.toLong() && destinationOffset <= Int.MAX_VALUE.toLong())
                    sourceBuffer.position(sourceOffset.toInt())
                    sourceBuffer.get(canonical, destinationOffset.toInt(), meaningfulRowBytes)
                }

                return DetachedRawSensorImage(
                    width = width,
                    height = height,
                    timestampNs = timestampNs,
                    sourceRowStrideBytes = rowStride,
                    sourcePixelStrideBytes = pixelStride,
                    canonicalRaster = canonical,
                )
            } finally {
                source.close()
            }
        }
    }
}
