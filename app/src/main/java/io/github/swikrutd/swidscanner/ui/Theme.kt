package io.github.swikrutd.swidscanner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0F5E5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6EBEA),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFFB4690E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB6),
    onSecondaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF7F6F2),
    surface = Color(0xFFF7F6F2),
    surfaceContainer = Color(0xFFEDEBE5),
    surfaceContainerHigh = Color(0xFFE6E4DE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8AD4D3),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF0F4F50),
    onPrimaryContainer = Color(0xFFB6EBEA),
    secondary = Color(0xFFF2B866),
    onSecondary = Color(0xFF472A00),
    secondaryContainer = Color(0xFF653E00),
    onSecondaryContainer = Color(0xFFFFDDB6),
    background = Color(0xFF111414),
    surface = Color(0xFF111414),
    surfaceContainer = Color(0xFF1C2020),
    surfaceContainerHigh = Color(0xFF262A2A),
)

@Composable
fun SwidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
