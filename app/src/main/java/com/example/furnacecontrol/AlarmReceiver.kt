package com.example.furnacecontrol

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

object AlarmHelper {
    private const val CHANNEL = "furnace_finished"
    private const val PREFS = "furnace_preferences"
    private const val SOUND_URI = "alarm_sound_uri"

    fun getSelectedSound(context: Context): String = context.getSharedPreferences(PREFS, 0)
        .getString(SOUND_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString().orEmpty()).orEmpty()

    fun setSelectedSound(context: Context, uri: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString(SOUND_URI, uri).apply()
    }

    fun schedule(context: Context, timer: FurnaceTimer) {
        if (!timer.running) return
        val intent = Intent(context, AlarmReceiver::class.java).putExtra("furnace_id", timer.id)
        val pi = PendingIntent.getBroadcast(context, timer.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endAt, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endAt, pi)
        }
    }

    fun cancel(context: Context, id: Int) {
        val pi = PendingIntent.getBroadcast(context, id,
            Intent(context, AlarmReceiver::class.java).putExtra("furnace_id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL, "Окончание обработки",
                NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // Звук воспроизводит отдельный сервис до подтверждения
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun startRinging(context: Context) {
        val intent = Intent(context, FurnaceAlarmService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun stopRinging(context: Context) {
        context.stopService(Intent(context, FurnaceAlarmService::class.java))
    }

    fun refreshRinging(context: Context) {
        val anyWaiting = TimerStore.load(context).any { it.completed }
        if (anyWaiting) startRinging(context) else stopRinging(context)
    }

    fun notification(context: Context) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("Требуется выгрузка")
        .setContentText("Есть печь, ожидающая подтверждения оператора")
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("furnace_id", -1)
        val timer = TimerStore.load(context).firstOrNull { it.id == id } ?: return
        if (!timer.running || timer.endAt > System.currentTimeMillis()) return
        val finished = timer.copy(completed = true)
        TimerStore.update(context, id) { finished }
        AlarmHelper.ensureChannel(context)
        AlarmHelper.startRinging(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        TimerStore.load(context).forEach { timer ->
            if (timer.running) {
                if (timer.endAt <= System.currentTimeMillis()) {
                    TimerStore.update(context, timer.id) { it.copy(completed = true) }
                } else AlarmHelper.schedule(context, timer)
            }
        }
        AlarmHelper.refreshRinging(context)
    }
}

class FurnaceAlarmService : Service() {
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AlarmHelper.ensureChannel(this)
        startForeground(9000, AlarmHelper.notification(this))
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FurnaceControl:Alarm")
            .apply { setReferenceCounted(false); acquire() }
        startAlarmSound()
    }

    private fun startAlarmSound() {
        try {
            val sound = AlarmHelper.getSelectedSound(this)
            val uri = sound.takeIf { it.isNotBlank() }?.let(Uri::parse)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri == null) return
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(this@FurnaceAlarmService, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (_: Exception) {
            // Если выбранный системный звук недоступен, пробуем стандартный сигнал будильника.
            try {
                val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
                player?.release()
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@FurnaceAlarmService, fallback)
                    isLooping = true
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            } catch (_: Exception) { }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (TimerStore.load(this).none { it.completed }) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        try { player?.stop() } catch (_: Exception) { }
        player?.release()
        player = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
