package com.sahidcode404.camx.core.rawvideo.codec

import com.sahidcode404.camx.core.camera.acquisition.AcquisitionPlaneDescriptor
import com.sahidcode404.camx.core.camera.acquisition.AcquisitionSourceApi
import com.sahidcode404.camx.core.camera.acquisition.CalibrationEvidence
import com.sahidcode404.camx.core.camera.acquisition.CanonicalRasterDigest
import com.sahidcode404.camx.core.camera.acquisition.CfaPattern
import com.sahidcode404.camx.core.camera.acquisition.IntRect
import com.sahidcode404.camx.core.camera.acquisition.MosaicSensorSamples
import com.sahidcode404.camx.core.camera.acquisition.PublicSourceFormat
import com.sahidcode404.camx.core.camera.acquisition.RepresentationDescriptor
import com.sahidcode404.camx.core.camera.acquisition.SamplePacking
import com.sahidcode404.camx.core.camera.acquisition.SensorPixelMode
import com.sahidcode404.camx.core.camera.model.IntSize
import kotlin.random.Random

internal fun m2bDescriptor(payloadBytes: Int): RepresentationDescriptor {
    require(payloadBytes >= 2 && payloadBytes % 2 == 0)
    val samples = payloadBytes / 2
    return RepresentationDescriptor(
        representation = MosaicSensorSamples,
        sourceFormat = PublicSourceFormat.RAW_SENSOR,
        packing = SamplePacking.UNPACKED_16_LE,
        storedBits = 16,
        effectiveBits = 12,
        size = IntSize(samples, 1),
        activeArea = IntRect(0, 0, samples, 1),
        planeDescriptors = listOf(
            AcquisitionPlaneDescriptor(
                planeIndex = 0,
                offsetBytes = 0L,
                rowStrideBytes = payloadBytes.toLong(),
                meaningfulRowBytes = payloadBytes.toLong(),
                rowCount = 1,
                pixelStrideBytes = 2,
            ),
        ),
        cfaPattern = CfaPattern.RGGB,
        sensorPixelMode = SensorPixelMode.DEFAULT,
        colorCalibrationIdentity = "m2b-color",
        calibration = CalibrationEvidence("m2b-calibration", "1", 1.0),
        sourceApi = AcquisitionSourceApi.CAMERA2_PUBLIC,
    )
}

internal fun m2bFrame(payload: ByteArray): CanonicalCodecFrame {
    require(payload.size >= 2 && payload.size % 2 == 0)
    return CanonicalCodecFrame(
        representation = m2bDescriptor(payload.size),
        canonicalRaster = CanonicalRasterDigest(
            sha256 = codecSha256Hex(payload),
            byteCount = payload.size.toLong(),
        ),
        payload = payload,
    )
}

internal fun flatPayload(size: Int, value: Int = 0x40): ByteArray {
    require(size >= 2 && size % 2 == 0)
    return ByteArray(size) { value.toByte() }
}

internal fun rampPayload(size: Int): ByteArray {
    require(size >= 2 && size % 2 == 0)
    return ByteArray(size) { index -> (index and 0xff).toByte() }
}

internal fun deterministicRandomPayload(size: Int, seed: Int = 404): ByteArray {
    require(size >= 2 && size % 2 == 0)
    return ByteArray(size).also { Random(seed).nextBytes(it) }
}
