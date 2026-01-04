package br.com.darkton.pomodoro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pomodoro_prefs")

enum class PomodoroState {
    IDLE, FOCUS, BREAK, PAUSED, COMPLETED
}

data class PomodoroPreferences(
    val focusMinutes: Int,
    val breakMinutes: Int,
    val totalRounds: Int,
    val currentRound: Int,
    val currentState: PomodoroState,
    val timerEndTimestamp: Long?,
    val remainingMillis: Long = 0L
)

class PomodoroDataStore(private val context: Context) {
    private val FOCUS_MINUTES = intPreferencesKey("focus_minutes")
    private val BREAK_MINUTES = intPreferencesKey("break_minutes")
    private val TOTAL_ROUNDS = intPreferencesKey("total_rounds")
    private val CURRENT_ROUND = intPreferencesKey("current_round")
    private val CURRENT_STATE = stringPreferencesKey("current_state")
    private val TIMER_END_TIMESTAMP = longPreferencesKey("timer_end_timestamp")
    private val REMAINING_MILLIS = longPreferencesKey("remaining_millis")

    val preferencesFlow: Flow<PomodoroPreferences> = context.dataStore.data.map { preferences ->
        PomodoroPreferences(
            focusMinutes = preferences[FOCUS_MINUTES] ?: 1,
            breakMinutes = preferences[BREAK_MINUTES] ?: 5,
            totalRounds = preferences[TOTAL_ROUNDS] ?: 4,
            currentRound = preferences[CURRENT_ROUND] ?: 1,
            currentState = PomodoroState.valueOf(preferences[CURRENT_STATE] ?: PomodoroState.IDLE.name),
            timerEndTimestamp = preferences[TIMER_END_TIMESTAMP],
            remainingMillis = preferences[REMAINING_MILLIS] ?: 0L
        )
    }

    suspend fun updateConfig(focus: Int, breakM: Int, rounds: Int) {
        context.dataStore.edit { preferences ->
            preferences[FOCUS_MINUTES] = focus
            preferences[BREAK_MINUTES] = breakM
            preferences[TOTAL_ROUNDS] = rounds
        }
    }

    suspend fun updateState(state: PomodoroState, round: Int, endTimestamp: Long?, remaining: Long = 0L) {
        context.dataStore.edit { preferences ->
            preferences[CURRENT_STATE] = state.name
            preferences[CURRENT_ROUND] = round
            preferences[REMAINING_MILLIS] = remaining
            if (endTimestamp != null) {
                preferences[TIMER_END_TIMESTAMP] = endTimestamp
            } else {
                preferences.remove(TIMER_END_TIMESTAMP)
            }
        }
    }

    suspend fun updateRemaining(remaining: Long) {
        context.dataStore.edit { preferences ->
            preferences[REMAINING_MILLIS] = remaining
        }
    }

    suspend fun reset() {
        context.dataStore.edit { preferences ->
            preferences[CURRENT_STATE] = PomodoroState.IDLE.name
            preferences[CURRENT_ROUND] = 1
            preferences.remove(TIMER_END_TIMESTAMP)
            preferences[REMAINING_MILLIS] = 0L
        }
    }
}
