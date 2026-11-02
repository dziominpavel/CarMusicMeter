package com.example.carmusicmeter.autostart

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.example.carmusicmeter.service.StartSource

/**
 * Страховочный автозапуск через JobScheduler.
 *
 * Прошивка Teyes может не отдавать BOOT_COMPLETED без своего whitelist.
 * Persisted job переживает перезагрузку и запускает службу, когда система
 * сочтёт возможным; ошибка старта не роняет приложение.
 *
 * Кроме разового job после загрузки есть периодический watchdog: он поднимает
 * службу, если процесс убили, но автозапуск включён.
 */
object AutostartScheduler {

    const val JOB_ID_ONESHOT = 4201
    const val JOB_ID_WATCHDOG = 4202
    const val WATCHDOG_INTERVAL_MS = 15 * 60_000L

    fun sync(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) {
            scheduler.cancel(JOB_ID_ONESHOT)
            scheduler.cancel(JOB_ID_WATCHDOG)
            return
        }

        val component = ComponentName(context, AutostartJobService::class.java)
        val oneShot = JobInfo.Builder(JOB_ID_ONESHOT, component)
            .setPersisted(true)
            .setMinimumLatency(10_000L)
            .setOverrideDeadline(60_000L)
            .build()
        runCatching { scheduler.schedule(oneShot) }

        val watchdog = JobInfo.Builder(JOB_ID_WATCHDOG, component)
            .setPersisted(true)
            .setPeriodic(WATCHDOG_INTERVAL_MS)
            .build()
        runCatching { scheduler.schedule(watchdog) }
    }

    fun sourceForJob(jobId: Int): StartSource =
        if (jobId == JOB_ID_WATCHDOG) StartSource.WATCHDOG else StartSource.JOB

    fun shouldStart(autoStart: Boolean, serviceRunning: Boolean): Boolean =
        autoStart && !serviceRunning
}
