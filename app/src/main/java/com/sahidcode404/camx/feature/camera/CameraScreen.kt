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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sahidcode404.camx.R
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewProblem
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewRenderSpec
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewUiState
import com.sahidcode404.camx.core.camera.lens.CameraLensUiItem
import com.sahidcode404.camx.core.camera.lens.LensTestStatus
import com.sahidcode404.camx.core.camera.model.CanonicalLensFingerprint
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceBinding
import com.sahidcode404.camx.core.camera.preview.PreviewSurfaceIdentity
import com.sahidcode404.camx.ui.components.StableSurfaceView
import com.sahidcode404.camx.ui.design.CamXColors

@Composable
fun CameraScreen(
    permissionGranted: Boolean,
    showSettingsAction: Boolean,
    uiState: VisiblePreviewUiState,
    renderSpec: VisiblePreviewRenderSpec?,
    lensItems: List<CameraLensUiItem>,
    onLensSelected: (CanonicalLensFingerprint) -> Unit,
    onSurfaceAvailable: (PreviewSurfaceBinding) -> Unit,
    onSurfaceDestroyed: (PreviewSurfaceIdentity) -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val previewContentDescription = stringResource(R.string.camera_preview_content_description)
    val captureContentDescription = stringResource(R.string.capture_unavailable_content_description)
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
            previewStatusText(uiState)?.let { status ->
                Text(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    text = status,
                    color = CamXColors.TextPrimary,
                )
            }

            if (lensItems.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 128.dp)
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
        }

        Button(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp)
                .size(72.dp)
                .semantics { contentDescription = captureContentDescription },
            enabled = false,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = Color.White.copy(alpha = 0.55f),
            ),
            onClick = {},
        ) {
            Box(modifier = Modifier.size(1.dp))
        }
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
            Text(
                text = statusLabel,
                color = if (item.status == LensTestStatus.VERIFIED) {
                    CamXColors.TextPrimary
                } else {
                    CamXColors.TextSecondary
                },
            )
        }
    }
}

@Composable
private fun lensStatusText(status: LensTestStatus): String = when (status) {
    LensTestStatus.ADVERTISED -> stringResource(R.string.lens_status_advertised)
    LensTestStatus.OPENING -> stringResource(R.string.lens_status_opening)
    LensTestStatus.VERIFIED -> stringResource(R.string.lens_status_verified)
    LensTestStatus.FAILED -> stringResource(R.string.lens_status_failed)
}

@Composable
private fun previewStatusText(state: VisiblePreviewUiState): String? = when (state) {
    VisiblePreviewUiState.WaitingForPermission -> null
    VisiblePreviewUiState.Starting -> stringResource(R.string.camera_preview_starting)
    VisiblePreviewUiState.WaitingForSurface -> stringResource(R.string.camera_preview_waiting_surface)
    is VisiblePreviewUiState.Opening -> stringResource(R.string.camera_preview_opening)
    is VisiblePreviewUiState.Previewing -> if (state.firstFrameVerified) null
    else stringResource(R.string.camera_preview_waiting_first_frame)
    is VisiblePreviewUiState.Unavailable -> when (state.problem) {
        VisiblePreviewProblem.NoCredibleSeed -> stringResource(R.string.camera_preview_no_camera)
        is VisiblePreviewProblem.Capability -> stringResource(R.string.camera_preview_capability_unavailable)
        is VisiblePreviewProblem.Policy -> stringResource(R.string.camera_preview_unsupported)
        is VisiblePreviewProblem.Controller -> stringResource(R.string.camera_preview_camera_error)
        is VisiblePreviewProblem.Startup -> stringResource(R.string.camera_preview_startup_error)
    }
    is VisiblePreviewUiState.Error -> when (state.problem) {
        VisiblePreviewProblem.NoCredibleSeed -> stringResource(R.string.camera_preview_no_camera)
        is VisiblePreviewProblem.Capability -> stringResource(R.string.camera_preview_capability_unavailable)
        is VisiblePreviewProblem.Policy -> stringResource(R.string.camera_preview_unsupported)
        is VisiblePreviewProblem.Controller -> stringResource(R.string.camera_preview_camera_error)
        is VisiblePreviewProblem.Startup -> stringResource(R.string.camera_preview_startup_error)
    }
}
