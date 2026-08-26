package com.sahidcode404.camx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewGraph
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewUiState
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.raw.RawCaptureOutcome
import com.sahidcode404.camx.core.rawvideo.recording.SensorRawVideoStartOutcome
import com.sahidcode404.camx.core.rawvideo.recording.SensorRawVideoStatus
import com.sahidcode404.camx.core.rawvideo.recording.SensorRawVideoStopOutcome
import com.sahidcode404.camx.core.update.DevelopmentUpdateViewModel
import com.sahidcode404.camx.core.update.DevelopmentUpdateViewModelFactory
import com.sahidcode404.camx.feature.camera.CameraScreen
import com.sahidcode404.camx.feature.update.DevelopmentUpdatesOverlay
import com.sahidcode404.camx.ui.theme.CamXTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var cameraPermissionGranted by mutableStateOf(false)
    private lateinit var visiblePreviewGraph: VisiblePreviewGraph
    private lateinit var updateViewModel: DevelopmentUpdateViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        visiblePreviewGraph = VisiblePreviewGraph(this)
        updateViewModel = ViewModelProvider(
            this,
            DevelopmentUpdateViewModelFactory(this),
        )[DevelopmentUpdateViewModel::class.java]
        cameraPermissionGranted = hasCameraPermission()
        visiblePreviewGraph.coordinator.setPermission(cameraPermissionGranted)
        enableEdgeToEdge()
        setContent {
            CamXTheme(darkTheme = true) {
                var requestCompleted by remember { mutableStateOf(cameraPermissionGranted) }
                var storagePermissionGranted by remember { mutableStateOf(hasLegacyStoragePermission()) }
                var pendingLegacyCapture by remember { mutableStateOf(false) }
                var captureRequestNonce by remember { mutableStateOf(0) }
                var captureBusy by remember { mutableStateOf(false) }
                var videoActionBusy by remember { mutableStateOf(false) }
                var captureMessage by remember { mutableStateOf<String?>(null) }
                val cameraScope = rememberCoroutineScope()

                val uiState by visiblePreviewGraph.coordinator.uiState.collectAsState()
                val renderSpec by visiblePreviewGraph.coordinator.renderSpec.collectAsState()
                val lensItems by visiblePreviewGraph.coordinator.lensItems.collectAsState()
                val auxAudit by visiblePreviewGraph.auxAudit.collectAsState()
                val lensInventoryStatus by visiblePreviewGraph.lensInventoryStatus.collectAsState()
                val photoCaptureAvailable by visiblePreviewGraph.photoCaptureAvailable.collectAsState()
                val videoCaptureAvailable by visiblePreviewGraph.videoCaptureAvailable.collectAsState()
                val rawVideoStatus by visiblePreviewGraph.rawVideoStatus.collectAsState()
                val updateState by updateViewModel.state.collectAsState()

                val captureBusyText = stringResource(R.string.capture_raw_busy)
                val captureSavedText = stringResource(R.string.capture_raw_saved)
                val captureCancelledText = stringResource(R.string.capture_raw_cancelled)
                val captureFailedText = stringResource(R.string.capture_raw_failed)
                val storagePermissionText = stringResource(R.string.capture_storage_permission_required)
                val rawVideoStartingText = stringResource(R.string.raw_video_starting)
                val rawVideoStoppingText = stringResource(R.string.raw_video_stopping)
                val rawVideoCancelledText = stringResource(R.string.raw_video_cancelled)
                val rawVideoNotRecordingText = stringResource(R.string.raw_video_not_recording)

                val videoRecording = rawVideoStatus is SensorRawVideoStatus.Recording
                val recordingStartedElapsedRealtimeNs =
                    (rawVideoStatus as? SensorRawVideoStatus.Recording)?.startedElapsedRealtimeNs
                val videoUiEnabled = videoCaptureAvailable || videoRecording
                val captureInteractionBusy = captureBusy || videoActionBusy ||
                    rawVideoStatus is SensorRawVideoStatus.Starting || rawVideoStatus is SensorRawVideoStatus.Stopping

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    cameraPermissionGranted = granted
                    requestCompleted = true
                    visiblePreviewGraph.coordinator.setPermission(granted)
                }
                val storagePermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    storagePermissionGranted = granted
                    val shouldCapture = pendingLegacyCapture && granted
                    pendingLegacyCapture = false
                    if (shouldCapture) {
                        captureRequestNonce += 1
                    } else if (!granted) {
                        captureMessage = storagePermissionText
                    }
                }

                LaunchedEffect(Unit) {
                    if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
                }
                LaunchedEffect(uiState) {
                    val preview = uiState as? VisiblePreviewUiState.Previewing
                    if (preview?.firstFrameVerified == true) {
                        updateViewModel.onFirstVerifiedFrame()
                    }
                }
                LaunchedEffect(captureRequestNonce) {
                    if (captureRequestNonce <= 0 || captureBusy || videoRecording) return@LaunchedEffect
                    captureBusy = true
                    captureMessage = captureBusyText
                    try {
                        captureMessage = when (
                            visiblePreviewGraph.capturePhoto(currentDisplayRotation())
                        ) {
                            is RawCaptureOutcome.Saved -> captureSavedText
                            is RawCaptureOutcome.Failed -> captureFailedText
                            RawCaptureOutcome.Cancelled -> captureCancelledText
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        captureMessage = captureFailedText
                    } finally {
                        captureBusy = false
                    }
                }
                LaunchedEffect(captureMessage, captureInteractionBusy, videoRecording) {
                    if (captureMessage != null && !captureInteractionBusy && !videoRecording) {
                        delay(CAPTURE_MESSAGE_MILLIS)
                        captureMessage = null
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    CameraScreen(
                        permissionGranted = cameraPermissionGranted,
                        showSettingsAction = requestCompleted && !cameraPermissionGranted,
                        uiState = uiState,
                        renderSpec = renderSpec,
                        lensItems = lensItems,
                        auxAudit = auxAudit,
                        inventoryStatus = lensInventoryStatus,
                        photoCaptureEnabled = photoCaptureAvailable && !videoRecording,
                        videoCaptureEnabled = videoUiEnabled,
                        videoRecording = videoRecording,
                        videoRecordingStartedElapsedRealtimeNs = recordingStartedElapsedRealtimeNs,
                        captureBusy = captureInteractionBusy,
                        captureMessage = captureMessage,
                        onCapturePhoto = {
                            if (!captureBusy && !videoActionBusy && !videoRecording && photoCaptureAvailable) {
                                if (requiresLegacyStoragePermission() && !storagePermissionGranted) {
                                    pendingLegacyCapture = true
                                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    captureRequestNonce += 1
                                }
                            }
                        },
                        onToggleVideoRecording = {
                            if (!videoActionBusy && !captureBusy) {
                                cameraScope.launch {
                                    videoActionBusy = true
                                    try {
                                        when (rawVideoStatus) {
                                            is SensorRawVideoStatus.Recording -> {
                                                captureMessage = rawVideoStoppingText
                                                captureMessage = when (val outcome = visiblePreviewGraph.stopRawVideo()) {
                                                    is SensorRawVideoStopOutcome.Completed -> getString(
                                                        R.string.raw_video_saved,
                                                        outcome.summary.frameCount,
                                                    )
                                                    is SensorRawVideoStopOutcome.Failed -> getString(
                                                        R.string.raw_video_failed,
                                                        outcome.reason,
                                                    )
                                                    SensorRawVideoStopOutcome.NotRecording -> rawVideoNotRecordingText
                                                    SensorRawVideoStopOutcome.Cancelled -> rawVideoCancelledText
                                                }
                                            }
                                            is SensorRawVideoStatus.Starting,
                                            is SensorRawVideoStatus.Stopping,
                                            -> Unit
                                            else -> {
                                                if (!videoCaptureAvailable) return@launch
                                                captureMessage = rawVideoStartingText
                                                captureMessage = when (val outcome = visiblePreviewGraph.startRawVideo(currentDisplayRotation())) {
                                                    is SensorRawVideoStartOutcome.Started -> null
                                                    is SensorRawVideoStartOutcome.Failed -> getString(
                                                        R.string.raw_video_failed,
                                                        outcome.reason,
                                                    )
                                                    SensorRawVideoStartOutcome.Cancelled -> rawVideoCancelledText
                                                }
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Throwable) {
                                        captureMessage = getString(
                                            R.string.raw_video_failed,
                                            failure.message ?: getString(R.string.raw_video_unknown_failure),
                                        )
                                    } finally {
                                        videoActionBusy = false
                                    }
                                }
                            }
                        },
                        onLensSelected = visiblePreviewGraph.coordinator::selectLens,
                        onDeepRescan = { visiblePreviewGraph.requestDeepRescan() },
                        onResetDiscoveryCache = visiblePreviewGraph::resetDiscoveryCache,
                        onSurfaceAvailable = visiblePreviewGraph::publishSurface,
                        onSurfaceDestroyed = visiblePreviewGraph::surfaceDestroyed,
                        onOpenAppSettings = ::openAppSettings,
                    )
                    DevelopmentUpdatesOverlay(
                        enabled = updateViewModel.enabled,
                        installed = updateViewModel.installedVersion,
                        state = updateState,
                        onCheck = updateViewModel::checkManually,
                        onDownload = updateViewModel::downloadAvailable,
                        onCancel = updateViewModel::cancel,
                        onInstall = updateViewModel::installReadyUpdate,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cameraPermissionGranted = hasCameraPermission()
        if (::visiblePreviewGraph.isInitialized) {
            visiblePreviewGraph.coordinator.setPermission(cameraPermissionGranted)
            visiblePreviewGraph.coordinator.resume(currentDisplayRotation())
        }
        if (::updateViewModel.isInitialized) updateViewModel.onHostResumed()
    }

    override fun onPause() {
        if (::visiblePreviewGraph.isInitialized) visiblePreviewGraph.coordinator.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::visiblePreviewGraph.isInitialized) visiblePreviewGraph.close()
        super.onDestroy()
    }

    private fun currentDisplayRotation(): DisplayRotation = when (window.decorView.display?.rotation) {
        android.view.Surface.ROTATION_90 -> DisplayRotation.ROTATION_90
        android.view.Surface.ROTATION_180 -> DisplayRotation.ROTATION_180
        android.view.Surface.ROTATION_270 -> DisplayRotation.ROTATION_270
        else -> DisplayRotation.ROTATION_0
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun requiresLegacyStoragePermission(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    private fun hasLegacyStoragePermission(): Boolean = !requiresLegacyStoragePermission() ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CAPTURE_MESSAGE_MILLIS = 1_800L
    }
}
