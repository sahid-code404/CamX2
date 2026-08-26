package com.sahidcode404.camx.core.camera.discovery

import com.sahidcode404.camx.core.camera.model.CameraCapabilities
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraMetadataEvidence
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.CameraTransportId
import com.sahidcode404.camx.core.camera.model.CameraTrust
import com.sahidcode404.camx.core.camera.model.LensFacing
import com.sahidcode404.camx.core.camera.model.frozenCopy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal const val SEED_MAX_ADVERTISED_IDS = 64
internal const val SEED_MAX_FOCAL_LENGTHS = 16

enum class SeedDiscoveryFailureKind {
    ID_ENUMERATION_UNAVAILABLE,
    ADVERTISED_ID_LIMIT_EXCEEDED,
    INVALID_ADVERTISED_ID,
    CHARACTERISTICS_UNAVAILABLE,
    CHARACTERISTICS_INVALID,
}

data class SeedDiscoveryFailure(
    val transportId: CameraTransportId?,
    val kind: SeedDiscoveryFailureKind,
)

data class SeedDiscoveryResult(
    val route: CameraRoute?,
    val evidenceSnapshot: CameraEvidenceSnapshot,
    val failures: List<SeedDiscoveryFailure>,
    val advertisedIdCount: Int,
    val examinedIdCount: Int,
    val batchLimitExceeded: Boolean,
) {
    init {
        require(advertisedIdCount >= 0) { "Advertised camera ID count cannot be negative" }
        require(examinedIdCount in 0..advertisedIdCount) {
            "Examined camera ID count cannot exceed advertised count"
        }
        require(!batchLimitExceeded || examinedIdCount == 0) {
            "An oversized seed batch must fail closed before characteristics reads"
        }
    }
}

/**
 * CAMX-102-only evidence. It deliberately carries no complete stream inventory, RAW capability,
 * physical-camera relationship, or deep-discovery data.
 */
internal data class SeedCameraEvidence(
    val metadata: CameraMetadataEvidence,
    val privatePreviewOutputAdvertised: Boolean,
    val backwardCompatibleAdvertised: Boolean?,
) {
    init {
        require(metadata.source == CameraRouteSource.JAVA_PUBLIC) {
            "Seed evidence must come from the public Java Camera2 backend"
        }
        require(metadata.physicalId == null && metadata.logicalParentId == null) {
            "CAMX-102 seed discovery does not reconcile logical/physical camera relationships"
        }
        require(metadata.capabilities == CameraCapabilities()) {
            "CAMX-102 seed discovery must not publish a partial complete-capability inventory"
        }
        require(metadata.activeArray == null && metadata.pixelArray == null &&
            metadata.sensorOrientationDegrees == null && metadata.apertureValues.isEmpty() &&
            metadata.colorFilterArrangement == null
        ) { "CAMX-102 seed evidence contains metadata reserved for later complete discovery" }
        require(metadata.focalLengthsMillimetres.size <= SEED_MAX_FOCAL_LENGTHS) {
            "Seed focal-length evidence exceeds its explicit bound"
        }
    }
}

internal interface PublicCameraSeedMetadataSource {
    fun advertisedCameraIds(): List<String>
    fun readSeedEvidence(transportId: CameraTransportId): SeedCameraEvidence
}

private data class OrderedTransportId(
    val transportId: CameraTransportId,
    val orderKey: String,
)

private data class RankedSeedEvidence(
    val evidence: SeedCameraEvidence,
    val orderKey: String,
)

/**
 * Owns one bounded, cancellable first-install metadata batch. It never opens a camera and never owns
 * CameraCharacteristics beyond the source's single synchronous read for one advertised public ID.
 */
internal class MinimalFirstInstallSeedDiscovery(
    private val source: PublicCameraSeedMetadataSource,
    private val environment: CameraEnvironmentFingerprint,
    private val elapsedRealtimeNs: () -> Long,
) {
    suspend fun discover(): SeedDiscoveryResult {
        coroutineContext.ensureActive()
        val advertised = try {
            source.advertisedCameraIds()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return result(
                route = null,
                evidence = emptyList(),
                failures = listOf(
                    SeedDiscoveryFailure(null, SeedDiscoveryFailureKind.ID_ENUMERATION_UNAVAILABLE),
                ),
                advertisedIdCount = 0,
                examinedIdCount = 0,
                batchLimitExceeded = false,
            )
        }
        coroutineContext.ensureActive()

        if (advertised.size > SEED_MAX_ADVERTISED_IDS) {
            return result(
                route = null,
                evidence = emptyList(),
                failures = listOf(
                    SeedDiscoveryFailure(null, SeedDiscoveryFailureKind.ADVERTISED_ID_LIMIT_EXCEEDED),
                ),
                advertisedIdCount = advertised.size,
                examinedIdCount = 0,
                batchLimitExceeded = true,
            )
        }

        val boundedAdvertised = ArrayList(advertised)
        val failures = ArrayList<SeedDiscoveryFailure>(boundedAdvertised.size)
        val unique = LinkedHashMap<String, CameraTransportId>(boundedAdvertised.size)
        for (rawId in boundedAdvertised) {
            coroutineContext.ensureActive()
            if (rawId.isBlank()) {
                failures += SeedDiscoveryFailure(null, SeedDiscoveryFailureKind.INVALID_ADVERTISED_ID)
                continue
            }
            if (!unique.containsKey(rawId)) unique[rawId] = CameraTransportId(rawId)
        }

        val orderedIds = unique.values
            .map { OrderedTransportId(it, opaqueOrderKey(it)) }
            .sortedBy(OrderedTransportId::orderKey)
        val successful = ArrayList<RankedSeedEvidence>(orderedIds.size)
        var examined = 0
        for (orderedId in orderedIds) {
            val transportId = orderedId.transportId
            coroutineContext.ensureActive()
            examined += 1
            val seedEvidence = try {
                source.readSeedEvidence(transportId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures += SeedDiscoveryFailure(
                    transportId,
                    SeedDiscoveryFailureKind.CHARACTERISTICS_UNAVAILABLE,
                )
                continue
            }
            coroutineContext.ensureActive()
            if (!validForRequestedRoute(seedEvidence, transportId)) {
                failures += SeedDiscoveryFailure(
                    transportId,
                    SeedDiscoveryFailureKind.CHARACTERISTICS_INVALID,
                )
                continue
            }
            successful += RankedSeedEvidence(
                evidence = seedEvidence.copy(metadata = seedEvidence.metadata.frozenCopy()),
                orderKey = orderedId.orderKey,
            )
        }
        coroutineContext.ensureActive()

        val selected = successful
            .asSequence()
            .filter { it.evidence.privatePreviewOutputAdvertised }
            .minWithOrNull(seedCandidateComparator)
        val route = selected?.evidence?.let(::toAdvertisedRoute)
        return result(
            route = route,
            evidence = successful.map { it.evidence.metadata },
            failures = failures,
            advertisedIdCount = advertised.size,
            examinedIdCount = examined,
            batchLimitExceeded = false,
        )
    }

    private fun result(
        route: CameraRoute?,
        evidence: List<CameraMetadataEvidence>,
        failures: List<SeedDiscoveryFailure>,
        advertisedIdCount: Int,
        examinedIdCount: Int,
        batchLimitExceeded: Boolean,
    ): SeedDiscoveryResult {
        val frozenEvidence = Collections.unmodifiableList(
            ArrayList(evidence.map(CameraMetadataEvidence::frozenCopy)),
        )
        val frozenFailures = Collections.unmodifiableList(ArrayList(failures))
        return SeedDiscoveryResult(
            route = route,
            evidenceSnapshot = CameraEvidenceSnapshot(
                source = CameraRouteSource.JAVA_PUBLIC,
                environment = environment,
                evidence = frozenEvidence,
                completedAtElapsedRealtimeNs = elapsedRealtimeNs(),
            ),
            failures = frozenFailures,
            advertisedIdCount = advertisedIdCount,
            examinedIdCount = examinedIdCount,
            batchLimitExceeded = batchLimitExceeded,
        )
    }

    private fun validForRequestedRoute(
        evidence: SeedCameraEvidence,
        transportId: CameraTransportId,
    ): Boolean = evidence.metadata.transportId == transportId &&
        evidence.metadata.source == CameraRouteSource.JAVA_PUBLIC &&
        evidence.metadata.physicalId == null &&
        evidence.metadata.logicalParentId == null &&
        evidence.metadata.focalLengthsMillimetres.size <= SEED_MAX_FOCAL_LENGTHS

    private fun toAdvertisedRoute(candidate: SeedCameraEvidence): CameraRoute = CameraRoute(
        id = routeIdFor(candidate.metadata.transportId),
        source = CameraRouteSource.JAVA_PUBLIC,
        openCameraId = candidate.metadata.transportId,
        physicalCameraId = null,
        capabilities = CameraCapabilities(),
        metadataTrust = CameraTrust.ADVERTISED,
        sources = setOf(CameraRouteSource.JAVA_PUBLIC),
    )

    private companion object {
        val seedCandidateComparator: Comparator<RankedSeedEvidence> = compareBy(
            { -facingRank(it.evidence.metadata.facing) },
            { -compatibilityRank(it.evidence.backwardCompatibleAdvertised) },
            { -opticalEvidenceRank(it.evidence.metadata) },
            RankedSeedEvidence::orderKey,
        )

        fun facingRank(facing: LensFacing): Int = when (facing) {
            LensFacing.BACK -> 3
            LensFacing.FRONT -> 2
            LensFacing.EXTERNAL -> 1
            LensFacing.UNKNOWN -> 0
        }

        fun compatibilityRank(backwardCompatibleAdvertised: Boolean?): Int = when (backwardCompatibleAdvertised) {
            true -> 2
            null -> 1
            false -> 0
        }

        fun opticalEvidenceRank(metadata: CameraMetadataEvidence): Int {
            val hasFocal = metadata.focalLengthsMillimetres.isNotEmpty()
            val hasPhysicalSize = metadata.sensorPhysicalWidthMillimetres != null &&
                metadata.sensorPhysicalHeightMillimetres != null
            return when {
                hasFocal && hasPhysicalSize -> 2
                hasFocal || hasPhysicalSize -> 1
                else -> 0
            }
        }

        fun opaqueOrderKey(transportId: CameraTransportId): String =
            digestHex("seed-order|${transportId.value}", bytesToKeep = 32)

        fun routeIdFor(transportId: CameraTransportId): CameraRouteId =
            CameraRouteId("route:${digestHex("${transportId.value}|", bytesToKeep = 16)}")

        fun digestHex(value: String, bytesToKeep: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
            require(bytesToKeep in 1..digest.size)
            val alphabet = "0123456789abcdef"
            val chars = CharArray(bytesToKeep * 2)
            var output = 0
            for (index in 0 until bytesToKeep) {
                val byte = digest[index].toInt() and 0xff
                chars[output++] = alphabet[byte ushr 4]
                chars[output++] = alphabet[byte and 0x0f]
            }
            return String(chars)
        }
    }
}
