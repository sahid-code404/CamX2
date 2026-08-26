package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraFpsCapability
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.HotStartSnapshot
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.PhysicalCameraId
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewFpsFallbackReason
import com.sahidcode404.camx.core.camera.model.PreviewFpsRequest
import com.sahidcode404.camx.core.camera.model.PreviewFpsResolution
import com.sahidcode404.camx.core.camera.model.PreviewStreamType
import com.sahidcode404.camx.core.camera.model.PreviewTrust

internal object HotStartCacheCodec {
    fun encode(snapshot: HotStartSnapshot): ByteArray {
        require(snapshot.schema == CameraSchemaVersions.HOT_START) { "Unsupported hot-cache schema" }
        validateString(snapshot.environment.value, CacheBounds.ENVIRONMENT_BYTES, "environment")
        validateString(snapshot.selectedCanonicalFingerprint.value, CacheBounds.IDENTIFIER_BYTES, "canonical fingerprint")
        validateString(snapshot.selectedProfileFingerprint.value, CacheBounds.IDENTIFIER_BYTES, "profile fingerprint")
        validateString(snapshot.routeId.value, CacheBounds.IDENTIFIER_BYTES, "route ID")
        validateString(snapshot.openCameraId.value, CacheBounds.IDENTIFIER_BYTES, "open camera ID")
        snapshot.physicalCameraId?.let { validateString(it.value, CacheBounds.IDENTIFIER_BYTES, "physical camera ID") }
        validateString(snapshot.previewConfiguration.signature, CacheBounds.SIGNATURE_BYTES, "preview signature")

        val writer = CacheBinaryWriter()
        writer.writeString(snapshot.environment.value, CacheBounds.ENVIRONMENT_BYTES, "environment")
        writer.writeString(snapshot.selectedCanonicalFingerprint.value, CacheBounds.IDENTIFIER_BYTES, "canonical fingerprint")
        writer.writeString(snapshot.selectedProfileFingerprint.value, CacheBounds.IDENTIFIER_BYTES, "profile fingerprint")
        writer.writeString(snapshot.routeId.value, CacheBounds.IDENTIFIER_BYTES, "route ID")
        writer.writeString(snapshot.openCameraId.value, CacheBounds.IDENTIFIER_BYTES, "open camera ID")
        writer.writeNullable(snapshot.physicalCameraId) {
            writer.writeString(it.value, CacheBounds.IDENTIFIER_BYTES, "physical camera ID")
        }
        writePreviewConfiguration(writer, snapshot.previewConfiguration)
        writer.writeNullable(snapshot.sensorOrientationDegrees) { writer.writeInt(it) }
        writer.writeInt(snapshot.facing.ordinal)
        writer.writeInt(snapshot.routeTrust.ordinal)
        writer.writeInt(snapshot.previewTrust.ordinal)
        writer.writeLong(snapshot.lastVerifiedElapsedRealtimeNs)
        return CacheEnvelope.encode(
            CacheEnvelope.HOT_MAGIC,
            CameraSchemaVersions.HOT_START,
            CacheBounds.HOT_PAYLOAD_BYTES,
            writer.toByteArray(),
        )
    }

    fun decode(
        bytes: ByteArray,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CacheRead<HotStartSnapshot> = when (
        val envelope = CacheEnvelope.decode(
            bytes,
            CacheEnvelope.HOT_MAGIC,
            CameraSchemaVersions.HOT_START,
            CacheBounds.HOT_PAYLOAD_BYTES,
        )
    ) {
        CacheEnvelope.Decoded.Unsupported -> CacheRead.Miss
        is CacheEnvelope.Decoded.Corrupt -> CacheRead.Corrupt(envelope.reason)
        is CacheEnvelope.Decoded.Payload -> decodePayload(envelope.bytes, expectedEnvironment)
    }

    private fun decodePayload(
        payload: ByteArray,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CacheRead<HotStartSnapshot> {
        return try {
            val reader = CacheBinaryReader(payload)
            val environment = CameraEnvironmentFingerprint(
                reader.readString(CacheBounds.ENVIRONMENT_BYTES, "environment"),
            )
            if (environment != expectedEnvironment) return CacheRead.Miss
            val snapshot = HotStartSnapshot(
                schema = CameraSchemaVersions.HOT_START,
                environment = environment,
                selectedCanonicalFingerprint = CanonicalLensFingerprint(
                    reader.readString(CacheBounds.IDENTIFIER_BYTES, "canonical fingerprint"),
                ),
                selectedProfileFingerprint = CameraProfileFingerprint(
                    reader.readString(CacheBounds.IDENTIFIER_BYTES, "profile fingerprint"),
                ),
                routeId = CameraRouteId(reader.readString(CacheBounds.IDENTIFIER_BYTES, "route ID")),
                openCameraId = CameraTransportId(
                    reader.readString(CacheBounds.IDENTIFIER_BYTES, "open camera ID"),
                ),
                physicalCameraId = reader.readNullable("physical camera ID") {
                    PhysicalCameraId(reader.readString(CacheBounds.IDENTIFIER_BYTES, "physical camera ID"))
                },
                previewConfiguration = readPreviewConfiguration(reader),
                sensorOrientationDegrees = reader.readNullable("sensor orientation") {
                    reader.readInt("sensor orientation")
                },
                facing = reader.readEnum(LensFacing.values(), "lens facing"),
                routeTrust = reader.readEnum(CameraTrust.values(), "route trust"),
                previewTrust = reader.readEnum(PreviewTrust.values(), "preview trust"),
                lastVerifiedElapsedRealtimeNs = reader.readLong("last verified timestamp"),
            )
            reader.requireExhausted()
            CacheRead.Hit(snapshot)
        } catch (error: Exception) {
            CacheRead.Corrupt(error.message ?: "Malformed hot cache")
        }
    }

    private fun writePreviewConfiguration(writer: CacheBinaryWriter, value: PreviewConfiguration) {
        writer.writeInt(value.streamType.ordinal)
        writeSize(writer, value.size)
        writer.writeBoolean(value.fps.request.overrideEnabled)
        writer.writeInt(value.fps.request.requestedMinimum)
        writer.writeInt(value.fps.request.requestedMaximum)
        writer.writeNullable(value.fps.resolvedRange) {
            writer.writeInt(it.minimum)
            writer.writeInt(it.maximum)
        }
        writer.writeInt(value.fps.reason.ordinal)
        writer.writeBoolean(value.highResolutionViewfinder)
        writer.writeString(value.signature, CacheBounds.SIGNATURE_BYTES, "preview signature")
    }

    private fun readPreviewConfiguration(reader: CacheBinaryReader): PreviewConfiguration {
        val streamType = reader.readEnum(PreviewStreamType.values(), "preview stream type")
        val size = readSize(reader, "preview size")
        val request = PreviewFpsRequest(
            overrideEnabled = reader.readBoolean("FPS override"),
            requestedMinimum = reader.readInt("requested minimum FPS"),
            requestedMaximum = reader.readInt("requested maximum FPS"),
        )
        val resolvedRange = reader.readNullable("resolved FPS range") {
            CameraFpsCapability(
                minimum = reader.readInt("resolved minimum FPS"),
                maximum = reader.readInt("resolved maximum FPS"),
            )
        }
        return PreviewConfiguration(
            streamType = streamType,
            size = size,
            fps = PreviewFpsResolution(
                request = request,
                resolvedRange = resolvedRange,
                reason = reader.readEnum(PreviewFpsFallbackReason.values(), "FPS fallback reason"),
            ),
            highResolutionViewfinder = reader.readBoolean("high-resolution viewfinder"),
            signature = reader.readString(CacheBounds.SIGNATURE_BYTES, "preview signature"),
        )
    }

    private fun writeSize(writer: CacheBinaryWriter, size: IntSize) {
        writer.writeInt(size.width)
        writer.writeInt(size.height)
    }

    private fun readSize(reader: CacheBinaryReader, label: String): IntSize = IntSize(
        reader.readInt("$label width"),
        reader.readInt("$label height"),
    )

    private fun validateString(value: String, maximumBytes: Int, label: String) {
        require(value.isNotBlank()) { "$label cannot be blank" }
        require(value.toByteArray(Charsets.UTF_8).size <= maximumBytes) { "$label exceeds cache bound" }
    }
}
