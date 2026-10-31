package com.example.carmusicmeter.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.carmusicmeter.MainActivity
import com.example.carmusicmeter.R
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.ListeningTracker
import com.example.carmusicmeter.data.LiveTrack
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.data.MediaPhase
import com.example.carmusicmeter.data.SnapshotPicker
import com.example.carmusicmeter.diagnostics.SnapshotWriter
import com.example.carmusicmeter.probe.MediaSessionProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Невидимая служба: раз в полсекунды читает громкость и раз в секунду —
 * активные медиасессии, превращает изменения в события журнала.
 *
 * Служба foreground, иначе прошивка магнитолы убьёт её при уходе в фон.
 */
class MediaWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private lateinit var probe: MediaSessionProbe
    private lateinit var tracker: ListeningTracker
    private lateinit var audioManager: AudioManager
    private lateinit var snapshotWriter: SnapshotWriter
    private var lastCapture = MediaSessionProbe.CaptureResult(emptyList(), null)
    private var lastNotificationSignature: String? = null
    private var startEventLogged = false

    private val app: CarMusicMeterApp get() = application as CarMusicMeterApp

    override fun onCreate() {
        super.onCreate()
        probe = MediaSessionProbe(this)
        tracker = ListeningTracker()
        audioManager = getSystemService(AudioManager::class.java)
        snapshotWriter = SnapshotWriter(this, probe)
        ListeningRepository.setServiceRunning(true)
        ListeningRepository.setNotificationAccess(probe.hasNotificationAccess())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        logServiceStart(intent)
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { watchLoop() }
        }
        return START_STICKY
    }

    private fun logServiceStart(intent: Intent?) {
        if (startEventLogged) return
        val source = intent?.getStringExtra(WatchServiceController.EXTRA_SOURCE) ?: return
        startEventLogged = true
        app.eventLog.append(
            LogEvent(
                ts = System.currentTimeMillis(),
                type = "service_started",
                fields = mapOf("source" to source),
            ),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching {
            val events = tracker.finish(System.currentTimeMillis(), "service_stopped")
            if (events.isNotEmpty()) {
                app.eventLog.appendAll(events)
                ListeningRepository.record(events)
            }
        }
        ListeningRepository.setServiceRunning(false)
        ListeningRepository.updateLive(
            live = null,
            volume = 0,
            volumeMax = 0,
            notificationAccess = probe.hasNotificationAccess(),
            error = null,
        )
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watchLoop() {
        var tick = 0L
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            val events = mutableListOf<LogEvent>()
            events += tracker.onVolume(now, volume, volumeMax)

            // Сессии — раз в секунду, громкость — каждые полсекунды.
            if (tick % 2L == 0L) {
                lastCapture = probe.capture()
            }
            tick += 1

            val snapshot = SnapshotPicker.pick(lastCapture.snapshots, tracker.currentPackage)
            val result = tracker.onTick(now, snapshot, volume, volumeMax)
            events += result.events

            if (events.isNotEmpty()) {
                app.eventLog.appendAll(events)
                ListeningRepository.record(events)
            }

            if (events.any { it.type == "track_started" }) {
                runCatching {
                    snapshotWriter.capture(
                        reason = "track_started",
                        sessions = probe.sessionsJson(),
                        live = result.live,
                    )
                }
            }

            ListeningRepository.updateLive(
                live = result.live,
                volume = volume,
                volumeMax = volumeMax,
                notificationAccess = probe.hasNotificationAccess(),
                error = lastCapture.error,
            )
            updateNotification(result.live, volume, volumeMax)

            delay(if (result.live?.phase == MediaPhase.PLAYING) 500L else 1_000L)
        }
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(null, 0, 0),
            type,
        )
    }

    private fun updateNotification(live: LiveTrack?, volume: Int, volumeMax: Int) {
        // На Android 13+ без разрешения уведомление просто не показывается;
        // служба при этом продолжает работать.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val signature = notificationSignature(live, volume, volumeMax)
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(live, volume, volumeMax))
        }
    }

    private fun notificationSignature(live: LiveTrack?, volume: Int, volumeMax: Int): String =
        listOf(live?.packageName, live?.title, live?.artist, volume, volumeMax).joinToString("|")

    private fun buildNotification(
        live: LiveTrack?,
        volume: Int,
        volumeMax: Int,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = live?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.notif_title)
        val text = if (live != null) {
            "${live.artist ?: "неизвестный исполнитель"} • громкость $volume/$volumeMax"
        } else {
            getString(R.string.notif_text_idle)
        }
        return NotificationCompat.Builder(this, CarMusicMeterApp.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_stat_cmm)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
