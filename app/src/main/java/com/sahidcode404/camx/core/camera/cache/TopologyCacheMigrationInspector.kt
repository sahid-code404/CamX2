package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

internal enum class TopologyCacheInspectionStatus {
    ABSENT,
    COMPATIBLE,
    INCOMPATIBLE_SCHEMA,
    INCOMPATIBLE_ENVIRONMENT,
    CORRUPT,
    IO_FAILURE,
}

internal data class TopologyCacheInspection(
    val status: TopologyCacheInspectionStatus,
    val storedSchema: Int? = null,
    val environmentCompatible: Boolean? = null,
)

internal data class TopologyCacheAuditState(
    val currentTopologySchema: Int = CameraSchemaVersions.TOPOLOGY,
    val storedTopologySchema: Int? = null,
    val status: String = "NOT_CHECKED",
    val environmentCompatible: Boolean? = null,
    val migrated: Boolean = false,
)

/** Process-local bounded migration telemetry. It contains no camera IDs or canonical fingerprints. */
internal object TopologyCacheMigrationAudit {
    private val state = AtomicReference(TopologyCacheAuditState())

    fun snapshot(): TopologyCacheAuditState = state.get()

    fun recordInspection(inspection: TopologyCacheInspection) {
        val status = when (inspection.status) {
            TopologyCacheInspectionStatus.ABSENT -> "ABSENT"
            TopologyCacheInspectionStatus.COMPATIBLE -> "CHECKING_COMPATIBLE_CACHE"
            TopologyCacheInspectionStatus.INCOMPATIBLE_SCHEMA -> "REJECTED_SCHEMA"
            TopologyCacheInspectionStatus.INCOMPATIBLE_ENVIRONMENT -> "REJECTED_ENVIRONMENT"
            TopologyCacheInspectionStatus.CORRUPT -> "CORRUPT"
            TopologyCacheInspectionStatus.IO_FAILURE -> "IO_FAILURE"
        }
        state.set(
            TopologyCacheAuditState(
                storedTopologySchema = inspection.storedSchema,
                status = status,
                environmentCompatible = inspection.environmentCompatible,
            ),
        )
    }

    fun recordRead(result: CacheRead<*>) {
        state.updateAndGetApi23 { current ->
            when (result) {
                is CacheRead.Hit -> current.copy(
                    storedTopologySchema = CameraSchemaVersions.TOPOLOGY,
                    status = "HIT",
                    environmentCompatible = true,
                    migrated = false,
                )
                CacheRead.Miss -> when (current.status) {
                    "REJECTED_SCHEMA", "REJECTED_ENVIRONMENT", "ABSENT" -> current
                    else -> current.copy(status = "MISS")
                }
                CacheRead.Stale -> current.copy(status = "STALE")
                is CacheRead.Corrupt -> current.copy(status = "CORRUPT")
                is CacheRead.IoFailure -> current.copy(status = "IO_FAILURE")
            }
        }
    }

    fun recordWrite(result: CacheWrite) {
        if (result != CacheWrite.Success) return
        state.updateAndGetApi23 { current ->
            val migrated = current.status == "REJECTED_SCHEMA"
            TopologyCacheAuditState(
                storedTopologySchema = CameraSchemaVersions.TOPOLOGY,
                status = if (migrated) "MIGRATED" else "WRITTEN",
                environmentCompatible = true,
                migrated = migrated,
            )
        }
    }

    private inline fun <T> AtomicReference<T>.updateAndGetApi23(transform: (T) -> T): T {
        while (true) {
            val current = get()
            val updated = transform(current)
            if (compareAndSet(current, updated)) return updated
        }
    }
}

/**
 * Read-only PARITY-4 probe over bytes already obtained through bounded cache IO. It inspects only
 * the envelope and environment prefix; it never reinterprets legacy canonical fingerprints or
 * publishes topology. Authoritative acceptance still goes through TopologyCacheCodec.decode().
 */
internal object TopologyCacheMigrationInspector {
    fun inspectBytes(
        bytes: ByteArray,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): TopologyCacheInspection {
        if (bytes.size < CacheBounds.ENVELOPE_BYTES || bytes.size > CacheBounds.TOPOLOGY_FILE_BYTES) {
            return TopologyCacheInspection(TopologyCacheInspectionStatus.CORRUPT)
        }
        return try {
            val header = ByteBuffer.wrap(bytes, 0, CacheBounds.ENVELOPE_BYTES)
            val magic = header.int
            val format = header.int
            val schema = header.int
            val payloadLength = header.int
            val expectedCrc = header.int
            if (magic != CacheEnvelope.TOPOLOGY_MAGIC ||
                payloadLength < 0 ||
                payloadLength > CacheBounds.TOPOLOGY_PAYLOAD_BYTES ||
                CacheBounds.ENVELOPE_BYTES.toLong() + payloadLength.toLong() != bytes.size.toLong()
            ) {
                return TopologyCacheInspection(
                    status = TopologyCacheInspectionStatus.CORRUPT,
                    storedSchema = schema.takeIf { it > 0 },
                )
            }
            val payload = bytes.copyOfRange(CacheBounds.ENVELOPE_BYTES, bytes.size)
            val actualCrc = CRC32().apply { update(payload) }.value.toInt()
            if (actualCrc != expectedCrc) {
                return TopologyCacheInspection(
                    status = TopologyCacheInspectionStatus.CORRUPT,
                    storedSchema = schema.takeIf { it > 0 },
                )
            }
            val reader = CacheBinaryReader(payload)
            val storedEnvironment = CameraEnvironmentFingerprint(
                reader.readString(CacheBounds.ENVIRONMENT_BYTES, "environment"),
            )
            val environmentCompatible = storedEnvironment == expectedEnvironment
            val status = when {
                format != CacheEnvelope.FORMAT_VERSION || schema != CameraSchemaVersions.TOPOLOGY ->
                    TopologyCacheInspectionStatus.INCOMPATIBLE_SCHEMA
                !environmentCompatible -> TopologyCacheInspectionStatus.INCOMPATIBLE_ENVIRONMENT
                else -> TopologyCacheInspectionStatus.COMPATIBLE
            }
            TopologyCacheInspection(
                status = status,
                storedSchema = schema,
                environmentCompatible = environmentCompatible,
            )
        } catch (_: Exception) {
            TopologyCacheInspection(TopologyCacheInspectionStatus.CORRUPT)
        }
    }
}
