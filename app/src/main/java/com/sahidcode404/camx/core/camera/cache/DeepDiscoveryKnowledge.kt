package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DeepDiscoveryKnowledge(
    val schema: Int,
    val environment: CameraEnvironmentFingerprint,
    val advertisedTopologySignature: String,
    val successfulDeepIds: List<String>,
    val sessionVerifiedDeepIds: List<String>,
    val fullReconciliationComplete: Boolean,
) {
    init {
        require(schema == CameraSchemaVersions.DEEP_DISCOVERY) { "Unsupported deep discovery schema" }
        require(advertisedTopologySignature.isNotBlank()) { "Advertised topology signature cannot be blank" }
        require(
            advertisedTopologySignature.toByteArray(StandardCharsets.UTF_8).size <= CacheBounds.SIGNATURE_BYTES,
        ) { "Advertised topology signature exceeds cache bound" }
        val combined = (successfulDeepIds + sessionVerifiedDeepIds).distinct()
        require(combined.size <= CacheBounds.DEEP_CANDIDATES) { "Deep candidate knowledge exceeds cache bound" }
        require(combined.all(::isSafePersistedDeepId)) { "Deep candidate identifier is outside cache bounds" }
    }

    fun frozenCopy(): DeepDiscoveryKnowledge {
        val verified = normalizeDeepIds(sessionVerifiedDeepIds)
        val verifiedSet = verified.toSet()
        val successful = normalizeDeepIds(successfulDeepIds).filterNot { it in verifiedSet }
        require(successful.size + verified.size <= CacheBounds.DEEP_CANDIDATES) {
            "Deep candidate knowledge exceeds cache bound"
        }
        return copy(
            successfulDeepIds = immutableList(successful),
            sessionVerifiedDeepIds = immutableList(verified),
        )
    }
}

internal interface DeepDiscoveryKnowledgePersistence {
    suspend fun readDeepKnowledge(
        environment: CameraEnvironmentFingerprint,
    ): CacheRead<DeepDiscoveryKnowledge>

    suspend fun writeDeepKnowledge(knowledge: DeepDiscoveryKnowledge): CacheWrite
}

/** Low-frequency bounded repository for opaque successful and session-verified deep route history. */
internal class DeepDiscoveryKnowledgeRepository(
    private val persistence: DeepDiscoveryKnowledgePersistence,
) {
    private val memory = AtomicReference<DeepDiscoveryKnowledge?>(null)
    private val mutationMutex = Mutex()

    fun current(): DeepDiscoveryKnowledge? = memory.get()

    /** Diagnostic cache reset only; callers serialize this against discovery reconciliation. */
    fun forgetCurrent() {
        memory.set(null)
    }

    suspend fun load(environment: CameraEnvironmentFingerprint): CacheRead<DeepDiscoveryKnowledge> =
        mutationMutex.withLock {
            memory.set(memory.get()?.takeIf { it.environment == environment })
            val result = try {
                persistence.readDeepKnowledge(environment)
            } catch (error: Exception) {
                CacheRead.IoFailure(error.message ?: error.javaClass.simpleName)
            }
            when (result) {
                is CacheRead.Hit -> {
                    val candidate = result.value
                    if (candidate.schema != CameraSchemaVersions.DEEP_DISCOVERY || candidate.environment != environment) {
                        memory.set(null)
                        CacheRead.Corrupt("Deep knowledge schema or environment mismatch")
                    } else {
                        val frozen = candidate.frozenCopy()
                        memory.set(frozen)
                        CacheRead.Hit(frozen)
                    }
                }
                CacheRead.Miss -> {
                    memory.set(null)
                    CacheRead.Miss
                }
                CacheRead.Stale -> CacheRead.Stale
                is CacheRead.Corrupt -> {
                    memory.set(null)
                    result
                }
                is CacheRead.IoFailure -> result
            }
        }

    suspend fun recordSuccessful(
        environment: CameraEnvironmentFingerprint,
        advertisedTopologySignature: String,
        ids: Collection<String>,
        reconciliationComplete: Boolean,
    ): Boolean = mutationMutex.withLock {
        val accepted = normalizeDeepIds(ids).take(CacheBounds.DEEP_CANDIDATES)
        if (accepted.isEmpty()) return@withLock true
        val existing = memory.get()?.takeIf { it.environment == environment }
        val verified = existing?.sessionVerifiedDeepIds.orEmpty()
        val successful = normalizeDeepIds(existing?.successfulDeepIds.orEmpty() + accepted)
            .filterNot { it in verified.toSet() }
            .take((CacheBounds.DEEP_CANDIDATES - verified.size).coerceAtLeast(0))
        persist(
            DeepDiscoveryKnowledge(
                schema = CameraSchemaVersions.DEEP_DISCOVERY,
                environment = environment,
                advertisedTopologySignature = advertisedTopologySignature,
                successfulDeepIds = successful,
                sessionVerifiedDeepIds = verified,
                fullReconciliationComplete = reconciliationComplete &&
                    (existing?.fullReconciliationComplete != false),
            ).frozenCopy(),
        )
    }

    suspend fun markSessionVerified(
        environment: CameraEnvironmentFingerprint,
        candidateId: String,
    ): Boolean = mutationMutex.withLock {
        if (!isSafePersistedDeepId(candidateId)) return@withLock false
        val existing = memory.get()?.takeIf { it.environment == environment } ?: return@withLock false
        if (candidateId !in existing.successfulDeepIds && candidateId !in existing.sessionVerifiedDeepIds) {
            return@withLock false
        }
        val verified = normalizeDeepIds(existing.sessionVerifiedDeepIds + candidateId)
            .take(CacheBounds.DEEP_CANDIDATES)
        val verifiedSet = verified.toSet()
        val successful = normalizeDeepIds(existing.successfulDeepIds)
            .filterNot { it in verifiedSet }
            .take((CacheBounds.DEEP_CANDIDATES - verified.size).coerceAtLeast(0))
        persist(
            existing.copy(
                successfulDeepIds = successful,
                sessionVerifiedDeepIds = verified,
            ).frozenCopy(),
        )
    }

    suspend fun completeReconciliation(
        environment: CameraEnvironmentFingerprint,
        advertisedTopologySignature: String,
        successfulThisPass: Collection<String>,
        conclusivelyRetiredIds: Collection<String> = emptyList(),
    ): Boolean = mutationMutex.withLock {
        val existing = memory.get()?.takeIf { it.environment == environment }
        val retired = normalizeDeepIds(conclusivelyRetiredIds).toSet()
        val verified = normalizeDeepIds(existing?.sessionVerifiedDeepIds.orEmpty())
            .filterNot { it in retired }
            .take(CacheBounds.DEEP_CANDIDATES)
        val verifiedSet = verified.toSet()
        val successful = normalizeDeepIds(
            existing?.successfulDeepIds.orEmpty() + successfulThisPass,
        ).filterNot { it in retired || it in verifiedSet }
            .take((CacheBounds.DEEP_CANDIDATES - verified.size).coerceAtLeast(0))
        persist(
            DeepDiscoveryKnowledge(
                schema = CameraSchemaVersions.DEEP_DISCOVERY,
                environment = environment,
                advertisedTopologySignature = advertisedTopologySignature,
                successfulDeepIds = successful,
                sessionVerifiedDeepIds = verified,
                fullReconciliationComplete = true,
            ).frozenCopy(),
        )
    }

    suspend fun recordStableEmptyReconciliation(
        environment: CameraEnvironmentFingerprint,
        advertisedTopologySignature: String,
    ): Boolean = completeReconciliation(
        environment = environment,
        advertisedTopologySignature = advertisedTopologySignature,
        successfulThisPass = emptyList(),
    )

    private suspend fun persist(value: DeepDiscoveryKnowledge): Boolean {
        memory.set(value)
        return try {
            persistence.writeDeepKnowledge(value) == CacheWrite.Success
        } catch (_: Exception) {
            false
        }
    }
}

internal object DeepDiscoveryKnowledgeCodec {
    fun encode(knowledge: DeepDiscoveryKnowledge): ByteArray {
        val value = knowledge.frozenCopy()
        val writer = CacheBinaryWriter()
        writer.writeString(value.environment.value, CacheBounds.ENVIRONMENT_BYTES, "environment")
        writer.writeString(
            value.advertisedTopologySignature,
            CacheBounds.SIGNATURE_BYTES,
            "advertised topology signature",
        )
        writer.writeBoolean(value.fullReconciliationComplete)
        writer.writeInt(value.sessionVerifiedDeepIds.size)
        value.sessionVerifiedDeepIds.forEach {
            writer.writeString(it, CacheBounds.IDENTIFIER_BYTES, "session verified deep ID")
        }
        writer.writeInt(value.successfulDeepIds.size)
        value.successfulDeepIds.forEach {
            writer.writeString(it, CacheBounds.IDENTIFIER_BYTES, "successful deep ID")
        }
        return CacheEnvelope.encode(
            magic = CacheEnvelope.DEEP_MAGIC,
            schema = CameraSchemaVersions.DEEP_DISCOVERY,
            maximumPayloadBytes = CacheBounds.DEEP_PAYLOAD_BYTES,
            payload = writer.toByteArray(),
        )
    }

    fun decode(
        bytes: ByteArray,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CacheRead<DeepDiscoveryKnowledge> = when (
        val envelope = CacheEnvelope.decode(
            bytes = bytes,
            expectedMagic = CacheEnvelope.DEEP_MAGIC,
            expectedSchema = CameraSchemaVersions.DEEP_DISCOVERY,
            maximumPayloadBytes = CacheBounds.DEEP_PAYLOAD_BYTES,
        )
    ) {
        CacheEnvelope.Decoded.Unsupported -> CacheRead.Miss
        is CacheEnvelope.Decoded.Corrupt -> CacheRead.Corrupt(envelope.reason)
        is CacheEnvelope.Decoded.Payload -> decodePayload(envelope.bytes, expectedEnvironment)
    }

    private fun decodePayload(
        payload: ByteArray,
        expectedEnvironment: CameraEnvironmentFingerprint,
    ): CacheRead<DeepDiscoveryKnowledge> {
        return try {
            val reader = CacheBinaryReader(payload)
            val environment = CameraEnvironmentFingerprint(
                reader.readString(CacheBounds.ENVIRONMENT_BYTES, "environment"),
            )
            if (environment != expectedEnvironment) return CacheRead.Miss
            val signature = reader.readString(CacheBounds.SIGNATURE_BYTES, "advertised topology signature")
            val complete = reader.readBoolean("full reconciliation complete")
            val verifiedCount = reader.readCount(CacheBounds.DEEP_CANDIDATES, "session verified deep count")
            val verified = ArrayList<String>(verifiedCount)
            repeat(verifiedCount) {
                verified += reader.readString(CacheBounds.IDENTIFIER_BYTES, "session verified deep ID")
            }
            val successfulCount = reader.readCount(
                (CacheBounds.DEEP_CANDIDATES - verifiedCount).coerceAtLeast(0),
                "successful deep count",
            )
            val successful = ArrayList<String>(successfulCount)
            repeat(successfulCount) {
                successful += reader.readString(CacheBounds.IDENTIFIER_BYTES, "successful deep ID")
            }
            reader.requireExhausted()
            CacheRead.Hit(
                DeepDiscoveryKnowledge(
                    schema = CameraSchemaVersions.DEEP_DISCOVERY,
                    environment = environment,
                    advertisedTopologySignature = signature,
                    successfulDeepIds = successful,
                    sessionVerifiedDeepIds = verified,
                    fullReconciliationComplete = complete,
                ).frozenCopy(),
            )
        } catch (error: Exception) {
            CacheRead.Corrupt(error.message ?: "Malformed deep discovery cache")
        }
    }
}

private fun isSafePersistedDeepId(value: String): Boolean {
    if (value.isBlank() || value.any(Char::isISOControl)) return false
    return value.toByteArray(StandardCharsets.UTF_8).size <= CacheBounds.IDENTIFIER_BYTES
}

private fun normalizeDeepIds(values: Collection<String>): List<String> = values.asSequence()
    .filter(::isSafePersistedDeepId)
    .distinct()
    .sorted()
    .toList()

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
