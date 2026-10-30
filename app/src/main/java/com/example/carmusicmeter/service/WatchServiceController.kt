package com.example.carmusicmeter.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object WatchServiceController {

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MediaWatchService::class.java),
        )
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, MediaWatchService::class.java))
    }
}
