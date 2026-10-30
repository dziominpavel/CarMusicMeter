package com.example.tunemileage.diagnostics

import com.example.tunemileage.data.EventLog
import com.example.tunemileage.data.LogEvent
import java.io.File

/**
 * Пишет стек падения в файл рядом с журналом. На магнитоле нет logcat,
 * поэтому это единственный способ увидеть, почему приложение упало.
 */
object CrashLogger {

    fun install(log: EventLog) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ts = System.currentTimeMillis()
                val file = File(log.logsDir, "crash-$ts.txt")
                file.writeText(
                    buildString {
                        appendLine("time=$ts")
                        appendLine("thread=${thread.name}")
                        appendLine()
                        append(throwable.stackTraceToString())
                    },
                    Charsets.UTF_8,
                )
                log.append(
                    LogEvent(
                        ts = ts,
                        type = "app_crash",
                        fields = mapOf(
                            "message" to throwable.message,
                            "exception" to throwable.javaClass.name,
                            "file" to file.name,
                        ),
                    ),
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                throwable.printStackTrace()
            }
        }
    }
}
