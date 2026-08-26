package com.sahidcode404.camx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.sahidcode404.camx.core.camera.bootstrap.VisiblePreviewGraph
import com.sahidcode404.camx.core.camera.model.DisplayRotation
import com.sahidcode404.camx.feature.camera.CameraScreen
import com.sahidcode404.camx.ui.theme.CamXTheme

class MainActivity : ComponentActivity() {
    private var cameraPermissionGranted by mutableStateOf(false)
    private lateinit var visiblePreviewGraph: VisiblePreviewGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        visiblePreviewGraph = VisiblePreviewGraph(this)
        cameraPermissionGranted = hasCameraPermission()
        visiblePreviewGraph.coordinator.setPermission(cameraPermissionGranted)
        enableEdgeToEdge()
        setContent {
            CamXTheme(darkTheme = true) {
                var requestCompleted by remember { mutableStateOf(cameraPermissionGranted) }
                val uiState by visiblePreviewGraph.coordinator.uiState.collectAsState()
                val renderSpec by visiblePreviewGraph.coordinator.renderSpec.collectAsState()
                val lensItems by visiblePreviewGraph.coordinator.lensItems.collectAsState()
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    cameraPermissionGranted = granted
                    requestCompleted = true
                    visiblePreviewGraph.coordinator.setPermission(granted)
                }

                LaunchedEffect(Unit) {
                    if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
                }

                CameraScreen(
                    permissionGranted = cameraPermissionGranted,
                    showSettingsAction = requestCompleted && !cameraPermissionGranted,
                    uiState = uiState,
                    renderSpec = renderSpec,
                    lensItems = lensItems,
                    onLensSelected = visiblePreviewGraph.coordinator::selectLens,
                    onSurfaceAvailable = visiblePreviewGraph::publishSurface,
                    onSurfaceDestroyed = visiblePreviewGraph::surfaceDestroyed,
                    onOpenAppSettings = ::openAppSettings,
                )
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
}
