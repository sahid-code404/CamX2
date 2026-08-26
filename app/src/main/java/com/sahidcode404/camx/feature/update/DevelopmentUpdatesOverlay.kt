package com.sahidcode404.camx.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sahidcode404.camx.core.update.DevOtaManifest
import com.sahidcode404.camx.core.update.InstalledUpdateVersion
import com.sahidcode404.camx.core.update.UpdateState
import com.sahidcode404.camx.ui.design.CamXColors
import java.util.Locale

@Composable
fun DevelopmentUpdatesOverlay(
    enabled: Boolean,
    installed: InstalledUpdateVersion,
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    if (!enabled) return
    var expanded by remember { mutableStateOf(false) }
    val updateAvailable = state is UpdateState.Available
    Box(modifier = Modifier.fillMaxSize()) {
        TextButton(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 32.dp, start = 12.dp),
            onClick = { expanded = true },
        ) {
            Text(
                text = if (updateAvailable) "Updates •" else "Updates",
                color = CamXColors.TextPrimary,
            )
        }

        if (expanded) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 380.dp),
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Development updates", color = CamXColors.TextPrimary)
                        TextButton(onClick = { expanded = false }) {
                            Text("Close")
                        }
                    }

                    Text(
                        "Installed: ${installed.versionName} (${installed.versionCode})",
                        color = CamXColors.TextSecondary,
                    )
                    latestManifest(state)?.let { latest ->
                        Text(
                            "Latest: ${latest.versionName} (${latest.versionCode})",
                            color = CamXColors.TextSecondary,
                        )
                        if (latest.changelog.isNotBlank()) {
                            Text("Changelog: ${latest.changelog}", color = CamXColors.TextSecondary)
                        }
                    }
                    Text("Status: ${statusText(state)}", color = CamXColors.TextPrimary)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (state) {
                            UpdateState.Idle,
                            is UpdateState.UpToDate,
                            -> Button(onClick = onCheck) { Text("Check for update") }

                            UpdateState.Checking -> Unit
                            is UpdateState.Available -> {
                                TextButton(onClick = onCheck) { Text("Check again") }
                                Button(onClick = onDownload) { Text("Download update") }
                            }
                            is UpdateState.Downloading -> {
                                Button(onClick = onCancel) { Text("Cancel") }
                            }
                            is UpdateState.ReadyToInstall -> {
                                Button(onClick = onInstall) { Text("Install update") }
                            }
                            is UpdateState.Failed -> {
                                Button(
                                    onClick = if (state.manifest != null) onDownload else onCheck,
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun latestManifest(state: UpdateState): DevOtaManifest? = when (state) {
    is UpdateState.UpToDate -> state.manifest
    is UpdateState.Available -> state.manifest
    is UpdateState.Downloading -> state.manifest
    is UpdateState.ReadyToInstall -> state.manifest
    is UpdateState.Failed -> state.manifest
    UpdateState.Idle,
    UpdateState.Checking,
    -> null
}

private fun statusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Idle"
    UpdateState.Checking -> "Checking"
    is UpdateState.UpToDate -> "Up to date"
    is UpdateState.Available -> "Update available"
    is UpdateState.Downloading -> downloadStatus(state)
    is UpdateState.ReadyToInstall -> "Ready to install"
    is UpdateState.Failed -> "Failed: ${state.code.name}"
}

private fun downloadStatus(state: UpdateState.Downloading): String {
    val downloaded = formatBytes(state.downloadedBytes)
    val total = state.totalBytes ?: return "Downloading update — $downloaded"
    val percent = if (total > 0L) ((state.downloadedBytes * 100L) / total).coerceIn(0L, 100L) else 0L
    return "Downloading update — $downloaded / ${formatBytes(total)} ($percent%)"
}

private fun formatBytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
