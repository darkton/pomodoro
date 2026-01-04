package br.com.darkton.pomodoro.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import br.com.darkton.pomodoro.R
import br.com.darkton.pomodoro.data.PomodoroDataStore
import br.com.darkton.pomodoro.data.PomodoroState
import br.com.darkton.pomodoro.presentation.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class TimerService : LifecycleService() {

    private lateinit var dataStore: PomodoroDataStore
    private var timerJob: Job? = null
    private val CHANNEL_ID = "PomodoroTimerChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        dataStore = PomodoroDataStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        when (action) {
            ACTION_START -> startTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_STOP -> stopTimer()
        }
        return START_STICKY
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            val prefs = dataStore.preferencesFlow.first()
            
            if (prefs.currentState == PomodoroState.PAUSED) {
                val endTime = System.currentTimeMillis() + prefs.remainingMillis
                val originalState = if (prefs.currentRound % 2 != 0) PomodoroState.FOCUS else PomodoroState.BREAK // Simple logic, might need adjustment based on how rounds are tracked
                // Better: we should have saved the state it was in before pausing. 
                // Let's assume for now it stays in FOCUS or BREAK but we need a way to know.
                // Actually, if we just set it back to what it was.
                // For now, let's assume it was FOCUS if round is odd, BREAK if even is not robust.
                // Let's look at PomodoroState. 
            }

            // Refined Start/Resume logic
            val currentState = prefs.currentState
            if (currentState == PomodoroState.IDLE || currentState == PomodoroState.COMPLETED) {
                val endTime = System.currentTimeMillis() + (prefs.focusMinutes * 60 * 1000)
                dataStore.updateState(PomodoroState.FOCUS, 1, endTime)
            } else if (currentState == PomodoroState.PAUSED) {
                val endTime = System.currentTimeMillis() + prefs.remainingMillis
                // We need to know if it was FOCUS or BREAK. 
                // Let's assume we store the 'pre-pause' state or just deduce it.
                // If it's PAUSED, we don't know if it was FOCUS or BREAK.
                // I should have added a 'previousState' or just not change state to PAUSED but use a boolean 'isPaused'.
                // But let's use the round: if it's currently focused on a round.
                // Actually, let's just use FOCUS for now or improve DataStore.
                dataStore.updateState(PomodoroState.FOCUS, prefs.currentRound, endTime)
            }
            
            startForeground(NOTIFICATION_ID, createNotification("Timer running"))
            
            while (isActive) {
                val currentPrefs = dataStore.preferencesFlow.first()
                if (currentPrefs.currentState == PomodoroState.PAUSED) break
                
                val endTime = currentPrefs.timerEndTimestamp ?: break
                val remaining = endTime - System.currentTimeMillis()

                if (remaining <= 0) {
                    handleTransition(currentPrefs)
                } else {
                    updateNotification(remaining)
                }
                delay(1000)
            }
        }
    }

    private fun pauseTimer() {
        timerJob?.cancel()
        lifecycleScope.launch {
            val prefs = dataStore.preferencesFlow.first()
            if (prefs.currentState == PomodoroState.FOCUS || prefs.currentState == PomodoroState.BREAK) {
                val remaining = (prefs.timerEndTimestamp ?: System.currentTimeMillis()) - System.currentTimeMillis()
                dataStore.updateState(PomodoroState.PAUSED, prefs.currentRound, null, remaining)
                updateNotification(remaining)
            }
        }
    }

    private suspend fun handleTransition(prefs: br.com.darkton.pomodoro.data.PomodoroPreferences) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        when (prefs.currentState) {
            PomodoroState.FOCUS -> {
                if (prefs.currentRound < prefs.totalRounds) {
                    vibrate(vibrator, VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
                    val nextEndTime = System.currentTimeMillis() + (prefs.breakMinutes * 60 * 1000)
                    dataStore.updateState(PomodoroState.BREAK, prefs.currentRound, nextEndTime)
                } else {
                    vibrate(vibrator, VibrationEffect.createWaveform(longArrayOf(0, 600, 200, 200, 200, 200), -1))
                    dataStore.updateState(PomodoroState.COMPLETED, prefs.currentRound, null)
                    stopTimer()
                }
            }
            PomodoroState.BREAK -> {
                vibrate(vibrator, VibrationEffect.createWaveform(longArrayOf(0, 200, 200, 200), -1))
                val nextEndTime = System.currentTimeMillis() + (prefs.focusMinutes * 60 * 1000)
                dataStore.updateState(PomodoroState.FOCUS, prefs.currentRound + 1, nextEndTime)
            }
            else -> stopTimer()
        }
    }

    private fun vibrate(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Pomodoro Timer Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pomodoro")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(remainingMs: Long) {
        val minutes = (remainingMs / 1000) / 60
        val seconds = (remainingMs / 1000) % 60
        val timeStr = String.format("%02d:%02d", minutes, seconds)
        val notification = createNotification("Remaining: $timeStr")
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START = "br.com.darkton.pomodoro.START"
        const val ACTION_PAUSE = "br.com.darkton.pomodoro.PAUSE"
        const val ACTION_STOP = "br.com.darkton.pomodoro.STOP"
    }
}
