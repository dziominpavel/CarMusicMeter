package com.example.carmusicmeter.data

import android.media.AudioManager

data class AudioStreamInfo(
    val name: String,
    val index: Int,
)

/**
 * Диагностика громкости: на некоторых магнитолах физическая ручка управляет
 * MCU/DSP, а `STREAM_MUSIC` в Android остаётся на максимуме. Снимок должен
 * показать все потоки, чтобы это отличить.
 */
object AudioDiagnostics {

    val STREAMS = listOf(
        AudioStreamInfo("music", AudioManager.STREAM_MUSIC),
        AudioStreamInfo("voice_call", AudioManager.STREAM_VOICE_CALL),
        AudioStreamInfo("system", AudioManager.STREAM_SYSTEM),
        AudioStreamInfo("notification", AudioManager.STREAM_NOTIFICATION),
        AudioStreamInfo("alarm", AudioManager.STREAM_ALARM),
        AudioStreamInfo("ring", AudioManager.STREAM_RING),
        AudioStreamInfo("accessibility", AudioManager.STREAM_ACCESSIBILITY),
    )

    fun snapshot(
        volume: (Int) -> Int,
        max: (Int) -> Int,
        isVolumeFixed: Boolean,
        audioMode: Int,
        isMusicActive: Boolean,
    ): Map<String, Any?> = linkedMapOf(
        "volumes" to STREAMS.map { stream ->
            linkedMapOf(
                "name" to stream.name,
                "stream" to stream.index,
                "value" to volume(stream.index),
                "max" to max(stream.index),
            )
        },
        "isVolumeFixed" to isVolumeFixed,
        "audioMode" to audioMode,
        "isMusicActive" to isMusicActive,
    )
}
