package com.example.carmusicmeter.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

enum class StartSource(val value: String) {
    MANUAL("manual"),
    BOOT("boot"),
    NOTIFICATION("notification"),
}

object WatchServiceController {

    const val EXTRA_SOURCE = "start_source"

    fun start(context: Context, source: StartSource) {
        val intent = Intent(context, MediaWatchService::class.java)
            .putExtra(EXTRA_SOURCE, source.value)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, MediaWatchService::class.java))
    }
}
