package com.sahidcode404.camx.core.camera.discovery

import android.os.Build
import android.os.SystemClock
import com.sahidcode404.camx.core.camera.diagnostics.Available
import com.sahidcode404.camx.core.camera.diagnostics.NativeCore
import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraStreamCapability
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal const val NDK_AUX_MAX_CAMERA_IDS = 64
internal const val NDK_AUX_MAX_FOCAL_LENGTHS = 16
internal const val NDK_AUX_MAX_APERTURES = 16
internal const val NDK_AUX_MAX_PREVIEW_STREAMS = 128
internal const val NDK_AUX_MAX_FPS_RANGES = 64
internal const val NDK_AUX_MAX_RAW_SIZES = 64
internal const val NDK_AUX_MAX_ID_BYTES = 256
internal const val NDK_AUX_MAX_FAILURES = 128
internal const val NDK_AUX_MAX_ENCODED_BYTES = 1024 * 1024
private const val CAMERA_NDK_MIN_API = 24

enum class NdkAdvertisedRuntimeState {
    NOT_RUN,
    AVAILABLE,
    UNAVAILABLE,
    INVALID_PAYLOAD,
}

enum class NdkAdvertisedEvidenceFailureKind {
    ID_ENUMERATION_UNAVAILABLE,
    CAMERA_ID_LIMIT_EXCEEDED,
    INVALID_CAMERA_ID,
    METADATA_UNAVAILABLE,
    MALFORMED_METADATA,
    METADATA_BOUND_EXCEEDED,
    MALFORMED_NATIVE_PAYLOAD,
}

data class NdkAdvertisedEvidenceFailure(
    val kind: NdkAdvertisedEvidenceFailureKind,
    val transportId: String? = null,
)

data class NdkAdvertisedEvidenceReport(
    val snapshot: CameraEvidenceSnapshot,
    val runtimeState: NdkAdvertisedRuntimeState,
    val failures: List<NdkAdvertisedEvidenceFailure>,
)

/**
 * API-24+ Camera-NDK advertised metadata only. The native implementation lives
 * in the API-23-loadable core and resolves public Camera-NDK symbols with
 * dlopen/dlsym, so this bridge never makes libcamera2ndk.so a strong dependency.
 */
internal object NdkAdvertisedNativeBridge {
    fun collect(deviceApi: Int): ByteArray? {
        if (deviceApi < CAMERA_NDK_MIN_API) return null
        if (NativeCore.availability != Available) return null
        return runCatching { nativeCollect(deviceApi) }.getOrNull()
    }

    private external fun nativeCollect(androidApi: Int): ByteArray?
}

internal class NdkAdvertisedCameraEvidenceBackend(
    private val environment: CameraEnvironmentFingerprint,
    private val deviceApi: () -> Int = { Build.VERSION.SDK_INT },
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val rawCollector: (Int) -> ByteArray? = NdkAdvertisedNativeBridge::collect,
) : CameraEvidenceBackend {
    override suspend fun discover(depth: DiscoveryDepth): CameraEvidenceSnapshot =
        discoverReport(depth).snapshot

    suspend fun discoverReport(depth: DiscoveryDepth): NdkAdvertisedEvidenceReport {
        coroutineContext.ensureActive()
        if (depth == DiscoveryDepth.STARTUP_SEED) {
            return report(NdkAdvertisedRuntimeState.NOT_RUN, emptyList(), emptyList())
        }
        val api = deviceApi()
        if (api < CAMERA_NDK_MIN_API) {
            return report(NdkAdvertisedRuntimeState.UNAVAILABLE, emptyList(), emptyList())
        }
        val payload = rawCollector(api)
            ?: return report(NdkAdvertisedRuntimeState.UNAVAILABLE, emptyList(), emptyList())
        coroutineContext.ensureActive()
        val decoded = NdkAdvertisedSnapshotCodec.decode(payload)
            ?: return report(
                NdkAdvertisedRuntimeState.INVALID_PAYLOAD,
                emptyList(),
                listOf(NdkAdvertisedEvidenceFailure(NdkAdvertisedEvidenceFailureKind.MALFORMED_NATIVE_PAYLOAD)),
            )
        return report(
            if (decoded.runtimeAvailable) NdkAdvertisedRuntimeState.AVAILABLE
            else NdkAdvertisedRuntimeState.UNAVAILABLE,
            decoded.evidence,
            decoded.failures,
        )
    }

    private fun report(
        state: NdkAdvertisedRuntimeState,
        evidence: List<CameraMetadataEvidence>,
        failures: List<NdkAdvertisedEvidenceFailure>,
    ) = NdkAdvertisedEvidenceReport(
        snapshot = CameraEvidenceSnapshot(
            source = CameraRouteSource.NDK_ADVERTISED,
            environment = environment,
            evidence = immutableList(evidence),
            completedAtElapsedRealtimeNs = clockNanos().coerceAtLeast(0L),
        ),
        runtimeState = state,
        failures = immutableList(failures),
    )

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}

internal data class DecodedNdkAdvertisedSnapshot(
    val runtimeAvailable: Boolean,
    val evidence: List<CameraMetadataEvidence>,
    val failures: List<NdkAdvertisedEvidenceFailure>,
)

/** Strict bounded decoder for the compact native snapshot protocol. */
internal object NdkAdvertisedSnapshotCodec {
    private val magic = byteArrayOf('C'.code.toByte(), 'X'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte())
    private const val schema = 1

    fun decode(bytes: ByteArray?): DecodedNdkAdvertisedSnapshot? {
        if (bytes == null || bytes.size !in 12..NDK_AUX_MAX_ENCODED_BYTES) return null
        val reader = Reader(bytes)
        if (!reader.raw(4)?.contentEquals(magic).orFalse()) return null
        if (reader.u16() != schema) return null
        val status = reader.u8() ?: return null
        if (status !in 0..1 || reader.u8() != 0) return null
        val recordCount = reader.u16() ?: return null
        val failureCount = reader.u16() ?: return null
        if (recordCount > NDK_AUX_MAX_CAMERA_IDS || failureCount > NDK_AUX_MAX_FAILURES) return null
        if (status == 1 && (recordCount != 0 || failureCount != 0)) return null

        val evidence = ArrayList<CameraMetadataEvidence>(recordCount)
        val ids = HashSet<String>(recordCount)
        repeat(recordCount) {
            val id = reader.utf8(allowEmpty = false) ?: return null
            if (!ids.add(id)) return null
            val facingCode = reader.u8() ?: return null
            val flags = reader.u8() ?: return null
            if (facingCode !in 0..3 || flags and 0b1110_0000 != 0) return null
            val focalCount = reader.u16() ?: return null
            val apertureCount = reader.u16() ?: return null
            val previewCount = reader.u16() ?: return null
            val fpsCount = reader.u16() ?: return null
            val rawCount = reader.u16() ?: return null
            if (focalCount > NDK_AUX_MAX_FOCAL_LENGTHS ||
                apertureCount > NDK_AUX_MAX_APERTURES ||
                previewCount > NDK_AUX_MAX_PREVIEW_STREAMS ||
                fpsCount > NDK_AUX_MAX_FPS_RANGES ||
                rawCount > NDK_AUX_MAX_RAW_SIZES
            ) return null

            val sensorWidth = if (flags.has(0)) reader.positiveFloat() ?: return null else null
            val sensorHeight = if (flags.has(0)) reader.positiveFloat() ?: return null else null
            val active = if (flags.has(1)) reader.positiveSize() ?: return null else null
            val pixel = if (flags.has(2)) reader.positiveSize() ?: return null else null
            val orientation = if (flags.has(3)) {
                (reader.i32() ?: return null).takeIf { it in 0..270 && it % 90 == 0 } ?: return null
            } else null
            val cfa = if (flags.has(4)) reader.i32() ?: return null else null
            val focals = List(focalCount) { reader.positiveFloat() ?: return null }
            val apertures = List(apertureCount) { reader.positiveFloat() ?: return null }
            val previews = List(previewCount) {
                val size = reader.positiveSize() ?: return null
                val duration = reader.i64() ?: return null
                if (duration < -1L) return null
                CameraStreamCapability(
                    type = PreviewStreamType.CAMERA2_PRIVATE,
                    size = size,
                    minimumFrameDurationNs = duration.takeUnless { it == -1L },
                )
            }
            val fps = List(fpsCount) {
                val minimum = reader.i32() ?: return null
                val maximum = reader.i32() ?: return null
                if (minimum <= 0 || maximum < minimum) return null
                CameraFpsCapability(minimum, maximum)
            }
            val raw = List(rawCount) { reader.positiveSize() ?: return null }

            evidence += CameraMetadataEvidence(
                source = CameraRouteSource.NDK_ADVERTISED,
                transportId = CameraTransportId(id),
                facing = when (facingCode) {
                    1 -> LensFacing.FRONT
                    2 -> LensFacing.BACK
                    3 -> LensFacing.EXTERNAL
                    else -> LensFacing.UNKNOWN
                },
                focalLengthsMillimetres = immutableSorted(focals),
                sensorPhysicalWidthMillimetres = sensorWidth,
                sensorPhysicalHeightMillimetres = sensorHeight,
                activeArray = active,
                pixelArray = pixel,
                sensorOrientationDegrees = orientation,
                apertureValues = immutableSorted(apertures),
                colorFilterArrangement = cfa,
                capabilities = CameraCapabilities(
                    previewStreams = immutableList(previews.distinct().sortedWith(compareBy(
                        { it.size.area }, { it.size.width }, { it.size.height }, { it.minimumFrameDurationNs ?: Long.MAX_VALUE },
                    ))),
                    fpsRanges = immutableList(fps.distinct().sortedWith(compareBy({ it.minimum }, { it.maximum }))),
                    rawSizes = immutableList(raw.distinct().sortedWith(compareBy({ it.area }, { it.width }, { it.height }))),
                ),
            )
        }

        val failures = ArrayList<NdkAdvertisedEvidenceFailure>(failureCount)
        repeat(failureCount) {
            val code = reader.u8() ?: return null
            val id = reader.utf8(allowEmpty = true) ?: return null
            val kind = when (code) {
                1 -> NdkAdvertisedEvidenceFailureKind.ID_ENUMERATION_UNAVAILABLE
                2 -> NdkAdvertisedEvidenceFailureKind.CAMERA_ID_LIMIT_EXCEEDED
                3 -> NdkAdvertisedEvidenceFailureKind.INVALID_CAMERA_ID
                4 -> NdkAdvertisedEvidenceFailureKind.METADATA_UNAVAILABLE
                5 -> NdkAdvertisedEvidenceFailureKind.MALFORMED_METADATA
                6 -> NdkAdvertisedEvidenceFailureKind.METADATA_BOUND_EXCEEDED
                else -> return null
            }
            failures += NdkAdvertisedEvidenceFailure(kind, id.ifEmpty { null })
        }
        if (!reader.finished()) return null

        val orderedEvidence = evidence.sortedWith(compareBy({ opaqueOrderKey(it.transportId.value) }, { it.transportId.value }))
        val orderedFailures = failures.sortedWith(compareBy(
            { it.kind.ordinal },
            { it.transportId?.let(::opaqueOrderKey).orEmpty() },
            { it.transportId.orEmpty() },
        ))
        return DecodedNdkAdvertisedSnapshot(
            runtimeAvailable = status == 0,
            evidence = immutableList(orderedEvidence),
            failures = immutableList(orderedFailures),
        )
    }

    private fun Boolean?.orFalse() = this == true
    private fun Int.has(bit: Int) = this and (1 shl bit) != 0

    private fun opaqueOrderKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("ndk-decode-order|$value".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun immutableSorted(values: List<Float>): List<Float> =
        immutableList(values.distinct().sorted())

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private class Reader(private val bytes: ByteArray) {
        private var index = 0

        fun finished() = index == bytes.size

        fun raw(count: Int): ByteArray? {
            if (count < 0 || index > bytes.size - count) return null
            return bytes.copyOfRange(index, index + count).also { index += count }
        }

        fun u8(): Int? = raw(1)?.single()?.toInt()?.and(0xff)

        fun u16(): Int? {
            val value = raw(2) ?: return null
            return (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8)
        }

        fun i32(): Int? {
            val value = raw(4) ?: return null
            return (value[0].toInt() and 0xff) or
                ((value[1].toInt() and 0xff) shl 8) or
                ((value[2].toInt() and 0xff) shl 16) or
                ((value[3].toInt() and 0xff) shl 24)
        }

        fun i64(): Long? {
            val value = raw(8) ?: return null
            var result = 0L
            repeat(8) { offset -> result = result or ((value[offset].toLong() and 0xffL) shl (offset * 8)) }
            return result
        }

        fun positiveFloat(): Float? {
            val bits = i32() ?: return null
            return Float.fromBits(bits).takeIf { it.isFinite() && it > 0f }
        }

        fun positiveSize(): IntSize? {
            val width = i32() ?: return null
            val height = i32() ?: return null
            if (width <= 0 || height <= 0) return null
            return IntSize(width, height)
        }

        fun utf8(allowEmpty: Boolean): String? {
            val length = u16() ?: return null
            if (length > NDK_AUX_MAX_ID_BYTES || (!allowEmpty && length == 0)) return null
            val raw = raw(length) ?: return null
            val decoded = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString()
            }.getOrNull() ?: return null
            if ((!allowEmpty && decoded.isBlank()) || decoded.indexOf('\u0000') >= 0) return null
            return decoded
        }
    }
}
