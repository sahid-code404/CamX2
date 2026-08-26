package com.sahidcode404.camx.feature.camera

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sahidcode404.camx.R
import com.sahidcode404.camx.core.camera.bootstrap.LensInventoryStatus
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewRenderSpec
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewUiState
import com.sahidcode404.camx.core.camera.diagnostics.AuxHardwareAuditSnapshot
import com.sahidcode404.camx.core.camera.lens.CameraLensUiItem
import com.sahidcode404.camx.core.camera.lens.LensTestStatus
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceBinding
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.ui.components.StableSurfaceView
import com.sahidcode404.camx.ui.design.CamXColors
import kotlinx.coroutines.delay

private enum class CameraCaptureMode {
    PHOTO,
    VIDEO,
}

@Composable
fun CameraScreen(
    permissionGranted: Boolean,
    showSettingsAction: Boolean,
    uiState: VisiblePreviewUiState,
    renderSpec: VisiblePreviewRenderSpec?,
    lensItems: List<CameraLensUiItem>,
    auxAudit: AuxHardwareAuditSnapshot = AuxHardwareAuditSnapshot(),
    inventoryStatus: LensInventoryStatus? = null,
    photoCaptureEnabled: Boolean = false,
    videoCaptureEnabled: Boolean = false,
    videoRecording: Boolean = false,
    videoRecordingStartedElapsedRealtimeNs: Long? = null,
    captureBusy: Boolean = false,
    captureMessage: String? = null,
    onCapturePhoto: () -> Unit = {},
    onToggleVideoRecording: () -> Unit = {},
    onLensSelected: (CanonicalLensFingerprint) -> Unit,
    onDeepRescan: () -> Unit = {},
    onResetDiscoveryCache: () -> Unit = {},
    onSurfaceAvailable: (PreviewSurfaceBinding) -> Unit,
    onSurfaceDestroyed: (PreviewSurfaceIdentity) -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val previewContentDescription = stringResource(R.string.camera_preview_content_description)
    val photoContentDescription = stringResource(R.string.capture_raw_photo_content_description)
    val startVideoContentDescription = stringResource(R.string.capture_raw_video_content_description)
    val stopVideoContentDescription = stringResource(R.string.stop_raw_video_content_description)
    val revealPreviewSurface = shouldRevealPreviewSurface(uiState, renderSpec)
    var showAuxAudit by remember { mutableStateOf(false) }
    var captureMode by remember { mutableStateOf(CameraCaptureMode.PHOTO) }
    val captureMessageScrollState = rememberScrollState()

    LaunchedEffect(videoRecording) {
        if (videoRecording) captureMode = CameraCaptureMode.VIDEO
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CamXColors.Ink),
    ) {
        StableSurfaceView(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = previewContentDescription },
            bufferSize = renderSpec?.bufferSize,
            geometry = renderSpec?.geometry,
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
        )

        // Keep the SurfaceView and its identity alive while target fixed-size/geometry is applied.
        // A neutral cover is used only after leaving the verified outgoing presentation and remains
        // until the exact target first frame is verified, preventing stale frames from being shown
        // under the target crop/rotation/mirror transform.
        if (!revealPreviewSurface) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CamXColors.Ink),
            )
        }

        if (!permissionGranted) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_required),
                    color = CamXColors.TextSecondary,
                )
                if (showSettingsAction) {
                    TextButton(onClick = onOpenAppSettings) {
                        Text(stringResource(R.string.open_app_settings))
                    }
                }
            }
        } else {
            TextButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 12.dp),
                onClick = { showAuxAudit = true },
            ) {
                Text("AUX Audit")
            }

            captureMessage?.let { message ->
                Text(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(max = 96.dp)
                        .verticalScroll(captureMessageScrollState)
                        .padding(start = 16.dp, end = 16.dp, bottom = 244.dp),
                    text = message,
                    textAlign = TextAlign.Center,
                    color = CamXColors.TextPrimary,
                )
            }

            if (videoRecording && videoRecordingStartedElapsedRealtimeNs != null) {
                RawRecordingTimer(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 214.dp),
                    startedElapsedRealtimeNs = videoRecordingStartedElapsedRealtimeNs,
                )
            } else if (captureMessage == null && captureMode == CameraCaptureMode.VIDEO && !videoCaptureEnabled) {
                Text(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = 214.dp),
                    text = stringResource(R.string.raw_video_unavailable),
                    color = CamXColors.TextSecondary,
                )
            }

            if (lensItems.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 164.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    lensItems.forEach { item ->
                        key(item.canonicalFingerprint.value) {
                            LensTestButton(
                                item = item,
                                interactionEnabled = !videoRecording,
                                onClick = { onLensSelected(item.canonicalFingerprint) },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 116.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeButton(
                    label = stringResource(R.string.camera_mode_photo),
                    selected = captureMode == CameraCaptureMode.PHOTO,
                    enabled = !videoRecording,
                    onClick = { captureMode = CameraCaptureMode.PHOTO },
                )
                ModeButton(
                    label = stringResource(R.string.camera_mode_video),
                    selected = captureMode == CameraCaptureMode.VIDEO,
                    enabled = true,
                    onClick = { captureMode = CameraCaptureMode.VIDEO },
                )
            }

            val captureEnabled = permissionGranted && !captureBusy && when (captureMode) {
                CameraCaptureMode.PHOTO -> photoCaptureEnabled && !videoRecording
                CameraCaptureMode.VIDEO -> videoCaptureEnabled
            }
            val captureDescription = when (captureMode) {
                CameraCaptureMode.PHOTO -> photoContentDescription
                CameraCaptureMode.VIDEO -> if (videoRecording) stopVideoContentDescription else startVideoContentDescription
            }
            val captureColor = when (captureMode) {
                CameraCaptureMode.PHOTO -> Color.White
                CameraCaptureMode.VIDEO -> Color.Red
            }
            Button(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(72.dp)
                    .semantics { contentDescription = captureDescription },
                enabled = captureEnabled,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = captureColor,
                    disabledContainerColor = captureColor.copy(alpha = 0.55f),
                ),
                onClick = {
                    when (captureMode) {
                        CameraCaptureMode.PHOTO -> onCapturePhoto()
                        CameraCaptureMode.VIDEO -> onToggleVideoRecording()
                    }
                },
            ) {
                if (captureMode == CameraCaptureMode.VIDEO && videoRecording) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.White, RoundedCornerShape(5.dp)),
                    )
                } else {
                    Box(modifier = Modifier.size(1.dp))
                }
            }
        }

        if (showAuxAudit) {
            AuxHardwareAuditPanel(
                audit = auxAudit,
                inventoryStatus = inventoryStatus,
                onClose = { showAuxAudit = false },
                onDeepRescan = onDeepRescan,
                onResetDiscoveryCache = onResetDiscoveryCache,
            )
        }
    }
}

@Composable
private fun RawRecordingTimer(
    modifier: Modifier,
    startedElapsedRealtimeNs: Long,
) {
    var nowNs by remember(startedElapsedRealtimeNs) {
        mutableLongStateOf(SystemClock.elapsedRealtimeNanos())
    }
    LaunchedEffect(startedElapsedRealtimeNs) {
        while (true) {
            nowNs = SystemClock.elapsedRealtimeNanos()
            delay(250L)
        }
    }
    val elapsedSeconds = ((nowNs - startedElapsedRealtimeNs).coerceAtLeast(0L) / 1_000_000_000L)
    val minutes = elapsedSeconds / 60L
    val seconds = elapsedSeconds % 60L
    val clock = "${twoDigits(minutes)}:${twoDigits(seconds)}"
    Text(
        modifier = modifier,
        text = stringResource(R.string.raw_video_recording_timer, clock),
        color = Color.Red,
    )
}

private fun twoDigits(value: Long): String = if (value < 10L) "0$value" else value.toString()

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(enabled = enabled, onClick = onClick) {
        Text(
            text = label,
            color = if (selected) CamXColors.TextPrimary else CamXColors.TextSecondary,
        )
    }
}

@Composable
private fun LensTestButton(
    item: CameraLensUiItem,
    interactionEnabled: Boolean,
    onClick: () -> Unit,
) {
    val statusLabel = lensStatusText(item.status)
    val description = stringResource(
        R.string.lens_content_description,
        item.primaryLabel,
        statusLabel,
    )
    TextButton(
        modifier = Modifier
            .sizeIn(minWidth = 56.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = description
                selected = item.selected
            },
        enabled = item.enabled && interactionEnabled,
        onClick = onClick,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = item.primaryLabel,
                color = if (item.selected) CamXColors.TextPrimary else CamXColors.TextSecondary,
            )
            item.secondaryOpticalLabel?.let { secondary ->
                Text(
                    text = secondary,
                    color = CamXColors.TextSecondary,
                )
            }
        }
    }
}

/** Lifecycle state remains available to accessibility and AUX Audit, but is not visual Camera UI. */
@Composable
private fun lensStatusText(status: LensTestStatus): String = when (status) {
    LensTestStatus.ADVERTISED,
    LensTestStatus.AVAILABLE,
    -> stringResource(R.string.lens_status_advertised)
    LensTestStatus.OPENING -> stringResource(R.string.lens_status_opening)
    LensTestStatus.VERIFIED -> stringResource(R.string.lens_status_verified)
    LensTestStatus.FAILED -> stringResource(R.string.lens_status_failed)
}
