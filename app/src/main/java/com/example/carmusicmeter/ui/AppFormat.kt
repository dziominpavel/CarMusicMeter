package com.example.carmusicmeter.ui

import com.example.carmusicmeter.data.LogEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatMs(ms: Long?): String {
    if (ms == null || ms < 0) return "—"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun formatClock(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

fun reasonLabel(reason: Any?): String = when (reason?.toString()) {
    "completed" -> "дослушан"
    "skipped" -> "пропущен"
    "interrupted" -> "прерван"
    "stopped" -> "остановлен"
    "session_lost" -> "сессия пропала"
    "service_stopped" -> "служба остановлена"
    else -> reason?.toString() ?: "?"
}

fun LogEvent.summary(): String = when (type) {
    "track_started" ->
        "▶ ${fields["artist"] ?: "?"} — ${fields["title"] ?: "?"}"
    "track_ended" ->
        "${reasonLabel(fields["endReason"])} • ${formatMs((fields["playedMs"] as? Number)?.toLong())}"
    "volume_changed" ->
        "громкость ${fields["previous"]} → ${fields["value"]} (${signedNumber(fields["delta"])})"
    "playback_paused" -> "пауза"
    "playback_resumed" -> "продолжение"
    "playback_stopped" -> "остановка"
    "seek" ->
        "перемотка ${formatMs((fields["fromMs"] as? Number)?.toLong())} → " +
            formatMs((fields["toMs"] as? Number)?.toLong())
    "notification" ->
        "уведомление ${fields["package"]}: ${fields["title"] ?: fields["text"] ?: ""}"
    "app_crash" ->
        "падение: ${fields["message"] ?: fields["exception"] ?: "?"}"
    "boot_received" -> "загрузка: ${fields["action"]}"
    "notification_listener_connected" -> "доступ к уведомлениям включён"
    "notification_listener_disconnected" -> "доступ к уведомлениям выключен"
    "service_start_failed" -> "не удалось запустить службу: ${fields["error"]}"
    else -> fields.entries.take(2).joinToString(" · ") { "${it.key}=${it.value}" }
}

private fun signedNumber(value: Any?): String {
    val number = (value as? Number)?.toInt() ?: return "?"
    return if (number > 0) "+$number" else number.toString()
}
