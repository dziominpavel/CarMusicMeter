package com.example.carmusicmeter.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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
import com.example.carmusicmeter.data.SourcePicker
import com.example.carmusicmeter.data.StockPlayerBridge
import com.example.carmusicmeter.data.StockPlayerParser
import com.example.carmusicmeter.data.StockPlayerProtocol
import com.example.carmusicmeter.data.VolumeReading
import com.example.carmusicmeter.data.VolumeSelector
import com.example.carmusicmeter.data.VolumeSource
import com.example.carmusicmeter.diagnostics.SnapshotWriter
import com.example.carmusicmeter.probe.MediaSessionProbe
import com.example.carmusicmeter.teyes.TeyesToolkitClient
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
    private lateinit var teyes: TeyesToolkitClient
    private var lastCapture = MediaSessionProbe.CaptureResult(emptyList(), null)
    private var lastNotificationSignature: String? = null
    private var lastTeyesSignature: String? = null
    private var lastStockSignature: String? = null
    private var startEventLogged = false
    private val isTeyesUnit =
        Build.MANUFACTURER.equals("sprd", ignoreCase = true) ||
            Build.MODEL.contains("ums512", ignoreCase = true)

    private val app: CarMusicMeterApp get() = application as CarMusicMeterApp

    private val stockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val now = System.currentTimeMillis()
            when (action) {
                StockPlayerProtocol.TITLES_RECEIVER -> {
                    val extras = bundleToMap(intent.extras)
                    StockPlayerParser.fromBroadcast(
                        extras = extras,
                        nowMs = now,
                        packageName = StockPlayerProtocol.resolvePackage(this@MediaWatchService),
                    )?.let(StockPlayerBridge::update)
                }

                StockPlayerProtocol.REMOVE_RECEIVER -> StockPlayerBridge.remove()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        probe = MediaSessionProbe(this)
        tracker = ListeningTracker()
        audioManager = getSystemService(AudioManager::class.java)
        snapshotWriter = SnapshotWriter(this, probe)
        teyes = TeyesToolkitClient(this).also { it.start() }
        registerStockReceiver()
        ListeningRepository.setServiceRunning(true)
        ListeningRepository.setNotificationAccess(probe.hasNotificationAccess())
        ListeningRepository.setAccessibilityEnabled(AccessibilityAccess.isEnabled(this))
    }

    private fun registerStockReceiver() {
        val filter = IntentFilter().apply {
            addAction(StockPlayerProtocol.TITLES_RECEIVER)
            addAction(StockPlayerProtocol.REMOVE_RECEIVER)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(stockReceiver, filter)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun bundleToMap(bundle: Bundle?): Map<String, Any?> {
        if (bundle == null) return emptyMap()
        return bundle.keySet().associateWith { key -> bundle.get(key) }
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
        runCatching { unregisterReceiver(stockReceiver) }
        teyes.stop()
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watchLoop() {
        var tick = 0L
        var accessibilityEnabled = AccessibilityAccess.isEnabled(this)
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val reading = readVolume()
            val teyesStatus = teyes.status()
            val stockState = StockPlayerBridge.activeState(now, audioManager.isMusicActive)

            val events = mutableListOf<LogEvent>()
            events += tracker.onVolume(
                nowMs = now,
                volume = reading.value,
                volumeMax = reading.max ?: 0,
                source = reading.source.value,
                muted = reading.muted,
            )
            events += teyesEventIfChanged(now, teyesStatus)
            events += stockEventIfChanged(now, stockState)

            // Сессии — раз в секунду, громкость — каждые полсекунды.
            if (tick % 2L == 0L) {
                lastCapture = probe.capture()
            }
            if (tick % 10L == 0L) {
                teyes.refresh()
            }
            if (tick % 20L == 0L) {
                accessibilityEnabled = AccessibilityAccess.isEnabled(this)
            }
            tick += 1

            val stockSnapshot = stockState?.toSnapshot(now, audioManager.isMusicActive)
            val snapshot = SourcePicker.pick(
                mediaSessions = lastCapture.snapshots,
                stock = stockSnapshot,
                currentPackage = tracker.currentPackage,
            )
            val result = tracker.onTick(
                nowMs = now,
                snapshot = snapshot,
                volume = reading.value,
                volumeMax = reading.max ?: 0,
                source = reading.source.value,
                muted = reading.muted,
            )
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
                volume = reading.value,
                volumeMax = reading.max ?: 0,
                notificationAccess = probe.hasNotificationAccess(),
                error = lastCapture.error,
                volumeSource = reading.source.value,
                teyesAvailable = teyesStatus.available,
                teyesError = teyesStatus.error,
                mcuVolume = teyesStatus.volume,
                mcuMuted = teyesStatus.muted,
                stockSource = stockState?.source,
                accessibilityEnabled = accessibilityEnabled,
            )
            updateNotification(result.live, reading)

            delay(if (result.live?.phase == MediaPhase.PLAYING) 500L else 1_000L)
        }
    }

    private fun readVolume(): VolumeReading {
        val audioValue = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val audioMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return VolumeSelector.select(teyes.reading(), audioValue, audioMax)
    }

    private fun teyesEventIfChanged(
        now: Long,
        status: TeyesToolkitClient.Status,
    ): List<LogEvent> {
        if (status.signature == lastTeyesSignature) return emptyList()
        lastTeyesSignature = status.signature
        if (!isTeyesUnit && !status.available) return emptyList()
        return listOf(
            LogEvent(
                ts = now,
                type = "teyes_toolkit",
                fields = linkedMapOf(
                    "available" to status.available,
                    "connected" to status.connected,
                    "module" to status.module,
                    "volume" to status.volume,
                    "muted" to status.muted,
                    "error" to status.error,
                ),
            ),
        )
    }

    private fun stockEventIfChanged(
        now: Long,
        state: com.example.carmusicmeter.data.StockPlayerState?,
    ): List<LogEvent> {
        val signature = state?.metaSignature
        if (signature == lastStockSignature) return emptyList()
        lastStockSignature = signature
        if (state == null || state.title.isBlank()) return emptyList()
        return listOf(
            LogEvent(
                ts = now,
                type = "stock_player_text",
                fields = linkedMapOf(
                    "package" to state.packageName,
                    "title" to state.title,
                    "artist" to state.artist,
                    "album" to state.album,
                    "source" to state.source,
                    "playing" to state.playing,
                    "path" to state.path,
                    "durationMs" to state.durationMs,
                    "positionMs" to state.positionMs,
                    "rawTexts" to state.rawTexts,
                ),
            ),
        )
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
            buildNotification(null, VolumeReading(0, 0, VolumeSource.AUDIO)),
            type,
        )
    }

    private fun updateNotification(live: LiveTrack?, reading: VolumeReading) {
        // На Android 13+ без разрешения уведомление просто не показывается;
        // служба при этом продолжает работать.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val signature = notificationSignature(live, reading)
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(live, reading))
        }
    }

    private fun notificationSignature(live: LiveTrack?, reading: VolumeReading): String =
        listOf(
            live?.packageName,
            live?.title,
            live?.artist,
            reading.value,
            reading.max,
            reading.source.value,
        ).joinToString("|")

    private fun buildNotification(
        live: LiveTrack?,
        reading: VolumeReading,
    ): Notification {
        val volume = reading.value
        val volumeMax = reading.max ?: 0
        val volumeText = if (volumeMax > 0) "$volume/$volumeMax" else volume.toString()
        val sourceText = if (reading.source.value == "mcu") "MCU" else "Android"
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = live?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.notif_title)
        val text = if (live != null) {
            "${live.artist ?: "неизвестный исполнитель"} • громкость $volumeText ($sourceText)"
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
