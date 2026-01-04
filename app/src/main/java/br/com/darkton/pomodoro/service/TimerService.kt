package br.com.darkton.pomodoro.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
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
    private var currentRingtone: Ringtone? = null
    private val CHANNEL_ID = "PomodoroTimerChannel"
    private val NOTIFICATION_ID = 1
    private val handler = Handler(Looper.getMainLooper())
    private var stopAlarmRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        dataStore = PomodoroDataStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        stopAlarm() 
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
            
            val (nextState, endTime) = when {
                prefs.currentState == PomodoroState.IDLE || prefs.currentState == PomodoroState.COMPLETED -> {
                    val duration = prefs.focusMinutes.toLong() * 60 * 1000
                    PomodoroState.FOCUS to (System.currentTimeMillis() + duration)
                }
                prefs.currentState == PomodoroState.PAUSED -> {
                    PomodoroState.FOCUS to (System.currentTimeMillis() + prefs.remainingMillis)
                }
                prefs.timerEndTimestamp == null -> {
                    val duration = if (prefs.currentState == PomodoroState.BREAK) 
                        prefs.breakMinutes.toLong() * 60 * 1000 
                    else 
                        prefs.focusMinutes.toLong() * 60 * 1000
                    prefs.currentState to (System.currentTimeMillis() + duration)
                }
                else -> {
                    prefs.currentState to (System.currentTimeMillis() + prefs.remainingMillis)
                }
            }

            dataStore.updateState(nextState, prefs.currentRound, endTime)
            
            startForeground(NOTIFICATION_ID, createNotification(getString(R.string.timer_running)))
            
            while (isActive) {
                val currentPrefs = dataStore.preferencesFlow.first()
                if (currentPrefs.currentState == PomodoroState.PAUSED || currentPrefs.currentState == PomodoroState.IDLE) break
                
                val currentEndTime = currentPrefs.timerEndTimestamp ?: break
                val remaining = currentEndTime - System.currentTimeMillis()

                if (remaining <= 0) {
                    handleTransition(currentPrefs)
                    break 
                } else {
                    updateNotification(remaining)
                    dataStore.updateRemaining(remaining)
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
                dataStore.updateState(PomodoroState.PAUSED, prefs.currentRound, null, maxOf(0, remaining))
                updateNotification(remaining)
            }
        }
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)!!
        }
    }

    private suspend fun handleTransition(prefs: br.com.darkton.pomodoro.data.PomodoroPreferences) {
        val vibrator = getVibrator()
        
        val urgencyPattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
        vibrate(vibrator, VibrationEffect.createWaveform(urgencyPattern, -1))
        
        playAlarm()
        
        val nextRemaining = if (prefs.currentState == PomodoroState.FOCUS) 
            prefs.breakMinutes.toLong() * 60 * 1000 
        else 
            prefs.focusMinutes.toLong() * 60 * 1000
            
        val nextRound = if (prefs.currentState == PomodoroState.BREAK) prefs.currentRound + 1 else prefs.currentRound
        
        if (prefs.currentState == PomodoroState.BREAK && prefs.currentRound >= prefs.totalRounds) {
            dataStore.updateState(PomodoroState.COMPLETED, prefs.currentRound, null)
            showCompletionNotification()
            stopTimer()
        } else {
            dataStore.updateState(
                state = PomodoroState.PAUSED,
                round = nextRound,
                endTimestamp = null,
                remaining = nextRemaining
            )
            showAlarmNotification()
        }
    }

    private fun playAlarm() {
        stopAlarm()
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            currentRingtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            currentRingtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            currentRingtone?.play()

            stopAlarmRunnable = Runnable { stopAlarm() }
            stopAlarmRunnable?.let { handler.postDelayed(it, 30000) }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarm() {
        currentRingtone?.stop()
        currentRingtone = null
        stopAlarmRunnable?.let { handler.removeCallbacks(it) }
        stopAlarmRunnable = null
        val vibrator = getVibrator()
        vibrator.cancel()
    }

    private fun showAlarmNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.phase_complete_title))
            .setContentText(getString(R.string.phase_complete_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .addAction(0, getString(R.string.open_app), pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
        
        try {
            startActivity(notificationIntent)
        } catch (e: Exception) {
            // OS might block direct start
        }
    }

    private fun showCompletionNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.pomodoro_completed_title))
            .setContentText(getString(R.string.pomodoro_completed_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2, notification)
    }

    private fun vibrate(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(1000)
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        stopAlarm()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            serviceChannel.enableVibration(true)
            serviceChannel.setSound(null, null)
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(remainingMs: Long) {
        val minutes = maxOf(0, (remainingMs / 1000) / 60)
        val seconds = maxOf(0, (remainingMs / 1000) % 60)
        val timeStr = String.format("%02d:%02d", minutes, seconds)
        val notification = createNotification(getString(R.string.remaining_time, timeStr))
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "br.com.darkton.pomodoro.START"
        const val ACTION_PAUSE = "br.com.darkton.pomodoro.PAUSE"
        const val ACTION_STOP = "br.com.darkton.pomodoro.STOP"
    }
}
