package com.example.carmusicmeter

import android.app.Activity
import android.os.Bundle
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.service.StartSource
import com.example.carmusicmeter.service.WatchServiceController

/**
 * Невидимый пункт для списка «Автомат. включ. при запуске» прошивки Teyes.
 *
 * Пользователь выбирает «CarMusicMeter (автозапуск)» в настройках Teyes;
 * activity поднимает foreground-службу и сразу закрывается, не показывая экран.
 */
class AutostartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CarMusicMeterApp
        if (app.prefs.autoStart && !ListeningRepository.state.value.serviceRunning) {
            runCatching {
                WatchServiceController.start(this, StartSource.TEYES_AUTOSTART)
            }.onFailure { error ->
                app.eventLog.append(
                    LogEvent(
                        System.currentTimeMillis(),
                        "service_start_failed",
                        mapOf("error" to error.message, "source" to "teyes_autostart"),
                    ),
                )
            }
        }
        finish()
    }
}
