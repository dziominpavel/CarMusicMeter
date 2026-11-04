package com.example.carmusicmeter.diagnostics

import com.example.carmusicmeter.data.EventLog
import com.example.carmusicmeter.data.LogEvent
import java.io.File

/**
 * Пишет стек падения в файл рядом с журналом. На магнитоле нет logcat,
 * поэтому это единственный способ увидеть, почему приложение упало.
 */
object CrashLogger {

    private var eventLog: EventLog? = null

    fun install(log: EventLog) {
        eventLog = log
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

    /**
     * Записать некритичный сбой в файл рядом с журналом. На магнитоле нет
     * logcat, поэтому иначе такая ошибка остаётся невидимой: файл попадает
     * в экспорт вместе с crash-файлами.
     */
    fun record(error: Throwable, source: String) {
        val log = eventLog ?: return
        runCatching {
            val ts = System.currentTimeMillis()
            File(log.logsDir, "crash-$ts.txt").writeText(
                buildString {
                    appendLine("time=$ts")
                    appendLine("source=$source")
                    appendLine()
                    append(error.stackTraceToString())
                },
                Charsets.UTF_8,
            )
        }
    }
}
