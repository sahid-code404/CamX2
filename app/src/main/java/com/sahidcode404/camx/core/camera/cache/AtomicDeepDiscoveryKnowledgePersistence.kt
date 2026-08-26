package com.sahidcode404.camx.core.camera.cache

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint

/** Internal Checkpoint-C bridge that keeps deep knowledge off the public atomic-cache API. */
internal class AtomicDeepDiscoveryKnowledgePersistence(
    private val persistence: AtomicCameraCachePersistence,
) : DeepDiscoveryKnowledgePersistence {
    override suspend fun readDeepKnowledge(
        environment: CameraEnvironmentFingerprint,
    ): CacheRead<DeepDiscoveryKnowledge> = persistence.readDeepKnowledgeInternal(environment)

    override suspend fun writeDeepKnowledge(knowledge: DeepDiscoveryKnowledge): CacheWrite =
        persistence.writeDeepKnowledgeInternal(knowledge)
}
