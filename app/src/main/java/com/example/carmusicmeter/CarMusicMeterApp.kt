package com.example.carmusicmeter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.carmusicmeter.autostart.AutostartScheduler
import com.example.carmusicmeter.data.EventLog
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.Prefs
import com.example.carmusicmeter.diagnostics.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CarMusicMeterApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var eventLog: EventLog
        private set
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        eventLog = EventLog(filesDir)
        prefs = Prefs(this)
        CrashLogger.install(eventLog)
        createNotificationChannel()
        AutostartScheduler.sync(this, prefs.autoStart)
        appScope.launch {
            ListeningRepository.attach(eventLog)
            eventLog.trimOld(prefs.storagePolicy())
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_WATCH,
            getString(R.string.channel_watch_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_watch_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_WATCH = "listening_watch"

        lateinit var instance: CarMusicMeterApp
            private set
    }
}
