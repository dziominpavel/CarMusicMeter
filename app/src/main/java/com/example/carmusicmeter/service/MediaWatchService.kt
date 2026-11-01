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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.carmusicmeter.MainActivity
import com.example.carmusicmeter.R
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.CaptureErrorThrottle
import com.example.carmusicmeter.data.CaptureHealth
import com.example.carmusicmeter.data.CaptureWrite
import com.example.carmusicmeter.data.CaptureWriteException
import com.example.carmusicmeter.data.ClockJumpDetector
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.ListeningTracker
import com.example.carmusicmeter.data.LiveTrack
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.data.MediaPhase
import com.example.carmusicmeter.data.SourcePicker
import com.example.carmusicmeter.data.StockPlayerBridge
import com.example.carmusicmeter.data.StockPlayerParser
import com.example.carmusicmeter.data.StockPlayerProtocol
import com.example.carmusicmeter.data.TickBackoff
import com.example.carmusicmeter.data.TickTime
import com.example.carmusicmeter.data.VolumeReading
import com.example.carmusicmeter.data.VolumeSelector
import com.example.carmusicmeter.data.VolumeSource
import com.example.carmusicmeter.diagnostics.SnapshotWriter
import com.example.carmusicmeter.probe.MediaSessionProbe
import com.example.carmusicmeter.teyes.TeyesToolkitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
    private var lastCaptureErrorLoggedAt: Long? = null
    private var lastLive: LiveTrack? = null
    private var lastReading = VolumeReading(0, 0, VolumeSource.AUDIO)
    private var lastClockWallMs: Long? = null
    private var lastClockMonoMs: Long? = null
    private var notificationAccess = false
    private var finalized = false

    @Volatile
    private var stopping = false

    @Volatile
    private var health = CaptureHealth()

    private val isTeyesUnit =
        Build.MANUFACTURER.equals("sprd", ignoreCase = true) ||
            Build.MODEL.contains("ums512", ignoreCase = true)

    private val app: CarMusicMeterApp get() = application as CarMusicMeterApp

    private val stockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val now = System.currentTimeMillis()
            val mono = SystemClock.elapsedRealtime()
            when (action) {
                StockPlayerProtocol.TITLES_RECEIVER -> {
                    val extras = bundleToMap(intent.extras)
                    StockPlayerParser.fromBroadcast(
                        extras = extras,
                        nowMs = now,
                        packageName = StockPlayerProtocol.resolvePackage(this@MediaWatchService),
                        nowMonoMs = mono,
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
        health = CaptureHealth()
        notificationAccess = probe.hasNotificationAccess()
        ListeningRepository.updateCaptureHealth(health)
        ListeningRepository.setServiceRunning(true)
        ListeningRepository.setNotificationAccess(notificationAccess)
        ListeningRepository.setAccessibilityEnabled(AccessibilityAccess.isEnabled(this))
    }

    private fun registerStockReceiver() {
        val filter = IntentFilter().apply {
            addAction(StockPlayerProtocol.TITLES_RECEIVER)
            addAction(StockPlayerProtocol.REMOVE_RECEIVER)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                stockReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun bundleToMap(bundle: Bundle?): Map<String, Any?> {
        if (bundle == null) return emptyMap()
        return bundle.keySet().associateWith { key -> bundle.get(key) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        startForegroundCompat()
        logServiceStart(intent)
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { watchLoop() }
        }
        return START_STICKY
    }

    private fun logServiceStart(intent: Intent?) {
        if (startEventLogged) return
        val source = intent?.getStringExtra(WatchServiceController.EXTRA_SOURCE) ?: "system_restart"
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
        stopping = true
        loopJob?.cancel()

        val joined = runBlocking {
            withTimeoutOrNull(FINALIZE_TIMEOUT_MS) {
                loopJob?.join()
                true
            }
        } ?: false

        if (joined) {
            finalizeOnce()
        } else {
            health = health.failure("finalize_timeout")
            ListeningRepository.updateCaptureHealth(health)
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
        scope.cancel()
        super.onDestroy()
    }

    private fun finalizeOnce() {
        if (finalized) return
        finalized = true
        runCatching {
            val time = TickTime(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            val events = tracker.finish(time, "service_stopped")
            if (events.isNotEmpty()) {
                app.eventLog.appendAll(events)
                ListeningRepository.record(events)
            }
        }.onFailure { error ->
            health = health.failure("finalize: ${error.message ?: error.javaClass.simpleName}")
            ListeningRepository.updateCaptureHealth(health)
        }
    }

    private suspend fun watchLoop() {
        var tick = 0L
        var accessibilityEnabled = AccessibilityAccess.isEnabled(this)
        while (currentCoroutineContext().isActive && !stopping) {
            val time = TickTime(
                wallMs = System.currentTimeMillis(),
                monoMs = SystemClock.elapsedRealtime(),
            )
            logClockChange(time)
            health = health.tickAttempt(time.wallMs)
            ListeningRepository.updateCaptureHealth(health)

            try {
                if (tick % 20L == 0L) {
                    accessibilityEnabled = AccessibilityAccess.isEnabled(this)
                    notificationAccess = probe.hasNotificationAccess()
                }
                val delayMs = executeTick(time, tick, accessibilityEnabled)
                tick += 1
                val failuresBefore = health.consecutiveFailures
                val errorBefore = health.lastError
                health = health.success(time.wallMs)
                if (failuresBefore > 0) {
                    logCaptureRecovered(time, failuresBefore, errorBefore)
                }
                lastCaptureErrorLoggedAt = null
                ListeningRepository.updateCaptureHealth(health)
                updateNotification(lastLive, lastReading, health)
                delay(delayMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CaptureFailure) {
                health = health.failure(
                    "${failure.stage}: ${failure.cause?.message ?: failure.cause?.javaClass?.simpleName}",
                    dropped = failure.droppedEvents,
                )
                logCaptureError(time, failure.stage, failure.cause ?: failure)
                ListeningRepository.updateCaptureHealth(health)
                updateNotification(lastLive, lastReading, health)
                delay(TickBackoff.delayMs(health.consecutiveFailures))
            } catch (error: Exception) {
                health = health.failure(
                    "tick: ${error.message ?: error.javaClass.simpleName}",
                )
                logCaptureError(time, "tick", error)
                ListeningRepository.updateCaptureHealth(health)
                updateNotification(lastLive, lastReading, health)
                delay(TickBackoff.delayMs(health.consecutiveFailures))
            }
        }
    }

    private fun executeTick(
        time: TickTime,
        tick: Long,
        accessibilityEnabled: Boolean,
    ): Long {
        val now = time.wallMs
        val mono = time.monoMs
        val reading = readVolume()
        val teyesStatus = teyes.status()
        val stockState = StockPlayerBridge.activeState(mono, audioManager.isMusicActive)

        val events = mutableListOf<LogEvent>()
        events += tracker.onVolume(
            time = time,
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

        val stockSnapshot = stockState?.toSnapshot(mono, audioManager.isMusicActive)
        val snapshot = SourcePicker.pick(
            mediaSessions = lastCapture.snapshots,
            stock = stockSnapshot,
            currentPackage = tracker.currentPackage,
        )
        val result = tracker.onTick(
            time = time,
            snapshot = snapshot,
            volume = reading.value,
            volumeMax = reading.max ?: 0,
            source = reading.source.value,
            muted = reading.muted,
        )
        events += result.events
        tracker.checkpoint(time, reading.value, reading.max ?: 0)?.let { events += it }

        if (events.isNotEmpty()) {
            try {
                CaptureWrite.append(events) { app.eventLog.appendAll(it) }
                ListeningRepository.record(events)
            } catch (error: CaptureWriteException) {
                throw CaptureFailure("append", error.droppedEvents, error.cause ?: error)
            }
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

        lastReading = reading
        lastLive = result.live
        ListeningRepository.updateLive(
            live = result.live,
            volume = reading.value,
            volumeMax = reading.max ?: 0,
            notificationAccess = notificationAccess,
            error = lastCapture.error,
            volumeSource = reading.source.value,
            teyesAvailable = teyesStatus.available,
            teyesError = teyesStatus.error,
            mcuVolume = teyesStatus.volume,
            mcuMuted = teyesStatus.muted,
            mcuMax = if (reading.source == VolumeSource.MCU) app.prefs.mcuMaxVolume else null,
            stockSource = stockState?.source,
            accessibilityEnabled = accessibilityEnabled,
        )
        updateNotification(result.live, reading, health)
        return if (result.live?.phase == MediaPhase.PLAYING) 500L else 1_000L
    }

    private fun logClockChange(time: TickTime) {
        val jump = ClockJumpDetector.detect(
            previousWallMs = lastClockWallMs,
            previousMonoMs = lastClockMonoMs,
            wallMs = time.wallMs,
            monoMs = time.monoMs,
        )
        lastClockWallMs = time.wallMs
        lastClockMonoMs = time.monoMs
        if (jump == null) return
        runCatching {
            val event = LogEvent(
                ts = time.wallMs,
                type = "clock_changed",
                fields = linkedMapOf(
                    "wallDelta" to jump.wallDeltaMs,
                    "monoDelta" to jump.monoDeltaMs,
                    "driftMs" to jump.driftMs,
                ),
            )
            app.eventLog.append(event)
            ListeningRepository.record(listOf(event))
        }
    }

    private fun logCaptureError(time: TickTime, stage: String, error: Throwable) {
        if (!CaptureErrorThrottle.shouldLog(
                nowMs = time.wallMs,
                lastLoggedAtMs = lastCaptureErrorLoggedAt,
                failures = health.consecutiveFailures,
            )
        ) {
            return
        }
        lastCaptureErrorLoggedAt = time.wallMs
        runCatching {
            app.eventLog.append(
                LogEvent(
                    ts = time.wallMs,
                    type = "capture_error",
                    fields = linkedMapOf(
                        "stage" to stage,
                        "message" to error.message,
                        "exception" to error.javaClass.name,
                        "consecutive" to health.consecutiveFailures,
                        "droppedEvents" to health.droppedEvents,
                    ),
                ),
            )
        }
    }

    private fun logCaptureRecovered(time: TickTime, failures: Int, lastError: String?) {
        runCatching {
            app.eventLog.append(
                LogEvent(
                    ts = time.wallMs,
                    type = "capture_recovered",
                    fields = linkedMapOf(
                        "failures" to failures,
                        "lastError" to lastError,
                    ),
                ),
            )
        }
    }

    private fun readVolume(): VolumeReading {
        val audioValue = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val audioMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return VolumeSelector.select(
            mcu = teyes.reading(),
            audioValue = audioValue,
            audioMax = audioMax,
            mcuMax = app.prefs.mcuMaxVolume,
        )
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
            buildNotification(null, VolumeReading(0, 0, VolumeSource.AUDIO), null),
            type,
        )
    }

    private fun updateNotification(
        live: LiveTrack?,
        reading: VolumeReading,
        health: CaptureHealth,
    ) {
        // На Android 13+ без разрешения уведомление просто не показывается;
        // служба при этом продолжает работать.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val signature = notificationSignature(live, reading, health)
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(live, reading, health.lastError))
        }
    }

    private fun notificationSignature(
        live: LiveTrack?,
        reading: VolumeReading,
        health: CaptureHealth,
    ): String = listOf(
        live?.packageName,
        live?.title,
        live?.artist,
        reading.value,
        reading.max,
        reading.source.value,
        health.consecutiveFailures,
        health.lastError,
    ).joinToString("|")

    private fun buildNotification(
        live: LiveTrack?,
        reading: VolumeReading,
        captureError: String?,
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
        val text = when {
            captureError != null -> "Ошибка сбора: ${captureError.take(90)}"
            live != null ->
                "${live.artist ?: "неизвестный исполнитель"} • громкость $volumeText ($sourceText)"
            else -> getString(R.string.notif_text_idle)
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

    private class CaptureFailure(
        val stage: String,
        val droppedEvents: Int,
        cause: Throwable,
    ) : Exception(cause)

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val FINALIZE_TIMEOUT_MS = 2_000L
    }
}
