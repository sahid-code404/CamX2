package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.StableLensReferenceSnapshot

internal object StableLensReferenceCacheCodec {
    fun encode(snapshot: StableLensReferenceSnapshot): ByteArray {
        require(snapshot.schema == CameraSchemaVersions.LENS_REFERENCE) {
            "Unsupported lens-reference cache schema"
        }
        val writer = CacheBinaryWriter()
        writer.writeString(snapshot.environment.value, CacheBounds.ENVIRONMENT_BYTES, "environment")
        writer.writeString(
            snapshot.canonicalFingerprint.value,
            CacheBounds.IDENTIFIER_BYTES,
            "canonical fingerprint",
        )
        return CacheEnvelope.encode(
            CacheEnvelope.REFERENCE_MAGIC,
            CameraSchemaVersions.LENS_REFERENCE,
            CacheBounds.REFERENCE_PAYLOAD_BYTES,
            writer.toByteArray(),
        )
    }

    fun decode(
        bytes: ByteArray,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CacheRead<StableLensReferenceSnapshot> = when (
        val envelope = CacheEnvelope.decode(
            bytes,
            CacheEnvelope.REFERENCE_MAGIC,
            CameraSchemaVersions.LENS_REFERENCE,
            CacheBounds.REFERENCE_PAYLOAD_BYTES,
        )
    ) {
        CacheEnvelope.Decoded.Unsupported -> CacheRead.Miss
        is CacheEnvelope.Decoded.Corrupt -> CacheRead.Corrupt(envelope.reason)
        is CacheEnvelope.Decoded.Payload -> try {
            val reader = CacheBinaryReader(envelope.bytes)
            val environment = CameraEnvironmentFingerprint(
                reader.readString(CacheBounds.ENVIRONMENT_BYTES, "environment"),
            )
            if (environment != expectedEnvironment) return CacheRead.Miss
            val snapshot = StableLensReferenceSnapshot(
                schema = CameraSchemaVersions.LENS_REFERENCE,
                environment = environment,
                canonicalFingerprint = CanonicalLensFingerprint(
                    reader.readString(CacheBounds.IDENTIFIER_BYTES, "canonical fingerprint"),
                ),
            )
            reader.requireExhausted()
            CacheRead.Hit(snapshot)
        } catch (error: Exception) {
            CacheRead.Corrupt(error.message ?: "Malformed lens-reference cache")
        }
    }
}
