package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepDiscoveryKnowledgeTest {
    private val environment = CameraEnvironmentFingerprint("deep-cache-environment")

    @Test
    fun `deep knowledge codec round trips opaque successful and verified ids`() {
        val original = knowledge(
            successful = listOf("opaque/vendor/aux"),
            verified = listOf("verified-hidden"),
        )

        val decoded = DeepDiscoveryKnowledgeCodec.decode(
            DeepDiscoveryKnowledgeCodec.encode(original),
            environment,
        )

        val hit = decoded as CacheRead.Hit<DeepDiscoveryKnowledge>
        assertEquals(listOf("opaque/vendor/aux"), hit.value.successfulDeepIds)
        assertEquals(listOf("verified-hidden"), hit.value.sessionVerifiedDeepIds)
        assertTrue(hit.value.fullReconciliationComplete)
    }

    @Test
    fun `deep cache bytes are deterministic across input permutations`() {
        val first = knowledge(
            successful = listOf("z-hidden", "a-hidden"),
            verified = listOf("verified-b", "verified-a"),
        )
        val second = knowledge(
            successful = listOf("a-hidden", "z-hidden"),
            verified = listOf("verified-a", "verified-b"),
        )

        assertArrayEquals(
            DeepDiscoveryKnowledgeCodec.encode(first),
            DeepDiscoveryKnowledgeCodec.encode(second),
        )
    }

    @Test
    fun `environment mismatch invalidates deep knowledge without trusting ids`() {
        val encoded = DeepDiscoveryKnowledgeCodec.encode(knowledge(successful = listOf("hidden")))

        val decoded = DeepDiscoveryKnowledgeCodec.decode(
            encoded,
            CameraEnvironmentFingerprint("different-environment"),
        )

        assertEquals(CacheRead.Miss, decoded)
    }

    @Test
    fun `corrupted deep cache fails closed`() {
        val encoded = DeepDiscoveryKnowledgeCodec.encode(knowledge(successful = listOf("hidden")))
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 0x01).toByte()

        val decoded = DeepDiscoveryKnowledgeCodec.decode(encoded, environment)

        assertTrue(decoded is CacheRead.Corrupt)
    }

    @Test
    fun `deep cache candidate bound is enforced`() {
        val result = runCatching {
            knowledge(successful = (0..CacheBounds.DEEP_CANDIDATES).map { "opaque-$it" })
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `repository promotes successful deep id to session verified only after explicit proof`() = runTest {
        val persistence = MemoryDeepPersistence(knowledge(successful = listOf("hidden")))
        val repository = DeepDiscoveryKnowledgeRepository(persistence)
        repository.load(environment)

        assertTrue(repository.markSessionVerified(environment, "hidden"))

        val current = repository.current()!!
        assertEquals(listOf("hidden"), current.sessionVerifiedDeepIds)
        assertFalse("hidden" in current.successfulDeepIds)
    }

    @Test
    fun `unknown id cannot be fabricated into session verified knowledge`() = runTest {
        val persistence = MemoryDeepPersistence(knowledge(successful = listOf("known")))
        val repository = DeepDiscoveryKnowledgeRepository(persistence)
        repository.load(environment)

        assertFalse(repository.markSessionVerified(environment, "unknown"))
        assertEquals(listOf("known"), repository.current()!!.successfulDeepIds)
    }

    @Test
    fun `successful reconciliation can retire conclusively incompatible id`() = runTest {
        val persistence = MemoryDeepPersistence(
            knowledge(successful = listOf("stale", "keep"), verified = listOf("verified-stale")),
        )
        val repository = DeepDiscoveryKnowledgeRepository(persistence)
        repository.load(environment)

        repository.completeReconciliation(
            environment = environment,
            advertisedTopologySignature = "signature-next",
            successfulThisPass = listOf("new"),
            conclusivelyRetiredIds = listOf("stale", "verified-stale"),
        )

        val current = repository.current()!!
        assertEquals(listOf("keep", "new"), current.successfulDeepIds)
        assertTrue(current.sessionVerifiedDeepIds.isEmpty())
        assertEquals("signature-next", current.advertisedTopologySignature)
        assertTrue(current.fullReconciliationComplete)
    }

    @Test
    fun `failed persistence does not erase compatible in memory deep history`() = runTest {
        val persistence = MemoryDeepPersistence(knowledge(successful = listOf("keep")))
        val repository = DeepDiscoveryKnowledgeRepository(persistence)
        repository.load(environment)
        persistence.failWrites = true

        val written = repository.recordSuccessful(
            environment = environment,
            advertisedTopologySignature = "stable-signature",
            ids = listOf("new"),
            reconciliationComplete = true,
        )

        assertFalse(written)
        assertTrue(repository.current()!!.successfulDeepIds.containsAll(listOf("keep", "new")))
    }

    @Test
    fun `stable empty reconciliation persists a skip capable knowledge record`() = runTest {
        val persistence = MemoryDeepPersistence(null)
        val repository = DeepDiscoveryKnowledgeRepository(persistence)

        assertTrue(repository.recordStableEmptyReconciliation(environment, "stable-empty"))

        val current = repository.current()!!
        assertTrue(current.successfulDeepIds.isEmpty())
        assertTrue(current.sessionVerifiedDeepIds.isEmpty())
        assertTrue(current.fullReconciliationComplete)
    }

    private fun knowledge(
        successful: List<String> = emptyList(),
        verified: List<String> = emptyList(),
    ) = DeepDiscoveryKnowledge(
        schema = CameraSchemaVersions.DEEP_DISCOVERY,
        environment = environment,
        advertisedTopologySignature = "stable-signature",
        successfulDeepIds = successful,
        sessionVerifiedDeepIds = verified,
        fullReconciliationComplete = true,
    ).frozenCopy()

    private class MemoryDeepPersistence(initial: DeepDiscoveryKnowledge?) : DeepDiscoveryKnowledgePersistence {
        var stored: DeepDiscoveryKnowledge? = initial
        var failWrites = false

        override suspend fun readDeepKnowledge(
            environment: CameraEnvironmentFingerprint,
        ): CacheRead<DeepDiscoveryKnowledge> {
            val value = stored ?: return CacheRead.Miss
            return if (value.environment == environment) CacheRead.Hit(value) else CacheRead.Miss
        }

        override suspend fun writeDeepKnowledge(knowledge: DeepDiscoveryKnowledge): CacheWrite {
            if (failWrites) return CacheWrite.IoFailure("forced")
            stored = knowledge
            return CacheWrite.Success
        }
    }
}
