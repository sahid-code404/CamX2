package com.sahidcode404.camx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.sahidcode404.camx.ui.design.CamXColors

private val CameraDarkScheme = darkColorScheme(
    primary = CamXColors.ElectricBlue,
    secondary = CamXColors.Violet,
    background = CamXColors.Ink,
    surface = CamXColors.Graphite,
    surfaceVariant = CamXColors.RaisedGraphite,
    onPrimary = CamXColors.Ink,
    onBackground = CamXColors.TextPrimary,
    onSurface = CamXColors.TextPrimary,
    onSurfaceVariant = CamXColors.TextSecondary,
    error = CamXColors.Error,
)

private val SettingsLightScheme = lightColorScheme(
    primary = CamXColors.Violet,
    secondary = CamXColors.ElectricBlue,
    background = androidx.compose.ui.graphics.Color(0xFFF8F8FC),
    surface = androidx.compose.ui.graphics.Color.White,
    onBackground = androidx.compose.ui.graphics.Color(0xFF171821),
    onSurface = androidx.compose.ui.graphics.Color(0xFF171821),
)

@Composable
fun CamXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CameraDarkScheme else SettingsLightScheme,
        content = content,
    )
}
