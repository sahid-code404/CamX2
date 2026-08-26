package com.sahidcode404.camx.core.camera.bootstrap

import com.sahidcode404.camx.core.camera.diagnostics.CameraFailure
import com.sahidcode404.camx.core.camera.lens.CameraLensProjection
import com.sahidcode404.camx.core.camera.lens.CameraLensProjectionInput
import com.sahidcode404.camx.core.camera.lens.CameraLensUiItem
import com.sahidcode404.camx.core.camera.lens.CameraLensUiProjector
import com.sahidcode404.camx.core.camera.lens.LensSelectionTarget
import com.sahidcode404.camx.core.camera.lens.LensTestStatus
import com.sahidcode404.camx.core.camera.model.ActiveCameraSelection
import com.sahidcode404.camx.core.camera.model.CameraProfileFingerprint
import com.sahidcode404.camx.core.camera.model.CameraRoute
import com.sahidcode404.camx.core.camera.model.CameraRouteId
import com.sahidcode404.camx.core.camera.model.CameraTopologySnapshot
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.model.IntSize
import com.sahidcode404.camx.core.camera.model.PreviewConfiguration
import com.sahidcode404.camx.core.camera.model.PreviewGeometry
import com.sahidcode404.camx.core.camera.model.SelectionGeneration
import com.sahidcode404.camx.core.camera.model.SessionGeneration
import com.sahidcode404.camx.core.camera.preview.PreviewPolicyInput
import com.sahidcode404.camx.core.camera.preview.PreviewPolicyResult
import com.sahidcode404.camx.core.camera.preview.PreviewStreamPolicy
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.core.camera.session.CameraEngineState
import com.sahidcode404.camx.core.settings.SettingsSnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

sealed interface VisiblePreviewProblem {
    data object NoCredibleSeed : VisiblePreviewProblem
    data class Capability(val reason: SelectedSeedCapabilityFailure) : VisiblePreviewProblem
    data class Policy(val result: PreviewPolicyResult.Unsupported) : VisiblePreviewProblem
    data class Controller(val failure: CameraFailure) : VisiblePreviewProblem
    data class Startup(val kind: VisiblePreviewStartupFailure) : VisiblePreviewProblem
}

enum class VisiblePreviewStartupFailure {
    SEED_DISCOVERY_FAILED,
    PREVIEW_START_FAILED,
}

data class VisiblePreviewRenderSpec(
    val bufferSize: IntSize,
    val geometry: PreviewGeometry,
)

sealed interface VisiblePreviewUiState {
    data object WaitingForPermission : VisiblePreviewUiState
    data object Starting : VisiblePreviewUiState
    data object WaitingForSurface : VisiblePreviewUiState
    data class Opening(val render: VisiblePreviewRenderSpec) : VisiblePreviewUiState
    data class Previewing(
        val render: VisiblePreviewRenderSpec,
        val firstFrameVerified: Boolean,
    ) : VisiblePreviewUiState
    data class Unavailable(val problem: VisiblePreviewProblem) : VisiblePreviewUiState
    data class Error(val problem: VisiblePreviewProblem) : VisiblePreviewUiState
}

internal fun interface VisiblePreviewSeedSource {
    suspend fun discoverSeed(): CameraRoute?
}

internal fun interface VisiblePreviewPolicyPort {
    fun resolve(input: PreviewPolicyInput): PreviewPolicyResult
}

internal interface VisiblePreviewLease : AutoCloseable {
    val identity: PreviewSurfaceIdentity
    val viewSize: IntSize
    val bufferSize: IntSize
}

internal interface VisiblePreviewSurfacePort {
    suspend fun awaitSurface(): VisiblePreviewLease
    suspend fun awaitBufferSize(identity: PreviewSurfaceIdentity, size: IntSize)
}

internal interface VisiblePreviewSessionPort {
    val state: StateFlow<CameraEngineState>

    suspend fun startPreview(
        selection: ActiveCameraSelection,
        route: CameraRoute,
        lease: VisiblePreviewLease,
        configuration: PreviewConfiguration,
        settings: SettingsSnapshot,
    )

    suspend fun surfaceInvalidated(identity: PreviewSurfaceIdentity)
    suspend fun pause()
    suspend fun shutdown()
}

/**
 * Low-frequency production startup/switch coordinator. Camera2 resources remain owned exclusively by
 * CameraSessionController; this class owns orchestration, not devices/sessions.
 */
class VisiblePreviewCoordinator internal constructor(
    private val seedSource: VisiblePreviewSeedSource,
    private val capabilitySource: SelectedSeedPreviewCapabilitySource,
    private val surfacePort: VisiblePreviewSurfacePort,
    private val session: VisiblePreviewSessionPort,
    private val topology: StateFlow<CameraTopologySnapshot?> = MutableStateFlow(null),
    private val runtimeApiLevel: Int = 23,
    private val settings: () -> SettingsSnapshot = { SettingsSnapshot() },
    private val mirrorFrontPreview: () -> Boolean = { true },
    private val policy: VisiblePreviewPolicyPort = VisiblePreviewPolicyPort(PreviewStreamPolicy::resolve),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val shutdownRequested = AtomicBoolean(false)
    private val mutableUiState = MutableStateFlow<VisiblePreviewUiState>(
        VisiblePreviewUiState.WaitingForPermission,
    )
    private val mutableRenderSpec = MutableStateFlow<VisiblePreviewRenderSpec?>(null)
    private val mutableLensItems = MutableStateFlow<List<CameraLensUiItem>>(emptyList())
    private val statusByLens = LinkedHashMap<CanonicalLensFingerprint, LensTestStatus>()

    private var permissionGranted = false
    private var resumed = false
    private var displayRotation = DisplayRotation.ROTATION_0
    private var startupGeneration = 0L
    private var startupJob: Job? = null
    private var activeSelection: ActiveCameraSelection? = null
    private var preferredLens: CanonicalLensFingerprint? = null

    val uiState: StateFlow<VisiblePreviewUiState> = mutableUiState.asStateFlow()
    val renderSpec: StateFlow<VisiblePreviewRenderSpec?> = mutableRenderSpec.asStateFlow()
    val lensItems: StateFlow<List<CameraLensUiItem>> = mutableLensItems.asStateFlow()

    init {
        scope.launch {
            session.state.collect(::projectControllerState)
        }
        scope.launch {
            topology.collect {
                refreshLensProjection()
            }
        }
    }

    fun setPermission(granted: Boolean) {
        if (shutdownRequested.get()) return
        scope.launch {
            if (permissionGranted == granted) return@launch
            permissionGranted = granted
            if (!granted) {
                invalidateStartup()
                clearOpeningStatuses()
                mutableRenderSpec.value = null
                session.pause()
                mutableUiState.value = VisiblePreviewUiState.WaitingForPermission
                refreshLensProjection()
            } else if (resumed) {
                beginStartup()
            }
        }
    }

    fun resume(rotation: DisplayRotation) {
        if (shutdownRequested.get()) return
        scope.launch {
            val rotationChanged = displayRotation != rotation
            displayRotation = rotation
            val wasResumed = resumed
            resumed = true
            if (!permissionGranted) {
                mutableUiState.value = VisiblePreviewUiState.WaitingForPermission
                return@launch
            }
            if (!wasResumed || rotationChanged || startupJob == null) beginStartup()
        }
    }

    fun updateDisplayRotation(rotation: DisplayRotation) {
        if (shutdownRequested.get()) return
        scope.launch {
            if (displayRotation == rotation) return@launch
            displayRotation = rotation
            if (permissionGranted && resumed) {
                invalidateStartup()
                mutableRenderSpec.value = null
                session.pause()
                beginStartup()
            }
        }
    }

    fun pause() {
        if (shutdownRequested.get()) return
        scope.launch {
            resumed = false
            invalidateStartup()
            clearOpeningStatuses()
            mutableRenderSpec.value = null
            session.pause()
            mutableUiState.value = if (permissionGranted) {
                VisiblePreviewUiState.WaitingForSurface
            } else {
                VisiblePreviewUiState.WaitingForPermission
            }
            refreshLensProjection()
        }
    }

    fun surfaceInvalidated(identity: PreviewSurfaceIdentity) {
        if (shutdownRequested.get()) return
        scope.launch {
            invalidateStartup()
            mutableRenderSpec.value = null
            session.surfaceInvalidated(identity)
            if (permissionGranted && resumed) beginStartup()
        }
    }

    /** Selects one current canonical topology lens. Raw Camera2 transport identities never cross this API. */
    fun selectLens(canonicalFingerprint: CanonicalLensFingerprint) {
        if (shutdownRequested.get()) return
        scope.launch {
            if (!permissionGranted || !resumed) return@launch
            val projection = currentLensProjection()
            val target = projection.targets[canonicalFingerprint] ?: return@launch
            val currentCanonical = activeSelection?.routeId?.let(::canonicalForRoute)
            if (currentCanonical == canonicalFingerprint &&
                activeSelection?.routeId == target.routeId &&
                statusByLens[canonicalFingerprint] == LensTestStatus.VERIFIED
            ) return@launch

            preferredLens = canonicalFingerprint
            statusByLens.keys.toList().forEach { lens ->
                if (statusByLens[lens] == LensTestStatus.VERIFIED) statusByLens[lens] = LensTestStatus.ADVERTISED
            }
            statusByLens[canonicalFingerprint] = LensTestStatus.OPENING
            activeSelection = null
            mutableRenderSpec.value = null
            mutableUiState.value = VisiblePreviewUiState.Starting
            // Do not rehydrate the outgoing verified session snapshot over explicit pending user intent.
            mutableLensItems.value = currentLensProjection().items

            invalidateStartup()
            val generation = startupGeneration
            startupJob = scope.launch {
                runTopologyStartup(generation, target, releaseCurrentPreview = true)
            }
        }
    }

    fun requestShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) return
        scope.launch {
            invalidateStartup()
            mutableRenderSpec.value = null
            try {
                session.shutdown()
            } finally {
                scope.cancel()
            }
        }
    }

    internal suspend fun shutdownForTest() {
        if (!shutdownRequested.compareAndSet(false, true)) return
        invalidateStartup()
        mutableRenderSpec.value = null
        try {
            session.shutdown()
        } finally {
            scope.cancel()
        }
    }

    private fun beginStartup() {
        if (!permissionGranted || !resumed || shutdownRequested.get()) return
        invalidateStartup()
        val generation = startupGeneration
        val preferredTarget = preferredLens?.let { currentLensProjection().targets[it] }
        if (preferredTarget != null) {
            statusByLens[preferredTarget.canonicalFingerprint] = LensTestStatus.OPENING
            refreshLensProjection()
            startupJob = scope.launch {
                runTopologyStartup(generation, preferredTarget, releaseCurrentPreview = false)
            }
        } else {
            startupJob = scope.launch {
                runSeedStartup(generation)
            }
        }
    }

    private suspend fun runSeedStartup(generation: Long) {
        mutableUiState.value = VisiblePreviewUiState.Starting
        val route = try {
            seedSource.discoverSeed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (isCurrent(generation)) {
                mutableUiState.value = VisiblePreviewUiState.Error(
                    VisiblePreviewProblem.Startup(VisiblePreviewStartupFailure.SEED_DISCOVERY_FAILED),
                )
            }
            return
        }
        if (!isCurrent(generation)) return
        if (route == null) {
            mutableUiState.value = VisiblePreviewUiState.Unavailable(VisiblePreviewProblem.NoCredibleSeed)
            return
        }

        val capabilityResult = capabilitySource.read(route)
        if (!isCurrent(generation)) return
        val selectedCapabilities = when (capabilityResult) {
            is SelectedSeedCapabilityResult.Available -> capabilityResult.value
            is SelectedSeedCapabilityResult.Unavailable -> {
                mutableUiState.value = VisiblePreviewUiState.Unavailable(
                    VisiblePreviewProblem.Capability(capabilityResult.reason),
                )
                return
            }
        }
        runResolvedPreview(
            generation = generation,
            target = ResolvedPreviewTarget(
                selection = bootstrapSelection(route, selectedCapabilities),
                route = route,
                capabilities = selectedCapabilities,
                canonicalLens = null,
            ),
        )
    }

    private suspend fun runTopologyStartup(
        generation: Long,
        target: LensSelectionTarget,
        releaseCurrentPreview: Boolean,
    ) {
        if (releaseCurrentPreview) {
            try {
                session.pause()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrent(generation)) failLens(target.canonicalFingerprint)
                return
            }
            if (!isCurrent(generation)) return
        }
        val selection = ActiveCameraSelection(
            canonicalLensFingerprint = target.canonicalFingerprint,
            profileFingerprint = target.profileFingerprint,
            routeId = target.routeId,
            selectionGeneration = SelectionGeneration(0L),
            sessionGeneration = SessionGeneration(0L),
        )
        runResolvedPreview(
            generation = generation,
            target = ResolvedPreviewTarget(
                selection = selection,
                route = target.route,
                capabilities = SelectedSeedPreviewCapabilities(
                    capabilities = target.route.capabilities,
                    sensorOrientationDegrees = target.previewMetadata.sensorOrientationDegrees,
                    lensFacing = target.previewMetadata.lensFacing,
                ),
                canonicalLens = target.canonicalFingerprint,
            ),
        )
    }

    private suspend fun runResolvedPreview(
        generation: Long,
        target: ResolvedPreviewTarget,
    ) {
        if (!isCurrent(generation)) return
        mutableUiState.value = VisiblePreviewUiState.WaitingForSurface
        var lease: VisiblePreviewLease? = null
        var handedToController = false
        try {
            lease = surfacePort.awaitSurface()
            if (!isCurrent(generation)) return
            val settingsSnapshot = settings()
            val policyResult = policy.resolve(
                PreviewPolicyInput(
                    capabilities = target.capabilities.capabilities,
                    viewSize = lease.viewSize,
                    sensorOrientationDegrees = target.capabilities.sensorOrientationDegrees,
                    displayRotation = displayRotation,
                    lensFacing = target.capabilities.lensFacing,
                    mirrorFrontPreview = mirrorFrontPreview(),
                    requestedStreamType = settingsSnapshot.previewStreamType,
                    highResolutionViewfinder = settingsSnapshot.highResolutionViewfinder,
                    fpsRequest = settingsSnapshot.fpsRequest,
                ),
            )
            if (!isCurrent(generation)) return
            val supported = when (policyResult) {
                is PreviewPolicyResult.Supported -> policyResult
                is PreviewPolicyResult.Unsupported -> {
                    target.canonicalLens?.let(::failLens)
                    mutableUiState.value = VisiblePreviewUiState.Unavailable(
                        VisiblePreviewProblem.Policy(policyResult),
                    )
                    return
                }
            }
            val render = VisiblePreviewRenderSpec(
                bufferSize = supported.configuration.size,
                geometry = supported.geometry,
            )
            mutableRenderSpec.value = render
            mutableUiState.value = VisiblePreviewUiState.Opening(render)
            surfacePort.awaitBufferSize(lease.identity, supported.configuration.size)
            if (!isCurrent(generation)) return
            try {
                session.startPreview(
                    selection = target.selection,
                    route = target.route,
                    lease = lease,
                    configuration = supported.configuration,
                    settings = settingsSnapshot,
                )
                handedToController = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrent(generation)) {
                    target.canonicalLens?.let(::failLens)
                    mutableUiState.value = VisiblePreviewUiState.Error(
                        VisiblePreviewProblem.Startup(VisiblePreviewStartupFailure.PREVIEW_START_FAILED),
                    )
                }
            }
        } finally {
            if (!handedToController) lease?.close()
        }
    }

    private fun invalidateStartup() {
        check(startupGeneration < Long.MAX_VALUE) { "Visible preview startup generation exhausted" }
        startupGeneration += 1L
        startupJob?.cancel()
        startupJob = null
    }

    private fun isCurrent(generation: Long): Boolean =
        generation == startupGeneration && permissionGranted && resumed && !shutdownRequested.get()

    private fun projectControllerState(state: CameraEngineState) {
        if (!permissionGranted || !resumed || shutdownRequested.get()) return
        val selection = stateSelection(state)
        val lens = selection?.routeId?.let(::canonicalForRoute)
        when (state) {
            is CameraEngineState.Opening,
            is CameraEngineState.ConfiguringPreview,
            is CameraEngineState.Switching,
            -> lens?.let { statusByLens[it] = LensTestStatus.OPENING }
            is CameraEngineState.Previewing -> {
                activeSelection = state.selection
                if (lens != null) {
                    if (state.firstFrameVerified) {
                        statusByLens.keys.toList().forEach { fingerprint ->
                            if (fingerprint != lens && statusByLens[fingerprint] == LensTestStatus.VERIFIED) {
                                statusByLens[fingerprint] = LensTestStatus.ADVERTISED
                            }
                        }
                        statusByLens[lens] = LensTestStatus.VERIFIED
                        preferredLens = lens
                    } else {
                        statusByLens[lens] = LensTestStatus.OPENING
                    }
                }
            }
            is CameraEngineState.RecoverableError,
            is CameraEngineState.StructuralError,
            -> lens?.let(::failLens)
            else -> Unit
        }
        refreshLensProjection()

        val render = mutableRenderSpec.value
        when (state) {
            is CameraEngineState.Opening,
            is CameraEngineState.ConfiguringPreview,
            is CameraEngineState.Switching,
            -> if (render != null) mutableUiState.value = VisiblePreviewUiState.Opening(render)
            is CameraEngineState.Previewing -> if (render != null) {
                mutableUiState.value = VisiblePreviewUiState.Previewing(
                    render = render,
                    firstFrameVerified = state.firstFrameVerified,
                )
            }
            is CameraEngineState.RecoverableError -> {
                mutableUiState.value = VisiblePreviewUiState.Error(
                    VisiblePreviewProblem.Controller(state.failure),
                )
            }
            is CameraEngineState.StructuralError -> {
                mutableUiState.value = VisiblePreviewUiState.Error(
                    VisiblePreviewProblem.Controller(state.failure),
                )
            }
            else -> Unit
        }
    }

    private fun stateSelection(state: CameraEngineState): ActiveCameraSelection? = when (state) {
        is CameraEngineState.WaitingForSurface -> state.selection
        is CameraEngineState.Opening -> state.selection
        is CameraEngineState.ConfiguringPreview -> state.selection
        is CameraEngineState.Previewing -> state.selection
        is CameraEngineState.Switching -> state.to
        is CameraEngineState.Pausing -> state.selection
        is CameraEngineState.RecoverableError -> state.selection
        is CameraEngineState.StructuralError -> state.selection
        else -> null
    }

    private fun currentLensProjection(): CameraLensProjection = CameraLensUiProjector.project(
        CameraLensProjectionInput(
            topology = topology.value,
            runtimeApiLevel = runtimeApiLevel,
            activeSelection = activeSelection,
            statusByLens = statusByLens,
        ),
    )

    private fun refreshLensProjection() {
        val snapshot = topology.value
        if (snapshot == null) {
            mutableLensItems.value = emptyList()
            return
        }
        val validLenses = snapshot.canonicalLenses.map { it.fingerprint }.toSet()
        statusByLens.keys.retainAll(validLenses)

        val previewing = session.state.value as? CameraEngineState.Previewing
        if (previewing?.firstFrameVerified == true) {
            val canonical = canonicalForRoute(previewing.selection.routeId)
            if (canonical != null) {
                statusByLens.keys.toList().forEach { fingerprint ->
                    if (fingerprint != canonical && statusByLens[fingerprint] == LensTestStatus.VERIFIED) {
                        statusByLens[fingerprint] = LensTestStatus.ADVERTISED
                    }
                }
                activeSelection = previewing.selection
                statusByLens[canonical] = LensTestStatus.VERIFIED
                preferredLens = canonical
            }
        }

        var projection = currentLensProjection()
        val preferred = preferredLens
        if (preferred != null && preferred !in projection.targets) {
            preferredLens = null
            projection = currentLensProjection()
        }
        mutableLensItems.value = projection.items
    }

    private fun canonicalForRoute(routeId: CameraRouteId): CanonicalLensFingerprint? = topology.value
        ?.canonicalLenses
        ?.firstOrNull { lens -> lens.profiles.any { profile -> profile.route.id == routeId } }
        ?.fingerprint

    private fun clearOpeningStatuses() {
        statusByLens.keys.toList().forEach { lens ->
            if (statusByLens[lens] == LensTestStatus.OPENING) statusByLens[lens] = LensTestStatus.ADVERTISED
        }
    }

    private fun failLens(lens: CanonicalLensFingerprint) {
        statusByLens[lens] = LensTestStatus.FAILED
        if (preferredLens == lens) preferredLens = null
        refreshLensProjection()
    }

    private data class ResolvedPreviewTarget(
        val selection: ActiveCameraSelection,
        val route: CameraRoute,
        val capabilities: SelectedSeedPreviewCapabilities,
        val canonicalLens: CanonicalLensFingerprint?,
    )

    private companion object {
        fun bootstrapSelection(
            route: CameraRoute,
            capabilities: SelectedSeedPreviewCapabilities,
        ): ActiveCameraSelection {
            val evidenceKey = buildString {
                append("route=").append(route.id.value)
                append(";source=").append(route.source.name)
                append(";orientation=").append(capabilities.sensorOrientationDegrees)
                append(";facing=").append(capabilities.lensFacing.name)
                capabilities.capabilities.previewStreams
                    .sortedWith(compareBy({ it.type.ordinal }, { it.size.width }, { it.size.height }, { it.minimumFrameDurationNs ?: Long.MAX_VALUE }))
                    .forEach {
                        append(";stream=").append(it.type.name)
                            .append(':').append(it.size.width).append('x').append(it.size.height)
                            .append(':').append(it.minimumFrameDurationNs ?: -1L)
                    }
                capabilities.capabilities.fpsRanges
                    .sortedWith(compareBy({ it.minimum }, { it.maximum }))
                    .forEach { append(";fps=").append(it.minimum).append('-').append(it.maximum) }
            }
            val routeKey = digestHex("bootstrap-canonical|${route.id.value}", 16)
            val profileKey = digestHex("bootstrap-profile|$evidenceKey", 16)
            return ActiveCameraSelection(
                canonicalLensFingerprint = CanonicalLensFingerprint("bootstrap:$routeKey"),
                profileFingerprint = CameraProfileFingerprint("bootstrap:$profileKey"),
                routeId = route.id,
                selectionGeneration = SelectionGeneration(0L),
                sessionGeneration = SessionGeneration(0L),
            )
        }

        fun digestHex(value: String, bytes: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
            val alphabet = "0123456789abcdef"
            val out = CharArray(bytes * 2)
            var cursor = 0
            for (index in 0 until bytes) {
                val byte = digest[index].toInt() and 0xff
                out[cursor++] = alphabet[byte ushr 4]
                out[cursor++] = alphabet[byte and 0x0f]
            }
            return String(out)
        }
    }
}
