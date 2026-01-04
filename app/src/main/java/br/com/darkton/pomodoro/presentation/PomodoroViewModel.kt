package br.com.darkton.pomodoro.presentation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.darkton.pomodoro.data.PomodoroDataStore
import br.com.darkton.pomodoro.data.PomodoroPreferences
import br.com.darkton.pomodoro.data.PomodoroState
import br.com.darkton.pomodoro.service.TimerService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PomodoroViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = PomodoroDataStore(application)

    val uiState: StateFlow<PomodoroPreferences> = dataStore.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PomodoroPreferences(25, 5, 4, 1, PomodoroState.IDLE, null)
        )

    fun startTimer() {
        val intent = Intent(getApplication(), TimerService::class.java).apply {
            action = TimerService.ACTION_START
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun pauseTimer() {
        val intent = Intent(getApplication(), TimerService::class.java).apply {
            action = TimerService.ACTION_PAUSE
        }
        getApplication<Application>().startService(intent)
    }

    fun resetTimer() {
        viewModelScope.launch {
            dataStore.reset()
            val intent = Intent(getApplication(), TimerService::class.java).apply {
                action = TimerService.ACTION_STOP
            }
            getApplication<Application>().stopService(intent)
        }
    }

    fun updateConfig(focus: Int, breakM: Int, rounds: Int) {
        viewModelScope.launch {
            dataStore.updateConfig(focus, breakM, rounds)
        }
    }
}
