package br.com.darkton.pomodoro.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

@Composable
fun PomodoroTheme(
    content: @Composable () -> Unit
) {
    // In alpha26, dynamic color might not be available as a simple function or might have different signature
    // We'll use the default constructor which handles basic M3 theming
    MaterialTheme(
        content = content
    )
}
