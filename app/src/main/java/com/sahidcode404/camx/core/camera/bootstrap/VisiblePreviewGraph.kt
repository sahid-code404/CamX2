package com.sahidcode404.camx.core.camera.bootstrap

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import com.sahidcode404.camx.core.camera.cache.AtomicCameraCachePersistence
import com.sahidcode404.camx.core.camera.cache.AtomicDeepDiscoveryKnowledgePersistence
import com.sahidcode404.camx.core.camera.cache.CacheRead
import com.sahidcode404.camx.core.camera.cache.CameraCacheRepository
import com.sahidcode404.camx.core.camera.cache.DeepDiscoveryKnowledgeRepository
import com.sahidcode404.camx.core.camera.cache.DiscoveryCacheResetResult
import com.sahidcode404.camx.core.camera.diagnostics.AuxDiscoveryAuditTracker
import com.sahidcode404.camx.core.camera.diagnostics.AuxHardwareAudit
import com.sahidcode404.camx.core.camera.diagnostics.AuxHardwareAuditSnapshot
import com.sahidcode404.camx.core.camera.diagnostics.DeepRescanCoordinator
import com.sahidcode404.camx.core.camera.diagnostics.DeepRescanRequestResult
import com.sahidcode404.camx.core.camera.diagnostics.LensSwitchDiagnostics
import com.sahidcode404.camx.core.camera.discovery.AndroidAdvertisedCameraEvidenceBackend
import com.sahidcode404.camx.core.camera.discovery.AndroidFirstInstallSeedDiscovery
import com.sahidcode404.camx.core.camera.discovery.DiscoveryDepth
import com.sahidcode404.camx.core.camera.discovery.DiscoveryMetadataBudget
import com.sahidcode404.camx.core.camera.discovery.JavaDeepControlCertifier
import com.sahidcode404.camx.core.camera.discovery.NdkAdvertisedCameraEvidenceBackend
import com.sahidcode404.camx.core.camera.discovery.NdkDeepAuxDiscoveryBackend
import com.sahidcode404.camx.core.camera.lens.CameraLensProjection
import com.sahidcode404.camx.core.camera.lens.CameraLensProjectionInput
import com.sahidcode404.camx.core.camera.lens.CameraLensUiProjector
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraEnvironmentFingerprint
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteSource
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.preview.GenerationSafePreviewSurfaceProvider
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceBinding
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceLease
import com.sahidcode404.camx.core.camera.raw.AndroidDngWriter
import com.sahidcode404.camx.core.camera.raw.RawCaptureOutcome
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.camera.session.CameraSessionController
import com.sahidcode404.camx.core.camera.topology.AdvertisedTopologyEvidenceProvider
import com.sahidcode404.camx.core.camera.topology.CameraTopologyRepository
import com.sahidcode404.camx.core.camera.topology.JavaDeepCertificationSource
import com.sahidcode404.camx.core.camera.topology.JavaLevel2EvidenceSource
import com.sahidcode404.camx.core.camera.topology.NdkDeepEvidenceSource
import com.sahidcode404.camx.core.camera.topology.NdkLevel2EvidenceSource
import com.sahidcode404.camx.core.camera.topology.PostFirstFrameAuxDiscoveryOrchestrator
import com.sahidcode404.camx.core.camera.topology.PostFirstFrameTopologyReconciler
import com.sahidcode404.camx.core.camera.topology.ReconciliationCompletion
import com.sahidcode404.camx.core.rawvideo.recording.AndroidSensorRawVideoStore
import com.sahidcode404.camx.core.rawvideo.recording.SensorRawVideoStartOutcome
import com.sahidcode404.camx.core.rawvideo.recording.SensorRawVideoStatus
import com.sahidcode404.camx.core.rawvideo.recording.SensorRawVideoStopOutcome
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Lifecycle-scoped production graph. CameraSessionController remains the sole Camera2 resource owner. */
class VisiblePreviewGraph(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val environment = runtimeEnvironmentFingerprint()
    private val controller = CameraSessionController(cameraManager)
    private val dngWriter = AndroidDngWriter(appContext)
    private val rawVideoStore = AndroidSensorRawVideoStore(appContext)
    private val seedDiscovery = AndroidFirstInstallSeedDiscovery(
        cameraManager = cameraManager,
        environment = environment,
    )
    private val metadataBudget = DiscoveryMetadataBudget()
    private val javaAdvertisedDiscovery = AndroidAdvertisedCameraEvidenceBackend(
        cameraManager = cameraManager,
        environment = environment,
        metadataBudget = metadataBudget,
    )
    private val ndkAdvertisedDiscovery = NdkAdvertisedCameraEvidenceBackend(environment)
    private val ndkDeepDiscovery = NdkDeepAuxDiscoveryBackend(
        environment = environment,
        metadataBudget = metadataBudget,
    )
    private val javaDeepCertifier = JavaDeepControlCertifier(
        cameraManager = cameraManager,
        environment = environment,
        metadataBudget = metadataBudget,
    )
    private val cachePersistence = AtomicCameraCachePersistence(
        File(appContext.filesDir, "camera-cache"),
    )
    private val cameraCacheRepository = CameraCacheRepository(cachePersistence)
    private val lensInventory = LensInventoryCoordinator(
        environment = environment,
        runtimeApiLevel = Build.VERSION.SDK_INT,
        clockNanos = SystemClock::elapsedRealtimeNanos,
    )
    private val deepKnowledgeRepository = DeepDiscoveryKnowledgeRepository(
        AtomicDeepDiscoveryKnowledgePersistence(cachePersistence),
    )
    private val surfaceBridge = AndroidVisiblePreviewSurfaceBridge()
    private val topologySignalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val explicitDeepRescanRequested = AtomicBoolean(false)
    private val activeInventoryRescan = AtomicReference<Long?>(null)
    private val firstFrameVerified = AtomicBoolean(false)
    private val structurallyFailedAuditProfiles = LinkedHashSet<CameraProfileFingerprint>()
    private val auditTracker = AuxDiscoveryAuditTracker(SystemClock::elapsedRealtimeNanos)
    private val latestSwitchDiagnostics = AtomicReference(LensSwitchDiagnostics())
    private val mutableAuxAudit = MutableStateFlow(AuxHardwareAuditSnapshot())

    val topologyRepository = CameraTopologyRepository()
    val auxAudit: StateFlow<AuxHardwareAuditSnapshot> = mutableAuxAudit.asStateFlow()
    val lensInventoryStatus: StateFlow<LensInventoryStatus> = lensInventory.status
    val photoCaptureAvailable: StateFlow<Boolean> = controller.rawPhotoAvailable
    val videoCaptureAvailable: StateFlow<Boolean> = controller.rawVideoAvailable
    val rawVideoStatus: StateFlow<SensorRawVideoStatus> = controller.rawVideoStatus

    private val auxDiscoveryOrchestrator = PostFirstFrameAuxDiscoveryOrchestrator(
        environment = environment,
        javaLevel2 = JavaLevel2EvidenceSource { emit ->
            val report = javaAdvertisedDiscovery.discoverIncrementally(DiscoveryDepth.ADVERTISED) { batch ->
                auditTracker.onJavaAdvertised(batch)
                emit(batch)
            }
            auditTracker.onJavaAdvertised(report)
            report
        },
        ndkLevel2 = NdkLevel2EvidenceSource {
            val report = metadataBudget.withNativeMetadata {
                ndkAdvertisedDiscovery.discoverReport(DiscoveryDepth.ADVERTISED)
            }
            auditTracker.onNdkAdvertised(report)
            report
        },
        ndkDeep = NdkDeepEvidenceSource { request, emit ->
            val report = ndkDeepDiscovery.discoverIncrementally(request) { batch ->
                auditTracker.onNdkDeep(batch)
                emit(batch)
            }
            auditTracker.onNdkDeep(report)
            report
        },
        javaDeep = JavaDeepCertificationSource { outcomes, existingJavaEvidence, emit ->
            val report = javaDeepCertifier.certifyIncrementally(outcomes, existingJavaEvidence) { batch ->
                auditTracker.onJavaDeep(batch)
                emit(batch)
            }
            auditTracker.onJavaDeep(report)
            report
        },
        deepKnowledge = deepKnowledgeRepository,
        explicitDeepRescan = { explicitDeepRescanRequested.get() },
    )

    private val observedAuxProvider = AdvertisedTopologyEvidenceProvider { emit ->
        auditTracker.beginRun(
            selectableCount = coordinator.lensItems.value.size,
            publicationCount = topologyRepository.publicationCount(),
        )
        try {
            auxDiscoveryOrchestrator.collect(emit)
        } finally {
            auditTracker.finishRun()
            if (!explicitDeepRescanRequested.get()) {
                persistInventoryCompletion(
                    lensInventory.completeAutomaticReconciliation(topologyRepository.topology.value),
                )
            }
        }
    }

    private val topologyReconciler = PostFirstFrameTopologyReconciler(
        environment = environment,
        repository = topologyRepository,
        providers = listOf(observedAuxProvider),
    )

    val coordinator = VisiblePreviewCoordinator(
        seedSource = VisiblePreviewSeedSource { seedDiscovery.discover().route },
        capabilitySource = AndroidSelectedSeedPreviewCapabilityReader(cameraManager),
        surfacePort = surfaceBridge,
        session = AndroidVisiblePreviewSessionPort(controller),
        topology = lensInventory.topology,
        stableOneXReference = lensInventory.stableOneXReference,
        runtimeApiLevel = Build.VERSION.SDK_INT,
        settings = { SettingsSnapshot() },
        clockNanos = SystemClock::elapsedRealtimeNanos,
        switchDiagnosticsSink = { diagnostics ->
            latestSwitchDiagnostics.set(diagnostics)
            // Keep this hot-path update bounded. Full topology/optical audit projection remains on
            // the existing low-frequency refresh paths below.
            mutableAuxAudit.value = mutableAuxAudit.value.copy(switch = diagnostics)
        },
    )

    private val deepRescanCoordinator = DeepRescanCoordinator(
        firstFrameVerified = { firstFrameVerified.get() },
        inventoryReady = lensInventory::isReadyForExplicitRescan,
        reconciliationRunning = topologyReconciler::isRunning,
        setExplicitDeepRescan = explicitDeepRescanRequested::set,
        requestReconciliation = { done ->
            topologyReconciler.requestReconciliationWithCompletion(
                preserveCurrentTopology = true,
                onFinished = done,
            )
        },
        onRescanStarted = {
            activeInventoryRescan.set(lensInventory.beginExplicitRescan())
        },
        onRescanFinished = { completion ->
            val generation = activeInventoryRescan.getAndSet(null)
            if (generation != null) {
                val inventoryCompletion = lensInventory.completeExplicitRescan(
                    generation = generation,
                    coherent = completion == ReconciliationCompletion.COMPLETE,
                    finalSnapshot = topologyRepository.topology.value,
                )
                topologySignalScope.launch(Dispatchers.IO) {
                    persistInventoryCompletion(inventoryCompletion)
                }
            }
        },
        resetCaches = {
            val hadDeepMemory = deepKnowledgeRepository.current() != null
            val disk = cachePersistence.resetDiscoveryCaches()
            if (disk != DiscoveryCacheResetResult.FAILED) deepKnowledgeRepository.forgetCurrent()
            if (disk == DiscoveryCacheResetResult.NOTHING_TO_RESET && hadDeepMemory) {
                DiscoveryCacheResetResult.SUCCESS
            } else {
                disk
            }
        },
    )

    init {
        // Cache IO begins immediately but never blocks Camera2 ownership or the switch hot path.
        topologySignalScope.launch(Dispatchers.IO) {
            val topologyRead = cameraCacheRepository.loadTopology(environment)
            if (topologyRead is CacheRead.Hit) {
                val referenceRead = cachePersistence.readStableLensReference(environment)
                val persistedReference = (referenceRead as? CacheRead.Hit)?.value?.canonicalFingerprint
                val completion = lensInventory.acceptCompatibleCache(
                    snapshot = topologyRead.value,
                    persistedReference = persistedReference,
                )
                if (completion.structuralPublished) {
                    topologyRepository.seedFromCache(topologyRead.value)
                    val elected = completion.referenceToPersist
                    if (elected != null && elected.canonicalFingerprint != persistedReference) {
                        cachePersistence.writeStableLensReference(elected)
                    }
                }
            }
        }
        // Only a verified first frame arms Level-2/Level-4 discovery. Metadata work is independent of
        // the camera dispatcher and each topology improvement only refreshes diagnostics until the
        // inventory coordinator reaches one coherent publication point.
        topologySignalScope.launch {
            coordinator.uiState.collect { state ->
                if (state is VisiblePreviewUiState.Previewing && state.firstFrameVerified) {
                    if (firstFrameVerified.compareAndSet(false, true)) auditTracker.markFirstFrame()
                    topologyReconciler.startAfterFirstFrame()
                }
            }
        }
        // A JAVA_DEEP_PROBED route becomes session-verified history only after the sole controller
        // reports the exact first frame for the user's real selection.
        topologySignalScope.launch {
            controller.state.collect { state ->
                if (state is CameraEngineState.StructuralError) {
                    synchronized(structurallyFailedAuditProfiles) {
                        structurallyFailedAuditProfiles += state.selection.profileFingerprint
                    }
                }
                val previewing = state as? CameraEngineState.Previewing
                if (previewing?.firstFrameVerified == true) {
                    val topology = topologyRepository.topology.value
                    val route = topology?.routes?.firstOrNull { it.id == previewing.selection.routeId }
                    if (route != null && CameraRouteSource.JAVA_DEEP_PROBED in route.sources) {
                        deepKnowledgeRepository.markSessionVerified(environment, route.openCameraId.value)
                    }
                }
                refreshAudit()
            }
        }
        topologySignalScope.launch {
            topologyRepository.topology.collect { snapshot ->
                lensInventory.observeCandidate(snapshot)
                if (snapshot != null) {
                    val validProfiles = snapshot.canonicalLenses.asSequence()
                        .flatMap { it.profiles.asSequence() }
                        .map { it.fingerprint }
                        .toSet()
                    synchronized(structurallyFailedAuditProfiles) {
                        structurallyFailedAuditProfiles.retainAll(validProfiles)
                    }
                }
                val projection = currentAuditProjection()
                auditTracker.onTopologyState(projection.items.size, topologyRepository.publicationCount())
                refreshAudit(projection)
            }
        }
        topologySignalScope.launch {
            coordinator.lensItems.collect { refreshAudit() }
        }
        topologySignalScope.launch {
            auditTracker.changes.collect { refreshAudit() }
        }
    }

    suspend fun capturePhoto(displayRotation: DisplayRotation): RawCaptureOutcome =
        controller.captureRawDng(displayRotation, dngWriter)

    suspend fun startRawVideo(displayRotation: DisplayRotation): SensorRawVideoStartOutcome =
        controller.startRawVideo(displayRotation, rawVideoStore)

    suspend fun stopRawVideo(): SensorRawVideoStopOutcome = controller.stopRawVideo()

    fun requestDeepRescan(): DeepRescanRequestResult {
        val result = deepRescanCoordinator.requestDeepRescan()
        auditTracker.recordDeepRescanResult(result)
        return result
    }

    fun resetDiscoveryCache() {
        topologySignalScope.launch {
            val result = deepRescanCoordinator.resetDiscoveryCache()
            auditTracker.recordCacheResetResult(result)
        }
    }

    fun publishSurface(binding: PreviewSurfaceBinding) {
        when (val change = surfaceBridge.publish(binding)) {
            SurfacePublication.Unchanged -> Unit
            is SurfacePublication.Replaced -> coordinator.surfaceInvalidated(change.previousIdentity)
            is SurfacePublication.ViewportChanged -> coordinator.surfaceInvalidated(change.identity)
        }
    }

    fun surfaceDestroyed(identity: PreviewSurfaceIdentity) {
        if (surfaceBridge.invalidate(identity)) coordinator.surfaceInvalidated(identity)
    }

    override fun close() {
        topologySignalScope.cancel()
        topologyReconciler.close()
        surfaceBridge.close()
        coordinator.requestShutdown()
    }

    private suspend fun persistInventoryCompletion(completion: LensInventoryCompletion) {
        completion.topologyToPersist?.let { cameraCacheRepository.replaceTopology(it) }
        completion.referenceToPersist?.let { cachePersistence.writeStableLensReference(it) }
    }

    private fun refreshAudit(projection: CameraLensProjection = currentAuditProjection()) {
        mutableAuxAudit.value = AuxHardwareAudit.build(
            topology = topologyRepository.topology.value,
            projection = projection,
            tracker = auditTracker.snapshot(),
            switch = latestSwitchDiagnostics.get(),
        )
    }

    private fun currentAuditProjection(): CameraLensProjection {
        val status = coordinator.lensItems.value.associate { it.canonicalFingerprint to it.status }
        val failed = synchronized(structurallyFailedAuditProfiles) { structurallyFailedAuditProfiles.toSet() }
        val active = (controller.state.value as? CameraEngineState.Previewing)?.selection
        return CameraLensUiProjector.project(
            CameraLensProjectionInput(
                topology = topologyRepository.topology.value,
                runtimeApiLevel = Build.VERSION.SDK_INT,
                activeSelection = active,
                statusByLens = status,
                structurallyFailedProfiles = failed,
                stableOneXReferenceFingerprint = lensInventory.stableOneXReference.value,
            ),
        )
    }

    private fun runtimeEnvironmentFingerprint(): CameraEnvironmentFingerprint {
        val fingerprint = Build.FINGERPRINT.takeIf(String::isNotBlank) ?: "unavailable"
        return CameraEnvironmentFingerprint("android-api${Build.VERSION.SDK_INT}:$fingerprint")
    }
}

internal sealed interface SurfacePublication {
    data object Unchanged : SurfacePublication
    data class Replaced(val previousIdentity: PreviewSurfaceIdentity) : SurfacePublication
    data class ViewportChanged(val identity: PreviewSurfaceIdentity) : SurfacePublication
}

internal class AndroidVisiblePreviewSurfaceBridge : VisiblePreviewSurfacePort, AutoCloseable {
    private val provider = GenerationSafePreviewSurfaceProvider()
    private val currentBinding = MutableStateFlow<PreviewSurfaceBinding?>(null)

    fun publish(binding: PreviewSurfaceBinding): SurfacePublication {
        val previous = currentBinding.value
        provider.publish(binding)
        currentBinding.value = binding
        return when {
            previous == null -> SurfacePublication.Unchanged
            previous.identity != binding.identity -> SurfacePublication.Replaced(previous.identity)
            previous.viewSize != binding.viewSize -> SurfacePublication.ViewportChanged(binding.identity)
            else -> SurfacePublication.Unchanged
        }
    }

    fun invalidate(identity: PreviewSurfaceIdentity): Boolean {
        val current = currentBinding.value ?: return false
        if (current.identity != identity) return false
        currentBinding.value = null
        provider.invalidate(identity)
        return true
    }

    override suspend fun awaitSurface(): VisiblePreviewLease =
        AndroidVisiblePreviewLease(provider.awaitSurface())

    override suspend fun awaitBufferSize(identity: PreviewSurfaceIdentity, size: IntSize) {
        val current = currentBinding.value
        if (current != null && current.identity == identity && current.bufferSize == size) return
        currentBinding.filterNotNull().first { binding ->
            binding.identity == identity && binding.bufferSize == size
        }
    }

    override fun close() {
        currentBinding.value = null
        provider.close()
    }
}

private class AndroidVisiblePreviewLease(
    internal val delegate: PreviewSurfaceLease,
) : VisiblePreviewLease {
    override val identity: PreviewSurfaceIdentity
        get() = delegate.binding.identity
    override val viewSize: IntSize
        get() = delegate.binding.viewSize
    override val bufferSize: IntSize
        get() = delegate.binding.bufferSize

    override fun close() {
        delegate.close()
    }
}

private class AndroidVisiblePreviewSessionPort(
    private val controller: CameraSessionController,
) : VisiblePreviewSessionPort {
    override val state: StateFlow<CameraEngineState> = controller.state

    override suspend fun startPreview(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        lease: VisiblePreviewLease,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    ) {
        val androidLease = requireNotNull(lease as? AndroidVisiblePreviewLease) {
            "Production visible preview requires an Android PreviewSurfaceLease"
        }
        controller.startPreview(
            selection = selection,
            route = route,
            surfaceLease = androidLease.delegate,
            configuration = configuration,
            settings = settings,
        )
    }

    override suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity) {
        controller.surfaceInvalidated(identity)
    }

    override suspend fun pause() {
        controller.pause()
    }

    override suspend fun shutdown() {
        controller.shutdown()
    }
}