package com.sahidcode404.camx.core.camera.acquisition

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

@JvmInline
value class ManifestSourceId(val value: String) {
    init {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Manifest source ID must be lowercase SHA-256"
        }
    }
}

data class CorpusStratumKey(
    val representation: String,
    val sourceFormat: PublicSourceFormat,
    val width: Int,
    val height: Int,
    val canonicalLens: String,
    val profile: String,
    val sensorPixelMode: SensorPixelMode,
    val cfaPattern: CfaPattern?,
) {
    internal fun stableKey(): String = listOf(
        representation,
        sourceFormat.name,
        width.toString(),
        height.toString(),
        canonicalLens,
        profile,
        sensorPixelMode.name,
        cfaPattern?.name.orEmpty(),
    ).joinToString("\u0000")
}

class SourceManifestRecord private constructor(
    val sourceId: ManifestSourceId,
    val identity: AcquisitionIdentity,
    val canonicalRaster: CanonicalRasterDigest,
    val descriptorSha256: String,
) {
    init {
        require(descriptorSha256.length == 64 && descriptorSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Descriptor digest must be lowercase SHA-256"
        }
        require(canonicalRaster.byteCount == identity.representation.canonicalByteCount()) {
            "Manifest raster byte count must match its immutable representation descriptor"
        }
    }

    fun stratum(): CorpusStratumKey = CorpusStratumKey(
        representation = identity.representation.representationName(),
        sourceFormat = identity.representation.sourceFormat,
        width = identity.representation.size.width,
        height = identity.representation.size.height,
        canonicalLens = identity.canonicalLensFingerprint.value,
        profile = identity.cameraProfileFingerprint.value,
        sensorPixelMode = identity.representation.sensorPixelMode,
        cfaPattern = identity.representation.cfaPattern,
    )

    companion object {
        fun create(
            identity: AcquisitionIdentity,
            canonicalRaster: CanonicalRasterDigest,
        ): SourceManifestRecord {
            val descriptorDigest = CanonicalRasterHasher.descriptorSha256(identity.representation)
            return SourceManifestRecord(
                sourceId = ManifestSourceId(
                    sourceIdDigest(identity, canonicalRaster, descriptorDigest),
                ),
                identity = identity,
                canonicalRaster = canonicalRaster,
                descriptorSha256 = descriptorDigest,
            )
        }
    }
}

class SourceCorpusSnapshot internal constructor(records: List<SourceManifestRecord>) {
    val records: List<SourceManifestRecord> = Collections.unmodifiableList(
        ArrayList(records.sortedBy { it.sourceId.value }),
    )

    fun stratifiedCounts(): Map<CorpusStratumKey, Int> {
        val ordered = records
            .groupingBy(SourceManifestRecord::stratum)
            .eachCount()
            .entries
            .sortedBy { it.key.stableKey() }
        val result = LinkedHashMap<CorpusStratumKey, Int>(ordered.size)
        ordered.forEach { result[it.key] = it.value }
        return Collections.unmodifiableMap(result)
    }
}

/**
 * Bounded mutable ingest builder whose published benchmark corpus is immutable. It never owns camera
 * resources; callers add only validated source manifest records after canonical hashing.
 */
class BoundedSourceCorpusBuilder(
    private val maxEntries: Int,
    private val maxCanonicalBytes: Long,
) {
    private val records = LinkedHashMap<ManifestSourceId, SourceManifestRecord>()
    private var canonicalBytes = 0L

    init {
        require(maxEntries in 1..M1AcquisitionLimits.MAX_CORPUS_ENTRIES) {
            "Corpus entry bound is outside M1 limits"
        }
        require(maxCanonicalBytes in 1..M1AcquisitionLimits.MAX_CORPUS_CANONICAL_BYTES) {
            "Corpus byte bound is outside M1 limits"
        }
    }

    @Synchronized
    fun add(record: SourceManifestRecord) {
        require(record.sourceId !in records) { "Corpus source record already exists" }
        require(records.size < maxEntries) { "Corpus entry bound reached" }
        val nextBytes = try {
            Math.addExact(canonicalBytes, record.canonicalRaster.byteCount)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Corpus canonical-byte total overflow", error)
        }
        require(nextBytes <= maxCanonicalBytes) { "Corpus canonical-byte bound reached" }
        records[record.sourceId] = record
        canonicalBytes = nextBytes
    }

    @Synchronized
    fun freeze(): SourceCorpusSnapshot = SourceCorpusSnapshot(records.values.toList())

    @Synchronized
    fun size(): Int = records.size

    @Synchronized
    fun canonicalByteCount(): Long = canonicalBytes
}

private fun sourceIdDigest(
    identity: AcquisitionIdentity,
    raster: CanonicalRasterDigest,
    descriptorDigest: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun token(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
    token(identity.canonicalLensFingerprint.value)
    token(identity.cameraProfileFingerprint.value)
    token(identity.routeId.value)
    token(identity.physicalTarget?.value.orEmpty())
    token(identity.providerEpoch.toString())
    token(identity.selectionGeneration.value.toString())
    token(identity.sessionGeneration.value.toString())
    token(identity.captureToken.value.toString())
    token(identity.captureGeneration?.toString().orEmpty())
    token(identity.surfaceGeneration?.toString().orEmpty())
    token(identity.timebase.imageTimestampNs.toString())
    token(identity.timebase.captureResultTimestampNs?.toString().orEmpty())
    token(identity.timebase.declaredTimebase.name)
    token(descriptorDigest)
    token(raster.sha256)
    token(raster.byteCount.toString())
    return digest.digest().toLowerHex()
}
