package com.sahidcode404.camx.core.camera.discovery

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val DEFAULT_JAVA_METADATA_LANES = 3
internal const val DEFAULT_NATIVE_METADATA_LANES = 1
internal const val HARD_MAX_METADATA_PRESSURE = 4

/**
 * Shared low-frequency HAL metadata budget. Java and native discovery use separate lanes so a
 * wedged Java metadata call cannot consume the native lane, while their configured sum remains
 * globally bounded.
 */
internal class DiscoveryMetadataBudget(
    val javaLanes: Int = DEFAULT_JAVA_METADATA_LANES,
    val nativeLanes: Int = DEFAULT_NATIVE_METADATA_LANES,
) {
    init {
        require(javaLanes in 1..HARD_MAX_METADATA_PRESSURE) { "Java metadata lane count is out of bounds" }
        require(nativeLanes in 1..HARD_MAX_METADATA_PRESSURE) { "Native metadata lane count is out of bounds" }
        require(javaLanes + nativeLanes <= HARD_MAX_METADATA_PRESSURE) {
            "Combined discovery metadata pressure exceeds $HARD_MAX_METADATA_PRESSURE"
        }
    }

    private val javaSemaphore = Semaphore(javaLanes)
    private val nativeSemaphore = Semaphore(nativeLanes)

    suspend fun <T> withJavaMetadata(block: suspend () -> T): T =
        javaSemaphore.withPermit { block() }

    suspend fun <T> withNativeMetadata(block: suspend () -> T): T =
        nativeSemaphore.withPermit { block() }

    val maximumEffectivePressure: Int
        get() = javaLanes + nativeLanes
}
