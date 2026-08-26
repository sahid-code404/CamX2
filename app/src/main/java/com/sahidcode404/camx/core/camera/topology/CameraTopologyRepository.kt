package com.sahidcode404.camx.core.camera.topology

import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraSchemaVersions
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.frozenCopy
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TopologyPublicationPermit internal constructor(
    val environment: CameraEnvironmentFingerprint,
    internal val reconciliationSequence: Long,
)

class CameraTopologyRepository(initial: CameraTopologySnapshot? = null) {
    private val acceptedInitial = initial?.takeIf { it.schema == CameraSchemaVersions.TOPOLOGY }
    private val publicationSequence = AtomicLong(0L)
    private val mutableTopology = MutableStateFlow(acceptedInitial?.frozenCopy())
    private var activeEnvironment = acceptedInitial?.environment
    private var activeReconciliation = 0L
    private var lastPublishedReconciliation = -1L

    val topology: StateFlow<CameraTopologySnapshot?> = mutableTopology.asStateFlow()

    @Synchronized
    fun beginReconciliation(environment: CameraEnvironmentFingerprint): TopologyPublicationPermit {
        check(activeReconciliation < Long.MAX_VALUE) { "Topology reconciliation sequence exhausted" }
        activeReconciliation += 1L
        if (activeEnvironment != environment) {
            activeEnvironment = environment
            mutableTopology.value = null
        }
        return TopologyPublicationPermit(environment, activeReconciliation)
    }

    @Synchronized
    fun publish(
        snapshot: CameraTopologySnapshot,
        permit: TopologyPublicationPermit,
    ): Boolean {
        if (permit.reconciliationSequence != activeReconciliation ||
            permit.reconciliationSequence == lastPublishedReconciliation ||
            permit.environment != activeEnvironment ||
            snapshot.environment != permit.environment ||
            snapshot.schema != CameraSchemaVersions.TOPOLOGY
        ) return false
        publicationSequence.incrementAndGet()
        mutableTopology.value = snapshot.frozenCopy()
        lastPublishedReconciliation = permit.reconciliationSequence
        return true
    }

    fun publicationCount(): Long = publicationSequence.get()
}
