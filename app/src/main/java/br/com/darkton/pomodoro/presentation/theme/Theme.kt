package br.com.darkton.pomodoro.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

@Composable
fun PomodoroTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        content = content
    )
}
