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
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.carmusicmeter.AppVersion
import com.example.carmusicmeter.MainActivity
import com.example.carmusicmeter.R
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.AccessibilityConnection
import com.example.carmusicmeter.data.CaptureErrorThrottle
import com.example.carmusicmeter.data.CaptureGap
import com.example.carmusicmeter.data.CaptureHealth
import com.example.carmusicmeter.data.CaptureWrite
import com.example.carmusicmeter.data.CaptureWriteException
import com.example.carmusicmeter.data.ClockJumpDetector
import com.example.carmusicmeter.data.ClockStatus
import com.example.carmusicmeter.data.DeviceProfile
import com.example.carmusicmeter.data.FytMetadataStatus
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.ListeningTracker
import com.example.carmusicmeter.data.LiveTrack
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.data.MediaButtonEvent
import com.example.carmusicmeter.data.MediaPhase
import com.example.carmusicmeter.data.MediaSnapshot
import com.example.carmusicmeter.data.NoSourceGapTracker
import com.example.carmusicmeter.data.OpenPlay
import com.example.carmusicmeter.data.PlayRecord
import com.example.carmusicmeter.data.RestoreDecision
import com.example.carmusicmeter.data.ServiceProcessInfo
import com.example.carmusicmeter.data.decideRestore
import com.example.carmusicmeter.data.shouldPersistOpenPlay
import com.example.carmusicmeter.data.ServiceStartGate
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
    private var lastAccessibilityEnabled: Boolean? = null
    private var lastAccessibilityConnected: Boolean? = null
    private val noSourceGap = NoSourceGapTracker()
    private val startGate = ServiceStartGate()
    private val serviceStartedMonoMs = SystemClock.elapsedRealtime()
    private var stopReason = "destroy"
    private var lastCaptureErrorLoggedAt: Long? = null
    private var lastLive: LiveTrack? = null
    private var lastReading = VolumeReading(0, 0, VolumeSource.AUDIO)
    private var lastClockWallMs: Long? = null
    private var lastClockMonoMs: Long? = null

    /** Когда последний раз сохраняли достоверное время; так первый тик пишет сразу. */
    private var lastClockPersistMonoMs = -60_000L

    /** Прослушивание от прошлого процесса, решение по которому ещё не принято. */
    private var pendingRestored: OpenPlay? = null
    private var pendingRestoredSinceMonoMs = 0L
    private var notificationAccess = false
    private var finalized = false

    @Volatile
    private var stopping = false

    @Volatile
    private var health = CaptureHealth()

    private val isTeyesUnit = DeviceProfile.isTeyes(Build.MANUFACTURER, Build.MODEL)

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

    private val mediaButtonReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
            val keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return
            val event = MediaButtonEvent.fromKey(
                keyCode = keyEvent.keyCode,
                action = keyEvent.action,
                eventTime = keyEvent.eventTime,
                repeatCount = keyEvent.repeatCount,
                sourcePackage = intent.getStringExtra("sourcePak"),
            ) ?: return
            val logEvent = event.toLogEvent(System.currentTimeMillis(), lastLive)
            scope.launch(Dispatchers.IO) {
                runCatching {
                    app.eventLog.append(logEvent)
                    ListeningRepository.record(listOf(logEvent))
                }
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
        registerMediaButtonReceiver()
        health = CaptureHealth()
        notificationAccess = probe.hasNotificationAccess()
        ListeningRepository.updateCaptureHealth(health)
        ListeningRepository.setServiceRunning(true)
        ListeningRepository.setTeyesDevice(isTeyesUnit)
        ListeningRepository.setNotificationAccess(notificationAccess)
        ListeningRepository.setAccessibilityEnabled(AccessibilityAccess.isEnabled(this))
        ListeningRepository.updateClock(app.eventLog.unverifiedEvents)
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

    private fun registerMediaButtonReceiver() {
        val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON)
        runCatching {
            ContextCompat.registerReceiver(
                this,
                mediaButtonReceiver,
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
        if (intent?.action == ACTION_STOP) {
            stopReason = "manual"
            stopSelf()
            return START_NOT_STICKY
        }
        stopping = false
        startForegroundCompat()
        logServiceStart(intent)
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { watchLoop() }
        }
        return START_STICKY
    }

    private fun logServiceStart(intent: Intent?) {
        if (!startGate.shouldLog()) return
        val source = intent?.getStringExtra(WatchServiceController.EXTRA_SOURCE) ?: "system_restart"
        val pid = Process.myPid()
        app.eventLog.append(
            LogEvent(
                ts = System.currentTimeMillis(),
                type = "service_started",
                fields = linkedMapOf(
                    "source" to source,
                    "pid" to pid,
                    "newProcess" to ServiceProcessInfo.newProcess(pid),
                ),
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

        runCatching {
            app.eventLog.append(
                LogEvent(
                    ts = System.currentTimeMillis(),
                    type = "service_stopped",
                    fields = linkedMapOf(
                        "reason" to stopReason,
                        "pid" to Process.myPid(),
                        "uptimeMs" to (SystemClock.elapsedRealtime() - serviceStartedMonoMs)
                            .coerceAtLeast(0L),
                    ),
                ),
            )
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
        runCatching { unregisterReceiver(mediaButtonReceiver) }
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
                appendEvents(events)
                ListeningRepository.record(events)
            }
        }.onFailure { error ->
            health = health.failure("finalize: ${error.message ?: error.javaClass.simpleName}")
            ListeningRepository.updateCaptureHealth(health)
        }
        // Прослушивание закрыто (или запись уже недоступна): сохранение больше
        // не нужно, иначе следующая служба закроет его второй раз.
        runCatching { app.prefs.openPlay = null }
        runCatching { app.eventLog.writeOpenPlay(null) }
    }

    /** Пишет события и, если среди них закрытие трека, готовый факт. */
    private fun appendEvents(events: List<LogEvent>) {
        if (events.isEmpty()) return
        val verified = ClockStatus.verified
        val appVersion = AppVersion.name(this)
        val plays = events.mapNotNull { event ->
            PlayRecord.fromTrackEnded(
                event = event,
                bootId = app.eventLog.bootId,
                deviceId = app.prefs.deviceId,
                appVersion = appVersion,
                clockVerified = verified,
            )
        }
        app.eventLog.appendBatch(events, plays)
        if (plays.isNotEmpty() && app.prefs.liveSince == null) {
            app.prefs.liveSince = System.currentTimeMillis()
        }
    }

    private fun persistOpenPlay(time: TickTime) {
        runCatching {
            val open = tracker.openPlay(app.eventLog.bootId, time)
            app.prefs.openPlay = open
            app.eventLog.writeOpenPlay(
                open?.let {
                    PlayRecord.fromOpenPlay(
                        open = it,
                        deviceId = app.prefs.deviceId,
                        appVersion = AppVersion.name(this),
                        clockVerified = ClockStatus.verified,
                    )
                },
            )
        }.onFailure { error ->
            health = health.failure("open_play: ${error.message ?: error.javaClass.simpleName}")
            ListeningRepository.updateCaptureHealth(health)
        }
    }

    /**
     * Запоминает прослушивание, оставшееся от предыдущего процесса.
     *
     * Решение принимается на первом тике ([resolvePendingRestored]): если
     * играет тот же трек, прослушивание продолжается, иначе оно закрывается
     * одним `track_ended(restored = true)`. Раньше закрытие выполнялось сразу,
     * и обновление APK во время трека давало лишнее прослушивание на 0 мс.
     */
    private fun loadPendingRestored() {
        val open = runCatching { app.prefs.openPlay }.getOrNull() ?: return
        if (open.bootId == app.eventLog.bootId) return
        pendingRestored = open
        pendingRestoredSinceMonoMs = SystemClock.elapsedRealtime()
    }

    /**
     * Продолжает или закрывает восстановленное прослушивание.
     *
     * Возвращает событие, если прослушивание пришлось закрыть. Пока снимок не
     * пришёл, ожидание длится [RESTORE_GRACE_MS]: плеер мог ещё не отдать
     * метаданные после холодного старта.
     */
    private fun resolvePendingRestored(time: TickTime, snapshot: MediaSnapshot?): LogEvent? {
        val pending = pendingRestored ?: return null
        when (
            decideRestore(
                pendingTrackKey = pending.trackKey,
                snapshotTrackKey = snapshot?.trackKey,
                elapsedMs = time.monoMs - pendingRestoredSinceMonoMs,
            )
        ) {
            RestoreDecision.WAIT -> return null

            RestoreDecision.CONTINUE -> {
                pendingRestored = null
                if (snapshot != null && tracker.adoptRestored(pending, time, snapshot)) {
                    // Тот же трек: одно прослушивание вместо закрытия на 0 мс и
                    // второго старта. Состояние обновляем сразу — если процесс
                    // убьют, следующий подхватит актуальные playedMs и startedAt.
                    persistOpenPlay(time)
                    return null
                }
            }

            RestoreDecision.CLOSE -> Unit
        }

        pendingRestored = null
        return runCatching {
            val event = tracker.closeRestored(pending, time)
            app.prefs.openPlay = null
            app.eventLog.writeOpenPlay(null)
            event
        }.onFailure { error ->
            health = health.failure("restore_play: ${error.message ?: error.javaClass.simpleName}")
            ListeningRepository.updateCaptureHealth(health)
        }.getOrNull()
    }

    private suspend fun watchLoop() {
        loadPendingRestored()
        var tick = 0L
        var accessibilityEnabled = AccessibilityAccess.isEnabled(this)
        while (currentCoroutineContext().isActive && !stopping) {
            val time = TickTime(
                wallMs = System.currentTimeMillis(),
                monoMs = SystemClock.elapsedRealtime(),
            )
            logClockChange(time)
            rememberVerifiedClock(time)
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
        val accessibilityConnected = AccessibilityConnection.isConnected(mono)

        val events = mutableListOf<LogEvent>()
        events += tracker.onVolume(
            time = time,
            volume = reading.value,
            volumeMax = reading.max ?: 0,
            source = reading.source.value,
            muted = reading.muted,
            sourceReason = reading.reason,
        )
        events += teyesEventIfChanged(now, teyesStatus)
        events += stockEventIfChanged(now, stockState)
        events += accessibilityEventIfChanged(now, accessibilityEnabled, accessibilityConnected)

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
        val noSource = audioManager.isMusicActive && snapshot == null &&
            (!accessibilityEnabled || !accessibilityConnected)
        when (noSourceGap.update(noSource)) {
            true -> events += captureGapEvent(now, active = true, reason = "no_source")
            false -> events += captureGapEvent(now, active = false, reason = "source_available")
            null -> Unit
        }
        resolvePendingRestored(time, snapshot)?.let { events += it }
        val result = tracker.onTick(
            time = time,
            snapshot = snapshot,
            volume = reading.value,
            volumeMax = reading.max ?: 0,
            source = reading.source.value,
            muted = reading.muted,
            sourceReason = reading.reason,
        )
        events += result.events
        val checkpoint = tracker.checkpoint(time, reading.value, reading.max ?: 0)
        checkpoint?.let { events += it }

        if (events.isNotEmpty()) {
            try {
                CaptureWrite.append(events) { appendEvents(it) }
                ListeningRepository.record(events)
            } catch (error: CaptureWriteException) {
                throw CaptureFailure("append", error.droppedEvents, error.cause ?: error)
            }
        }

        // Открытое прослушивание сохраняем только после того, как события легли
        // на диск: иначе восстановление закрыло бы трек без его начала. После
        // track_ended сохраняем тоже — тогда закрытое прослушивание стирается и
        // не закрывается второй раз при следующем старте службы.
        if (shouldPersistOpenPlay(events, checkpoint)) {
            persistOpenPlay(time)
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
            accessibilityConnected = accessibilityConnected,
            musicActive = audioManager.isMusicActive,
            fytMetadataReceived = FytMetadataStatus.received,
            fytMetadataAt = FytMetadataStatus.updatedAtMs,
        )
        updateNotification(result.live, reading, health)
        ListeningRepository.updateClock(app.eventLog.unverifiedEvents)
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
        // Часы исправились: со следующего тика журнал снова пишется по дате, а
        // поправка уходит в событие, чтобы отчёт пересчитал уже записанное.
        ClockStatus.onClockChanged(time.wallMs, jump.driftMs)
        runCatching { app.prefs.lastVerifiedWallMs = time.wallMs }
        runCatching {
            val event = LogEvent(
                ts = time.wallMs,
                type = "clock_changed",
                fields = linkedMapOf(
                    "wallDelta" to jump.wallDeltaMs,
                    "monoDelta" to jump.monoDeltaMs,
                    "driftMs" to jump.driftMs,
                    "shiftMs" to jump.driftMs,
                ),
            )
            app.eventLog.append(event)
            ListeningRepository.record(listOf(event))
        }
    }

    /**
     * Запоминает достоверное время для следующего запуска: по нему отличается
     * холодный старт со сбитыми часами. Пишется не чаще раза в минуту.
     */
    private fun rememberVerifiedClock(time: TickTime) {
        if (!ClockStatus.verified) return
        ClockStatus.touchVerified(time.wallMs)
        if (time.monoMs - lastClockPersistMonoMs < CLOCK_PERSIST_INTERVAL_MS) return
        lastClockPersistMonoMs = time.monoMs
        runCatching { app.prefs.lastVerifiedWallMs = time.wallMs }
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
        val status = teyes.status()
        val mcu = teyes.reading().let {
            if (!status.available && !isTeyesUnit) it.copy(reason = "audio_fallback") else it
        }
        return VolumeSelector.select(
            mcu = mcu,
            audioValue = audioValue,
            audioMax = audioMax,
            mcuMax = app.prefs.mcuMaxVolume,
            fallbackReason = mcu.reason,
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

    private fun captureGapEvent(now: Long, active: Boolean, reason: String): LogEvent {
        val gap = CaptureGap(
            active = active,
            reason = reason,
            last = StockPlayerBridge.latest(),
            isMusicActive = audioManager.isMusicActive,
            windows = emptyList(),
        )
        return LogEvent(now, "capture_gap", gap.toFields())
    }

    private fun accessibilityEventIfChanged(
        now: Long,
        enabled: Boolean,
        connected: Boolean,
    ): List<LogEvent> {
        val previousEnabled = lastAccessibilityEnabled
        val previousConnected = lastAccessibilityConnected
        lastAccessibilityEnabled = enabled
        lastAccessibilityConnected = connected
        if (previousEnabled == enabled && previousConnected == connected) return emptyList()

        val events = mutableListOf<LogEvent>()
        events += LogEvent(
            ts = now,
            type = "accessibility_state_changed",
            fields = linkedMapOf(
                "enabled" to enabled,
                "connected" to connected,
                "initial" to (previousEnabled == null && previousConnected == null),
            ),
        )
        if (previousConnected == true && !connected) {
            events += LogEvent(
                ts = now,
                type = "accessibility_disconnected",
                fields = linkedMapOf("enabled" to enabled),
            )
        }
        return events
    }

    private fun stockEventIfChanged(
        now: Long,
        state: com.example.carmusicmeter.data.StockPlayerState?,
    ): List<LogEvent> {
        val signature = state?.textSignature
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
        const val ACTION_STOP = "com.example.carmusicmeter.action.STOP"
        private const val FINALIZE_TIMEOUT_MS = 2_000L

        /** Как часто последнее достоверное время сохраняется в настройки. */
        private const val CLOCK_PERSIST_INTERVAL_MS = 60_000L
    }
}
