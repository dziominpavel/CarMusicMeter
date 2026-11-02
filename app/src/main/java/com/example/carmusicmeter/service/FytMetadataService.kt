package com.example.carmusicmeter.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.FytMetadataStatus
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.StockPlayerBridge
import com.example.carmusicmeter.data.StockPlayerParser
import com.example.carmusicmeter.data.StockPlayerProtocol
import com.example.carmusicmeter.data.StockPlayerSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Принимает metadata штатного плеера FYT.
 *
 * Штатный плеер адресует `com.fyt.launcher.music` конкретным пакетам. Наше
 * приложение установлено под `com.syu.widget.music`, поэтому получает intent
 * напрямую: без второго APK, bridge и signature-permission.
 *
 * Сервис ничего не воспроизводит, не меняет громкость и не запрашивает
 * аудиофокус. Он только читает extras и обновляет состояние.
 */
class FytMetadataService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FYT_MUSIC) {
            handle(intent)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent) {
        val extras = intent.extras ?: return
        val map = extras.keySet().associateWith { key -> extras.get(key) }
        val now = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtime()

        val state = StockPlayerParser.fromBroadcast(
            extras = map,
            nowMs = now,
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            nowMonoMs = mono,
            source = StockPlayerSources.FYT_SERVICE,
        ) ?: return

        StockPlayerBridge.update(state)

        val metadataKeys = map.keys
        val changed = FytMetadataStatus.update(
            packageName = StockPlayerProtocol.STOCK_PACKAGE,
            keys = metadataKeys,
            title = state.title,
            artist = state.artist,
            playing = state.playing,
            nowMs = now,
        )

        val app = applicationContext as? CarMusicMeterApp ?: return
        if (changed) {
            val event = FytMetadataStatus.toLogEvent(now)
            ioScope.launch {
                runCatching {
                    app.eventLog.append(event)
                    ListeningRepository.record(listOf(event))
                }
            }
        }

        if (app.prefs.autoStart && !ListeningRepository.state.value.serviceRunning) {
            runCatching {
                WatchServiceController.start(this, StartSource.FYT_SERVICE)
            }
        }
    }

    private companion object {
        const val ACTION_FYT_MUSIC = "com.fyt.launcher.music"

        // Не отменяем scope в onDestroy: первый fyt_metadata_service должен
        // успеть попасть на диск, даже если система сразу остановит сервис.
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
