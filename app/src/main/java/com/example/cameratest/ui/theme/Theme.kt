package com.example.cameratest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 与桌面端预览器一致的视觉语言：深色底 + 单一绿色强调
val Green = Color(0xFF00D97E)
val GreenDim = Color(0xFF0C5C44)
val Background = Color(0xFF0B0F0E)
val Surface = Color(0xFF121817)
val OnSurface = Color(0xFFE6F0EC)
val Muted = Color(0xFF7C8B86)
val Danger = Color(0xFFFF6B6B)
val Warn = Color(0xFFFFD166)

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF06231A),
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = Surface,
    onSurfaceVariant = Muted,
    error = Danger
)

private val LightColors = lightColorScheme(
    primary = GreenDim,
    onPrimary = Color.White,
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF0B0F0E),
    surface = Color.White,
    onSurface = Color(0xFF0B0F0E)
)

@Composable
fun CameraTestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
