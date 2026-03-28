package com.joergi.jukebox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DeepPurple = Color(0xFF673AB7)
private val DeepPurpleLight = Color(0xFF9C27B0)

private val LightColors = lightColorScheme(
    primary = DeepPurple,
    secondary = DeepPurpleLight,
)

private val DarkColors = darkColorScheme(
    primary = DeepPurpleLight,
    secondary = DeepPurple,
)

@Composable
fun JukeboxTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
