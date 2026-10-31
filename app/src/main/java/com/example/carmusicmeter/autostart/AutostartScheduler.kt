package com.example.carmusicmeter.autostart

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * Страховочный автозапуск через JobScheduler.
 *
 * Прошивка Teyes может не отдавать BOOT_COMPLETED без своего whitelist.
 * Persisted job переживает перезагрузку и запускает службу, когда система
 * сочтёт возможным; ошибка старта не роняет приложение.
 */
object AutostartScheduler {

    private const val JOB_ID = 4201

    fun sync(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, AutostartJobService::class.java),
        )
            .setPersisted(true)
            .setMinimumLatency(10_000L)
            .setOverrideDeadline(60_000L)
            .build()
        runCatching { scheduler.schedule(job) }
    }
}
