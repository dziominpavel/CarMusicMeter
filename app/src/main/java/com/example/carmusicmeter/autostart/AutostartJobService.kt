package com.example.carmusicmeter.autostart

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.carmusicmeter.CarMusicMeterApp
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.LogEvent
import com.example.carmusicmeter.service.StartSource
import com.example.carmusicmeter.service.WatchServiceController

class AutostartJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as CarMusicMeterApp
        if (app.prefs.autoStart && !ListeningRepository.state.value.serviceRunning) {
            runCatching {
                WatchServiceController.start(this, StartSource.JOB)
            }.onFailure { error ->
                app.eventLog.append(
                    LogEvent(
                        System.currentTimeMillis(),
                        "service_start_failed",
                        mapOf("error" to error.message, "source" to "job"),
                    ),
                )
            }
        }
        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
