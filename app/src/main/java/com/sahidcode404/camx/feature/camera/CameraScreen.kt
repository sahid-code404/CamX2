package com.sahidcode404.camx.feature.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
    val videoContentDescription = stringResource(R.string.capture_raw_video_content_description)
    val revealPreviewSurface = shouldRevealPreviewSurface(uiState, renderSpec)
    var showAuxAudit by remember { mutableStateOf(false) }
    var captureMode by remember { mutableStateOf(CameraCaptureMode.PHOTO) }

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
                        .padding(bottom = 244.dp),
                    text = message,
                    color = CamXColors.TextPrimary,
                )
            }

            if (captureMode == CameraCaptureMode.VIDEO && !videoCaptureEnabled) {
                Text(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = 214.dp),
                    text = stringResource(R.string.raw_video_m10_unavailable),
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
                    onClick = { captureMode = CameraCaptureMode.PHOTO },
                )
                ModeButton(
                    label = stringResource(R.string.camera_mode_video),
                    selected = captureMode == CameraCaptureMode.VIDEO,
                    onClick = { captureMode = CameraCaptureMode.VIDEO },
                )
            }

            val captureEnabled = permissionGranted && !captureBusy && when (captureMode) {
                CameraCaptureMode.PHOTO -> photoCaptureEnabled
                CameraCaptureMode.VIDEO -> videoCaptureEnabled
            }
            val captureDescription = when (captureMode) {
                CameraCaptureMode.PHOTO -> photoContentDescription
                CameraCaptureMode.VIDEO -> videoContentDescription
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
                Box(modifier = Modifier.size(1.dp))
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
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) CamXColors.TextPrimary else CamXColors.TextSecondary,
        )
    }
}

@Composable
private fun LensTestButton(
    item: CameraLensUiItem,
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
        enabled = item.enabled,
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
