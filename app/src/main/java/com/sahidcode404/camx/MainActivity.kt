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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewGraph
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewUiState
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.core.camera.raw.RawCaptureOutcome
import com.sahidcode404.camx.core.update.DevelopmentUpdateViewModel
import com.sahidcode404.camx.core.update.DevelopmentUpdateViewModelFactory
import com.sahidcode404.camx.feature.camera.CameraScreen
import com.sahidcode404.camx.feature.update.DevelopmentUpdatesOverlay
import com.sahidcode404.camx.ui.theme.CamXTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
                var cp1RequestNonce by remember { mutableStateOf(0) }
                var captureBusy by remember { mutableStateOf(false) }
                var captureMessage by remember { mutableStateOf<String?>(null) }

                val uiState by visiblePreviewGraph.coordinator.uiState.collectAsState()
                val renderSpec by visiblePreviewGraph.coordinator.renderSpec.collectAsState()
                val lensItems by visiblePreviewGraph.coordinator.lensItems.collectAsState()
                val auxAudit by visiblePreviewGraph.auxAudit.collectAsState()
                val lensInventoryStatus by visiblePreviewGraph.lensInventoryStatus.collectAsState()
                val photoCaptureAvailable by visiblePreviewGraph.photoCaptureAvailable.collectAsState()
                val updateState by updateViewModel.state.collectAsState()

                val captureBusyText = stringResource(R.string.capture_raw_busy)
                val captureSavedText = stringResource(R.string.capture_raw_saved)
                val captureCancelledText = stringResource(R.string.capture_raw_cancelled)
                val captureFailedText = stringResource(R.string.capture_raw_failed)
                val storagePermissionText = stringResource(R.string.capture_storage_permission_required)
                val cp1BusyText = stringResource(R.string.cp1_raw_busy)

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
                    if (captureRequestNonce <= 0 || captureBusy) return@LaunchedEffect
                    captureBusy = true
                    captureMessage = captureBusyText
                    try {
                        captureMessage = when (
                            visiblePreviewGraph.capturePhoto(currentDisplayRotation())
                        ) {
                            is RawCaptureOutcome.Saved -> captureSavedText
                            is RawCaptureOutcome.Probed -> captureFailedText
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
                LaunchedEffect(cp1RequestNonce) {
                    if (cp1RequestNonce <= 0 || captureBusy) return@LaunchedEffect
                    captureBusy = true
                    captureMessage = cp1BusyText
                    try {
                        val result = visiblePreviewGraph.captureComputationalRawProbe(currentDisplayRotation())
                        val report = result.report
                        captureMessage = if (report.success) {
                            result.cp2Report?.let { cp2 ->
                                getString(
                                    R.string.cp1_cp2_success,
                                    report.exactPairsCreated,
                                    report.requestedFrames,
                                    cp2.exactDynamicBindings,
                                    cp2.noiseProfileFrames,
                                )
                            } ?: getString(
                                R.string.cp1_raw_success,
                                report.exactPairsCreated,
                                report.requestedFrames,
                            )
                        } else {
                            getString(
                                R.string.cp1_raw_failed,
                                report.exactPairsCreated,
                                report.requestedFrames,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        captureMessage = getString(R.string.cp1_raw_failed, 0, 8)
                    } finally {
                        captureBusy = false
                    }
                }
                LaunchedEffect(captureMessage, captureBusy) {
                    if (captureMessage != null && !captureBusy) {
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
                        photoCaptureEnabled = photoCaptureAvailable,
                        videoCaptureEnabled = false,
                        showCp1Action = BuildConfig.DEBUG,
                        cp1CaptureEnabled = photoCaptureAvailable,
                        captureBusy = captureBusy,
                        captureMessage = captureMessage,
                        onCapturePhoto = {
                            if (!captureBusy && photoCaptureAvailable) {
                                if (requiresLegacyStoragePermission() && !storagePermissionGranted) {
                                    pendingLegacyCapture = true
                                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    captureRequestNonce += 1
                                }
                            }
                        },
                        onCaptureCp1 = {
                            if (!captureBusy && photoCaptureAvailable) cp1RequestNonce += 1
                        },
                        onToggleVideoRecording = {},
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
